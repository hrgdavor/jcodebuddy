# Identifier Ownership

> Up: [20_writes/README.md](README.md). Back to [business_logic/README.md](../../README.md).

Ids are allocated **inside the process** by a deterministic allocator
exposed on the unit (`unit.ids().next("order")`), not by the database.
This lets the unit emit `Insert` rows with their final id and lets
references between entities be resolved before commit.

```java
var orderId = unit.ids().next("order");
unit.coreWrites().insert(new EntityInsert<>(orderId,
    Order.draft(customerId, lines)));
```

> `<!-- TODO/EXPLORE: id allocator scope (per-unit vs shared), snowflake
> vs ULID vs simple monotonic, and replay-safety guarantees. -->`