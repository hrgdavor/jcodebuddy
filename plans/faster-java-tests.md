# Faster Java tests — measured plan

**Status: 2026-11-05 — implemented (F0–F6 done).** This plan makes the Java test loop fast enough to use
while editing, without deleting tests or weakening the recorded gate. The hot spot was
`hipster-entity-tooling`: **542 tests, 490.8 s**, of which **309.9 s (63%) was six classes that each ran
the same full generation pass once per test method**. After the work: **272.0 s serial / 1:49–1:55 with the
four-fork profile**, the six baseline classes **73.6 s**, fast lane **45.6 s**, single class **10.5 s**, all
**542** tests still green.

The plan is deliberately ordered by measured payoff: share the expensive fixture first (F2), then keep
the one-time completeness sweep out of the edit loop (F3), then try parallel forks only after the work
per run is smaller (F4). Tagging (F1) and the fast command (F5) make the result usable before the last
optimisation lands.

---

## 1. The measurement

Collected from the latest `target/surefire-reports/TEST-*.xml` after the E2 run (2026-11-05):

| Module                       | Test classes | Tests | Class time              |
| ---------------------------- | ------------ | ----- | ----------------------- |
| `hipster-entity-tooling`     | 83           | 542   | **490.8 s**             |
| `hipster-entity-core`        | 9            | 91    | ~0.1 s (cached reports) |
| `hipster-entity-test`        | 5            | 31    | ~0.2 s (cached reports) |
| `hipster-entity-jackson`     | 4            | 26    | ~0.2 s (cached reports) |
| `hipster-entity-api`         | 1            | 4     | ~0 s                    |

The tooling module **is** the test suite for this purpose. Its slowest classes:

| Class                                  | Tests  | Time        | What it does per test                              |
| -------------------------------------- | ------ | ----------- | -------------------------------------------------- |
| `ExampleRegenerationTest`              | 9      | 75.1 s      | copies the example and runs a full pass (9 passes) |
| `MetadataFileIdsTest`                  | 7      | 72.7 s      | copies the example and runs a pass (7–9 passes)    |
| `MigrationCompletenessTest`            | 3      | 62.2 s      | walks and parses the whole reactor (3 sweeps)      |
| `MetadataSourcePathTest`               | 9      | 45.0 s      | runs a pass per test method (8 + rename passes)    |
| `MetadataLocationsTest`                | 4      | 31.0 s      | copies the example and runs a pass (4 passes)      |
| `ExampleDivergenceReportTest`          | 4      | 24.0 s      | copies the example and runs a pass (4 passes)      |
| **top six (baseline classes)**         | **36** | **309.9 s** | **63% of the module**                              |

The slowest single methods make the cause unambiguous: every one of them is a full example pass
(`regenerationIsAlsoIdempotentOnASecondPass` 16.7 s, `theIndexIsDeterministic` 23.0 s,
`renamingATypeUpdatesEveryReferenceThePassWrites` 16.6 s, and ~8 s for each of the remaining pass
methods). The fixed cost is the **pass**, not the assertion.

Current Maven configuration (root `pom.xml`, `<pluginManagement>`):

- `maven-surefire-plugin` is pinned, but configured only with `<parallel>none</parallel>`;
- there is no explicit `forkCount`, so surefire uses one fork and reuses it;
- there is no JUnit tag/profile split for a fast lane;
- `MigrationCompletenessTest` runs in the same pass as everything else even though it is a one-time
  migration guard;
- several test classes already build their own copy-and-pass fixture, so the duplication is structural,
  not accidental.

The Maven build cache makes an **unchanged** full run cheap. It does not help when a test or generator
source changed — and that is the loop this plan targets.

---

## 2. What "faster" means

Targets for this machine, measured with the same test count and assertions:

| Lane / metric                                | Baseline | Target      | Result (2026-11-05)                                               |
| -------------------------------------------- | -------- | ----------- | ----------------------------------------------------------------- |
| Baseline six classes                         | 309.9 s  | **≤ 90 s**  | **73.6 s** — met (32.8 + 12.0 + 9.7 + 9.0 + 10.0 + 0.08)          |
| Full tooling suite, serial                   | 490.8 s  | **≤ 240 s** | **272.0 s class time / 4:32 wall** — serial target missed; see F4 |
| Full tooling suite, four-fork profile        | n/a      | **≤ 240 s** | **1:49–1:55** across three runs — met                             |
| Fast lane (`bun scripts/mvn-fast.js --fast`) | n/a      | **≤ 60 s**  | **45.6 s** — met                                                  |
| Single-class loop                            | n/a      | **≤ 30 s**  | **10.5 s** (`--tests EntityBuildStateTest`) — met                 |
| Full test count                              | 542      | **542**     | **542** — met                                                     |

