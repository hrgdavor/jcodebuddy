package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;
import hr.hrg.hipster.entity.api.Identifiable;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The task 6.4 acceptance test of {@code plan.dsflash.md} § 11: a tracked collection of tracked
 * views must report <strong>add</strong>, <strong>remove</strong>, <strong>reorder</strong> and a
 * <strong>per-index field delta</strong> each <em>distinctly</em>, and must fall back to positional
 * deltas plus a diagnostic — never a guess — when the element type is not identifiable.
 *
 * <p>Every mutation below is performed <strong>in place</strong> on the list. That is the point:
 * none of these changes writes to any field of the view that holds the list, so none of them sets a
 * bit in its {@code changes()} set. They are only visible through the deep path.</p>
 */
class ListChangeTrackerTest {

    // ------------------------------------------------------------------ fixtures

    /** One element of a tracked collection: a tiny view that is identifiable and tracks fields. */
    private static final class Address implements Identifiable<Long>, ViewChangeTracking<TrackingTestFixtures.Fields, EEnumSet<TrackingTestFixtures.Fields>> {

        private final EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array;

        Address(long id, String street) {
            Object[] values = new Object[TrackingTestFixtures.Fields.values().length];
            values[TrackingTestFixtures.Fields.id.ordinal()] = id;
            values[TrackingTestFixtures.Fields.firstName.ordinal()] = street;
            this.array = EntityUpdateTrackingArray.create(
                    new ForNameOrdinalImpl<>(TrackingTestFixtures.Fields.class),
                    TrackingTestFixtures.Fields.values(),
                    values);
        }

        @Override
        public Long id() {
            return (Long) array.get(TrackingTestFixtures.Fields.id.ordinal());
        }

        /** The mutable "field" of this fixture, i.e. the one a test edits to produce a delta. */
        void street(String street) {
            array.set(TrackingTestFixtures.Fields.firstName.ordinal(), street);
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

        @Override
        public String toString() {
            return "Address[" + id() + " " + array.get(TrackingTestFixtures.Fields.firstName.ordinal()) + "]";
        }
    }

    /** The same thing without an identity: the type the fallback exists for. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ViewChangeTracking<TrackingTestFixtures.Fields, ?> anonymous(String street) {
        Object[] values = new Object[TrackingTestFixtures.Fields.values().length];
        values[0] = 0L;
        values[TrackingTestFixtures.Fields.firstName.ordinal()] = street;
        return EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(TrackingTestFixtures.Fields.class),
                TrackingTestFixtures.Fields.values(),
                values);
    }

    private static Address address(long id, String street) {
        return new Address(id, street);
    }

    /** A list of identifiable addresses, already wired, with the structure taken as the baseline. */
    private static AddressList wired(Address... entries) {
        AddressList list = new AddressList();
        for (Address entry : entries) {
            list.add(entry);
        }
        list.tracker().snapshot();
        return list;
    }

    /** A list plus the tracker over it, in one object so each step cannot desynchronize them. */
    private static final class AddressList extends ArrayList<Address> {
        private static final long serialVersionUID = 1L;
        private final transient ListChangeTracker tracker = new ListChangeTracker(this);

        ListChangeTracker tracker() {
            return tracker;
        }
    }

    // ------------------------------------------------------------------ add

    @Test
    void anAddedEntryIsReportedAsAnAdditionWithItsIdentity() {
        AddressList list = wired(address(1, "One"), address(2, "Two"));
        list.add(address(3, "Three"));

        List<ListDelta> deltas = list.tracker().changes();

        Assertions.assertEquals(1, deltas.size(), "only the new entry changed; got " + deltas);
        ListDelta added = deltas.get(0);
        Assertions.assertEquals(ListChangeKind.ADDED, added.kind());
        Assertions.assertEquals(2, added.index(), "the index it occupies in the current list");
        Assertions.assertEquals(-1, added.previousIndex(), "it was not in the baseline");
        Assertions.assertEquals(3L, added.identity(), "the identity is known, so it is reported");
        Assertions.assertFalse(added.hasFieldChanges(), "there is no previous element to compare against");
        Assertions.assertFalse(added.fallback(), "identity matching, not the fallback");
    }

    // ------------------------------------------------------------------ remove

    @Test
    void aRemovedEntryIsReportedAsARemovalWithItsIdentity() {
        AddressList list = wired(address(1, "One"), address(2, "Two"), address(3, "Three"));
        list.remove(1);

        List<ListDelta> deltas = list.tracker().changes();

        Assertions.assertEquals(1, deltas.size(), "only the removal changed; got " + deltas);
        ListDelta removed = deltas.get(0);
        Assertions.assertEquals(ListChangeKind.REMOVED, removed.kind());
        Assertions.assertEquals(1, removed.index(), "the index it occupied in the baseline");
        Assertions.assertEquals(2L, removed.identity());
        Assertions.assertFalse(removed.fallback());
    }

