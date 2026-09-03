# Core vs Side-Effect Open Questions

> Up: [90_open_questions/README.md](README.md). Back to [business_logic/README.md](../../README.md).

- `<!-- TODO/EXPLORE: should the categorization be enforced at compile
> time, e.g. the unit's `coreWrites()` accessor is only callable from
> a `@CoreChange` / `@CoreChangeOnChange` method, `sideEffects()`
> only from a `@NotificationOnly`, etc.? Or should it be a review-time
> / runtime convention only? -->`
- `<!-- TODO/EXPLORE: should diff views be split per category (core
> entity diff vs side-effect diff) so a debugger can focus on one? -->`
- `<!-- TODO/EXPLORE: should `next-step triggers` be a distinct
> third slot (separate from generic notifications), so they can be
> reviewed and dispatched under their own policy? -->`
- `<!-- TODO/EXPLORE: should an `@AuditEmit` type exist so the
> discipline extends to "no step may emit an audit entry unless
> explicitly typed as audit"? -->`
- `<!-- TODO/EXPLORE: PR templates that auto-collapse side-effect
> blocks by default. -->`
- `<!-- TODO/EXPLORE: review bot that flags a side-effect change in a
> PR without a corresponding core change (or vice-versa) as
> suspicious. -->`