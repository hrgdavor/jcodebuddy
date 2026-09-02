# Debugging and Observability

> Back to [README](README.md).

Goal: when a function deep in the call graph mutates an entity, and
several functions later another function mutates the same entity, we
want to know **which call contributed which delta**, without
re-running in a debugger.

## Snapshot-based diff

The primary debugging tool is the **per-entity, per-step diff** built
from the snapshots recorded at call-graph boundaries (see
[05_aggregation_and_snapshots.md](05_aggregation_and_snapshots.md)).

```
Entity: Order#42 (type=Order)
Step 1  validateOrder         : total = 100.00, discount = 0, shipping = 0
Step 3  applyPromotion(P10)  : total =  90.00, discount = 10
Step 5  applyShipping(STD)   : total =  97.50, shipping = 7.50
```

## Stack traces

Each snapshot captures a compact stack trace so the diff can be
correlated with the source location that triggered the change.

## Log capture

Logging is also a side-effect, so it goes through the unit too:

- Pure functions call `unit.log().info("...")` instead of `LOG.info(...)`.
- The unit captures log messages into a debug slot.
- The dispatcher replays them to the real logger at commit time
  (or routes them to a separate sink).

This lets the same diff view include "what did this step say about
itself", not just "what did it change".

## Toggling

The debug collector is **optional** and can be turned on/off several
ways:

- bound to a log level (e.g. `DEBUG`),
- a `DebugMode` flag passed into the entry method,
- a thread-local for targeted inspection in production,
- a JCodeBuddy profile (`dev`, `staging`, `prod`) that decides whether
  the collector is wired in at all.

## Storage

- in-memory ring buffer for live debugging,
- off-heap buffer via `metadata-arena` for long-running processes,
- mmap file for post-mortem analysis.

> `<!-- TODO/EXPLORE: exact `DebugCollector` API and storage backend. -->`

## Tooling integration (open)

> `<!-- TODO/EXPLORE: how to expose the diff view to dev tools (LSP
> sidecar, MCP server). -->`
> `<!-- TODO/EXPLORE: structured-logging correlation between captured
> log output and entity diffs. -->`