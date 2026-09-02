# Pure Functions in the Call Graph

> Back to [README](README.md).

Every function on the call graph is **pure** with respect to the outside
world. It reads inputs and the unit, mutates the unit, and returns
values. It never calls `emailService.send(...)`, `orders.update(...)`,
`kafka.publish(...)`, `clock.now()` directly, etc.

## Contract

```java
@PureStep
OrderTotals recomputeTotals(ProcessingUnit<OrderContext> unit, OrderView order) {
    var discount = unit.promotions().appliedTo(order);
    var shipping = unit.shipping().quoteFor(order);

    var totals = OrderTotals.of(order.subtotal(), discount, shipping);

    // describe intended effect — DO NOT perform it
    unit.entityWrites().update(order.id(), order.withTotals(totals));

    // describe a notification — DO NOT send it
    unit.notifications().queue(EmailOutcome
        .to(order.customerEmail())
        .subject("Your order total was updated")
        .body(renderReceipt(order, totals)));

    // audit
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

## The "must add side-effect information" rule

> *"function call in business process does not have side-effects, but
> must add side-effect information into a current processing unit"*

This is the core rule. A function in a business process that *would*
do something externally observable MUST add a description of that
something to the unit instead. If it cannot or does not add such a
description, it is doing nothing observable and probably belongs in a
pure helper rather than the business process.

## Interactions between pure functions

Pure functions call each other freely. Recursion, conditionals, deep
branching — all allowed. The only discipline is that every observable
change goes into the unit.

```java
@BusinessProcess
OrderResult recalcOrder(ProcessingUnit<OrderContext> unit, long orderId) {
    var order = unit.orders().load(orderId);          // pure read into unit
    if (!unit.policies().isEligible(order)) {
        unit.audit().record("rejected", orderId, "policy");
        return OrderResult.rejected(orderId);
    }

    var totals = recomputeTotals(unit, order);          // pure call, mutates unit
    unit.promotions().applyIfEligible(unit, order);   // pure call, mutates unit
    unit.shipping().apply(unit, order, totals);       // pure call, mutates unit

    unit.audit().record("totals.finalized", orderId, totals);
    return OrderResult.ready(orderId, totals);
}
```