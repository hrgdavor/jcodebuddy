# Memory, Performance, and the Unit as WAL

> Up: [40_engineering/README.md](README.md). Back to [business_logic/README.md](../../README.md).

The notes call out: *"Look for optimizing memory usage in these cases,
and performance."*

## Reuse effect slots where possible

A process that touches N entities should not allocate N×M independent
`EntityWrite` objects if many fields are not touched. The generated
unit offers:

- shared templates per entity type,
- field-level "was-touched" bits instead of full copies,
- slice-based views over an off-heap buffer (via `metadata-arena`).

## Snapshots are the expensive part

Snapshots capture per-step state. For a process with K steps and E
affected entities, naive snapshots cost O(K·E). Strategies:

- snapshot only entities that were actually touched since the last
  snapshot,
- snapshot only the touched fields, not the whole entity,
- keep a compact structural diff chain instead of full copies,
- evict old snapshots when the entity has not been touched for a
  while.

> `<!-- TODO/EXPLORE: benchmark snapshot cost vs benefit; pick a default
> strategy. -->`

## Outcomes / unit as WAL

Because every observable effect the process intends is described in the
unit — **both core writes and side effects** — **the unit itself is a
perfect WAL entry**:

- serialize the unit at commit,
- apply core writes to the database asynchronously (or synchronously
  for transactional flows),
- deliver side effects via the side-effect dispatcher (often
  async, often retriable, often idempotent),
- in-memory caches remain consistent with the unit's contents,
- DB eventually catches up.

```
[ pure call graph ] --unit--> [ UnitLog (mmap) ] --async--> [ core writes to DB ]
                              |                         \--> [ side effects delivered ]
                              v
                         [ Debug diff view ]
```

The unit becomes the unifying primitive between in-process logic,
persistence, and observability, while the **slot split** lets core
and side effects flow through different downstream pipelines without
the call graph knowing about it.

## Idempotency

A serialized unit carries every effect description. Replaying the same
unit against the dispatcher must be safe (same DB state, same
notifications skipped or sent depending on idempotency keys).

> `<!-- TODO/EXPLORE: idempotency keys on notifications, dedup keys on
> entity writes, and how the unit records them. -->`

## Persistence (open)

- `<!-- TODO/EXPLORE: unit-to-SQL mapping strategy. -->`
- `<!-- TODO/EXPLORE: integration with `metadata-arena` for off-heap
> unit buffers and WAL. -->`
- `<!-- TODO/EXPLORE: idempotency and replay. -->`
- `<!-- TODO/EXPLORE: separate WALs for core vs side effects, or one
> WAL with slot markers? -->`