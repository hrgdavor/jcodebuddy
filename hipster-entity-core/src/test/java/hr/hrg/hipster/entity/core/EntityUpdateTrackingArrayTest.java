package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.ForNameOrdinal;
import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The Phase 1 core suite of {@code plan.dsflash.md} § 6.4, tests 1–7, 14–17 plus the
 * state-sharing acceptance test for S5 (steps 8–12) run against the array-backed materialization.
 *
 * <p>Before this class existed the update-tracking array could not be constructed at all
 * (plan.dsflash § 2.4) and had no test whatsoever. Test {@link #createBuildsBothVariants()} is the
 * regression test for that NPE.</p>
 */
class EntityUpdateTrackingArrayTest {

    // ------------------------------------------------------------------ 1
    @Test
    void createBuildsBothVariants() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> small = TrackingTestFixtures.tracking();
        Assertions.assertNotNull(small);
        Assertions.assertInstanceOf(EntityUpdateTrackingArray64.class, small,
                "7 fields must select the 64-bit variant");
        Assertions.assertNotNull(small.changesBuilder().getEnumClass(),
                "the builder must know its enum class - the bug was a null here");

        EntityUpdateTrackingArray<Object, TrackingTestFixtures.BuiltIn> large = TrackingTestFixtures.trackingLarge();
        Assertions.assertInstanceOf(EntityUpdateTrackingArrayLarge.class, large,
                "65 fields must select the Large variant");
        Assertions.assertNotNull(large.changesBuilder().getEnumClass());
        Assertions.assertEquals(65, large.changesBuilder().getEnumClass().getEnumConstants().length);
    }

    // ------------------------------------------------------------------ 2
    @Test
    void arityMismatchThrows() {
        Object[] tooShort = new Object[3];
        Assertions.assertThrows(IllegalArgumentException.class, () -> EntityUpdateTrackingArray.create(
                TrackingTestFixtures.ORDINALS, TrackingTestFixtures.Fields.values(), tooShort));
    }

    @Test
    void universeLengthMismatchThrows() {
        Object[] values = TrackingTestFixtures.values(TrackingTestFixtures.Fields.values());
        Assertions.assertThrows(IllegalArgumentException.class, () -> EntityUpdateTrackingArray.create(
                TrackingTestFixtures.ORDINALS, TrackingTestFixtures.Fields.values(), 2, values));
    }

    @Test
    void nullUniverseThrows() {
        Object[] values = new Object[0];
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> EntityUpdateTrackingArray.create(TrackingTestFixtures.ORDINALS, null, values));
    }

    // ------------------------------------------------------------------ 3
    @Test
    void equalValueSetLeavesTheChangeSetEmpty() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = TrackingTestFixtures.tracking();
        array.set(TrackingTestFixtures.Fields.firstName.ordinal(), "v1");

        Assertions.assertFalse(array.isChanged(), "DEC-012: an equal-value write is a no-op");
        Assertions.assertTrue(array.changes().isEmpty());
    }

    // ------------------------------------------------------------------ 4
    @Test
    void setIdOrdinalThrows() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = TrackingTestFixtures.tracking();
        Assertions.assertThrows(UnsupportedOperationException.class, () -> array.set(0, 99L));
    }

    @Test
    void setOutOfBoundsOrdinalThrows() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = TrackingTestFixtures.tracking();
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> array.set(99, "x"));
    }

    // ------------------------------------------------------------------ 5
    @Test
    void clearEmptiesTheChangeSet() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = TrackingTestFixtures.tracking();
        array.set(TrackingTestFixtures.Fields.firstName.ordinal(), "changed");
        Assertions.assertTrue(array.isChanged());

        array.clear();

        Assertions.assertFalse(array.isChanged());
        Assertions.assertTrue(array.changes().isEmpty());
    }

    // ------------------------------------------------------------------ 6 / 14
    /**
     * The tracker reports the value the field holds now, and the baseline stays with the caller.
     *
     * <p>This replaces the removed {@code previousValue(ord)} test. The old value was never the
     * tracker's to keep: the caller still holds the values it constructed the array from, and the
     * comparison the old accessor used to make is made there. The test therefore asserts both halves
     * of the new contract — the tracker reports the current value, and the caller's own baseline
     * array is untouched by the write.</p>
     */
    @Test
    void theTrackerReportsTheCurrentValueAndTheCallerKeepsTheBaseline() {
        Object[] baseline = TrackingTestFixtures.values(TrackingTestFixtures.Fields.values());
        Object[] live = baseline.clone();
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array =
                EntityUpdateTrackingArray.create(
                        TrackingTestFixtures.ORDINALS, TrackingTestFixtures.Fields.values(), live);
        int ord = TrackingTestFixtures.Fields.firstName.ordinal();

        array.set(ord, "changed");

        Assertions.assertTrue(array.changes().has(ord), "a different value marks the ordinal");
        Assertions.assertEquals("changed", array.currentValue(TrackingTestFixtures.Fields.firstName),
                "the tracker reports the value the field holds now");
        Assertions.assertEquals("v1", baseline[ord],
                "and the baseline the caller passed in is still the caller's — the tracker neither "
                        + "copied it nor kept a reference to the replaced value");
    }

    /**
     * The accepted consequence of keeping no baseline: a write back to the original value stays
     * marked, and the caller's own comparison is what answers "is it different from the baseline".
     */
    @Test
    void writingTheOriginalValueBackStaysMarkedAndTheCallerCompares() {
        Object[] baseline = TrackingTestFixtures.values(TrackingTestFixtures.Fields.values());
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array =
                EntityUpdateTrackingArray.create(
                        TrackingTestFixtures.ORDINALS, TrackingTestFixtures.Fields.values(), baseline.clone());
        int ord = TrackingTestFixtures.Fields.firstName.ordinal();

        array.set(ord, "changed");
        array.set(ord, "v1");

        Assertions.assertTrue(array.isChanged(),
                "the field really was written twice, and the tracker keeps no baseline that could "
                        + "unmark it");
        Assertions.assertEquals(baseline[ord], array.currentValue(TrackingTestFixtures.Fields.firstName),
                "while the caller's comparison — its own baseline against the current value — says "
                        + "the two agree, which is the answer that matters");
    }

    // ------------------------------------------------------------------ 7
    @Test
    void boundary64And65ProduceEquivalentChanges() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> small = TrackingTestFixtures.tracking();
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.BuiltIn> large = TrackingTestFixtures.trackingLarge();

        small.set(1, "changed-1");
        small.set(6, "changed-6");
        large.set(1, "changed-1");
        large.set(6, "changed-6");

        // The two universes share their first seven constant names, so the same mutation
        // sequence must produce the same change set and the same reported values.
        Assertions.assertEquals(List.of("firstName", "tags"), TrackingTestFixtures.names(large.changes()));
        Assertions.assertEquals(List.of("firstName", "tags"), TrackingTestFixtures.names(small.changes()));
        Assertions.assertEquals(2, small.changes().size());
        Assertions.assertEquals(2, large.changes().size());
        Assertions.assertEquals("changed-1", small.currentValue(TrackingTestFixtures.Fields.firstName));
        Assertions.assertEquals("changed-1", large.currentValue(TrackingTestFixtures.BuiltIn.firstName));
        Assertions.assertEquals("changed-6", large.currentValue(TrackingTestFixtures.BuiltIn.tags));
    }

    @Test
    void largeVariantMarksPastThe64BitBoundary() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.BuiltIn> large = TrackingTestFixtures.trackingLarge();
        large.set(64, "changed-64");

        Assertions.assertTrue(large.changes().has(64), "ordinal 64 needs the second long segment");
        Assertions.assertEquals(1, large.changes().size());
        Assertions.assertEquals("changed-64", large.currentValue(TrackingTestFixtures.BuiltIn.f64));
    }

    // ------------------------------------------------------------------ 15
    @Test
    void changedValuesYieldsFieldAndCurrentPairs() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = TrackingTestFixtures.tracking();
        array.set(TrackingTestFixtures.Fields.firstName.ordinal(), "Jane");
        array.set(TrackingTestFixtures.Fields.age.ordinal(), 42);

        List<FieldChange<TrackingTestFixtures.Fields>> changes = array.changedValues();

        Assertions.assertEquals(2, changes.size());
        Assertions.assertEquals(TrackingTestFixtures.Fields.firstName, changes.get(0).field());
        Assertions.assertEquals("Jane", changes.get(0).current());
        Assertions.assertEquals("firstName", changes.get(0).fieldName());
        Assertions.assertEquals(TrackingTestFixtures.Fields.age, changes.get(1).field());
        Assertions.assertEquals(42, changes.get(1).current());
    }

    @Test
    void changedValuesIsEmptyWhenNothingChanged() {
        Assertions.assertTrue(TrackingTestFixtures.tracking().changedValues().isEmpty());
    }

    // ------------------------------------------------------------------ 16
    /**
     * The former shallow-reference hazard is gone by construction, and this test pins that instead.
     *
     * <p>The old design recorded the replaced value as a shallow reference, so mutating a collection
     * in place afterwards made the recorded "previous" value reflect the mutation — a hazard the
     * guide had to warn about. With no previous value kept, there is nothing to go stale: the
     * tracker reports the value the field holds now, by reference, and the caller's own baseline is
     * the only other copy in play.</p>
     */
    @Test
    void noStaleReferenceExistsBecauseNoPreviousValueIsKept() {
        Object[] baseline = {"id", "one", "two", "three", "four", "five", "six"};
        Map<String, Object> baselineValue = TrackingTestFixtures.map("k", "five");
        baseline[TrackingTestFixtures.Fields.metadata.ordinal()] = baselineValue;
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array =
                EntityUpdateTrackingArray.create(
                        TrackingTestFixtures.ORDINALS, TrackingTestFixtures.Fields.values(), baseline.clone());
        int ord = TrackingTestFixtures.Fields.metadata.ordinal();

        Map<String, Object> replacement = TrackingTestFixtures.map("k", "first");
        array.set(ord, replacement);

        Assertions.assertTrue(array.changes().has(ord));
        List<FieldChange<TrackingTestFixtures.Fields>> replaced = array.changedValues();
        Assertions.assertEquals(1, replaced.size());
        Assertions.assertEquals(TrackingTestFixtures.Fields.metadata, replaced.get(0).field());
        Assertions.assertSame(replacement, replaced.get(0).current(),
                "the reported value is the one the field holds, by reference");

        // Mutating the replacement in place changes what the field holds — there is no recorded
        // second copy that could disagree with it.
        replacement.put("k", "mutated-in-place");
        Assertions.assertSame(replacement, array.currentValue(TrackingTestFixtures.Fields.metadata));
        Assertions.assertEquals("five", baselineValue.get("k"),
                "and the caller's baseline keeps its own contents");
        Assertions.assertTrue(array.isChanged(), "the field stays marked across further writes");
    }

    // ------------------------------------------------------------------ 17
    @Test
    void clearChangesEmptiesTheChangeSet() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = TrackingTestFixtures.tracking();
        int ord = TrackingTestFixtures.Fields.firstName.ordinal();
        array.set(ord, "changed");
        Assertions.assertTrue(array.isChanged());

        array.clearChanges();

        Assertions.assertFalse(array.isChanged());
        Assertions.assertTrue(array.changes().isEmpty());
        Assertions.assertEquals("changed", array.currentValue(TrackingTestFixtures.Fields.firstName),
                "clearing the change set does not revert the value — the caller's baseline is what "
                        + "differs");
    }

    // ------------------------------------------------------------------ S5 steps 8-12
    @Test
    void stateIsSharedBetweenChangesAndChangesBuilder() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = TrackingTestFixtures.tracking();
        int ord = TrackingTestFixtures.Fields.firstName.ordinal();

        // 8: fresh tracking view
        Assertions.assertTrue(array.changes().isEmpty());
        Assertions.assertFalse(array.isChanged());

        // 9: one real change - both accessors agree
        array.set(ord, "changed");
        Assertions.assertTrue(array.changesBuilder().has(ord));
        Assertions.assertTrue(array.changes().has(ord));

        // 11 (captured before 10): a snapshot is a time snapshot, not a live alias
        EEnumSet<TrackingTestFixtures.Fields> before = array.changes();
        Assertions.assertTrue(before.has(ord));

        // 10: mutation through the builder is visible through a later changes()
        array.changesBuilder().removeOrdinal(ord);
        Assertions.assertFalse(array.changes().has(ord),
                "mutation through changesBuilder() must be visible through changes()");
        Assertions.assertFalse(array.isChanged());

        // 11: the earlier snapshot still reports the old state
        Assertions.assertTrue(before.has(ord), "changes() is a snapshot, not a live alias");

        // 12: clearChanges empties both accessors
        array.set(ord, "again");
        Assertions.assertTrue(array.isChanged());
        array.clearChanges();
        Assertions.assertTrue(array.changes().isEmpty());
        Assertions.assertTrue(array.changesBuilder().isEmpty());
        Assertions.assertFalse(array.isChanged());
    }

    // ------------------------------------------------------------------ D5 (18), array half
    @Test
    void unknownFieldNameReturnsMinusOne() {
        EntityUpdateTrackingArray<Object, TrackingTestFixtures.Fields> array = TrackingTestFixtures.tracking();
        Assertions.assertEquals(-1, array.set("noSuchField", "x"));
        Assertions.assertEquals(1, array.set("firstName", "changed"));
        Assertions.assertNull(array.get("noSuchField"));
        Assertions.assertFalse(array.supports("noSuchField"));
        Assertions.assertTrue(array.supports("firstName"));
    }

    /**
     * The tracking contract has no previous-value surface left, asserted executably.
     *
     * <p>The compiler already enforces this for every call site in the tree; this pins the intent so
     * that re-adding an accessor is a visible decision rather than a quiet regression. The comparison
     * a consumer wants is a comparison of <em>two instances</em>, and the caller owns both.</p>
     */
    @Test
    void theTrackingContractExposesNoPreviousValueAccessor() throws Exception {
        Assertions.assertThrows(NoSuchMethodException.class,
                () -> ViewChangeTracking.class.getMethod("previousValue", int.class));
        Assertions.assertThrows(NoSuchMethodException.class,
                () -> ViewChangeTracking.class.getMethod("hasPreviousValue", int.class));
        Assertions.assertThrows(NoSuchMethodException.class,
                () -> ViewChangeTracking.class.getMethod("diff"));
        Assertions.assertNotNull(ViewChangeTracking.class.getMethod("changedValues"),
                "the current half is the whole of the reported change");
    }

    @Test
    void forNameOrdinalRejectsUnknownNames() {
        ForNameOrdinal ordinals = new ForNameOrdinalImpl<>(TrackingTestFixtures.Fields.class);
        Assertions.assertEquals(1, ordinals.forNameOrdinal("firstName"));
        Assertions.assertEquals(-1, ordinals.forNameOrdinal("noSuchField"));
    }
}
