# Field Boilerplate Generator Functional Specification

## Purpose

The tooling generator is responsible for generating Java boilerplate code for entity view field metadata and view metadata support.
It must produce output that can be compiled and used directly by the rest of the project, including:

- generated field enums implementing `FieldDef`
- generated property enums for view-level metadata
- optional `ViewMeta` instances for generated metadata classes
- correct imports and package declarations
- correct type expressions for primitives, arrays, and generics
- support for polymorphic discriminator metadata on sealed view roots

## Scope

This specification covers two generator implementations:

1. `FieldBoilerplateGenerator`
2. `EntityMetadataGenerator`

## FieldBoilerplateGenerator

### Behavior

The generator must produce a Java enum source file with the following characteristics:

- correct package declaration when `packageName` is provided
- `import` statements for required runtime and API classes
- enum definition named using `enumName`
- enum constants matching each property name in the same casing used by the source model
- a constructor accepting the Java type expression for each property
- `FieldDef` implementation when generating field metadata enums
- `TypeUtils`-based generic type expressions when generating property enums
- a `forName(String)` switch factory for reverse lookup
- a `NAME_MAPPER` field when encoding a field enum
- optional `public static final ViewMeta<...> META` when `metaCreatorBody` is provided
- discriminator field reference, discriminator value, and permitted subtype array when generating polymorphic metadata

### Builder API

The builder must support all of the following fluent operations:

- `withEnumTypeName(String)`
- `withPropertyEnumMode()`
- `withMetaCreatorBody(String)`
- `withDiscriminatorField(String)`
- `withDiscriminatorValue(String)`
- `withPermittedSubtypeClassNames(String...)`
- `withAdditionalImports(String...)`
- `generate(Path)`

### Acceptance Criteria

- given a set of simple properties, the generator writes a compile-ready enum file
- given generic property types, the generator writes the expected `TypeUtils.parameterizedType(...)` expression
- given primitive and array types, the generator writes proper boxed and reflection-based class literals
- given view metadata parameters, the generator writes a `ViewMeta` constant with discriminator and subtype metadata

## EntityMetadataGenerator

### Behavior

The generator must parse one or more Java source files and produce:

- one metadata JSON file per entity marker root
- one generated property enum source file per detected view interface
- entity-wide field summaries with per-view type tracking
- view metadata values derived from interface inheritance and annotations

### Input Requirements

The generator must accept either:

- a source root directory containing Java files
- a single Java source file path, in which case it must derive the source root from the enclosing `src/main/java` or `src/test/java` path

### Parsing Rules

- only interfaces are considered for view metadata generation
- marker entities are interfaces that extend `EntityBase<?>`
- view interfaces that extend a marker entity are treated as views
- inherited properties from extended interfaces must be merged in declaration order

### Output Requirements

For each detected entity marker root:

- generate `<EntityName>.metadata.json`
- generate `ViewProperty` enum sources under the same package as the view
- preserve `lineNumber` metadata for properties and views
- resolve view names and types exactly as declared in the source

### Acceptance Criteria

- a simple generated entity package produces metadata JSON that contains entity and view names
- generated property enums exist for each view and include expected fields
- real example sources such as `paymentMethod` generate metadata and property enums for the concrete subviews

## Testing Requirements

Tests must cover the following generator functions and behaviors:

- `FieldBoilerplateGenerator.builder(...)` and `generate(...)`
- enum generation for field metadata, property metadata, and `ViewMeta` metadata
- type expression generation for primitives, arrays, and generics
- source generation from a real example module (`paymentMethod` views)
- generation of metadata JSON and output files for example views

## Non-Goals

- full Java compilation of generated source is not required by the generator itself
- exhaustive view parsing for all project conventions is outside this document; only the current example and builder behaviors are in scope
