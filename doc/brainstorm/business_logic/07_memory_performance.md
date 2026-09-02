# Memory & Performance

> Back to [README](README.md).

The notes call out: *"Look for optimizing memory usage in these cases,
and performance."*

## Reuse `UpdatePair` storage where possible

A pipeline that processes N orders should not allocate N×M independent
`UpdatePair` objects per step if many fields are not touched. The
generated wrapper can offer:

- a single shared `UpdatePair` template,
- field-level "was-touched" bits instead of object copies,
- slice-based views over an off-heap buffer (via `metadata-arena`).

## Outcomes as the WAL record

Because each outcome already contains every byte required to perform its
effect, it is a perfect WAL entry:

- write outcomes to a log,
- async apply them to the database,
- in-memory caches remain consistent with the WAL,
- DB eventually catches up.

```
[ Pipeline ] --emit--> [ OutcomeLog (mmap) ] --async--> [ DB ]
                          |
                          v
                     [ Debug view ]
```

This turns "outcomes" into the unifying primitive between
in-process logic, persistence, and observability.

## Persistence (open)

> `<!-- TODO/EXPLORE: outcome-to-SQL mapping strategy. -->`
> `<!-- TODO/EXPLORE: integration with `metadata-arena` for off-heap
> outcome buffers and WAL. -->`
> `<!-- TODO/EXPLORE: idempotency and replay. -->`