    @Test
    void aRemovalIsNotMisreportedAsAnInPlaceFieldChange() {
        AddressList list = wired(address(1, "One"), address(2, "Two"));
        list.remove(1);

        List<ListDelta> deltas = list.tracker().changes();

        Assertions.assertEquals(List.of(ListChangeKind.REMOVED),
                deltas.stream().map(ListDelta::kind).toList(),
                "removing an entry must not look like the entry at index 1 changed its fields");
    }

    // ------------------------------------------------------------------ reorder

    @Test
    void aReorderIsReportedAsMovesAndNotAsAddOrRemove() {
        Address first = address(1, "One");
        Address second = address(2, "Two");
        Address third = address(3, "Three");
        AddressList list = wired(first, second, third);

        java.util.Collections.reverse(list);

        List<ListDelta> deltas = list.tracker().changes();

        Assertions.assertTrue(deltas.stream().allMatch(d -> d.kind() == ListChangeKind.REORDERED),
                "a reorder is neither an add nor a remove; got " + deltas);
        Assertions.assertEquals(2, deltas.size(),
                "the third entry is the longest run still in baseline order, so only two moved; got "
                        + deltas);
        Assertions.assertEquals(List.of(3L, 2L), deltas.stream().map(ListDelta::identity).toList(),
                "moves are reported in current-list order");
        Assertions.assertEquals(List.of(2, 1), deltas.stream().map(ListDelta::previousIndex).toList(),
                "each entry's baseline index is reported");
        Assertions.assertEquals(List.of(0, 1), deltas.stream().map(ListDelta::index).toList());
        Assertions.assertTrue(deltas.stream().noneMatch(ListDelta::fallback));
    }

    @Test
    void aSwapOfTwoEntriesIsReportedAsAMoveOfOneOfThem() {
        AddressList list = wired(address(1, "One"), address(2, "Two"));
        java.util.Collections.swap(list, 0, 1);

        List<ListDelta> deltas = list.tracker().changes();

        Assertions.assertEquals(1, deltas.size(), "one entry can stay, so exactly one left its index; got "
                + deltas);
        ListDelta moved = deltas.get(0);
        Assertions.assertEquals(ListChangeKind.REORDERED, moved.kind());
        Assertions.assertEquals(0, moved.index());
        Assertions.assertEquals(1, moved.previousIndex(), "the entry that was second is now first");
        Assertions.assertEquals(2L, moved.identity());
    }

    // ------------------------------------------------------------------ per-index field delta

    @Test
    void anEntryEditedInPlaceAtAKnownIndexIsReportedAsAFieldChange() {
        AddressList list = wired(address(1, "One"), address(2, "Two"), address(3, "Three"));

        list.get(1).street("Two-updated");

        List<ListDelta> deltas = list.tracker().changes();

        Assertions.assertEquals(1, deltas.size(), "the structure did not change; got " + deltas);
        ListDelta delta = deltas.get(0);
        Assertions.assertEquals(ListChangeKind.FIELD_CHANGED, delta.kind());
        Assertions.assertEquals(1, delta.index(), "the index the entry occupies");
        Assertions.assertEquals(2L, delta.identity(), "the identity disambiguates it from a move");
        Assertions.assertFalse(delta.isStructural());
        Assertions.assertEquals(List.of("firstName"), delta.fieldNames());
        Assertions.assertEquals("Two-updated", delta.fieldChanges().get(0).current(),
                "the element's tracker reports the value the field holds now; the value it replaced is "
                        + "not kept anywhere");
    }

    @Test
    void anEditAndAReorderOfTheSameEntryAreBothReported() {
        AddressList list = wired(address(1, "One"), address(2, "Two"), address(3, "Three"));

        // Reverse first, then edit the entry that ended up at index 0 -- it both moved and changed.
        java.util.Collections.reverse(list);
        list.get(0).street("Three-updated");

        List<ListDelta> deltas = list.tracker().changes();

        ListDelta edited = deltas.stream().filter(ListDelta::hasFieldChanges).findFirst().orElseThrow(
                () -> new AssertionError("the edited entry must keep its field delta; got " + deltas));
        Assertions.assertEquals(ListChangeKind.REORDERED, edited.kind(),
                "one delta carries the move and the field delta together; got " + deltas);
        Assertions.assertEquals(3L, edited.identity(), "the entry that was edited and moved");
        Assertions.assertEquals(0, edited.index());
        Assertions.assertEquals(2, edited.previousIndex());
        Assertions.assertEquals(List.of("firstName"), edited.fieldNames());
        Assertions.assertEquals("Three-updated", edited.fieldChanges().get(0).current());
    }

    @Test
    void anUnchangedListReportsNothing() {
        AddressList list = wired(address(1, "One"), address(2, "Two"));
        Assertions.assertTrue(list.tracker().changes().isEmpty());
        Assertions.assertTrue(list.tracker().diagnostics().isEmpty());
    }

