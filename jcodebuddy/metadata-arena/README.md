# metadata-arena

A Java 25 library providing compact, off-heap-capable memory arenas and long-keyed indexes for high-performance metadata storage.

## Overview

`metadata-arena` is a low-level module that supplies memory allocation arenas and a compact hash index data structure designed for storing metadata in off-heap memory. It targets Java 25 and leverages the Foreign Function & Memory (Panama) API alongside traditional `ByteBuffer`-based backends.

## Components

### Arenas

An **Arena** is a contiguous region of memory from which fixed-size blocks areallocated sequentially. The module provides two implementations:

- **`ByteBufferArena`** — backed by `java.nio.ByteBuffer` (direct or wrapped). Suitable for general-purpose use and mmap integration.
- **`FfmArena`** — backed by `java.lang.foreign.Arena` (Java 25 FFM API). Provides true off-heap allocation with automatic native memory management.

Both arena types support little-endian and big-endian byte orders, resetting (reclaiming all allocated space), and memory views for direct read/write access.

### Memory View

The `MemoryView` interface provides typed access to arena memory:

- `getLong` / `putLong` — read/write a single `long` at an offset
- `getLongs` / `putLongs` — bulk read/write arrays of `long` values
- `getInt` / `putInt` — read/write a single `int` at an offset
- `getBytes` / `putBytes` — byte-level access (default methods on the interface)

### LongToLongsIndex

A compact, open-addressing hash index that maps `long` keys to arrays of `long` values. It is the core data structure for storing metadata associations.

- **Hash table** with linear probing, power-of-2 capacity
- **Variable-length value lists** — each key can map to a dynamically growing array of longs
- **Off-heap capable** — all data lives in the arena's memory, not on the Java heap
- **Serializable format** — supports reading and writing to memory-mapped files via `IndexMmapReader` and `IndexMmapWriter`
- **Binary format** defined by `CompactIndexFormat`: little-endian, 64-byte header, magic bytes `ARENA01\0`

**The arena must have room for the fixed region *and* the values.** The layout is `HEADER_SIZE + 16 * capacity` bytes of fixed region (header, key table, value pointers) followed by the value lists. The fixed region is written straight into the arena's storage while only the data area is allocated, so `totalSize()` is `fixedRegion + arena.size()`. An arena sized to the fixed region alone constructs fine and then fails on the first `put` with an `IndexOutOfBoundsException`; size it from that formula plus the values you expect to store. `Arena` carries one more rule worth reading before use: `view()` covers the whole storage, and a view taken before a growth still points at the *old* storage — take the view after the last allocation that may grow.

Key `0` is reserved as the empty-slot marker and capacity must be a power of two (probing masks with `capacity - 1`). Values keep insertion order, and a value list is rebuilt by copying rather than grown in place.

### Mmap I/O

- **`IndexMmapReader`** — memory-maps an existing index file and exposes a `LongToLongsIndex` over it
- **`IndexMmapWriter`** — writes a `LongToLongsIndex` to a file via a direct `ByteBuffer`

The mmap format is little-endian and designed for cross-backend compatibility (works with both `ByteBufferArena` and `FfmArena`). Because that order is a decision rather than an accident, the writer **refuses an index built over a big-endian arena** instead of producing a file the little-endian reader would misread; the error names the byte order. Both writer and reader are covered by round-trip tests that assert the values, the exact file size (the format has no padding), and the validation of the magic and version.

## Endianness

The module uses **little-endian** byte order exclusively. This choice is driven by SIMD friendliness: little-endian layout ensures zero-cost type reinterpretation (bitcasting) and straightforward vector element indexing on both x86 (AVX) and ARM (NEON/SVE) architectures. See [docs/endian.md](docs/endian.md) for the full rationale.

## Module Structure

