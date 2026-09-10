# DEC-017 Brainstorm: Identifiable<ID> as opt-in identity mixin

This document captures the exploration and tradeoffs behind DEC-017.

## Problem framing

`id()` was originally on `ViewReader`. When embedded sub-documents were considered as first-class
readers, this became wrong: a `get(F field)` contract should be independent of whether the data has
a primary key.

Attempts to move `id()` to `EntityBase` instead were rejected because:
- `EntityBase` is a pure marker/type-bound — methods would force even test stubs to implement `id()`.
- The `EEnumSetTrackingJmhBenchmark` inner classes broke when `id()` was added to `EntityBase`,
  confirming this is a real cost for any trivial implementor.

## Why `Identifiable` instead of `EntityRoot`

`EntityRoot` was considered but implies DDD aggregate root semantics (lifecycle, consistency boundary).
`Identifiable` is a Spring Data concept — widely recognized, precisely scoped, no extra connotations.
It also composes cleanly: `PersonEntity extends EntityBase<Long>, Identifiable<Long>` reads naturally.

## Why `EntityBase<Void>` for embedded documents

Alternative: a separate `EmbeddedBase` marker. Rejected — it adds a name nobody will remember.
`EntityBase<Void>` is self-documenting: "this is a structured thing with no identity type". It also
keeps one generic class in the hierarchy rather than many.

## Why array classes must NOT implement Identifiable

`EntityReadArray` already unconditionally stores `values[0]` as the id. If it implemented
`Identifiable`, it would advertise identity even when used for embedded docs where slot 0 is
something like `street`. The contract must be on the view interface, not the storage primitive.

## Why the proxy no longer special-cases "id"

Before this decision, `ArrayBackedViewProxyFactory.ReadHandler` had an explicit `if (name.equals("id"))` branch.
This was needed because `ViewReader` declared `id()` and the proxy had to handle it outside the
field mapper. After removing `id()` from `ViewReader`, there is no longer a special `ViewReader`
method to intercept — `id` becomes a plain accessor like `firstName`, resolved by `FieldNameMapper`
hitting the compiled switch in the field enum. The special case is gone; the enum entry `id` does the
work.

## Tradeoffs

Pros:
- Clean opt-in: only types that say `Identifiable` have identity.
- No accidental `.id()` on embedded views at compile time.
- Proxy dispatch is simpler and uniform.
- Consistent with Spring Data naming (familiar to Java developers).

Cons:
- Every root entity now extends two things; minor boilerplate in root marker interface.
- Embedded views must actively avoid extending `Identifiable`; this is convention, not enforcement.

