package hr.hrg.watch2.arena;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class LongToLongsIndex implements AutoCloseable {
    private static final int EMPTY_SLOT = 0;
    private static final long VO_EMPTY = 0;
    private static final long VO_BIAS = 1;

    private volatile Arena arena;
    private final int capacity;
    private volatile int size;
    private final long headerOffset;
    private final long keysOffset;
    private final long voOffset;
    private final long dataOffset;
    private final ByteOrder byteOrder;

    public LongToLongsIndex(Arena arena, int initialCapacity) {
        if (arena == null) throw new IllegalArgumentException("arena is null");
        if (!isPowerOf2(initialCapacity)) throw new IllegalArgumentException("capacity must be power of 2");
        this.arena = arena;
        this.capacity = initialCapacity;
        this.byteOrder = arena.byteOrder();
        this.headerOffset = 0;
        this.keysOffset = CompactIndexFormat.HEADER_SIZE;
        this.voOffset = keysOffset + (long) capacity * CompactIndexFormat.LONG_SIZE;
        this.dataOffset = voOffset + (long) capacity * CompactIndexFormat.LONG_SIZE;
        writeHeader(0, 0);
        MemoryView v = view();
        for (long i = 0; i < capacity; i++) {
            v.putLong(keysOffset + i * CompactIndexFormat.LONG_SIZE, EMPTY_SLOT);
            v.putLong(voOffset + i * CompactIndexFormat.LONG_SIZE, VO_EMPTY);
        }
    }

    LongToLongsIndex(Arena arena, int capacity, long headerOffset, long keysOffset, long voOffset, long dataOffset, long dataSize, int size) {
        if (arena == null) throw new IllegalArgumentException("arena is null");
        this.arena = arena;
        this.capacity = capacity;
        this.headerOffset = headerOffset;
        this.keysOffset = keysOffset;
        this.voOffset = voOffset;
        this.dataOffset = dataOffset;
        this.byteOrder = arena.byteOrder();
        this.size = size;
    }

    private MemoryView view() {
        Arena a = arena;
        if (a == null) throw new IllegalStateException("index is closed");
        return a.view();
    }

    public static LongToLongsIndex from(Arena arena) {
        MemoryView v = arena.view();
        long magic = v.getLong(0);
        int version = v.getInt(8);
        if (magic != bytesToLong(CompactIndexFormat.MAGIC)) {
            throw new IllegalArgumentException("invalid magic");
        }
        if (version != CompactIndexFormat.VERSION) {
            throw new IllegalArgumentException("unsupported version: " + version);
        }
        int capacity = v.getInt(16);
        int size = v.getInt(20);
        long keysOffset = v.getLong(24);
        long voOffset = v.getLong(32);
        long dataOffset = v.getLong(40);
        long dataSize = v.getLong(48);
        return new LongToLongsIndex(arena, capacity, 0, keysOffset, voOffset, dataOffset, dataSize, size);
    }

    private static boolean isPowerOf2(int v) {
        return v > 0 && (v & (v - 1)) == 0;
    }

    private void writeHeader(int size, long dataSize) {
        MemoryView v = view();
        v.putLong(headerOffset + 0, bytesToLong(CompactIndexFormat.MAGIC));
        v.putInt(headerOffset + 8, CompactIndexFormat.VERSION);
        v.putInt(headerOffset + 12, 0);
        v.putInt(headerOffset + 16, capacity);
        v.putInt(headerOffset + 20, size);
        v.putLong(headerOffset + 24, keysOffset);
        v.putLong(headerOffset + 32, voOffset);
        v.putLong(headerOffset + 40, dataOffset);
        v.putLong(headerOffset + 48, dataSize);
    }

    private static long bytesToLong(byte[] bytes) {
        long v = 0;
        for (byte b : bytes) {
            v = (v << 8) | (b & 0xFF);
        }
        return v;
    }

    public void put(long key, long value) {
        if (key == EMPTY_SLOT) throw new IllegalArgumentException("key 0 is reserved");
        MemoryView v = view();
        int slot = findSlot(key, v);
        if (slot < 0) {
            slot = -slot - 1;
            v.putLong(keysOffset + (long) slot * CompactIndexFormat.LONG_SIZE, key);
            v.putLong(voOffset + (long) slot * CompactIndexFormat.LONG_SIZE, VO_EMPTY);
            size++;
            v.putInt(headerOffset + 20, size);
        }
        long currentVo = v.getLong(voOffset + (long) slot * CompactIndexFormat.LONG_SIZE);
        int currentCount = 0;
        long currentDataOffset = 0;
        if (currentVo != VO_EMPTY) {
            currentDataOffset = currentVo - VO_BIAS;
            currentCount = v.getInt(dataOffset + currentDataOffset);
        }
        int newCount = currentCount + 1;
        long newOffset = arena.allocate(4 + (long) newCount * 8);
        v.putInt(dataOffset + newOffset, newCount);
        if (currentCount > 0) {
            long[] temp = new long[newCount];
            v.getLongs(dataOffset + currentDataOffset + 4, temp, 0, currentCount);
            v.putLongs(dataOffset + newOffset + 4, temp, 0, currentCount);
        }
        v.putLong(dataOffset + newOffset + 4 + (long) currentCount * 8, value);
        v.putLong(voOffset + (long) slot * CompactIndexFormat.LONG_SIZE, newOffset + VO_BIAS);
    }

    public long[] get(long key) {
        if (key == EMPTY_SLOT) return new long[0];
        MemoryView v = view();
        int slot = findSlot(key, v);
        if (slot < 0) return new long[0];
        long vo = v.getLong(voOffset + (long) slot * CompactIndexFormat.LONG_SIZE);
        if (vo == VO_EMPTY) return new long[0];
        int count = v.getInt(dataOffset + (vo - VO_BIAS));
        long[] result = new long[count];
        v.getLongs(dataOffset + (vo - VO_BIAS) + 4, result, 0, count);
        return result;
    }

    public long[] keys() {
        MemoryView v = view();
        long[] result = new long[size];
        int idx = 0;
        for (int i = 0; i < capacity; i++) {
            long k = v.getLong(keysOffset + (long) i * CompactIndexFormat.LONG_SIZE);
            if (k != EMPTY_SLOT) {
                result[idx++] = k;
            }
        }
        return result;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public long totalSize() {
        return dataOffset + arena.size();
    }

    public void reset() {
        MemoryView v = view();
        for (long i = 0; i < capacity; i++) {
            v.putLong(keysOffset + i * CompactIndexFormat.LONG_SIZE, EMPTY_SLOT);
            v.putLong(voOffset + i * CompactIndexFormat.LONG_SIZE, VO_EMPTY);
        }
        arena.reset();
        size = 0;
        writeHeader(0, 0);
    }

    public void swap(LongToLongsIndex fresh) {
        if (fresh == null) throw new IllegalArgumentException("fresh is null");
        Arena oldArena = this.arena;
        int oldSize = this.size;
        int oldCapacity = this.capacity;
        this.arena = fresh.arena;
        this.size = fresh.size;
        fresh.arena = oldArena;
        fresh.size = oldSize;
        if (oldCapacity != fresh.capacity) {
            throw new IllegalArgumentException("capacity mismatch: " + oldCapacity + " vs " + fresh.capacity);
        }
    }

    Arena arenaReflection() {
        return arena;
    }

    @Override
    public void close() {
        Arena a = this.arena;
        this.arena = null;
        this.size = 0;
        if (a != null) {
            a.close();
        }
    }

    private int findSlot(long key, MemoryView v) {
        int mask = capacity - 1;
        int slot = (int) (Long.hashCode(key) & mask);
        while (true) {
            long k = v.getLong(keysOffset + (long) slot * CompactIndexFormat.LONG_SIZE);
            if (k == EMPTY_SLOT) {
                return -(slot + 1);
            }
            if (k == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }
}
