# DEC-W007: Unified source metadata model for generators and runtime

- Status: Proposed
- Date: 2026-07-24
- Owners: project
- Related docs: [DEC-W005: Code generation interface contract](DEC-W005.md), [DEC-W006: Metadata cache with per-hash invalidation](DEC-W006.md), [DEC-W008: Metadata parsing without cache](DEC-W008.md), [DEC-W009: In-RAM metadata relations storage](DEC-W009.md), [hipster-entity-tooling](../README.md), [hipster-entity-api](../README.md), [project-automation](../README.md)
- Supersedes: -
- Superseded by: -

## Context

The hipster-entity subsystem already collects a subset of source metadata: entity interfaces are parsed, fields are extracted with type descriptors, and view properties are merged. The `EntityMetadataGenerator` produces JSON containing `EntityMeta`, `ViewMeta`, `EntityFieldMeta`, and `TypeDescriptor`. At runtime, `TypeDefinition` exposes `qualifiedName`, `simpleName`, `fields`, and `fieldTypes` through `TypeResolver`.

This coverage is sufficient for entity-field mapping, but it is too narrow for general code generation. Generators need:

- **Class-level metadata**: modifiers, annotations, type parameters, superclass, implemented interfaces, nested type declarations.
- **Method-level metadata**: name, return type, modifiers, annotations, generic signature, body-level summary, parameters.
- **Parameter-level metadata**: type, modifiers, annotations, varargs flag.
- **Import section metadata**: static vs non-static, on-demand vs single-type, wildcard usage.
- **File-level metadata**: package declaration, module declaration, file-level annotations.

Meanwhile, runtime code that performs generic operations on entities (e.g., field enumeration, type-safe mapping, validation) needs a stable, typed view of the same source structure. Today the runtime model (`TypeDefinition`) and the generator model (`EntityMeta`/`ViewMeta`) are separate shapes with overlapping but inconsistent fields. This divergence creates maintenance burden and makes it impossible to share a single parser output between generation-time and runtime consumers.

A unified source metadata model is needed that:

- captures the full Java type structure at all resolution levels,
- is usable by both generators (who need the richest possible view) and runtime code (who need a stable, minimal subset),
- can be produced once from a source file wayhash and reused across multiple consumers,
- integrates with the metadata cache from DEC-W006 so that metadata nodes are stored and invalidated together.

## Decision

Introduce a **unified source metadata model** in `hipster-entity-tooling` that describes a parsed Java source file as a tree of typed descriptors. The model is generated once per file wayhash, stored in the metadata cache, and exposed through two projection interfaces:

1. **`SourceMetadata`** — the complete tree, used by generators and the cache layer.
2. **`RuntimeTypeView`** — a stable, minimal subset used by runtime `TypeResolver` and entity frameworks.

Both projections are derived from the same `SourceMetadata` instance, so they are always consistent.

### Cache entry shape

Each source file produces one `CacheEntry` record keyed by wayhash. The `SourceMetadata` tree inside a `CacheEntry` MUST contain only information that can be derived from parsing the file's own source bytes alone. Information that requires reading other files (e.g., resolved type definitions from imported classes, annotation processor inventories, RPC method registries) MUST NOT be stored in `SourceMetadata`.

```java
public record CacheEntry(
    byte[] hash,
    String fullClassName,
    String relativePath,
    SourceMetadata metadata,  // nullable: null during inventory phase; file-local only
    byte[] dependencyHash     // hash of all files whose metadata contributed to correlation data; null if no correlation data exists
) {}
```

`CacheEntry` lives in `hipster-entity-tooling.meta` alongside `EntityMeta`, `ViewMeta`, etc. The cache stores `CacheEntry` records keyed by wayhash. The `metadata` field is nullable: it is `null` during the inventory phase and populated later during enrichment.

### Two-phase metadata population

The cache supports two-phase population to avoid blocking the initial project-state snapshot on expensive parsing:

1. **Inventory phase (single-threaded, fast):**
   - Scan all source files in the module.
   - Compute wayhash for each file.
   - Write `CacheEntry(hash, fullClassName, relativePath, metadata=null, dependencyHash=null)` to cache.
   - Update module index.
   - This phase must not invoke JavaParser or any expensive metadata generation.

