package hr.hrg.watch2.arena;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class IndexMmapReader implements AutoCloseable {
    private final FileChannel channel;
    private final long size;
    private final ByteBuffer mappedBuffer;
    private final LongToLongsIndex index;
    private final boolean cleanerAvailable;

    public IndexMmapReader(Path file) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        this.size = channel.size();
        if (size < CompactIndexFormat.HEADER_SIZE) {
            throw new IOException("file too small: " + size);
        }
        this.mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
        this.mappedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        ByteBufferArena arena = ByteBufferArena.wrap(mappedBuffer);
        this.index = LongToLongsIndex.from(arena);
        this.cleanerAvailable = initCleaner();
    }

    public LongToLongsIndex index() {
        return index;
    }

    @Override
    public void close() throws IOException {
        try {
            index.close();
        } finally {
            if (cleanerAvailable) {
                unmap(mappedBuffer);
            }
            channel.close();
        }
    }

    private static boolean initCleaner() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            MethodType mt = MethodType.methodType(void.class, ByteBuffer.class);
            MethodHandle mh = MethodHandles.lookup().findVirtual(unsafeClass, "invokeCleaner", mt);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void unmap(ByteBuffer buffer) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            MethodType mt = MethodType.methodType(void.class, ByteBuffer.class);
            MethodHandle mh = MethodHandles.lookup().findVirtual(unsafeClass, "invokeCleaner", mt);
            mh.invoke(unsafe, buffer);
        } catch (Throwable e) {
        }
    }
}