    @Test
    void anEntryAddedAfterTheBaselineIsReportedAsAnAddition() {
        AddressList list = new AddressList();
        list.tracker().snapshot(); // an explicitly empty baseline

        list.add(address(1, "One"));

        Assertions.assertEquals(List.of(ListChangeKind.ADDED),
                list.tracker().changes().stream().map(ListDelta::kind).toList());

        list.tracker().snapshot();

        Assertions.assertTrue(list.tracker().changes().isEmpty(),
                "taking the baseline is how a caller says 'this is the version I loaded'");
    }

    // ------------------------------------------------------------------ the non-identifiable fallback

    @Test
    void aNonIdentifiableElementTypeYieldsPositionalDeltasAndADiagnostic() {
        List<ViewChangeTracking<TrackingTestFixtures.Fields, ?>> list = new ArrayList<>();
        ViewChangeTracking<TrackingTestFixtures.Fields, ?> first = anonymous("One");
        ViewChangeTracking<TrackingTestFixtures.Fields, ?> second = anonymous("Two");
        list.add(first);
        list.add(second);

        ListChangeTracker tracker = new ListChangeTracker(list);
        Assertions.assertFalse(tracker.matchedByIdentity(),
                "the element type is not Identifiable, so identity matching is impossible");

        // A writer marks the ordinal after comparing; the change set itself keeps no values at all,
        // so this fixture marks directly (the array-backed path marks from set(int, Object)).
        first.changesBuilder().addOrdinal(TrackingTestFixtures.Fields.firstName.ordinal());

        List<ListDelta> deltas = tracker.changes();
        Assertions.assertEquals(1, deltas.size(), "got " + deltas);
        Assertions.assertEquals(ListChangeKind.REPLACED, deltas.get(0).kind(),
                "the tracker must not claim add/remove/reorder it cannot know");
        Assertions.assertTrue(deltas.get(0).fallback(), "the delta is marked as the fallback");
        Assertions.assertNull(deltas.get(0).identity(), "there is no identity to report");
        Assertions.assertEquals(List.of("firstName"), deltas.get(0).fieldNames(),
                "the per-index field delta is still reported; only the structural half is given up");

        List<CollectionDiagnostic> diagnostics = tracker.diagnostics();
        Assertions.assertEquals(1, diagnostics.size(), "the fallback is reported, not silent");
        Assertions.assertEquals(CollectionDiagnostic.NOT_IDENTIFIABLE, diagnostics.get(0).code());
        Assertions.assertEquals(-1, diagnostics.get(0).ordinal(),
                "a standalone tracker does not know which field holds the list");
    }

    @Test
    void theNonIdentifiableFallbackStillReportsALengthChangePositionally() {
        List<ViewChangeTracking<TrackingTestFixtures.Fields, ?>> list = new ArrayList<>();
        list.add(anonymous("One"));
        list.add(anonymous("Two"));
        ListChangeTracker tracker = new ListChangeTracker(list);
        Assertions.assertTrue(tracker.changes().isEmpty(), "nothing changed yet");
        Assertions.assertFalse(tracker.diagnostics().isEmpty(), "the fallback is visible from the start");

        list.remove(1);

        List<ListDelta> deltas = tracker.changes();

        Assertions.assertEquals(1, deltas.size(), "got " + deltas);
        Assertions.assertEquals(ListChangeKind.REMOVED, deltas.get(0).kind(),
                "an index the current list does not have can only have been removed");
        Assertions.assertEquals(1, deltas.get(0).index(), "the index it occupied in the baseline");
        Assertions.assertTrue(deltas.get(0).fallback());

        // The other direction, from a fresh baseline: an index the baseline does not have can only
        // have been added.
        List<ViewChangeTracking<TrackingTestFixtures.Fields, ?>> shortList = new ArrayList<>();
        shortList.add(anonymous("One"));
        ListChangeTracker shortTracker = new ListChangeTracker(shortList);
        Assertions.assertTrue(shortTracker.changes().isEmpty(), "nothing changed yet");

        shortList.add(anonymous("Two"));

        List<ListDelta> after = shortTracker.changes();
        Assertions.assertEquals(1, after.size(), "the add is what changed now; got " + after);
        Assertions.assertEquals(ListChangeKind.ADDED, after.get(0).kind());
        Assertions.assertEquals(1, after.get(0).index());
        Assertions.assertTrue(after.get(0).fallback());
    }

    @Test
    void anIdentifiableElementTypeRaisesNoDiagnosticAtAll() {
        AddressList list = wired(address(1, "One"));
        Assertions.assertTrue(list.tracker().matchedByIdentity());
        Assertions.assertTrue(list.tracker().diagnostics().isEmpty(),
                "a diagnostic must never be raised for a type that can be matched");
    }
}
