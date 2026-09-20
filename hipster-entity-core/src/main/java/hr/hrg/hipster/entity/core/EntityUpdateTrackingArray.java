package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ForNameOrdinal;
import hr.hrg.hipster.entity.api.ViewWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base for update-tracking entity array views.
 * Use {@link #create} to obtain the correct concrete variant for the enum size.
 * Concrete subclasses hold their builder as a concrete type so the JVM can
 * statically dispatch (and inline) {@link #mark}/{@link #clear} on the hot path.
 *
 * <h3>Ordinal-array contract</h3>
 * <p>{@code values[field.ordinal()]} holds the value of {@code field} for every constant of the
 * view's {@link FieldDef} enum, so {@code values.length == universe.length}. Ordinal 0 is the
 * entity identity and is immutable here.</p>
 *
 * <h3>Change tracking</h3>
 * <p>This array <em>is</em> a {@link ViewChangeTracking}: {@link #changes()} is the immutable
 * snapshot, {@link #changesBuilder()} the live mutable builder, and the two are views over one
 * piece of state — the marked ordinals. Nothing else is kept: the array holds the values the fields
 * hold <em>now</em>, and the value a field held before a write is not copied anywhere (see
 * {@link ViewChangeTracking} for why that is the contract).</p>
 */
public abstract class EntityUpdateTrackingArray<T, F extends Enum<F> & FieldDef>
        implements ViewWriter, ViewChangeTracking<F, EEnumSet<F>> {

    protected final Object[] values;
    private final ForNameOrdinal forNameOrdinal;
    /** The view's field constants by ordinal; also the argument the builders are constructed with. */
    private final F[] universe;

    /**
     * Per-ordinal trackers for fields whose value is a {@code List} of tracked views
     * (plan.dsflash § 11/6.4). Populated at construction, because the tracker's baseline is the
     * list as it was handed to this array — a tracker created lazily on first read would already
     * have missed the change it was about to be asked about.
     */
    private final java.util.Map<Integer, ListChangeTracker> collectionTrackers;

    /**
     * @param forNameOrdinal name &rarr; ordinal resolution for {@link #set(String, Object)}
     * @param universe       the view's {@link FieldDef} constants; must have
     *                       {@code universe.length == values.length}. This is the parameter that
     *                       reaches {@link EEnumSetBuilder64}, fixing the {@code null} enum class
     *                       of plan.dsflash § 2.4.
     * @param fieldCount     expected field count, asserted against {@code universe.length} and
     *                       {@code values.length}
     * @param values         the positional backing array
     * @param collectionTrackers the collection trackers for this view, keyed by field ordinal;
     *                       empty for a view that nests no collection of tracked views
     */
    protected EntityUpdateTrackingArray(ForNameOrdinal forNameOrdinal, F[] universe, int fieldCount, Object[] values,
                                       java.util.Map<Integer, ListChangeTracker> collectionTrackers) {
        this.forNameOrdinal = forNameOrdinal;
        this.collectionTrackers = collectionTrackers == null ? java.util.Map.of() : collectionTrackers;
        if (universe == null) {
            throw new IllegalArgumentException("View field universe must not be null");
        }
        if (fieldCount != values.length) {
            throw new IllegalArgumentException("View field count and data provided lengths do not match");
        }
        if (universe.length != fieldCount) {
            throw new IllegalArgumentException(
                    "View field universe length " + universe.length + " does not match field count " + fieldCount);
        }
        this.values = values;
        this.universe = universe;
    }

    /**
     * The no-collection form, kept for callers that construct a variant directly: it is exactly the
     * five-argument constructor with no collection trackers, and it discovers them from
     * {@code values} the same way, so a directly constructed variant is not a degraded one.
     */
    protected EntityUpdateTrackingArray(ForNameOrdinal forNameOrdinal, F[] universe, int fieldCount, Object[] values) {
        this(forNameOrdinal, universe, fieldCount, values, trackersForLists(values));
    }

    /** Factory: picks {@link EntityUpdateTrackingArray64} or {@link EntityUpdateTrackingArrayLarge}. */
    public static <T, F extends Enum<F> & FieldDef>
    EntityUpdateTrackingArray<T, F> create(ForNameOrdinal forNameOrdinal, F[] universe, Object... values) {
        if (universe == null) {
            throw new IllegalArgumentException("View field universe must not be null");
        }
        java.util.Map<Integer, ListChangeTracker> trackers = trackersForLists(values);
        return universe.length <= 64
                ? new EntityUpdateTrackingArray64<>(forNameOrdinal, universe, universe.length, values, trackers)
                : new EntityUpdateTrackingArrayLarge<>(forNameOrdinal, universe, universe.length, values, trackers);
    }

    /**
     * Turns the ordinals that hold a {@code List} into trackers. A tracker is created for
     * <em>any</em> list-valued field rather than only for one whose entries are tracked views right
     * now: a list that is empty (or holds plain values) at construction may hold tracked views
     * later, and a tracker created at that point would take a baseline that already includes them
     * and could never report the addition.
     *
     * <p>Like {@link #nestedTrackers()}, this is a value-level discovery: an array-backed view has no
     * declared field types at runtime, so the only honest question to ask is what it is holding.</p>
     */
    private static java.util.Map<Integer, ListChangeTracker> trackersForLists(Object[] values) {
        java.util.Map<Integer, ListChangeTracker> found = new java.util.LinkedHashMap<>();
        if (values == null) {
            return found;
        }
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            if (values[ordinal] instanceof List<?> list) {
                found.put(ordinal, new ListChangeTracker(list));
            }
        }
        return found;
    }

    /** Mark field ordinal as changed. Returns {@code true} if the bit was newly set. */
    public abstract boolean mark(int ordinal);

    /** Unmark field ordinal. Returns {@code true} if the bit was cleared. */
    public abstract boolean unmark(int ordinal);

    /** Reset all change bits. Nothing else is retained, so nothing else needs releasing. */
    public abstract void clear();

    /** The live, mutable change set — same state as {@link #changes()}. */
    @Override
    public abstract EEnumSetBuilder<F> changesBuilder();

    /** Immutable snapshot of the currently marked fields. */
    @Override
    public EEnumSet<F> changes() {
        return changesBuilder().toImmutable();
    }

    @Override
    public boolean isChanged() {
        return !changesBuilder().isEmpty();
    }

    @Override
    public Object currentValue(F field) {
        return field == null ? null : values[field.ordinal()];
    }

    /**
     * One {@link FieldChange} per marked field, in ascending ordinal order, each carrying the value
     * the field holds now. The value it held before the write is not reported — see
     * {@link ViewChangeTracking#changedValues()}.
     */
    @Override
    public List<FieldChange<F>> changedValues() {
        EEnumSetBuilder<F> builder = changesBuilder();
        List<FieldChange<F>> result = new ArrayList<>(builder.size());
        if (builder.isEmpty()) {
            return result;
        }
        builder.forEach((field, index) -> result.add(new FieldChange<>(field, values[field.ordinal()])));
        return result;
    }

    @Override
    public void clearChanges() {
        clear();
    }

    /**
     * The deep change paths (plan.dsflash § 11, D4 — pull, not push).
     *
     * <p>At the shallow level this array reports one leaf path per marked ordinal. For a field whose
     * stored value is itself a {@link ViewChangeTracking}, the path is extended through the child's
     * own deep paths, so a change three levels down inside a nested tracked view is reported as
     * {@code outer.inner.leaf} without the parent's bitset being touched. The child is read
     * <strong>through the value the array holds</strong>, so the walk costs nothing for a view that
     * nests nothing: the loop below never looks at a non-tracked value.</p>
     */
    @Override
    public List<ChangePath> changesDeep() {
        EEnumSetBuilder<F> builder = changesBuilder();
        List<ChangePath> paths = new ArrayList<>();
        if (builder.isEmpty() && collectionTrackers.isEmpty()) {
            return paths;
        }
        builder.forEach((field, index) -> {
            Object value = values[field.ordinal()];
            if (value instanceof ViewChangeTracking<?, ?> nested) {
                List<ChangePath> childPaths = (List<ChangePath>) nested.changesDeep();
                for (ChangePath childPath : childPaths) {
                    paths.add(new ChangePath(field, -1, childPath));
                }
                // A reassignment is itself a change at this level, even when the child shows none.
                if (childPaths.isEmpty()) {
                    paths.add(ChangePath.of(field));
                }
            } else if (value instanceof List<?> && collectionTrackers.containsKey(field.ordinal())) {
                // The field was reassigned to a list. The path still has to reach inside it: a
                // replacement and an element change are different findings, and a caller that gets
                // only "heads" cannot tell which of the two happened.
                if (!addCollectionPaths(field, collectionTrackers.get(field.ordinal()), paths)) {
                    paths.add(ChangePath.of(field));
                }
            } else {
                paths.add(ChangePath.of(field));
            }
        });
        // The marked ordinals, captured before the walk: the collection pass below must not re-walk
        // an ordinal the loop above already reported.
        java.util.Set<Integer> marked = new java.util.HashSet<>();
        builder.forEach((field, index) -> marked.add(field.ordinal()));

        // A collection is walked whether or not its own field was marked: mutating an entry in place
        // -- and adding or removing one -- writes to no field of this view, so the marked ordinals
        // alone would never reach it. A tracker exists only for a field that holds a list, so this
        // loop is empty for a view that nests no collection.
        collectionTrackers.forEach((ordinal, tracker) -> {
            if (!marked.contains(ordinal)) {
                addCollectionPaths(universe[ordinal], tracker, paths);
            }
        });
        return paths;
    }

    /**
     * Adds the deep paths of one collection-valued field (plan.dsflash § 11/6.4).
     *
     * <p>Two findings can come out of one collection and both are reported:</p>
     *
     * <ul>
     *   <li>an element that is still in the list changed inside it — one path per element, carrying
     *       that element's {@link ChangePath#listIndex()};</li>
     *   <li>the collection changed structurally (an entry added, removed or moved). That has no
     *       single field to point at, so the summary is one path naming the field that holds the
     *       list, and the kind of change is read from {@link #collectionDeltas()}.</li>
     * </ul>
     *
     * <p>Reporting only the first would lose a reorder that accompanied an edit; reporting only the
     * second would throw away the leaf the caller actually wants to patch.</p>
     *
     * @return whether any path was added; {@code false} means the collection reports nothing, and
     *         the caller should fall back to naming the field itself
     */
    private boolean addCollectionPaths(F field, ListChangeTracker tracker, List<ChangePath> paths) {
        boolean added = false;
        boolean structural = false;
        for (ListDelta delta : tracker.changes()) {
            if (delta.hasFieldChanges()) {
                // The field constant is a FieldDef at runtime by the ChangePath contract; only the
                // generic bound of the wildcard list hides that here.
                paths.add(new ChangePath(field, delta.index(),
                        ChangePath.of((hr.hrg.hipster.entity.api.FieldDef) delta.fieldChanges().get(0).field())));
                added = true;
            } else if (delta.isStructural()) {
                structural = true;
            }
        }

        if (structural) {
            // One summary path naming the field that holds the collection; the kind of each
            // structural change is read from collectionDeltas(). Reporting it once rather than per
            // delta keeps the path list readable when a whole list was turned over.
            paths.add(ChangePath.of(field));
            added = true;
        }
        return added;
    }

    /**
     * The collection deltas of every collection-valued field, keyed by field ordinal
     * (plan.dsflash § 11/6.4). This is where an add, a remove and a reorder stay
     * <em>distinguishable</em> — {@link ChangePath} says where a change is, a
     * {@link ListDelta} says what kind of change it is.
     *
     * <p>Empty for the overwhelming majority of views: a tracker exists only for a field that holds
     * a {@code List}.</p>
     */
    @Override
    public java.util.Map<Integer, List<ListDelta>> collectionDeltas() {
        if (collectionTrackers.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Integer, List<ListDelta>> deltas = new java.util.LinkedHashMap<>();
        collectionTrackers.forEach((ordinal, tracker) -> deltas.put(ordinal, tracker.changes()));
        return deltas;
    }

    /**
     * The collection deltas of one field, an empty list when that field holds no tracked
     * collection.
     */
    public List<ListDelta> collectionDeltas(int ordinal) {
        ListChangeTracker tracker = collectionTrackers.get(ordinal);
        return tracker == null ? List.of() : tracker.changes();
    }

    /** Whether the field at {@code ordinal} holds a {@code List} this array tracks. */
    @Override
    public boolean hasCollection(int ordinal) {
        return collectionTrackers.containsKey(ordinal);
    }

    /** The diagnostics of every collection-valued field, aggregated in field-ordinal order. */
    @Override
    public List<CollectionDiagnostic> collectionDiagnostics() {
        if (collectionTrackers.isEmpty()) {
            return List.of();
        }
        List<CollectionDiagnostic> diagnostics = new ArrayList<>();
        collectionTrackers.forEach((ordinal, tracker) ->
                tracker.diagnostics().forEach(diagnostic -> diagnostics.add(diagnostic.atOrdinal(ordinal))));
        return diagnostics;
    }

    /**
     * Re-captures the baseline of one collection field to the list's current contents. Use it after
     * a structural change has been consumed, exactly as {@link #clearChanges()} resets the change
     * set; without it the field keeps reporting the entries the array was constructed with.
     */
    public void snapshotCollection(int ordinal) {
        ListChangeTracker tracker = collectionTrackers.get(ordinal);
        if (tracker != null) {
            tracker.snapshot();
        }
    }

    /** Re-captures the baseline of every collection field. */
    public void snapshotCollections() {
        collectionTrackers.values().forEach(ListChangeTracker::snapshot);
    }

    /**
     * Re-captures the baseline of every collection field to what the list holds <em>now</em>.
     * Called by {@link #clear()}: resetting the change set includes resetting the collection
     * baselines, or {@code clearChanges()} would leave a stale structural delta behind.
     */
    protected void clearCollectionChanges() {
        snapshotCollections();
    }

    /**
     * The collection trackers this array holds, keyed by field ordinal — one per {@code List}-valued
     * field. Exposed so the proxy handler can answer {@code snapshotCollection} without reaching
     * into this class, and so a test can assert that a view with no list field pays nothing.
     */
    public java.util.Map<Integer, ListChangeTracker> collectionTrackers() {
        return java.util.Collections.unmodifiableMap(collectionTrackers);
    }

    /**
     * The nested trackers this array currently holds, discovered from the values it stores.
     *
     * <p>An array-backed view has no declared field types, so unlike a generated builder it must
     * look at the values. That is still not reflection: it is an {@code instanceof} test on the
     * value the field already holds, evaluated only for marked ordinals.</p>
     */
    @Override
    public java.util.Map<Integer, ViewChangeTracking<?, ?>> nestedTrackers() {
        java.util.Map<Integer, ViewChangeTracking<?, ?>> found = new java.util.LinkedHashMap<>();
        changesBuilder().forEach((field, index) -> {
            Object value = values[field.ordinal()];
            if (value instanceof ViewChangeTracking<?, ?> nested) {
                found.put(field.ordinal(), nested);
            }
        });
        return found;
    }

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

        Object held = values[fieldOrdinal];
        if (Objects.equals(held, value)) {
            return; // DEC-012 no-op rule: compare first, and mark nothing when the value is unchanged
        }

        // Compare, then mark, then assign. The comparison uses the value this array holds right now;
        // no pre-write value is retained (ViewChangeTracking: the old value belongs to the caller's
        // baseline instance, not to the tracker).
        changesBuilder().addOrdinal(fieldOrdinal);
        values[fieldOrdinal] = value;

        // A collection tracker holds the list instance it was created over, so it cannot see the
        // field being pointed at a different list. Telling it here is what keeps the deep walk honest
        // when a whole list is swapped in.
        ListChangeTracker tracker = collectionTrackers.get(fieldOrdinal);
        if (tracker != null && !tracker.isWatching(value)) {
            if (value instanceof List<?> replacement) {
                tracker.rebased(replacement);
            } else {
                collectionTrackers.remove(fieldOrdinal);
            }
        }
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

    @Override
    public Object get(String fieldName) {
        int field  = forNameOrdinal.forNameOrdinal(fieldName);
        return field == -1 ? null : values[field];
    }

    @Override
    public boolean supports(String fieldName) {
        return forNameOrdinal.forNameOrdinal(fieldName) != -1;
    }
}
