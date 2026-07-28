package hr.hrg.watch2.arena;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class FfmArena implements Arena {
    private final FfmArenaImpl impl;
    private final ByteOrder byteOrder;

    public FfmArena(int initialCapacity) {
        this(initialCapacity, ByteOrder.LITTLE_ENDIAN);
    }

    public FfmArena(int initialCapacity, ByteOrder byteOrder) {
        this.impl = new FfmArenaImpl(initialCapacity, byteOrder);
        this.byteOrder = byteOrder;
    }

    public static FfmArena wrap(MemorySegment segment) {
        return wrap(segment, ByteOrder.LITTLE_ENDIAN);
    }

    public static FfmArena wrap(MemorySegment segment, ByteOrder byteOrder) {
        FfmArenaImpl impl = new FfmArenaImpl(segment, byteOrder);
        return new FfmArena(impl, byteOrder);
    }

    private FfmArena(FfmArenaImpl impl, ByteOrder byteOrder) {
        this.impl = impl;
        this.byteOrder = byteOrder;
    }

    @Override
    public long allocate(long bytes) {
        return impl.allocate(bytes);
    }

    @Override
    public long size() {
        return impl.size();
    }

    @Override
    public MemoryView view() {
        return impl.view();
    }

    @Override
    public ByteOrder byteOrder() {
        return byteOrder;
    }

    @Override
    public void reset() {
        impl.reset();
    }

    @Override
    public void close() {
        impl.close();
    }
}
