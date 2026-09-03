# 40 — Engineering

> Back to [README](../README.md). Up: [business_logic/](../README.md).

Cross-cutting engineering concerns: why the model is data-oriented, how
it scales in memory and over time, the WAL story, and how JCodeBuddy
generates the supporting code.

## Files in this folder

| File                                   | Topic                                                                   |
| -------------------------------------- | ----------------------------------------------------------------------- |
| [`00_data_oriented_design.md`](00_data_oriented_design.md) | Why the whole model is data-oriented and what that unlocks. |
| [`05_memory_performance_wal.md`](05_memory_performance_wal.md) | Memory considerations and using the unit as a WAL record. |
| [`10_jcodebuddy_integration.md`](10_jcodebuddy_integration.md) | How JCodeBuddy cooperative codegen makes this easy to write and maintain. |