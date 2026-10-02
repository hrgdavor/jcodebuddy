package hr.hrg.watch2.arena;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The {@link MemoryView} contract, over both backends.
 *
 * <p>The two implementations reach the memory through different APIs (`ByteBuffer` versus a
 * `MemorySegment` with an ordered value layout), so the assertions here are about the semantics a caller
 * depends on — offsets are byte offsets, bulk operations honour the array's own offset and length, and the
 * byte-level defaults pack eight bytes into each word from the most significant one. Those last two are
 * easy to write differently in each backend and never notice, which is why every case runs twice.</p>
 */
class MemoryViewTest {

    private static List<NamedView> views() {
        return List.of(
                new NamedView("ByteBufferMemoryView", capacity -> {
                    ByteBufferArena arena = new ByteBufferArena(capacity);
                    return new Pair(arena, arena.view());
                }),
                new NamedView("FfmMemoryView", capacity -> {
                    FfmArena arena = new FfmArena(capacity);
                    arena.allocate(capacity);
                    return new Pair(arena, arena.view());
                }));
    }

    private record Pair(Arena arena, MemoryView view) {
    }

    private record NamedView(String name, IntFunction<Pair> factory) {
        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    void singleValuesRoundTripAtByteOffsets() {
        for (NamedView named : views()) {
            Pair pair = named.factory().apply(256);
            try (Arena arena = pair.arena()) {
                MemoryView view = pair.view();
                view.putLong(16, Long.MIN_VALUE);
                view.putLong(24, 1234567890123456789L);
                view.putInt(32, -12345);

                Assertions.assertEquals(Long.MIN_VALUE, view.getLong(16), named.name());
                Assertions.assertEquals(1234567890123456789L, view.getLong(24), named.name());
                Assertions.assertEquals(-12345, view.getInt(32), named.name());
                Assertions.assertEquals(0L, view.getLong(0),
                        named.name() + ": an unwritten word reads as zero, which is what the index's "
                                + "empty-slot marker relies on");
            }
        }
    }

    @Test
    void bulkOperationsHonourTheArrayOffsetAndLength() {
        for (NamedView named : views()) {
            Pair pair = named.factory().apply(256);
            try (Arena arena = pair.arena()) {
                MemoryView view = pair.view();
                long[] source = {9, 9, 1, 2, 3, 9};
                view.putLongs(64, source, 2, 3);

                long[] neighbours = new long[]{9, 9, 9, 9, 9};
                view.getLongs(56, neighbours, 0, 5);
                Assertions.assertArrayEquals(new long[]{0, 1, 2, 3, 0}, neighbours,
                        named.name() + ": only the three requested words were written, starting at the "
                                + "requested byte offset — the words on either side are untouched");

                long[] destination = {0, 0, 0, 0, 0};
                view.getLongs(64, destination, 1, 3);
                Assertions.assertArrayEquals(new long[]{0, 1, 2, 3, 0}, destination,
                        named.name() + ": and the read lands at the requested index in the array");
            }
        }
    }

    @Test
    void byteAccessRoundTripsAcrossWordBoundariesAndPartialWords() {
        for (ByteOrder order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            ByteBufferArena arena = new ByteBufferArena(256, order);
            try (arena) {
                MemoryView view = arena.view();
                byte[] source = new byte[20];
                for (int i = 0; i < source.length; i++) {
                    source[i] = (byte) (i * 7 + 1);
                }
                view.putBytes(8, source, 0, source.length);

                byte[] read = new byte[20];
                view.getBytes(8, read, 0, read.length);
                Assertions.assertArrayEquals(source, read,
                        "order=" + order + ": the byte-level defaults are order-independent because they "
                                + "go through the typed accessors");
            }
        }
    }

    /**
     * The layout the default byte methods use, asserted rather than assumed.
     *
     * <p>Eight bytes live in one word, and byte {@code i} of the group sits in the word's
     * <em>most</em> significant position when {@code i % 8 == 0}. A caller that needs a different packing
     * (a file header, a hash) has to say so; this is the one the interface chooses, and the index's mmap
     * writer uses it for the tail of the file.</p>
     */
    @Test
    void byteAccessPacksTheMostSignificantByteFirstWithinAWord() {
        ByteBufferArena arena = new ByteBufferArena(64);
        try (arena) {
            MemoryView view = arena.view();
            view.putBytes(0, new byte[]{0x41}, 0, 1);

            Assertions.assertEquals(0x4100000000000000L, view.getLong(0),
                    "byte 0 is the word's top byte");
            view.putBytes(0, new byte[]{0, 0, 0, 0, 0, 0, 0, 0x42}, 0, 8);
            Assertions.assertEquals(0x42L, view.getLong(0),
                    "and byte 7 is the word's bottom byte — one byte per position, eight positions per word");

            // A fresh word, because writing one byte is a read-modify-write of the word it lands in: the
            // 0x42 above would still be in the bottom byte, which is correct and not what this asserts.
            view.putBytes(8, new byte[]{(byte) 0xFF}, 0, 1);
            Assertions.assertEquals(0xFF00000000000000L, view.getLong(8),
                    "a byte is taken as unsigned, not sign-extended into its neighbours");
        }
    }

    @Test
    void aViewOverAReadOnlyArenaStillReads() {
        // Written and read with the same order: the fixture has to agree with the view it is read through,
        // or the assertion would be measuring the byte order rather than the read-only path. (The first
        // version of this test wrote through a fresh heap buffer, which is big-endian, and then wrapped it
        // little-endian — it read 42 shifted into the top byte, which is exactly what that means.)
        java.nio.ByteBuffer raw = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        raw.putLong(0, 42L);
        raw.putLong(8, 43L);

        try (ByteBufferArena arena = ByteBufferArena.wrap(raw.asReadOnlyBuffer())) {
            Assertions.assertEquals(42L, arena.view().getLong(0),
                    "the read path is the same code; only allocation is refused");
            Assertions.assertEquals(43L, arena.view().getLong(8));
        }
    }
}
