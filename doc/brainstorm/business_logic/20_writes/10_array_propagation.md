# Arrays of Entities — Marker Propagation

> Up: [20_writes/README.md](README.md). Back to [business_logic/README.md](../../README.md).

When a top-level entity owns a collection (e.g. `Order.lines`), changes
to elements must force a change marker on the **owner** so the core
dispatcher emits a single coherent write for the owner even if its
head fields were not touched.

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