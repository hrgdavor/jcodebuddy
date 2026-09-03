# 30 — Runtime

> Back to [README](../README.md). Up: [business_logic/](../README.md).

How the unit-driven process actually runs in production: the execution
model, how to test it, and how to observe and debug it.

## Files in this folder

| File                                   | Topic                                                                   |
| -------------------------------------- | ----------------------------------------------------------------------- |
| [`00_execution_model.md`](00_execution_model.md)         | Call-graph execution, composition, branching, recursion.      |
| [`05_testability.md`](05_testability.md)                 | Testing pure functions by asserting on the unit, with no mocks. |
| [`10_debugging_observability.md`](10_debugging_observability.md) | Snapshot / diff view, log capture, stack-trace correlation. |