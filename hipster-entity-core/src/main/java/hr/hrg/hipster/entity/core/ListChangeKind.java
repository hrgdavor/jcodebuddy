package hr.hrg.hipster.entity.core;

/**
 * What kind of change happened to one entry of a tracked collection (plan.dsflash § 11/6.4).
 *
 * <p>A collection of tracked views reports two different things and they must stay
 * distinguishable: a <strong>structural</strong> change (an entry appeared, disappeared or moved)
 * and a <strong>field delta</strong> (an entry that is still at its position has a changed field).
 * This enum names the structural half; the field half is carried by {@link ListDelta#fieldChanges()}
 * and is present only for the kinds that still have an element to inspect.</p>
 *
 * <p>{@link #REPLACED} is the honest answer for the one case the tracker cannot resolve: an element
 * type that is not identifiable, where "an entry appeared here" and "a different entry appeared
 * here" cannot be told apart. It is always accompanied by a
 * {@link CollectionDiagnostic} — see {@link ListChangeTracker}.</p>
 */
public enum ListChangeKind {

    /** The entry at this index still has its baseline field values; no field delta. */
    UNCHANGED,

    /** A field inside the entry at this index changed; the entry itself did not move. */
    FIELD_CHANGED,

    /**
     * The entry was present in the baseline and is still present, but at a different index.
     * Only ever reported for an {@link hr.hrg.hipster.entity.api.Identifiable} element type.
     */
    REORDERED,

    /** An entry that was not in the baseline. Its {@code identity} is {@code null} when the element type is not identifiable. */
    ADDED,

    /** A baseline entry that is gone. Its {@code identity} is {@code null} when the element type is not identifiable. */
    REMOVED,

    /**
     * The entry at this index is not the baseline entry, and the element type is not identifiable,
     * so the tracker cannot say whether it was added, removed or merely swapped. The fallback kind
     * of {@link ListChangeTracker#matchedByIdentity()} {@code == false}.
     */
    REPLACED;
}