2. **Enrichment phase (multi-threaded):**
   - Iterate over cache entries with `metadata == null`.
   - Distribute entries across worker threads.
   - Each thread parses source with JavaParser, builds `SourceMetadata` tree, and writes back to the existing `CacheEntry` with `dependencyHash=null`.
   - Index entries are updated atomically after metadata is written.

**Contract for consumers:** All cache read APIs must handle `metadata == null` gracefully:
- `get(hash)` returns a `CacheEntry` with `metadata == null`.
- `get(hash, TypeMeta.class, "class:com.example.Foo")` returns `null` if `metadata` is absent.
- Generators that require metadata must check for null and either skip or trigger enrichment.

### Metadata hierarchy

Each `SourceMetadata` instance represents one compilation unit and contains:

| Level                    | Descriptor          | Key content                                                                                    |
| ------------------------ | ------------------- | ---------------------------------------------------------------------------------------------- |
| File                     | `FileMeta`          | package, module, file-level annotations, declared types                                        |
| Import section           | `ImportSectionMeta` | imports (static, wildcard, on-demand)                                                          |
| Class / interface / enum | `TypeMeta`          | modifiers, annotations, type parameters, superclass, interfaces, nested types, fields, methods |
| Field                    | `FieldMeta`         | modifiers, annotations, type descriptor, initializer                                           |
| Record component         | `RecordComponentMeta` | name, type descriptor, annotations (sibling of `FieldMeta`)                                  |
| Method                   | `MethodMeta`        | modifiers, annotations, return type descriptor, type parameters, parameters, called method signatures, range |
| Parameter                | `ParameterMeta`     | modifiers, annotations, type descriptor, varargs, index                                        |

All type references use the `TypeDescriptor` record (moved to `hipster-entity-api.meta`) extended with type-use annotation support. Every descriptor that can carry Java modifiers includes a `Set<Modifier>` and a list of `AnnotationMeta`. Modern Java features are supported explicitly:

- `List<String> permits` on `TypeMeta` for sealed class hierarchies.
- `PatternMeta` for switch-expression analysis.
- Type-use annotations on `TypeDescriptor` via `List<AnnotationMeta> annotations`.

### Source-position Range

Every descriptor (`FileMeta`, `ImportSectionMeta`, `TypeMeta`, `FieldMeta`, `RecordComponentMeta`, `MethodMeta`, `ParameterMeta`) includes a source-position range for mapping metadata back to AST positions:

```java
public record Range(int startLine, int endLine, int startColumn, int endColumn) {}
```

Line/column pairs are used because JavaParser natively provides line numbers, and line/column is standard for error reporting and IDE integration.

### Modifier and annotation coverage

Every descriptor carries `Set<Modifier>` (public, protected, private, abstract, static, final, sealed, non-sealed, etc.) and a list of `AnnotationMeta` (name + member values):

```java
public record AnnotationMeta(String qualifiedName, List<MemberValue> values) {}
public record MemberValue(String name, String value) {}
```

`value` is the raw member value as a string for schema stability. The parsing layer resolves primitives, arrays, and nested annotations into this flat form.

### Generic / deep typing

`TypeDescriptor` is the universal type representation:

```java
public record TypeDescriptor(
    String typeName,
    List<TypeDescriptor> typeArguments,
    boolean array,
    boolean primitive,
    List<AnnotationMeta> annotations  // type-use annotations
) {
    public boolean isParameterized() { ... }
}
```

Generators get the full tree. Runtime code gets a flattened view where parameterized types are stringified for backward compatibility with `TypeDefinition.fieldTypes`.

### Runtime projection

`RuntimeTypeView` is the runtime-facing subset. It exposes:

- `qualifiedName()`, `simpleName()`
- `fields()` — list of field names
- `fieldTypes()` — map of field name to stringified type
- `methods()` — list of method signatures (name + parameter types, no bodies)
- `modifiers()` — class-level modifiers

`TypeResolver` is updated to return `RuntimeTypeView` instead of the current `TypeDefinition`. The old `TypeDefinition` record is marked deprecated and removed in a follow-up release.

### Generator projection

Generators receive the full `SourceMetadata` through `CodeContext`:

```java
public interface CodeContext {
    ...
    SourceMetadata getSourceMetadata();
}
```

Generators can navigate the tree directly:

```java
SourceMetadata meta = context.getSourceMetadata();
TypeMeta clazz = meta.findType("com.example.Foo");
for (MethodMeta method : clazz.methods()) {
    if (method.name().equals("bar")) {
        TypeDescriptor returnType = method.returnType();
        List<ParameterMeta> params = method.parameters();
    }
}
```

### MetadataTypeResolver bridge

`MetadataTypeResolver` bridges the existing `TypeResolver` API with the new metadata cache. It lives in `project-automation` as a sibling to `TypeResolver`:

```java
// In project-automation
public interface MetadataTypeResolver extends TypeResolver {
    void index(SourceMetadata metadata);
}
```

It indexes all `TypeMeta` instances from cached `SourceMetadata` trees and returns `RuntimeTypeView` on `resolve()`. The existing `TypeDefinition`-based `TypeResolver` is deprecated in favor of this.

`project-automation` depends on both `hipster-entity-api` and `hipster-entity-tooling`. `hipster-entity-api` must not depend on `hipster-entity-tooling` to avoid circular dependencies.

### Relationship to existing models

- `EntityMeta`, `ViewMeta`, `EntityFieldMeta`, and `Property` are **projections** built on top of `SourceMetadata` by `EntityMetadataGenerator`. They are not replaced; they are reimplemented as adapters that walk the unified tree.
- The JSON output of `EntityMetadataGenerator` remains byte-for-byte compatible with existing consumers. The internal parsing logic changes from ad-hoc JavaParser walks to tree construction followed by projection.
- `TypeDescriptor` moves from `hipster-entity-tooling.meta` to `hipster-entity-api.meta` so that both runtime and generator code can reference it without a tooling dependency.

### Cache integration

The unified model is the unit stored in the metadata cache (DEC-W006). The cache key is the file wayhash. The value is a `CacheEntry` containing the full `SourceMetadata` tree plus `fullClassName` and `relativePath`. Generators and runtime code read from the cache through typed accessors rather than deserializing the full tree when they need only a subset.

Typed accessors are defined as:

```java
<T extends MetadataNode> T get(byte[] hash, Class<T> type, String path);
SourceMetadata get(byte[] hash);
```

`MetadataNode` is a marker interface implemented by all descriptors (`FileMeta`, `TypeMeta`, etc.). The cache returns the exact descriptor type, not a generic `Object` or `JsonNode`.

`get(hash, level, path)` returns the node at the specified path within the entry, or `null` if `metadata` is absent or the path is absent. `put(hash, level, path, value)` writes into the tree at the specified path within the entry, creating the tree if absent.

### Correlation metadata stored outside cache entries

`SourceMetadata` contains only information derivable from the file's own source bytes. Any metadata that requires reading other files — annotation collector indexes, RPC method inventories, cross-file dependency graphs — MUST be stored as correlation metadata outside the `CacheEntry`.

Correlation metadata is keyed by a `dependencyHash`, which is a wayhash computed from the sorted concatenation of all constituent file wayhashes. When any constituent file changes, its wayhash changes, the `dependencyHash` changes, and the correlation metadata is recognized as stale and recalculated.

Correlation metadata MUST be calculated on the fly and is unlikely to be cached persistently. It MAY be held in an in-memory index with fast rebuild capability. The in-memory index SHOULD use an arena allocator to minimize heap allocations and allow efficient invalidation of entire subtrees when a file's wayhash changes.

### Java module vs Maven module

`FileMeta` module fields are split to avoid conflating Java 9+ module declarations with Maven coordinates:

- `String javaModule` — the `module` name from `module-info.java`, or `null` if not present.
- `String mavenModule` — the Maven coordinate (`groupId:artifactId`), injected by the watcher layer, not parsed from source.

### Serialization schema versioning

Apache Fury/Fory is the preferred serialization format, with protobuf and flatbuffers as fallbacks. Schema evolution is required:

```java
public interface MetadataSerializer {
    byte[] serialize(SourceMetadata metadata);
    SourceMetadata deserialize(byte[] bytes, int expectedVersion) throws MetadataSchemaException;
}
```

`int schemaVersion` on `SourceMetadata` enables fallback for incompatible on-disk formats. Fury's built-in schema evolution is primary; the version field enables graceful degradation.

### Migration path for EntityMetadataGenerator

The migration from ad-hoc JavaParser walks to `SourceMetadata` projection occurs in four phases:

