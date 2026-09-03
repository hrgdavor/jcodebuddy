# Problem and Idea

> Up: [00_intro/README.md](README.md). Back to [business_logic/README.md](../../README.md).

## The actual shape of a business process

A "business process" is rarely a list of steps. It's a **call graph**:

- validation may invoke policy rules, which may invoke the catalog,
- pricing may invoke promotion rules and shipping estimators,
- promotions may interact with loyalty balances,
- audit may observe *all* of the above and want to know exactly what
  each call decided,
- notifications may need to know what emails / webhooks / push
  messages the process decided to send.

The branches are conditional (`if user.isEligible(...)`), recursive
(line items have sub-items), and may depend on data loaded mid-graph.

## The problem with the usual approach

When each of those calls can directly call injected services, the
effects are scattered across the codebase. To answer "what will this
process do?", a reviewer has to follow a tangle of method calls and
framework callbacks, and to test it you have to mock every collaborator.

Worse, **two calls may both touch the same entity** (e.g. `Order.total`
is updated by both the promotion step and the shipping step). With
direct calls, you only see the final value — the contributions are lost.

And worst of all for review: the reviewer cannot easily separate
**"the minimum the process must do to be correct"** (validate,
compute, persist) from **"the peripheral things around it"** (emails,
webhooks, next-step triggers, audit). PRs end up mixing both kinds of
change, and a fix to an email template looks indistinguishable from a
fix to the discount calculation.

## The idea

Make every function in the process **pure** w.r.t. the outside world.
Functions only **describe** the effects they intend into a shared
**Processing Unit** that travels with the call graph.

Separate the work into **two clearly-marked categories**:

- **Core steps** — the minimum necessary to produce the business
  outcome. Reviewers can read these in isolation to understand *what
  this process does to the world*.
- **Side-effect steps** — peripheral effects (notifications, next-step
  triggers, audit, telemetry) that happen *around* the core. Clearly
  marked, easily skippable in review, and dispatchable independently.

Real side-effects are performed once, at the end, by one or more
dispatchers consuming the unit's slots.

When multiple functions affect the same entity, the unit keeps every
contribution (or merges under an explicit rule). Between well-defined
points in the graph, snapshots are captured so the contributions are
**diffable** — both for live debugging and for code review.

> `<!-- TODO/EXPLORE: validate this idea against existing approaches
> (effect systems, command pattern, transactional outbox, IO monads in
> other languages). Decide what to borrow vs stay distinct. -->`

## What we keep vs reject

**Reject** approaches that hide effects inside the call graph:

- injected services that do real work,
- event handlers that mutate other aggregates,
- DB-assigned identifiers,
- "magic" side-effects inside framework-managed callbacks,
- and — by project-wide rule — any wiring that is not materialized
  as committed, IDE-navigable Java source. See
  [`../../architecture/decisions/DEC-019.md`](../../architecture/decisions/DEC-019.md).

**Keep** what is genuinely useful:

- clear separation between "decide" and "act",
- **separation between core business outcome and peripheral effects**,
- replayable history of decisions,
- composable, branching, recursive call graphs,
- declarative description of intended effects.