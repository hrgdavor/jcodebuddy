# DEC-W008: Metadata parsing without cache as manual-mode fallback and dependency-free tool path

- Status: Proposed
- Date: 2026-07-28
- Owners: project
- Related docs: [DEC-W005: Code generation interface contract](DEC-W005.md), [DEC-W006: Metadata cache with per-hash invalidation](DEC-W006.md), [DEC-W007: Unified source metadata model](DEC-W007.md)
- Supersedes: -
- Superseded by: -

> **RENAMED 2026-10-03 (plan step 3.0m).** The module this record calls `metadata-server` is now
> **`jcodebuddy-meta`** — directory, artifactId, `<name>` and package (`hr.hrg.jcodebuddy.meta.*`) — and its
> MCP sibling is `jcodebuddy-meta-mcp`. Nothing about the behaviour below changed: the serving shapes and the
> transports are this record's. The body keeps the name the module had when it was written.

> **IMPLEMENTATION STATUS (2026-10-01) — the P0 half is implemented; four points of the text below were
> wrong and are corrected here.**
>
> **UPDATE 2026-10-03 — correction 1 below is itself corrected, and the requirement it waived is now met.**
> The maintainer allowed `jcodebuddy-meta` to depend on `jcodebuddy-core` (plan step 3.0j), so the module that
> owns this interface now has the repository's one source reader on its classpath and **the default `parse`
> parses**: it delegates to `IndexMetadataProvider.parseSource`. This decision's original requirement — a default
> that "works correctly regardless of whether overriding exists" — is therefore satisfied rather than waived, and
> `MetadataParseUnsupportedException` is now what a provider *chooses* to throw rather than the only option.
> The reference implementation moved with it: `parse` lives in `jcodebuddy-meta`, over the engine's model, and
> `project-automation`'s `SourceMetadataParser` is deleted rather than left as a second assembly of the same
> facts. The class-and-type half arrived at the same time: `IndexMetadataProvider` answers `listClasses`,
> `get(hash)` and `hasChanged` from the class index, and `WatchMetadataProvider` delegates those questions to it
> while keeping what only a watcher knows.
>
> **Implemented.**
>
> - `MetadataProvider.parse(String relativePath, byte[] sourceBytes)` exists on the interface
>   (`jcodebuddy-meta`), and its **default** is the engine-backed implementation.
> - The **reference implementation** is `jcodebuddy-meta`'s `IndexMetadataProvider.parseSource`. It is pure (it
>   reads and writes nothing outside its two arguments), and its facts are file-scoped only: the wayhash of the
>   LF-normalised bytes (DEC-029 § 4), the types the file declares, the primary type's fully qualified name, the
>   type's kind, and its declared method names.
> - The additive surfaces exist: RPC `parseFile` in `MetadataRpcService` and MCP `parse_file` in
>   `MetadataMcpToolProvider`. Both leave the cache-backed methods untouched, and a provider that declines to
>   parse answers with a **named** failure (`MetadataParseUnsupportedException`) rather than a silent `null` —
>   the dispatcher turns it into a JSON-RPC error whose message names the provider.
> - Tests: `IndexMetadataProviderTest` (purity, CRLF/LF checksum equality, broken source, the file-name rule for
>   the primary type, and the index-backed class questions) plus `MetadataServerTest` cases for the RPC surface,
>   for a provider that refuses to parse, and for one that overrides nothing and is parsed for anyway.
>
> **Corrected in this decision's text.**
>
> 1. **"The default behaviour of `parse` MUST work correctly" is not implementable in `metadata-server`.**
>    **Superseded 2026-10-03** — see the update above: the reader now lives in the engine, the provider module
>    may depend on it, and the default parses. What was true when written, and is still true: a default that
>    parses needs the repository's one source reader, and the module that owned the interface did not have it.
>    That is why the location mattered rather than the requirement.
> 2. **`SourceMetadata` does not exist as a type.** DEC-W007's model was never implemented, so `parse`
>    returns the same `Map<String, Object>` payload the cache entries already carry. Inventing a parallel
>    model here would have made the interface's two halves disagree on the day the model lands.
> 3. **The parsing is OpenRewrite's, not JavaParser's.** The migration removed JavaParser from the
>    repository entirely (DEC-030); every sentence below that names it describes the state before
>    2026-09-22.
> 4. ~~**The manual-mode CLI is not implemented.** `jcodebuddy metadata parse <file>` does not exist; the RPC
>    and MCP routes above are the manual-mode path today. The CLI stays open work, scheduled in
>    [`plans/unified-plan.md`](../../../plans/unified-plan.md).~~ **Closed 2026-10-08 (plan step 7.7) — it
>    exists.** The command is `bun scripts/jcodebuddy.js metadata parse <file>`: a Bun launcher (rule § 2 —
>    JavaScript script, Maven build step, never a `.cmd`) resolves JDK 25 through `scripts/lib/toolchain.js`,
>    compiles `project-automation` with its dependencies, exports the classpath once into `.tmp/`, and runs
>    `hr.hrg.jcodebuddy.automation.cli.MetadataCli`, which calls `IndexMetadataProvider.parseSource` — the
>    no-cache path this decision names — and prints the `CacheEntry` as one line of JSON. Exit `0` on success,
>    `2` for a usage error or an unreadable file, and nothing on stdout in that case so a caller piping the
>    output cannot mistake an error for metadata. It was verified against a source file in a directory with **no
>    `.jcodebuddy/`**, and the test asserts that none was created, that the JSON equals the RPC's `parseFile`
>    result field for field, and that a missing file exits `2`.
>
> The status stays **Proposed**: P1 (cache write-back), P1/P2 (`get` falling back to `parse`) and P2+ are
> still unbuilt, and a decision whose later phases have no code is not Accepted.

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

