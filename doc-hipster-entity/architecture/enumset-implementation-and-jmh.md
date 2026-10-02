# EnumSet Implementation Path and JMH Evidence

This document is an implementation guide for the custom enum-bitset structures used for change tracking in proxy and builder workflows.

For the decision rationale, tradeoffs, and measured evidence, see [DEC-014](../brainstorm/dec-014-enumset-concrete-dispatch-strategy.md).

For what these measurements mean at workload scale — a sweep over 3000 users, in time and in garbage
per hour — see [Overlap at workload scale](enumset-overlap-workload-summary.md).

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
- session/agent service-ID **overlap** (`hasAny`) against `HashSet`/`BitSet`/raw-`long` baselines, at enum widths 64, 96 and 256, across density and agent-count axes

Relevant benchmark classes:

- `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetJmhBenchmark.java`
- `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetTrackingJmhBenchmark.java`
- `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetOverlapJmhBenchmark.java`

Fixtures and parity tests for the overlap benchmarks:

- `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetOverlapFixtures.java`
- `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetOverlapParityTest.java`

Runner:

- `scripts/run-jmh.js`

## Quick local commands

- `bun run scripts/run-jmh.js --include ".*EEnumSetJmhBenchmark.*"`
- `bun run scripts/run-jmh.js --include ".*EEnumSetTrackingJmhBenchmark.*"`
- `bun run scripts/run-jmh.js --include ".*EEnumSetOverlapJmhBenchmark.*" -p density=0.05,0.25,0.5,0.9 -p agents=100,1000`
- `mvnd -pl hipster-entity-core test` (unit test + smoke check)

The overlap benchmark parameters are explicit: without `-p` values JMH 1.37 injects the parameter *key name* as the string (e.g. `"density"`), and `setup()` fails with `NumberFormatException`. Every invocation must pass `-p density=... -p agents=...`.

`run-jmh.js` derives its `java` and `javac` from `JAVA_HOME`, and it recompiles the JMH-generated sources itself. The gate compiles at release 25, so a `JAVA_HOME` on an older JDK breaks that recompile step with
`bad class file: ... class file has wrong version 69.0, should be 65.0` (69.0 = Java 25, 65.0 = Java 21) and then cascades into hundreds of `cannot find symbol` errors in the generated `_jmhTest` sources. Set `JAVA_HOME` to the JDK the gate uses before invoking the runner; on Windows also note that `java` on `PATH` may be an unrelated JDK (here: 8), which is why every command in this document goes through `JAVA_HOME`-derived executables or an absolute path.

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

### Decision-grade: service-ID overlap (`EEnumSetOverlapJmhBenchmark`)

The use case is a session holding a service-ID set and many agents each holding one; the operation under
test is "which agents overlap this session", i.e. `EEnumSetRead.hasAny` against one candidate set. The
benchmarks keep the agent set in place and vary two axes:

- `density` — probability that a given service ID is present in the agent's set (`0.05`, `0.25`, `0.5`, `0.9`)
- `agents` — batch size for the whole-corpus methods (`100`, `1000`)

Everything below comes from one full run of the decision-grade profile, with this shape:

| Setting     | Value                                       |
| ----------- | ------------------------------------------- |
| JMH         | 1.37                                        |
| JDK         | 25.0.3+9-LTS-195 (HotSpot 64-Bit Server VM) |
| JVM args    | `-Xmx4g`                                    |
| Forks       | 3                                           |
| Warmup      | 6 iterations × 2 s                          |
| Measurement | 8 iterations × 2 s                          |
| Threads     | 1 (synchronized iterations)                 |
| Mode        | Throughput (`ops/ms`)                       |
| Variants    | 76                                          |

Re-run it with:

```bash
bun run scripts/run-jmh.js --include ".*EEnumSetOverlapJmhBenchmark.*" -p density=0.05,0.25,0.5,0.9 -p agents=100,1000
```

