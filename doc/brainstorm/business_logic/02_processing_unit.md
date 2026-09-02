# The Processing Unit

> Back to [README](README.md).

The **`ProcessingUnit`** is the single object that travels through the
call graph and accumulates every side-effect description the process
intends to produce.

## What it holds

The unit is a **container of effect descriptions**, not a sequence. A
typical unit has slots for:

- **`entityWrites`** — `EntityWrite<E>` per affected entity
  (insert / update / delete, with the new state and the assigned id).
- **`notifications`** — outgoing messages, emails, webhooks, push
  notifications, all fully populated with their final content.
- **`auditEntries`** — structured entries for every decision taken
  during the process.
- **`generatedIds`** — ids assigned by the process itself
  (so the DB doesn't have to).
- **`diagnostics`** — debug snapshots, captured logs, anything that
  helps review or replay.

```java
public final class ProcessingUnit {
    private final Map<EntityRef<?>, EntityWrite<?>> entityWrites = new LinkedHashMap<>();
    private final List<Notification> notifications = new ArrayList<>();
    private final List<AuditEntry> auditEntries = new ArrayList<>();
    private final IdAllocator ids;
    private final DebugCollector debug; // optional
    // ...
}
```

## Ownership and threading

- The unit is **owned by the orchestration entry point** that started
  the process and passed down through every call.
- It is **not** a global singleton, **not** a thread-local by default.
- For concurrent sub-graphs the unit is either partitioned (one per
  task) and merged, or wrapped in a transactional context.
- It is fully **disposable** at the end of the process — its purpose
  is to be consumed by the dispatcher and then dropped.

> `<!-- TODO/EXPLORE: exact merging rules for concurrent sub-units. -->`

## Why a single object (and not "return value plus scattered calls")

The single object is what makes the process **reviewable and replayable**:

- A reviewer can read the orchestration method and see *all* the calls,
  not chase services across files.
- A debugger can inspect the unit at any point in the graph.
- A test can assert on the unit's contents at any point, with no mocks.
- A WAL can serialize the unit's effect descriptions and replay them
  later (see [10_memory_performance_wal.md](10_memory_performance_wal.md)).

## Type safety

The unit is typed enough to keep call signatures clean. For example,
`ProcessingUnit<OrderContext>` carries typed entity-write slots for
order and line items, plus generic slots for notifications and audit.

> `<!-- TODO/EXPLORE: should the unit be a generated class per process,
> with sealed write slots, or a single generic class with type tokens? -->`

## Lifecycle

```
[ empty unit ]
      |
      v  pure function calls (graph)
[ accumulated unit ]
      |
      v  dispatcher.commit(unit)
[ real effects applied + WAL recorded ]
      |
      v
[ unit discarded ]
```