1. **Dual-write phase:** `EntityMetadataGenerator` writes both the legacy JSON and the new `SourceMetadata` tree to the cache. Existing JSON consumers are unaffected.
2. **Validation phase:** Run both paths in parallel and assert byte-for-byte JSON compatibility.
3. **Cutover phase:** Once validated, `EntityMetadataGenerator` reads `SourceMetadata` from cache and projects to JSON. Direct JavaParser walks are removed.
4. **Cleanup phase:** Deprecate and remove legacy JSON output paths.

### Cross-module references

References to types, fields, or methods in other modules are stored as qualified links that include the producing file's relative path:

```
<moduleKey>::<relativePath>#<memberPath>@<hash>
```

- `moduleKey` identifies the producing Maven module (e.g., `com.example:core`).
- `relativePath` is the path from the project root to the source file (e.g., `core/src/main/java/com/example/Foo.java`).
- `memberPath` is the hierarchical path within the file. Format:
  - Class: `class:<simpleName>`
  - Field: `class:<simpleName>.field:<name>`
  - Method: `class:<simpleName>.method:<name>(<paramTypeList>)`
  - Parameter: `class:<simpleName>.method:<name>(<paramTypeList>).param:<index>`
- `hash` is the **wayhash** of the producing file at the time the reference was recorded.

Including `relativePath` disambiguates references because class names are not unique within a module. It also allows stale-link detection even when class names collide.

### Per-module index for searchability

Each module maintains a single `index.fury` file that maps:
- `relativePath` → `hash`
- `fullClassName` → `hash` (stored as a list to handle collisions)
- `hash` → `CacheEntry`

The index is updated atomically when entries are written or invalidated. Cleanup uses the index to determine which hashes are absent from the current project state.

### Data flow (cache lifecycle)

#### Inventory phase (fast, single-threaded)

```
Scan project source files
       |
       v
For each file:
  Compute wayhash of source bytes
  Build CacheEntry(hash, fullClassName, relativePath, metadata=null)
  Write CacheEntry to cache directory: <module>/.cache/<hash>.fury
  Update module index: relativePath -> hash, fullClassName -> hash, hash -> CacheEntry
```

#### Enrichment phase (parallel, multi-threaded)

```
Iterate over cache entries with metadata == null
       |
       v
Distribute entries across worker threads
       |
       v
Each thread:
  Read source bytes for assigned entries
  Parse source with JavaParser
  Build SourceMetadata tree
  Write back to existing CacheEntry (atomic update)
  Update module index atomically
```

#### Read path (consumer)

```
Generator reads CacheEntry via MetadataCache
  - get(hash) -> CacheEntry (metadata may be null)
  - If metadata == null:
      - Return empty/default result
      - OR trigger async enrichment
      - OR block until enriched (policy decision)
  - If metadata != null:
      - get(hash, TypeMeta.class, "class:com.example.Foo") -> specific TypeMeta
      - link(fromHash, toModule, toRelativePath, toMemberPath) -> record cross-module ref
```

#### Cleanup (on full recompile / overflow)

```
Current project state (set of relativePaths)
       |
       v
For each hash in module index:
  - If hash not in current state AND older than retention window -> evict
  - If hash not in current state but within retention window -> keep (branch-switch reuse)
  - If hash in current state -> keep
```

### Module ownership and dependencies

| Component | Module | Depends on |
|-----------|--------|------------|
| `CacheEntry`, `SourceMetadata`, descriptors | `hipster-entity-tooling` | `hipster-entity-api` |
| `TypeDescriptor`, `AnnotationMeta`, `Range` | `hipster-entity-api` | - |
| `MetadataTypeResolver`, `MetadataSerializer`, `MetadataCache` interface, `MetadataNode` | `project-automation` | `hipster-entity-api` |
| `MetadataCache` implementation, `index.fury` handling | `project-automation` | `hipster-entity-tooling` |
| `EntityMetadataGenerator` migration | `hipster-entity-tooling` | `hipster-entity-api`, `project-automation` |

`hipster-entity-api` must not depend on `hipster-entity-tooling`. `project-automation` depends on both modules and is the correct owner for `MetadataTypeResolver`.

### MethodMeta body summary semantics

"Body summary" is replaced with `List<String> calledMethodSignatures` — a list of method signatures called from the method body. This is deterministic, hashable, and useful for dependency tracking. Only direct calls from the method body are included; inherited or interface method calls are excluded because they require full hierarchy resolution and are less safe for caching.

