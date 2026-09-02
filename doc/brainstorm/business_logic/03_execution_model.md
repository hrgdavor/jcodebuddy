# Execution Model

> Back to [README](README.md).

## Linear pipeline (looks like normal code)

```java
public List<Outcome> recalcOrder(long orderId, Clock clock) {
    var order    = orders.load(orderId);           // -> UpdatePair
    var promo    = promotions.findFor(order.view());
    var shipping = shipping.quote(order.view());

    var o1 = applyPromotion(order, promo, sink);
    var o2 = applyShipping(o1, shipping, sink);
    var o3 = recomputeTotals(o2, clock, sink);

    return sink.outcomes();
}
```

A reviewer sees **one method**, top to bottom. The "magic" of
combining steps is done by JCodeBuddy at build / live-reload time.

## Bulk mode

The same pipeline is reusable over a batch:

```java
public Map<Long, List<Outcome>> recalcOrders(Collection<Long> ids) {
    return ids.stream().collect(groupingBy(
        identity(),
        mapping(this::recalcOrder, toList())
    ));
}
```

Because outcomes are pure data, batching is a free optimization.

## Composition

Multiple pipelines can be composed:

```java
var orderOutcomes = recalcOrder(orderId, clock);
var invoiceOutcomes = invoices.issue(orderOutcomes, clock);
```

Composition is done by **passing outcomes around as data**, not via
framework callbacks.

## Pipeline semantics (open)

> `<!-- TODO/EXPLORE: short-circuit / early return semantics. -->`
> `<!-- TODO/EXPLORE: how are validation errors propagated? as
> ValidationOutcome? as exceptions? -->`
> `<!-- TODO/EXPLORE: how do steps declare optional dependencies vs
> required ones? -->`

## Concurrency (open)

> `<!-- TODO/EXPLORE: per-entity parallelism vs whole-pipeline
> parallelism. -->`
> `<!-- TODO/EXPLORE: ordering guarantees when outcomes from concurrent
> steps are merged. -->`