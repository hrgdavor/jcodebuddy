package hr.hrg.watch2.arena;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ByteBufferMemoryView implements MemoryView {
    private final ByteBuffer buffer;
    private final ByteOrder byteOrder;

    ByteBufferMemoryView(ByteBuffer buffer, ByteOrder byteOrder) {
        this.buffer = buffer;
        this.byteOrder = byteOrder;
        this.buffer.order(byteOrder);
    }

    @Override
    public long getLong(long offset) {
        return buffer.getLong((int) offset);
    }

    @Override
    public void putLong(long offset, long value) {
        buffer.putLong((int) offset, value);
    }

    @Override
    public void getLongs(long offset, long[] dst, int off, int len) {
        int intOffset = (int) offset;
        for (int i = 0; i < len; i++) {
            dst[off + i] = buffer.getLong(intOffset + i * 8);
        }
    }

    @Override
    public void putLongs(long offset, long[] src, int off, int len) {
        int intOffset = (int) offset;
        for (int i = 0; i < len; i++) {
            buffer.putLong(intOffset + i * 8, src[off + i]);
        }
    }

    @Override
    public int getInt(long offset) {
        return buffer.getInt((int) offset);
    }

    @Override
    public void putInt(long offset, int value) {
        buffer.putInt((int) offset, value);
    }

    @Override
    public void close() {
    }
}
