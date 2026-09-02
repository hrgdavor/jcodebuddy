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
or reviewed. Reviewers cannot easily tell **what is the minimum the process
must do** from **what is peripheral around it** (emails, webhooks, next-step
triggers, audit).

This concept organizes business logic so that:

- **Every function is pure.** A function in a business process never calls
  `emailService.send(...)` or `orders.update(...)`. Instead it describes
  what *would* happen and hands that description to a shared
  **Processing Unit**.
- **Core steps are separated from side-effect steps.**
  - **Core steps** are the minimum necessary to produce the business
    outcome — validate, compute, persist the state.
  - **Side-effect steps** are the peripheral things that happen *around*
    the core — confirmation emails, webhooks, next-step triggers, audit
    entries, telemetry.
  The two are written in the same pure-function style but **marked
  differently**, surfaced in **different slots of the unit**, and may be
  **dispatched independently** (e.g. core committed synchronously,
  side-effects dispatched asynchronously / WAL'd for later).
- **The Processing Unit accumulates side-effects.** It is a single, explicit
  object passed through the call graph. It holds core entity writes,
  side-effect descriptions (notifications, next-step triggers), audit
  entries, generated identifiers, and debug snapshots.
- **Conflicting mutations to the same entity are aggregated and diffable.**
  When two functions both want to change `Order.total`, both contributions
  are kept (or merged under a defined rule) and a snapshot of *before* /
  *after* for that entity is recorded at well-defined points so a reviewer
  or debugger can see exactly which call contributed which delta.
- **The actual side-effects happen at the end**, by one or more
  dispatchers consuming the unit's slots.

```
   recalcOrder(orderId)                           (pure orchestration)
       |
       +-- core ----+  validate, recompute totals, persist
       |            |
       |            v
       |      core writes into unit.coreWrites()
       |
       +-- side effects --+
                          |
                          v
                unit.sideEffects() { emails, webhooks, next-steps, audit }
                          |
                          v
                dispatcher.commit(unit)            (impure boundary)
                - core dispatcher commits entity writes
                - side-effect dispatcher sends notifications, fires triggers
```

The call graph can be **deep, branching, conditional, recursive** —
whatever the process actually needs. What stays constant is that every
node on the graph is pure, every intended effect is materialized in the
Processing Unit, and the reviewer can read the **core** in isolation to
understand the business outcome.

## Contents

| File                                                              | Topic                                                                                  |
| ----------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| [`01_problem_and_idea.md`](01_problem_and_idea.md)                | Why the call graph is non-linear, why effects must be deferred to a unit.              |
| [`02_processing_unit.md`](02_processing_unit.md)                  | The `ProcessingUnit` object: structure, ownership, lifecycle.                           |
| [`03_pure_functions.md`](03_pure_functions.md)                    | Contract of pure functions; how they receive the unit and contribute effects.          |
| [`04_core_vs_sideeffect_steps.md`](04_core_vs_sideeffect_steps.md) | Separation of minimum core process from peripheral side-effects (emails, triggers). |
| [`05_entity_writes.md`](05_entity_writes.md)                      | `EntityWrite` marker (insert/update/delete), identifier ownership, array propagation.   |
| [`06_aggregation_and_snapshots.md`](06_aggregation_and_snapshots.md) | When two functions change the same entity, how snapshots & diffs capture it.        |
| [`07_execution_model.md`](07_execution_model.md)                  | Call-graph execution, composition, branching, recursion.                                |
| [`08_testability.md`](08_testability.md)                          | Testing pure functions by asserting on the unit, with no mocks.                        |
| [`09_debugging_observability.md`](09_debugging_observability.md)  | Snapshot / diff view, log capture, stack-trace correlation.                             |
| [`09_data_oriented_design.md`](09_data_oriented_design.md)        | Why the whole model is data-oriented and what that unlocks.                             |
| [`10_memory_performance_wal.md`](10_memory_performance_wal.md)    | Memory considerations and using the unit as a WAL record.                              |
| [`11_jcodebuddy_integration.md`](11_jcodebuddy_integration.md)    | How JCodeBuddy cooperative codegen makes this easy to write and maintain.               |
| [`12_open_questions.md`](12_open_questions.md)                    | Open questions and `TODO/EXPLORE` placeholders.                                        |

## Key Concepts at a Glance

- **`ProcessingUnit`** — single object passed through the call graph that
  accumulates all side-effect descriptions (core writes, side-effects,
  audit, generated ids, snapshots, log capture).
- **Pure function** — never performs side-effects; only mutates the unit.
- **Core step vs side-effect step** — pure functions are *categorized*:
  core steps produce the business outcome; side-effect steps produce
  notifications, next-step triggers, audit, telemetry.
- **`EntityWrite<E>`** — sealed `Insert / Update / Delete` marker, so
  identifiers stay in code rather than being assigned by the database.
- **Snapshot / diff** — between well-defined points in the call graph,
  the state of every entity affected by the unit is captured before and
  after, so multi-step mutations to the same entity are **diffable**
  for debugging and review.
- **Dispatcher(s)** — the impure boundary. Can be split into a
  **core dispatcher** (commits entity writes) and a **side-effect
  dispatcher** (delivers notifications, fires triggers).
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

| Term                  | Meaning                                                                 |
| --------------------- | ----------------------------------------------------------------------- |
| **Processing Unit**   | The shared object that accumulates side-effect descriptions.            |
| **Pure function**     | A function that only reads inputs and mutates the unit; no I/O, no DB.  |
| **Core step**         | A pure function that produces the minimum business outcome (state).    |
| **Side-effect step**  | A pure function that produces peripheral effects (email, webhook, …).  |
| **EntityWrite**       | Sealed `Insert / Update / Delete` marker on an entity change.           |
| **Snapshot**          | Captured state of an entity at a point in the call graph.               |
| **Diff**              | Per-step or per-segment before/after comparison of an entity.           |
| **Core dispatcher**   | Impure boundary that commits entity writes from the unit.               |
| **Side-effect dispatcher** | Impure boundary that delivers notifications / triggers / audit.     |
| **WAL**               | Write-Ahead Log; here, the serialized form of the unit itself.         |