# Entity Writes, Identifiers, and Array Propagation

> Back to [README](README.md).

This file covers the "side-effect information" type used for entity
mutations inside the unit, and the rules that make those mutations
**controllable from code** rather than left to the database.

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

The dispatcher uses this marker to decide what SQL/operation to run.
It also makes bulk operations and diffs trivial to inspect.

> `<!-- TODO/EXPLORE: code-generate `Kinds` registry and per-process
> write slots on the unit via JCodeBuddy. -->`

## Why markers matter for aggregation

When two pure functions both want to change `Order#42.total`, the unit
needs to know **whether** they want to update the same row (and how to
merge the contributions), or whether one of them wants to insert a new
row versus update an existing one. The marker is the explicit answer:

- `Insert` + `Insert` for the same id → **conflict** (the process
  tried to create the same entity twice).
- `Update` + `Update` for the same id → **merge** under a defined rule
  (last-write-wins, deep merge, or recorded as two contributions for
  diffing — see [05_aggregation_and_snapshots.md](05_aggregation_and_snapshots.md)).
- `Update` + `Delete` for the same id → **conflict** unless the
  process ordered them intentionally.

## Identifier ownership

Ids are allocated **inside the process** by a deterministic allocator
exposed on the unit (`unit.ids().next("order")`), not by the database.
This lets the unit emit `Insert` rows with their final id and lets
references between entities be resolved before commit.

```java
var orderId = unit.ids().next("order");
unit.entityWrites().insert(new EntityInsert<>(orderId,
    Order.draft(customerId, lines)));
```

> `<!-- TODO/EXPLORE: id allocator scope (per-unit vs shared), snowflake
> vs ULID vs simple monotonic, and replay-safety guarantees. -->`

## Arrays of entities — marker propagation

When a top-level entity owns a collection (e.g. `Order.lines`), changes
to elements must force a change marker on the **owner** so the
dispatcher emits a single coherent write for the owner even if its head
fields were not touched.

```java
class ArrayChangeMarker<T> {
    boolean dirty();
    void markDirty();
    // specialized impl propagates `markDirty()` to the owner container
}
```

This means:

- Mutating `Order.lines[3].qty` automatically marks `Order.lines` dirty.
- Mutating `Order.lines` automatically marks `Order` dirty.
- The dispatcher therefore produces a single `EntityUpdate<Order>`
  whose `lines` collection reflects all aggregated line changes.

> `<!-- TODO/EXPLORE: precise propagation rules for nested arrays
> (`List<List<Item>>`, maps, sets, primitive arrays), and for cases
> where only structural changes vs only element changes must be
> distinguished. -->`