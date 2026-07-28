package hr.hrg.watch2.arena;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ByteBufferArena implements Arena {
    private final ByteBufferArenaImpl impl;
    private final ByteOrder byteOrder;

    public ByteBufferArena(int initialCapacity) {
        this(initialCapacity, ByteOrder.LITTLE_ENDIAN);
    }

    public ByteBufferArena(int initialCapacity, ByteOrder byteOrder) {
        this.impl = new ByteBufferArenaImpl(initialCapacity, byteOrder);
        this.byteOrder = byteOrder;
    }

    public static ByteBufferArena wrap(ByteBuffer buffer) {
        return wrap(buffer, ByteOrder.LITTLE_ENDIAN);
    }

    public static ByteBufferArena wrap(ByteBuffer buffer, ByteOrder byteOrder) {
        ByteBufferArenaImpl impl = new ByteBufferArenaImpl(buffer, byteOrder);
        return new ByteBufferArena(impl, byteOrder);
    }

    private ByteBufferArena(ByteBufferArenaImpl impl, ByteOrder byteOrder) {
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
