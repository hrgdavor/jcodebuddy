package hr.hrg.watch2.arena;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * {@link LongToLongsIndex} over both memory backends: the numbers that decide which backend the metadata
 * cache should default to, and whether the rebuild protocol (DEC-W009) is cheap enough to run on every
 * watcher batch.
 *
 * <h3>What is measured, and what is deliberately not</h3>
 *
 * <ul>
 *   <li>{@link #getHot} — one key, read repeatedly: the cache's steady-state lookup.</li>
 *   <li>{@link #getRandom} — a different key per invocation, so the probe touches a cold slot.</li>
 *   <li>{@link #rebuild} — {@code reset()} plus re-inserting every entry: the operation DEC-W009 expects
 *       on a full rebuild, measured as one thing because that is how a caller performs it.</li>
 *   <li>{@link #mmapLoad} — map the file written in setup, read every entry, unmap. This is the cold-start
 *       path a metadata server takes when it finds an index on disk.</li>
 * </ul>
 *
 * <p>The two parameters are the two questions worth asking separately: the backend, and the size. Nothing
 * here claims a result; the numbers are recorded where the decision is made, and a comparison between the
 * backends is only meaningful within one run (same JVM, same machine).</p>
 *
 * <h3>Running it</h3>
 *
 * <pre>
 * bun run scripts/run-jmh.js --include ".*ArenaIndexJmhBenchmark.*"
 * </pre>
 *
 * <p>The runner supplies the profile — forks, warmup and measurement — and warns when it is lowered, so
 * this class declares none of them: a benchmark that pins its own iterations cannot be run at
 * decision-grade settings without editing it first. The runner's smoke flags are for iterating on the
 * benchmark, never for a number that gets recorded.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ArenaIndexJmhBenchmark {

    /** One backend, one table size, one fully populated index — and a file holding the same index. */
    @State(Scope.Thread)
    public static class IndexState {

        @Param({"1000", "100000"})
        public int entries;

        @Param({"bytebuffer", "ffm"})
        public String backend;

        Arena arena;
        LongToLongsIndex index;
        long[] keys;
        Path file;
        int cursor;
        int probe;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            Random random = new Random(0x5EEDL);
            keys = new long[entries];
            for (int i = 0; i < entries; i++) {
                long key = random.nextLong() & 0x7FFFFFFFFFFFFFFFL;
                keys[i] = key == 0 ? 1 : key;
            }
            probe = entries / 2;
            cursor = 0;

            int capacity = capacityFor(entries);
            arena = "ffm".equals(backend)
                    ? new FfmArena((int) arenaBytes(entries, capacity))
                    : new ByteBufferArena((int) arenaBytes(entries, capacity));
            index = new LongToLongsIndex(arena, capacity);
            for (int i = 0; i < entries; i++) {
                index.put(keys[i], i);
            }

            // Written once, outside every measured region: the reader benchmark is about mapping and
            // reading an existing file, not about producing one.
            file = Files.createTempFile("arena-jmh-", ".bin");
            try (IndexMmapWriter writer = new IndexMmapWriter(file)) {
                writer.write(index);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            index.close();
            Files.deleteIfExists(file);
        }

        /** The next key in the array, wrapping: a cold slot per invocation without allocating. */
        long nextKey() {
            int i = cursor++ % entries;
            return keys[i];
        }

        /** A power of two at or above twice the entry count, so the table sits well under its load factor. */
        static int capacityFor(int entries) {
            int capacity = 1;
            while (capacity < entries * 2) {
                capacity <<= 1;
            }
            return capacity;
        }

        /** The fixed region plus the data area the single-value inserts need, and a little slack. */
        static long arenaBytes(int entries, int capacity) {
            return CompactIndexFormat.HEADER_SIZE + 16L * capacity + (long) entries * 12 + 4096;
        }
    }

    @Benchmark
    public long getHot(IndexState state) {
        return state.index.get(state.keys[state.probe])[0];
    }

    @Benchmark
    public long getRandom(IndexState state) {
        return state.index.get(state.nextKey())[0];
    }

    /**
     * The full rebuild: clear the table, then insert every entry again.
     *
     * <p>Both halves are inside the measured method because they are one operation to a caller, and because
     * `reset()` alone would measure a loop over the key table rather than the work a watcher batch pays.
     * The returned size keeps the JIT from discarding the inserts.</p>
     */
    @Benchmark
    public int rebuild(IndexState state) {
        state.index.reset();
        for (int i = 0; i < state.entries; i++) {
            state.index.put(state.keys[i], i);
        }
        return state.index.size();
    }

    /**
     * The cold-start path: map the file, read every entry, unmap.
     *
     * <p>The sum is returned rather than a count, because summing forces every value to be read: a
     * benchmark that counted keys could be answered from the key table alone and would flatter the
     * reader.</p>
     */
    @Benchmark
    public long mmapLoad(IndexState state) throws IOException {
        long sum = 0;
        try (IndexMmapReader reader = new IndexMmapReader(state.file)) {
            LongToLongsIndex index = reader.index();
            for (int i = 0; i < state.entries; i++) {
                sum += index.get(state.keys[i])[0];
            }
        }
        return sum;
    }
}
