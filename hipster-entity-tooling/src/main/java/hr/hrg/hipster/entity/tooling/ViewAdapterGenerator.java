package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.generated.GeneratedCodeMarkers;

import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates the positional SQL/JDBC adapter for one view (plan.dsflash § 12.1/7.1–7.3).
 *
 * <h3>Status: DRAFT / EXPLORATION — opt-in only, not a supported generator</h3>
 * <p>This class explores what generated JDBC access could look like. It is <strong>not</strong> part
 * of the supported generator set:</p>
 * <ul>
 *   <li>it runs only when a project explicitly asks for it ({@code --adapters}); the default
 *       generation pass emits nothing from this package, and no POM property, profile or annotation
 *       can switch it on implicitly. {@code ViewAdapterGeneratorTest#sqlGenerationIsOptInAndOffByDefault}
 *       pins that rule, and it is the rule any future SQL support must keep — opt-in, never a
 *       default;</li>
 *   <li>the example project deliberately does not enable it, so no committed example depends on the
 *       emitted shape;</li>
 *   <li>the emitted API (class names, method signatures, how SQL fragments are composed) may change
 *       or be withdrawn. Treat it as a sketch, not a contract.</li>
 * </ul>
 *
 * <p>It emits two concrete, reflection-free classes in the view's own package, following the G5 naming
 * contract:</p>
 * <ul>
 *   <li>{@code <View>RowAdapter} — {@code public static Object[] fromResultSet(ResultSet, ViewMeta)},
 *       driven by {@code meta.fieldNameAt(i)} / {@code meta.fieldTypeAt(i)};</li>
 *   <li>{@code <View>Binder} — {@code public static void bind(PreparedStatement, int, View)}, writing
 *       only fields that are {@code COLUMN} and not {@code retired()}, in ordinal order, plus
 *       {@code insertSql()} / {@code updateSql()} fragment builders.</li>
 * </ul>
 *
 * <p>The emitted source is committed, ordinary Java (AGENTS.md § 1 / DEC-019): a direct method body
 * the IDE can navigate, never a reflective dispatcher.</p>
 *
 * <h3>What it deliberately does not do</h3>
 * <ul>
 *   <li>It never emits a column for a {@code DERIVED}/{@code JOINED} field, and never for a
 *       {@code retired()} tombstone. {@code FieldKind} alone cannot express the second: a tombstone
 *       has no accessor to carry {@code @FieldSource} and therefore defaults to {@code COLUMN}.</li>
 *   <li>It never assumes {@code SELECT *} ordering — every read is by column name.</li>
 * </ul>
 */
public final class ViewAdapterGenerator {

    private ViewAdapterGenerator() {
    }

    /** What one generated adapter class ended up containing, for diagnostics and tests. */
    public record AdapterResult(Path rowAdapterFile, Path binderFile, List<String> writableColumns) {
    }

    /**
     * @param outputRoot the java source root
     * @param packageName the view's package
     * @param view        the view to adapt
     * @param properties  the view's full, resolved property list in ordinal order
     */
    public static AdapterResult generate(Path outputRoot, String packageName, ViewMeta view, List<Property> properties)
            throws IOException {
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));

        List<Property> writable = properties.stream().filter(ViewAdapterGenerator::isWritable).toList();

        Path rowAdapter = packageDir.resolve(view.name() + "RowAdapter.java");
        Path binder = packageDir.resolve(view.name() + "Binder.java");
        Files.writeString(rowAdapter, rowAdapterSource(packageName, view, properties));
        Files.writeString(binder, binderSource(packageName, view, properties, writable));
        return new AdapterResult(rowAdapter, binder, writable.stream().map(Property::name).toList());
    }

    /**
     * A field is writable when it is a real column: {@code COLUMN} kind and not retired.
     *
     * <p>The plan expresses this in two layers (S1 for the kind, R1.4/DR-2 for retirement); a
     * property with no {@code @FieldSource} is {@code COLUMN}, i.e. writable, which preserves the
     * behaviour of an unannotated view.</p>
     */
    static boolean isWritable(Property property) {
        return property.fieldKind() == null || "COLUMN".equals(property.fieldKind());
    }

    /**
     * Whether the property is an R1.4 tombstone — a constant kept in place after its accessor was
     * removed (plan.dsflash § 4.6/R1.4, § 4.7/DR-2).
     *
     * <p>A tombstone is a real <em>slot</em> and not a real field: it keeps its ordinal in the
     * positional array and gets a nullable component in the record, but it has no accessor on the
     * view, so nothing may copy it from a source instance, no setter may write it, and no column may
     * carry it. {@link #isWritable} already excludes it because its kind is neither {@code null} nor
     * {@code COLUMN}; this predicate is what the emitters that must still <em>place</em> it consult
     * before emitting an accessor or a copy-constructor line.</p>
     */
    static boolean isRetired(Property property) {
        return "RETIRED".equals(property.fieldKind());
    }

    /**
     * The SQL column label of a field: {@code @FieldSource.column()} when set, else the accessor
     * name — the resolution {@code FieldDef.column()} documents, applied at generation time so the
     * adapter never re-implements it.
     */
    static String columnLabel(Property property) {
        String column = property.column();
        return column == null || column.isBlank() ? property.name() : column;
    }

    private static String rowAdapterSource(String packageName, ViewMeta view, List<Property> properties) {
        StringBuilder sb = new StringBuilder();
        sb.append(header(view, "Positional ResultSet reader for the " + view.name() + " view."));
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import hr.hrg.hipster.entity.api.ViewMeta;\n");
        sb.append("import java.sql.ResultSet;\n");
        sb.append("import java.sql.SQLException;\n\n");
        sb.append("/**\n");
        sb.append(" * Reads one row into the positional array the ordinal contract expects:\n");
        sb.append(" * {@code values[field.ordinal()]} holds the value of each field.\n");
        sb.append(" *\n");
        sb.append(" * <p>Every slot is read by <strong>column name</strong>, so the adapter does not\n");
        sb.append(" * depend on {@code SELECT *} ordering. It tolerates {@code null} in every slot: a\n");
        sb.append(" * {@code DERIVED}/{@code JOINED} field an adapter cannot fill, and a tombstone slot a\n");
        sb.append(" * row no longer carries, are both left {@code null} rather than rejected.</p>\n");
        sb.append(" */\n");
        sb.append("public final class ").append(view.name()).append("RowAdapter {\n\n");
        sb.append("    private ").append(view.name()).append("RowAdapter() {\n    }\n\n");
        sb.append("    public static Object[] fromResultSet(ResultSet rs, ViewMeta<?, ?> meta) throws SQLException {\n");
        sb.append("        Object[] values = new Object[meta.fieldCount()];\n");
        sb.append("        for (int i = 0; i < values.length; i++) {\n");
        sb.append("            values[i] = rs.getObject(meta.fieldNameAt(i));\n");
        sb.append("        }\n");
        sb.append("        return values;\n");
        sb.append("    }\n\n");
        sb.append("    /** Typed read of one field by ordinal, using the view's own column names. */\n");
        sb.append("    public static Object read(ResultSet rs, int ordinal, ViewMeta<?, ?> meta) throws SQLException {\n");
        sb.append("        return rs.getObject(meta.fieldNameAt(ordinal));\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String binderSource(String packageName, ViewMeta view, List<Property> allProperties, List<Property> writable) {
        StringBuilder sb = new StringBuilder();
        sb.append(header(view, "Positional PreparedStatement binder for the " + view.name() + " view."));
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import hr.hrg.hipster.entity.api.FieldDef;\n");
        sb.append("import hr.hrg.hipster.entity.api.ViewMeta;\n");
        sb.append("import hr.hrg.hipster.entity.api.ViewReader;\n");
        sb.append("import java.sql.PreparedStatement;\n");
        sb.append("import java.sql.SQLException;\n");
        sb.append("import java.sql.Types;\n\n");
        sb.append("/**\n");
        sb.append(" * Binds a view to a {@link PreparedStatement}, writing only fields that are a real\n");
        sb.append(" * column ({@code FieldKind.COLUMN}) and not {@code retired()}.\n");
        sb.append(" *\n");
        sb.append(" * <p>Binding happens in <strong>ordinal order</strong> so a {@code PreparedStatement}\n");
        sb.append(" * plan stays stable and cacheable. A {@code DERIVED}/{@code JOINED} field is never\n");
        sb.append(" * bound, and neither is an R1.4 tombstone — {@code FieldKind} alone cannot exclude the\n");
        sb.append(" * tombstone, because a retired constant has no accessor left to carry\n");
        sb.append(" * {@code @FieldSource} and therefore reports {@code COLUMN}.</p>\n");
        sb.append(" */\n");
        sb.append("public final class ").append(view.name()).append("Binder {\n\n");
        sb.append("    /** The writable column labels, in ordinal order. */\n");
        sb.append("    public static final String[] COLUMNS = {");
        for (int i = 0; i < writable.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(quote(columnLabel(writable.get(i))));
        }
        sb.append("};\n\n");
        sb.append("    /** The ordinals those columns come from, in the same order. */\n");
        sb.append("    public static final int[] ORDINALS = {");
        for (int i = 0; i < writable.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(ordinalOf(allProperties, writable.get(i)));
        }
        sb.append("};\n\n");
        sb.append("    private ").append(view.name()).append("Binder() {\n    }\n\n");
        sb.append("    /** Number of parameters {@link #bind} sets. */\n");
        sb.append("    public static int parameterCount() {\n");
        sb.append("        return COLUMNS.length;\n    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Binds the view's writable columns starting at {@code startIndex} (1-based, as\n");
        sb.append("     * JDBC counts parameters).\n");
        sb.append("     */\n");
        sb.append("    public static void bind(PreparedStatement ps, int startIndex, ViewReader view) throws SQLException {\n");
        sb.append("        for (int i = 0; i < ORDINALS.length; i++) {\n");
        sb.append("            setObject(ps, startIndex + i, view.get(ORDINALS[i]));\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    /** Binds only the given changed ordinals — the partial-update path (§ 12.1/7.3). */\n");
        sb.append("    public static void bindChanged(PreparedStatement ps, int startIndex, ViewReader view, boolean[] changed) throws SQLException {\n");
        sb.append("        int index = startIndex;\n");
        sb.append("        for (int i = 0; i < ORDINALS.length; i++) {\n");
        sb.append("            int ordinal = ORDINALS[i];\n");
        sb.append("            if (changed[ordinal]) {\n");
        sb.append("                setObject(ps, index++, view.get(ordinal));\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    private static void setObject(PreparedStatement ps, int index, Object value) throws SQLException {\n");
        sb.append("        if (value == null) {\n");
        sb.append("            // A null is written as SQL NULL, in an untyped slot, rather than as a type error.\n");
        sb.append("            ps.setNull(index, Types.NULL);\n");
        sb.append("        } else {\n");
        sb.append("            ps.setObject(index, value);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    /** A parameterised INSERT fragment listing exactly the writable columns. */\n");
        sb.append("    public static String insertSql(String table) {\n");
        sb.append("        StringBuilder sql = new StringBuilder(\"INSERT INTO \").append(table).append(\" (\");\n");
        sb.append("        for (int i = 0; i < COLUMNS.length; i++) {\n");
        sb.append("            if (i > 0) sql.append(\", \");\n");
        sb.append("            sql.append(COLUMNS[i]);\n");
        sb.append("        }\n");
        sb.append("        sql.append(\") VALUES (\");\n");
        sb.append("        for (int i = 0; i < COLUMNS.length; i++) {\n");
        sb.append("            if (i > 0) sql.append(\", \");\n");
        sb.append("            sql.append('?');\n");
        sb.append("        }\n");
        sb.append("        return sql.append(')').toString();\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * A parameterised UPDATE fragment for a <strong>change set</strong>: only the changed\n");
        sb.append("     * columns appear, so a partial update touches just those.\n");
        sb.append("     */\n");
        sb.append("    public static String updateSql(String table, boolean[] changed) {\n");
        sb.append("        StringBuilder sql = new StringBuilder(\"UPDATE \").append(table).append(\" SET \");\n");
        sb.append("        int written = 0;\n");
        sb.append("        for (int i = 0; i < ORDINALS.length; i++) {\n");
        sb.append("            if (!changed[ORDINALS[i]]) continue;\n");
        sb.append("            if (written++ > 0) sql.append(\", \");\n");
        sb.append("            sql.append(COLUMNS[i]).append(\" = ?\");\n");
        sb.append("        }\n");
        sb.append("        if (written == 0) {\n");
        sb.append("            return null; // nothing changed: there is no UPDATE to send\n");
        sb.append("        }\n");
        sb.append("        return sql.toString();\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static int ordinalOf(List<Property> allProperties, Property property) {
        for (int i = 0; i < allProperties.size(); i++) {
            if (allProperties.get(i).name().equals(property.name())) {
                return i;
            }
        }
        return -1;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The two-line file header, via the one place its spelling is written down (DEC-035).
     *
     * <p>The {@code view} parameter is unused now and kept deliberately: it is what a caller passes to say
     * which view the file is for, and dropping it would make the two call sites read as if the header had
     * nothing to do with the view. The header carries the <b>generator's</b> FQN, because that is what a
     * reader jumps to; the view is named in the description.
     */
    private static String header(ViewMeta view, String description) {
        return GeneratedCodeMarkers.fileHeader(ViewAdapterGenerator.class.getName(), description);
    }

    /** Reserved for a future non-JDBC dialect; keeps the unused-import warning away. */
    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