### Alternatives considered

- **Separate generator-only and runtime-only models with a one-time conversion step** — rejected because the conversion step becomes a maintenance burden and source of drift; any schema change must be duplicated.
- **Use Java reflection as the runtime model and JavaParser AST as the generator model** — rejected because reflection is unavailable at generation time for uncompiled source, and AST shapes are too low-level for runtime consumers.
- **JSON as the interchange format between generator and runtime** — rejected because JSON loses type safety and requires schema versioning; a Java record model is simpler and type-safe across the same JVM.
- **Keep the current ad-hoc `EntityMetadataGenerator` parsing and add method metadata only** — rejected because it would continue the pattern of special-casing entity interfaces while leaving plain classes and other generators without method/parameter metadata.

### Consequences

- Positive: A single source of truth for source metadata; generators and runtime code share the same parsed representation.
- Positive: Method, parameter, modifier, and annotation metadata is available to all generators without per-generator JavaParser boilerplate.
- Positive: The metadata cache (DEC-W006) stores one `CacheEntry` per wayhash, which all consumers reuse.
- Positive: Runtime `TypeResolver` gains method and modifier visibility without breaking existing field-only consumers.
- Positive: Two-phase population enables fast project-state snapshots, parallel metadata generation, and graceful degradation when metadata is not yet available.
- Negative: The unified model is larger than the current entity-only model; serialization and cache size must be monitored.
- Negative: Cross-module links require `relativePath` disambiguation, adding path length to each link.
- Negative: The cache must handle `metadata == null` gracefully in all read paths.
- Generators continue to use JavaParser AST for source manipulation, while the metadata model provides a rich structural overview for decision-making. Utility code must exist to map metadata items back to AST positions when deeper inspection is required.
- Follow-up: Define the serialization format for `SourceMetadata`. **Apache Fury/Fory** is the preferred candidate for serialization and deserialization of metadata because it provides zero-copy, schema-evolution-safe, cross-language binary serialization with low overhead. Protobuf and flatbuffers remain fallback options if Fury integration is not feasible.
- Follow-up: Add cache hit-rate and eviction-rate metrics to the agent daemon observability layer.
- Follow-up: Migrate `EntityMetadataGenerator` to produce `EntityMeta`/`ViewMeta` as projections from `SourceMetadata` and validate JSON compatibility.

### Future extensions

The `SourceMetadata` tree is not only a source for generators; it is also a substrate for runtime analysis of the project's code structure. Several complementary approaches are under consideration:

- **Lucene / full-text index** — index method names, field names, annotations, and type signatures to support fast textual queries.
- **Knowledge graph** — model types, methods, fields, and their cross-module references as a graph for structural queries.
- **Embeddings and vector search** — generate embedding vectors for method bodies and class names for semantic queries.
- **LLM analysis** — feed `SourceMetadata` trees to an LLM for code-summarization, documentation generation, and test-generation prompts.

These approaches depend on the metadata model defined here but are not part of this decision.

### Out of scope

- Remote or distributed cache — the initial scope is local disk, per-module, per-JVM.
- Cache encryption or access control — this is a developer-time artifact, not a runtime dependency.
- Hot-swap of cache format versions — format changes require full cache invalidation for the affected module.
- Integration with build-system caches (Maven local repository, Gradle build cache) — those are complementary but separate concerns.
- Full Java AST equivalence — `SourceMetadata` is a semantic model, not a 1:1 AST mirror; syntax-level details (comments, formatting) are intentionally omitted.
- Runtime analysis index (Lucene, knowledge graph, vector search, LLM) — those are complementary layers built on top of the metadata cache; the initial scope covers only metadata production and storage, not querying or analysis infrastructure.

### Acceptance criteria

