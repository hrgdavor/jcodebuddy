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
- **Every step is exactly one of three operation types** — see
  [`10_concept/05_operation_types.md`](10_concept/05_operation_types.md):
  - `@CoreChange` — only changes core data, no dependency on current changes.
  - `@CoreChangeOnChange` — changes core data based on current changes;
    protected by a **loop guard** that detects and stops infinite oscillation.
  - `@NotificationOnly` — only generates notifications / external effects;
    runs once after the core loop has converged.
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

## Folder layout

The concept is split into stable, named **folders** rather than a flat
sequence of numbered files. Folder numbers are spaced (`00_…`, `10_…`,
`20_…`, …) so a new sub-topic can be added inside a folder without
renaming anything; only its **local** number changes within that
folder. The hierarchy itself is what is supposed to stay stable across
edits.

| Folder                                             | Topic                                                                        |
| -------------------------------------------------- | ---------------------------------------------------------------------------- |
| [`00_intro/`](00_intro/README.md)                  | Problem, idea, the call-graph mental model, high-level diagram.                |
| [`10_concept/`](10_concept/README.md)              | Core abstractions: Processing Unit, pure functions, the three operation types. |
| [`20_writes/`](20_writes/README.md)                | `EntityWrite`, identifiers, aggregation, snapshots.                            |
| [`30_runtime/`](30_runtime/README.md)             | Execution model, testability, debugging / observability.                       |
| [`40_engineering/`](40_engineering/README.md)     | Data-oriented design, memory / performance, WAL, JCodeBuddy integration.       |
| [`90_open_questions/`](90_open_questions/README.md) | All `TODO/EXPLORE` placeholders, grouped.                                      |

> **Editing rule:** when you add a new sub-topic, pick the folder that
> owns the area and use the next free local number. **Do not**
> renumber the top-level folders, the other files inside a folder, or
> the cross-folder structure. The cross-file links are written so this
> rule keeps working.

## Key Concepts at a Glance

- **`ProcessingUnit`** — single object passed through the call graph that
  accumulates all side-effect descriptions (core writes, side-effects,
  audit, generated ids, snapshots, log capture).
- **Pure function** — never performs side-effects; only mutates the unit.
- **Core step vs side-effect step** — pure functions are *categorized*:
  core steps produce the business outcome; side-effect steps produce
  notifications, next-step triggers, audit, telemetry.
- **The three operation types** — every pure function must be exactly
  one of `@CoreChange` (writes core data, no dependency on current
  changes), `@CoreChangeOnChange` (writes core data based on current
  changes, **guarded by a fixed-point loop**), or `@NotificationOnly`
  (emits notifications/external effects only, runs once after the core
  loop has converged). See
  [`10_concept/05_operation_types.md`](10_concept/05_operation_types.md).
- **`EntityWrite<E>`** — sealed `Insert / Update / Delete` marker, so
  identifiers stay in code rather than being assigned by the database.
- **Loop guard** — the unit detects infinite oscillation between
  `@CoreChangeOnChange` steps via per-entity write fingerprints and a
  bounded `maxPasses` limit, and fails loudly with a
  `LoopDetectedException` and the per-step contribution chain.
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
- **Not a Spring / Quarkus / Dagger / Guice-style framework.** The
  project does not act like those frameworks — it does not hide
  program flow behind runtime classpath scanning, reflection or proxy
  magic. See
  [`doc/architecture/decisions/DEC-019.md`](../../architecture/decisions/DEC-019.md)
  for the project-wide rule ("source-visible, IDE-navigable
  wiring"): annotations are markers, every declared connection is
  materialized as committed Java source, and the committed source
  must be navigable with a basic IDE.

> `<!-- TODO/EXPLORE: explicit comparison write-up once prototype exists. -->`

## Glossary

| Term                  | Meaning                                                                 |
| --------------------- | ----------------------------------------------------------------------- |
| **Processing Unit**   | The shared object that accumulates side-effect descriptions.            |
| **Pure function**     | A function that only reads inputs and mutates the unit; no I/O, no DB.  |
| **Core step**         | A pure function that produces the minimum business outcome (state).    |
| **Side-effect step**  | A pure function that produces peripheral effects (email, webhook, …).  |
| **`@CoreChange`**     | Type-1 step: writes core data, independent of current changes in the unit. |
| **`@CoreChangeOnChange`** | Type-2 step: writes core data based on current changes; protected by a loop guard. |
| **`@NotificationOnly`** | Type-3 step: emits notifications / external effects only; runs once after the core loop converges. |
| **Loop guard**        | Fixed-point mechanism that detects oscillation between type-2 steps via per-entity write fingerprints and a bounded pass count. |
| **EntityWrite**       | Sealed `Insert / Update / Delete` marker on an entity change.           |
| **Snapshot**          | Captured state of an entity at a point in the call graph.               |
| **Diff**              | Per-step or per-segment before/after comparison of an entity.           |
| **Core dispatcher**   | Impure boundary that commits entity writes from the unit.               |
| **Side-effect dispatcher** | Impure boundary that delivers notifications / triggers / audit.     |
| **WAL**               | Write-Ahead Log; here, the serialized form of the unit itself.         |