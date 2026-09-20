package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;
import hr.hrg.hipster.entity.api.Identifiable;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The array-path half of task 6.4 ({@code plan.dsflash.md} § 11): a view whose field holds a
 * {@code List} of tracked views reports the per-element deep paths, the structural deltas and the
 * fallback diagnostic through the same contract as the shallow path.
 */
class CollectionDeepTrackingTest {

    private static final TrackingTestFixtures.Fields F = TrackingTestFixtures.Fields.firstName;
    private static final int STREET = TrackingTestFixtures.Fields.firstName.ordinal();
    private static final int TAGS = TrackingTestFixtures.Fields.tags.ordinal();

    /** An identifiable tracked element: the case the deep path can resolve in full. */
    private static final class Node implements Identifiable<Long>,
            ViewChangeTracking<TrackingTestFixtures.Fields, EEnumSet<TrackingTestFixtures.Fields>> {

        private final long id;
        private final EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array;

        Node(long id) {
            this.id = id;
            Object[] values = new Object[TrackingTestFixtures.Fields.values().length];
            values[TrackingTestFixtures.Fields.id.ordinal()] = id;
            this.array = EntityUpdateTrackingArray.create(
                    new ForNameOrdinalImpl<>(TrackingTestFixtures.Fields.class),
                    TrackingTestFixtures.Fields.values(),
                    values);
        }

        @Override
        public Long id() {
            return id;
        }

        void street(String street) {
            array.set(STREET, street);
        }

        @Override
        public boolean isChanged() {
            return array.isChanged();
        }

        @Override
        public EEnumSet<TrackingTestFixtures.Fields> changes() {
            return array.changes();
        }

        @Override
        public EEnumSetBuilder<TrackingTestFixtures.Fields> changesBuilder() {
            return array.changesBuilder();
        }

        @Override
        public void clearChanges() {
            array.clearChanges();
        }

        @Override
        public Object currentValue(TrackingTestFixtures.Fields field) {
            return array.currentValue(field);
        }

        @Override
        public List<FieldChange<TrackingTestFixtures.Fields>> changedValues() {
            return array.changedValues();
        }

        @Override
        public List<ChangePath> changesDeep() {
            return array.changesDeep();
        }
    }

