package hr.hrg.hipster.entity.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EEnumSet64Task1Test {

    @Test
    void accepts64DistinctValuesIncludingOrdinal63() {
        EnumTestUtil.Enum64[] all64 = EnumTestUtil.Enum64.values();

        EEnumSet64<EnumTestUtil.Enum64> set = assertDoesNotThrow(() -> new EEnumSet64<>(EnumTestUtil.Enum64.class, all64));

        assertEquals(64, set.size());
        assertTrue(set.has(EnumTestUtil.Enum64.E63));
    }

    @Test
    void rejectsOrdinal64InConstructor() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new EEnumSet64<>(EnumTestUtil.Enum65.class, EnumTestUtil.Enum65.values()));

        assertTrue(ex.getMessage().contains("at most 64 values"));
    }

    @Test
    void hasReturnsFalseForOutOfRangeOrdinals() {
        EEnumSet64<EnumTestUtil.Enum64> set = new EEnumSet64<>(EnumTestUtil.Enum64.class, EnumTestUtil.Enum64.values());

        assertFalse(set.has(64));
        assertFalse(set.has(128));
        assertFalse(set.has(-1));
    }
}
