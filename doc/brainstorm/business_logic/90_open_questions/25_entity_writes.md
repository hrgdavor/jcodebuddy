# Entity-Writes Open Questions

> Up: [90_open_questions/README.md](README.md). Back to [business_logic/README.md](../../README.md).

- `<!-- TODO/EXPLORE: code-generate `Kinds` registry and per-process
> write slots on the unit via JCodeBuddy. -->`
- `<!-- TODO/EXPLORE: id allocator scope (per-unit vs shared), snowflake
> vs ULID vs simple monotonic, and replay-safety guarantees. -->`
- `<!-- TODO/EXPLORE: precise propagation rules for nested arrays
> (`List<List<Item>>`, maps, sets, primitive arrays), and for cases
> where only structural changes vs only element changes must be
> distinguished. -->`