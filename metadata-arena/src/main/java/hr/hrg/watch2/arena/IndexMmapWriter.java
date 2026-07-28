package hr.hrg.watch2.arena;

import java.io.IOException;
import java.nio.ByteBuffer;
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
        long totalSize = index.totalSize();
        ByteBuffer direct = ByteBuffer.allocateDirect((int) totalSize);
        direct.order(index.arenaReflection().byteOrder());
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
