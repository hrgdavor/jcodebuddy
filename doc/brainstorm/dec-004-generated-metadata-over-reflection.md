# DEC-004 Brainstorm: Generated metadata over runtime reflection

This document expands DEC-004 and explains why generated metadata is preferred to reflection-heavy runtime introspection.

For a deeper comparison against annotation processing and a more explicit performance rationale, see [Annotation processing vs generated metadata and materialized code](annotation-processing-vs-generated-metadata.md).

## Why this matters

The project depends on interface hierarchies, generic types, and field-origin semantics. Recomputing this structure through runtime reflection increases runtime coupling and can become brittle.

## Benefits of generated metadata

- Deterministic outputs tied to source state.
- Faster startup and lower runtime introspection overhead.
- Clear reviewable artifacts for tooling and debugging.
- Easier cross-language or non-JVM consumption via metadata JSON.
- Lower runtime cache-building and repeated structural discovery.
- Better support for generated direct adapters, serializers, and other explicit helper code that avoids reflection-heavy hot paths.

## Performance direction

Generated metadata is valuable not only because it replaces reflection as a lookup mechanism, but because it enables more explicit execution paths.
Once structure is extracted ahead of time, the project can generate direct code for common operations such as mapping, validation, serialization, and ordinal-based field access.

That helps by:
- reducing startup work
- reducing allocation pressure from reflective descriptors and caches
- creating simpler control flow for JVM and AOT optimizers
- reducing native-image metadata/configuration burden for dynamic features

## Reflection fallback scope

Reflection may remain useful for:
- exploratory tooling
- debugging diagnostics
- compatibility fallback when generated artifacts are missing

But reflection is not the canonical architecture path.

## Risks and mitigations

- Risk: metadata version drift.
Mitigation: explicit schema versioning and compatibility checks.

- Risk: generator complexity growth.
Mitigation: strict output contracts and regression snapshots.
