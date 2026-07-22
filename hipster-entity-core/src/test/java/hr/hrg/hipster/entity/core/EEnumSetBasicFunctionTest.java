package hr.hrg.hipster.entity.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EEnumSetBasicFunctionTest {

    @Test
    void builder64MarkUnmarkAndClearUpdateState() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);

        assertTrue(builder.isEmpty());
        assertEquals(0, builder.size());

        assertTrue(builder.add(EnumTestUtil.Enum64.E00));
        assertTrue(builder.add(EnumTestUtil.Enum64.E63));
        assertFalse(builder.add(EnumTestUtil.Enum64.E63));

        assertTrue(builder.has(EnumTestUtil.Enum64.E00));
        assertTrue(builder.has(EnumTestUtil.Enum64.E63));
        assertEquals(2, builder.size());

        assertTrue(builder.remove(EnumTestUtil.Enum64.E00));
        assertFalse(builder.remove(EnumTestUtil.Enum64.E00));
        assertEquals(1, builder.size());

        builder.clear();
        assertTrue(builder.isEmpty());
        assertEquals(0, builder.size());
    }

    @Test
    void builderLargeMarksAcrossTwoSegments() {
        EEnumSetBuilder<EnumTestUtil.Enum65> builder = EEnumSetBuilder.create(EnumTestUtil.Enum65.class);

        assertTrue(builder.add(EnumTestUtil.Enum65.E00));
        assertTrue(builder.add(EnumTestUtil.Enum65.E64));

        assertTrue(builder.has(EnumTestUtil.Enum65.E00));
        assertTrue(builder.has(EnumTestUtil.Enum65.E64));
        assertEquals(2, builder.size());

        assertTrue(builder.remove(EnumTestUtil.Enum65.E64));
        assertFalse(builder.has(EnumTestUtil.Enum65.E64));
        assertEquals(1, builder.size());
    }

    @Test
    void addOrdinalChangeReturnsFalseWhenValuesAreEqual() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);

        assertFalse(builder.addOrdinalChange(EnumTestUtil.Enum64.E05.ordinal(), "same", "same"));
        assertFalse(builder.has(EnumTestUtil.Enum64.E05));
    }

    @Test
    void addOrdinalChangeReturnsTrueWhenValueChangesEvenIfOrdinalAlreadyPresent() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);

        assertTrue(builder.addOrdinalChange(EnumTestUtil.Enum64.E05.ordinal(), "old", "new"));
        assertTrue(builder.addOrdinalChange(EnumTestUtil.Enum64.E05.ordinal(), "old2", "new2"));
        assertTrue(builder.has(EnumTestUtil.Enum64.E05));
    }

    @Test
    void toImmutableReturnsEmptySetWhenNothingMarked() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);

        EEnumSet<EnumTestUtil.Enum64> immutable = builder.toImmutable();

        assertInstanceOf(EEnumSetEmpty.class, immutable);
        assertTrue(immutable.isEmpty());
    }

    @Test
    void toImmutableAndBackToBuilderPreservesBitsFor64() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.add(EnumTestUtil.Enum64.E01);
        builder.add(EnumTestUtil.Enum64.E63);

        EEnumSet<EnumTestUtil.Enum64> immutable = builder.toImmutable();
        EEnumSetBuilder<EnumTestUtil.Enum64> roundTrip = immutable.toBuilder();

        assertTrue(roundTrip.has(EnumTestUtil.Enum64.E01));
        assertTrue(roundTrip.has(EnumTestUtil.Enum64.E63));
        assertEquals(2, roundTrip.size());
    }

    @Test
    void toImmutableAndBackToBuilderPreservesBitsForLarge() {
        EEnumSetBuilder<EnumTestUtil.Enum65> builder = EEnumSetBuilder.create(EnumTestUtil.Enum65.class);
        builder.add(EnumTestUtil.Enum65.E02);
        builder.add(EnumTestUtil.Enum65.E64);

        EEnumSet<EnumTestUtil.Enum65> immutable = builder.toImmutable();
        EEnumSetBuilder<EnumTestUtil.Enum65> roundTrip = immutable.toBuilder();

        assertTrue(roundTrip.has(EnumTestUtil.Enum65.E02));
        assertTrue(roundTrip.has(EnumTestUtil.Enum65.E64));
        assertEquals(2, roundTrip.size());
    }

    @Test
    void hasAnyAndHasAllWorkForOverlappingSets() {
        EEnumSetBuilder<EnumTestUtil.Enum64> left = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        left.add(EnumTestUtil.Enum64.E01);
        left.add(EnumTestUtil.Enum64.E02);

        EEnumSetBuilder<EnumTestUtil.Enum64> overlap = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        overlap.add(EnumTestUtil.Enum64.E02);

        EEnumSetBuilder<EnumTestUtil.Enum64> disjoint = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        disjoint.add(EnumTestUtil.Enum64.E10);

        assertTrue(left.hasAny(overlap));
        assertTrue(left.hasAll(overlap));

        assertFalse(left.hasAny(disjoint));
        assertFalse(left.hasAll(disjoint));
    }

    @Test
    void forEachEnumeratesMarkedValuesInOrdinalOrderForBuilder64() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.add(EnumTestUtil.Enum64.E10);
        builder.add(EnumTestUtil.Enum64.E00);
        builder.add(EnumTestUtil.Enum64.E03);

        List<EnumTestUtil.Enum64> visited = new ArrayList<>();
        builder.forEach((value, index) -> visited.add(value));

        assertEquals(List.of(EnumTestUtil.Enum64.E00, EnumTestUtil.Enum64.E03, EnumTestUtil.Enum64.E10), visited);
        assertEquals(visited.size(), builder.size());
    }

    @Test
    void hasReturnsFalseForNegativeAndOutOfRangeOnLarge() {
        EEnumSetBuilder<EnumTestUtil.Enum65> builder = EEnumSetBuilder.create(EnumTestUtil.Enum65.class);

        assertFalse(builder.has(-1));
        assertFalse(builder.has(65));
        assertFalse(builder.has(999));
    }

    @Test
    void setOrdinalUsesCalculatedBooleanValue() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);

        boolean shouldSet = 5 > 3;
        assertTrue(builder.setOrdinal(EnumTestUtil.Enum64.E05.ordinal(), shouldSet));
        assertTrue(builder.has(EnumTestUtil.Enum64.E05));

        boolean shouldUnset = 2 > 7;
        assertTrue(builder.setOrdinal(EnumTestUtil.Enum64.E05.ordinal(), shouldUnset));
        assertFalse(builder.has(EnumTestUtil.Enum64.E05));
    }

    @Test
    void enumSet64GetAndToStringReflectMarkedValues() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.add(EnumTestUtil.Enum64.E01);
        builder.add(EnumTestUtil.Enum64.E03);

        EEnumSet<EnumTestUtil.Enum64> immutable = builder.toImmutable();

        assertEquals("[E01,E03]", immutable.toString());
        assertArrayEquals(
                new EnumTestUtil.Enum64[]{EnumTestUtil.Enum64.E01, EnumTestUtil.Enum64.E03},
                immutable.toArray(new EnumTestUtil.Enum64[0]));

        List<EnumTestUtil.Enum64> visited = new ArrayList<>();
        immutable.forEach((value, idx) -> visited.add(value));
        assertEquals(List.of(EnumTestUtil.Enum64.E01, EnumTestUtil.Enum64.E03), visited);
    }

    @Test
    void enumSetLargeGetAndToStringReflectMarkedValues() {
        EEnumSetBuilder<EnumTestUtil.Enum65> builder = EEnumSetBuilder.create(EnumTestUtil.Enum65.class);
        builder.add(EnumTestUtil.Enum65.E02);
        builder.add(EnumTestUtil.Enum65.E64);

        EEnumSet<EnumTestUtil.Enum65> immutable = builder.toImmutable();

        assertEquals("[E02,E64]", immutable.toString());
        assertArrayEquals(
                new EnumTestUtil.Enum65[]{EnumTestUtil.Enum65.E02, EnumTestUtil.Enum65.E64},
                immutable.toArray(new EnumTestUtil.Enum65[0]));

        List<EnumTestUtil.Enum65> visited = new ArrayList<>();
        immutable.forEach((value, idx) -> visited.add(value));
        assertEquals(List.of(EnumTestUtil.Enum65.E02, EnumTestUtil.Enum65.E64), visited);
    }

    @Test
    void emptyEnumSetHasAllTrueForEmptyOther() {
        EEnumSet<EnumTestUtil.Enum64> empty = new EEnumSetEmpty<>(EnumTestUtil.Enum64.class);
        EEnumSet<EnumTestUtil.Enum64> otherEmpty = new EEnumSetEmpty<>(EnumTestUtil.Enum64.class);
        EEnumSetBuilder<EnumTestUtil.Enum64> nonEmptyBuilder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        nonEmptyBuilder.add(EnumTestUtil.Enum64.E00);

        assertTrue(empty.hasAll(otherEmpty));
        assertFalse(empty.hasAll(nonEmptyBuilder.toImmutable()));
    }

    @Test
    void enumSetEqualityAndHashCodeBehaviors() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builderA = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builderA.add(EnumTestUtil.Enum64.E01);
        builderA.add(EnumTestUtil.Enum64.E03);
        EEnumSet<EnumTestUtil.Enum64> immutableA = builderA.toImmutable();

        EEnumSetBuilder<EnumTestUtil.Enum64> builderB = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builderB.add(EnumTestUtil.Enum64.E03);
        builderB.add(EnumTestUtil.Enum64.E01);
        EEnumSet<EnumTestUtil.Enum64> immutableB = builderB.toImmutable();

        assertEquals(immutableA, immutableB);
        assertEquals(immutableA.hashCode(), immutableB.hashCode());
    }

    @Test
    void enumSetToEnumSetConversion() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.add(EnumTestUtil.Enum64.E02);
        builder.add(EnumTestUtil.Enum64.E05);

        EnumSet<EnumTestUtil.Enum64> converted = builder.toEnumSet();

        assertTrue(converted.contains(EnumTestUtil.Enum64.E02));
        assertTrue(converted.contains(EnumTestUtil.Enum64.E05));
        assertFalse(converted.contains(EnumTestUtil.Enum64.E01));
        assertEquals(2, converted.size());

        EEnumSet<EnumTestUtil.Enum64> immutable = builder.toImmutable();
        assertEquals(converted, immutable.toEnumSet());
    }

    @Test
    void builderAddAllRemoveAllChainability() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);

        builder.addAll(EnumTestUtil.Enum64.E01, EnumTestUtil.Enum64.E03, EnumTestUtil.Enum64.E05)
            .removeAll(EnumTestUtil.Enum64.E03);

        assertTrue(builder.has(EnumTestUtil.Enum64.E01));
        assertFalse(builder.has(EnumTestUtil.Enum64.E03));
        assertTrue(builder.has(EnumTestUtil.Enum64.E05));
        assertEquals(2, builder.size());

        EEnumSet<EnumTestUtil.Enum64> snapshot = builder.toImmutable();
        assertEquals(2, snapshot.size());
    }

    @Test
    void builderOfFactoryCreatesExpectedSet() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.of(EnumTestUtil.Enum64.class, EnumTestUtil.Enum64.E02, EnumTestUtil.Enum64.E04);

        assertTrue(builder.has(EnumTestUtil.Enum64.E02));
        assertTrue(builder.has(EnumTestUtil.Enum64.E04));
        assertFalse(builder.has(EnumTestUtil.Enum64.E03));
        assertEquals(2, builder.size());
    }

    @Test
    void toArrayWithDifferentInputSizesBehavesLikeCollectionToArray() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.add(EnumTestUtil.Enum64.E01);
        builder.add(EnumTestUtil.Enum64.E02);

        EEnumSet<EnumTestUtil.Enum64> immutable = builder.toImmutable();

        EnumTestUtil.Enum64[] tooSmall = immutable.toArray(new EnumTestUtil.Enum64[1]);
        assertArrayEquals(new EnumTestUtil.Enum64[]{EnumTestUtil.Enum64.E01, EnumTestUtil.Enum64.E02}, tooSmall);

        EnumTestUtil.Enum64[] exact = immutable.toArray(new EnumTestUtil.Enum64[2]);
        assertArrayEquals(new EnumTestUtil.Enum64[]{EnumTestUtil.Enum64.E01, EnumTestUtil.Enum64.E02}, exact);

        EnumTestUtil.Enum64[] tooLarge = immutable.toArray(new EnumTestUtil.Enum64[4]);
        assertArrayEquals(new EnumTestUtil.Enum64[]{EnumTestUtil.Enum64.E01, EnumTestUtil.Enum64.E02, null, null}, tooLarge);
    }

    @Test
    void enumSetAllBehaviour() {
        EEnumSet<EnumTestUtil.Enum64> all = new EEnumSetAll<>(EnumTestUtil.Enum64.class);
        EEnumSetBuilder<EnumTestUtil.Enum64> someBuilder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        someBuilder.add(EnumTestUtil.Enum64.E05);
        EEnumSet<EnumTestUtil.Enum64> some = someBuilder.toImmutable();

        assertEquals(EnumTestUtil.Enum64.values().length, all.size());
        assertTrue(all.has(EnumTestUtil.Enum64.E00));
        assertTrue(all.hasAny(some));
        assertTrue(all.hasAll(some));
        assertTrue(all.hasAll(all));

        EEnumSet<EnumTestUtil.Enum64> allBuilderSnapshot = all.toBuilder().toImmutable();
        assertEquals(all, allBuilderSnapshot);
    }

    @Test
    void enumSetEmptyBehaviour() {
        EEnumSet<EnumTestUtil.Enum64> empty = new EEnumSetEmpty<>(EnumTestUtil.Enum64.class);
        assertTrue(empty.isEmpty());

        EEnumSetBuilder<EnumTestUtil.Enum64> nonEmptyBuilder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        nonEmptyBuilder.add(EnumTestUtil.Enum64.E00);
        assertFalse(empty.hasAny(nonEmptyBuilder.toImmutable()));

        EEnumSet<EnumTestUtil.Enum64> emptyFromBuilder = empty.toBuilder().toImmutable();
        assertInstanceOf(EEnumSetEmpty.class, emptyFromBuilder);
        assertTrue(emptyFromBuilder.hasAll(empty));
        assertFalse(emptyFromBuilder.hasAny(empty));
    }

    @Test
    void immutableRoundTripDoesNotMutateOriginal() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.add(EnumTestUtil.Enum64.E10);
        EEnumSet<EnumTestUtil.Enum64> immutable = builder.toImmutable();

        EEnumSetBuilder<EnumTestUtil.Enum64> roundBuilder = immutable.toBuilder();
        roundBuilder.remove(EnumTestUtil.Enum64.E10);

        assertTrue(immutable.has(EnumTestUtil.Enum64.E10));
        assertFalse(roundBuilder.has(EnumTestUtil.Enum64.E10));
    }

    @Test
    void copyOfReadVariantPaths() {
        EEnumSet<EnumTestUtil.Enum64> empty = EEnumSetEmpty.of(EnumTestUtil.Enum64.class);
        assertSame(empty, EEnumSet.copyOf(empty));

        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.add(EnumTestUtil.Enum64.E03);
        EEnumSet<EnumTestUtil.Enum64> snapshot = EEnumSet.copyOf(builder);
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.has(EnumTestUtil.Enum64.E03));
    }

    @Test
    void toListAndToEnumSetReturnOrderedValues() {
        EEnumSetBuilder<EnumTestUtil.Enum65> builder = EEnumSetBuilder.create(EnumTestUtil.Enum65.class);
        builder.add(EnumTestUtil.Enum65.E01);
        builder.add(EnumTestUtil.Enum65.E03);
        EEnumSet<EnumTestUtil.Enum65> set = builder.toImmutable();

        assertEquals(List.of(EnumTestUtil.Enum65.E01, EnumTestUtil.Enum65.E03), set.toList());
        assertEquals(EnumSet.of(EnumTestUtil.Enum65.E01, EnumTestUtil.Enum65.E03), set.toEnumSet());
    }

    @Test
    void hasAnyAndHasAllWithNullAndCrossClassReturnFalse() {
        EEnumSetBuilder<EnumTestUtil.Enum64> tempBuilder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        tempBuilder.add(EnumTestUtil.Enum64.E01);
        EEnumSet<EnumTestUtil.Enum64> set = tempBuilder.toImmutable();

        assertFalse(set.hasAny(null));
        assertFalse(set.hasAll(null));
    }

    @Test
    void setOpsRejectDifferentEnumClass() {
        EEnumSetBuilder<EnumTestUtil.Enum64> b64 = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        b64.add(EnumTestUtil.Enum64.E00);
        EEnumSet<EnumTestUtil.Enum64> a64 = b64.toImmutable();

        EEnumSetBuilder<EnumTestUtil.Enum65> b65 = EEnumSetBuilder.create(EnumTestUtil.Enum65.class);
        b65.add(EnumTestUtil.Enum65.E00);
        EEnumSet<EnumTestUtil.Enum65> a65 = b65.toImmutable();

        assertFalse(a64.hasAny((EEnumSetRead<EnumTestUtil.Enum64>) (Object) a65));
        assertFalse(a64.hasAll((EEnumSetRead<EnumTestUtil.Enum64>) (Object) a65));

        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> a64.union((EEnumSetRead<EnumTestUtil.Enum64>) (Object) a65));
        assertEquals("enum class mismatch or null", e1.getMessage());
    }

    @Test
    void largeEnumToArrayAndToEnumSetConsistency() {
        EEnumSetBuilder<EnumTestUtil.Enum65> builder = EEnumSetBuilder.create(EnumTestUtil.Enum65.class);
        builder.add(EnumTestUtil.Enum65.E01);
        builder.add(EnumTestUtil.Enum65.E64);
        builder.add(EnumTestUtil.Enum65.E63);

        EEnumSet<EnumTestUtil.Enum65> immutable = builder.toImmutable();
        EnumTestUtil.Enum65[] ary = immutable.toArray(new EnumTestUtil.Enum65[0]);
        assertArrayEquals(new EnumTestUtil.Enum65[]{EnumTestUtil.Enum65.E01, EnumTestUtil.Enum65.E63, EnumTestUtil.Enum65.E64}, ary);

        assertEquals(EnumSet.of(EnumTestUtil.Enum65.E01, EnumTestUtil.Enum65.E63, EnumTestUtil.Enum65.E64), immutable.toEnumSet());
    }
}

