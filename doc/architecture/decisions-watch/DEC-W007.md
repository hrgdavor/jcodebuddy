# DEC-W007: Unified source metadata model for generators and runtime

- Status: Proposed
- Date: 2026-07-24
- Owners: project
- Related docs: [DEC-W005: Code generation interface contract](DEC-W005.md), [DEC-W006: Metadata cache with per-hash invalidation](DEC-W006.md), [hipster-entity-tooling](../README.md), [hipster-entity-api](../README.md), [project-automation](../README.md)
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

### Metadata hierarchy

Each `SourceMetadata` instance represents one compilation unit and contains:

| Level                    | Descriptor          | Key content                                                                                    |
| ------------------------ | ------------------- | ---------------------------------------------------------------------------------------------- |
| File                     | `FileMeta`          | package, module, file-level annotations, declared types                                        |
| Import section           | `ImportSectionMeta` | imports (static, wildcard, on-demand)                                                          |
| Class / interface / enum | `TypeMeta`          | modifiers, annotations, type parameters, superclass, interfaces, nested types, fields, methods |
| Field                    | `FieldMeta`         | modifiers, annotations, type descriptor, initializer                                           |
| Method                   | `MethodMeta`        | modifiers, annotations, return type descriptor, type parameters, parameters, body summary      |
| Parameter                | `ParameterMeta`     | modifiers, annotations, type descriptor, varargs, index                                        |

All type references use the `TypeDescriptor` record (already present in `hipster-entity-tooling.meta`) extended with type-use annotation support.

### Modifier and annotation coverage

Every descriptor that can carry Java modifiers includes a `Set<Modifier>` (public, protected, private, abstract, static, final, sealed, non-sealed, etc.) and a list of `AnnotationMeta` (name + member values). This is mandatory at class, field, method, and parameter levels.

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

### Relationship to existing models

- `EntityMeta`, `ViewMeta`, `EntityFieldMeta`, and `Property` are **projections** built on top of `SourceMetadata` by `EntityMetadataGenerator`. They are not replaced; they are reimplemented as adapters that walk the unified tree.
- The JSON output of `EntityMetadataGenerator` remains byte-for-byte compatible with existing consumers. The internal parsing logic changes from ad-hoc JavaParser walks to tree construction followed by projection.
- `TypeDescriptor` moves from `hipster-entity-tooling.meta` to a shared package (`hipster-entity-api.meta`) so that both runtime and generator code can reference it without a tooling dependency.

### Cache integration

The unified model is the unit stored in the metadata cache (DEC-W006). The cache key is the file wayhash. The value is a `SourceMetadata` tree. Generators and runtime code read from the cache through typed accessors rather than deserializing the full tree when they need only a subset.

### Runtime analysis and querying

The `SourceMetadata` tree is not only a source for generators; it is also a substrate for runtime analysis of the project's code structure. Several complementary approaches are under consideration:

- **Lucene / full-text index** — index method names, field names, annotations, and type signatures to support fast textual queries (e.g., "find all classes with a method named `handle`", "find all classes annotated with `@Entity`"). Lucene is a natural fit because the metadata is already tree-structured and can be flattened into searchable documents.
- **Knowledge graph** — model types, methods, fields, and their cross-module references as a graph. Nodes represent classes/methods/fields; edges represent `extends`, `implements`, `calls`, `returns`, `annotates`, and cross-module `links`. A graph database (e.g., Neo4j) or an in-memory graph (e.g., TinkerPop) enables structural queries: "trace all callers of `UserService.findById`", "find all fields of type `java.time.LocalDate` across modules", "detect cycles in the interface hierarchy".
- **Embeddings and vector search** — generate embedding vectors for method bodies, class names, or type signatures and store them in a vector index (e.g., HNSW via Lucene or a dedicated vector DB). This enables semantic queries: "find methods semantically similar to `validateAndPersist`", "find classes that behave like a repository", "suggest boilerplate generators by example". Embeddings can be produced offline (e.g., by an LLM) or at generation time.
- **LLM analysis** — feed `SourceMetadata` trees (or projections thereof) to an LLM for code-summarization, documentation generation, architectural review, or test-generation prompts. Because the metadata is structured and wayhash-keyed, LLM analysis can be cached and invalidated together with the source file.

