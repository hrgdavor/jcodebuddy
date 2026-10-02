package hr.hrg.watch2.arena;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class FfmMemoryView implements MemoryView {
    private final MemorySegment segment;
    private final java.lang.foreign.ValueLayout.OfLong layout;
    private final java.lang.foreign.ValueLayout.OfInt intLayout;
    private final ByteOrder byteOrder;

    FfmMemoryView(MemorySegment segment, ByteOrder byteOrder) {
        this.segment = segment;
        this.byteOrder = byteOrder;
        // Alignment 1, because the layouts this view serves are *packed*: `LongToLongsIndex` stores a
        // 4-byte count immediately followed by that many `long` values, so a value routinely starts at a
        // 4-byte-aligned offset. `ValueLayout.JAVA_LONG` carries an 8-byte alignment constraint by default,
        // and the FFM API enforces it — the index could not be built over an FfmArena at all until this
        // was declared, which is what `LongToLongsIndexLayoutTest.theIndexRunsOverBothBackends` exists for.
        // The ByteBuffer backend has no such constraint, so the two backends disagreed on a format the
        // module's README says works with both.
        this.layout = java.lang.foreign.ValueLayout.JAVA_LONG.withOrder(byteOrder).withByteAlignment(1);
        this.intLayout = java.lang.foreign.ValueLayout.JAVA_INT.withOrder(byteOrder).withByteAlignment(1);
    }

    @Override
    public long getLong(long offset) {
        return segment.get(layout, offset);
    }

    @Override
    public void putLong(long offset, long value) {
        segment.set(layout, offset, value);
    }

    @Override
    public void getLongs(long offset, long[] dst, int off, int len) {
        for (int i = 0; i < len; i++) {
            dst[off + i] = segment.get(layout, offset + (long) i * 8);
        }
    }

    @Override
    public void putLongs(long offset, long[] src, int off, int len) {
        for (int i = 0; i < len; i++) {
            segment.set(layout, offset + (long) i * 8, src[off + i]);
        }
    }

    @Override
    public int getInt(long offset) {
        return segment.get(intLayout, offset);
    }

    @Override
    public void putInt(long offset, int value) {
        segment.set(intLayout, offset, value);
    }

    @Override
    public void close() {
    }
}
