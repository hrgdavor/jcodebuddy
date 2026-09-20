package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates {@code <View>Builder} — the mutable, untracked materialization
 * (plan.dsflash § 8.5/3.13).
 *
 * <h3>Shape</h3>
 * <p>A concrete class with one mutable field per view field, accessors, fluent setters
 * <strong>for writable fields only</strong> (S1), {@code Object get(int)}, {@code void set(int,Object)},
 * {@code int set(String,Object)} with the verified {@code -1} probe contract (D5), and
 * {@code build()}.</p>
 *
 * <h3>What {@code build()} returns</h3>
 * <p>The concrete materialization, not a proxy: the view's nested {@code Record} when the view
 * declares one ({@code PersonSummary.Record}), otherwise the emitted top-level
 * {@code <View>Record}. This is where the levels compose — {@code BUILDER} sits above {@code RECORD}
 * on the ladder, so the builder must produce the record the level below provides rather than fall
 * back to an array-backed proxy.</p>
 *
 * <h3>S1 asymmetry</h3>
 * <p>A {@code DERIVED}/{@code JOINED} field keeps its mutable field, its read accessor and its
 * ordinal in {@code get(int)} — only the <em>write</em> surface shrinks. The hand-written example
 * contradicts this today (it declares setters for {@code age} and {@code departmentName}); under S1
 * the regenerated builder drops them and their {@code set(int)}/{@code set(String)} arms, while the
 * fields stay in the record and the positional array.</p>
 */
public final class ViewBuilderGenerator {

    private ViewBuilderGenerator() {
    }

    /** What the generator produced, for tests and diagnostics. */
    public record Result(Path builderFile, String builderClass, List<String> writableFields) {
    }

    /** The name of the plain builder emitted for a view. Refactor-sensitive (§ 8.7/3.22). */
    public static String builderClassName(String viewName) {
        return viewName + "Builder";
    }

