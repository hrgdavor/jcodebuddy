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
}
