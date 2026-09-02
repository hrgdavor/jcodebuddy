# JCodeBuddy Cooperative Codegen Integration

> Back to [README](README.md).

This concept is deliberately shaped to be **easy for JCodeBuddy to
generate**, so that what developers actually read stays small and
idiomatic.

## What the generator produces

Given a hand-written pipeline:

```java
@BusinessProcess
public List<Outcome> recalcOrder(long orderId, Clock clock) { ... }
```

JCodeBuddy's `project-automation` module produces:

1. The `OutcomeSink`, `DebugCollector`, `Logger` wiring for the method.
2. The combined `EntityWrite` container types used by the pipeline.
3. The dispatcher mapping from `Outcome.kind()` to its effect handler.
4. Step-level debug hooks if `dev` profile is active.
5. Bulk entry point (`recalcOrders(Collection<Long>)`).
6. Tests scaffolding (golden snapshots, empty-outcome assertions).

## What the developer writes

Just the linear method body. Everything else is generated cooperatively
with other generators (entity generators, IOC wiring, etc.).

> `<!-- TODO/EXPLORE: should the pipeline also be expressed as an
> annotation-driven DSL (e.g. `@Step("applyPromotion")`) for tooling that
> wants to render it graphically? -->`

## Why this is "codebuddy-friendly"

- **Stable shape** – generated code targets a small, well-known set of
  types (`Outcome`, `UpdatePair`, `EntityWrite`, `OutcomeSink`).
- **Composes with other generators** – the entity generator emits
  `OrderView`/`OrderUpdate`, the pipeline generator wires them in.
- **Live-reload friendly** – changing a step body does not invalidate
  the generated dispatcher; the watcher only re-emits the pipeline.
- **Reviewable diffs** – almost all generated code lives in
  `target/generated-sources/`, so PRs mostly show hand-written
  pipelines.

## Generator specifics (open)

> `<!-- TODO/EXPLORE: how `Outcome.kind()` values are namespaced. -->`
> `<!-- TODO/EXPLORE: how multi-module projects split generated code
> between `app` and `project-automation`. -->`
> `<!-- TODO/EXPLORE: how this concept plays with `hipster-entity` and
> `hipster-ioc` once extracted. -->`