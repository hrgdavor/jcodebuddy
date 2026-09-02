# Core Steps vs Side-Effect Steps

> Back to [README](README.md).

## Why separate them

A business process does two qualitatively different kinds of work:

- **Core steps** — the **minimum necessary** to produce the business
  outcome. Validate the order, compute totals, allocate inventory,
  persist the final state. If a core step is skipped or wrong, the
  business outcome is wrong.
- **Side-effect steps** — the **peripheral things** that happen
  *around* the core. Send a confirmation email, enqueue a webhook,
  trigger the next workflow step, write an audit entry, emit
  telemetry. If a side-effect step is skipped or fails, the business
  outcome is still correct — only the peripheral behavior is degraded.

These two kinds of work have **different review requirements**:

- A change to a core step is high-risk: it can change what the
  business does. Reviewers must look carefully.
- A change to a side-effect step is usually low-risk: a new email
  template, a new audit field, a new webhook payload. Reviewers
  should be able to skim or skip.

And they have **different operational requirements**:

- Core writes must be committed **transactionally** with the rest of
  the core; they cannot be retried indefinitely without correctness
  risk.
- Side-effects are **naturally idempotent and retriable**; they are
  good candidates for async delivery, WAL'd queues, and at-least-once
  delivery with idempotency keys.

The concept therefore makes the separation **first-class**:

- Pure functions are annotated by category.
- The unit exposes **different slots** per category.
- The dispatcher can be **split** per category, with different
  delivery semantics.
- Review tools can **collapse** side-effect blocks.

## How it's expressed in code

```java
@BusinessProcess
OrderResult recalcOrder(ProcessingUnit<OrderContext> unit, long orderId) {

    // ============== CORE ==============
    var order = unit.orders().load(orderId);                       // @CoreStep
    if (!validateOrder(unit, order)) {                             // @CoreStep
        return OrderResult.rejected(orderId);
    }
    var totals = recomputeTotals(unit, order);                     // @CoreStep
    applyPromotion(unit, order, totals);                           // @CoreStep
    applyShipping(unit, order, totals);                            // @CoreStep
    unit.coreWrites().update(order.id(), order.withTotals(totals));// aggregate
    // =================================

    // ============== SIDE EFFECTS ==============
    sendReceiptEmail(unit, order, totals);          // @SideEffectStep
    enqueueOrderUpdatedWebhook(unit, order);        // @SideEffectStep
    triggerNextWorkflowStep(unit, order);           // @SideEffectStep
    // ===========================================

    // ============== AUDIT ==============
    unit.audit().record("order.recalculated", order.id(), totals); // @AuditStep
    // ===================================

    return OrderResult.ready(orderId, totals);
}
```

## Slot grouping on the unit

The `ProcessingUnit` exposes its slots grouped, so reviewers can see at
a glance which slots are core vs peripheral:

- `unit.coreWrites()` — entity writes from `@CoreStep`s.
- `unit.sideEffects()` — notifications, webhooks, next-step triggers
  from `@SideEffectStep`s.
- `unit.audit()` — observation entries from `@AuditStep`s.

See [02_processing_unit.md](02_processing_unit.md) for the full unit
shape.

## Dispatch modes

The dispatcher can be configured to commit:

- **All** — core writes + side effects (default in production).
- **Core only** — useful for dry-runs, replay analysis, and "what
  would this process do to the database?" queries. Side-effect
  slots are inspected but not delivered.
- **Side effects only** — useful for replay after a separate core
  commit (e.g. side-effects were deferred or failed previously and
  need to be re-delivered).
- **Core now, side effects later** — the most common production
  mode: core is committed synchronously inside the request, side
  effects are WAL'd and delivered asynchronously.

## Next-step triggers as side effects

A **next-step trigger** is "tell another process to run". It is a
side-effect, not a core write:

- It does not change the current business outcome.
- It must be **idempotent** (the receiver may have already run).
- It must be **retriable** (delivery may fail).

Treating next-step triggers as side effects gives them all the
properties above for free: they go into `unit.sideEffects()`,
serialized into the WAL alongside other side effects, and delivered
through the side-effect dispatcher.

```java
@SideEffectStep
void triggerNextWorkflowStep(ProcessingUnit<OrderContext> unit, OrderView order) {
    unit.sideEffects().queue(NextStepTrigger.of(
        "shipment.create",
        Map.of("orderId", order.id())
    ));
}
```

## Reviewability benefits

- PRs that only touch side-effect code (emails, webhooks, telemetry)
  cannot accidentally change the core outcome.
- Tests can assert "core produced X" without asserting on every email
  content.
- A "core diff" view shows only entity writes; a "side-effect diff"
  shows only notifications and triggers.
- Documentation / process diagrams can be generated from the
  `@CoreStep` list alone, ignoring side-effects.
- A reviewer can collapse the side-effect block in their editor / PR
  view and read just the core.

## Compile-time enforcement (open)

> `<!-- TODO/EXPLORE: should the categorization be enforced at compile
> time, e.g. the unit's `coreWrites()` accessor is only callable from
> a `@CoreStep` method, `sideEffects()` only from a `@SideEffectStep`,
> etc.? Or should it be a review-time / runtime convention only? -->`

## Interaction with snapshots (open)

> `<!-- TODO/EXPLORE: should diff views be split per category (core
> entity diff vs side-effect diff) so a debugger can focus on one? -->`

## Granularity (open)

> `<!-- TODO/EXPLORE: should `next-step triggers` be a distinct
> third slot (separate from generic notifications), so they can be
> reviewed and dispatched under their own policy? -->`

> `<!-- TODO/EXPLORE: should `@AuditStep` exist at all, or is "audit"
> just another kind of side-effect with its own slot? -->`