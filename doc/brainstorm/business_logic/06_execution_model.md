# Execution Model

> Back to [README](README.md).

The execution model is **not** a linear pipeline. It is a **call graph**
of pure functions, branching, recursive, and conditional as the
business process actually requires.

## Shape

```
recalcOrder(unit, orderId)
    |
    +-> loadOrder(unit, orderId)
    +-> isEligible?(unit, order)         // branch
         |-> yes
              +-> recomputeTotals(unit, order)
                   +-> applyPromotion(unit, order, totals)
                   +-> applyShipping(unit, order, totals)
                   +-> recomputeTaxes(unit, order, totals)   // may recurse for bundles
         |-> no
              +-> recordRejection(unit, order)
    |
    v
unit is ready for the dispatcher
```

Every node is pure; the unit is the only thing that carries
side-effect information out of the graph.

## Composition

Multiple graphs can be composed:

```java
unit.absorb(invoices.issueGraph(unit));
unit.absorb(shipping.bookGraph(unit));
```

Composition is done by **absorbing one unit into another** at well-
defined points, not by chaining callbacks.

## Branching and recursion

Free. As long as every observable effect is described in the unit,
the graph may be as deep and branching as the process requires.

## Bulk / batch

The same graph runs per item; the unit is created fresh per item
(or merged across items if explicitly designed for batch). Because
every effect is described in data, batching is a free optimization.

```java
for (var orderId : orderIds) {
    var perOrderUnit = recalcOrder(new ProcessingUnit<>(...), orderId);
    dispatcher.commit(perOrderUnit);
}
```

> `<!-- TODO/EXPLORE: should the unit itself be partitioned for batch
> processing (one sub-unit per item) and merged before commit? -->`

## Pipeline semantics (open)

- `<!-- TODO/EXPLORE: short-circuit / early return semantics. -->`
- `<!-- TODO/EXPLORE: how are validation errors propagated? as
  structured "rejection" entries in the unit? as exceptions raised
  past the dispatcher? -->`
- `<!-- TODO/EXPLORE: how do pure functions declare optional
  dependencies vs required ones? -->`

## Concurrency (open)

- `<!-- TODO/EXPLORE: per-entity parallelism vs whole-graph
  parallelism. -->`
- `<!-- TODO/EXPLORE: ordering guarantees when units from concurrent
  sub-graphs are merged. -->`
- `<!-- TODO/EXPLORE: snapshot consistency in concurrent sub-graphs. -->`