Provenance: the runner writes decision-grade output to `target/jmh/results.json` and short-profile output
to `target/jmh/results-smoke.json`, so a smoke run cannot overwrite these numbers. `target/` is scratch,
so the tables below are the durable record; the run's console capture (with per-variant error bars) is
kept at `target/jmh/overlap-full2.log` and the allocation/GC probe at `target/jmh/gc-probe.log`.

#### Pairwise overlap, single agent set (`ops/ms`)

| Benchmark                   | 0.05      | 0.25      | 0.5       | 0.9       |
| --------------------------- | --------- | --------- | --------- | --------- |
| `overlapEEnumSet64`         | 869,543   | 826,517   | 719,214   | 859,759   |
| `overlapEEnumSet96`         | 610,970   | 616,510   | 623,629   | 617,329   |
| `overlapEEnumSet256`        | 304,090 † | 608,114   | 604,264   | 614,204   |
| `overlapEEnumSetBuilder64`  | 828,548   | 836,466   | 804,305   | 816,442   |
| `overlapEEnumSetBuilder256` | 300,070 † | 625,741   | 617,127   | 615,602   |
| `overlapRawLong64`          | 1,752,893 | 1,762,388 | 1,760,516 | 1,779,034 |
| `overlapBitSet96`           | 80,123    | 79,975    | 83,392    | 86,074    |
| `overlapBitSet256`          | 75,800    | 82,873    | 82,498    | 81,306    |
| `overlapHashSet64`          | 13,853    | 3,156     | 1,845     | 1,164     |
| `overlapHashSet96`          | 10,653    | 2,182     | 1,249     | 844       |
| `overlapHashSet256`         | 4,522     | 942       | 485       | 285       |

† Both width-256 EEnumSet rows read roughly half their neighbours at `density=0.05` and match them
everywhere else. This is a real measurement, not a transcription slip, and it is left visible rather
than smoothed: a nearly-empty 256-wide set takes a different branch profile than a populated one, and
the point needs re-measurement before it is quoted. It does not change any conclusion — even the low
point beats every `HashSet` row at the same density: ~22× against `overlapHashSet64` (its narrowest
margin) and ~67× against `overlapHashSet256`.

#### Disjoint sets, worst case for the primitive (`ops/ms`)

| Benchmark             | 0.05      | 0.25      | 0.5       | 0.9       |
| --------------------- | --------- | --------- | --------- | --------- |
| `disjointEEnumSet64`  | 1,124,064 | 1,104,362 | 1,098,006 | 1,093,059 |
| `disjointEEnumSet256` | 330,039   | 313,422   | 321,583   | 326,029   |
| `disjointHashSet64`   | 1,939     | 2,040     | 2,144     | 1,958     |
| `disjointHashSet256`  | 567       | 509       | 540       | 564       |

`disjoint*` is the pathological case for `hasAny` — the scan has to visit everything before it can
answer "no". Both EEnumSet rows stay within ±3% of their mean across the whole density axis, so the worst
case costs essentially nothing over the best case.

#### Whole-corpus filtering, the actual use case (`ops/ms`)

| Benchmark               | agents | 0.05   | 0.25   | 0.5    | 0.9    |
| ----------------------- | ------ | ------ | ------ | ------ | ------ |
| `filterAgents64`        | 100    | 15,201 | 18,321 | 20,338 | 20,468 |
| `filterAgentsHashSet64` | 100    | 135.3  | 31.3   | 17.6   | 10.6   |
| `filterAgents64`        | 1000   | 1,009  | 1,000  | 1,355  | 1,543  |
| `filterAgentsHashSet64` | 1000   | 11.8   | 2.13   | 1.25   | 1.07   |

This is the shape the use case is actually about: one session set tested against every agent set in
turn. EEnumSet gets *faster* as density rises (more early exits), while the `HashSet` baseline gets
*slower* because `retainAll` materializes an intersection proportional to the overlap.

#### Allocation and GC

`-prof gc`, `density=0.5`, 1 fork × 5 × 1 s (allocation behaviour is structural, so the reduced
profile is sufficient for it; the throughput column of this probe is not decision-grade):

