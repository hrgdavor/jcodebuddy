# Data-Oriented Design

> Back to [README](README.md).

The whole concept leans data-oriented:

- **Outcomes are records** of plain data.
- **Steps are functions** `Input -> (Output, UpdatePair[])`.
- **Side effects are a separate phase.**
- **Reviewing a pipeline = reviewing its data flow** + a small amount of
  generated glue.

This is friendly to:

- snapshot testing,
- replaying a pipeline from recorded outcomes,
- moving to a different runtime (e.g. serverless) by rehydrating from WAL,
- diffing outcomes in code review (they're plain text / JSON-like records).