# Open Questions and TODO/EXPLORE Placeholders

> Back to [README](README.md).

This file collects every `TODO/EXPLORE` marker across the other docs,
grouped by area.

## Concept-level

- `<!-- TODO/EXPLORE: validate this idea against existing approaches
> (effect systems, command pattern, transactional outbox, IO monads in
> other languages). Decide what to borrow vs stay distinct. -->`
- `<!-- TODO/EXPLORE: explicit comparison write-up once prototype
> exists. -->`

## Processing Unit

- `<!-- TODO/EXPLORE: exact merging rules for concurrent sub-units. -->`
- `<!-- TODO/EXPLORE: should the unit be a generated class per process,
> with sealed write slots, or a single generic class with type tokens? -->`
- `<!-- TODO/EXPLORE: should the unit itself be partitioned for batch
> processing (one sub-unit per item) and merged before commit? -->`

## Pure functions

- `<!-- TODO/EXPLORE: minimum set of contextual parameters every pure
> function may receive. Should they all be carried inside the unit, or
> passed alongside it? -->`

## Entity writes

- `<!-- TODO/EXPLORE: code-generate `Kinds` registry and per-process
> write slots on the unit via JCodeBuddy. -->`
- `<!-- TODO/EXPLORE: id allocator scope (per-unit vs shared), snowflake
> vs ULID vs simple monotonic, and replay-safety guarantees. -->`
- `<!-- TODO/EXPLORE: precise propagation rules for nested arrays
> (`List<List<Item>>`, maps, sets, primitive arrays), and for cases
> where only structural changes vs only element changes must be
> distinguished. -->`

## Aggregation and snapshots

- `<!-- TODO/EXPLORE: which merge rule should be the default per field
> type, and how to let developers override per-process. -->`
- `<!-- TODO/EXPLORE: lightweight annotations for "snapshot point" vs
> automatic inference from method boundaries. -->`
- `<!-- TODO/EXPLORE: ring buffer size, eviction policy, and how to
> expose the diff view to dev tools (LSP sidecar, MCP server). -->`

## Pipeline semantics

- `<!-- TODO/EXPLORE: short-circuit / early return semantics. -->`
- `<!-- TODO/EXPLORE: how are validation errors propagated? as
> structured "rejection" entries in the unit? as exceptions raised
> past the dispatcher? -->`
- `<!-- TODO/EXPLORE: how do pure functions declare optional
> dependencies vs required ones? -->`

## Concurrency

- `<!-- TODO/EXPLORE: per-entity parallelism vs whole-graph
> parallelism. -->`
- `<!-- TODO/EXPLORE: ordering guarantees when units from concurrent
> sub-graphs are merged. -->`
- `<!-- TODO/EXPLORE: snapshot consistency in concurrent sub-graphs. -->`

## Debug & ops

- `<!-- TODO/EXPLORE: exact `DebugCollector` API and storage backend. -->`
- `<!-- TODO/EXPLORE: how to expose the diff view to dev tools (LSP
> sidecar, MCP server). -->`
- `<!-- TODO/EXPLORE: structured-logging correlation between captured
> log output and entity diffs. -->`

## Persistence / WAL

- `<!-- TODO/EXPLORE: unit-to-SQL mapping strategy. -->`
- `<!-- TODO/EXPLORE: integration with `metadata-arena` for off-heap
> unit buffers and WAL. -->`
- `<!-- TODO/EXPLORE: idempotency and replay. -->`
- `<!-- TODO/EXPLORE: idempotency keys on notifications, dedup keys on
> entity writes, and how the unit records them. -->`
- `<!-- TODO/EXPLORE: benchmark snapshot cost vs benefit; pick a default
> strategy. -->`

## Test scaffolding

- `<!-- TODO/EXPLORE: golden-file format (JSON, custom text), where to
> store the files, and how to make diffs reviewer-friendly. -->`

## Generator specifics

- `<!-- TODO/EXPLORE: how unit write slots are namespaced and typed. -->`
- `<!-- TODO/EXPLORE: how multi-module projects split generated code
> between `app` and `project-automation`. -->`
- `<!-- TODO/EXPLORE: how this concept plays with `hipster-entity` and
> `hipster-ioc` once extracted. -->`
- `<!-- TODO/EXPLORE: lightweight annotation set for "snapshot point",
> "rejection", "id allocator scope", etc. -->`