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
        long offset = 0;
        int chunk = 65536;
        while (offset < totalSize) {
            int bytes = (int) Math.min(totalSize - offset, chunk);
            int longs = bytes / 8;
            long[] tmp = new long[longs];
            view.getLongs(offset, tmp, 0, longs);
            for (long l : tmp) {
                direct.putLong(l);
            }
            offset += longs * 8L;
            int remaining = (int) (totalSize - offset);
            if (remaining > 0) {
                byte[] rem = new byte[remaining];
                view.getBytes(offset, rem, 0, remaining);
                direct.put(rem);
                offset += remaining;
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
