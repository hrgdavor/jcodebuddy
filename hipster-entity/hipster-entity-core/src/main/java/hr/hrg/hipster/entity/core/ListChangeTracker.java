package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.Identifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deep change tracking for a <strong>collection of tracked views</strong> (plan.dsflash § 11/6.4,
 * recorded in {@code DEC-024}).
 *
 * <p>A {@code List<Address>} field where {@code Address} is a {@code BUILDER_TRACKED} view can
 * change in two independent ways, and the shallow field bit cannot express either of them:</p>
 *
 * <ul>
 *   <li>the list reference was replaced — that is the one bit the parent already has;</li>
 *   <li>an element was <strong>mutated in place</strong> — the list reference is unchanged and the
 *       parent has no bit at all, yet a nested value really did change;</li>
 *   <li>an element was <strong>added, removed or moved</strong> — also invisible to the parent.</li>
 * </ul>
 *
 * <p>This tracker answers all three from the live list, by <strong>pulling</strong>: each element
 * that is itself a {@link ViewChangeTracking} is asked for its own
 * {@link ViewChangeTracking#changedValues()} at call time, and structural change is computed by
 * comparing the entry <em>identities</em> the list held when the tracker was created with the
 * identities it holds now.</p>
 *
 * <h3>Identity, and what happens without it</h3>
 * <p>Reorder detection <em>is</em> "same identity, different index". Without a stable per-entry
 * identity there is no way to tell a reorder from a remove-plus-add, and comparing elements by
 * {@code equals} would answer a different question (content equality, not identity). The element
 * type must therefore be {@link Identifiable}; when it is not, this tracker
 * <strong>refuses to guess</strong>: it reports positional {@link ListChangeKind#REPLACED} deltas,
 * sets {@link ListDelta#fallback()} on them, and raises a
 * {@link CollectionDiagnostic#NOT_IDENTIFIABLE} diagnostic.</p>
 *
 * <h3>Baseline — identities only, never old values</h3>
 * <p>The baseline is the entry <em>identity</em> list captured by {@link #snapshot()}, which is what
 * the constructor calls (lazily, on the first read). It is a membership record — one identity token
 * per entry, obtained from {@link Identifiable#id()} — and it holds no field value of any entry: not
 * the old values, and not the old element instances (the elements themselves are read from the live
 * list). That distinction is the whole contract: the change-tracking state must not become a second
 * copy of the caller's data, and a consumer that wants an old value reads it off the baseline
 * instance its caller still holds and compares it with the current one. What the identity baseline
 * buys is the ability to say <em>added</em>, <em>removed</em> or <em>moved</em> instead of only
 * "this position looks different", which is not derivable from the current list alone.</p>
 *
 * <p>That baseline is re-captured explicitly ({@link #snapshot()}) rather than implicitly whenever
 * something is read: a tracker that re-snapshotted on read would be unable to report the very
 * change it was about to be asked about.</p>
 *
 * <h3>Cost</h3>
 * <p>Nothing is allocated per write. The snapshot is one {@code Object[]} per collection field, and
 * {@link #changes()} allocates only when it has something to report. A view with no collection of
 * tracked views never creates a tracker at all (DEC-014, "pay only for what you use").</p>
 */
public final class ListChangeTracker {

    /** The list this tracker watches; replaced by {@link #rebased(List)} when the field is rewritten. */
    private List<?> list;
    private boolean identifiable;
    private Object[] baselineIds = new Object[0];
    /** The first repeated identity in the baseline, or {@code null} when every entry is distinct. */
    private Object duplicateIdentity;

    /**
     * The baseline is taken by the first {@link #changes()}/{@link #diagnostics()} call rather than
     * by the constructor. An array-backed view is constructed from its positional array, and its
     * list field may legitimately be filled after that array was handed over; taking the baseline at
     * construction would call those entries "added" and report a change on a view nobody has touched
     * yet. Deferring one step means the baseline is the list as it stood the first time the tracker
     * was asked — which is what a consumer means by "the version this view was loaded from".
     * {@link #snapshot()} moves that baseline forward on demand.
     */
    private boolean initialised;

    public ListChangeTracker(List<?> list) {
        this.list = Objects.requireNonNull(list, "list");
    }

    /** A tracker over the current contents of {@code list}. */
    public static ListChangeTracker of(List<?> list) {
        return new ListChangeTracker(list);
    }

    /**
     * Re-captures the entry identities of the current list as the baseline. Call this after the
     * list has been changed deliberately and the change consumed, exactly as
     * {@link ViewChangeTracking#clearChanges()} resets a change set.
     *
     * <p>The current element instances are read at this moment, so a mutation performed before the
     * snapshot is part of the new baseline and is never reported.</p>
     */
    public void snapshot() {
        // The mode is decided from the elements actually present: a list that currently holds no
        // identifiable element is tracked positionally even if its declared type is Identifiable.
        // The declared type is not visible here (an array-backed view stores an Object[]), and the
        // plan's own rule for the array path is "discover nesting from the value".
        this.identifiable = anyIdentifiable(list);
        Object[] ids = new Object[list.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = identityOf(list.get(i));
        }
        this.baselineIds = ids;
        this.duplicateIdentity = firstDuplicate(ids);
        this.initialised = true;
    }

    /**
     * The first identity that appears more than once, or {@code null}. Duplicates make an identity
     * name more than one position, which is the one situation in which identity matching would
     * invent a removal and a move out of nothing — so it degrades to the positional fallback and is
     * reported rather than guessed at.
     */
    private static Object firstDuplicate(Object[] ids) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == null) {
                continue;
            }
            for (int j = i + 1; j < ids.length; j++) {
                if (ids[i].equals(ids[j])) {
                    return ids[i];
                }
            }
        }
        return null;
    }

    /** Takes the baseline if it has not been taken yet. */
    private void initialise() {
        if (!initialised) {
            snapshot();
        }
    }

    /** Whether this tracker matches entries by identity. {@code false} means the positional fallback
     * is in use — either because no element is identifiable or because two share an identity.
     */
    public boolean matchedByIdentity() {
        return identifiable && duplicateIdentity == null;
    }

    /**
     * Whether the collection this tracker watches is still the one it was created over. A field write
     * may install a *different* list instance, and this tracker has no way to see that (it holds the
     * old one), so the array path tells it via {@link #rebased(List)}.
     */
    public boolean isWatching(Object candidate) {
        return list == candidate;
    }

    /**
     * The field was reassigned to a different list instance. The tracker re-points at it and keeps
     * the <em>old</em> entries' identities as the baseline, so the membership difference between the
     * two lists is what gets reported — a wholesale replacement with no identity in common is
     * therefore a removal of every baseline entry plus an addition of every new one, which is exactly
     * what happened.
     */
    public void rebased(List<?> replacement) {
        initialise();
        this.list = replacement;
        Object[] ids = new Object[replacement.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = identityOf(replacement.get(i));
        }
        // A replacement does not change what a kind of element *is*, so the mode is inherited and
        // only widened: a tracker that already matched by identity keeps doing so.
        this.identifiable = identifiable || anyIdentifiable(replacement);
        this.baselineIds = ids;
        this.duplicateIdentity = firstDuplicate(ids);
        this.initialised = true;
    }

    /**
     * Whether any entry of the list is itself tracked. {@code false} means this collection cannot
     * report a nested change of any kind, and a caller need not ask it.
     */
    public boolean hasTrackedElements() {
        for (Object element : list) {
            if (element instanceof ViewChangeTracking<?, ?>) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every change to the collection, in a stable order: additions and in-place field deltas in
     * current-list order first, then moves, then removals in baseline order. Empty when nothing
     * changed.
     */
    public List<ListDelta> changes() {
        initialise();
        if (list.isEmpty() && baselineIds.length == 0) {
            return List.of();
        }
        return matchedByIdentity() ? changesByIdentity() : changesByPosition();
    }

    /**
     * The diagnostics for this collection. Empty unless the collection holds tracked views whose
     * entries cannot be matched to a baseline position: a {@code List} of plain values has no
     * structural change worth reporting, so warning about it would be noise rather than a finding.
     */
    public List<CollectionDiagnostic> diagnostics() {
        initialise();
        if (!hasTrackedElements()) {
            return List.of();
        }
        if (duplicateIdentity != null) {
            return List.of(CollectionDiagnostic.duplicateIdentity(duplicateIdentity));
        }
        if (identifiable) {
            return List.of();
        }
        return List.of(CollectionDiagnostic.notIdentifiable(
                "the element type of a tracked collection is not Identifiable, so add/remove/reorder"
                        + " cannot be told apart; reporting per-index deltas only"));
    }

    /** The per-field deltas inside the element currently at {@code index}; empty when it has none. */
    public List<FieldChange<?>> fieldChangesAt(int index) {
        if (index < 0 || index >= list.size()) {
            return List.of();
        }
        Object element = list.get(index);
        if (!(element instanceof ViewChangeTracking<?, ?> tracked)) {
            return List.of();
        }
        return fieldChangesOf(tracked);
    }

    /** The entry identities of the current list, for callers that need them positionally. */
    public Object[] currentIdentities() {
        Object[] ids = new Object[list.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = identityOf(list.get(i));
        }
        return ids;
    }

    // ------------------------------------------------------------------ the two modes

    /**
     * Identity mode. One pass over the current list reports every addition, every in-place field
     * delta and every move; a second pass over the baseline reports every removal.
     *
     * <p>An entry is reported as {@link ListChangeKind#REORDERED} when it is not part of the
     * <strong>longest run of survivors that is still in baseline order</strong> — the longest
     * increasing subsequence of the survivors' baseline indices, read in current-list order. The
     * distinction matters, because the obvious alternatives are wrong in both directions:</p>
     *
     * <ul>
     *   <li>Comparing each entry's index with its own baseline index reports the survivors that
     *       merely shifted down when an earlier entry was removed. They did not move: the removal
     *       alone accounts for the change, and reporting them would turn one removal into "a removal
     *       plus <em>N</em> spurious reorders".</li>
     *   <li>Comparing only the survivors' relative order is complete but reports an entry whose
     *       absolute index never changed — reversing three entries would report the middle one,
     *       which stayed exactly where it was.</li>
     * </ul>
     *
     * <p>Keeping the longest such run also makes the report <em>small</em>: a two-entry swap reports
     * one move (either entry can be the one left in place), a reversal of three reports two, a
     * single entry moved to the front reports that one entry, and a stable prefix is never reported.
     * Where several runs are equally long the earliest is kept, so the answer is deterministic
     * rather than merely correct.</p>
     *
     * <p>Deltas are reported in current-list order, one per position, in contrast to
     * {@link #changesByPosition()}: the sequence reads as "what the list looks like now, position by
     * position", which is what a consumer walking a patch wants.</p>
     */
    private List<ListDelta> changesByIdentity() {
        List<ListDelta> deltas = new ArrayList<>();

        // The baseline index of the entry at each current position; -1 for an entry that is new.
        int[] survivorBaselineIndex = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            survivorBaselineIndex[i] = baselineIndexOf(identityOf(list.get(i)));
        }
        boolean[] inPlace = longestInPlaceRun(survivorBaselineIndex);

        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            Object id = identityOf(element);
            int previous = survivorBaselineIndex[i];
            if (previous < 0) {
                deltas.add(new ListDelta(ListChangeKind.ADDED, i, -1, id, fieldChangesAt(i), false));
                continue;
            }
            if (!inPlace[i]) {
                deltas.add(new ListDelta(ListChangeKind.REORDERED, i, previous, id, fieldChangesAt(i), false));
                continue;
            }
            List<FieldChange<?>> inside = fieldChangesAt(i);
            if (!inside.isEmpty()) {
                deltas.add(ListDelta.fields(i, id, inside));
            }
        }

        // Removals have no current position to interleave with, so they are appended in baseline order.
        for (int i = 0; i < baselineIds.length; i++) {
            if (!isSurvivor(i, survivorBaselineIndex)) {
                deltas.add(new ListDelta(ListChangeKind.REMOVED, i, i, baselineIds[i], List.of(), false));
            }
        }
        return deltas;
    }

    /**
     * The longest increasing subsequence of {@code baselineIndex}, marked by position — the entries
     * that are still in baseline order relative to each other, and which are therefore not reported
     * as moves. An addition ({@code -1}) is never part of the run: an entry that was not in the
     * baseline cannot have stayed in baseline order.
     *
     * <p>Computed with the patience-sorting formulation (tails plus a predecessor trace), so it is
     * <em>O(n log n)</em> in the number of entries and always returns a longest run. Where several
     * runs are equally long, the one built from the earliest positions wins, which is what makes the
     * reported move set stable across runs: a swap of two entries reports both, a reversal of three
     * reports two, and a single entry moved to the front reports one.</p>
     */
    private static boolean[] longestInPlaceRun(int[] baselineIndex) {
        int n = baselineIndex.length;
        int[] tails = new int[n];       // tails[len] = position ending the smallest run of length len+1
        int[] trace = new int[n];       // trace[i] = the position preceding i in its run, or -1
        int longest = 0;

        for (int i = 0; i < n; i++) {
            if (baselineIndex[i] < 0) {
                continue; // an addition has no baseline index, so it cannot be part of the order
            }
            int lo = 0;
            int hi = longest;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (baselineIndex[tails[mid]] < baselineIndex[i]) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            trace[i] = lo > 0 ? tails[lo - 1] : -1;
            tails[lo] = i;
            if (lo == longest) {
                longest++;
            }
        }

        boolean[] inPlace = new boolean[n];
        for (int at = longest == 0 ? -1 : tails[longest - 1]; at >= 0; at = trace[at]) {
            inPlace[at] = true;
        }
        return inPlace;
    }

    /** Whether the baseline entry at {@code baselinePosition} still has a survivor. */
    private static boolean isSurvivor(int baselinePosition, int[] survivorBaselineIndex) {
        for (int previous : survivorBaselineIndex) {
            if (previous == baselinePosition) {
                return true;
            }
        }
        return false;
    }

    /**
     * Positional fallback. The length difference is reported positionally (an index the baseline
     * does not have can only have been added, an index the current list does not have can only have
     * been removed) and every index both lists share is reported with its field deltas only. The
     * add/remove/reorder distinction is <em>not</em> made: that is what the diagnostic says.
     */
    private List<ListDelta> changesByPosition() {
        List<ListDelta> deltas = new ArrayList<>();
        int shared = Math.min(baselineIds.length, list.size());

        for (int i = 0; i < shared; i++) {
            Object id = identityOf(list.get(i));
            List<FieldChange<?>> inside = fieldChangesAt(i);
            if (inside.isEmpty()) {
                continue; // position and content both unchanged: nothing is claimed about it
            }
            deltas.add(new ListDelta(ListChangeKind.REPLACED, i, -1, id, inside, true));
        }
        for (int i = shared; i < list.size(); i++) {
            deltas.add(new ListDelta(ListChangeKind.ADDED, i, -1, null, List.of(), true));
        }
        for (int i = shared; i < baselineIds.length; i++) {
            deltas.add(new ListDelta(ListChangeKind.REMOVED, i, i, null, List.of(), true));
        }
        return deltas;
    }

    // ------------------------------------------------------------------ helpers

    /** The identity of {@code element}, or {@code null} when it is not identifiable. */
    private static Object identityOf(Object element) {
        return element instanceof Identifiable<?> identified ? identified.id() : null;
    }

    /** The baseline index holding the same identity, or {@code -1}. */
    private int baselineIndexOf(Object id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < baselineIds.length; i++) {
            if (id.equals(baselineIds[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean anyIdentifiable(List<?> list) {
        for (Object element : list) {
            if (element instanceof Identifiable<?>) {
                return true;
            }
        }
        return false;
    }

    /**
     * The element's own changed fields with their current values. The element is a
     * {@link ViewChangeTracking} whose field enum type is not known here, so its
     * {@code changedValues()} is consumed as a list of wildcards; the field constant itself is
     * retained and remains navigable.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<FieldChange<?>> fieldChangesOf(ViewChangeTracking<?, ?> tracked) {
        List<?> raw = ((ViewChangeTracking) tracked).changedValues();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<FieldChange<?>> changes = new ArrayList<>(raw.size());
        for (Object change : raw) {
            changes.add((FieldChange<?>) change);
        }
        return changes;
    }

    @Override
    public String toString() {
        return "ListChangeTracker[elements=" + list.size() + " baseline=" + baselineIds.length
                + (identifiable ? " byIdentity" : " byPosition") + "]";
    }
}
