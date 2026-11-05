# The hipster-entity MVP surface

This page says what the usable-MVP batch exercises, and what remains opt-in or experimental. It does not
replace the feature guides; it is the status page a reader checks before assuming a feature is finished.

## What the MVP gate exercises

| Capability                                            | State                         | Where it is proved                                                |
| ----------------------------------------------------- | ----------------------------- | ----------------------------------------------------------------- |
| A marker, its derived views, and their generated Java | **Stable**                    | `ExampleRegenerationTest`, the example's committed sources        |
| Metadata documents and the class index                | **Stable**                    | `MetadataFileIdsTest`, `MetadataSourcePathTest`, `ClassIndexTest` |
| `--check` (full pass into a temp tree, no writes)     | **Stable**                    | `CheckCommandTest`, `bun scripts/gen.js --check`                  |
| One-shot `--incremental` with a no-op                 | **Stable**                    | `EntityBuildStateTest`, `MvpAcceptanceTest`                       |
| Selective emission for an existing-file change        | **Stable (MVP)**              | `IncrementalEmissionTest`                                         |
| Generated-file/fallback matrix                        | **Stable (MVP)**              | `IncrementalFallbackTest`, `EntityBuildStateTest`                 |
| DTO projection (`--dto-projections`)                  | **Stable, opt-in**            | `ExampleConverterManifestTest`, `ViewJsonGeneratorTest`           |
| Mapper (`--mapper`)                                   | **Stable, opt-in**            | `ViewMapperGeneratorTest`, the mapper incremental test            |
| Validation (`--validate[=OFF/REPORT/STRICT]`)         | **Stable**                    | the validation test set                                           |
| SQL row adapter / binder (`--adapters`)               | **Draft/experimental**        | `ViewAdapterGeneratorTest`; not a supported generator             |
| Patch appliers (`--patch-appliers`)                   | **Draft/experimental**        | `GeneratedPatchApplierTest`                                       |
| Merge (`--merge`)                                     | **Full-pass only in the MVP** | merge tests; a merge is family-wide                               |
| Watch mode                                            | **Postponed**                 | no watcher in this batch; W7b owns the daemon cutover             |
| Parse reuse                                           | **Post-MVP (E10)**            | the incremental pass still parses the whole set                   |
| SQL generation as a supported output                  | **Not supported**             | the JDBC pattern is hand-written; adapters are an exploration     |

## What "opt-in" means

An opt-in emitter is off unless its flag is given. It is not part of a default pass, and a project that
does not pass the flag receives no class that imports its dependencies. The three current examples are
`--adapters`, `--patch-appliers` and `--merge`; `--dto-projections` and `--mapper` are opt-in too, but they
are covered by the incremental path because they are view-scoped.

## What "draft/experimental" means

The code is tested, but the shape may change without a deprecation cycle before the major release. Do not
build a public API on it; do report what it cannot express, because that report is how the gap gets fixed
in JCodeBuddy rather than worked around in a consumer.

## Where the rest of the story lives

- [Getting started](getting-started.md) — the first pass.
- [Incremental generation](incremental-build.md) — state, fallback matrix, `--check`, what is postponed.
- [DEC-052](../architecture/decisions/DEC-052.md) — the decision record behind the incremental contract.
- [`plans/hipster-entity-mvp.md`](../../plans/hipster-entity-mvp.md) — the batch plan and its evidence.
