package hr.hrg.watch2.arena;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * The binary format's constants, and the layout arithmetic they imply.
 *
 * <p>These numbers are written into a file and read back by a different process, so they are a wire
 * format: a constant that changes without the version changing is a file that no longer parses. The
 * assertions below are therefore exact — including the magic's eight bytes, because the README's prose
 * spelling of it ("`ARENA\0\001`") is not what the code writes, and the bytes are the thing on disk.</p>
 */
class CompactIndexFormatTest {

    @Test
    void theMagicIsTheEightBytesTheFormatWrites() {
        Assertions.assertArrayEquals(
                "ARENA01\u0000".getBytes(StandardCharsets.US_ASCII),
                CompactIndexFormat.MAGIC,
                "the magic is exactly these eight bytes; a change invalidates every index file on disk and "
                        + "must come with a VERSION change");
        Assertions.assertEquals(8, CompactIndexFormat.MAGIC.length);
    }

    @Test
    void theHeaderIsOneCacheLineAndEveryFieldFitsInsideIt() {
        Assertions.assertEquals(64, CompactIndexFormat.HEADER_SIZE,
                "one cache line, and the layout is designed around that");
        Assertions.assertEquals(1, CompactIndexFormat.VERSION);
        Assertions.assertEquals(3, CompactIndexFormat.SLOT_SHIFT,
                "a slot is 8 bytes, so its shift is 3");
        Assertions.assertEquals(8, CompactIndexFormat.LONG_SIZE);
        Assertions.assertEquals(4, CompactIndexFormat.INT_SIZE);
        Assertions.assertEquals(0.75f, CompactIndexFormat.LOAD_FACTOR,
                "the load factor the hash table is sized against");

        // The fields LongToLongsIndex writes into the header: magic(8) version(4) spare(4) capacity(4)
        // size(4) keys(8) vo(8) data(8) dataSize(8) = 56, which must not run past the header.
        long lastFieldEnd = 48 + CompactIndexFormat.LONG_SIZE;
        Assertions.assertTrue(lastFieldEnd <= CompactIndexFormat.HEADER_SIZE,
                "the header's last field ends at " + lastFieldEnd + ", inside " + CompactIndexFormat.HEADER_SIZE);
    }

    /**
     * The file-size formula, asserted where it is derived rather than only where it is written.
     *
     * <p>It is {@code HEADER_SIZE + 16 * capacity + data}, not the "header + 12 * capacity + data area"
     * an earlier note claimed: keys and the per-slot value pointers are <em>both</em> 8 bytes per slot, so
     * the fixed region is 16 bytes per slot. The mmap round-trip test asserts the same formula against a
     * real file, which is what keeps this arithmetic honest.</p>
     */
    @Test
    void theFixedRegionIsSixteenBytesPerSlotPlusTheHeader() {
        for (int capacity : new int[]{1, 4, 512, 1024}) {
            long keys = (long) capacity * CompactIndexFormat.LONG_SIZE;
            long valuePointers = (long) capacity * CompactIndexFormat.LONG_SIZE;
            long fixedRegion = CompactIndexFormat.HEADER_SIZE + keys + valuePointers;

            Assertions.assertEquals(CompactIndexFormat.HEADER_SIZE + 16L * capacity, fixedRegion,
                    "capacity=" + capacity);
        }
    }
}
