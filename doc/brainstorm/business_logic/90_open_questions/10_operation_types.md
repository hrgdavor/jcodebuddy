# Operation-Types Open Questions

> Up: [90_open_questions/README.md](README.md). Back to [business_logic/README.md](../../README.md).

- `<!-- TODO/EXPLORE: how should `maxPasses` be configured per process,
> per type, and per entity? Should the limit be on the number of
> changes per entity, the total passes, or both? -->`
- `<!-- TODO/EXPLORE: should `@CoreChangeOnChange` declare which
> entity types / fields it reads, so the unit can short-circuit
> re-running it when none of those fields changed? -->`
- `<!-- TODO/EXPLORE: is there a useful fourth type for steps that
> are pure reads (no core write, no side effect), used purely to
> structure the call graph, or are they just helper methods? -->`
- `<!-- TODO/EXPLORE: how do we visually / textually render the
> per-pass change chain when `LoopDetected` fires, so the developer
> can see exactly which two steps were oscillating? -->`
- `<!-- TODO/EXPLORE: alternative to pass-boundary fixed point —
> purely declarative dependency graph (each `@CoreChangeOnChange`
> declares which entities / fields it reads from and writes to, the
> unit topologically schedules reactive steps until no edges fire).
> Trade off ergonomics vs determinism. -->`