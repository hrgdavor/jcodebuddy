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
    /** Set by {@link #close()}: FFM's own arena throws on a second close, so this one refuses to ask. */
    private boolean closed;

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
            // The whole old segment, not `cursor` bytes. The cursor counts what was *allocated*, and a
            // caller may legitimately write through a view without allocating (that is exactly what
            // `LongToLongsIndex` does with its fixed region), so copying only the allocated prefix loses
            // those writes on growth. `ByteBufferArenaImpl.ensureCapacity` already copies its whole buffer;
            // this is the same behaviour, and `ArenaTest.growthKeepsWhatWasAlreadyWritten` is the test that
            // made the difference visible.
            MemorySegment.copy(segment, 0, newSeg, 0, segment.byteSize());
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
        // The whole segment, not `asSlice(0, cursor)`, which is what this used to be — and that slice made
        // the two backends disagree about what a view is. `ByteBufferArenaImpl.view()` has always covered
        // its whole buffer, and `LongToLongsIndex` depends on exactly that: it writes its fixed region
        // (header, key table, value pointers) directly into the arena before allocating anything, so a view
        // limited to the allocated prefix is an empty view at construction time and the index could not be
        // built over an FfmArena at all. The module's README claims the index works over either backend;
        // `LongToLongsIndexLayoutTest.theIndexRunsOverBothBackends` is what holds this fix in place. The
        // guard that is genuinely useful — refusing a write past the storage — is the segment's own bounds,
        // which this still has.
        return new FfmMemoryView(segment, byteOrder);
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
        // Idempotent, because `java.lang.foreign.Arena.close()` is not: it throws IllegalStateException
        // ("Already closed") on the second call. Closing twice is normal here and not a mistake worth an
        // exception — `LongToLongsIndex.close()` closes the arena it was handed, and a caller that also has
        // the arena in its own try-with-resources (the shape every test in this module uses) closes it
        // again. `ByteBufferArenaImpl.close()` has always been a no-op, so this is also what makes the two
        // backends behave alike.
        if (closed) {
            return;
        }
        closed = true;
        if (ffa != null) {
            ffa.close();
        }
    }
}
