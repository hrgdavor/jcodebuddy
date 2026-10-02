package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ForNameOrdinal;

/**
 * Update-tracking array view for enums with ≤ 64 values.
 * The {@code changes} field is typed as the concrete {@link EEnumSetBuilder64} so
 * all {@link #mark}/{@link #clear} calls are statically dispatched by the JVM.
 */
public final class EntityUpdateTrackingArray64<T, F extends Enum<F> & FieldDef>
        extends EntityUpdateTrackingArray<T, F> {

    private final EEnumSetBuilder64<F> changes;

    EntityUpdateTrackingArray64(ForNameOrdinal forNameOrdinal, F[] universe, int fieldCount, Object[] values) {
        this(forNameOrdinal, universe, fieldCount, values, java.util.Map.of());
    }

    EntityUpdateTrackingArray64(ForNameOrdinal forNameOrdinal, F[] universe, int fieldCount, Object[] values,
                                java.util.Map<Integer, ListChangeTracker> collectionTrackers) {
        super(forNameOrdinal, universe, fieldCount, values, collectionTrackers);
        this.changes = new EEnumSetBuilder64<>(universe);
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
        clearCollectionChanges();
    }

    @Override
    public EEnumSetBuilder<F> changesBuilder() {
        return changes;
    }
}