**Implemented 2026-10-08 (plan step 7.7).** `project-automation` owns
`hr.hrg.jcodebuddy.automation.cli.MetadataCli`, and the entry point a person types is

```sh
bun scripts/jcodebuddy.js metadata parse src/main/java/demo/Person.java
```

Four choices are worth recording, because each could have gone the other way:

- **The script is Bun JavaScript and the build step is Maven** (rule § 2), so there is no `.cmd` and no `.sh`.
  The launcher mirrors [`scripts/gen.js`](../../../scripts/gen.js): one
  `-pl project-automation -am compile dependency:build-classpath` invocation, then
  `java -cp <target/classes><delimiter><exported dependencies>`. `mvn exec:java` is deliberately **not** used,
  for the two measured reasons that file records (a direct goal runs on every module in the reactor, and it
  resolves a `provided` dependency from `~/.m2` instead of the reactor). Nothing is packaged and nothing is
  installed, so this works in a checkout nobody has built.
- **The output is the bare `CacheEntry` on one line**, not a JSON-RPC envelope: a CLI's caller wants the entry,
  and the round-trip requirement is satisfied by *equality of the answer* rather than by copying the transport.
  `MetadataCliTest` asserts that equality against a real `parseFile` dispatch, field for field.
- **Exit codes are part of the contract**: `0` with the entry, `2` for a usage error or a file that cannot be
  read, and **nothing on stdout** in the failure case, so `... parse f | jq` cannot silently read an error
  message as metadata.
- **It writes nothing.** No `.jcodebuddy/`, no cache, no index — asserted by the test rather than promised,
  because a manual-mode command that quietly starts a scan would satisfy the output requirement while
  violating the one this section exists for.

### Dependency-free tool path

Tools that consume metadata SHOULD be able to use the `parse` method directly, bypassing the cache layer entirely. The `MetadataRpcService` and `MetadataMcpToolProvider` MAY expose additional RPC/MCP methods (`parseFile` / `parse_file`) that call `parse` internally, but these are additive and MUST NOT break existing cache-backed tools.

### Cache write-back as a later layer

Persisting `CacheEntry` records by hash to `.cache/<hash>.fury` and maintaining `index.fury` is deferred to P1. A `MetadataCacheWriter` component will handle write-back, retention cleanup, and index maintenance in a separate decision.

### Cache read with fallback

`MetadataProvider.get(hash)` SHOULD try the cache first and fall back to a no-op or empty result when the entry is absent. A future enhancement (P1/P2, documented in DEC-W009 or follow-up) MAY allow `get(hash)` to fall back to `parse` for entries not yet in the cache, but this is out of scope for DEC-W008.