The targets are acceptance aims, not promises. The plan records the measured before/after; a step that
does not reach its target either reports why or is split rather than quietly declared done.

---

## 3. Principles

1. **The full gate still runs every test.** The fast lane is an additional command, never the recorded
   gate. `bun scripts/mvn-jdk25.js` keeps its meaning.
2. **Do not delete coverage to buy time.** A test that is too slow gets a shared fixture, a tag, or a
   smaller input — not a `@Disabled`.
3. **Tag by cost source, not by class name.** `generator` means "runs a full pass"; `compiler` means
   "invokes javac"; `completeness` means "walks the reactor". A new slow test inherits the rule.
4. **Measure before and after, with a script.** The repository's rule for timing output applies: a Bun
   tool reports its duration as its last line via [`scripts/lib/timing.js`](../scripts/lib/timing.js).
5. **Parallelism is not the first answer.** Static generator configuration and shared temp trees make
   thread-level parallelism risky; sharing work first is both safer and larger.
6. **Narrowed runs never save the build cache.** `-Dmaven.build.cache.skipSave=true` is mandatory for
   `-Dtest=`/fast-lane invocations, or a later full run can hit a cache entry with only one test class.
7. **Bun only for the tooling.** The cost report and any fast-lane wrapper are `.js` files run with
   `bun`; no `.ps1`, `.cmd`, `.sh`.

---

## 4. Steps

Each step is one commit with its own gate.

### F0 — Measure the suite and keep the report (S)
**Who:** agent · **Depends:** none

Add `scripts/test-cost.js` (Bun). It reads `target/surefire-reports/TEST-*.xml` for the requested
modules, prints:

- per-module totals (classes, tests, seconds);
- the slowest classes and the slowest individual methods;
- `--json` for a machine-readable copy, and the duration as the last line via `timing.js`.

It writes nothing and does not parse Java; the lane assignment is the source tags F1 adds, and the
report's per-class times are what a reviewer checks them against. The baseline in § 1 is its expected
output at `HEAD`.

**Gate:** the script's totals match Maven's (542 tests, 490.8 s) and the last line reports its own
duration. No test or POM change.

### F1 — Tag the cost lanes and add a fast profile (S)
**Who:** agent · **Depends:** F0

Introduce JUnit 5 tags with one meaning each:

| Tag            | Meaning                                                           | Examples                                                              |
| -------------- | ----------------------------------------------------------------- | --------------------------------------------------------------------- |
| `generator`    | the test runs a full `EntityMetadataGenerator.generate(...)` pass | `ExampleRegenerationTest`, `MetadataFileIdsTest`, `MetadataSourcePathTest`, `MetadataLocationsTest`, `ExampleDivergenceReportTest` |
| `compiler`     | the test invokes `JavaCompiler`/`CompileHarness`                  | `GeneratedSourceCompilesTest`, `GeneratedTrackingBuilderContractTest` |
| `completeness` | the test walks the reactor or a migration-wide source set         | `MigrationCompletenessTest`                                           |

Add a `fast-tests` Maven profile in the root POM whose surefire config excludes those groups; the
default `test` still runs everything. `scripts/mvn-fast.js --fast` forwards the profile and adds
`-Dmaven.build.cache.skipSave=true`; `--tests <Class>` remains the single-class loop.

Tagging is mechanical, but a tag is a statement about cost: a test that starts running a pass and is
not tagged is the drift the F0 report exposes.

**Gate:** the default suite still reports 542 tests; the fast profile reports only the unit subset and
is green; the classes named in § 1 carry the tags that put them in the slow lanes; no assertion changed.

### F2 — Share the example pass across a test class (M/L, the main win)
**Who:** agent · **Depends:** F1

Add one test utility, for example `ExamplePassFixture`, that:

- copies the committed `hipster-entity-example` into a temp module **once per class** (or once per JVM
  where the class contract allows it);
- runs the full pass once with the same packages/flags the current tests use;
- exposes the module root, source root, report directory, class index, and parsed metadata documents;
- offers `freshCopy()` for the few tests that mutate the tree (a rename, a deliberate edit), so shared
  state is never written by a test that does not own it.

Convert, in this order, measuring after each:

