package hr.hrg.watch2.arena;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * The mmap round trip: an index written to a file, read back by a fresh reader.
 *
 * <p>The existing test in this module asserts that the reader reports the right <em>size</em> and
 * capacity, which is not the same claim as "the data survived" — the header can be perfect while every
 * value pointer is wrong. So this class asserts the values, the file's exact size (the format has no
 * padding to hide a layout mistake), the validation of the magic and version, and the one case the format
 * decision makes easy to get wrong: an index built in <strong>big-endian</strong> order still produces a
 * <strong>little-endian</strong> file, because the format is little-endian by decision
 * ([`docs/endian.md`](../docs/endian.md)) rather than by whatever the arena happened to be.</p>
 */
class IndexMmapRoundTripTest {

    /** Writes {@code entries} through the given arena's index and returns the file. */
    private Path writeIndex(Path dir, String name, ByteOrder order, Map<Long, long[]> entries) throws Exception {
        Path file = dir.resolve(name);
        try (ByteBufferArena arena = new ByteBufferArena(65536, order);
             LongToLongsIndex index = new LongToLongsIndex(arena, 256);
             IndexMmapWriter writer = new IndexMmapWriter(file)) {
            for (Map.Entry<Long, long[]> entry : entries.entrySet()) {
                for (long value : entry.getValue()) {
                    index.put(entry.getKey(), value);
                }
            }
            writer.write(index);
        }
        return file;
    }

    @Test
    void everyKeyAndValueSurvivesTheRoundTrip(@TempDir Path dir) throws Exception {
        Map<Long, long[]> entries = new LinkedHashMap<>();
        Random random = new Random(20261001L);
        for (int i = 0; i < 100; i++) {
            long key = random.nextLong() & 0x7FFFFFFFFFFFFFFFL;
            if (key == 0) {
                key = 1;
            }
            entries.put(key, new long[]{random.nextLong(), random.nextLong()});
        }

        Path file = writeIndex(dir, "values.bin", ByteOrder.LITTLE_ENDIAN, entries);

        try (IndexMmapReader reader = new IndexMmapReader(file)) {
            LongToLongsIndex index = reader.index();
            Assertions.assertEquals(entries.size(), index.size(), "every key is in the file");
            for (Map.Entry<Long, long[]> entry : entries.entrySet()) {
                Assertions.assertArrayEquals(entry.getValue(), index.get(entry.getKey()),
                        "key " + entry.getKey() + " must come back with its values, in order");
            }
            long[] expectedKeys = entries.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
            Assertions.assertArrayEquals(expectedKeys, java.util.Arrays.stream(index.keys()).sorted().toArray(),
                    "and the key table holds exactly the keys that were written — no more, no fewer");
        }
    }

    @Test
    void theFileIsExactlyTheFormatsSizeWithNoPadding(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("size.bin");
        long dataBytes;
        long totalSize;
        int capacity = 256;

        try (ByteBufferArena arena = new ByteBufferArena(65536);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity);
             IndexMmapWriter writer = new IndexMmapWriter(file)) {
            for (int i = 1; i <= 50; i++) {
                index.put(i, i * 3L);
            }
            dataBytes = arena.size();
            totalSize = index.totalSize();
            writer.write(index);
        }

        Assertions.assertEquals(
                CompactIndexFormat.HEADER_SIZE + 16L * capacity + dataBytes, totalSize,
                "the header plus 16 bytes per slot (keys and value pointers) plus the data area");
        Assertions.assertEquals(totalSize, Files.size(file),
                "and that is exactly what lands on disk: the writer must not pad, or a reader that maps "
                        + "the file and trusts the header would read somebody's alignment bytes as data");
    }

    @Test
    void aFileWithABadMagicIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad-magic.bin");
        Files.write(file, new byte[128]);

        // A file of a plausible size whose first eight bytes are zeros: the magic is what distinguishes
        // this format from any other run of bytes, and both the index factory and the reader refuse it.
        try (ByteBufferArena arena = new ByteBufferArena(128)) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> LongToLongsIndex.from(arena),
                    "the magic is what distinguishes this format from any other bytes");
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> new IndexMmapReader(file),
                "and the reader refuses it rather than handing back an index over garbage");
    }

    @Test
    void aFileWithAnUnsupportedVersionIsRefused() {
        try (ByteBufferArena arena = new ByteBufferArena(4096)) {
            MemoryView view = arena.view();
            view.putLong(0, magicAsLong());
            view.putInt(8, CompactIndexFormat.VERSION + 1);
            view.putInt(16, 4);
            view.putInt(20, 0);
            view.putLong(24, CompactIndexFormat.HEADER_SIZE);
            view.putLong(32, CompactIndexFormat.HEADER_SIZE + 4L * 8);
            view.putLong(40, CompactIndexFormat.HEADER_SIZE + 8L * 8);
            view.putLong(48, 0);

            // A version the reader does not know must fail rather than be read as if it were version 1:
            // the whole point of writing the number down is that a later layout cannot be mistaken for
            // this one.
            IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> LongToLongsIndex.from(arena));
            Assertions.assertTrue(failure.getMessage().contains("version"), failure.getMessage());
        }
    }

    /**
     * The format decision, enforced where it is easy to break: a big-endian index is refused, not written.
     *
     * <p>The file format is little-endian by decision ([`docs/endian.md`](../docs/endian.md)) and the reader
     * maps every file that way. The first version of this test asserted the stronger-sounding thing — that a
     * big-endian index produces a little-endian file — and the writer cannot honestly do that: it
     * transcribes whole 8-byte words, while the header holds 4-byte fields whose bytes a word-level copy in
     * another order scrambles. The file would look plausible and read as nonsense, which is the worst
     * available outcome, so the writer refuses and this test pins the refusal.</p>
     */
    @Test
    void aBigEndianIndexIsRefusedRatherThanWrittenAsNonsense(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big-endian.bin");
        try (ByteBufferArena arena = new ByteBufferArena(65536, ByteOrder.BIG_ENDIAN);
             LongToLongsIndex index = new LongToLongsIndex(arena, 256);
             IndexMmapWriter writer = new IndexMmapWriter(file)) {
            index.put(1L, 0x0102030405060708L);

            IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> writer.write(index));
            Assertions.assertTrue(failure.getMessage().contains("little-endian"), failure.getMessage());
            Assertions.assertTrue(failure.getMessage().contains("BIG_ENDIAN"), failure.getMessage());
        }
    }

    private static long magicAsLong() {
        long v = 0;
        for (byte b : CompactIndexFormat.MAGIC) {
            v = (v << 8) | (b & 0xFF);
        }
        return v;
    }
}
