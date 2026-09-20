package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.EEnumSetBuilder;
import hr.hrg.hipster.entity.core.EEnumSetBuilder64;

/**
 * The builder-side materialization of {@link TrackedNode} — the stand-in for a generated
 * {@code TrackedNodeBuilderTracking}, hand-written because a runtime app module must not depend on
 * the dev-time generator ({@code AGENTS.md} § 2).
 *
 * <p>It mirrors the generated shape: one typed field per column, one shared change builder, and a
 * positional {@code get}/{@code set} pair driven by the field enum.</p>
 */
public final class TrackedNodeBuilder implements TrackedNode {

    private final EEnumSetBuilder64<TrackedNode_> mf = new EEnumSetBuilder64<>(TrackedNode_.values());
    private Long id;
    private String label;

    public TrackedNodeBuilder(long id, String label) {
        // The identity is set through the positional setter, exactly as a generated builder does;
        // the change it records is then cleared, so the instance starts as an untouched baseline.
        set(TrackedNode_.id.ordinal(), id);
        set(TrackedNode_.label.ordinal(), label);
        mf.clear();
    }

    @Override
    public Long id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Object get(int fieldOrdinal) {
        return switch (fieldOrdinal) {
            case 0 -> id;
            default -> label;
        };
    }

    @Override
    public void set(int fieldOrdinal, Object value) {
        // The DEC-012 no-op rule lives at the write site: compare the value the field holds now,
        // mark the ordinal only when the two differ, and never retain the value being replaced
        // (ViewChangeTracking keeps no baseline; the caller's instance is the baseline).
        switch (fieldOrdinal) {
            case 0 -> {
                if (!java.util.Objects.equals(id, value)) {
                    mf.addOrdinal(0);
                }
                id = (Long) value;
            }
            default -> {
                if (!java.util.Objects.equals(label, value)) {
                    mf.addOrdinal(TrackedNode_.label.ordinal());
                }
                label = (String) value;
            }
        }
    }

    @Override
    public int set(String field, Object value) {
        TrackedNode_ def = TrackedNode_.forName(field);
        if (def == null) {
            return -1;
        }
        set(def.ordinal(), value);
        return def.ordinal();
    }

    @Override
    public boolean isChanged() {
        return !mf.isEmpty();
    }

    @Override
    public EEnumSet<TrackedNode_> changes() {
        return mf.toImmutable();
    }

    @Override
    public EEnumSetBuilder<TrackedNode_> changesBuilder() {
        return mf;
    }

    @Override
    public void clearChanges() {
        mf.clear();
    }

    @Override
    public Object currentValue(TrackedNode_ field) {
        return get(field.ordinal());
    }

    @Override
    public String toString() {
        return "TrackedNodeBuilder[" + id + " " + label + "]";
    }
}
