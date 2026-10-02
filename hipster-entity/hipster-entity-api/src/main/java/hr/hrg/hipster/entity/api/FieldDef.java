package hr.hrg.hipster.entity.api;

import java.lang.reflect.Type;

/**
 * Implemented by all field-definition enums that describe the fields of an entity view.
 *
 * <h3>Naming contract</h3>
 * <p>The enum constant name <strong>must</strong> exactly match the zero-argument accessor method
 * name on the view interface. There is no mapping layer — {@code enum.name()} is the field name,
 * always. This mirrors the Java {@code record} convention where component names are fixed.
 * Callers use {@link Enum#name()} directly; no {@code methodName()} indirection exists.</p>
 *
 * <h3>Ordinal contract</h3>
 * <p>The enum ordinal doubles as the positional index into the backing array used by
 * array-backed view proxies: {@code values[field.ordinal()]} always holds the field value.
 * Enum constants must therefore be declared in a stable, agreed order and must never be
 * reordered without a corresponding migration.</p>
 */
public interface FieldDef {

    /**
     * The raw Java type of the field value.
     * Used for typed JSON deserialization, code generation, and type-safe dispatching.
     *
     * @return the field's Java type; never {@code null}
     */
    Type javaType();

    /**
     * The name of the field, which must match the zero-argument accessor method
     * name on the view interface.
     *
     * @return the field name; never {@code null}
     */
    String name();

    /**
     * The ordinal of the field, which doubles as the positional index into the backing array
     * used by array-backed view proxies: {@code values[field.ordinal()]} always holds the field value.
     * Actually just what will happen by default as it is an enum, but this makes it explicit and allows for future flexibility if needed.
     * 
     * @return the field ordinal; never negative
     */
    int ordinal();

    // ------------------------------------------------------------------
    // @FieldSource-derived accessors (DEC-019 / plan.dsflash X2).
    //
    // Every method below has a default that reproduces the behaviour of a field
    // with no @FieldSource annotation, so every existing hand-written and generated
    // enum keeps compiling unchanged. A generated enum overrides a method here only
    // when the source accessor actually carries @FieldSource.
    // ------------------------------------------------------------------

    /**
     * Classification of this field's data origin, which is what decides writability
     * (plan.dsflash S1: only {@link FieldKind#COLUMN} fields get setters).
     * <p>
     * An unannotated field is {@code COLUMN}, i.e. writable — the behaviour that
     * predates this accessor.
     */
    default FieldKind fieldKind() {
        return FieldKind.COLUMN;
    }

    /**
     * The database column name for this field, or {@code null} when the field is not a column.
     * <p>
     * The annotation's own {@code column()} is a label that "defaults to the method name when
     * empty"; that resolution lives here so generated adapters never re-implement it: an
     * implementation that carries {@code @FieldSource(column = "x")} returns {@code "x"}, one
     * that carries {@code @FieldSource} with an empty column returns the field name, and a
     * field with no annotation (or a non-COLUMN field) returns {@code null}.
     *
     * @return the SQL column name, or {@code null} when this field has none
     */
    default String column() {
        return null;
    }

    /**
     * Relation path for a {@link FieldKind#JOINED} field (e.g. {@code "department.name"}).
     *
     * @return the relation path, or {@code null} when this field is not a joined field
     */
    default String relation() {
        return null;
    }

    /**
     * Expression or description for a {@link FieldKind#DERIVED} field (e.g. a SQL fragment).
     *
     * @return the expression, or {@code null} when this field is not a derived field
     */
    default String expression() {
        return null;
    }

    /**
     * Whether this constant is a <em>retired</em> field — an R1.4 tombstone kept only to
     * preserve ordinals after its accessor disappeared from the view.
     * <p>
     * This is the write-side gate that {@link #fieldKind()} cannot express: a tombstone has
     * no accessor left to carry {@code @FieldSource} and therefore defaults to
     * {@code COLUMN}, so without this flag a retired column would silently reappear in
     * generated SQL. Every generated writer skips a retired field; readers stay tolerant,
     * because the ordinal slot still exists and may hold either {@code null} (a row that no
     * longer carries the column) or a value (a row that still does).
     *
     * @return {@code true} only on an R1.4 tombstone constant
     */
    default boolean retired() {
        return false;
    }
}
