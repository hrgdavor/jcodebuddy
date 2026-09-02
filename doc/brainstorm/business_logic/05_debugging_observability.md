# Debugging & Observability

> Back to [README](README.md).

Goal: when a step in a 12-step pipeline mutates a value that a later
step also mutates, we want to know **which step changed what and why**,
without re-running in a debugger.

## Per-step change capture

A debug collector subscribes to the same `OutcomeSink` and records a
**typed before/after snapshot** of every `UpdatePair` mutated by a step.

```
Step: applyPromotion
  Order#42.total  : 100.00  ->  90.00   (promotion=PROMO10)
  capture stack:  OrderFlow::applyPromotion:14
                  OrderFlow::recalcOrder:22
                  ...
```

## Toggling

- Bound to a log level (e.g. `DEBUG`).
- Or a `DebugMode` flag passed into the method.
- Or a thread-local for targeted inspection in production.
- Or a JCodeBuddy profile (`dev`, `staging`, `prod`) that decides whether
  the collector is wired in at all.

> `<!-- TODO/EXPLORE: design the exact API of `DebugCollector`,
> storage format (in-memory ring buffer, off-heap via `metadata-arena`,
> mmap), and how to correlate with the standard logger. -->`

## Logger as parameter

Instead of calling `Loggers.BUSINESS.info(...)` from deep inside steps,
the **logger is an explicit parameter** of the method performing the
business logic. This:

- keeps steps pure (no static lookup),
- makes it trivial to inject a capturing logger that pipes messages into
  the same debug collector,
- aligns with how `OutcomeSink` is injected.

```java
OrderStepResult applyPromotion(
    UpdatePair<OrderView, OrderUpdate> order,
    UpdatePair<PromotionView, PromotionUpdate> promo,
    OutcomeSink out,
    Logger log
) {
    log.debug("Applying promotion {} to order {}", promo.view().id(), order.view().id());
    ...
}
```

> `<!-- TODO/EXPLORE: should we use SLF4J directly, a thin wrapper, or a
> generated `BusinessLog` per pipeline? -->`

## Ops integration (open)

> `<!-- TODO/EXPLORE: storage backend for `DebugCollector`. -->`
> `<!-- TODO/EXPLORE: how to expose the change log to dev tools (LSP
> sidecar, MCP server). -->`
> `<!-- TODO/EXPLORE: structured-logging correlation between logger
> output and captured mutations. -->`