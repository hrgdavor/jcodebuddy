# DEC-W009: In-RAM metadata relations storage with arena allocation and annotation indexes

- Status: Proposed
- Date: 2026-07-28
- Owners: project
- Related docs: [DEC-W006: Metadata cache with per-hash invalidation](DEC-W006.md), [DEC-W007: Unified source metadata model](DEC-W007.md), [DEC-W008: Metadata parsing without cache](DEC-W008.md), [metadata-server](../README.md), [rpc-dispatcher](../README.md)
- Supersedes: -
- Superseded by: -

## Context

The metadata cache (DEC-W006) stores per-file `CacheEntry` records keyed by wayhash. Each entry contains `SourceMetadata` derived exclusively from the file's own source bytes. However, consumers such as the RPC dispatcher, annotation collectors, and cross-module reference trackers need to efficiently answer questions that span multiple files:

- Which files contain methods annotated with `@RpcMethod`?
- What is the dependency graph between files (which file's metadata depends on which other file's hash)?
- When a file changes, which dependent correlation metadata entries must be invalidated?

Currently, this cross-file information is not stored in any structured, efficiently queryable form. The correlation metadata described in DEC-W006 and DEC-W007 is calculated on the fly and is unlikely to be cached persistently. However, the in-memory representation of these relations must be efficient: it SHOULD use an arena allocator with minimal heap allocations, and it MUST support fast rebuild when file hashes change.

The existing `RpcDispatcher` uses reflection to scan `@RpcMethod` annotations on service objects at registration time. A more systematic approach is needed where annotation indexes are built from parsed source metadata and can be rebuilt incrementally when file wayhashes change.

## Decision

In-RAM metadata relations MUST be stored in a dedicated relations index that uses an arena allocator (off-heap or contiguous memory block) to minimize GC pressure and heap fragmentation. The index MUST maintain three structures:

1. **Dependency graph** — a mapping from each file's wayhash to the set of wayhashes it depends on, enabling invalidation propagation when any constituent file changes.
2. **Annotation index** — a mapping from annotation qualified names to the set of wayhashes of files containing types or methods annotated with that annotation, enabling collectors (e.g., RPC handlers) to enumerate files of interest without scanning all files.
3. **Hash-to-file-index** — a bidirectional mapping from wayhash to file relative path and module key, enabling lookup by hash for correlation metadata resolution.

### Arena allocation

The relations index MUST be backed by an arena allocator that allocates memory from a single contiguous block (or a small pool of blocks). Individual entries (dependency edges, annotation index entries) MUST be stored as compact records within the arena without individual heap allocations.

Each entry in the dependency graph is a pair:

```
(sourceHash, dependentHash)
```

Where `sourceHash` is a file's wayhash, and `dependentHash` is the wayhash of a file whose `SourceMetadata` depends on `sourceHash` (e.g., a file that imports a type defined in `sourceHash`'s file).

Each entry in the annotation index is:

```
(annotationQualifiedName, fileWayhash)
```

Both structures MUST support O(1) insertion and O(n) full rebuild, where n is the number of entries.

### Rebuild on change

The relations index MUST support a `rebuild` operation that is triggered when any file's wayhash changes. On rebuild, the entire index is recomputed from the current set of `SourceMetadata` trees and correlation metadata. The rebuild MUST:

1. Clear the arena (reset the arena pointer to the beginning of the contiguous block).
2. Re-scan all `SourceMetadata` trees in the current cache.
3. Re-populate the dependency graph and annotation index from scratch.
4. Publish the new index atomically (swapping a pointer to the rebuilt arena).

Atomic swap MUST ensure that readers always see a consistent index state — either the old complete index or the new complete index, never a partially rebuilt one.

Because the arena is simply reset (not deallocated per-entry), rebuild is O(n) in the number of edges but avoids per-entry deallocation overhead and heap fragmentation.

### Dependency hash for correlation metadata

Correlation metadata (computed from reading multiple files) uses a composite `dependencyHash` that encompasses all constituent file wayhashes. The `dependencyHash` is used as the key when storing correlation metadata in-memory (not in the persistent cache). When any constituent file changes, the `dependencyHash` changes, the old correlation metadata becomes unreachable, and it is recalculated on the next access or rebuild cycle.

The dependency graph in the arena index tracks which `dependencyHash` values depend on which file wayhashes, enabling efficient invalidation: when file `F` changes, the index finds all correlation entries whose `dependencyHash` includes `F`'s wayhash and marks them as stale.

### Annotation index for RPC and other collectors

Annotation indexes MUST be generic enough to support any annotation used as a collection marker, not only `@RpcMethod`. The index is keyed by annotation qualified name:

```
Map<String, Set<Wayhash>> annotationIndex
```

Collectors that need an inventory of files with a specific annotation (e.g., RPC handlers needing all `@RpcMethod`-annotated methods across the project) MUST query the annotation index rather than iterating all cache entries. If a collector needs method-level detail, it uses the wayhash to look up the `SourceMetadata` tree for that file and extracts the specific method descriptors.

The `@RpcMethod` annotation inventory specifically MUST map from annotation qualified name to a set of qualified method references (`className#methodName(signature)`), not just file wayhashes. This allows the RPC dispatcher to discover all RPC endpoints without scanning all modules.

### Memory model and access patterns

The arena index MUST be accessible from any thread that reads metadata. Writers (rebuild operations) MUST NOT mutate the arena while readers are accessing it. The atomic swap approach described above achieves this without locks on the read path.

The index SHOULD be rebuilt on a schedule aligned with the project's full-recompile cycle or on demand when a large batch of file changes is detected. Incremental updates (adding or removing single entries) MAY be supported for small change sets, but a full rebuild MUST be the guaranteed-correct fallback.

## Alternatives considered

- **HashMap-based index on the Java heap** — rejected because per-entry heap allocations cause GC pressure in large multimaven projects and fragmentation makes long-running processes degrade. Arena allocation avoids these issues.
- **Persistent storage for correlation metadata** — rejected because correlation metadata is computed fresh on each rebuild and rarely needs to survive process restarts; the persistent cache (DEC-W006) is the wrong place for frequently recomputed data.
- **Scanning all cache entries on every RPC dispatch** — rejected because O(n) scans of the full file set on every request are too expensive for large projects; an index provides O(1) or O(k) lookup where k is the number of matching files.
- **Incremental update only, no full rebuild** — rejected because incremental updates are fragile when many files change simultaneously (branch switches, large recompiles); full rebuild guarantees correctness at the cost of occasional O(n) rebuild.
- **Storing correlation metadata inside CacheEntry** — rejected because it blurs the boundary between file-scoped metadata and cross-file correlations, making invalidation harder; correlation metadata MUST live outside the cache entry as specified in DEC-W006 and DEC-W007.

## Consequences

### Positive

- Annotation indexes and dependency graphs are queryable in O(1) for lookup and O(k) for range queries, enabling fast RPC dispatch and cross-module invalidation.
- Arena allocation eliminates per-entry heap allocation overhead and GC pressure, enabling the index to scale with large multimaven projects without degradation.
- Full rebuild on change guarantees index consistency; partial rebuilds are not possible.
- Atomic swap on rebuild ensures readers never see a partially reconstructed index.
- The `dependencyHash` mechanism provides a precise invalidation signal: any file change immediately invalidates exactly the correlation entries that depend on it.

### Negative

- Full rebuild on large change sets is O(n) in the number of entries, which may cause latency spikes during initial project scanning or large branch switches.
- Arena allocation requires a contiguous memory block; very large projects with many files and deep dependency graphs may require tuning the arena size.
- The `@RpcMethod` inventory requires a separate mapping from annotation to qualified method references, which adds a second index layer beyond simple file→hash mapping.
- Correlation metadata must be recalculated on every rebuild cycle, even if nothing relevant changed, unless a change-detection mechanism skips unchanged `dependencyHash` values.

### Follow-up

- Define the arena allocator implementation details (off-heap via `sun.misc.Unsafe`, `ByteBuffer` direct allocation, or a library like `Chronicle Bytes`).
- Determine the rebuild trigger policy (full-recompile cycle, debounced batch, on-demand).
- Benchmark arena size vs. project size to establish default configuration values.

## Out of scope

- Persistent storage of the relations index; only in-memory storage is in scope.
- Distributed relations across multiple JVMs.
- Detailed serialization format for correlation metadata (addressed in DEC-W007).
- Cache encryption or access control.
- Build-system integration for relations index rebuild scheduling.

## Acceptance criteria

- The arena index MUST support O(1) insertion of dependency edges and annotation index entries.
- The arena index MUST support a full rebuild triggered by any file wayhash change.
- Rebuild MUST be atomic: readers always see either the old complete index or the new complete index.
- The annotation index MUST support querying by annotation qualified name to get all file wayhashes (or qualified method references for `@RpcMethod`).
- The dependency graph MUST support lookup of all correlation entries affected by a changed file's wayhash.
- `parse` in DEC-W008 MUST produce file-scoped `SourceMetadata` only; correlation data MUST be stored in the arena index, not in cache entries.
- Arena allocation MUST not use per-entry heap allocations for individual edges or index entries.