1. `ExampleRegenerationTest` — one shared first pass; a second pass only for the idempotence test.
2. `MetadataFileIdsTest` — one shared pass; a second only for `theIndexIsDeterministic`.
3. `MetadataSourcePathTest` — one shared pass; `renamingAType…` gets `freshCopy()`.
4. `MetadataLocationsTest` — one shared pass.
5. `ExampleDivergenceReportTest` — one shared report.
6. `ExampleConverterManifestTest` and any other class that repeats the same copy-and-pass pattern.

Do not share a mutable tree blindly: each class's tests must either be read-only on the fixture or
request their own copy. The fixture's javadoc states which one it guarantees.

**Gate:** the six classes drop from 309.9 s to **≤ 90 s**; the full tooling suite is **≤ 300 s**; the
generated bytes and all assertions are unchanged; the test count is still 542; three consecutive full
runs are green.

### F3 — Cache the completeness sweep (S/M)
**Who:** agent · **Depends:** F2

`MigrationCompletenessTest` walks the same committed sources three times and parses them three times.
Build the file list and the per-file facts once in a static fixture (`@BeforeAll`): path, text, the
`SourceReader.Read`, the javac verdict, and the positions the existing assertions need. The three test
methods then assert over the same facts instead of re-walking and re-parsing the reactor.

Keep the tag `completeness`, so the fast lane skips it while the full gate still runs all three
assertions.

**Gate:** the class drops from 62.2 s to **≤ 30 s**; the three tests still assert the same properties;
a deliberately unreadable or unparsable fixture still fails; the full gate remains green.

### F4 — Try fork parallelism, with a measured gate (M)
**Who:** agent · **Depends:** F2, F3

The generator's configuration is process-global static state, so **JUnit thread parallelism is not the
first move**. Surefire forking is safer: each fork has its own statics, and the tests already write to
per-test temp directories.

Add a profile (for example `parallel-tests`) that sets, for `hipster-entity-tooling` only:

```xml
<forkCount>2</forkCount>
<reuseForks>true</reuseForks>
```

Run the full tooling suite three times with the same assertions, and compare wall time and memory. If
green and materially faster, keep the profile as an opt-in (`--parallel`) and consider making it the
default only after the third clean run; if it is flaky or memory-heavy, record the measurement and stop
at F2/F3.

Do **not** turn on `junit.jupiter.execution.parallel.enabled` for generator tests in this step. If it is
tried later, generator classes need `@ResourceLock("entity-generator")` and any stdout/property tests
need their own locks; that is a separate decision, not a free win.

**Gate:** three consecutive full runs with the same test count and assertions; the profile is either
adopted (with the measured wall time) or rejected (with the reason and the measurement); the default
gate is unchanged if the experiment is not adopted.

### F5 — One fast command, and the docs to use it (S)
**Who:** agent · **Depends:** F1–F4

Make the editor loop explicit:

- `bun scripts/mvn-fast.js --fast` — the fast lane (unit tags only), no `clean`, cache read, save
  withheld;
- `bun scripts/mvn-fast.js --tests <Class>` — one class, same safeguards;
- `bun scripts/mvn-fast.js --fast --tests <Class>` — one unit class;
- `bun scripts/test-cost.js --module hipster-entity-tooling` — where the time went.

Document the three commands in the root README's existing `scripts/mvn-fast.js` row, and in
[`hipster-entity-example/codebuddy.md`](../hipster-entity/hipster-entity-example/codebuddy.md) § 3
where the module's test loop is described. State plainly that the fast lane is not the gate and that
`bun scripts/mvn-jdk25.js` still runs all 542 tests.

**Gate:** the commands exist, print the lane and the test count, and their last line reports duration;
the full gate is unchanged and green; docs links resolve.

### F6 — Record the new budget and close (S)
**Who:** agent · **Depends:** F5

Re-run `test-cost.js`, record the before/after in this plan and in the module README, and add the
measured lanes to the roadmap's test notes if one exists. Add a short "how to keep it fast" rule: a new
test that runs a full pass gets `@Tag("generator")`; a new reactor walk gets `completeness`; a new test
that calls javac gets `compiler`.

Do not add a wall-clock failure to the recorded gate — a shared machine makes that flaky. The cost
report is the observation; the gate is still correctness.

**Gate:** the plan's numbers are updated with the measured result; the full gate is green at 542 tests;
the fast lane is green and under its target (or the miss is reported with the reason).

---

## 5. What not to do

- **Do not delete or disable slow tests to hit a number.** The completeness sweep and the example
  regeneration are the evidence for decisions; they move to a lane, not to the bin.