```
jcodebuddy/metadata-arena/
├── pom.xml
├── README.md
├── docs/
│   └── endian.md          # Endianness design rationale
└── src/
    ├── main/java/hr/hrg/watch2/arena/
    │   ├── Arena.java                    # Arena interface
    │   ├── ByteBufferArena.java          # ByteBuffer-backed arena
    │   ├── ByteBufferArenaImpl.java      # ByteBuffer arena implementation
    │   ├── ByteBufferMemoryView.java     # ByteBuffer-based memory view
    │   ├── FfmArena.java                 # FFM-backed arena
    │   ├── FfmArenaImpl.java            # FFM arena implementation
    │   ├── FfmMemoryView.java           # FFM-based memory view
    │   ├── MemoryView.java              # Memory view interface
    │   ├── CompactIndexFormat.java      # Binary format constants
    │   ├── IndexMmapReader.java         # Memory-mapped index reader
    │   ├── IndexMmapWriter.java         # Memory-mapped index writer
    │   └── LongToLongsIndex.java        # Long-to-longs hash index
    └── test/java/hr/hrg/watch2/arena/
        ├── ArenaTest.java                   # both backends: allocation, views, growth, reset, read-only
        ├── CompactIndexFormatTest.java      # the wire format's constants and layout arithmetic
        ├── IndexMmapRoundTripTest.java      # values, exact file size, magic/version, byte-order refusal
        ├── LongToLongsIndexLayoutTest.java  # sizing requirement, value-list growth, both backends
        ├── LongToLongsIndexTest.java        # round-trip, collisions, rebuild, swap, mmap, zero key
        └── MemoryViewTest.java              # typed + bulk + byte access, both backends
```

Every contract the tests assert is stated where a caller reads it: the interfaces (`Arena`, `MemoryView`),
`LongToLongsIndex`'s javadoc, and this file. The tests exist so those statements cannot quietly stop being
true — several of them were written against behaviour that turned out to be wrong, and are noted in the
class javadoc of the test that caught them.

## Benchmarks

`ArenaIndexJmhBenchmark` measures the index over **both** backends at two table sizes:

- `getHot` / `getRandom` — the cache's steady-state lookup, and the same probe on a cold slot
- `rebuild` — `reset()` plus re-inserting every entry, which is DEC-W009's rebuild protocol measured as the
  caller performs it
- `mmapLoad` — map the file written during setup, read every entry, unmap: the cold-start path a metadata
  server takes when it finds an index on disk

Run it through the repository's one JMH entry point:

```bash
bun run scripts/run-jmh.js --include ".*ArenaIndexJmhBenchmark.*"
```

The runner supplies the profile (3 forks, 6×2 s warmup, 8×2 s measurement) and warns when it is lowered, so
the benchmark class declares no iterations of its own. `-Pjmh` activates the JMH annotation processor, and
this module's `jmh` profile is what puts `-proc:full` on the compile: without it the benchmark sources
compile, the build stays green, and no `*_jmhTest` harness exists to run.

The numbers decide one thing worth deciding — which backend the metadata cache should default to, and
whether a full rebuild is cheap enough to run on every watcher batch. They are recorded where that decision
is made, not here: a number in a README is stale the moment the machine changes. The recorded run and the
decision it settled are in
[DEC-W009's implementation note](../../doc/architecture/decisions-watch/DEC-W009.md): `ByteBufferArena` is the
default (about 10× the hot-path throughput of `FfmArena`, measured), a full rebuild fits the watcher's
300 ms debounce by two orders of magnitude at realistic sizes, and `FfmArena` remains the answer for an
arena beyond the 2 GiB a `ByteBuffer` can address.

## Position in JCodeBuddy

`metadata-arena` is a foundational library within the JCodeBuddy project. It is currently a standalone module under active development, intended to serve as the storage layer for the `metadata-server` and `metadata-mcp-server` modules. Unlike the higher-level metadata modules (which handle JSON-RPC transport and MCP tool integration), `metadata-arena` focuses purely on memory management and compact index structures.

## Building

The module requires **Java 25** and is built with Maven:

```bash
mvn compile -pl metadata-arena
mvn test -pl metadata-arena
```

## Dependencies

- **slf4j-api** — optional logging facade
- **JUnit Jupiter** — test dependencies
- **JMH** — benchmark infrastructure (test scope)