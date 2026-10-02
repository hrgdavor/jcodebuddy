package hr.hrg.watch2.arena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LongToLongsIndexTest {

    @Test
    void putGetRoundTrip(@TempDir Path dir) throws IOException {
        int capacity = 16;
        try (ByteBufferArena arena = new ByteBufferArena(4096);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
            long[] keys = {1, 2, 3, 4, 5};
            long[][] values = {
                {10, 100},
                {20},
                {30, 300, 3000},
                {40},
                {50, 500}
            };
            for (int i = 0; i < keys.length; i++) {
                for (long v : values[i]) {
                    index.put(keys[i], v);
                }
            }
            for (int i = 0; i < keys.length; i++) {
                long[] got = index.get(keys[i]);
                assertArrayEquals(values[i], got, "key=" + keys[i]);
            }
            assertArrayEquals(keys, index.keys());
            assertEquals(keys.length, index.size());
        }
    }

    @Test
    void collisionHandling() {
        int capacity = 8;
        try (ByteBufferArena arena = new ByteBufferArena(4096);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
            long keyA = "alpha".hashCode() & (capacity - 1);
            long keyB = "beta".hashCode() & (capacity - 1);
            long keyC = keyA;
            if (keyA == 0) keyA = 1;
            if (keyB == 0) keyB = 2;
            if (keyC == 0) keyC = 1;
            index.put(keyA, 1);
            index.put(keyB, 2);
            index.put(keyC, 3);
            assertEquals(2, index.get(keyA).length);
            assertEquals(1, index.get(keyB).length);
        }
    }

    @Test
    void rebuildViaReset(@TempDir Path dir) throws IOException {
        int capacity = 256;
        try (ByteBufferArena arena = new ByteBufferArena(65536);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
            for (int i = 1; i <= 100; i++) {
                index.put(i, i * 10);
            }
            index.reset();
            assertEquals(0, index.size());
            assertEquals(0, index.get(1).length);
            for (int i = 1; i <= 100; i++) {
                index.put(i, i * 10);
            }
            assertEquals(100, index.size());
            for (int i = 1; i <= 100; i++) {
                assertArrayEquals(new long[]{i * 10L}, index.get(i));
            }
        }
    }

    @Test
    void swap(@TempDir Path dir) throws IOException {
        int capacity = 16;
        try (ByteBufferArena arena1 = new ByteBufferArena(4096);
             LongToLongsIndex oldIndex = new LongToLongsIndex(arena1, capacity);
             ByteBufferArena arena2 = new ByteBufferArena(4096);
             LongToLongsIndex freshIndex = new LongToLongsIndex(arena2, capacity)) {
            oldIndex.put(1, 10);
            oldIndex.put(2, 20);
            freshIndex.put(3, 30);
            freshIndex.put(4, 40);
            freshIndex.swap(oldIndex);
            assertArrayEquals(new long[]{10}, freshIndex.get(1));
            assertArrayEquals(new long[]{20}, freshIndex.get(2));
            assertArrayEquals(new long[]{30}, oldIndex.get(3));
            assertArrayEquals(new long[]{40}, oldIndex.get(4));
            assertEquals(2, freshIndex.size());
            assertEquals(2, oldIndex.size());
        }
    }

    @Test
    void mmapRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("mmap-test.bin");
        int capacity = 256;
        try (ByteBufferArena arena = new ByteBufferArena(65536);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity);
             IndexMmapWriter writer = new IndexMmapWriter(file)) {
            Random rnd = new Random(42);
            for (int i = 0; i < 100; i++) {
                long key = rnd.nextLong() & 0x7FFFFFFFFFFFFFFFL;
                if (key == 0) key = 1;
                long value = rnd.nextLong();
                index.put(key, value);
            }
            writer.write(index);
        }
        try (IndexMmapReader reader = new IndexMmapReader(file);
             LongToLongsIndex mmapIndex = reader.index()) {
            assertEquals(100, mmapIndex.size());
            assertEquals(256, mmapIndex.capacity());
        }
    }

    @Test
    void capacityGrowth() {
        int capacity = 512;
        try (ByteBufferArena arena = new ByteBufferArena(65536);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
            for (int i = 1; i <= 200; i++) {
                index.put(i, i * 100);
            }
            for (int i = 1; i <= 200; i++) {
                assertArrayEquals(new long[]{i * 100L}, index.get(i));
            }
            assertEquals(200, index.size());
        }
    }

    @Test
    void zeroValueEntry() {
        int capacity = 8;
        try (ByteBufferArena arena = new ByteBufferArena(4096);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
            index.put(42, 1);
            index.put(42, 2);
            long[] got = index.get(42);
            assertEquals(2, got.length);
            assertEquals(1, got[0]);
            assertEquals(2, got[1]);
        }
    }

    @Test
    void emptyGetReturnsEmpty() {
        int capacity = 8;
        try (ByteBufferArena arena = new ByteBufferArena(4096);
             LongToLongsIndex index = new LongToLongsIndex(arena, capacity)) {
            assertArrayEquals(new long[0], index.get(999));
            assertEquals(0, index.size());
        }
    }
}
