# Persistence / WAL Open Questions

> Up: [90_open_questions/README.md](README.md). Back to [business_logic/README.md](../../README.md).

- `<!-- TODO/EXPLORE: unit-to-SQL mapping strategy. -->`
- `<!-- TODO/EXPLORE: integration with `metadata-arena` for off-heap
> unit buffers and WAL. -->`
- `<!-- TODO/EXPLORE: idempotency and replay. -->`
- `<!-- TODO/EXPLORE: idempotency keys on notifications, dedup keys on
> entity writes, and how the unit records them. -->`
- `<!-- TODO/EXPLORE: benchmark snapshot cost vs benefit; pick a default
> strategy. -->`
- `<!-- TODO/EXPLORE: separate WALs for core vs side effects, or one
> WAL with slot markers? -->`