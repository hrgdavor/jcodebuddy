# Open Questions & TODO/EXPLORE Placeholders

> Back to [README](README.md).

This file collects every `TODO/EXPLORE` marker across the other docs,
grouped by area. Each will be expanded as the concept is validated
against real code.

## Pipeline semantics

- `<!-- TODO/EXPLORE: short-circuit / early return semantics. -->`
- `<!-- TODO/EXPLORE: how are validation errors propagated? as
  ValidationOutcome? as exceptions? -->`
- `<!-- TODO/EXPLORE: how do steps declare optional dependencies vs
  required ones? -->`

## Concurrency

- `<!-- TODO/EXPLORE: per-entity parallelism vs whole-pipeline
  parallelism. -->`
- `<!-- TODO/EXPLORE: ordering guarantees when outcomes from concurrent
  steps are merged. -->`

## Persistence

- `<!-- TODO/EXPLORE: outcome-to-SQL mapping strategy. -->`
- `<!-- TODO/EXPLORE: integration with `metadata-arena` for off-heap
  outcome buffers and WAL. -->`
- `<!-- TODO/EXPLORE: idempotency and replay. -->`

## Debug & ops

- `<!-- TODO/EXPLORE: design the exact API of `DebugCollector`,
  storage format (in-memory ring buffer, off-heap via `metadata-arena`,
  mmap), and how to correlate with the standard logger. -->`
- `<!-- TODO/EXPLORE: storage backend for `DebugCollector`. -->`
- `<!-- TODO/EXPLORE: how to expose the change log to dev tools (LSP
  sidecar, MCP server). -->`
- `<!-- TODO/EXPLORE: structured-logging correlation between logger
  output and captured mutations. -->`
- `<!-- TODO/EXPLORE: should we use SLF4J directly, a thin wrapper, or a
  generated `BusinessLog` per pipeline? -->`

## Generator specifics

- `<!-- TODO/EXPLORE: code-generate `kind()` constants and a `Kinds`
  registry from a single source-of-truth annotated on the record via
  JCodeBuddy. -->`
- `<!-- TODO/EXPLORE: how does the generated container materialize as a
  Java type? builder, wither record, sealed interface? -->`
- `<!-- TODO/EXPLORE: precise propagation rules for nested arrays
  (List<List<Item>>, maps, sets, primitive arrays). -->`
- `<!-- TODO/EXPLORE: should the pipeline also be expressed as an
  annotation-driven DSL (e.g. `@Step("applyPromotion")`) for tooling that
  wants to render it graphically? -->`
- `<!-- TODO/EXPLORE: how `Outcome.kind()` values are namespaced. -->`
- `<!-- TODO/EXPLORE: how multi-module projects split generated code
  between `app` and `project-automation`. -->`
- `<!-- TODO/EXPLORE: how this concept plays with `hipster-entity` and
  `hipster-ioc` once extracted. -->`

## Framework comparison

- `<!-- TODO/EXPLORE: validate this concept against existing frameworks
> (Axon, Eventuate, Temporal, etc.) and decide whether to borrow their
> terminology or stay deliberately distinct. -->`
- `<!-- TODO/EXPLORE: explicit comparison write-up once prototype
> exists. -->`

## Test scaffolding

- `<!-- TODO/EXPLORE: golden-file tests that snapshot the *full* outcome
> list of a pipeline for visual diffing in code review. -->`