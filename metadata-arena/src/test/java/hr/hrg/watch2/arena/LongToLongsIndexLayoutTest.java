package hr.hrg.watch2.arena;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntFunction;

/**
 * {@link LongToLongsIndex}'s own contracts: the sizing requirement it places on an arena, how a key's
 * value list grows, what {@code totalSize()} counts, and the two arguments it refuses.
 *
 * <p>The sizing requirement is the one that is invisible until it bites. The index writes its fixed region
 * (header, key table, value pointers) <strong>directly</strong> into the arena's storage and allocates only
 * the data area — which is why {@code totalSize()} is {@code fixedRegion + arena.size()} rather than just
 * {@code arena.size()}. The consequence is that the arena must have room for the fixed region
 * <em>in addition to</em> what the index allocates, and an arena sized to exactly the fixed region fails on
 * the first {@code put} with an {@code IndexOutOfBoundsException} from deep inside the memory view. That is
 * a requirement on the caller, so it is documented in the class javadoc and asserted here — a caller who
 * trips it deserves a test that explains it rather than a stack trace that does not.</p>
 *
 * <p>Both backends are exercised, because the module's README claims the index works over either and that
 * claim is worth a test of its own.</p>
 */
class LongToLongsIndexLayoutTest {

    private static List<NamedArena> arenas() {
        return List.of(
                new NamedArena("ByteBufferArena", capacity -> new ByteBufferArena(capacity)),
                new NamedArena("FfmArena", capacity -> new FfmArena(capacity)));
    }

    private record NamedArena(String name, IntFunction<Arena> factory) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** The fixed region for a capacity, spelled the way the format spells it. */
    private static long fixedRegion(int capacity) {
        return CompactIndexFormat.HEADER_SIZE + 16L * capacity;
    }

    @Test
    void theIndexRunsOverBothBackends() {
        for (NamedArena named : arenas()) {
            try (Arena arena = named.factory().apply(4096);
                 LongToLongsIndex index = new LongToLongsIndex(arena, 16)) {
                for (int i = 1; i <= 10; i++) {
                    index.put(i, i * 11L);
                    index.put(i, i * 12L);
                }
                for (int i = 1; i <= 10; i++) {
                    Assertions.assertArrayEquals(new long[]{i * 11L, i * 12L}, index.get(i),
                            named.name() + ": key " + i);
                }
                Assertions.assertEquals(10, index.size(), named.name());
            }
        }
    }

    @Test
    void anArenaSizedToTheFixedRegionOnlyFailsLoudlyOnTheFirstValue() {
        for (NamedArena named : arenas()) {
            int capacity = 4;
            try (Arena arena = named.factory().apply((int) fixedRegion(capacity));
                 LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
                Assertions.assertEquals(0, index.size(),
                        named.name() + ": the fixed region itself fits, so construction is fine");
                Assertions.assertThrows(IndexOutOfBoundsException.class, () -> index.put(1, 10),
                        named.name() + ": the data area needs room beyond the fixed region, and the caller "
                                + "is who must leave it");
            }
        }
    }

    @Test
    void anArenaWithRoomForTheDataAreaWorks() {
        for (NamedArena named : arenas()) {
            int capacity = 4;
            // 200 values of one key: 4 + 8*200 bytes of data, well past the fixed region.
            try (Arena arena = named.factory().apply((int) (fixedRegion(capacity) + 8192));
                 LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
                index.put(1, 42);
                Assertions.assertArrayEquals(new long[]{42}, index.get(1), named.name());
            }
        }
    }

    @Test
    void aValueListGrowsByCopyingAndKeepsInsertionOrder() {
        // 200 values for one key means the data area grows to sum(4 + 8k) for k = 1..200 ≈ 162 KB, and the
        // index allocates a fresh list per value rather than growing the old one, so the arena has to be
        // sized for that whole area — the sizing requirement asserted elsewhere in this class.
        try (ByteBufferArena arena = new ByteBufferArena(262144);
             LongToLongsIndex index = new LongToLongsIndex(arena, 8)) {
            for (int i = 0; i < 200; i++) {
                index.put(42, i);
            }

            long[] values = index.get(42);
            Assertions.assertEquals(200, values.length, "every value is kept");
            for (int i = 0; i < 200; i++) {
                Assertions.assertEquals(i, values[i], "values keep insertion order at index " + i);
            }
            Assertions.assertEquals(1, index.size(), "one key, however many values it has");
        }
    }

    @Test
    void totalSizeIsTheFixedRegionPlusTheDataArea() {
        int capacity = 16;
        try (ByteBufferArena arena = new ByteBufferArena(65536);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
            long fixed = fixedRegion(capacity);
            Assertions.assertEquals(fixed, index.totalSize(),
                    "with no data the total is the fixed region, which the arena did not allocate");

            index.put(1, 1);
            Assertions.assertEquals(fixed + 12, index.totalSize(),
                    "one value costs a 4-byte count and 8 bytes of value");

            index.put(1, 2);
            Assertions.assertEquals(fixed + 12 + 20, index.totalSize(),
                    "and a second value builds a new two-entry list (4 + 16) rather than growing in place, "
                            + "because nothing in this design deallocates per entry");
        }
    }

    @Test
    void zeroIsReservedAsTheEmptySlotMarker() {
        try (ByteBufferArena arena = new ByteBufferArena(4096);
             LongToLongsIndex index = new LongToLongsIndex(arena, 8)) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> index.put(0, 1),
                    "key 0 is the empty-slot marker in the key table, so it cannot also be a key");
            Assertions.assertArrayEquals(new long[0], index.get(0),
                    "and asking for it is an empty answer rather than a probe into slot 0");
        }
    }

    @Test
    void theConstructorRefusesAnUnusableArenaOrCapacity() {
        try (ByteBufferArena arena = new ByteBufferArena(4096)) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new LongToLongsIndex(arena, 3),
                    "the hash table masks with capacity - 1, so a non-power-of-two silently loses slots");
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new LongToLongsIndex(arena, 0));
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> new LongToLongsIndex(null, 4));
    }

    @Test
    void aClosedIndexSaysSoInsteadOfReadingFreedMemory() {
        ByteBufferArena arena = new ByteBufferArena(4096);
        LongToLongsIndex index = new LongToLongsIndex(arena, 8);
        index.put(1, 10);
        index.close();
        index.close(); // idempotent: resource teardown happens in try-with-resources beside other work

        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class, () -> index.get(1));
        Assertions.assertTrue(failure.getMessage().contains("closed"), failure.getMessage());
        Assertions.assertThrows(IllegalStateException.class, index::keys);
    }
}