| Benchmark           | `gc.alloc.rate.norm` | `gc.alloc.rate`  | `gc.count` | `gc.time` |
| ------------------- | -------------------- | ---------------- | ---------- | --------- |
| `overlapEEnumSet64` | ≈ 10⁻⁵ B/op          | 0.007 MB/sec     | ≈ 0 counts | —         |
| `overlapHashSet64`  | 1,376.004 B/op       | 2,145.761 MB/sec | 18 counts  | 22 ms     |

The EEnumSet path is allocation-free in steady state: bitwise AND over `long` fields with no boxing and
no iterator. The `HashSet` path allocates ~1.4 KB per operation and collects ~18 times per second-long
iteration, spending ~22 ms of each second in GC. On a service that filters agent sets on every request
this is the difference between "no GC pressure" and "GC pressure proportional to request rate".

#### Headline ratios

| Comparison                                                    | Ratio                        |
| ------------------------------------------------------------- | ---------------------------- |
| `overlapEEnumSet64` vs `overlapHashSet64` @0.5                | ~390×                        |
| `overlapEEnumSet64` vs `overlapHashSet64` @0.9                | ~739×                        |
| `overlapEEnumSet256` vs `overlapHashSet256` @0.5              | ~1,246×                      |
| `disjointEEnumSet64` vs `disjointHashSet64` @0.5              | ~512×                        |
| `disjointEEnumSet256` vs `disjointHashSet256` @0.5            | ~596×                        |
| `filterAgents64` vs `filterAgentsHashSet64`, 100 agents @0.9  | ~1,933×                      |
| `filterAgents64` vs `filterAgentsHashSet64`, 1000 agents @0.9 | ~1,444×                      |
| `overlapEEnumSet96` vs `overlapBitSet96` @0.5                 | ~7.5×                        |
| `overlapRawLong64` vs `overlapEEnumSet64` @0.5                | ~2.5× (raw `long` is faster) |

Interpretation:

- **The overlap use case is genuine.** `hasAny` against a bitset beats the `HashSet` shape by two to
  three orders of magnitude, and the advantage grows with density — the regime where a real service
  registry lives, since an agent typically serves a handful of a large ID universe.
- **The cost is predictable.** EEnumSet is flat across the density axis; the baselines are not. Capacity
  planning for the bitset path does not need a density assumption.
- **Width costs what the scan has to walk, not what the type declares.** On the pairwise path at
  `density=0.5`, widths 96 and 256 both sit ~15% below width 64 (~624k and ~604k against ~719k) and
  within ~3% of each other, because `hasAny` returns on the first matching segment. With no early exit
  (`disjoint*`, width 256) the whole set is scanned and throughput falls to ~322k against width 64's
  ~1,098k — a ~3.4× step, four `long` segments instead of one. That is the honest cost model: the width
  penalty tracks how far the scan runs before it can answer.
- **The raw-`long` row is the honest upper bound.** `EEnumSet64` gives up ~2.5× against a hand-rolled
  `long` mask, and buys the type safety, the width-agnostic interface, immutability and the shared read
  API for it. That is the trade, stated plainly.
- **`Builder64` tracks the immutable read path** (~800–836k ops/ms), so building-and-testing is not a
  separate performance regime from testing a snapshot.

Modelling assumption, stated because it bounds where these numbers apply: EEnumSet requires the service
IDs to come from a bounded, known universe that can be assigned ordinals. The `HashSet`, `BitSet` and
raw-`long` baselines are kept in the benchmark for exactly that reason — a `long`-ID system can read the
`rawLong64` and `BitSet` rows to size the same work without an enum, and the comparison against those
rows holds either way.

### Directional: update tracking (`EEnumSetTrackingJmhBenchmark`)

Directional results from the tracking suite show:

- `markUnmark*` paths generally benefit from concrete dispatch
- `clear*` paths are mixed by variant and width (64 vs 96)
- snapshot paths are largely dominated by allocation and conversion cost

These runs used short smoke settings and are included for shape only, not as decision-grade evidence.

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