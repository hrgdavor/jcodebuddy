# EnumSet Implementation Path and JMH Evidence

This document is an implementation guide for the custom enum-bitset structures used for change tracking in proxy and builder workflows.

For the decision rationale, tradeoffs, and measured evidence, see [DEC-014](../brainstorm/dec-014-enumset-concrete-dispatch-strategy.md).

## What are EEnumSets and why not use `java.util.EnumSet`?

`java.util.EnumSet` is already a bitset-backed collection, but it is opaque: you cannot read its raw bits, it is mutable-only (no immutable variant), it does not implement value equality across instances, and it cannot be used as a key without wrapping. It is also not composable — there is no shared read-only interface between a mutable set and a snapshot.

`EEnumSet` is a family of types built around a shared read interface (`EEnumSetRead`) that exposes the raw bit segments directly (`getBits0()`, `getBits(int)`). This enables:

- **Zero-allocation membership tests** — `has(E)`, `hasAny(...)`, `hasAll(...)` operate directly on `long` values with no boxing or object creation.
- **Bit-level set algebra** — `union`, `intersect`, `difference` use bitwise OR/AND/AND-NOT across `long` segments.
- **Value equality** — two `EEnumSetRead` instances with the same enum class and identical bits compare equal regardless of concrete type (immutable vs builder).
- **Snapshotting** — `toImmutable()` on a builder yields a frozen `EEnumSet` instance; `toBuilder()` on an immutable yields a mutable copy ready for further mutation.
- **Cross-type interop** — immutables and builders share the same interface, so code that reads a set does not need to know whether it came from a builder or a snapshot.
- **Consistent `forEach`** — The `ObjIntConsumer<E>` variant passes both the value and its position index in set order, useful for index-aware processing without allocating a list.
- **Conversion helpers** — `toArray(E[])`, `toList()`, and `toEnumSet()` provide bridges to standard Java types when needed.

## Implementation scope

The implementation provides three layers of enum-set behavior for update tracking:

- `EEnumSet64` and `EEnumSetLarge`: immutable read snapshots
- `EEnumSetBuilder64` and `EEnumSetBuilderLarge`: mutable accumulators for mark/unmark/clear
- `EntityUpdateTrackingArray64` and `EntityUpdateTrackingArrayLarge`: concrete backing for tracking arrays in proxies

Special singletons avoid unnecessary allocation for degenerate cases:

- `EEnumSetEmpty`: cached per enum class, returned by builders when the result is empty
- `EEnumSetAll`: represents the full universe without storing any bits at all

## Implementation approach

The design intentionally separates immutable and mutable responsibilities:

- immutable `EEnumSet*` classes expose read semantics and snapshot representation
- mutable `EEnumSetBuilder*` classes expose `add`, `remove`, `setOrdinal`, and `clear`
- tracking arrays hold concrete builder variants to reduce polymorphic dispatch on hot paths

Two storage variants are used:

- **64-value enums**: single `long` field (`EEnumSet64`, `EEnumSetBuilder64`) — all operations are a single bitwise instruction, no loops, no arrays
- **larger enums**: segmented `long[]` (`EEnumSetLarge`, `EEnumSetBuilderLarge`) — iterates only over non-zero segments using `Long.numberOfTrailingZeros` to visit set bits without scanning zeros

This keeps fast paths small and predictable while preserving support for larger enums.

### Why concrete type dispatch matters

`hasAll`, `hasAny`, `addAll`, `removeAll`, and `retainAll` use `instanceof` checks to take short-circuit paths when both sides are known concrete types. This avoids calling `getBits(int)` through the interface when direct field access (or a raw bits accessor like `rawBits0()`) is available, which gives the JIT a better inlining opportunity. See DEC-014 for JMH evidence on this point.

### The `EEnumSetAll` sentinel

`EEnumSetAll` stores no bits. It computes the correct bit mask for any segment on demand via `bitsForSegment(int)`. This means:

- `size()` returns the enum universe length at zero storage cost
- `has(E)` is a single null-check — every non-null value is a member
- `hasAll(other)` is an enum-class equality check — the "all" set contains every possible subset
- `toBuilder()` fills a builder using `bitsForSegment` to avoid duplicating the last-segment mask logic

### Immutable/mutable split ergonomics

Builder operations return `this` for chaining:

```java
EEnumSet<MyField> snapshot = EEnumSetBuilder.create(MyField.class)
    .add(MyField.NAME)
    .add(MyField.EMAIL)
    .toImmutable();
```

`EEnumSet.copyOf(EEnumSetRead<E>)` accepts any read source — if it is already an immutable `EEnumSet`, the same instance is returned without copying:

```java
EEnumSet<MyField> safe = EEnumSet.copyOf(someReadSource);
```

## Why JMH is used

The optimization goal is practical runtime behavior, not theoretical micro-optimizations.
JMH is used to validate that the chosen path performs well under representative operations.

Benchmark focus:

- `mark` and `unmark` throughput
- `clear` throughput
- snapshot creation throughput
- concrete dispatch vs interface or abstract dispatch
- 64-field and 96-field cases

Relevant benchmark classes:

- `hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetJmhBenchmark.java`
- `hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetTrackingJmhBenchmark.java`

Runner:

- `scripts/run-jmh.js`

## Quick local commands

- `bun run scripts/run-jmh.js --include ".*EEnumSetJmhBenchmark.*"`
- `bun run scripts/run-jmh.js --include ".*EEnumSetTrackingJmhBenchmark.*"`
- `mvnd -pl hipster-entity-core test` (unit test + smoke check)

## Inlining-oriented benchmark profile

The default runner profile is tuned to allow JIT inlining stabilization:

- forks: `3`
- warmup iterations: `6`
- measurement iterations: `8`
- warmup time: `2s`
- measurement time: `2s`

Recommended command:

```bash
bun run scripts/run-jmh.js --include ".*EEnumSetTrackingJmhBenchmark.*"
```

Short smoke runs are still useful for quick checks, but they should not be treated as decision-grade evidence.

## Current evidence summary

Directional results from the tracking suite show:

- `markUnmark*` paths generally benefit from concrete dispatch
- `clear*` paths are mixed by variant and width (64 vs 96)
- snapshot paths are largely dominated by allocation and conversion cost

Interpretation:

- The custom EnumSet path is a good fit for update-tracking mutation hot paths.
- Dispatch specialization helps most where work per call is small and frequent.
- Snapshot cost should be treated as a separate optimization concern from dispatch.

## Decision guidance

Use this implementation path when:

- tracking updates frequently in proxy or builder flows
- enum width is known and benefits from specialized 64 or segmented code paths
- reproducible JMH runs confirm expected behavior in your target JVM profile

Reassess when:

- snapshot creation dominates total cost
- production workload patterns diverge from benchmark assumptions
- a simpler baseline provides equivalent end-to-end performance with lower maintenance cost