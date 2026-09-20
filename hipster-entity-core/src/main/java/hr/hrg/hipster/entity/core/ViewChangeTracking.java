package hr.hrg.hipster.entity.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The change-tracking contract shared by both materializations: the generated tracking builder
 * and the array-backed updatable proxy (plan.dsflash S5, § 4.1).
 *
 * <h3>One piece of state, two accessors</h3>
 * <p>{@link #changes()} is an <strong>immutable snapshot</strong> taken at call time;
 * {@link #changesBuilder()} is the <strong>live, mutable</strong> builder both views share. They
 * are two views over the same state, never two copies, so they cannot disagree — an
 * implementation that returns a copy from {@code changesBuilder()} violates this contract. The
 * acceptance test is the state-sharing test in {@code EntityUpdateTrackingArrayTest}: mutating
 * through {@code changesBuilder()} must be visible through a <em>later</em> {@code changes()}
 * call, while a snapshot captured <em>before</em> the mutation still reports the old state.</p>
 *
 * <p>Only {@code changesBuilder()} can mutate; {@code changes()} allocates when the set is
 * non-empty, so a hot path must use {@code changesBuilder()}. The empty case stays allocation-free
 * through {@link EEnumSetEmpty#of(Class)}.</p>
 *
 * <h3>What this contract does NOT keep</h3>
 * <p>A tracker records <strong>which</strong> fields changed. It does <strong>not</strong> keep the
 * value a field held before the write, and there is deliberately no accessor that would hand one
 * back: no {@code previousValue}, no {@code hasPreviousValue}, no previous/current pair anywhere in
 * this interface or in the change-set builder.</p>
 *
 * <p>The reason is ownership. The old value belongs to whoever still holds the instance the write
 * started from — a tracker is a <em>mutable</em> created from another mutable or from an immutable,
 * and that source is the caller's object, not the tracker's state. A consumer that wants an
 * old&nbsp;&rarr;&nbsp;new comparison is therefore <strong>given both instances by the caller</strong>
 * and compares them itself ({@code Objects.equals} on the accessors, or the view's own
 * {@code equals}); the library supplies {@link #changedValues()} for the new half and nothing for the
 * old half. Keeping a copy of the old value inside the tracker would duplicate state the caller
 * already owns, hold a stale shallow reference to a mutable value (the hazard the previous design had
 * to document), and make "did this change?" depend on two sources of truth.</p>
 *
 * <p>{@link #changes()} stays the authoritative answer to "what changed", and it is derived from the
 * writes that were actually performed: a write whose value differs from the one the field holds at
 * that moment marks the field. Writing the original value back afterwards leaves the field marked,
 * because relative to the state the tracker saw the field <em>was</em> written; a caller that needs
 * "differs from my baseline" asks the baseline it holds, which is exactly the comparison above.</p>
 *
 * <h3>Module location</h3>
 * <p>This interface deliberately stays in {@code hr.hrg.hipster.entity.core} (plan.dsflash S3/X1
 * deferred): its accessors are typed on the {@code EEnumSet*} family, whose concrete
 * implementations and static factories live here. A consuming project whose generated code
 * implements this interface therefore depends on {@code hipster-entity-core}, not on
 * {@code hipster-entity-api} alone.</p>
 *
 * <h3>Type parameters</h3>
 * <p>{@code S} is the {@link EEnumSet} <em>interface</em>, never the final class
 * {@link EEnumSet64}: {@link EEnumSetBuilder#toImmutable()} returns {@link EEnumSet} and yields
 * the cached {@link EEnumSetEmpty} singleton when the set is empty, and {@code EEnumSet64} is a
 * <em>sibling</em> of it rather than a supertype.</p>
 *
 * @param <E> the field-definition enum of the tracked view
 * @param <S> the immutable change-set type; {@link EEnumSet} in every implementation today
 */
public interface ViewChangeTracking<E extends Enum<E>, S extends EEnumSetRead<E>> {

    /** Whether any field was written with a value different from the one it held. */
    boolean isChanged();

    /** Immutable snapshot of the changed fields — safe to retain and iterate. */
    S changes();

    /** The live, mutable change set — a view over the same state, not a copy. */
    EEnumSetBuilder<E> changesBuilder();

    /** Reset the change set. No value baseline is involved, because none is kept. */
    void clearChanges();

    /** The value a changed field currently holds. */
    Object currentValue(E field);

    /**
     * One {@link FieldChange} per marked ordinal, in ascending ordinal order, each carrying the
     * value the field holds <em>now</em>. Empty when nothing changed.
     * <p>
     * There is no {@code previous} half by design (see the class comment): a consumer that needs the
     * old value is given the baseline instance by its caller and reads it there. Field names come
     * from the field constant itself, so no per-call name lookup table is allocated (DEC-016).
     */
    default List<FieldChange<E>> changedValues() {
        EEnumSetBuilder<E> builder = changesBuilder();
        List<FieldChange<E>> result = new ArrayList<>(builder.size());
        if (builder.isEmpty()) {
            return result;
        }
        builder.forEach((value, index) -> result.add(new FieldChange<>(value, currentValue(value))));
        return result;
    }

    /**
     * Every <strong>deep</strong> change path, one per changed leaf (plan.dsflash § 11/6.2, D4).
     *
     * <p>The model is <strong>pull</strong>: this walks into every field whose declared type is
     * itself a {@code ViewChangeTracking} and ORs the child's state into the reported result,
     * <em>without</em> mutating the parent's own bitset. Children therefore stay reusable and
     * shareable across parents, and there is no parent/child lifecycle coupling.</p>
     *
     * <p>Shallow tracking is unchanged and still free when nothing is nested: a view with no nested
     * trackable field returns the same information as {@link #shallowPaths()} and allocates one
     * {@link ChangePath} per marked ordinal, not per field (the DEC-014 "pay only for what you use"
     * discipline).</p>
     *
     * <p>Materializations that can see into their own fields override this. The default returns only
     * the shallow level, which is the correct answer for a view that nests nothing.</p>
     */
    default List<ChangePath> changesDeep() {
        return shallowPaths();
    }

    /**
     * The shallow paths — one leaf {@link ChangePath} per marked ordinal.
     *
     * <p>Distinct from {@link #changes()} and {@link #changesBuilder()}, which are the shallow
     * <em>change sets</em>: a path says <em>where</em>, a set says <em>which</em>. Both views are
     * explicit so a caller never has to guess which one a method returns.</p>
     */
    default List<ChangePath> shallowPaths() {
        EEnumSetBuilder<E> builder = changesBuilder();
        List<ChangePath> paths = new ArrayList<>(builder.size());
        if (builder.isEmpty()) {
            return paths;
        }
        // The cast is needed because E's bound is deliberately the loose `Enum<E>` (S3 deferred),
        // while ChangePath carries a FieldDef. Every field enum in the tree implements FieldDef;
        // the requirement is asserted at construction time by DefaultViewMeta's name-map check.
        builder.forEach((value, index) -> paths.add(ChangePath.of((hr.hrg.hipster.entity.api.FieldDef) value)));
        return paths;
    }

    /**
     * The nested trackers of this view, keyed by the field ordinal whose value is itself a tracked
     * view. Empty for a view that nests nothing.
     *
     * <p>Exposed as a method rather than a field so a generated builder can answer it from its own
     * typed fields, which is what keeps {@code changesDeep()} a concrete, navigable method body
     * instead of a reflection walk.</p>
     */
    default java.util.Map<Integer, ViewChangeTracking<?, ?>> nestedTrackers() {
        return java.util.Map.of();
    }

    /**
     * The changes to every field this view holds that is a <strong>collection of tracked views</strong>,
     * keyed by field ordinal (plan.dsflash § 11/6.4, recorded in {@code DEC-024}).
     *
     * <p>A collection reports two things {@link #changes()} cannot express: a structural change to
     * the collection itself (an entry added, removed or moved — none of which writes to any field of
     * the parent) and a per-entry field delta (an entry mutated in place, which writes to no field of
     * the parent either). {@link ListDelta} carries both and keeps them distinguishable.</p>
     *
     * <p>Empty for a view with no such field, which is the common case: the trackers exist only for
     * a field that actually holds a list of tracked views (DEC-014, "pay only for what you use").</p>
     */
    default java.util.Map<Integer, java.util.List<ListDelta>> collectionDeltas() {
        return java.util.Map.of();
    }

    /**
     * Whether the field at {@code ordinal} holds a tracked collection (<em>any</em> {@code List}
     * value, whether or not its elements are themselves tracked).
     *
     * <p>The distinction this answers cannot be read off {@link #collectionDeltas()}: a collection
     * that has not changed reports no deltas, and a collection whose elements are not tracked never
     * will. A consumer that has to tell "this field is a collection" from "this field is a scalar"
     * needs the structural answer, which is why it is a contract method rather than an inferred
     * convention. The array path answers from the trackers it holds; a generated builder answers from
     * the declared type of its own field.</p>
     */
    default boolean hasCollection(int ordinal) {
        return false;
    }

    /**
     * The collection findings that could not be expressed as a delta, in field-ordinal order.
     * Empty for every view whose tracked collections have an identifiable element type.
     *
     * <p>The one finding today is {@link CollectionDiagnostic#NOT_IDENTIFIABLE}: a collection whose
     * element type is not {@link hr.hrg.hipster.entity.api.Identifiable} cannot have its reorders
     * detected, so the tracker falls back to per-index deltas and says so here rather than guessing.
     * </p>
     *
     * @see ListChangeTracker
     */
    default java.util.List<CollectionDiagnostic> collectionDiagnostics() {
        return java.util.List.of();
    }
}
