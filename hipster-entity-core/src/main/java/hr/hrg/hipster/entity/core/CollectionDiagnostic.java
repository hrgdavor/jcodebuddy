package hr.hrg.hipster.entity.core;

/**
 * A non-fatal finding raised while tracking a collection of views (plan.dsflash § 11/6.4).
 *
 * <p>There are two findings today, and both exist because reorder detection <em>is</em> "same
 * identity, different index":</p>
 *
 * <ul>
 *   <li>the element type is not {@link hr.hrg.hipster.entity.api.Identifiable}, so no identity can be
 *       matched at all;</li>
 *   <li>two entries carry the <em>same</em> identity, so an identity names more than one position.
 *       Matching would then attribute one entry's change to the other's index and invent a removal
 *       that never happened.</li>
 * </ul>
 *
 * <p>In both cases the tracker refuses to guess: it falls back to positional deltas, marks them
 * {@link ListDelta#fallback()}, and reports here. Nothing is resolved through a
 * {@code HashMap} of names — the identity objects are compared directly (DEC-016).</p>
 *
 * <p>An {@code ordinal} of {@code -1} means the diagnostic is not tied to a single field (it was
 * raised by a tracker used directly, without an array to name the field).</p>
 *
 * @param ordinal the field ordinal whose value is the collection, or {@code -1} when unknown
 * @param code    a stable machine-readable code — see {@link #NOT_IDENTIFIABLE}
 * @param message the human-readable explanation
 */
public record CollectionDiagnostic(int ordinal, String code, String message) {

    /** The element type of a tracked collection is not {@link hr.hrg.hipster.entity.api.Identifiable}. */
    public static final String NOT_IDENTIFIABLE = "collection_element_not_identifiable";

    /**
     * Two elements of a tracked collection report the same
     * {@link hr.hrg.hipster.entity.api.Identifiable#id()}.
     */
    public static final String DUPLICATE_IDENTITY = "collection_duplicate_identity";

    /** A diagnostic from a tracker that does not know which field it belongs to. */
    public static CollectionDiagnostic notIdentifiable(String message) {
        return new CollectionDiagnostic(-1, NOT_IDENTIFIABLE, message);
    }

    /** A duplicate-identity diagnostic from a tracker that does not know its field. */
    public static CollectionDiagnostic duplicateIdentity(Object identity) {
        return new CollectionDiagnostic(-1, DUPLICATE_IDENTITY,
                "two elements of a tracked collection report the identity " + identity
                        + ", so an index cannot be matched to an entry; reporting per-index deltas only");
    }

    /** The same diagnostic, bound to the field that holds the collection. */
    public CollectionDiagnostic atOrdinal(int ordinal) {
        return new CollectionDiagnostic(ordinal, code, message);
    }
}
