// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code merge(ViewN other)} method a view builder gains (plan step 7.3, {@code todo.hipster-entity.md} § 7).
 *
 * <p><b>Why this is separate from {@link ViewMapperGenerator}.</b> A mapper converts a <em>source view</em> into a
 * <em>target view</em>: it constructs a different type, so every target field needs a home and a missing one is a
 * data-loss diagnostic. A merge is <b>builder-to-builder</b>: it copies the fields two views happen to share into a
 * builder that is already being built, so a field the other view does not have is simply not part of the merge and
 * must <em>not</em> be reported — "merge some fields and not others" is the feature, not a defect. Reusing the mapper
 * for this would have produced a diagnostic per unshared field and buried the one case that matters.
 *
 * <p><b>The one thing that is a diagnostic</b> is a field that matches <b>by name but not by type</b>: copying it
 * would need a conversion, and a silent narrowing (or a silent widening that loses {@code null}) is the class of bug
 * this repository refuses to generate. It is reported in DEC-022's format and <b>not</b> merged, so what is emitted is
 * always exactly what the compiler proves.
 */
public final class ViewMergeGenerator {

    private ViewMergeGenerator() {
    }

    /**
     * A view a builder can merge from.
     *
     * @param simpleName     the view's simple name, which is what the emitted parameter type uses
     * @param qualifiedName  the fully qualified name, for the {@code {@link}} in the emitted javadoc
     * @param properties     the partner's properties in ledger order
     */
    public record Partner(String simpleName, String qualifiedName, List<Property> properties) {

        public Partner {
            properties = List.copyOf(properties);
        }
    }

    /**
     * What one merge would copy, and what it refused.
     *
     * @param merged         the fields copied, in the host's own property order
     * @param typeMismatched the fields that matched by name only, with the two declared types
     */
    public record Plan(String partnerSimpleName, List<String> merged, List<String> typeMismatched) {
    }

    /**
     * Decide what a merge of {@code partner} into {@code hostProperties} copies, reporting every mismatch.
     *
     * <p>Only the host's <b>writable, non-retired</b> fields are candidates: a retired tombstone has no accessor to
     * read it with and no setter to write it through (R1.4), so offering it in a merge would emit code that cannot
     * compile.
     *
     * @param builderClass  the builder that will carry the method, used in the diagnostic's location
     * @param hostViewName  the host view's name, for the same reason
     * @param divergences   where the DEC-022 diagnostics go; may be {@code null} to stay silent
     */
    public static Plan plan(String builderClass, String hostViewName, List<Property> hostProperties,
                            Partner partner, DivergenceReporter divergences) {
        List<String> merged = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        String location = builderClass + ".merge";
        for (Property host : hostProperties) {
            if (ViewAdapterGenerator.isRetired(host) || !ViewAdapterGenerator.isWritable(host)) {
                continue;
            }
            Property other = findByName(partner.properties(), host.name());
            if (other == null || ViewAdapterGenerator.isRetired(other)) {
                // Not shared: this is what "merge some fields and not others" means, and reporting it would make the
                // normal case look like a fault. The mapper reports the same shape because there every target field
                // needs a value; here nothing is lost by leaving the builder's own field alone.
                continue;
            }
            if (!host.type().equals(other.type())) {
                mismatched.add(host.name());
                if (divergences != null) {
                    divergences.report("merge_field_type_mismatch", location + "." + host.name(),
                            "the two views declare this field with different types, so copying it needs a conversion",
                            host.type(), other.type(),
                            "rename one of them, align the types, or merge that field by hand");
                }
                continue;
            }
            merged.add(host.name());
        }
        if (merged.isEmpty() && !hostProperties.isEmpty() && divergences != null) {
            // A merge that copies nothing is almost always a mistake in the request rather than an intentional
            // no-op, and silence would look like the generator ignored the flag.
            divergences.report("merge_copies_nothing", location,
                    "no field of " + hostViewName + " matches " + partner.simpleName()
                            + " by name and type, so the merge would do nothing",
                    "0 fields", "at least one shared field",
                    "check the two views, or drop the merge request");
        }
        return new Plan(partner.simpleName(), List.copyOf(merged), List.copyOf(mismatched));
    }

    /**
     * The method source for one merge: a null-safe copy of the planned fields, returning the builder.
     *
     * <p><b>A builder never returns a partially built instance</b> (the build-time rule this repository keeps), and a
     * merge respects it by construction: it sets fields on the builder and returns the builder, so the only way to
     * obtain an instance is still {@code build()}.
     *
     * <p>A {@code null} argument returns the builder unchanged rather than throwing: the field setters are generated
     * from the view's own contract, and {@code null} meaning "nothing to merge" is the composable reading — a caller
     * can write {@code Person.builder().merge(maybeDto).build()} without a null check at every call site.
     */
    public static String method(String builderClass, Plan plan) {
        StringBuilder source = new StringBuilder();
        source.append("    /**\n")
                .append("     * Copies every field this builder shares by <b>name and type</b> with {@link ")
                .append(plan.partnerSimpleName()).append("} (plan step 7.3).\n")
                .append("     *\n")
                .append("     * <p>Generated: a field the other view does not declare, or declares with a different\n")
                .append("     * type, is left exactly as this builder has it. The build-time rule still holds - a\n")
                .append("     * builder never returns a partially built instance, and this method returns the builder.\n")
                .append("     *\n")
                .append("     * @param other the view to copy from; {@code null} changes nothing\n")
                .append("     * @return this builder, so a merge composes with the setters\n")
                .append("     */\n");
        source.append("    public ").append(builderClass).append(" merge(")
                .append(plan.partnerSimpleName()).append(" other) {\n");
        source.append("        if (other == null) {\n");
        source.append("            return this;\n");
        source.append("        }\n");
        Set<String> emitted = new LinkedHashSet<>();
        for (String field : plan.merged()) {
            if (!emitted.add(field)) {
                continue;
            }
            source.append("        ").append(field).append("(other.").append(field).append("());\n");
        }
        source.append("        return this;\n");
        source.append("    }\n");
        return source.toString();
    }

    /** The partner's property of that name, or {@code null} - the whole of the name matching this generator does. */
    private static Property findByName(List<Property> properties, String name) {
        for (Property property : properties) {
            if (property.name().equals(name)) {
                return property;
            }
        }
        return null;
    }
}
