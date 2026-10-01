# DEC-014 Brainstorm: EnumSet concrete dispatch strategy for change tracking

This document explores the decision to use custom enum-set structures with concrete dispatch specialization
for update tracking hot paths, rather than relying on polymorphic interfaces or generic collection types.

## 1. Problem statement

Update tracking and change detection are frequent operations in proxy and builder workflows:
- every property assignment must be recorded
- builder snapshots must be created during commits
- change tracking supports patch-oriented persistence and partial updates

The baseline challenge is dispatch cost:
- abstract or interface-typed field access adds virtual method call overhead
- generic collection lookups (e.g., `HashSet<Integer>`) add hashing and boxing cost
- these operations are small but frequent, making dispatch dominate total cost

Without specialization, tracking can become a measurable bottleneck in high-volume scenarios.

## 2. Goals

- provide fast update tracking that doesn't dominate end-to-end latency
- support enum values across multiple widths (up to 256 in practice)
- keep the tracking representation portable and embeddable in proxy code
- maintain clear semantics for touched/dirty tracking via bitmasks
- allow measurement via microbenchmarks to validate dispatch benefit hypothesis

## 3. Non-goals

- Optimize snapshot cost (allocation and conversion are separate concerns).
- Provide a one-size-fits-all tracking solution for all use cases.
- Eliminate the need for higher-level change tracking decisions.

## 4. Solution overview

Use custom `EEnumSet*` and `EEnumSetBuilder*` classes with two storage variants:

**Immutable variant** (`EEnumSet64`, `EEnumSetLarge`):
- represents a snapshot of tracked fields

**Mutable variant** (`EEnumSetBuilder64`, `EEnumSetBuilderLarge`):
- accumulates changes during composition
- implements `mark`, `unmark`, and `clear` operations

**Tracking arrays** (`EntityUpdateTrackingArray64`, `EntityUpdateTrackingArrayLarge`):
- hold concrete builder references to enable static binding on hot paths
- reduce virtual method calls from interface-typed access

### 4.1 Storage specialization

- **64-value enums**: single `long` bitmask (no allocation, CPU-friendly)
- **larger enums**: segmented `long[]` (predictable scaling, cache-friendly layout)

This avoids the memory cost and dispatch overhead of generic collections while keeping code generation simple.

### 4.2 Concrete dispatch

Tracking arrays store concrete builder types (`EEnumSetBuilder64<E>`, `EEnumSetBuilderLarge<E>`)
rather than interface-typed references. Generated code accesses these directly:

```java
// With concrete dispatch (static binding):
tracker.builder64.mark(ordinal);

// Versus interface-based (virtual dispatch):
tracker.builderInterface.mark(ordinal);

// Versus generic lookup (boxing + hash):
tracker.changedFields.add(ordinal);
```

## 5. Tradeoffs

### 5.1 Pros

- **Low dispatch cost**: concrete type enables static binding in fast C2 inlining scenarios.
- **Predictable memory**: no pointers to auxiliary HashSet or other collection overhead.
- **Small operations**: mark/unmark are single bit operations on a `long`.
- **Embeddable**: code generation can inline the whole structure into proxy fields.
- **Measurable**: JMH benchmarks can validate the dispatch benefit hypothesis.

### 5.2 Cons

- **Code generation complexity**: tracking array must choose and instantiate the right variant.
- **Proliferation of types**: separate classes for each storage width and role (64 vs large, immutable vs mutable).
- **Tight coupling**: generated proxies depend on the concrete enum-set types.
- **Allocation on scale-up**: transitioning from 64-bit to segmented storage requires recreation.
- **No polymorphic fallback**: can't change tracking strategy dynamically for a running instance.

## 6. Evidence and validation approach

### 6.1 JMH-based measurement

The project includes `EEnumSetTrackingJmhBenchmark` to measure:
- `mark` and `unmark` throughput on concrete vs interface dispatch
- `clear` and snapshot creation cost
- 64-value and 96-value enum widths

Benchmark runner: `scripts/run-jmh.js` with inlining-oriented profile (3 forks, 6 warmup × 2s, 8 measurement × 2s).

### 6.2 Observed results (directional)

- Mutation paths (`mark`, `unmark`) show **3–9% improvement** with concrete dispatch.
- Clear operation is **mixed** across variants.
- Snapshot creation is **allocation-dominated**, not dispatch-dependent.

Interpretation:
- Concrete dispatch helps most on small, frequent operations (bit flipping).
- The tradeoff is justified for hot paths with high marking frequency.
- Snapshot cost is a separate concern from dispatch strategy.

### 6.3 Service-ID overlap (decision-grade)

A full-profile run (3 forks, 6 × 2 s warmup, 8 × 2 s measurement, JMH 1.37, JDK 25) measured
`hasAny` overlap against `HashSet`, `BitSet` and raw-`long` baselines across enum widths 64 / 96 / 256
and densities 0.05–0.9:

- **~390× faster than `HashSet`** at density 0.5 and **~739×** at 0.9 (width 64), rising to **~1,246×**
  at width 256.
- Filtering a whole agent corpus inverts with density: the EEnumSet path **accelerates** as overlap
  rises while `HashSet.retainAll` **slows down** — ~1,933× apart at 100 agents and density 0.9.
- The disjoint worst case stays flat within ±3%, and the path is allocation-free in steady state
  (≈ 10⁻⁵ B/op, ≈ 0 GC counts per iteration, against 1,376 B/op and 18 GCs for `HashSet`).
- Width costs only what the scan walks: with no early exit, width 256 drops to ~322k ops/ms from width
  64's ~1,098k.

The full tables, the allocation probe and the reproduction command are in
[EnumSet implementation path and JMH evidence](../architecture/enumset-implementation-and-jmh.md); the
workload-scale reading of them (a 3000-user sweep: microseconds against milliseconds, and no garbage
against megabytes) is in
[Overlap at workload scale](../architecture/enumset-overlap-workload-summary.md).

## 7. Decision

**Adopted**: Use custom `EEnumSet*` and `EEnumSetBuilder*` classes with concrete dispatch for tracking arrays.

This strategy is **not required** for the metadata core; projects may use interface-based tracking
or alternative approaches. The strategy is **optional** and scoped to update tracking hot paths.

## 8. Implementation notes

See [EnumSet implementation path and JMH evidence](../architecture/enumset-implementation-and-jmh.md) for details on:
- class organization and storage variants
- JMH benchmark scope and runner usage
- inlining-oriented profile settings
- when to reassess this tradeoff

## 9. Related decisions

- [DEC-012](dec-012-update-array-and-change-tracking.md): Builder proxies backed by update arrays and change tracking
- [DEC-013](dec-013-factory-and-implementation-selection.md): Factory strategy for proxy and generated implementation selection
