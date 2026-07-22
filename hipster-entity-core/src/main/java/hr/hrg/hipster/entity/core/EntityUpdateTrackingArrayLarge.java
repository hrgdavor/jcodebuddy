package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ForNameOrdinal;

/**
 * Update-tracking array view for enums with > 64 values.
 * The {@code changes} field is typed as the concrete {@link EEnumSetBuilderLarge} so
 * all {@link #mark}/{@link #clear} calls are statically dispatched by the JVM.
 */
public final class EntityUpdateTrackingArrayLarge<T, F extends Enum<F> & FieldDef>
        extends EntityUpdateTrackingArray<T, F> {

    private final EEnumSetBuilderLarge<F> changes;

    EntityUpdateTrackingArrayLarge(ForNameOrdinal forNameOrdinal, int fieldCount, Object[] values) {
        super(forNameOrdinal, fieldCount, values);
        this.changes = new EEnumSetBuilderLarge<>(null); // Replace null with appropriate field type if needed
    }

    @Override
    public boolean mark(int ordinal) {
        return changes.addOrdinal(ordinal);
    }

    @Override
    public boolean unmark(int ordinal) {
        return changes.removeOrdinal(ordinal);
    }

    @Override
    public void clear() {
        changes.clear();
    }

    @Override
    public EEnumSet<F> changesSnapshot() {
        return changes.toImmutable();
    }

    @Override
    public EEnumSetBuilder<F> getChanges() {
        return changes;
    }

    /** Direct access to the concrete builder — no interface overhead. */
    public EEnumSetBuilderLarge<F> getChangesLarge() {
        return changes;
    }
}
