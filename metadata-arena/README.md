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
- **Binary format** defined by `CompactIndexFormat`: little-endian, 64-byte header, magic bytes `ARENA\0\001`

### Mmap I/O

- **`IndexMmapReader`** — memory-maps an existing index file and exposes a `LongToLongsIndex` over it
- **`IndexMmapWriter`** — writes a `LongToLongsIndex` to a file via a direct `ByteBuffer`

The mmap format is little-endian and designed for cross-backend compatibility (works with both `ByteBufferArena` and `FfmArena`).

## Endianness

The module uses **little-endian** byte order exclusively. This choice is driven by SIMD friendliness: little-endian layout ensures zero-cost type reinterpretation (bitcasting) and straightforward vector element indexing on both x86 (AVX) and ARM (NEON/SVE) architectures. See [docs/endian.md](docs/endian.md) for the full rationale.

## Module Structure

```
metadata-arena/
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
        └── LongToLongsIndexTest.java    # Index tests (round-trip, collisions, mmap, swap)
```

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