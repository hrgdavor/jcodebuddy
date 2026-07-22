package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ForNameOrdinal;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.ViewWriter;

import java.util.Objects;

/**
 * Base for update-tracking entity array views.
 * Use {@link #create} to obtain the correct concrete variant for the enum size.
 * Concrete subclasses hold their builder as a concrete type so the JVM can
 * statically dispatch (and inline) {@link #mark}/{@link #clear} on the hot path.
 */
public abstract class EntityUpdateTrackingArray<T, F extends Enum<F> & FieldDef> implements ViewWriter {

    protected final Object[] values;
    private ForNameOrdinal forNameOrdinal;

    @SuppressWarnings("unchecked")
    protected EntityUpdateTrackingArray(ForNameOrdinal forNameOrdinal, int fieldCount, Object[] values) {
        this.forNameOrdinal = forNameOrdinal;
        if (fieldCount != values.length) {
            throw new IllegalArgumentException("View field count and data provided lengths do not match");
        }
        this.values = values;   
    }

    /** Factory: picks {@link EntityUpdateTrackingArray64} or {@link EntityUpdateTrackingArrayLarge}. */
    public static <T, F extends Enum<F> & FieldDef>
    EntityUpdateTrackingArray<T, F> create(ForNameOrdinal forNameOrdinal, int fieldCount, Object... values) {
        return fieldCount <= 64
                ? new EntityUpdateTrackingArray64<>(forNameOrdinal, fieldCount, values)
                : new EntityUpdateTrackingArrayLarge<>(forNameOrdinal, fieldCount, values);
    }

    /** Mark field ordinal as changed. Returns {@code true} if the bit was newly set. */
    public abstract boolean mark(int ordinal);

    /** Unmark field ordinal. Returns {@code true} if the bit was cleared. */
    public abstract boolean unmark(int ordinal);

    /** Reset all change bits (call after flush). */
    public abstract void clear();

    /** Snapshot of currently marked fields as an immutable set. */
    public abstract EEnumSet<F> changesSnapshot();

    /** Access the underlying builder (interface-typed for generic callers). */
    public abstract EEnumSetBuilder<F> getChanges();

    @Override
    public Object get(int fieldOrdinal) {
        return values[fieldOrdinal];
    }

    @Override
    public void set(int fieldOrdinal, Object value) {
        if (fieldOrdinal < 0 || fieldOrdinal >= values.length) {
            throw new IndexOutOfBoundsException("Field ordinal out of bounds: " + fieldOrdinal);
        }
        if (fieldOrdinal == 0) {
            throw new UnsupportedOperationException("ID field is immutable in EntityUpdateTrackingArray");
        }

        Object previous = values[fieldOrdinal];
        if (Objects.equals(previous, value)) {
            return;
        }

        values[fieldOrdinal] = value;
        mark(fieldOrdinal);
    }

    @Override
    public int set(String fieldName, Object value) {
        int field  = forNameOrdinal.forNameOrdinal(fieldName);
        if (field == -1) {
            return -1; // field not found
        }
        set(field, value);
        return field;
    }
}
