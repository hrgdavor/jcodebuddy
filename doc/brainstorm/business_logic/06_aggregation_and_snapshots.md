# Aggregation and Snapshots

> Back to [README](README.md).

> *"multiple functions could affect same entity and final effect will be
> aggregate, but in such scenario it should be attempted to snapshot those
> changes between steps in way that is diffable for debugging."*

This file is the heart of the concept.

## Aggregation, not last-write-wins

When two core steps in the call graph both want to change
`Order#42.total`, the unit does **not** silently let the later call
overwrite the earlier one. There are three options, configurable per
process:

1. **Last-write-wins merge** — fine for purely additive reductions
   (e.g. summing discounts), recorded as a single `EntityUpdate`.
2. **Deep merge with diff** — both contributions are kept, the
   resulting merged entity is computed, and a per-contribution diff is
   recorded in the debug slot.
3. **Reject** — the process is invalid; emit a structured conflict
   outcome instead of a write.

> `<!-- TODO/EXPLORE: which merge rule should be the default per field
> type, and how to let developers override per-process. -->`

## Snapshots at well-defined points

Between well-defined points in the call graph — typically **between
named sub-steps** of a `@BusinessProcess` method, or around each call
to a `@CoreStep` / `@SideEffectStep` method — the unit records a
**snapshot** of every entity it has been told about.

A snapshot is a tiny value object:

```java
record EntitySnapshot<E>(
    long entityId,
    String entityType,
    int stepIndex,           // monotonic counter inside the process
    String stepName,         // method or logical step name
    StepCategory category,   // CORE / SIDE_EFFECT / AUDIT
    E state,                 // the entity state at this point
    StackTraceElement[] stack // captured at the snapshot point
) {}
```

The unit keeps a list of these (one per snapshot point per entity).
The debug collector groups them per entity and produces a per-step
diff.

## Example diff

```
Entity: Order#42 (type=Order)
Step 1  CORE    validateOrder         : total = 100.00, discount = 0, shipping = 0
Step 3  CORE    applyPromotion(P10)   : total =  90.00, discount = 10
Step 5  CORE    applyShipping(STD)    : total =  97.50, shipping = 7.50
Step 8  CORE    recomputeTotals       : total =  97.50  (unchanged from Step 5)
Step 9  SIDE    sendReceiptEmail      : (no entity change; email queued)
Step 10 SIDE    enqueueOrderWebhook   : (no entity change; webhook queued)
```

A reviewer sees the full chain of contributions at a glance, and the
core / side-effect boundary is visible in the diff itself.

## When snapshots are recorded

Snapshots are not recorded after every line of code. They are recorded
at **call-graph boundaries** that the developer marks, or that the
generator infers from method boundaries:

- at the entry and exit of every `@CoreStep` / `@SideEffectStep`
  method,
- at every `if` / `for` branch the developer annotates,
- around recursive calls.

> `<!-- TODO/EXPLORE: lightweight annotations for "snapshot point" vs
> automatic inference from method boundaries. -->`

## Stack traces

Each snapshot captures a compact stack trace (top N frames) so the
debugger can correlate the diff with the source location that triggered
it. This is enabled when the `DebugCollector` is active.

## Storage

Snapshots are pure data. They can be:

- kept in-memory for live debugging,
- written to an off-heap ring buffer (via `metadata-arena`) for
  long-running processes,
- serialized into the WAL alongside the unit for post-mortem replay.

> `<!-- TODO/EXPLORE: ring buffer size, eviction policy, and how to
> expose the diff view to dev tools (LSP sidecar, MCP server). -->`

## Idempotency

Because snapshots are derived from the unit's effect descriptions and
the recorded calls, replaying a WAL produces the same snapshots —
which makes replay-based debugging reliable.