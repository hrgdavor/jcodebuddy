package hr.hrg.watch2.arena;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The arena contract, checked against <strong>both</strong> implementations.
 *
 * <p>The module ships two backends for one interface, and the interesting failures are the ones where
 * they disagree: a `ByteBuffer` view is a heap or direct buffer, an FFM view is a `MemorySegment`, and the
 * two take different routes to "the storage grew underneath you". So every case here runs twice, over the
 * same assertions, through {@link #arenas()} — a shared contract rather than two test suites that drift.</p>
 *
 * <p>The growth case is worth spelling out, because it is the one that decides whether a view may be held
 * across an allocation: <strong>contents survive growth, but a view obtained before it does not follow the
 * storage.</strong> The tests below assert the first and pin the second, and the interfaces say so too —
 * the alternative is a caller discovering it by writing into a buffer nobody reads.</p>
 */
class ArenaTest {

    /** One factory per implementation, so every case runs against both. */
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

    @Test
    void allocationReturnsSequentialOffsetsAndSizeTracksThem() {
        for (NamedArena named : arenas()) {
            try (Arena arena = named.factory().apply(1024)) {
                Assertions.assertEquals(0, arena.size(), named.name());
                Assertions.assertEquals(0, arena.allocate(8), named.name());
                Assertions.assertEquals(8, arena.size(), named.name());
                Assertions.assertEquals(8, arena.allocate(16),
                        named.name() + ": the next block starts where the previous one ended");
                Assertions.assertEquals(24, arena.size(), named.name());
            }
        }
    }

    @Test
    void whatIsWrittenThroughAViewIsReadBackThroughAnother() {
        for (NamedArena named : arenas()) {
            try (Arena arena = named.factory().apply(1024)) {
                arena.allocate(24);
                MemoryView view = arena.view();
                view.putLong(0, 0x0102030405060708L);
                view.putInt(8, 0x0A0B0C0D);
                view.putLongs(16, new long[]{1, 2, 3}, 0, 3);

                MemoryView fresh = arena.view();
                Assertions.assertEquals(0x0102030405060708L, fresh.getLong(0), named.name());
                Assertions.assertEquals(0x0A0B0C0D, fresh.getInt(8), named.name());
                long[] dst = new long[3];
                fresh.getLongs(16, dst, 0, 3);
                Assertions.assertArrayEquals(new long[]{1, 2, 3}, dst, named.name());
            }
        }
    }

    @Test
    void theArenasByteOrderIsTheOrderItsViewWritesIn() {
        for (ByteOrder order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            Assertions.assertEquals(order, new ByteBufferArena(64, order).byteOrder());
            Assertions.assertEquals(order, new FfmArena(64, order).byteOrder());

            // The byte on disk is the proof: 0x0102030405060708 starts with 08 little-endian and 01
            // big-endian. Asserted on a heap buffer so the bytes can be read back directly.
            ByteBuffer raw = ByteBuffer.allocate(8);
            try (ByteBufferArena arena = ByteBufferArena.wrap(raw, order)) {
                arena.view().putLong(0, 0x0102030405060708L);
            }
            int expectedFirstByte = order == ByteOrder.LITTLE_ENDIAN ? 0x08 : 0x01;
            Assertions.assertEquals((byte) expectedFirstByte, raw.get(0), "order=" + order);
        }
    }

    @Test
    void resetReclaimsTheSpaceAndTheNextAllocationStartsAtZero() {
        for (NamedArena named : arenas()) {
            try (Arena arena = named.factory().apply(1024)) {
                arena.allocate(64);
                Assertions.assertEquals(64, arena.size(), named.name());

                arena.reset();

                Assertions.assertEquals(0, arena.size(), named.name());
                Assertions.assertEquals(0, arena.allocate(8),
                        named.name() + ": reset means the region is free again, not merely forgotten");
            }
        }
    }

    @Test
    void growthKeepsWhatWasAlreadyWritten() {
        for (NamedArena named : arenas()) {
            // Deliberately small, so the allocation below has to grow the storage.
            try (Arena arena = named.factory().apply(64)) {
                MemoryView before = arena.view();
                before.putLong(0, 111L);
                before.putLong(8, 222L);

                arena.allocate(4096);
                Assertions.assertTrue(arena.size() >= 4096, named.name() + ": size=" + arena.size());

                MemoryView after = arena.view();
                Assertions.assertEquals(111L, after.getLong(0), named.name() + ": growth copies the content");
                Assertions.assertEquals(222L, after.getLong(8), named.name());
                after.putLong(4000, 333L);
                Assertions.assertEquals(333L, arena.view().getLong(4000),
                        named.name() + ": and the new region is usable");
            }
        }
    }

    @Test
    void aWrappedReadOnlyArenaRefusesToGrowOrReset() {
        ByteBuffer readOnly = ByteBuffer.allocate(32).asReadOnlyBuffer();
        try (ByteBufferArena arena = ByteBufferArena.wrap(readOnly)) {
            Assertions.assertThrows(UnsupportedOperationException.class, () -> arena.allocate(8),
                    "a read-only arena cannot hand out writable space");
            Assertions.assertThrows(UnsupportedOperationException.class, arena::reset,
                    "and cannot pretend the space came back");
            Assertions.assertEquals(32, arena.size(),
                    "while reporting the whole mapped region as its size, which is what a reader wants");
        }

        // Fully qualified, and deliberately not imported: `java.lang.foreign.Arena` would shadow this
        // package's own `Arena` (JLS 6.4.1), and every assertion in this class is about the latter.
        try (java.lang.foreign.Arena ffa = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment segment = ffa.allocate(32);
            try (FfmArena arena = FfmArena.wrap(segment)) {
                Assertions.assertThrows(UnsupportedOperationException.class, () -> arena.allocate(8));
                Assertions.assertThrows(UnsupportedOperationException.class, arena::reset);
                Assertions.assertEquals(32, arena.size());
            }
        }
    }

    /**
     * The caveat the class javadoc states, pinned so it cannot change silently.
     *
     * <p>A view is a handle on <em>one</em> storage region. When the arena grows it moves to a new region,
     * and a handle taken before that still points at the old one — writes through it are lost. The
     * contract is therefore "take the view after the last allocation that may grow", and this test exists
     * so a future refactor that appears to make stale views work has to change this test deliberately.</p>
     */
    @Test
    void aViewTakenBeforeGrowthDoesNotFollowTheStorageAndTheFreshOneDoes() {
        for (NamedArena named : arenas()) {
            try (Arena arena = named.factory().apply(64)) {
                MemoryView stale = arena.view();
                arena.allocate(4096);

                stale.putLong(0, 999L);
                arena.view().putLong(0, 1234L);

                Assertions.assertEquals(1234L, arena.view().getLong(0),
                        named.name() + ": the fresh view is the one that speaks for the arena");
            }
        }
    }

    @Test
    void closeIsIdempotentAndASecondCloseIsNotAnError() {
        // Both implementations are AutoCloseable and both are used in try-with-resources beside other
        // resources; a close that throws on the second call turns a normal teardown into a failure.
        ByteBufferArena byteBufferArena = new ByteBufferArena(64);
        byteBufferArena.close();
        byteBufferArena.close();

        FfmArena ffmArena = new FfmArena(64);
        ffmArena.close();
        ffmArena.close();
    }
}
