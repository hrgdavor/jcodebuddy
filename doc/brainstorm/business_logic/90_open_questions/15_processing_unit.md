# Processing Unit Open Questions

> Up: [90_open_questions/README.md](README.md). Back to [business_logic/README.md](../../README.md).

- `<!-- TODO/EXPLORE: exact merging rules for concurrent sub-units, and
> how merging preserves the core / side-effect split. -->`
- `<!-- TODO/EXPLORE: should the unit be a generated class per process,
> with sealed write / side-effect slots, or a single generic class
> with type tokens? -->`
- `<!-- TODO/EXPLORE: should the unit itself be partitioned for batch
> processing (one sub-unit per item) and merged before commit? -->`