# DEC-W006: Metadata cache with per-hash invalidation and cross-module reference tracking

- Status: Proposed
- Date: 2026-07-24
- Owners: project
- Related docs: [DEC-W005: Code generation interface contract](DEC-W005.md), [DEC-W007: Unified source metadata model](DEC-W007.md), [DEC-W008: Metadata parsing without cache](DEC-W008.md), [DEC-W009: In-RAM metadata relations storage](DEC-W009.md), [java-watch-core](../README.md), [java-watch-run](../README.md), [project-automation](../README.md)
- Supersedes: -
- Superseded by: -

## Context

Code generators in the watch and project-automation subsystem currently must re-parse and re-analyze source files on every change event, even when the file content has not materially changed. This wastes CPU and I/O, especially in multimaven projects with many modules and deep class hierarchies. There is no shared, stable place for generators to deposit calculated metadata keyed by file identity, nor is there a mechanism to expire stale entries safely when switching branches or after a retention window.

A metadata cache is needed that:
- keys entries by hash (wayhash algorithm) so unchanged files are not re-analyzed,
- supports multiple independent generators writing into the same cache without collision,
- stores metadata at multiple resolution levels (file, import section, class, field, method, parameter),
- can expire entries that are absent from the current project state and older than a configurable threshold,
- survives branch switches so that files that reappear after checkout do not force full recalculation,
- tracks cross-module references so that a change in one module invalidates dependent metadata in another,
- scopes each cache entry to metadata derived exclusively from its own source file,
- stores correlation metadata (derived from reading other files) separately from the cache entry with its own dependency hash.

## Decision

Introduce a **per-module metadata cache** backed by content-addressed storage. The cache is owned by the project-automation layer and exposed to generators through the `CodeGenerator`/`CodeContext` contract defined in DEC-W005. Each cache entry is a `CacheEntry` record (defined in DEC-W007) keyed by wayhash.

### Cache identity and keying

Each cache entry is a **`CacheEntry`** record keyed by a **hash** computed with the **wayhash** algorithm of the source file bytes. The hash is computed once when a file is first read and reused for all subsequent lookups within the same debounce cycle.

```java
public record CacheEntry(
    byte[] hash,
    String fullClassName,
    String relativePath,
    SourceMetadata metadata  // nullable: null during inventory phase
) {}
```

`CacheEntry` lives in `hipster-entity-tooling.meta` alongside `EntityMeta`, `ViewMeta`, etc.

### Metadata hierarchy

A single file produces a **tree of metadata nodes** as defined in [DEC-W007](DEC-W007.md), stored within a `CacheEntry` record and keyed by its own path within the file:

| Level          | Key shape                                                         | Example                                                                 |
| -------------- | ----------------------------------------------------------------- | ----------------------------------------------------------------------- |
| File           | `root`                                                            | General file-level data (package, declared types, encoding)             |
| Import section | `imports`                                                         | Common import block data                                                |
| Class          | `class:<simpleName>`                                              | Per-class metadata (modifiers, annotations, superclass)                 |
| Field          | `class:<simpleName>.field:<name>`                                 | Per-field metadata (type, modifiers, annotations)                       |
| Method         | `class:<simpleName>.method:<name>(<paramTypeList>)`               | Per-method metadata (return type, modifiers, annotations, body hash) |
| Parameter      | `class:<simpleName>.method:<name>(<paramTypeList>).param:<index>` | Per-parameter metadata (type, annotations)                              |

Generators write only to the nodes they own within the `CacheEntry`. The `metadata` field is nullable: it is `null` during the inventory phase and populated later during enrichment.

### Cross-module references

References to types, fields, or methods in other modules are stored as **qualified links** that include the producing file's relative path to disambiguate same-named classes within a module:

```
<moduleKey>::<relativePath>#<memberPath>@<hash>
```

