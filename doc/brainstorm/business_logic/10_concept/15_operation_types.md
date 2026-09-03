# The Three Operation Types

> Up: [10_concept/README.md](README.md). Back to [business_logic/README.md](../../README.md).
> See also
> [`10_core_vs_sideeffect.md`](10_core_vs_sideeffect.md) for the
> broader core-vs-side-effect split that frames these three types.

This file gives a **concrete, mechanical definition** of the operation
types a pure function in a business process can be. Every step in the
call graph must declare which of the three types it is, because the
type determines how the unit's slots are populated, how the step may
be revisited, and what guarantees the dispatcher must make.

## The three types

| # | Type                              | Touches core data?   | Based on current changes? | May loop? | Annotation           |
| - | --------------------------------- | -------------------- | ------------------------- | --------- | -------------------- |
| 1 | **Core change**                   | Yes — new state       | No — independent of changes already in the unit | **No** — runs at most once per pass | `@CoreChange`        |
| 2 | **Core change on current change** | Yes — over the change | **Yes** — re-runs while the unit's core writes change what it reads | **Yes, with a loop guard** — must converge and stop | `@CoreChangeOnChange`|
| 3 | **Notification only**             | No                   | Either                   | **No** — runs at most once per pass | `@NotificationOnly`  |

> **Clear separation** — a function is *exactly one* of the three. A
> function that mutates core data **must not** also fire a side-effect
> email in the same call; the email is a separate `@NotificationOnly`
> step in the orchestration, so its presence, its content, and its
> delivery are visible in review independently of the core change.

## Type 1 — `@CoreChange` (only changes core data)

The most common kind. The function reads inputs, decides one or more
`EntityWrite`s, and adds them to `unit.coreWrites()`. It does **not**
look at the current contents of the unit's core-write slot to make
its decision — its decision is based purely on its inputs.

```java
@CoreChange
void applyPromotion(ProcessingUnit<OrderContext> unit, OrderView order, Promotion promo) {
    var newTotal = order.subtotal().minus(promo.amount());
    unit.coreWrites().update(order.id(), order.withTotal(newTotal));
}
```

Guarantees:

- runs **at most once** per orchestration pass (it doesn't depend on
  changes already in the unit, so re-running it would only re-emit the
  same write),
- the generated dispatcher may safely invoke it before
  `@CoreChangeOnChange` and `@NotificationOnly` steps,
- its contribution to the unit is the only thing it produces.

## Type 2 — `@CoreChangeOnChange` (changes core data based on current change)

A step of this type is **reactive**: it inspects the core writes
already in the unit and produces additional core writes that depend
on them. This is the natural shape for things like
"recompute derived totals after any field change" or "propagate a
status flag to child lines after the parent is updated".

```java
@CoreChangeOnChange
void recomputeDerivedTotals(ProcessingUnit<OrderContext> unit) {
    // reacts to whatever @CoreChange / @CoreChangeOnChange steps
    // have written so far in this pass
    for (var w : unit.coreWrites().updatesForType(Order.class)) {
        var order = w.entity();
        var derived = order.subtotal().plus(order.tax()).minus(order.discount());
        if (!derived.equals(order.derivedTotal())) {
            unit.coreWrites().update(order.id(), order.withDerivedTotal(derived));
        }
    }
}
```

### Why this needs a loop guard

Because the step reads from the unit and writes back to the unit, the
graph is **not** guaranteed to terminate:

- step A writes `Order.total = 100`,
- step B sees that, writes `Order.discount = total * 0.1 = 10`,
- step A re-runs (or a similar reactive step), sees the discount,
  writes `Order.total = total - discount = 90`,
- step B re-runs, writes `Order.discount = 9`, …

This is a **fixed-point loop**. The unit must detect when the
contributions have stopped changing and stop, otherwise the
orchestration never reaches the dispatcher.

### The loop-guard mechanism

The unit keeps a **monotonic pass counter** (`unit.pass()`) and a
**change-detector** that hashes the per-entity core writes between
passes.