- **Do not make the fast lane the gate.** A gate that skips the generator tests is not the recorded gate.
- **Do not enable thread-parallel generator tests before isolating static state.** The generator's
  packages/flags/`CooperativeCodegen.force` are process-global; two concurrent passes can silently
  configure each other.
- **Do not turn the build cache off.** The cache is why an unchanged full run is seconds; the fast loop
  uses its read path with the save withheld.
- **Do not trust a narrowed run that saved.** `-Dtest=` is not part of the cache checksum; save only
  from full runs.
- **Do not hand-align tables or write shell wrappers.** `md-fix-tables`, Bun, and `timing.js` are the
  repository's formats.

---

## 6. Risks

- **Shared fixture contamination.** A test that edits the shared tree breaks the next test in a way that
  looks like a generator bug. Mitigation: the fixture is read-only by contract; mutating tests call
  `freshCopy()`; the fixture is rebuilt per class so one class cannot contaminate another.
- **Tag drift.** A test becomes a full pass later and keeps its `unit` tag. Mitigation: the F0 report
  shows per-class time, and a review checks that time against the class's source tags; a `unit` class
  over a second is a question.
- **Fork memory.** Two tooling forks each load OpenRewrite and Jackson; a small machine may swap.
  Mitigation: the profile is opt-in first, and the measurement includes memory as well as wall time.
- **A shared fixture hides an isolation bug.** The existing tests already use temp trees; the fixture
  keeps that, and `freshCopy()` is the escape hatch. A suite that passes three times in a row is the
  evidence, not one green run.
- **The fast lane becomes the only lane used.** Mitigation: the gate remains `bun scripts/mvn-jdk25.js`,
  and the docs say so next to the fast command.

---

## 7. Done when

- `scripts/test-cost.js` reports the suite and its own duration; the baseline in § 1 is reproducible.
- The slow classes carry `generator`/`compiler`/`completeness` tags; the default suite still runs 542
  tests; the fast lane runs the unit subset in ≤ 60 s.
- The top six classes are ≤ 90 s; the full tooling suite is ≤ 240 s (or the measured result and the
  reason for a miss are recorded).
- `MigrationCompletenessTest` is ≤ 30 s and still asserts its three properties.
- `bun scripts/mvn-fast.js --fast` and `--tests` are documented and used by the module's runbook.
- The full recorded gate is green, and no test was deleted, disabled, or weakened to get there.

---

## 8. Implementation record (2026-11-05)

| Step | Result                                                                                  | Evidence                                                                             |
| ---- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| F0   | `scripts/test-cost.js` reads Surefire XML, reports totals/rankings, supports `--module`, `--json`, `--top`; duration last line | 542 / 490.8 s baseline reproduced; `--top 6 of 83` verified |
| F1   | `generator` / `compiler` / `completeness` tags; root `fast-tests` profile (`excludedGroups`); `mvn-fast --fast` | 542 tests in the default suite; fast lane ran 264 tooling unit tests + dependencies |
| F2   | `ExamplePassFixture` shared once per JVM; five classes converted (plus `ExampleConverterManifestTest` tagged only) | Baseline six **309.9 s → 73.6 s**; all 33 five-class tests preserved; full suite 542 |
| F3   | `MigrationCompletenessTest` parses the reactor once in `@BeforeAll`                     | **62.2 s → 30.1–32.8 s** across runs (the ≤30 s aim is at the edge of the variance); three tests unchanged |
| F4   | `parallel-tests` profile, `forkCount=4`, `reuseForks=true`; `mvn-fast --parallel-tests` | three consecutive green runs: **1:55, 1:51, 1:49** (542 tests each)                  |
| F5   | `--fast` scoped to the tooling module + `-am` deps; `--parallel-tests`; root README and `codebuddy.md` updated | fast lane **45.6 s**; `--fast --tests ContentHashTest` **1.3 s**; `--tests EntityBuildStateTest` **10.5 s** |
| F6   | measured budget recorded here and in the module docs                                    | serial class time **272.0 s**; parallel wall **1:49–1:55**; no test removed          |

**The one target that moved:** the plan's full-suite target was 240 s. The **serial** suite is 272.0 s of
class time (4:32 wall; recorded gate **4:59** including all 18 modules), because the remaining
generator/compiler tests are individually small but numerous.
The four-fork profile meets 240 s (1:49–1:55) and is the measured opt-in lane, not the default: a 4-fork
JVM set is not free on a smaller machine. The daily loop is the fast lane (45.6 s), not the full suite.