    /**
     * @param outputRoot    the java source root
     * @param packageName   the view's package
     * @param view          the view
     * @param allProperties the view's full, resolved property list in ordinal order
     * @param nestedRecord  {@code true} when the view declares its own matching nested record, so
     *                      {@code build()} targets {@code View.Record} rather than the emitted one
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view,
                                  List<Property> allProperties, boolean nestedRecord) throws IOException {
        return generate(outputRoot, packageName, view, allProperties, nestedRecord, null);
    }

    /**
     * As {@link #generate(Path, String, ViewMeta, List, boolean)}, reporting to a divergence sink.
     *
     * <p>The sink receives the {@code missing_setter} diagnostic of § 8.7/3.20: a field that is
     * writable by S1 but has no setter in the file being replaced. It matters because the emitters
     * also <em>preserve</em> user members now, so "the previous revision had no setter for a writable
     * field" can be a deliberate user deletion rather than a stale file — either way the reader
     * should be told which it is instead of the generator silently re-adding or silently skipping.</p>
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view,
                                  List<Property> allProperties, boolean nestedRecord,
                                  DivergenceReporter divergences) throws IOException {
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));

        List<Property> writable = allProperties.stream().filter(ViewAdapterGenerator::isWritable).toList();
        String builderClass = builderClassName(view.name());
        Path builderFile = packageDir.resolve(builderClass + ".java");
        if (divergences != null) {
            reportMissingSetters(builderFile, builderClass, view.name(), writable, divergences);
        }
        // § 8.7/3.19 (DEC-020) + the follow-up plan's § 1.1: reconcile the previous revision member by
        // member — a member the emitter does not produce is carried through, an edit to one it does
        // produce is reported. A RETIRED field's members are excluded: the generator used to emit its
        // setter and deliberately stopped (R1.4's "a retired field gets no setter"), so carrying it back
        // would resurrect a contract the tombstone exists to remove.
        java.util.Set<String> retired = allProperties.stream()
                .filter(ViewAdapterGenerator::isRetired)
                .map(Property::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        CooperativeCodegen.Reconciled reconciled = CooperativeCodegen.reconcileMembers(
                builderFile, builderClass,
                source(packageName, view, allProperties, writable, builderClass, nestedRecord),
                CooperativeCodegen.Reconciliation.ALL, retired);
        if (divergences != null) {
            divergences.addAll(reconciled.divergences());
        }
        Files.writeString(builderFile, reconciled.source());
        return new Result(builderFile, builderClass, writable.stream().map(Property::name).toList());
    }

    /**
     * Reports a writable field the existing builder file has no setter for.
     *
     * <p>Recognition is a <strong>one-parameter</strong> method named after the field. The arity is
     * what makes this a setter check rather than a name check: the same class also declares a no-arg
     * read accessor for every field, so matching on the name alone would find `lastName()` and
     * conclude the setter is present — which is precisely the mistake this check exists to catch.</p>
     */
    static void reportMissingSetters(Path builderFile, String builderClass, String viewName,
                                     List<Property> writable, DivergenceReporter divergences)
            throws IOException {
        if (!Files.exists(builderFile)) {
            return;
        }
        java.util.Set<String> setters = new java.util.LinkedHashSet<>();
        // The shared, fail-safe read (SourceReader): an error-tolerant partial parse of a broken
        // builder could report every setter as missing — a wall of false positives about a file
        // nobody can read, which is worse than saying nothing about it.
        SourceReader.Read read = SourceReader.read(builderFile);
        if (!read.readable()) {
            SourceReader.reportUnparseable(divergences, "source_not_parsed",
                    builderClass,
                    "the existing builder could not be parsed, so its setters are unknown",
                    "verify the setters: the file was left exactly as it is");
            return;
        }
        com.github.javaparser.ast.CompilationUnit cu = read.unit();
        for (com.github.javaparser.ast.body.MethodDeclaration method
                : cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {
            if (method.getParameters().size() == 1) {
                setters.add(method.getNameAsString());
            }
        }
        for (Property property : writable) {
            if (!setters.contains(property.name())) {
                divergences.report("missing_setter",
                        builderClass + "." + property.name(),
                        "the field is writable (FieldKind.COLUMN) and the existing builder has no "
                                + "one-argument method of that name",
                        "absent",
                        "a fluent setter " + property.name() + "(" + property.type() + ")",
                        "emit it, or restore the setter by hand if its deletion was deliberate");
            }
        }
    }

    private static String source(String packageName, ViewMeta view, List<Property> allProperties,
                                 List<Property> writable, String builderClass, boolean nestedRecord) {
        String viewName = view.name();
        StringBuilder sb = new StringBuilder();

        String fqn = packageName == null || packageName.isBlank() ? viewName : packageName + "." + viewName;
        sb.append("// {@link ").append(fqn).append("} Mutable builder for the ")
                .append(viewName).append(" view.\n");
        sb.append("// {enabled:true, blockMarker: \"implicit\"}\n");
        sb.append("package ").append(packageName).append(";\n\n");

        for (String importName : JdkImportSupport.importsFor(allProperties)) {
            sb.append("import ").append(importName).append(";\n");
        }
        for (String importName : ValidationGenerator.importsFor(allProperties)) {
            sb.append("import ").append(importName).append(";\n");
        }
        if (!JdkImportSupport.importsFor(allProperties).isEmpty()
                || !ValidationGenerator.importsFor(allProperties).isEmpty()) {
            sb.append('\n');
        }

        sb.append("/**\n");
        sb.append(" * A mutable copy of a {@link ").append(viewName).append("}. Setters exist only for\n");
        sb.append(" * writable fields: a {@code DERIVED}/{@code JOINED} field stays readable and keeps its\n");
        sb.append(" * ordinal, but has no setter (S1).\n");
        sb.append(" */\n");
        sb.append("public final class ").append(builderClass).append(" {\n\n");

        for (Property property : allProperties) {
            for (hr.hrg.hipster.entity.tooling.meta.FieldConstraint constraint : property.constraints()) {
                // § 12.3/7.10: the builder's field carries the same constraints as the record's
                // component, so a caller validating a partially-filled builder sees the same contract.
                sb.append("    ").append(constraint.annotation()).append('\n');
            }
            sb.append("    private ").append(ViewTrackingBuilderGenerator.boxedType(property.type()))
                    .append(' ').append(property.name()).append(";\n");
        }
        sb.append('\n');

        // Copy constructor from the source view: a builder without a starting point is unusable.
        sb.append("    /** Copies every field from a source view. */\n");
        sb.append("    public ").append(builderClass).append('(').append(viewName).append(" source) {\n");
        for (Property property : allProperties) {
            if (ViewAdapterGenerator.isRetired(property)) {
                // R1.4: the slot survives but its accessor does not, so there is nothing to copy from.
                // Emitting `source.<name>()` here does not compile — which is exactly how the missing
                // ordinal plumbing first announced itself.
                continue;
            }
            sb.append("        this.").append(property.name()).append(" = source.")
                    .append(property.name()).append("();\n");
        }
        sb.append("    }\n\n");

        // A no-arg constructor, so a builder can be filled from scratch.
        sb.append("    /** An empty builder. */\n");
        sb.append("    public ").append(builderClass).append("() {\n    }\n\n");

        for (Property property : allProperties) {
            if (ViewAdapterGenerator.isRetired(property)) {
                // The slot is reachable positionally through get(int) and build(); a named accessor
                // for a field the view no longer declares would only invite use.
                continue;
            }
            sb.append("    public ").append(ViewTrackingBuilderGenerator.boxedType(property.type()))
                    .append(' ').append(property.name()).append("() {\n");
            sb.append("        return ").append(property.name()).append(";\n");
            sb.append("    }\n\n");
        }

        sb.append("    /** Positional read, matching the ordinal contract. */\n");
        sb.append("    public Object get(int fieldOrdinal) {\n");
        sb.append("        return switch (fieldOrdinal) {\n");
        for (int i = 0; i < allProperties.size(); i++) {
            sb.append("            case ").append(i).append(" -> ").append(allProperties.get(i).name()).append(";\n");
        }
        sb.append("            default -> null;\n");
        sb.append("        };\n");
        sb.append("    }\n\n");

        // Fluent setters for writable fields only.
        for (Property property : writable) {
            String type = ViewTrackingBuilderGenerator.boxedType(property.type());
            sb.append("    public ").append(builderClass).append(' ').append(property.name())
                    .append('(').append(type).append(" value) {\n");
            sb.append("        this.").append(property.name()).append(" = value;\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        }

        // Positional mutator: writable ordinals only.
        sb.append("    /** Positional write. A non-writable ordinal is rejected (S1). */\n");
        sb.append("    public void set(int fieldOrdinal, Object value) {\n");
        if (writable.isEmpty()) {
            sb.append("        throw new UnsupportedOperationException(\n");
            sb.append("                \"").append(viewName).append(" has no writable field\");\n");
        } else {
            sb.append("        switch (fieldOrdinal) {\n");
            for (Property property : writable) {
                int ordinal = allProperties.indexOf(property);
                String type = ViewTrackingBuilderGenerator.boxedType(property.type());
                sb.append("            case ").append(ordinal).append(" -> this.").append(property.name())
                        .append(" = (").append(type).append(") value;\n");
            }
            sb.append("            default -> throw new UnsupportedOperationException(\n");
            sb.append("                    \"Field \" + fieldOrdinal + \" is not writable on ")
                    .append(viewName).append("\");\n");
            sb.append("        }\n");
        }
        sb.append("    }\n\n");

        // Name mutator with the -1 probe contract (D5).
        sb.append("    /**\n");
        sb.append("     * Name write, returning the ordinal written or {@code -1} for an unknown field.\n");
        sb.append("     * The low-level builders probe a name and report positionally; the user-facing\n");
        sb.append("     * proxy is the layer that turns {@code -1} into an exception (D5).\n");
        sb.append("     */\n");
        sb.append("    public int set(String field, Object value) {\n");
        sb.append("        switch (field) {\n");
        for (Property property : writable) {
            int ordinal = allProperties.indexOf(property);
            String type = ViewTrackingBuilderGenerator.boxedType(property.type());
            sb.append("            case \"").append(property.name()).append("\" -> {\n");
            sb.append("                this.").append(property.name()).append(" = (").append(type).append(") value;\n");
            sb.append("                return ").append(ordinal).append(";\n");
            sb.append("            }\n");
        }
        sb.append("            default -> {\n");
        sb.append("                return -1;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // build(): the concrete materialization from the level below.
        sb.append("    /** Builds the immutable view. */\n");
        sb.append("    public ").append(viewName).append(" build() {\n");
        sb.append("        return new ");
        sb.append(nestedRecord ? viewName + ".Record" : ViewRecordGenerator.recordClassName(viewName));
        sb.append('(');
        for (int i = 0; i < allProperties.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(allProperties.get(i).name());
        }
        sb.append(");\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }
}
