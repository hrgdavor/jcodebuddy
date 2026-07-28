# DEC-W008: Metadata parsing without cache as manual-mode fallback and dependency-free tool path

- Status: Proposed
- Date: 2026-07-28
- Owners: project
- Related docs: [DEC-W005: Code generation interface contract](DEC-W005.md), [DEC-W006: Metadata cache with per-hash invalidation](DEC-W006.md), [DEC-W007: Unified source metadata model](DEC-W007.md), [metadata-server](../README.md), [metadata-mcp-server](../README.md), [project-automation](../README.md), [hipster-entity-tooling](../README.md)
- Supersedes: -
- Superseded by: -

## Context

The current metadata-server and metadata-mcp-server expose only cache-backed RPC and MCP tools: `get_entry`, `list_entries`, `get_metadata`, `has_changed`, `list_classes`. The `MetadataProvider` interface has no method for generating metadata from source bytes when the cache is absent.

This blocks two important workflows:

1. **Manual invocation** — a developer running `jcodebuddy metadata parse <file>` from the CLI has no path to obtain metadata without first populating a cache through a daemon or watch process.
2. **Dependency-free tooling** — tools that only need metadata for a single file (e.g., CI scripts, pre-commit hooks, simple analysis utilities) should not be required to start a cache server or run an inventory pass first.

DEC-W006 and DEC-W007 already define the cache architecture and the `CacheEntry` / `SourceMetadata` model. The metadata cache is an optimization layer, but it currently sits in front of all metadata generation, making cache the prerequisite rather than an optional accelerator.

The sequencing problem is that the cache stack blocks metadata generation entirely when no cache entry exists. The `MetadataAnalysis.scan()` workflow in project-automation populates the cache eagerly, but ad-hoc single-file use cases cannot afford that overhead.

## Decision

The core contract that `MetadataProvider` MUST expose a `parse` method capable of producing a fully populated `CacheEntry` (or equivalent `SourceMetadata`) from source bytes without any backing store interaction.

### MetadataProvider.parse contract

`MetadataProvider` MUST add a method:

```java
CacheEntry parse(String relativePath, byte[] sourceBytes);
```

The returned `CacheEntry` MUST contain:
- `hash` — the wayhash of the source bytes (consistent with DEC-W006/DEC-W007 cache keying),
- `fullClassName` — the primary class declared in the source file,
- `relativePath` — the path passed as input,
- `metadata` — a fully populated `SourceMetadata` tree (never null for valid Java sources, file-scoped only).

`SourceMetadata` contains ONLY information that can be derived from the file's own source bytes. Cross-file resolved references, annotation inventories, and any metadata requiring reading other files MUST NOT be in `SourceMetadata`. Those correlation data MUST be stored outside the cache entry with their own dependency hash (covered in DEC-W009).

`parse` MUST NOT write to any cache, index, or backing store. It is a pure function from `(relativePath, sourceBytes)` to `CacheEntry`. Cache write-back is a separate concern (P1, DEC-W009 or follow-up).

### Manual-mode CLI

A CLI entry point `jcodebuddy metadata parse <file>` MUST invoke `MetadataProvider.parse` directly and emit the resulting `SourceMetadata` (or a serialized representation) to stdout. This command MUST work in a fresh checkout with no daemon, no cache folder, and no prior `scan` invocation.

The CLI module owning this command SHOULD be `project-automation`, since it already hosts `MetadataAnalysisRunner` and `InMemoryMetadataCacheProvider`.

### Dependency-free tool path

Tools that consume metadata SHOULD be able to use the `parse` method directly, bypassing the cache layer entirely. The `MetadataRpcService` and `MetadataMcpToolProvider` MAY expose additional RPC/MCP methods (`parseFile` / `parse_file`) that call `parse` internally, but these are additive and MUST NOT break existing cache-backed tools.

### Cache write-back as a later layer

Persisting `CacheEntry` records by hash to `.cache/<hash>.fury` and maintaining `index.fury` is deferred to P1. A `MetadataCacheWriter` component will handle write-back, retention cleanup, and index maintenance in a separate decision.

### Cache read with fallback

`MetadataProvider.get(hash)` SHOULD try the cache first and fall back to a no-op or empty result when the entry is absent. A future enhancement (P1/P2, documented in DEC-W009 or follow-up) MAY allow `get(hash)` to fall back to `parse` for entries not yet in the cache, but this is out of scope for DEC-W008.

### Implementation boundaries