    private static EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> rootWith(Object tags) {
        Object[] values = new Object[TrackingTestFixtures.Fields.values().length];
        values[TrackingTestFixtures.Fields.id.ordinal()] = 1L;
        values[TrackingTestFixtures.Fields.firstName.ordinal()] = "root";
        values[TAGS] = tags;
        return EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(TrackingTestFixtures.Fields.class),
                TrackingTestFixtures.Fields.values(),
                values);
    }

    @Test
    void aViewWithNoListFieldPaysNothing() {
        // A view whose fields are all scalars gets no tracker at all, and its deep walk is the
        // shallow one -- DEC-014's "pay only for what you use".
        Object[] values = new Object[TrackingTestFixtures.Fields.values().length];
        values[TrackingTestFixtures.Fields.id.ordinal()] = 1L;
        values[TrackingTestFixtures.Fields.firstName.ordinal()] = "root";
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(TrackingTestFixtures.Fields.class),
                TrackingTestFixtures.Fields.values(),
                values);

        Assertions.assertTrue(array.collectionTrackers().isEmpty(),
                "a view with no List field allocates no collection tracker (DEC-014)");
        Assertions.assertTrue(array.collectionDeltas().isEmpty());
        Assertions.assertTrue(array.collectionDiagnostics().isEmpty());

        array.set(F.ordinal(), "changed");

        Assertions.assertEquals(List.of("firstName"),
                array.changesDeep().stream().map(ChangePath::render).toList(),
                "the deep walk is the shallow one for a view that nests nothing");
    }

    @Test
    void aListOfUntrackedValuesReportsNoDeltas() {
        // A List field is tracked positionally, but a list that holds no tracked view has nothing to
        // report: no deltas, no diagnostics. That is what keeps the walk free for a plain List field.
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = rootWith(List.of("a", "b"));

        Assertions.assertEquals(1, array.collectionTrackers().size(), "the list field is tracked");
        Assertions.assertFalse(array.collectionTrackers().get(TAGS).hasTrackedElements());
        Assertions.assertTrue(array.collectionDeltas(TAGS).isEmpty());
        Assertions.assertTrue(array.collectionDiagnostics().isEmpty());
    }

    @Test
    void aCollectionOfTrackedViewsIsDiscoveredByOrdinal() {
        Node first = new Node(1);
        Node second = new Node(2);
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = rootWith(List.of(first, second));

        Map<Integer, ListChangeTracker> trackers = array.collectionTrackers();

        Assertions.assertEquals(1, trackers.size());
        Assertions.assertTrue(trackers.containsKey(TAGS), "the tracker is keyed by the field that holds the list");
        ListChangeTracker tags = trackers.get(TAGS);
        Assertions.assertTrue(tags.hasTrackedElements(),
                "the elements are tracked views, so the deep walk descends into them");
        // The baseline is taken by the first question asked of the tracker, so this call is also what
        // decides whether identity matching is available.
        Assertions.assertTrue(tags.changes().isEmpty(), "nothing has changed yet");
        Assertions.assertTrue(tags.matchedByIdentity(),
                "and they are identifiable, so reorders are detectable");
        Assertions.assertTrue(tags.diagnostics().isEmpty(), "an identifiable element type is never a finding");
    }

    @Test
    void theDeepWalkCarriesTheListIndexForAnElementChangedInPlace() {
        Node first = new Node(1);
        Node second = new Node(2);
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array =
                rootWith(new ArrayList<>(List.of(first, second)));

        second.street("second-updated");

        List<ChangePath> deep = array.changesDeep();

        Assertions.assertEquals(1, deep.size(), "only the edited element changed; got " + deep);
        ChangePath path = deep.get(0);
        Assertions.assertEquals(TAGS, path.field().ordinal(), "the path starts at the field holding the list");
        Assertions.assertEquals(1, path.listIndex(), "and carries the element's index");
        Assertions.assertTrue(path.isListElement());
        Assertions.assertEquals("tags[1].firstName", path.render());
        Assertions.assertEquals(2, path.depth());
    }

    @Test
    void structuralChangesAreReportedAsDeltasAndNotAsDeepPaths() {
        Node first = new Node(1);
        Node second = new Node(2);
        List<Node> entries = new ArrayList<>(List.of(first, second));
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = rootWith(entries);
        array.snapshotCollections();
        array.clearChanges();

        entries.remove(0);

        // The removal writes to no field of this view, so the shallow views stay silent...
        Assertions.assertFalse(array.isChanged());
        // ... while the deep path falls back to naming the field the collection lives in.
        Assertions.assertEquals(List.of("tags"),
                array.changesDeep().stream().map(ChangePath::render).toList());
        // ... and the structural half is where the removal is actually reported.
        List<ListDelta> deltas = array.collectionDeltas(TAGS);
        Assertions.assertEquals(1, deltas.size(), "got " + deltas);
        Assertions.assertEquals(ListChangeKind.REMOVED, deltas.get(0).kind());
        Assertions.assertEquals(1L, deltas.get(0).identity());
    }

    @Test
    void aReplacementOfTheListIsStillReportedShallowly() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array =
                rootWith(new ArrayList<>(List.of(new Node(1), new Node(2))));

        List<Object> replacement = new ArrayList<>();
        replacement.add(new Node(3));
        array.set(TAGS, replacement);

        // The field write is the shallow bit; the deep walk says the change is inside that field.
        Assertions.assertTrue(array.changes().has(TrackingTestFixtures.Fields.tags));
        Assertions.assertEquals(List.of("tags"),
                array.changesDeep().stream().map(ChangePath::render).toList(),
                "no element reports a change of its own, so this is a shallow-level change");
    }

    @Test
    void aNonIdentifiableElementTypeReportsADiagnosticThroughTheContract() {
        Object[] values = new Object[TrackingTestFixtures.Fields.values().length];
        values[TrackingTestFixtures.Fields.id.ordinal()] = 1L;
        values[TAGS] = List.of(anonymous());
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(TrackingTestFixtures.Fields.class),
                TrackingTestFixtures.Fields.values(),
                values);

        List<CollectionDiagnostic> diagnostics = array.collectionDiagnostics();

        Assertions.assertEquals(1, diagnostics.size(), "the fallback is reported; got " + diagnostics);
        Assertions.assertEquals(CollectionDiagnostic.NOT_IDENTIFIABLE, diagnostics.get(0).code());
        Assertions.assertEquals(TAGS, diagnostics.get(0).ordinal(),
                "the diagnostic names the field whose collection cannot be matched");
        Assertions.assertFalse(array.collectionTrackers().get(TAGS).matchedByIdentity());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object anonymous() {
        Object[] values = new Object[TrackingTestFixtures.Fields.values().length];
        values[0] = 0L;
        return EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(TrackingTestFixtures.Fields.class),
                TrackingTestFixtures.Fields.values(),
                values);
    }
}
