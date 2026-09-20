# User Patterns

This folder contains recipe-style guides for common `hipster-entity` usage patterns.

## Patterns

- [The Ordinal Array Contract](ordinal-array-contract.md) — the positional contract every
  materialization is built on: `values[field.ordinal()]`, rule S1, rule S4, the S5
  snapshot-vs-live rule, the `-1` probe, tombstoned fields, and the R1 append-only rule
  ([DEC-023](../../architecture/decisions/DEC-023.md)).
- [JDBC Row Adapter](jdbc-row-adapter.md) — the hand-written pattern for reading a `ResultSet` into
  a view by column name. The *generated* `<View>RowAdapter` / `<View>Binder` pair the page also
  describes is a **draft/exploration behind the tooling's opt-in `--adapters` flag**, not a supported
  generator.
- [Builder Usage](builder-usage.md) — the generated builder and tracking builder.
- [CRUD View Patterns](crud-views.md) — summary/details/update view layering.
- [Polymorphic View Patterns](polymorphic-views.md) — sealed hierarchies and discriminators.
- [Jackson Setup](jackson-setup.md) — Jackson 3 coordinates, the null policy, and the change-set
  shape.
- [Field-Enum Compaction](field-enum-compaction.md) — the ordinal migration procedure: draining
  the data, the two acknowledgements compaction demands, and how to verify and roll back
  ([DEC-025](../../architecture/decisions/DEC-025.md)).
- [Deep (Nested) Change Tracking](deep-change-tracking.md) — the shallow-vs-deep distinction
  (call-chain trace vs nested/deep tracking), the pull model, the collection
  add/remove/reorder semantics and their identity requirement, and the deep JSON patch
  ([DEC-024](../../architecture/decisions/DEC-024.md)).

## Guides

- [Getting started in a new project](../getting-started-new-project.md) — the end-to-end workflow
  from dependency to generated builder to JSON.
- [Getting Started](../getting-started.md) — concepts and the recorded build command.
- [Materialization Guide](../materialization-guide.md) — what each `GenLevel` gives you.
- [Core Concepts](../core-concepts.md)
- [FAQ](../faq.md)