- `moduleKey` identifies the producing Maven module (e.g., `com.example:core`).
- `relativePath` is the path from the project root to the source file (e.g., `core/src/main/java/com/example/Foo.java`).
- `memberPath` is the hierarchical path from the hierarchy table above:
  - Class: `class:<simpleName>`
  - Field: `class:<simpleName>.field:<name>`
  - Method: `class:<simpleName>.method:<name>(<paramTypeList>)`
  - Parameter: `class:<simpleName>.method:<name>(<paramTypeList>).param:<index>`
- `hash` is the **wayhash** of the producing file at the time the reference was recorded.

Including `relativePath` disambiguates references because class names are not unique within a module. It also allows stale-link detection even when class names collide. When the producing file changes, its hash changes; any link that points to the old hash is recognized as stale and triggers recalculation of the dependent metadata.

### Retention and cleanup

The cache maintains a **retention window** (default configurable, e.g., 7 days). On cleanup:

1. The current project state (set of files known to the watcher) is compared against the set of hashes present in the cache.
2. Any hash absent from the current state **and** older than the retention window is evicted.
3. Hashes absent from the current state but within the retention window are kept so that branch switches or undo operations can reuse them without recalculation.

Cleanup runs as part of the `BatchedFileWatcher` overflow / full-recompile path and may also be triggered on demand when the watcher detects a large batch of deletions.

### Two-phase population

The cache supports two-phase population to keep the initial inventory pass fast:

1. **Inventory phase (single-threaded, fast):**
   - Scan all source files in the module.
   - Compute wayhash for each file.
   - Write `CacheEntry(hash, fullClassName, relativePath, metadata=null)` to cache.
   - Update module index.
   - This phase must not invoke JavaParser or any expensive metadata generation.

2. **Enrichment phase (multi-threaded):**
   - Iterate over cache entries with `metadata == null`.
   - Distribute entries across worker threads.
   - Each thread parses source with JavaParser, builds `SourceMetadata` tree, and writes back to the existing `CacheEntry`.
   - Index entries are updated atomically after metadata is written.

**Contract for consumers:** All cache read APIs must handle `metadata == null` gracefully:
- `get(hash)` returns a `CacheEntry` with `metadata == null`.
- `get(hash, TypeMeta.class, "class:com.example.Foo")` returns `null` if `metadata` is absent.
- Generators that require metadata must check for null and either skip or trigger enrichment.

### Per-module scope and index

Each Maven module gets its own cache directory. Cross-module links remain valid because they carry the producer module key and hash; if a producer module is not present in the current project state, its stale links are cleaned up by the same retention logic.

Each module maintains a single `index.fury` file that maps:
- `relativePath` → `hash`
- `fullClassName` → `hash` (stored as a list to handle collisions)
- `hash` → `CacheEntry`

The index is updated atomically when entries are written or invalidated. Cleanup uses the index to determine which hashes are absent from the current project state.

### Generator contract

Generators interact with the cache through a typed API. The cache stores `CacheEntry` records (defined in [DEC-W007](DEC-W007.md)) keyed by file hash:

- `MetadataCache.get(hash)` — read the `CacheEntry` for a hash. `metadata` may be `null` during inventory phase.
- `MetadataCache.get(hash, type, path)` — read a specific metadata node at the given path. Returns `null` if `metadata` is absent or the path is absent.
- `MetadataCache.put(hash, path, value)` — write metadata owned by this generator into the node's tree at the specified path.
- `MetadataCache.link(fromHash, toModuleKey, toRelativePath, toMemberPath)` — record a cross-module reference.
- `MetadataCache.invalidate(hash)` — mark all nodes for a hash as dirty (called on change detection).

Generators MUST NOT overwrite metadata written by another generator. Cache implementations enforce this by namespace-prefixing generator IDs or by returning an existing-value error on conflicting writes.

### Correlation metadata and dependency hashing

Metadata generated by reading a single source file (its own `SourceMetadata` tree) is stored inside the `CacheEntry` for that file's wayhash. This covers all information that can be derived from parsing the file alone: class hierarchy, method signatures, field types, annotations, and import declarations.

