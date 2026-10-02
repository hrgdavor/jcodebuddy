# DEC-W009: In-RAM metadata relations storage with arena allocation and annotation indexes

- Status: Proposed
- Date: 2026-07-28
- Owners: project
- Related docs: [DEC-W006: Metadata cache with per-hash invalidation](DEC-W006.md), [DEC-W007: Unified source metadata model](DEC-W007.md), [DEC-W008: Metadata parsing without cache](DEC-W008.md)
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

The first three are settled by the implementation note at the end of this record — the allocator, the
rebuild trigger, and the sizing rule. The fourth is new and comes out of measuring.

- **Define the arena allocator implementation details** (off-heap via `sun.misc.Unsafe`, `ByteBuffer`
  direct allocation, or a library like `Chronicle Bytes`). → **Settled: `ByteBufferArena` direct
  allocation is the default, `FfmArena` stays as the escape hatch** — see Decision 1 in the note.
- **Determine the rebuild trigger policy** (full-recompile cycle, debounced batch, on-demand). →
  **Settled: a debounced full rebuild per watcher batch** (the existing 300 ms debounce), because the
  rebuild is two to four orders of magnitude inside that budget at realistic sizes — see Decision 2.
- **Benchmark arena size vs. project size to establish default configuration values.** → **Settled: the
  formula in the note** (`HEADER + 16 × capacity + 12 × entries + slack`, capacity the next power of two
  at or above twice the entries), with the resulting per-size figures.
- **Why the FFM backend is ~10× slower on the hot path is a hypothesis, not a finding.** The consistent
  explanation — an intrinsified `ByteBuffer` against alignment-1 var-handle access in `FfmMemoryView` — was
  not profiled, and the note records it as unexplained. Anything that puts FFM on a default path owes a
  profile first.

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

## Implementation note — the backend and the rebuild trigger, measured (2026-10-01)

This note answers the first three follow-ups above. It is the decision-grade run `plans/unified-plan.md`
step 2.3 asked for, and the numbers live here rather than in a README because a number in a README is stale
the moment the machine changes.

**How it was produced.** `bun run scripts/run-jmh.js --include ".*ArenaIndexJmhBenchmark.*"` at the
runner's default profile — 3 forks, 6 × 2 s warmup, 8 × 2 s measurement per case, JMH 1.37 — which the
runner warns about *only* when it is lowered; the run took 22 min 29 s and wrote `target/jmh/results.json`
(`results-smoke.json` is the marker of a run that must not be recorded, and it does not exist). Throughput
in ops/ms, higher is better; 24 samples per case except where noted. `ns/op` is derived (10⁶ ÷ ops/ms).

| Mode        | Backend    | Entries | ops/ms    | ±(99.9%) | ns/op   |
| ----------- | ---------- | ------: | --------: | -------: | ------: |
| `getHot`    | ByteBuffer | 1 000   | 121 774.1 | 5 153.2  | 8.2     |
| `getHot`    | ByteBuffer | 100 000 | 127 583.8 | 4 766.5  | 7.8     |
| `getHot`    | FFM        | 1 000   | 11 989.2  | 527.0    | 83.4    |
| `getHot`    | FFM        | 100 000 | 10 989.4  | 1 016.0  | 91.0    |
| `getRandom` | ByteBuffer | 1 000   | 86 943.5  | 10 400.4 | 11.5    |
| `getRandom` | ByteBuffer | 100 000 | 50 281.2  | 2 352.2  | 19.9    |
| `getRandom` | FFM        | 1 000   | 10 721.7  | 381.2    | 93.3    |
| `getRandom` | FFM        | 100 000 | 9 942.1   | 493.6    | 100.6   |
| `rebuild`   | ByteBuffer | 1 000   | 92.773    | 6.894    | 10.8 µs |
| `rebuild`   | ByteBuffer | 100 000 | 0.579     | 0.025    | 1.73 ms |
| `rebuild`   | FFM        | 1 000   | 7.273     | 0.753    | 137 µs  |
| `rebuild`   | FFM        | 100 000 | 0.071     | 0.004    | 14.1 ms |
| `mmapLoad`  | ByteBuffer | 1 000   | 5.549     | 0.246    | 180 µs  |
| `mmapLoad`  | ByteBuffer | 100 000 | 0.246     | 0.016    | 4.06 ms |
| `mmapLoad`  | FFM        | 1 000   | 5.858     | 0.349    | 171 µs  |
| `mmapLoad`  | FFM        | 100 000 | 0.212     | 0.022    | 4.72 ms |

### Decision 1 — the allocator is `ByteBufferArena` direct allocation

`ByteBufferArena` is **one order of magnitude faster on every hot-path mode** and the gaps are far outside
the intervals: `getHot` 10.2× at 1 000 entries and 11.6× at 100 000; `getRandom` 8.1× and 5.1×; `rebuild`
12.8× and 8.2×. The 99.9 % interval of the *slower* backend and the *faster* one do not come within a
factor of four of meeting.

