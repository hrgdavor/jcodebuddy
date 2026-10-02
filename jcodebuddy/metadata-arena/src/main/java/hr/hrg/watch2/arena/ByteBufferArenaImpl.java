package hr.hrg.watch2.arena;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ByteBufferArenaImpl implements hr.hrg.watch2.arena.Arena {
    private ByteBuffer buffer;
    private final ByteOrder byteOrder;
    private final boolean readOnly;
    private long cursor;

    ByteBufferArenaImpl(int initialCapacity, ByteOrder byteOrder) {
        this(ByteBuffer.allocateDirect(initialCapacity), byteOrder, false);
    }

    ByteBufferArenaImpl(ByteBuffer buffer, ByteOrder byteOrder) {
        this(buffer, byteOrder, buffer.isReadOnly());
    }

    private ByteBufferArenaImpl(ByteBuffer buffer, ByteOrder byteOrder, boolean readOnly) {
        this.buffer = buffer;
        this.byteOrder = byteOrder;
        this.readOnly = readOnly;
        this.cursor = readOnly ? buffer.capacity() : 0;
        this.buffer.order(byteOrder);
    }

    @Override
    public long allocate(long bytes) {
        if (readOnly) throw new UnsupportedOperationException("read-only arena");
        ensureCapacity(cursor + bytes);
        long addr = cursor;
        cursor += bytes;
        return addr;
    }

    private void ensureCapacity(long needed) {
        if (needed <= buffer.capacity()) return;
        int newCapacity = nextPowerOf2((int) needed);
        ByteBuffer newBuf = ByteBuffer.allocateDirect(newCapacity);
        newBuf.order(byteOrder);
        buffer.rewind();
        newBuf.put(buffer);
        buffer = newBuf;
    }

    private static int nextPowerOf2(int v) {
        int n = 1;
        while (n < v) n <<= 1;
        return n;
    }

    @Override
    public long size() {
        return cursor;
    }

    @Override
    public MemoryView view() {
        return new ByteBufferMemoryView(buffer, byteOrder);
    }

    @Override
    public ByteOrder byteOrder() {
        return byteOrder;
    }

    @Override
    public void reset() {
        if (readOnly) throw new UnsupportedOperationException("read-only arena");
        cursor = 0;
        buffer.clear();
    }

    @Override
    public void close() {
        buffer = null;
    }
}
