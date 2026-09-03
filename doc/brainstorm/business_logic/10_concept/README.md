# 10 — Core Concept

> Back to [README](../README.md). Up: [business_logic/](../README.md).

The three core abstractions of the concept: the **Processing Unit**
that accumulates effects, the **pure-function** contract, and the
**three operation types** that every step must be.

## Files in this folder

| File                                   | Topic                                                                   |
| -------------------------------------- | ----------------------------------------------------------------------- |
| [`00_processing_unit.md`](00_processing_unit.md)   | The `ProcessingUnit` object: structure, ownership, lifecycle. |
| [`05_pure_functions.md`](05_pure_functions.md)     | Contract of pure functions; what they may and may not do.       |
| [`10_core_vs_sideeffect.md`](10_core_vs_sideeffect.md) | Separation of minimum core process from peripheral side-effects. |
| [`15_operation_types.md`](15_operation_types.md)   | The three operation types (`@CoreChange`, `@CoreChangeOnChange`, `@NotificationOnly`) and the loop guard for type 2. |