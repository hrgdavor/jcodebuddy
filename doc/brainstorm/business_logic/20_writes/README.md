# 20 — Writes and Aggregation

> Back to [README](../README.md). Up: [business_logic/](../README.md).

How entity mutations are described in the unit, how identifiers are
owned by the process, and how multiple contributions to the same
entity are aggregated and made diffable.

## Files in this folder

| File                                   | Topic                                                                   |
| -------------------------------------- | ----------------------------------------------------------------------- |
| [`00_entity_writes.md`](00_entity_writes.md)               | `EntityWrite` marker (insert / update / delete).                |
| [`05_identifier_ownership.md`](05_identifier_ownership.md) | How identifiers are owned by the process, not the database.     |
| [`10_array_propagation.md`](10_array_propagation.md)       | Marker propagation for top-level arrays of entities.            |
| [`15_aggregation_and_snapshots.md`](15_aggregation_and_snapshots.md) | When two functions change the same entity, merging, snapshots, per-entity diff. |