package hr.hrg.hipster.entity.core;

import java.util.List;

/**
 * One reported change to one entry of a tracked collection of views (plan.dsflash § 11/6.4,
 * recorded in {@code DEC-024}).
 *
 * <p>A {@code List<Address>} field does not change in one way; it changes in two, and a consumer
 * (a JSON patch, an audit log, an optimistic-locking check) needs to tell them apart:</p>
 *
 * <ul>
 *   <li>a <strong>field delta</strong> — the entry kept its position and identity, and a field
 *       inside it changed ({@link ListChangeKind#FIELD_CHANGED});</li>
 *   <li>a <strong>structural change</strong> — an entry was added, removed or moved
 *       ({@link ListChangeKind#ADDED}, {@link ListChangeKind#REMOVED},
 *       {@link ListChangeKind#REORDERED}).</li>
 * </ul>
 *
 * <p>The two are carried by one value type rather than two lists because they always answer the
 * same question ("what happened at this index?") and a caller that wants only one half can filter
 * on {@link #kind()}. {@link #fieldChanges()} is the field half, and it is empty exactly when there
 * is no field half to report: a structural change on an entry that was added or removed has no
 * previous element to compare against.</p>
 *
 * @param kind         what happened to the entry at {@code index}
 * @param index        the entry's index in the <em>current</em> list, or, for
 *                     {@link ListChangeKind#REMOVED}, the index it occupied in the baseline
 * @param previousIndex the index the entry occupied in the baseline, or {@code -1} when it was not
 *                     in the baseline (an addition) or when the element type is not identifiable
 * @param identity     the entry's identity when the element type is
 *                     {@link hr.hrg.hipster.entity.api.Identifiable}, otherwise {@code null}
 * @param fieldChanges the per-field deltas inside the entry; empty when there is nothing to compare
 * @param fallback     {@code true} when the element type is not identifiable, so this delta was
 *                     produced by the positional fallback rather than by identity matching
 */
public record ListDelta(ListChangeKind kind,
                        int index,
                        int previousIndex,
                        Object identity,
                        List<FieldChange<?>> fieldChanges,
                        boolean fallback) {

    public ListDelta {
        fieldChanges = fieldChanges == null ? List.of() : List.copyOf(fieldChanges);
    }

    /** A delta with no field-level content. */
    public static ListDelta structural(ListChangeKind kind, int index) {
        return new ListDelta(kind, index, -1, null, List.of(), false);
    }

    /** A field delta at {@code index}, carrying the entry's identity when it has one. */
    public static ListDelta fields(int index, Object identity, List<FieldChange<?>> fieldChanges) {
        return new ListDelta(ListChangeKind.FIELD_CHANGED, index, -1, identity, fieldChanges, false);
    }

    /** Whether this delta has a structural half. */
    public boolean isStructural() {
        return kind != ListChangeKind.UNCHANGED && kind != ListChangeKind.FIELD_CHANGED;
    }

    /** Whether this delta has a field half. */
    public boolean hasFieldChanges() {
        return !fieldChanges.isEmpty();
    }

    /** The field names in {@link #fieldChanges()}, in ordinal order. */
    public List<String> fieldNames() {
        return fieldChanges.stream().map(FieldChange::fieldName).toList();
    }

    @Override
    public String toString() {
        return "ListDelta[" + kind + " index=" + index + " previousIndex=" + previousIndex
                + " identity=" + identity + (fallback ? " fallback" : "")
                + (fieldChanges.isEmpty() ? "" : " fields=" + fieldNames()) + "]";
    }
}