### Implementation boundaries

- `InMemoryMetadataCacheProvider` SHOULD override `parse` to avoid redundant work when the caller already has access to the in-memory map, but this is not required. ~~The default behavior of `parse` MUST work correctly regardless of whether overriding exists.~~ **Amended 2026-10-01:** the default throws `MetadataParseUnsupportedException` instead, because a default that parses would need the source reader, which lives in `hipster-entity-tooling` (DEC-030) and is not on this module's classpath. The reference implementation is the override, in `project-automation`.
- The metadata-server module adds `parseFile` to its RPC surface and `parse_file` to its MCP tool surface as optional, additive methods. These MUST NOT change the behavior of existing cache-backed methods. **Implemented 2026-10-01**, with a test that a provider which has no parser still serves `listEntries` unchanged.
- ~~`hipster-entity-tooling` (which provides JavaParser-based parsing)~~ **Amended 2026-10-01:** `hipster-entity-tooling` (which provides the OpenRewrite LST reader; JavaParser left the repository on 2026-09-22, DEC-030) is the expected implementation of `parse`. Whether `parse` lives in `metadata-server` or `project-automation` depends on module dependency resolution; the contract is defined here, and the implementation location is a follow-up decision. **Resolved 2026-10-01:** the reader is in `hipster-entity-tooling`, so the implementation lives in `project-automation` (`SourceMetadataParser`), which already depends on both it and this module.

### Source metadata as interchange

`parse` returns ~~the same rich `SourceMetadata` model defined in DEC-W007~~ **Amended 2026-10-01:** the same `Map<String, Object>` payload the cache entries carry — DEC-W007's typed `SourceMetadata` model does not exist yet, so a second, parallel model here would have made the interface's two halves disagree the day it lands. The payload carries the file-scoped facts only, as this paragraph requires: the wayhash, the primary type's FQN, its kind and its declared method names. It MUST NOT contain cross-file resolved references, annotation indexes, or any correlation data that requires reading other files; those belong in the relation store DEC-W009 describes.

### Implementation order

1. **P0:** `parse` produces file-scoped metadata from source bytes via `hipster-entity-tooling`'s reader. **Implemented 2026-10-01**, except the CLI half of the manual-mode path (`jcodebuddy metadata parse <file>`), which remains open and is scheduled in [`plans/unified-plan.md`](../../../plans/unified-plan.md).
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
- ~~Choose the CLI module and packaging for the `jcodebuddy metadata parse` command.~~ **Settled 2026-10-08 (plan
  step 7.7)**: the module is `project-automation`, as this decision's *SHOULD* proposed, and the packaging is a
  Bun launcher plus `java -cp` over `target/classes` — **no jar and no install**, so the command works in a
  checkout nobody has built. See § *Manual-mode CLI* for the invocation and the four choices it records.
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

---

## Appendix note — Phase 8 of the rewrite migration (2026-09-22)

**The `parse` contract is about *where* parsing lives; its parenthetical about *how* is updated by this note.**

§ "Implementation boundaries" says `hipster-entity-tooling` "(which provides JavaParser-based parsing)" is the expected implementation of `parse`, and § "Implementation order" names "`hipster-entity-tooling` JavaParser integration". **Amended 2026-10-02** (DEC-037, plan step 3.0f): the location changed. This paragraph used to read "the tooling module is still where source bytes are turned into a `SourceMetadata` tree" — that tree is now turned in the **engine** (`jcodebuddy-core`, `hr.hrg.jcodebuddy.engine.meta.SourceMetadata`), because parsing is one path for every consumer rather than one module's private one; `hipster-entity-tooling` consumes it. The parsing is OpenRewrite's, through [`SourceReader`](../../../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/source/SourceReader.java) (`readText(String)`) and `TreeQueries`, not `com.github.javaparser`.

The contract itself is untouched: `parse(relativePath, sourceBytes)` is a pure function with no cache interaction, and the `SourceMetadata` it returns is still file-scoped only, with correlation data kept outside the entry under its own dependency hash.

The representation decision is [DEC-030](../../../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md) and the reader's guide is [`doc_knowledge/code.graph.md`](../../../doc_knowledge/code.graph.md).