- `SourceMetadata` MUST represent file, import section, class, field, method, record component, and parameter levels with modifiers, annotations, type descriptors, and source-position ranges.
- `SourceMetadata` MUST be serializable and deserializable with Apache Fury/Fory as the primary format, with protobuf/flatbuffers as fallback.
- `TypeDescriptor` MUST support parameterized types, arrays, primitives, and type-use annotations.
- `CacheEntry` MUST store `hash`, `fullClassName`, `relativePath`, and nullable `metadata` keyed by wayhash.
- The cache MUST support two-phase population: inventory (fast, no parsing) followed by enrichment (parallel parsing).
- Cross-module references MUST use `<moduleKey>::<relativePath>#<memberPath>@<hash>` format.
- Each module MUST maintain a per-module index mapping `relativePath` → `hash`, `fullClassName` → `hash`, `hash` → `CacheEntry`.
- `RuntimeTypeView` MUST expose fields, fieldTypes, methods, and modifiers in a form compatible with existing `TypeDefinition` consumers.
- `TypeResolver.resolve()` MUST return `RuntimeTypeView` for known types and `null` for unknown types.
- `CodeContext` MUST provide `getSourceMetadata()` returning the `SourceMetadata` for the current file context.
- Generators MUST be able to navigate `SourceMetadata` to access class, method, and parameter metadata without parsing Java source directly.
- The metadata cache MUST store and retrieve `SourceMetadata` keyed by file wayhash.
- Existing `EntityMetadataGenerator` JSON output MUST remain byte-for-byte compatible after migration to the unified model.

### Edge cases and failure modes

| Edge case | Behavior |
|-----------|----------|
| Duplicate class names in same module | Index keyed by `fullClassName` must handle collisions. The index stores `fullClassName` → list of hashes; lookup requires `relativePath` disambiguation. |
| File deleted then restored within retention window | Cache hit on restored file if wayhash is still present. Cleanup does not evict. |
| Corrupted `index.fury` | Rebuild index by scanning all `<hash>.fury` files in the module cache directory. Recovery mode, not happy path. |
| Concurrent cache writes | Per-module cache directory and index. Writers use atomic rename. Index updates are single-writer per module (the watcher daemon). Enrichment threads write to distinct `CacheEntry` files, so they do not conflict. |
| Non-Java resources | `CacheEntry.fullClassName` is `null`. `SourceMetadata` is empty or contains only `FileMeta` with package/module info. Optional future extension. |
| Java 9+ `module-info.java` | Parsed as a special `TypeMeta` with `kind=MODULE`. `javaModule` is set on `FileMeta`. |
| Schema version mismatch | `MetadataSerializer.deserialize` checks `schemaVersion`. If incompatible, throw `MetadataSchemaException` and fall back to re-parsing source. |
| Metadata absent (`metadata == null`) | All cache read APIs return `null` or empty results instead of throwing. Generators that require metadata must check for null and either skip, return defaults, or trigger enrichment. |
| Enrichment thread failure | If a worker thread fails to parse a file, the `CacheEntry` remains with `metadata == null`. The enrichment scheduler retries failed entries in the next cycle. |
| Stale index after enrichment | After a thread writes enriched metadata, the index must be updated atomically. If the index update fails, the entry exists on disk but is unreachable by lookup; cleanup on the next full recompile will either find it via directory scan or leave it for manual recovery. |

### Validation plan

1. **Unit tests:** `CacheEntry` serialization/deserialization round-trip with Fury preserves all fields including `hash`, `fullClassName`, `relativePath`, and nullable `metadata`.
2. **Index tests:** Lookup by `relativePath`, `fullClassName`, and `hash` returns the correct `CacheEntry`. Collision test: two classes with same simple name but different paths resolve to different entries.
3. **Cross-module link tests:** A link in module A pointing to module B `relativePath` is recognized as stale when module B's file wayhash changes.
4. **Cleanup tests:** Files removed from project state are retained within retention window and evicted after it. Restored files within retention window hit cache.
5. **Migration tests:** `EntityMetadataGenerator` dual-write produces identical JSON to legacy path. After cutover, JSON output is byte-for-byte compatible.
6. **TypeResolver tests:** `MetadataTypeResolver.index()` populates correctly. `resolve("com.example.Foo")` returns `RuntimeTypeView` with fields, methods, and modifiers.
7. **Two-phase population tests:**
   - After inventory phase, all source files have `CacheEntry` records with `metadata == null`.
   - Index contains all entries; lookups by path and classname work before enrichment.
   - After enrichment phase, all `CacheEntry` records have non-null `metadata`.
   - Partial enrichment (some threads fail) leaves only failed entries with `metadata == null`; all others are populated.
8. **Concurrent enrichment tests:** Multiple threads enrich distinct entries simultaneously. No data corruption or lost updates. Index updates are atomic.
9. **Absent-metadata consumer tests:** Generators that call `get(hash, TypeMeta.class, path)` on an unenriched entry receive `null` and handle it without throwing.
