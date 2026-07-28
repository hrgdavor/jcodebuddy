package hr.hrg.watch2.arena;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class FfmArenaImpl implements hr.hrg.watch2.arena.Arena {
    private MemorySegment segment;
    private final java.lang.foreign.Arena ffa;
    private final ByteOrder byteOrder;
    private final boolean readOnly;
    private long cursor;

    FfmArenaImpl(int initialCapacity, ByteOrder byteOrder) {
        int size = nextPowerOf2(Math.max(64, initialCapacity));
        this.ffa = Arena.ofConfined();
        this.segment = ffa.allocate(size);
        this.byteOrder = byteOrder;
        this.readOnly = false;
        this.cursor = 0;
    }

    FfmArenaImpl(MemorySegment segment, ByteOrder byteOrder) {
        this(segment, byteOrder, true);
    }

    private FfmArenaImpl(MemorySegment segment, ByteOrder byteOrder, boolean readOnly) {
        this.segment = segment;
        this.ffa = null;
        this.byteOrder = byteOrder;
        this.readOnly = readOnly;
        this.cursor = readOnly ? segment.byteSize() : 0;
    }

    @Override
    public long allocate(long bytes) {
        if (readOnly) throw new UnsupportedOperationException("read-only arena");
        long needed = cursor + bytes;
        if (needed > segment.byteSize()) {
            int newSize = nextPowerOf2((int) needed);
            MemorySegment newSeg = ffa.allocate(newSize);
            MemorySegment.copy(segment, 0, newSeg, 0, cursor);
            segment = newSeg;
        }
        long addr = cursor;
        cursor += bytes;
        return addr;
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
        return new FfmMemoryView(readOnly ? segment : segment.asSlice(0, cursor), byteOrder);
    }

    @Override
    public ByteOrder byteOrder() {
        return byteOrder;
    }

    @Override
    public void reset() {
        if (readOnly) throw new UnsupportedOperationException("read-only arena");
        cursor = 0;
    }

    @Override
    public void close() {
        if (ffa != null) ffa.close();
    }
}
