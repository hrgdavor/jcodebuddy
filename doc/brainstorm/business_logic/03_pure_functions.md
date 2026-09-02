# Pure Functions in the Call Graph

> Back to [README](README.md).

Every function on the call graph is **pure** with respect to the outside
world. It reads inputs and the unit, mutates the unit, and returns
values. It never calls `emailService.send(...)`, `orders.update(...)`,
`kafka.publish(...)`, `clock.now()` directly, etc.

## Contract

```java
@CoreStep
OrderTotals recomputeTotals(ProcessingUnit<OrderContext> unit, OrderView order) {
    var discount = unit.promotions().appliedTo(order);
    var shipping = unit.shipping().quoteFor(order);

    var totals = OrderTotals.of(order.subtotal(), discount, shipping);

    // describe intended core effect — DO NOT perform it
    unit.coreWrites().update(order.id(), order.withTotals(totals));

    // audit (observation, not effect)
    unit.audit().record("totals.recomputed", order.id(), totals);

    return totals;
}
```

What this guarantees:

- The function can be called from anywhere in the graph and will not
  trigger real side-effects.
- The function can be called repeatedly (with the same inputs) and
  will produce the same contributions to the unit.
- The function can be tested by inspecting the unit after the call.

## What "pure" excludes

- Reading the wall clock directly — receive a `Clock` parameter or use
  `unit.clock()`.
- Generating random ids — receive an `IdAllocator` parameter or use
  `unit.ids()`.
- Logging to SLF4J directly — receive a `Logger` parameter or use
  `unit.log()` so the log can be captured into the unit's debug slot.
- Reading environment variables / config — pass a typed config object.

> `<!-- TODO/EXPLORE: minimum set of contextual parameters every pure
> function may receive. Should they all be carried inside the unit, or
> passed alongside it? -->`

## Step categories: `@CoreStep` vs `@SideEffectStep` vs `@AuditStep`

Pure functions are *categorized*. The category determines:

- which slot of the unit they may write to,
- which dispatcher consumes their contributions,
- how they are surfaced in review.

| Annotation        | May write to                | Consumed by               | Review weight |
| ----------------- | --------------------------- | ------------------------- | ------------- |
| `@CoreStep`       | `unit.coreWrites()`         | core dispatcher           | **high**      |
| `@SideEffectStep` | `unit.sideEffects()`        | side-effect dispatcher    | medium        |
| `@AuditStep`      | `unit.audit()`              | audit sink / dispatcher   | low           |

The categories are explained in detail in
[04_core_vs_sideeffect_steps.md](04_core_vs_sideeffect_steps.md).

## The "must add side-effect information" rule

> *"function call in business process does not have side-effects, but
> must add side-effect information into a current processing unit"*

This is the core rule. A function in a business process that *would*
do something externally observable MUST add a description of that
something to the appropriate unit slot instead. If it cannot or does
not add such a description, it is doing nothing observable and probably
belongs in a pure helper rather than the business process.

## Interactions between pure functions

Pure functions call each other freely. Recursion, conditionals, deep
branching — all allowed. The only discipline is that every observable
change goes into the unit, and every step declares its category.

```java
@BusinessProcess
OrderResult recalcOrder(ProcessingUnit<OrderContext> unit, long orderId) {
    var order = unit.orders().load(orderId);          // pure read into unit

    if (!validateOrder(unit, order)) {                // @CoreStep
        return OrderResult.rejected(orderId);
    }

    var totals = recomputeTotals(unit, order);        // @CoreStep
    applyPromotion(unit, order, totals);              // @CoreStep
    applyShipping(unit, order, totals);               // @CoreStep

    // ---- side effects (separate block in review) ----
    sendReceiptEmail(unit, order, totals);            // @SideEffectStep
    enqueueOrderUpdatedWebhook(unit, order);          // @SideEffectStep
    triggerNextWorkflowStep(unit, order);             // @SideEffectStep

    return OrderResult.ready(orderId, totals);
}
```

A reviewer can collapse the side-effect block and read only the core
to understand the business outcome.