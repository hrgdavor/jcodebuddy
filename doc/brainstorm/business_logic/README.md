# Java Business Logic via a Side-Effect-Aware Processing Unit

> Expanded from [`initial thoughts.md`](initial%20thoughts.md). Sections marked
> `<!-- TODO/EXPLORE -->` are deliberate placeholders for further exploration.

## Overview

Business processes are rarely linear. A real process is a **call graph**:
validation calls into policy, policy calls into pricing, pricing reads the
order, promotion rules adjust totals, the audit module wants to know about
every change, and the notification module wants to know which messages to
send. Several of those functions may touch the **same entity** (e.g.
`Order.total` is mutated by both the promotion step and the shipping step),
and the final effect must be the **aggregate** of all of them.

The problem with the usual Java approach is that those effects are scattered
across injected services, framework callbacks, and static method calls —
they leave no single place where "what will this process do?" can be answered
or reviewed.

This concept organizes business logic so that:

- **Every function is pure.** A function in a business process never calls
  `emailService.send(...)` or `orders.update(...)`. Instead it describes
  what *would* happen and hands that description to a shared
  **Processing Unit**.
- **The Processing Unit accumulates side-effects.** It is a single, explicit
  object passed through the call graph. It holds entity writes, outgoing
  notifications, audit entries, generated identifiers, and any other
  side-effect descriptions the process decides it may need.
- **Conflicting mutations to the same entity are aggregated and diffable.**
  When two functions both want to change `Order.total`, both contributions
  are kept (or merged under a defined rule) and a snapshot of *before* /
  *after* for that entity is recorded at well-defined points so a reviewer
  or debugger can see exactly which call contributed which delta.
- **The actual side-effects happen at the end**, by a single dispatcher,
  using the aggregated Processing Unit.

```
   recalcOrder(orderId)                       (pure orchestration)
       |
       +-> promotions.apply(unit)            (pure - writes to unit)
       +-> shipping.quote(unit)              (pure - writes to unit)
       +-> totals.recompute(unit)            (pure - writes to unit)
       +-> audit.collect(unit)               (pure - reads unit, adds audit entries)
       |
       v
   ProcessingUnit   { orderWrites, emails, auditEntries, ids, ... }
       |
       v
   Dispatcher.commit(unit)                   (impure boundary - the only side-effects)
```

The call graph can be **deep, branching, conditional, recursive** —
whatever the process actually needs. What stays constant is that every
node on the graph is pure, and every intended effect is materialized in
the Processing Unit instead of being executed immediately.

## Contents

| File                                                       | Topic                                                                                  |
| ---------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| [`01_problem_and_idea.md`](01_problem_and_idea.md)         | Why the call graph is non-linear, and why effects must be deferred to a unit.           |
| [`02_processing_unit.md`](02_processing_unit.md)           | The `ProcessingUnit` object: structure, ownership, lifecycle.                           |
| [`03_pure_functions.md`](03_pure_functions.md)             | Contract of pure functions; how they receive the unit and contribute effects.          |
| [`04_entity_writes.md`](04_entity_writes.md)               | `EntityWrite` marker (insert/update/delete), identifier ownership, array propagation.   |
| [`05_aggregation_and_snapshots.md`](05_aggregation_and_snapshots.md) | When two functions change the same entity, how snapshots & diffs capture it.    |
| [`06_execution_model.md`](06_execution_model.md)           | Call-graph execution, composition, branching, recursion.                                |
| [`07_testability.md`](07_testability.md)                   | Testing pure functions by asserting on the unit, with no mocks.                        |
| [`08_debugging_observability.md`](08_debugging_observability.md) | Snapshot / diff view, log capture, stack-trace correlation.                        |
| [`09_data_oriented_design.md`](09_data_oriented_design.md) | Why the whole model is data-oriented and what that unlocks.                             |
| [`10_memory_performance_wal.md`](10_memory_performance_wal.md) | Memory considerations and using the unit as a WAL record.                          |
| [`11_jcodebuddy_integration.md`](11_jcodebuddy_integration.md) | How JCodeBuddy cooperative codegen makes this easy to write and maintain.            |
| [`12_open_questions.md`](12_open_questions.md)             | Open questions and `TODO/EXPLORE` placeholders.                                        |

## Key Concepts at a Glance

- **`ProcessingUnit`** — single object passed through the call graph that
  accumulates all side-effect descriptions (entity writes, notifications,
  audit, generated ids, etc.). **Not** a linear sequence — a container.
- **Pure function** — never performs side-effects; only mutates the unit.
- **`EntityWrite<E>`** — sealed `Insert / Update / Delete` marker, so
  identifiers stay in code rather than being assigned by the database.
- **Snapshot / diff** — between well-defined points in the call graph,
  the state of every entity affected by the unit is captured before and
  after, so multi-step mutations to the same entity are **diffable**
  for debugging and review.
- **Dispatcher** — the single impure boundary. Consumes the unit and
  applies the effects for real.
- **Cooperative codegen (JCodeBuddy)** — keeps the call graph readable by
  generating the unit, dispatcher wiring, snapshot hooks, and tests.

## Anti-Goals (what this is *not*)

- Not a linear pipeline DSL.
- Not an event-sourcing framework.
- Not a workflow engine with persisted state machines.
- Not a CQRS projection toolkit.
- Not a replacement for Spring/Quarkus/etc. — it composes *with* them.

> `<!-- TODO/EXPLORE: explicit comparison write-up once prototype exists. -->`

## Glossary

| Term                | Meaning                                                                 |
| ------------------- | ----------------------------------------------------------------------- |
| **Processing Unit** | The shared object that accumulates side-effect descriptions.            |
| **Pure function**   | A function that only reads inputs and mutates the unit; no I/O, no DB.  |
| **EntityWrite**     | Sealed `Insert / Update / Delete` marker on an entity change.           |
| **Snapshot**        | Captured state of an entity at a point in the call graph.               |
| **Diff**            | Per-step or per-segment before/after comparison of an entity.           |
| **Dispatcher**      | The single impure boundary that consumes the unit and applies effects. |
| **WAL**             | Write-Ahead Log; here, the serialized form of the unit itself.         |