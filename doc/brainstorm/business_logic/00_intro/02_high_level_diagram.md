# High-Level Diagram

> Up: [00_intro/README.md](README.md). Back to [business_logic/README.md](../../README.md).

```
   recalcOrder(orderId)                           (pure orchestration)
       |
       +-- core ----+  validate, recompute totals, persist
       |            |
       |            v
       |      core writes into unit.coreWrites()
       |
       +-- side effects --+
                          |
                          v
                unit.sideEffects() { emails, webhooks, next-steps, audit }
                          |
                          v
                dispatcher.commit(unit)            (impure boundary)
                - core dispatcher commits entity writes
                - side-effect dispatcher sends notifications, fires triggers
```

## Reading the diagram

- The call graph is **not** linear. It branches, recurses, and
  short-circuits as the process requires.
- The unit is the **only** object that crosses the pure/impure
  boundary. Nothing else.
- The dispatcher may be split into a **core dispatcher** (commits
  entity writes) and a **side-effect dispatcher** (delivers
  notifications, fires next-step triggers), with independent
  delivery semantics.
- The same unit is suitable for: live debugging (snapshot / diff
  view), tests (assert on the unit, no mocks), replay (WAL), and
  operational tooling (review, audit, telemetry).