- `InMemoryMetadataCacheProvider` SHOULD override `parse` to avoid redundant work when the caller already has access to the in-memory map, but this is not required. The default behavior of `parse` MUST work correctly regardless of whether overriding exists.
- The metadata-server module adds `parseFile` to its RPC surface and `parse_file` to its MCP tool surface as optional, additive methods. These MUST NOT change the behavior of existing cache-backed methods.
- `hipster-entity-tooling` (which provides JavaParser-based parsing) is the expected implementation of `parse`. Whether `parse` lives in `metadata-server` or `project-automation` depends on module dependency resolution; the contract is defined here, and the implementation location is a follow-up decision.

### Source metadata as interchange

`parse` returns the same rich `SourceMetadata` model defined in DEC-W007 that enriched cache entries use, but ONLY for information that can be derived from the file's own source bytes alone. `SourceMetadata` MUST NOT contain cross-file resolved references, annotation indexes, or any correlation data that requires reading other files. This ensures interchange format consistency: producers (whether cache enrichment or on-demand parse) and consumers always see the same typed, file-scoped tree.

### Implementation order

1. **P0:** `parse` produces `SourceMetadata` from source bytes via `hipster-entity-tooling` JavaParser integration.
2. **P1:** `MetadataCacheWriter` persists `CacheEntry` by hash to configurable cache folder.
3. **P1/P2:** `get(hash)` falls back to `parse` when entry is absent or metadata is null.
4. **P2+:** Retention cleanup, index rebuild, cross-module link tracking.

## Alternatives considered

- **Require cache before any tool runs** — rejected because it blocks manual invocation and single-file tooling; adding a cache before metadata generation makes the simple case dependent on the complex case.
- **Separate cache-only and no-cache APIs** — rejected because it duplicates the provider interface and forces consumers to choose a code path upfront; a unified `parse` method that works independently or alongside cache is simpler and additive.
- **Always parse on demand and never cache** — rejected because it eliminates the performance benefit of the cache for repeated access, which DEC-W006/DEC-W007 are designed to provide; the cache MUST remain the fast path for repeated lookups.
- **Treat inventory-phase null entries as no-cache equivalent** — rejected because null-`metadata` entries still require a prior `scan` and cache infrastructure; they are not independently usable for ad-hoc single-file queries.

## Consequences

### Positive

- Metadata generation is testable and useful before any cache exists, reducing the bootstrap complexity for new tools and scripts.
- Manual CLI invocation works in a fresh checkout without starting a daemon or populating a cache.
- Simple tools (CI hooks, pre-commit checks, linters) have a simpler path that does not require cache setup or teardown.
- Cache population can proceed iteratively; the `parse` contract is stable and can be layered with cache read/write later.
- The change is additive to the existing API surface; no existing methods are modified or removed.

### Negative

- No-cache re-parses every time; repeated calls for the same file do not benefit from caching unless the caller manages caching externally.
- Parity MUST be maintained between `parse` output and cache-read output for `SourceMetadata`; any schema change to `SourceMetadata` (DEC-W007) affects both paths equally.
- `hipster-entity-tooling` becomes a dependency of `metadata-server` (or the `MetadataProvider` implementation) if `parse` is implemented there, increasing the module's transitive dependency graph.

### Follow-up

- Define the MCP `parse_file` parameter shape and request/response schema.
- Choose the CLI module and packaging for the `jcodebuddy metadata parse` command.
- Determine whether `hipster-entity-tooling` is added as a `metadata-server` dependency or whether `parse` lives in `project-automation`.

## Out of scope

- Cache encryption, remote cache, and build-system cache integration.
- Cache format hot-swap and serialization schema versioning (addressed in DEC-W007).
- Write-through from `parse` to the cache; `parse` MUST NOT perform any cache write.
- `MetadataCacheWriter` implementation and retention cleanup.
- Cross-module link tracking and index rebuild (covered by DEC-W006 follow-ups).
- Correlation metadata (data derived from reading other files, such as annotation indexes and RPC inventories); this is covered by DEC-W009.

## Acceptance criteria

- `parse(relativePath, sourceBytes)` MUST return a non-null `CacheEntry` with non-null `metadata` for valid Java source bytes, without any cache interaction.
- CLI `jcodebuddy metadata parse <file>` MUST work in a fresh checkout with no daemon, no cache folder, and no prior scan.
- MCP `parse_file` MUST return a `SourceMetadata` tree structurally identical to the tree produced by enriched cache entries for the same source file.
- Existing cache-backed RPC and MCP tools (`get_entry`, `list_entries`, `get_metadata`, `has_changed`, `list_classes`) MUST be unaffected by the addition of `parse`.
- Simple tools MUST be implementable using only `parse` without starting a cache server or running an inventory pass.
- Cache write-back and cache-read-with-fallback MAY be layered on later without changing the `parse` method signature or semantics.