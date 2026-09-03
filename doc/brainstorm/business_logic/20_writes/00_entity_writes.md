# Entity Writes

> Up: [20_writes/README.md](README.md). Back to [business_logic/README.md](../../README.md).

This file covers the "side-effect information" type used for entity
mutations inside the unit, and the rules that make those mutations
**controllable from code** rather than left to the database. Only
**type-1 (`@CoreChange`) and type-2 (`@CoreChangeOnChange`) steps**
populate these — see
[`10_concept/15_operation_types.md`](10_concept/15_operation_types.md).

## `EntityWrite<E>` — the marker for entity effects

To **own identifiers inside code** rather than relying on the database
to generate them, entity writes carry an explicit operation marker.

```java
public sealed interface EntityWrite<E>
    permits EntityInsert<E>, EntityUpdate<E>, EntityDelete<E> {}

public record EntityInsert<E>(long id, E entity) implements EntityWrite<E> {}
public record EntityUpdate<E>(long id, E entity) implements EntityWrite<E> {}
public record EntityDelete<E>(long id)               implements EntityWrite<E> {}
```

The core dispatcher uses this marker to decide what SQL/operation to
run. It also makes bulk operations and diffs trivial to inspect.

> `<!-- TODO/EXPLORE: code-generate `Kinds` registry and per-process
> write slots on the unit via JCodeBuddy. -->`

## Why markers matter for aggregation

When two core steps both want to change `Order#42.total`, the unit
needs to know **whether** they want to update the same row (and how to
merge the contributions), or whether one of them wants to insert a new
row versus update an existing one. The marker is the explicit answer:

- `Insert` + `Insert` for the same id → **conflict** (the process
  tried to create the same entity twice).
- `Update` + `Update` for the same id → **merge** under a defined rule
  (last-write-wins, deep merge, or recorded as two contributions for
  diffing — see
  [`15_aggregation_and_snapshots.md`](15_aggregation_and_snapshots.md)).
- `Update` + `Delete` for the same id → **conflict** unless the
  process ordered them intentionally.