```
pass = 0
loop:
    pass += 1
    unit.beginPass(pass)               // records a snapshot of core writes
    run all @CoreChange and @CoreChangeOnChange steps
    if unit.coreWrites().changedSince(pass - 1) {
        if pass >= maxPasses:
            throw LoopDetected(stepTrace)   // fail loudly
        continue loop
    }
    break  // fixed point reached
```

The mechanism has four parts:

1. **Per-entity write fingerprint.** For each entity the unit holds a
   stable hash of its current pending `EntityUpdate` / `EntityInsert`
   state. The hash is recomputed incrementally when a step writes.
2. **Pass boundary.** Every time the orchestration enters a loop
   iteration, the unit records the current fingerprint of every
   affected entity.
3. **Change check.** After all reactive steps have run for the pass,
   the unit compares the new fingerprint to the one recorded at the
   pass boundary. If *any* entity's fingerprint differs, more
   reactive work may still be needed.
4. **Bounded retries.** A configurable `maxPasses` (e.g. 16) bounds
   the loop. If the limit is hit, the orchestration fails with a
   `LoopDetectedException` carrying the per-step contribution chain
   so the bug is debuggable.

### What `@CoreChangeOnChange` is **not** allowed to do

- It may not depend on the **order** in which other steps ran in the
  same pass — its decision is a pure function of the unit's
  post-pass core writes.
- It may not write to `unit.sideEffects()` directly. If a reactive
  step wants to also send a notification, that is a **separate
  `@NotificationOnly` step** that reads the final core writes once
  the loop has converged.
- It may not increase a pass counter, allocate new ids for the same
  entity, or otherwise perform actions that would be unsound if the
  pass is rolled back.

> `<!-- TODO/EXPLORE: alternative to pass-boundary fixed point — purely
> declarative dependency graph (each `@CoreChangeOnChange` declares
> which entities / fields it reads from and writes to, the unit
> topologically schedules reactive steps until no edges fire). Trade
> off ergonomics vs determinism. -->`

## Type 3 — `@NotificationOnly` (only generates notifications / external effects)

A step that produces **no core writes at all**. It exists to emit
notifications to users or external systems (emails, SMS, webhooks,
next-step triggers, audit entries, telemetry, …).

```java
@NotificationOnly
void sendReceiptEmail(ProcessingUnit<OrderContext> unit, OrderView order) {
    // reads the final core writes (post-loop) — does not modify them
    var finalOrder = unit.coreWrites().finalStateFor(order.id()).orElse(order);
    unit.sideEffects().queue(EmailOutcome.to(finalOrder.customerEmail())
        .subject("Your order was updated")
        .body(renderReceipt(finalOrder)));
}
```

Guarantees:

- runs **at most once** per orchestration pass,
- runs **after** the core loop has converged, so it sees a stable
  view of the final core writes,
- it is the **only** type allowed to call `unit.sideEffects()`,
- it is the only type whose body may directly depend on
  `unit.coreWrites().finalStateFor(...)`.

> **Why it cannot write core data** — if a notification step also
> mutated core data, the loop guard could not distinguish "I produced
> a notification" from "I produced a core change that may need more
> reactive work", and the timing of the notification would be
> ambiguous (was it sent before or after a later reactive pass?).
> Keeping the type notification-only makes both the loop and the
> delivery order well-defined.

## How the three types are scheduled

The orchestration pass is structured so that the loop only ever
contains types 1 and 2, and type 3 runs once at the end:

```
[ @CoreChange steps ]               // type 1
[ loop {
    [ @CoreChangeOnChange steps ]   // type 2, with change detection
  } until no entity fingerprint changes or maxPasses exceeded ]
[ @NotificationOnly steps ]         // type 3, sees final core writes
[ dispatcher commit ]
```

The JCodeBuddy generator emits this scheduling scaffolding from the
annotation on each step method, so the hand-written orchestration
body stays linear and reviewable.

## Why the clear separation matters

