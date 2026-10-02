# Plan: JMH benchmark for `EEnumSet` — agent service-overlap use case

**Status (2026-10-01):** implemented. The benchmark, shared fixtures, and parity test live in
`hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/`; the parity gate is green, and the
decision-grade JMH results are recorded in
`doc-hipster-entity/architecture/enumset-implementation-and-jmh.md`.

**Audience:** an agent (or developer) implementing the benchmark in this repository.

**Mission:** measure, with decision-grade JMH evidence, how the `EEnumSet` overlap check
(`session.hasAny(agent)`) compares against the production overlap filter the user identified as
inefficient — a per-agent `new HashSet<>(ids)` + `retainAll(sessionSet)` + `isEmpty()` over boxed
`Long` ids — in a **POJO-free** model: plain sets of service ids, nothing else.

---

## 1. Use case and modelling

### 1.1 The production pattern (baseline to beat)

The code under scrutiny (from the driver project) is:

```java
final Set<Long> sessionServiceIdsSet = new HashSet<>(sessionServiceIds);
for (Agent agent : agents.values()) {
    if (!agent.getOrgid().equals(organizationId)) continue;
    if (ViewLevel.ALL == viewLevel) { ret.add(agent); continue; }
    final Set<Long> agentServiceIdsSet = new HashSet<>(agent.getServiceIds());
    agentServiceIdsSet.retainAll(sessionServiceIdsSet);
    if (!agentServiceIdsSet.isEmpty()) ret.add(agent);
}
```

The costs that matter:

- one `HashSet` allocation (plus its internal `HashMap` and node array) **per agent per call**;
- `O(|agent ids|)` hash probes per agent inside `retainAll`;
- `Long` boxing throughout the id path.

The orgid equality filter and the `ViewLevel.ALL` shortcut are **orthogonal to the set-overlap
cost** (the `ALL` path is a trivial `continue`; the orgid test is a single comparison) and are
deliberately dropped from the benchmark model, per the user's instruction to keep only
"sets of ids". The benchmark measures exactly the part the user flagged: *does this agent share at
least one service id with the session, and what does that check cost per agent?*

### 1.2 The benchmark model (no POJOs)

- **Universe.** Service ids are modelled as ordinals of a bounded enum universe
  (`Service64`, `Service96`, `Service256` — and optionally `Service128`). Ordinal = registry slot;
  the enum is the service registry.
- **Inputs.** One *session* set of ordinals and N *agent* sets of ordinals (N = 1 for the
  per-agent benchmarks, N = 100 or 1000 for the batch benchmark).
- **Operation.** "Does agent *i* overlap the session?" — answered by
  `session.hasAny(agentSet)` for the `EEnumSet` side, or by the production-shaped
  `new HashSet<>(ids); set.retainAll(sessionIds); !set.isEmpty()` for the baseline side.
- **Batch operation.** count how many of N agents overlap — the realistic workload shape.

### 1.3 Explicit modelling assumption (must be stated in the final evidence doc)

`EEnumSet` is enum-ordinal based: it only applies when the service-id space is a **bounded, known
registry**. The production `Set<Long>` ids map onto that model when service ids are surrogate keys
into a fixed registry (true in this domain: services are registered entities). If the real id
space were unbounded arbitrary `Long`s, `EEnumSet` would not transfer and the honest comparison
would be against a compressed-id `BitSet`/`long[]` instead. The `Long`-based baseline is kept in
the benchmark so the relative-cost story holds in either interpretation, and the evidence doc must
state which interpretation the numbers answer.

---

## 2. What to measure

### 2.1 Implementations compared (per-agent check)

| # | Benchmark method | Implementation | Allocation per iteration |
|---|------------------|----------------|--------------------------|
| 1 | `overlapHashSet*` | `new HashSet<>(agentIds)` + `retainAll(sessionIds)` + `!isEmpty()` — verbatim production shape | `HashSet` + internal map |
| 2 | `overlapEnumSet*` | `EnumSet.noneOf(...)` + `addAll(agentEnumSet)` + `retainAll(sessionEnumSet)` + `!isEmpty()` | 1–2 collections |
| 3 | `overlapBitSet*` | `agentBitSet.clone()` + `and(sessionBitSet)` + `!isEmpty()` | one `long[⌈u/64⌉]` |
| 4 | `overlapRawLong` | `(sessionBits & agentBits) != 0` — theoretical floor | 0 |
| 5 | `overlapEEnumSet*` | `session.hasAny(agentSet)` — both sides immutable `EEnumSet` snapshots | **0** |
| 6 | `overlapEEnumSetBuilder*` | `session.hasAny(agentBuilder)` — agent side is a live `EEnumSetBuilder` (the mutable tracking-state shape from DEC-012) | **0** |