Any metadata that requires reading other files — such as cross-module type resolution, RPC method inventories, or annotation collector indexes — MUST NOT be stored inside the cache entry. Instead, correlation metadata MUST be stored separately, keyed by a composite hash that encompasses all dependency hashes.

```java
// Correlation metadata is stored outside CacheEntry, keyed by a dependency hash
public record CorrelationEntry(
    byte[] dependencyHash,   // hash computed from all constituent file hashes
    Object correlationData   // annotation indexes, RPC inventories, etc.
) {}
```

The `dependencyHash` is computed as a wayhash of the sorted concatenation of all file wayhashes that contribute to the correlation metadata. When any constituent file changes, its wayhash changes, the `dependencyHash` changes, and the correlation metadata is recognized as stale and recalculated.

Correlation metadata MUST be calculated on the fly and is unlikely to be cached persistently. It MAY be held in an in-memory index with fast rebuild capability.

Out-of-module annotations (e.g., an `@RpcMethod` annotation whose defining class is in a different module) MUST be represented as a qualified reference (module key + relative path + method signature) rather than being embedded as local metadata. The annotation index in correlation metadata maps these qualified references back to their source files.

## Alternatives considered

- **In-memory-only cache** — rejected because it does not survive process restarts or branch switches, and multimaven projects can exceed reasonable heap sizes.
- **Global single-file cache (e.g., single JSON or SQLite DB for the whole project)** — rejected because it creates a write hotspot and makes per-module cleanup coarse-grained; multimaven builds often add or remove entire modules, which would require scanning the whole file.
- **Hash-only flat key-value store without hierarchy** — rejected because generators frequently need only a subset of metadata (e.g., methods without re-reading fields); flat storage forces full deserialization on every read.
- **Timestamp-based invalidation only (no wayhash)** — rejected because it cannot detect external changes (branch switch, revert) and would either over-invalidate or retain stale data.
- **Reference counting with manual generation counts** — rejected because it requires generators to cooperate on reference management, which is fragile; hash links are self-healing.

## Consequences

- Positive: Unchanged files across debounce cycles and branch switches are served from cache, reducing generator CPU and I/O.
- Positive: Cross-module references are explicit and self-healing; a producer change automatically propagates invalidation to consumers. Including `relativePath` in links disambiguates same-named classes within a module.
- Positive: Retention window balances memory pressure against branch-switch reuse.
- Positive: Two-phase population enables fast project-state snapshots and parallel metadata generation.
- Negative: Cache storage grows with project size and must be bounded; cleanup strategy must be tested under rapid module add/remove.
- Negative: Generators must adopt the new cache API and handle `metadata == null` in read paths; legacy generators need migration.
- Follow-up: Define the serialization format for `SourceMetadata` (Apache Fury/Fory is preferred) and the on-disk layout.
- Follow-up: Add cache hit-rate and eviction-rate metrics to the agent daemon observability layer.

## Out of scope

- Remote or distributed cache — the initial scope is local disk, per-module, per-JVM.
- Cache encryption or access control — this is a developer-time artifact, not a runtime dependency.
- Hot-swap of cache format versions — format changes require full cache invalidation for the affected module.
- Integration with build-system caches (Maven local repository, Gradle build cache) — those are complementary but separate concerns.

## Acceptance criteria

- A generator can store and retrieve file-level, class-level, field-level, method-level, and parameter-level metadata by hash without collision with other generators.
- Cross-module references stored as `<moduleKey>::<relativePath>#<memberPath>@<hash>` links MUST be recognized as stale when the producer hash changes.
- Cleanup MUST retain hashes absent from the current project state for a configurable retention window (default 7 days).
- Cleanup MUST evict hashes absent from the current state and older than the retention window.
- The cache API MUST reject or namespace-conflict writes from different generators to the same node.
- Branch switches that remove and restore files MUST NOT force full recalculation of restored files if their hash is still within the retention window.
- The cache MUST support two-phase population: inventory (fast, `metadata=null`) followed by enrichment (parallel parsing).
- The cache MUST handle `metadata == null` gracefully in all read APIs.
