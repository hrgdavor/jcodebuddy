package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the {@code RECORD} level: a concrete, immutable materialization of a view
 * (plan.dsflash § 8.4/3.10–3.11).
 *
 * <h3>Shape</h3>
 * <p>A top-level {@code <View>Record} whose component list <strong>is</strong> the field enum order:
 * {@code record PersonSummaryRecord(Long id, String firstName, …) implements PersonSummary}. The
 * record's generated accessors satisfy the view interface by name, so no proxy is needed and reads
 * are direct method calls the IDE can follow (AGENTS.md § 1 / DEC-019).</p>
 *
 * <h3>Why a top-level record and not a nested one</h3>
 * <p>A nested {@code PersonSummary.Record} is what the hand-written example carries, and § 8.4/3.10
 * says explicitly: if a nested record already exists in hand-written source, <strong>do not emit a
 * second one</strong> — recognise it by shape and generate {@code create()} against it instead.
 * {@link #hasNestedRecord} answers that question from the parsed view, and the generator only emits
 * this top-level record when the answer is no.</p>
 *
 * <h3>Null policy (S4)</h3>
 * <p>{@code create(Object[])} must accept {@code null} for any slot — in particular for a
 * {@code DERIVED}/{@code JOINED} field, which never arrives over the wire. A record component is a
 * reference type (primitives are boxed), so a null assignment is legal; there is deliberately no
 * "missing required field" rejection in this release.</p>
 */
public final class ViewRecordGenerator {

    private ViewRecordGenerator() {
    }

    /** What the generator produced, for tests and diagnostics. */
    public record Result(Path recordFile, String recordClass, String creatorBody) {
    }

    /**
     * The name of the record class emitted for a view.
     *
     * <p>Refactor-<strong>sensitive</strong>: it is derived from the view's type name, so a rename
     * refactor must reach it. The emitted {@code {@link}} header line is what wires that relationship
     * for an IDE (§ 8.7/3.22).</p>
     */
    public static String recordClassName(String viewName) {
        return viewName + "Record";
    }

    /**
     * The creator expression {@code META} must use at this level: a positional {@code new} of the
     * concrete record, with one explicit cast per component.
     *
     * <p>The cast is required because the ordinal array is {@code Object[]}: without it the record
     * constructor would not resolve for a component whose type is not {@code Object}. The cast also
     * makes the positional mapping visible in the emitted source, which is the source-visible
     * property DEC-019 asks for.</p>
     */
    public static String creatorBody(ViewMeta view, List<Property> allProperties) {
        StringBuilder sb = new StringBuilder();
        sb.append("(Object[] values) -> new ").append(recordClassName(view.name())).append('(');
        for (int i = 0; i < allProperties.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(ViewTrackingBuilderGenerator.boxedType(allProperties.get(i).type()))
                    .append(") values[").append(i).append(']');
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Whether the view already declares a nested {@code record} whose component list matches the
     * field order — in which case {@code create()} targets that record and nothing is emitted
     * (§ 8.4/3.10).
     *
     * @param nestedRecordComponents the component names of a nested record, or {@code null} when
     *                               the view declares none
     * @param fieldNames             the view's resolved field names in ordinal order
     */
    public static boolean hasNestedRecord(List<String> nestedRecordComponents, List<String> fieldNames) {
        return nestedRecordComponents != null && nestedRecordComponents.equals(fieldNames);
    }

    /**
     * Emits the top-level record for a view at {@code RECORD} or above.
     *
     * @param outputRoot    the java source root
     * @param packageName   the view's package
     * @param view          the view
     * @param allProperties the view's full, resolved property list in ordinal order
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view, List<Property> allProperties)
            throws IOException {
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);

        String recordClass = recordClassName(view.name());
        Path recordFile = packageDir.resolve(recordClass + ".java");
        // Cooperative preservation (DEC-020, § 8.7/3.19, and the follow-up plan's § 1.1): the record is
        // emitted whole, and the previous revision is reconciled member by member — a nested type or a
        // helper the developer added is carried over verbatim, an edit to a generated member is
        // reported.
        CooperativeCodegen.Reconciled reconciled = CooperativeCodegen.reconcileMembers(
                recordFile, recordClass, source(packageName, view, allProperties, recordClass));
        Files.writeString(recordFile, reconciled.source());
        return new Result(recordFile, recordClass, creatorBody(view, allProperties));
    }

    private static String source(String packageName, ViewMeta view, List<Property> allProperties, String recordClass) {
        StringBuilder sb = new StringBuilder();
        String fqn = packageName == null || packageName.isBlank() ? view.name() : packageName + "." + view.name();
        sb.append("// {@link ").append(fqn).append("} Immutable record materialization of the ")
                .append(view.name()).append(" view.\n");
        sb.append("// {enabled:true, blockMarker: \"implicit\"}\n");
        sb.append("package ").append(packageName).append(";\n\n");

        for (String importName : jdkImports(allProperties)) {
            sb.append("import ").append(importName).append(";\n");
        }
        for (String importName : ValidationGenerator.importsFor(allProperties)) {
            sb.append("import ").append(importName).append(";\n");
        }
        if (!jdkImports(allProperties).isEmpty() || !ValidationGenerator.importsFor(allProperties).isEmpty()) {
            sb.append('\n');
        }

        sb.append("/**\n");
        sb.append(" * An immutable {@link ").append(view.name()).append("} whose component order is the field\n");
        sb.append(" * enum order, so {@code values[field.ordinal()]} maps 1:1 onto the constructor.\n");
        sb.append(" *\n");
        sb.append(" * <p>Constraint annotations are carried over from the view's accessors (plan.dsflash\n");
        sb.append(" * § 12.3/7.10), so a Bean Validation provider at the boundary sees exactly what the\n");
        sb.append(" * author declared on the view. A constraint the generator could not apply is reported\n");
        sb.append(" * as a divergence rather than dropped in silence.</p>\n");
        sb.append(" */\n");
        sb.append("public record ").append(recordClass).append("(\n");
        for (int i = 0; i < allProperties.size(); i++) {
            Property property = allProperties.get(i);
            for (hr.hrg.hipster.entity.tooling.meta.FieldConstraint constraint : property.constraints()) {
                sb.append("        ").append(constraint.annotation()).append('\n');
            }
            sb.append("        ").append(ViewTrackingBuilderGenerator.boxedType(property.type()))
                    .append(' ').append(property.name())
                    .append(i == allProperties.size() - 1 ? ")\n" : ",\n");
        }
        sb.append("        implements ").append(view.name()).append(" {\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** The JDK imports the component types need; see the same resolution in the builder generator. */
    private static List<String> jdkImports(List<Property> allProperties) {
        return JdkImportSupport.importsFor(allProperties);
    }
}