These approaches are not mutually exclusive. A practical stack might combine a Lucene index for fast textual lookup, a knowledge graph for structural reasoning, and an LLM layer for semantic synthesis. The metadata cache (DEC-W006) acts as the single source of truth; the analysis layer indexes or consumes from the cache rather than re-parsing source.

## Alternatives considered

- **Separate generator-only and runtime-only models with a one-time conversion step** — rejected because the conversion step becomes a maintenance burden and source of drift; any schema change must be duplicated.
- **Use Java reflection as the runtime model and JavaParser AST as the generator model** — rejected because reflection is unavailable at generation time for uncompiled source, and AST shapes are too low-level for runtime consumers.
- **JSON as the interchange format between generator and runtime** — rejected because JSON loses type safety and requires schema versioning; a Java record model is simpler and type-safe across the same JVM.
- **Keep the current ad-hoc `EntityMetadataGenerator` parsing and add method metadata only** — rejected because it would continue the pattern of special-casing entity interfaces while leaving plain classes and other generators without method/parameter metadata.

## Consequences

- Positive: A single source of truth for source metadata; generators and runtime code share the same parsed representation.
- Positive: Method, parameter, modifier, and annotation metadata is available to all generators without per-generator JavaParser boilerplate.
- Positive: The metadata cache (DEC-W006) stores one `SourceMetadata` tree per wayhash, which all consumers reuse.
- Positive: Runtime `TypeResolver` gains method and modifier visibility without breaking existing field-only consumers.
- Negative: The unified model is larger than the current entity-only model; serialization and cache size must be monitored.
- Generators continue to use JavaParser AST for source manipulation, while the metadata model provides a rich structural overview for decision-making. Utility code must exist to map metadata items back to AST positions when deeper inspection is required.
- Follow-up: Define the serialization format for `SourceMetadata`. **Apache Fury/Fory** is the preferred candidate for serialization and deserialization of metadata because it provides zero-copy, schema-evolution-safe, cross-language binary serialization with low overhead. Protobuf and flatbuffers remain fallback options if Fury integration is not feasible.
- Follow-up: Add cache hit-rate and eviction-rate metrics to the agent daemon observability layer.
- Follow-up: Migrate `EntityMetadataGenerator` to produce `EntityMeta`/`ViewMeta` as projections from `SourceMetadata` and validate JSON compatibility.

## Out of scope

- Remote or distributed cache — the initial scope is local disk, per-module, per-JVM.
- Cache encryption or access control — this is a developer-time artifact, not a runtime dependency.
- Hot-swap of cache format versions — format changes require full cache invalidation for the affected module.
- Integration with build-system caches (Maven local repository, Gradle build cache) — those are complementary but separate concerns.
- Full Java AST equivalence — `SourceMetadata` is a semantic model, not a 1:1 AST mirror; syntax-level details (comments, formatting) are intentionally omitted.
- Runtime analysis index (Lucene, knowledge graph, vector search, LLM) — those are complementary layers built on top of the metadata cache; the initial scope covers only metadata production and storage, not querying or analysis infrastructure.

## Acceptance criteria

- `SourceMetadata` MUST represent file, import section, class, field, method, and parameter levels with modifiers, annotations, and type descriptors.
- `SourceMetadata` MUST be serializable and deserializable with Apache Fury/Fory as the primary format, with protobuf/flatbuffers as fallback.
- `TypeDescriptor` MUST support parameterized types, arrays, primitives, and type-use annotations.
- `RuntimeTypeView` MUST expose fields, fieldTypes, methods, and modifiers in a form compatible with existing `TypeDefinition` consumers.
- `TypeResolver.resolve()` MUST return `RuntimeTypeView` for known types and `null` for unknown types.
- Generators MUST be able to navigate `SourceMetadata` to access class, method, and parameter metadata without parsing Java source directly.
- The metadata cache MUST store and retrieve `SourceMetadata` keyed by file wayhash.
- Existing `EntityMetadataGenerator` JSON output MUST remain byte-for-byte compatible after migration to the unified model.
