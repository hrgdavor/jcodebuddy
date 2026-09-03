# 90 — Open Questions

> Back to [README](../README.md). Up: [business_logic/](../README.md).

All `TODO/EXPLORE` placeholders, grouped by area. The areas mirror
the folder structure; if you add a new area, add a new file inside
this folder and link it from this README.

## Files in this folder

| File                                   | Topic                                                                   |
| -------------------------------------- | ----------------------------------------------------------------------- |
| [`00_concept_level.md`](00_concept_level.md)                 | Concept-level: validation against existing approaches, comparisons. |
| [`05_core_vs_sideeffect.md`](05_core_vs_sideeffect.md)       | Compile-time enforcement, diff splitting, granularity.       |
| [`10_operation_types.md`](10_operation_types.md)             | Type 1 / 2 / 3 specifics: loop guard limits, dependency graph, fourth type, visual LoopDetected rendering. |
| [`15_processing_unit.md`](15_processing_unit.md)             | Unit merging, unit generation, batch partitioning.            |
| [`20_pure_functions.md`](20_pure_functions.md)               | Contextual parameters for pure functions.                     |
| [`25_entity_writes.md`](25_entity_writes.md)                 | Kind registry, id allocator scope, array propagation rules.   |
| [`30_aggregation_snapshots.md`](30_aggregation_snapshots.md) | Default merge rule per field, snapshot-point annotations, ring buffer policy. |
| [`35_pipeline_semantics.md`](35_pipeline_semantics.md)       | Short-circuit, error propagation, optional dependencies.      |
| [`40_concurrency.md`](40_concurrency.md)                     | Parallelism, merge ordering, concurrent snapshot consistency. |
| [`45_debug_ops.md`](45_debug_ops.md)                         | DebugCollector API, tooling exposure, structured logging.     |
| [`50_persistence_wal.md`](50_persistence_wal.md)             | Unit-to-SQL, off-heap WAL, idempotency, snapshot cost benchmark, WAL split. |
| [`55_test_scaffolding.md`](55_test_scaffolding.md)           | Golden-file format, storage, reviewer-friendly diffs.         |
| [`60_generator_specifics.md`](60_generator_specifics.md)     | Slot namespacing, multi-module split, hipster-entity / hipster-ioc interplay, annotation set. |