- **Review:** a `@CoreChange` PR cannot accidentally add a new
  notification; a `@NotificationOnly` PR cannot accidentally change
  state.
- **Reasoning:** type 1 is monotonic, type 2 has a well-defined
  termination argument, type 3 has a stable read view.
- **Replay:** a WAL of a fixed-point run can be replayed by simply
  re-executing the steps in order; the loop guard and pass counters
  in the WAL make replay deterministic.
- **Testing:** each type has a different test shape — type 1 is a
  one-shot input→core write assertion, type 2 is a "drive the loop
  to convergence" assertion, type 3 is a "given these final core
  writes, assert these side effects".
- **Dispatch:** types 1 and 2 populate `unit.coreWrites()` and are
  consumed by the core dispatcher; type 3 populates
  `unit.sideEffects()` and is consumed by the side-effect
  dispatcher. The slots are unambiguous.

## Interaction with the slot grouping

| Type | May call `unit.coreWrites()` | May call `unit.sideEffects()` | May call `unit.audit()` |
| ---- | ---------------------------- | ----------------------------- | ---------------------- |
| 1 `@CoreChange`                | **Yes** | No (must be a separate step) | Allowed for observation only |
| 2 `@CoreChangeOnChange`        | **Yes** | No (must be a separate step) | Allowed for observation only |
| 3 `@NotificationOnly`          | **Read only** (post-loop view) | **Yes** | Allowed (e.g. audit emit) |

> `<!-- TODO/EXPLORE: should audit be a fourth type (`@AuditEmit`) so
> the discipline extends to "no step may emit an audit entry unless
> explicitly typed as audit"? -->`

## Examples of mapping real work to the three types

| Real work                                  | Type | Why                                                     |
| ------------------------------------------ | ---- | ------------------------------------------------------- |
| Apply a promotion to an order              | 1    | Decision based on inputs only.                          |
| Validate the order                         | 1    | Decision based on inputs only.                          |
| Insert a new order row                     | 1    | Decision based on inputs only.                          |
| Recompute derived totals after any change  | 2    | Reads the unit's current core writes, writes more.      |
| Propagate a status flag to child lines     | 2    | Reads parent change, writes child changes.              |
| Cascade-delete children when parent deleted| 2    | Reads parent delete, writes child deletes.              |
| Send receipt email                         | 3    | Notification, no core effect.                           |
| Enqueue order-updated webhook              | 3    | Notification, no core effect.                           |
| Trigger "shipment.create" workflow         | 3    | Next-step trigger, no core effect.                      |
| Write a row to the audit table             | 3    | External observation, no core effect.                   |

## Anti-patterns

- **A single function that both updates core data and queues an
  email** — split it into a `@CoreChange` step plus a separate
  `@NotificationOnly` step.
- **A `@CoreChange` that depends on the current contents of
  `unit.coreWrites()`** — if it does, it must be typed as
  `@CoreChangeOnChange`, and the loop guard will protect you.
- **A `@NotificationOnly` that mutates `unit.coreWrites()`** — not
  allowed; lift the mutation into a type 1 or type 2 step.
- **An unbounded `@CoreChangeOnChange` loop** — the unit enforces
  `maxPasses`; if you hit it, the design is wrong (e.g. two steps
  are oscillating a value).

## Open questions

> `<!-- TODO/EXPLORE: how should `maxPasses` be configured per process,
> per type, and per entity? Should the limit be on the number of
> changes per entity, the total passes, or both? -->`

> `<!-- TODO/EXPLORE: should `@CoreChangeOnChange` declare which
> entity types / fields it reads, so the unit can short-circuit
> re-running it when none of those fields changed? -->`

> `<!-- TODO/EXPLORE: is there a useful fourth type for steps that
> are pure reads (no core write, no side effect), used purely to
> structure the call graph, or are they just helper methods? -->`

> `<!-- TODO/EXPLORE: how do we visually / textually render the
> per-pass change chain when `LoopDetected` fires, so the
> developer can see exactly which two steps were oscillating? -->`