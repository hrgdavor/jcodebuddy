package hr.hrg.hipster.entity.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EEnumSetBuilderTest {

    @Test
    void uses64BuilderFor64ValueEnum() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        assertTrue(builder instanceof EEnumSetBuilder64);

        assertTrue(builder.add(EnumTestUtil.Enum64.E00));
        assertTrue(builder.add(EnumTestUtil.Enum64.E63));
        assertFalse(builder.add(EnumTestUtil.Enum64.E63));

        EEnumSet<EnumTestUtil.Enum64> immutable = builder.toImmutable();
        assertTrue(immutable.has(EnumTestUtil.Enum64.E00));
        assertTrue(immutable.has(EnumTestUtil.Enum64.E63));
        assertEquals(2, immutable.size());
    }

    @Test
    void usesLargeBuilderFor65ValueEnum() {
        EEnumSetBuilder<EnumTestUtil.Enum65> builder = EEnumSetBuilder.create(EnumTestUtil.Enum65.class);
        assertTrue(builder instanceof EEnumSetBuilderLarge);

        assertTrue(builder.add(EnumTestUtil.Enum65.E64));
        EEnumSet<EnumTestUtil.Enum65> immutable = builder.toImmutable();
        assertTrue(immutable.has(EnumTestUtil.Enum65.E64));
        assertEquals(1, immutable.size());
    }

    @Test
    void addAllAndRemoveAllIterableWorks() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.addAll(java.util.List.of(EnumTestUtil.Enum64.E01, EnumTestUtil.Enum64.E03));

        assertTrue(builder.has(EnumTestUtil.Enum64.E01));
        assertTrue(builder.has(EnumTestUtil.Enum64.E03));
        assertEquals(2, builder.size());

        builder.removeAll(java.util.List.of(EnumTestUtil.Enum64.E01));
        assertFalse(builder.has(EnumTestUtil.Enum64.E01));
        assertTrue(builder.has(EnumTestUtil.Enum64.E03));
        assertEquals(1, builder.size());
    }

    @Test
    void retainAllWorks() {
        EEnumSetBuilder<EnumTestUtil.Enum64> builder = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        builder.add(EnumTestUtil.Enum64.E01);
        builder.add(EnumTestUtil.Enum64.E03);

        EEnumSetBuilder<EnumTestUtil.Enum64> other = EEnumSetBuilder.create(EnumTestUtil.Enum64.class);
        other.add(EnumTestUtil.Enum64.E03);

        builder.retainAll(other);

        assertFalse(builder.has(EnumTestUtil.Enum64.E01));
        assertTrue(builder.has(EnumTestUtil.Enum64.E03));
        assertEquals(1, builder.size());
    }
}