`FfmArena` is not slower on the cold-start path — `mmapLoad` is a wash (FFM 171 µs vs 180 µs at 1 000; FFM
4.72 ms vs 4.06 ms at 100 000, intervals overlapping), which is what one would expect from a path dominated
by mapping and page faults rather than by the accessor.

So: **the relations index allocates through `ByteBufferArena`**, and `FfmArena` stays in the module as the
escape hatch for the two cases where a `ByteBuffer` cannot serve:

- **size** — `ByteBuffer` is indexed by `int`, so one arena is capped at 2 GiB. That binds at roughly 30–40
  million entries with the formula below, far beyond a project's relation count, but it is a hard wall
  rather than a slow path;
- **the platform's own direction** — FFM is the supported API and `sun.misc.Unsafe` is not. `Unsafe` is
  rejected outright (it is deprecated and the JDK already warns when it is touched; the module has no need
  for it), and a new dependency such as `Chronicle Bytes` is rejected because it buys nothing this module
  does not already have in `ByteBuffer` — the third option the follow-up named, answered rather than
  deferred.

**For `metadata-server` this decides the backend it must use when it grows the index**: `ByteBufferArena`,
for the reasons above. It decides nothing about *when* — `metadata-server` does not depend on
`metadata-arena` today (its POM declares slf4j, Jackson and Fory), so this record's storage is not wired
into the server at all yet, and that wiring is a change of its own rather than a line in this note.

### Decision 2 — a full rebuild per watcher batch, and incremental updates are not needed for latency

The watcher's debounce is **300 ms** by default (`HotSwapDaemon.DEFAULT_DEBOUNCE_MS`, and the same fallback
in `EntityRegenerationWatcher`), so that is the budget a batch trigger has. Measured, on the default
backend:

| Entries                                 | Full rebuild | Share of a 300 ms batch |
| --------------------------------------: | -----------: | ----------------------: |
| 1 000                                   | 10.8 µs      | 0.004 %                 |
| 100 000                                 | 1.73 ms      | 0.6 %                   |
| 1 000 000 (extrapolated, 17.3 ns/entry) | ≈ 17 ms      | 6 %                     |
| 10 000 000 (extrapolated)               | ≈ 173 ms     | 58 %                    |

On `ByteBuffer` a batch's budget holds **≈ 17 million entries** (on FFM ≈ 2.1 million at 141 ns/entry).
Both are orders of magnitude above a plausible relations count for one project, and the cold-start path
(`mmapLoad`, 4.06 ms at 100 000 entries) is inside the same budget.

**Therefore: rebuild on a debounced batch, not on a compile cycle and not on demand**, and treat the
incremental path this record permits as an optimisation nobody currently needs — the measured margin is
large enough that a full rebuild per batch is simpler *and* fast enough, which is the combination
DEC-W009 already prefers for correctness. What the batch cannot absorb is not the rebuild but the arena
size the rebuild needs, which is why the numbers below matter more than the timings.

### Arena sizing, from the format rather than from a guess

`ArenaIndexJmhBenchmark.IndexState.arenaBytes` is the formula a caller must satisfy, and it is not a
measurement: `HEADER + 16 × capacity + 12 × entries + slack`, where `capacity` is the next power of two at
or above twice the entries (the table sits well under its load factor). With the rounding, that is between
~44 and ~76 bytes per entry:

| Entries    | Capacity   | Arena    |
| ---------: | ---------: | -------: |
| 1 000      | 2 048      | ≈ 49 KB  |
| 100 000    | 262 144    | ≈ 5.4 MB |
| 1 000 000  | 2 097 152  | ≈ 46 MB  |
| 10 000 000 | 33 554 432 | ≈ 657 MB |

A reasonable default is "measure the relation count, then size by this formula with ~25 % slack", and the
`ByteBuffer` cap is reached at the tens of millions of entries computed above.

### What this run does not establish

- **the cause** of the ByteBuffer advantage. No profiler was used; the consistent explanation remains the
  alignment-1 var-handle access in `FfmMemoryView` against an intrinsified `ByteBuffer`, and it is recorded
  as a hypothesis in the follow-up above. What *is* established is the direction and the magnitude;
- **one case's iteration count.** `getRandom/bytebuffer/1000` ran **5, 5 and 6** measurement iterations per
  fork instead of 8 each — uniform across its three forks, with no error recorded in `results.json`, and the
  cause is not established either (the console output of that run was not kept). Every other case is
  8/8/8. This is disclosed rather than smoothed over, and it does not carry the decision: its 100 000-entry
  sibling is complete, and even its wider interval (±10 400 on 86 943) leaves the gap to FFM's 10 722
  intact. Anyone who needs that one case complete can re-measure it alone —
  `--include ".*getRandom.*"` at the same default profile;
- **anything above 100 000 entries** except by the linear per-entry figure, which is a derivation and is
  labelled as one.
