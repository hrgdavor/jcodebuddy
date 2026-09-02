# Execution Model

> Back to [README](README.md).

The execution model is **not** a linear pipeline. It is a **call graph**
of pure functions, branching, recursive, and conditional as the
business process actually requires. Within that graph, core and
side-effect steps live as **separate blocks** that the reviewer can
collapse independently.

## Shape

```
recalcOrder(unit, orderId)
    |
    +-- core ----+ loadOrder, validateOrder, recomputeTotals,
    |            | applyPromotion, applyShipping
    |            |
    |            v
    |      unit.coreWrites()
    |
    +-- side effects -+ sendReceiptEmail, enqueueOrderWebhook,
                       triggerNextWorkflowStep
                       |
                       v
                 unit.sideEffects()
    |
    v
unit ready for the dispatcher(s)
```

Every node is pure; the unit is the only thing that carries
side-effect information out of the graph.

## Composition

Multiple graphs can be composed:

```java
unit.absorb(invoices.issueGraph(unit));   // adds both core writes and side effects
unit.absorb(shipping.bookGraph(unit));
```

Composition is done by **absorbing one unit into another** at well-
defined points, not by chaining callbacks.

## Branching and recursion

Free. As long as every observable effect is described in the unit and
each step declares its category, the graph may be as deep and
branching as the process requires.

## Bulk / batch

The same graph runs per item; the unit is created fresh per item
(or merged across items if explicitly designed for batch). Because
every effect is described in data, batching is a free optimization.

```java
for (var orderId : orderIds) {
    var perOrderUnit = recalcOrder(new ProcessingUnit<>(...), orderId);
    coreDispatcher.commit(perOrderUnit);            // sync core commit
    sideEffectDispatcher.enqueue(perOrderUnit);     // async side-effect delivery
}
```

> `<!-- TODO/EXPLORE: should the unit itself be partitioned for batch
> processing (one sub-unit per item) and merged before commit? -->`

## Pipeline semantics (open)

- `<!-- TODO/EXPLORE: short-circuit / early return semantics. -->`
- `<!-- TODO/EXPLORE: how are validation errors propagated? as
> structured "rejection" entries in the unit? as exceptions raised
> past the dispatcher? -->`
- `<!-- TODO/EXPLORE: how do pure functions declare optional
> dependencies vs required ones? -->`

## Concurrency (open)

- `<!-- TODO/EXPLORE: per-entity parallelism vs whole-graph
> parallelism. -->`
- `<!-- TODO/EXPLORE: ordering guarantees when units from concurrent
> sub-graphs are merged. -->`
- `<!-- TODO/EXPLORE: snapshot consistency in concurrent sub-graphs. -->`