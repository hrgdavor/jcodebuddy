# The Processing Unit

> Up: [10_concept/README.md](README.md). Back to [business_logic/README.md](../../README.md).

The **`ProcessingUnit`** is the single object that travels through the
call graph and accumulates every side-effect description the process
intends to produce.

## What it holds

The unit is a **container of effect descriptions**, not a sequence. A
typical unit has slots for:

- **`coreWrites`** — `EntityWrite<E>` per entity affected by **core**
  steps (insert / update / delete, with the new state and the assigned
  id). This is the minimum the process commits to be correct.
- **`sideEffects`** — peripheral effects from side-effect steps:
  outgoing notifications (emails, SMS, push), webhooks, **next-step
  triggers** that hand work to another process, etc. Fully populated
  with their final content so the dispatcher can deliver without
  re-reading context.
- **`auditEntries`** — structured observation entries recorded by
  audit steps. May be persisted alongside the core writes or sent to a
  separate audit sink.
- **`generatedIds`** — ids assigned by the process itself
  (so the DB doesn't have to).
- **`snapshots`** — per-entity, per-step state captures for diffing.
- **`capturedLog`** — log messages emitted via `unit.log()` from pure
  functions, captured for replay / diff.
- **`diagnostics`** — anything else that helps review or replay.

```java
public final class ProcessingUnit<C> {
    private final CoreWrites coreWrites;            // core writes
    private final SideEffects sideEffects;          // notifications, triggers, webhooks
    private final AuditEntries auditEntries;
    private final IdAllocator ids;
    private final SnapshotLog snapshots;            // optional
    private final CapturedLog log;                  // optional
    // ...
}
```

## Slot grouping is intentional

The split between `coreWrites`, `sideEffects`, and `auditEntries` is
**deliberate**, not cosmetic:

- A reviewer can read only `coreWrites` to understand the business
  outcome (see [`10_core_vs_sideeffect.md`](10_core_vs_sideeffect.md)).
- A "core commit" can be performed by one dispatcher; side-effects
  can be delivered later, on a different node, after retry, etc.
- A "dry-run" mode can commit core only and discard side-effects —
  useful for staging / analysis.
- Tests can assert on `coreWrites` without asserting on every email
  content, and on `sideEffects` without asserting on entity state.

## Ownership and threading

- The unit is **owned by the orchestration entry point** that started
  the process and passed down through every call.
- It is **not** a global singleton, **not** a thread-local by default.
- For concurrent sub-graphs the unit is either partitioned (one per
  task) and merged, or wrapped in a transactional context.
- It is fully **disposable** at the end of the process — its purpose
  is to be consumed by the dispatcher(s) and then dropped.

> `<!-- TODO/EXPLORE: exact merging rules for concurrent sub-units, and
> how merging preserves the core / side-effect split. -->`

## Why a single object (and not "return value plus scattered calls")

The single object is what makes the process **reviewable and replayable**:

- A reviewer can read the orchestration method and see *all* the calls,
  not chase services across files.
- A reviewer can read the **core** of the orchestration and ignore
  the side-effect noise.
- A debugger can inspect the unit at any point in the graph.
- A test can assert on the unit's contents at any point, with no mocks.
- A WAL can serialize the unit's effect descriptions and replay them
  later (see [`40_engineering/05_memory_performance_wal.md`](40_engineering/05_memory_performance_wal.md)).

## Type safety

The unit is typed enough to keep call signatures clean. For example,
`ProcessingUnit<OrderContext>` carries typed core-write slots for order
and line items, plus generic side-effect / audit slots.

> `<!-- TODO/EXPLORE: should the unit be a generated class per process,
> with sealed write / side-effect slots, or a single generic class
> with type tokens? -->`

## Lifecycle

```
[ empty unit ]
      |
      v  pure call graph (core + side-effect steps)
[ accumulated unit ]
      |
      +--> core dispatcher.commitCore(unit)       (impure)
      |       v
      |    [ core entity writes applied to DB ]
      |
      +--> side-effect dispatcher.deliver(unit)    (impure, may be async)
              v
          [ notifications sent, triggers fired, audit persisted ]
      |
      v
[ unit discarded ]
```