`*` = width suffix (`64`, `96`, `256`).

### 2.2 Axes

- **Width:** 64, 96, 256 (optionally 128). 64 = one `long`; 96 and 128 = two segments; 256 =
  four segments. Width is encoded in the **method name**, matching the existing
  `EEnumSetJmhBenchmark` convention (`hasAny64` / `hasAny96`), with one `@State` class per width.
- **Density** (`@Param`): 0.05 / 0.25 / 0.5 / 0.9 — fraction of the universe present in each set.
- **Overlap present vs absent:**
  - *typical* — seeded random fixture; both `true` and `false` outcomes occur naturally;
  - *guaranteed-disjoint* — session = lower half of the universe, agent = upper half. This is the
    worst case for **both** sides: `hasAny` scans every segment with zero AND result, and the
    `HashSet` baseline probes every key with no hit (no early exit).
- **Batch** (`@Param agents` ∈ {100, 1000}): `filterAgents*` / `filterAgentsHashSet*` loop over
  all agents and count overlaps (EEnumSet path vs production-shape path only).

### 2.3 Method matrix

- **64:** `overlapHashSet64`, `overlapEnumSet64`, `overlapBitSet64`, `overlapRawLong64`,
  `overlapEEnumSet64`, `overlapEEnumSetBuilder64`, `disjointHashSet64`, `disjointEEnumSet64`,
  `filterAgents64`, `filterAgentsHashSet64` (10 methods)
- **96 / 256:** `overlapEEnumSet*`, `overlapHashSet*`, `overlapBitSet*`, `disjointEEnumSet*`,
  `disjointHashSet*` (5 each)
- **256 additionally:** `overlapEEnumSetBuilder256` (1)
- **Optional if runtime budget is tight:** the 128 group.

Total ≈ 21 benchmark methods; at the runner's decision-grade profile (3 forks, 6×2 s warmup,
8×2 s measurement) that is roughly 20–30 minutes wall clock. Start with the full matrix; trim
only if the run becomes a problem.

---

## 3. Fixture and state design

### 3.1 State

One `@State(Scope.Thread)` class per width (e.g. `Overlap64State`), each with
`@Param("density")` and `@Param("agents")`. `@Setup` (trial level — no per-iteration mutation is
needed; `hasAny` is read-only) builds **every representation from one shared deterministic
fixture** (fixed seed, e.g. `SplittableRandom 0x5EED`) so results are reproducible across
machines and runs:

- `int[] sessionOrdinals`, `int[][] agentOrdinals` — distinct ordinals, size
  `max(1, ⌊density·u⌋)`;
- from those, per agent: immutable `EEnumSet` (via
  `EEnumSetBuilder.create(Enum).addAll(...).toImmutable()`), live `EEnumSetBuilder`,
  `EnumSet`, `BitSet`, raw `long[]` / `long`, and `List<Long>` ids (id = ordinal, the same value
  in every representation, so all implementations see the same sets);
- session-side equivalents (`EEnumSet`, `Set<Long>`, `EnumSet`, `BitSet`, `long`/`long[]`);
- the disjoint pair: session = first `⌊u/2⌋` ordinals, agent = last `⌊u/2⌋`.

### 3.2 Correctness guards (JMH does not verify correctness)

1. **In-state parity assertion** in `@Setup`: for every fixture agent, assert that all
   representations agree on the overlap verdict against the session; throw on mismatch so a
   divergent implementation fails the run instead of producing a misleading number.
2. **Separate JUnit parity test** — `EEnumSetOverlapParityTest` (not JMH): random fixtures over
   all widths and densities asserting `hasAny` agrees with the `HashSet` baseline and with a
   brute-force ordinal scan. It runs in the normal gate, so correctness is enforced continuously,
   not only when someone happens to run the benchmark.

---

## 4. Where the code lives — and the runner gap (read before implementing)

### 4.1 New files

- `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetOverlapJmhBenchmark.java`
  — next to the existing `EEnumSetJmhBenchmark` and `EEnumSetTrackingJmhBenchmark` (same package,
  so package-private constructors such as `EEnumSetBuilder64(E[], long, int)` are reachable if
  needed). Enum fixtures (`Service64`/`Service96`/`Service256`) live in the benchmark file itself or
  a sibling test fixture file; they are **ordinary committed source** — no generation hidden
  behind a processor (AGENTS.md §1). If hand-writing 256 enum constants is too verbose, a small
  Bun script may emit them, but the output is committed and regenerable.
- `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetOverlapParityTest.java`

### 4.2 The runner gap (verified against the current tree)

`scripts/run-jmh.js` compiles **only** `hipster-entity-test` and `hipster-entity-jackson`
(`-pl hipster-entity-test` in both the bootstrap `install` and the `clean test-compile` steps).
It declares `coreDir` (line 8) but never uses it. Consequences:

1. The two existing EEnumSet benchmarks in `hipster-entity/hipster-entity-core/src/test` are **not compiled or
   run** by the current runner, even though
   [`doc-hipster-entity/architecture/enumset-implementation-and-jmh.md`](../doc-hipster-entity/architecture/enumset-implementation-and-jmh.md)
   documents `bun run scripts/run-jmh.js --include ".*EEnumSetJmhBenchmark.*"` as the command.
2. `hipster-entity-test` has **no `jmh` profile**: its `jmh-generator-annprocess` test dependency
   gets no `-proc:full`. The root POM sets `maven.compiler.release=25`, and on JDK 23+
   classpath-discovered annotation processors do not run unless explicitly requested — so under
   the required JDK 25 no JMH `*_jmhTest` harness classes are generated for the test module.
   (`hipster-entity/hipster-entity-core/pom.xml` and `hipster-entity/hipster-entity-tooling/pom.xml` both carry a `jmh` profile
   with `-proc:full` precisely for this reason.)

Therefore the plan **includes a small, mechanical extension of `scripts/run-jmh.js`** so the new
benchmark is runnable through the standard entry point:

1. **Bootstrap + compile steps:** add `hipster-entity-core` (and its sibling deps as needed, e.g.
   `hipster-entity-api`) to the `-pl` lists, with `-Pjmh` so the core JMH profile activates
   (`jmh-generator-annprocess` + `-proc:full`). Confirm the exact `-pl` list against the reactor
   at implementation time.
2. **Classpath:** add `hipster-entity/hipster-entity-core/target/test-classes` and
   `hipster-entity/hipster-entity-core/target/classes` to the classpath entries (the core jars must be
   installed in the local repo — the extended bootstrap install covers that).
3. **Generated sources:** add `hipster-entity/hipster-entity-core/target/generated-test-sources/test-annotations`
   to the `collectJavaFiles` collection (the helper already exists; the script already collects
   from the jackson and test module directories).
4. **Stay Bun JavaScript** (AGENTS.md §2): extend the existing script; do not add a shell script
   and do not change the jackson/test benchmark behaviour — a jackson benchmark that runs before
   the change must still run after it.

### 4.3 JDK requirement

The runner picks `java`/`javac` from `JAVA_HOME`/PATH. The modules compile with `--release 25`,
so the run must be started with `JAVA_HOME=C:\Program Files\Java\jdk-25` (the current checkout
`JAVA_HOME` points at `jdk-21`, which cannot build `release 25`). The documented command in §6
sets this explicitly.

---

## 5. Benchmark class sketch

```java
package hr.hrg.hipster.entity.core;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)   // same convention as EEnumSetJmhBenchmark
public class EEnumSetOverlapJmhBenchmark {

    enum Service64  { /* 64 constants */ }
    enum Service96  { /* 96 constants */ }
    enum Service256 { /* 256 constants */ }

    @State(Scope.Thread)
    public static class Overlap64State {
        @Param("density") String density;
        @Param("agents")  String agents;

        EEnumSet<Service64> session;
        EEnumSet<Service64>[] agentSets;
        EEnumSetBuilder<Service64>[] agentBuilders;
        List<Long>[] agentIds;
        Set<Long> sessionIds;
        EnumSet<Service64> sessionEnumSet;
        EnumSet<Service64>[] agentEnumSets;
        BitSet sessionBitSet;
        BitSet[] agentBitSets;
        long sessionBits;
        long[] agentBits;
        EEnumSet<Service64> disjointAgent;
        List<Long> disjointAgentIds;

        @Setup
        public void setup() {
            // build every representation from the seeded fixture (density, agents)
            // parity-assert all representations against each other (throw on mismatch)
        }
    }
    // Overlap96State, Overlap256State analogous

    @Benchmark
    public boolean overlapHashSet64(Overlap64State s) {
        Set<Long> set = new HashSet<>(s.agentIds[0]);
        set.retainAll(s.sessionIds);
        return !set.isEmpty();
    }

    @Benchmark
    public boolean overlapEEnumSet64(Overlap64State s) {
        return s.session.hasAny(s.agentSets[0]);
    }
    // ... remaining methods per the matrix in §2.3

    @Benchmark
    public int filterAgents64(Overlap64State s) {
        int count = 0;
        for (EEnumSet<Service64> a : s.agentSets) {
            if (s.session.hasAny(a)) count++;
        }
        return count;
    }
}
```

Notes:

- Every benchmark method **returns its result** (boolean or count) so the JIT cannot eliminate
  the work.
- `agentIds` is a `List<Long>` as stored by the production `Agent.getServiceIds()`; the boxing
  happens at list construction, not in the hot loop — that is the faithful production shape.
