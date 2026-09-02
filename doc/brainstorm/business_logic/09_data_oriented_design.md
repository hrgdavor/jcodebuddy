# Data-Oriented Design

> Back to [README](README.md).

The whole concept leans data-oriented:

- **The Processing Unit is a plain object** of effect descriptions.
- **Pure functions are pure transformations** over the unit.
- **Side effects are a separate phase** (the dispatcher(s)).
- **Core and side-effect contributions are kept in separate slots** so
  they can be inspected, dispatched, and reviewed independently.
- **Snapshots and diffs are pure data**.
- **Reviewing a process = reviewing its data flow** + a small amount
  of generated glue.

This is friendly to:

- snapshot testing,
- replaying a process from a recorded unit,
- moving to a different runtime (e.g. serverless) by rehydrating
  from WAL,
- diffing units in code review (they're plain data),
- partial commits (core now, side effects later) without changing
  the call graph.