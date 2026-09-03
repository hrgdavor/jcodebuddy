# Mental Model

> Up: [00_intro/README.md](README.md). Back to [business_logic/README.md](../../README.md).

The mental model in three sentences:

1. A business process is a **call graph** of pure functions.
2. Every pure function describes its effects into a shared
   **Processing Unit** that travels with the call.
3. At the end of the call, one or more **dispatchers** consume the
   unit and perform the real effects (DB writes, emails, webhooks,
   next-step triggers).

Everything else in this concept is a consequence of those three
sentences.

## The pure-function contract

A pure function in a business process:

- **reads** its explicit parameters and the unit,
- **mutates** the unit by adding effect descriptions,
- **returns** values to its caller,
- does **not** call I/O, does **not** call injected services, does
  **not** read wall time or random ids directly,
- is annotated as exactly **one** of the three operation types —
  see [`10_concept/05_operation_types.md`](10_concept/05_operation_types.md).

## The role of the Processing Unit

The Processing Unit is the **only** object that carries observable
intent out of the call graph. It is a plain container of effect
descriptions, with **separate slots** for core writes, side effects,
and audit:

```
[ pure call graph ] --unit--> [ Dispatcher commit ]
                          |
   +-- coreWrites()      |   core dispatcher: persist
   +-- sideEffects()     |   side-effect dispatcher: notify, trigger, audit
   +-- audit()           |
   +-- ids, snapshots, log |
```

Because the unit is the only thing the call graph produces, the
process is **reviewable** (read the orchestration method, see every
effect that will happen) and **replayable** (serialize the unit, hand
it to the dispatcher again).