- The baseline `retainAll` mutates the per-iteration copy — correct, it mirrors production.
- No `@Setup(Level.Iteration)` is needed: `hasAny` and the baselines' inputs are all prebuilt
  trial-level state.

---

## 6. Running it

1. **Gate: compile + parity test**

   ```
   bun scripts/mvn-jdk25.js hipster-entity-core test
   ```

   (Surefire already excludes `**/jmh_generated/**` and `**/*_jmhTest.class`, so the JMH
   benchmark class is compiled but not run by the gate; the parity test runs normally.)

2. **Benchmark run** (after the runner extension in §4.2):

   ```
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
   bun run scripts/run-jmh.js --include ".*EEnumSetOverlapJmhBenchmark.*"
   ```

   - Decision-grade profile defaults: 3 forks, 6×2 s warmup, 8×2 s measurement (the runner
     warns when these are lowered — use the defaults for anything recorded as evidence).
   - Output: printed summary table + `target/jmh/results.json`. The class name lands in the
     runner's "SetCompare" summary group (it does not contain "Tracking").
   - Smoke option while iterating: `--forks 2 --warmup-iterations 4 --warmup-time 1s
     --measurement-iterations 4 --measurement-time 1s` — for feel only, never for recorded
     evidence.

3. **Post-run:** update
   [`doc-hipster-entity/architecture/enumset-implementation-and-jmh.md`](../doc-hipster-entity/architecture/enumset-implementation-and-jmh.md)
   — add the benchmark class to its related-benchmark list, extend the "Current evidence summary"
   with the new numbers (including the §1.3 modelling-interpretation statement), and, if the
   decision is adopted, point to it from the DEC-014 related-decisions section.

---

## 7. Interpretation and acceptance criteria

Hypotheses the run must confirm or refute:

1. **Per-agent check, 64-wide:** `overlapEEnumSet64` is orders of magnitude faster than
   `overlapHashSet64` (expected 1–2 orders: the baseline allocates a `HashSet` and does
   `O(|ids|)` hash probes; the EEnumSet path is one `AND` on a single `long`).
2. **Zero allocation:** the EEnumSet rows show **0 GC ops/iteration** in the JMH gc metrics; the
   baseline rows show GC proportional to their allocations. This is the headline result.
3. **Floor proximity:** `overlapEEnumSet64` is within ~2–3× of `overlapRawLong64` (the
   `instanceof`-dispatch and enum-class checks account for the residual gap).
4. **Disjoint worst case:** the EEnumSet penalty stays small (a full segment scan = `u/64`
   `AND`s); the `HashSet` baseline is worst (every key probed, no hit, no early exit).
5. **Width scaling:** at 256 the EEnumSet advantage shrinks (4-segment scan) while the baseline
   cost grows with density; record the crossover density if one exists.

**Decision rule:** if `EEnumSet` wins across the realistic density mix with zero per-iteration
allocation, document that the session↔agent service-overlap check is replaceable by
precomputed immutable `EEnumSet` snapshots (session set built once per session; agent sets
immutable snapshots) with `session.hasAny(agentSet)` as the per-agent predicate — the direct
successor of the production `HashSet` + `retainAll` pattern. If a baseline matches or beats it,
record that and let the benchmark falsify the use case; the evidence doc must say which way the
evidence went.

---

## 8. Deliverables

1. `plans/enumset-overlap-jmh-plan.md` — this document.
2. `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetOverlapJmhBenchmark.java`
3. `hipster-entity/hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EEnumSetOverlapParityTest.java`
4. `scripts/run-jmh.js` — additive extension for `hipster-entity-core` (§4.2).
5. Post-run update of
   [`doc-hipster-entity/architecture/enumset-implementation-and-jmh.md`](../doc-hipster-entity/architecture/enumset-implementation-and-jmh.md)
   (evidence summary + decision guidance).

## 9. Risks and pitfalls

- **Dead-code elimination:** benchmark methods must return their result (§5).
- **Fixture parity:** the ordinal↔`Long` mapping bug would silently make baselines and EEnumSet
  disagree; the in-state assertion (§3.2) and the parity test catch it.
- **Don't "help" the baseline:** the `Long` boxing and per-iteration allocation are the point of
  the comparison; keeping the baseline faithful is what makes the numbers mean something.
- **JDK mismatch:** a run started under `jdk-21` (the current `JAVA_HOME`) fails at compile time
  (`--release 25`); the documented command pins `jdk-25`.
- **Runner extension blast radius:** keep the `run-jmh.js` change additive; verify a pre-existing
  benchmark still runs after the change.
- **Enum fixture verbosity:** 256 constants by hand is long; if a generator script is used, its
  output is committed source (AGENTS.md §1), not build-time magic.
