# JCodeBuddy Cooperative Codegen Integration

> Up: [40_engineering/README.md](README.md). Back to [business_logic/README.md](../../README.md).

This concept is deliberately shaped to be **easy for JCodeBuddy to
generate**, so that what developers actually read stays small and
idiomatic.

## What the generator produces

Given a hand-written business process:

```java
@BusinessProcess
OrderResult recalcOrder(ProcessingUnit<OrderContext> unit, long orderId) { ... }
```

JCodeBuddy's `project-automation` module produces:

1. The `ProcessingUnit<OrderContext>` class with typed **core write**
   slots, **side-effect** slots, audit list, id allocator, debug
   collector, and log capture — all the plumbing the developer should
   not have to hand-write.
2. The **core dispatcher** mapping from each core write kind to its
   effect handler (DB, cache, …).
3. The **side-effect dispatcher** mapping from each side-effect kind
   to its delivery handler (email service, webhook queue,
   next-step-trigger publisher, …).
4. Snapshot / diff hooks at method boundaries, gated by the active
   profile, with category tagging (CORE / SIDE_EFFECT / AUDIT).
5. The **fixed-point loop guard** for `@CoreChangeOnChange` steps,
   including the pass counter, per-entity write fingerprint, and
   `LoopDetectedException` with per-step contribution chain.
6. Bulk entry points (`recalcOrders(Collection<Long>)`).
7. Tests scaffolding (golden snapshots per slot, conflict assertions,
   loop-convergence assertions).

## What the developer writes

Just the call graph. The developer writes pure functions tagged with
one of the three operation-type annotations (see
[`10_concept/15_operation_types.md`](10_concept/15_operation_types.md)):
- `@CoreChange` for steps that only write core data,
- `@CoreChangeOnChange` for steps that react to current core writes
  (the loop guard is wired in automatically),
- `@NotificationOnly` for steps that only emit side effects.

Everything else is generated cooperatively with other generators
(entity generators, IOC wiring, etc.).

## Why this is "codebuddy-friendly"

- **Stable shape** – generated code targets a small, well-known set of
  types (`ProcessingUnit`, `EntityWrite`, `SideEffect`, `AuditEntry`).
- **Slot-aware** – the generator can emit separate dispatcher code
  per slot, knowing exactly what each slot's effects mean.
- **Type-aware** – the generator can emit the loop-guard scaffolding
  based on which methods are annotated `@CoreChangeOnChange` and
  which are pure `@CoreChange` / `@NotificationOnly`.
- **Composes with other generators** – the entity generator emits
  entity types and their change markers, the pipeline generator wires
  them into the core slot.
- **Source-visible, IDE-navigable** – per project-wide rule
  ([`../../architecture/decisions/DEC-019.md`](../../architecture/decisions/DEC-019.md))
  every connection the generator emits is **ordinary Java source
  that is committed to git** and navigable with a basic IDE. The
  annotations are *markers only*; the dispatcher, the loop-guard
  scaffolding, and the slot wiring are real methods in real files,
  not runtime-discovered beans.
- **Live-reload friendly** – changing a pure function does not
  invalidate the generated dispatcher; the watcher only re-emits the
  unit wiring.
- **Reviewable diffs** – almost all generated code lives in
  `target/generated-sources/`, so PRs mostly show hand-written call
  graphs, with side-effect blocks reviewers can collapse.

## Review tooling integration (open)

- `<!-- TODO/EXPLORE: PR templates that auto-collapse side-effect
> blocks by default. -->`
- `<!-- TODO/EXPLORE: review bot that flags a side-effect change in a
> PR without a corresponding core change (or vice-versa) as
> suspicious. -->`
- `<!-- TODO/EXPLORE: review bot that flags a `@CoreChange` that
> reads from `unit.coreWrites()` and should be re-typed as
> `@CoreChangeOnChange`. -->`

## Generator specifics (open)

- `<!-- TODO/EXPLORE: how unit write / side-effect slots are namespaced
> and typed. -->`
- `<!-- TODO/EXPLORE: how multi-module projects split generated code
> between `app` and `project-automation`. -->`
- `<!-- TODO/EXPLORE: how this concept plays with `hipster-entity` and
> `hipster-ioc` once extracted. -->`
- `<!-- TODO/EXPLORE: lightweight annotation set for "snapshot point",
> "rejection", "id allocator scope", etc. -->`