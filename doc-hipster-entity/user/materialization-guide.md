# Materialization Guide

`hipster-entity` is designed so you can start small and add layers later.
Each level is cumulative: every higher level includes the features of the levels below it.

## Level 0 — Meta (two paths)

Start with a view interface or a record and generate field metadata.
This gives you compile-time field definitions and `ViewMeta` support without a concrete implementation contract.

What you get:

- `PersonSummary_` metadata enum
- `ViewMeta` runtime support
- a compact contract foundation for utilities such as database access, serialization, and config readers

If you start from a record, you can migrate to Level 1 in place because the generated interface uses the same method naming pattern as the record components.

Use this when you want schema metadata and generic adapters.

## Level 1 — Record

At this level, the interface becomes the stable contract and the record provides the materialization.
Both record-first and interface-first paths converge here.

What you get:

- immutable data objects
- easy integration with serialization frameworks
- a simple, stable runtime representation

Use this when you need a real object and a clear interface contract.

## Level 2 — Writable

Add a write-capable interface for forms, DTOs, or patch payloads.
This enables proxy-backed setters or generated write helpers.

What you get:

- explicit write-target contracts
- separate read and write shapes
- safer update flows without requiring a concrete record

Use this when you need update semantics without a full builder.

## Level 3 — Builder

Add a generated concrete builder class for construction.
This is more performant than proxy-backed write and provides a dedicated object for building values.

What you get:

- fluent construction APIs
- typed setter methods
- a clear way to build view instances

Use this when you need performance and builder-style creation.

## Level 4 — BuilderTracked

Add a builder that records which fields were modified.
This is useful for partial updates, patch operations, and diagnostics.

What you get:

- field-level change tracking
- ability to distinguish default values from explicit writes
- support for strict or lenient patch semantics

Use this when an update payload needs to know what changed.

## Level 5 — BuilderAll

Generate both a regular builder and a tracking builder.
This gives you the flexibility to use a lightweight plain builder when you do not need tracking, and a tracked builder when you do.

What you get:

- both builder variants generated side-by-side
- choice between untracked and tracked construction
- maximum support for different write use cases

Use this when the same view needs both bulk creation and patch-aware updates.

## Choosing a level

| Need | Recommended level |
|---|---|
| Read-only view metadata | Level 0 |
| Immutable DTO or JSON shape | Level 1 |
| Form/update payloads | Level 2 |
| Fluent builder construction | Level 3 |
| Patch-aware change tracking | Level 4 |
| Both untracked and tracked builders | Level 5 |

## See also

- [Getting Started](getting-started.md)
- [Core Concepts](core-concepts.md)
