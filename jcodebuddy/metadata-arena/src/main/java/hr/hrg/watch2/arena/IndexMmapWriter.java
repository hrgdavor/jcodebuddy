package hr.hrg.watch2.arena;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class IndexMmapWriter implements AutoCloseable {
    private final FileChannel channel;

    public IndexMmapWriter(Path file) throws IOException {
        this.channel = FileChannel.open(file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void write(LongToLongsIndex index) throws IOException {
        // The file format is little-endian by decision (docs/endian.md, README § Endianness) and the reader
        // maps every file that way, so a big-endian index is refused rather than written. It has to be
        // refused rather than converted: this writer transcribes whole 8-byte words, and a big-endian
        // arena's header holds 4-byte fields whose bytes would be scrambled by a word-level copy in a
        // different order — the file would look plausible and read as nonsense. Failing loudly is the only
        // honest option, and `IndexMmapRoundTripTest.aBigEndianIndexIsRefusedRatherThanWrittenAsNonsense`
        // pins it.
        if (index.arenaReflection().byteOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("the index file format is little-endian; this index was built "
                    + "over a " + index.arenaReflection().byteOrder() + " arena");
        }
        long totalSize = index.totalSize();
        ByteBuffer direct = ByteBuffer.allocateDirect((int) totalSize);
        direct.order(ByteOrder.LITTLE_ENDIAN);
        MemoryView view = index.arenaReflection().view();

        // The file is the little-endian byte image of the arena's storage, so it is copied WORD BY WORD:
        // `getLong` yields the value and `putLong` writes it little-endian, which is the same byte sequence
        // the arena holds. The previous version fell into a byte-wise path for everything after the first
        // 64 KB chunk — `remaining` was computed as the whole rest of the file, not as a partial word — and
        // the byte-level defaults transcribe a word most-significant-byte first, i.e. the reverse of the
        // memory order. Any index larger than one chunk therefore landed on disk scrambled from 64 KB on,
        // and a reader over it answered "no such key" instead of reporting a corrupt file. Found by
        // `ArenaIndexJmhBenchmark.mmapLoad` at 100 000 entries, whose index is ~5 MB; the unit tests' files
        // all fitted in one chunk.
        int chunkBytes = 65536;
        long[] chunk = new long[chunkBytes / Long.BYTES];
        long offset = 0;
        while (totalSize - offset >= Long.BYTES) {
            int longs = (int) Math.min((totalSize - offset) / Long.BYTES, chunk.length);
            view.getLongs(offset, chunk, 0, longs);
            for (int i = 0; i < longs; i++) {
                direct.putLong(chunk[i]);
            }
            offset += (long) longs * Long.BYTES;
        }

        // At most seven bytes left, and they are the first bytes of the final word's little-endian image —
        // so they are taken in that order rather than through the byte-level defaults, which would reverse
        // them within the word.
        if (offset < totalSize) {
            long lastWord = view.getLong(offset);
            for (int i = 0; offset + i < totalSize; i++) {
                direct.put((byte) (lastWord >>> (8 * i)));
            }
        }

        direct.flip();
        while (direct.hasRemaining()) {
            channel.write(direct);
        }
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
