# Incremental unit-test selection — a test dependency index over `fs-meta`

**Status: 2026-11-06 — T0–T2 done; T3 next.** The policy is now recorded in
[`AGENTS.md`](../AGENTS.md) § 2: hipster-entity work defaults to the affected/fast lane, and the full suite is
run only on request. This plan builds the selector that makes "affected" precise. It does not replace the
fast lane or the gate: it adds a narrower, dependency-aware layer for the tests that are genuinely local.

The problem is not "tests are slow" any more ([`faster-java-tests.md`](faster-java-tests.md) cut the
baseline six classes from 309.9 s to 73.6 s). The remaining problem is that the fast lane still runs every
unit test in the module, whether or not the class under it changed. A developer editing `ContentHash`
should not re-run the parser and metadata tests.

---

## 1. The shape we want

```
edited files (fs-meta hashes)
        │
        ▼
  class index (FQN → file, checksum)
        │
        ▼
  test index (test → production/test dependency closure)
        │
        ▼
  affected unit tests ──► mvn-fast --fast --tests A,B,C
        │
        ▼
  successful run publishes the new fs-meta state
```

Three things make this trustworthy rather than another selector that guesses:

1. **A test's dependencies are measured, not declared by hand.** A generated test index reads the
   compiled test class's constant pool and records every project class it names.
2. **The unit lane is a contract, not a vibe.** A test may call itself `unit` only if it directly depends
   on at most **three production classes**, does not use the generator/compiler/completeness surfaces,
   and does not read external files or resources. A contract test enforces it.
3. **The change set is the same one the build uses.** `fs-meta` hashes decide what changed; a state file
   records the last successful test run; a no-op means no test process starts.

---

## 2. Current inventory

Measured on 2026-11-06 by `bun scripts/test-lanes.js` (83 test classes; the plan-time tree was 78 —
five test classes landed with the MVP's E3–E6 work). **The direct-dependency distribution in the earlier
revision of this section could not be reproduced**, so it is replaced by the probe's measured values; the
plan-time column is the same rule over the 78-class tree, and confirms the totals are right even though the
old buckets were not.

- **83 test classes**, **542 test methods**; **52** carry a heavy tag (`generator` 28, `compiler` 23,
  `completeness` 1). **11** classes / **94** methods meet the `unit` rule; **20** classes / **176** methods
  are `component`; **0** are unclassified.
- Direct project-class references per test class (measured from the constant pool, nested types folded to
  their top-level class):

| Direct project deps | Test classes (83) | Plan-time 78 |
| ------------------- | ----------------- | ------------ |
| 0                   | 5                 | 4            |
| 1                   | 28                | 26           |
| 2                   | 23                | 21           |
| 3                   | 13                | 13           |
| 4–5                 | 12                | 12           |
| 6+                  | 2                 | 2            |

The low-dependency band is the unit candidate pool, but it is **not** the unit lane by itself: **42 of the
52 heavy-tagged classes have ≤3 direct production deps**, and **none of those 42 would become `unit`** even
if the tag were ignored — every one names a forbidden integration surface or a file/resource/environment
API. The heavy tags are therefore not hiding unit-eligible tests; the unit lane (11 classes / 94 methods) is
the pure-rule plus validation subset.

Today `bun scripts/mvn-fast.js --fast` excludes `generator`, `compiler` and `completeness` and runs the
rest — a good fast lane, but still all-or-nothing inside the unit/component band. There is no per-test
dependency graph and no test-run state.

---

## 3. The lanes

One tag per lane, on the test class. Existing tags are reused; the new ones are `unit` and `component`.

| Lane / tag     | Rule                                                                   | What may run it                                           |
| -------------- | ---------------------------------------------------------------------- | --------------------------------------------------------- |
| `unit`         | ≤ **3** direct **production** classes; no `generator`/`compiler`/`completeness` tag; no `CompileHarness`, `ExamplePassFixture`, `EntityMetadataGenerator`, `SourceReader`; no file/resource/environment dependency the index cannot see | `test-affected`, `--all-unit`, fast lane |
| `component`    | a small slice that uses one subsystem or a test-support fixture but is not a full pass/compiler run; more than 3 production deps or a deliberate fixture | fast lane; `test-affected --include-component` (optional) |
| `generator`    | runs `EntityMetadataGenerator.generate(...)` / a real-pass `main(...)` | full suite; never affected-unit                           |
| `compiler`     | invokes javac / `CompileHarness`                                       | full suite; never affected-unit                           |
| `completeness` | walks the reactor or a migration-wide source set                       | full suite only                                           |

The tags are the human-readable lane; the generated test index is the measured dependency record. The
contract test (T2) checks that the two agree: a `unit` class over the dependency threshold fails, and a
class under the threshold that is not tagged `unit` is reported (so the lane cannot quietly shrink).

**"Direct production class" means** a class under `src/main/java` in this module, named in the test
class's constant pool. JDK, JUnit and third-party classes do not count. Nested types count as their
declaring top-level class for the threshold, but the dependency set keeps the exact FQN for selection.
Test-support classes (`src/test/java`) are recorded separately and are never counted as production, but
a `unit` test's use of an integration support class is itself a contract violation.

---

## 4. The test index

Generated per module after `test-compile`, into the module's derived index subtree:

`.jcodebuddy/index/tests.json`

```json
{
  "format": 1,
  "module": "hipster-entity-tooling",
  "sourceRoot": "src/main/java",
  "testRoot": "src/test/java",
  "classIndex": "classes.json",
  "files": {
    "jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/index/ContentHash.java":
      "hr.hrg.jcodebuddy.engine.index.ContentHash"
  },
  "tests": {
    "hr.hrg.hipster.entity.tooling.index.ContentHashTest": {
      "file": "src/test/java/hr/hrg/hipster/entity/tooling/index/ContentHashTest.java",
      "tags": ["unit"],
      "productionDeps": ["hr.hrg.jcodebuddy.engine.index.ContentHash"],
      "testDeps": [],
      "productionClosure": ["hr.hrg.jcodebuddy.engine.index.ContentHash"],
      "checksum": "…"
    }
  },
  "production": {
    "hr.hrg.jcodebuddy.engine.index.ContentHash": {
      "file": "jcodebuddy/jcodebuddy-core/src/main/java/…/ContentHash.java",
      "module": "jcodebuddy-core",
      "deps": []
    }
  }
}
```

- **Source of dependencies:** the compiled class file, not imports. `java.lang.classfile` (JDK 25) reads
  the constant pool and yields the `ClassEntry` names; this catches static imports, fully-qualified uses
  and nested types that an import scan misses.
- **Source of tags:** the class file's `RuntimeVisibleAnnotations` for `@Tag`, cross-checked against the
  source tag in the contract. (A tag is runtime-retained by JUnit, so both are available.)
- **Cross-module deps are included.** The index scans the **test classpath's** `target/classes` and
  `target/test-classes` directories, not only this module. A class under
  `jcodebuddy-core/target/classes` maps back to its sibling `src/main/java` path, so a one-class test in
  the tooling module can depend on a core class and still be a unit test. The `file` table is
  repo-relative and is what a changed path is resolved through; a dependency that resolves only to a jar
  (a released artifact, not a workspace source root) is recorded as external and is not an editable
  input.
- **Production graph:** the same constant-pool read over every project class on the classpath, producing
  `FQN → direct project deps`. The test's `productionClosure` is the transitive closure of that graph, so
  a change to an indirect helper selects the test that depends on it.
- **Test-support graph:** the same read over test classes, excluding the test under analysis; a change to
  `CompileHarness` or `ExamplePassFixture` selects every test that names it.
- **No source content, no absolute paths:** paths are repo-relative, and the test index is a **separate
  derived table** — it is not written into any `<Marker>.metadata.json`, so `classes.json` remains the
  entity model's one addressing table (DEC-029). The test index's `files` table is the same rule for the
  test-selection surface.
- **Staleness:** the index records a checksum of itself and of the compile output; the selector refuses
  an index older than the classes and regenerates it, or falls back to the all-unit lane.

The index is derived and ignored, like `classes.json`; deleting it costs a regeneration, not correctness.
It is not committed: it is a fact about one test run's compiled classpath, and a checkout that has not
compiled has no such fact yet.

---

## 5. Selection and the `fs-meta` state

### 5.1 State

`<module>/.jcodebuddy/cache/test-affected/` holds one `fs-meta` trio (same format and hash mode as
`entity-build`) plus a small `run.json`:

- **Inputs:** the test module's `src/main/java`, `src/test/java`, `src/main/resources`,
  `src/test/resources`, and `pom.xml`, plus the **workspace source roots of every module named in the
  test index's `production`/`files` table**. A test in the tooling module that depends on
  `jcodebuddy-core` therefore sees a core source edit; a dependency that exists only as a jar has no
  source root and no local change to see. The scan ignores `target/`, `.jcodebuddy/` and `.git/`; files
  outside these roots (docs, scripts) do not invalidate tests.
- **`run.json`:** the test-index checksum, the last successful run's triples, and the selected-test list.
  It is written **only after every selected test passes**. A failed run leaves the old state, so the same
  tests (and their dependents) are selected again.
- **No-op:** no content change and the index checksum matches → print `no-op`, run no Maven, write
  nothing.
- A changed POM, a missing/corrupt state, or an index that cannot be rebuilt → **all unit tests** in the
  module (conservative fallback, reported).

### 5.2 Selection

1. Diff the test inputs with `FsMetaDiff`; ignore `TOUCHED`.
2. Map each changed path through the index's `files` table to FQNs. A path with no type
   (`package-info`, a resource) is "unmapped".
3. A changed **production** FQN selects every unit test whose `productionClosure` contains it.
4. A changed **test** FQN selects that test and every test whose `testDeps` contains it.
5. A changed **test support** file selects its dependents, or falls back to all unit tests if the support
   class is not in the index.
6. An **unmapped** changed file under a source/resource root selects all unit tests (conservative).
7. A changed `pom.xml` selects all unit tests.
8. A changed **test file** selects that test even if it is not a unit test; the runner reports that it
   cannot run a non-unit test through this lane and suggests the fast lane.

Selection is **reverse transitive**: if `A` changed, tests depending on `A` are selected; if `B` changed
and `A` depends on `B`, tests depending on `A` are selected because `productionClosure` includes `B`.
This over-selects in the safe direction and never under-selects for a dependency the index can see.

### 5.3 Command

`bun scripts/test-affected.js`:

| Command                                                        | Behaviour                                                      |
| -------------------------------------------------------------- | -------------------------------------------------------------- |
| `bun scripts/test-affected.js`                                 | select and run affected unit tests; no-op when nothing changed |
| `bun scripts/test-affected.js --module hipster-entity-tooling` | explicit module (default from the cwd or the entity set)       |
| `bun scripts/test-affected.js --all-unit`                      | run every `unit` test and publish state on success             |
| `bun scripts/test-affected.js --include-component`             | also select affected `component` tests                         |
| `bun scripts/test-affected.js --explain`                       | print changed file → FQN → test chains; run nothing            |
| `bun scripts/test-affected.js --dry-run`                       | print the Maven command; run nothing                           |
| `bun scripts/test-affected.js --json`                          | machine-readable selection and result                          |

The runner invokes the existing fast lane with the selected classes:

```
bun scripts/mvn-fast.js --fast --tests 'ClassA,ClassB'
```

No new Maven goal, no new test runner, no annotation processor. The fast lane already withholds the cache
save; `test-affected` adds no cache write of its own. Its state is a separate `fs-meta` trio, not the
entity build's, because its inputs are a different set (test sources, resources, POM).

---

## 6. Steps

Each step is one commit with its own gate.

### T0 — Measure the lanes and publish the contract (S)
**Who:** agent · **Depends:** none

- Add a read-only probe (or extend `scripts/test-cost.js` with `--lanes`) that reports, per test class:
  direct production deps, test-support deps, tags, and the candidate lane. The probe is the evidence for
  the threshold; it does not modify tests.
- Record the baseline: number of tests/classes per lane, and how many low-dependency tests are currently
  hidden inside `generator`/`compiler`.
- Write the lane rules into the plan (or a short DEC if the lane tags become a repository-wide contract).

**Gate:** the probe's counts match § 2; every current tag is classified; no test changed.

**Done 2026-11-06.** `bun scripts/test-lanes.js` is the read-only probe: it reads `target/test-classes`
with `javap -v`, classifies dependencies against every module's `target/classes` / `target/test-classes`,
and reports tags, lanes, the histogram and hidden low-dependency classes. Measured 83 classes / 542
methods: **11 unit, 20 component, 28 generator, 23 compiler, 1 completeness**. The § 2 table was corrected
to the measured distribution; the earlier buckets were unsupported (the plan-time 78-class totals were
right, the per-bucket counts were not). No test was changed, no Maven run, no full suite.

### T1 — Generate the test dependency index (M)
**Who:** agent · **Depends:** T0

- Add `TestIndexGenerator` (Java, JDK 25 `java.lang.classfile`) and a Bun wrapper that runs it after
  `test-compile`. It reads every project `target/classes` and `target/test-classes` directory on the
  module's test classpath, maps class files back to their `src/main/java`/`src/test/java` siblings, and
  writes `.jcodebuddy/index/tests.json` for the test module.
- Read `@Tag` from class-file annotations; record direct production deps, test-support deps, and the
  transitive production closure. Reject an unresolved project name loudly rather than dropping it.
- Add a test that the index covers every test class and that a known test's dependency row is exact
  (`ContentHashTest → ContentHash`, including the cross-module source path).
- Decide the Java home: a small test-scoped helper in `hipster-entity-tooling` is enough for this
  repository; promote it to a JCodeBuddy dev-time library only when a second module needs it (AGENTS § 1.1).

**Gate:** the index is generated for the tooling module; every test class has a row; the known row is
exact; the index is deterministic across two runs; deleting it costs one regeneration.

**Done 2026-11-06.** `TestIndexGenerator` (JDK 25 `java.lang.classfile`) and `scripts/test-index.js` write
`.jcodebuddy/index/tests.json`: **83 test rows, 134 production rows, 222 files**, byte-identical across two
runs. `TestIndexGeneratorTest` is **6/6**; `ContentHashTest` has exactly
`hr.hrg.jcodebuddy.engine.index.ContentHash` in `productionDeps`, with the real cross-module source path and
the transitive `Wyhash64` closure. The module's `.jcodebuddy/.gitignore` keeps the table derived. Integrating
it exposed `GeneratorGuardTest`'s assumption that `index/` always has `classes.json`; the guard now allows
the declared `tests.json` and only scans `classes.json` when it exists (**6/6**).

### T2 — Add the `unit` lane and its contract test (M)
**Who:** agent · **Depends:** T1

- Tag the qualifying classes `@Tag("unit")`; tag the small subsystem tests that do not meet the strict
  rule `@Tag("component")`. Do not re-tag `generator`/`compiler`/`completeness` classes.
- Add `UnitLaneContractTest` (or a Bun contract check over `tests.json`) that fails when:
  - a `unit` test has more than three production deps or names a forbidden integration surface;
  - a test in the low-dependency band is not tagged `unit`;
  - a test has both `unit` and `generator`/`compiler`/`completeness`;
  - the index and the source tags disagree.
- Start with a small set (the pure rule/chunk tests) and grow it as the contract proves correct; the
  contract, not a target count, decides.

**Gate:** every test has a lane; the contract is green; the fast lane still runs the same tests it did
before plus the new tags; the full suite still reports 542 JUnit methods (557 Surefire executions once
parameterized/nested expansion is counted).

**Done 2026-11-06.** Tagged exactly the 11 measured `unit` classes and the 20 `component` classes
(62 insertion lines; no heavy class re-tagged). `scripts/check-test-lanes.js` compares the measured lanes,
the `tests.json` tags and the ≤3-production-dependency threshold; it is green at **11/20/28/23/1** with no
untagged row. Regenerating the index was required — the contract correctly failed all 31 new tags before
that, which proves it is reading the source state rather than the old table. The uncached fast lane ran
**557 Surefire executions, 0 failures/errors**, and an all-83-class comparison found only
`AllLevelsCompileTest` differing (3 `@ParameterizedTest` methods expanding to 18 `@EnumSource`
invocations, +15); 542 is the method count, not a lost test. The full gate was not run.

### T3 — Implement the selector (M)
**Who:** agent · **Depends:** T1, T2

- Add the Java selector (or a Java helper called by the Bun script) that loads `tests.json`, `classes.json`
  and the `fs-meta` diff, and produces the selected unit test list with a reason chain.
- Implement the fallbacks in § 5.2: unmapped file, POM, missing/corrupt state, stale index → all unit.
- `--explain` prints `changed file → production FQN → test` and the closure path.
- Add unit tests for the selector over a synthetic index: direct hit, indirect hit, test-support hit,
  no hit, POM, unmapped, stale index.

**Gate:** a change to `ContentHash` selects `ContentHashTest` and no parser/generator test; a change to a
helper selects its dependents; an unrelated file selects none; `--explain` names the chain; no files
written in explain/dry-run.

### T4 — Add the state and the Bun runner (M)
**Who:** agent · **Depends:** T3

- Add `TestRunState` (or extend the selector) with the `fs-meta` trio and `run.json` from § 5.1.
- Add `scripts/test-affected.js` with the commands in § 5.3, `timing.js` last line, and a clear non-zero
  exit on test failure.
- On success, publish the state; on failure, do not. A no-op writes nothing.
- Reuse the fast lane; do not add a second Maven invocation path.

**Gate:** first run selects and runs; second no-op; a one-file change selects only its dependents; a
failed test leaves the state so the next run selects it again; a POM change selects all unit tests; the
script prints its duration last.

### T5 — Prove the loop and the safety net (M)
**Who:** agent · **Depends:** T4

- Add a fixture/probe that edits a temp copy of one production class and asserts the selection set.
- Run the tool against the real tooling module: no-op, `ContentHash` change, a test-support change, a POM
  change, and an unrelated change.
- Check the fast lane and the full gate are unchanged; `test-affected` is not the gate.
- Document the commands in the root README and `codebuddy.md`; state that watch mode is still later and
  will call the same selector.

**Gate:** every probe is green; no full-suite count changes; docs links resolve; `test-affected` is
measured (no-op, one-file, all-unit).

### T6 — Measure and close (S)
**Who:** agent · **Depends:** T5

Record: unit/component class counts, no-op time, one-file selection time, all-unit time, and how many
tests a typical edit skips. Update this plan's status and the README table. Add the “new test gets a
lane” rule to the test docs.

**Gate:** the recorded numbers are reproducible; the full gate is green at 542 tests; no test deleted,
disabled or weakened.

---

## 7. What this deliberately does not do

- **No watch mode.** The earlier decision stands: watch is postponed. `test-affected` is a one-shot
  command that can be called after an edit; a future watcher can feed it a batch.
- **No runtime coverage or dynamic tracing.** A test that reaches a class only through reflection, a
  string, a resource or an external process is not unit material; it gets `component`/`integration` or a
  manual exclusion. The index is a safe over-approximation for explicit dependencies, not a proof of
  coverage.
- **No Maven/Surefire replacement.** The selected list goes through the existing fast lane and the full
  gate stays the recorded gate.
- **No committing the index.** It is derived, per module, and regenerable.
- **No cross-classpath selection.** One test module and the workspace modules on its test classpath are
  the first cut. A module that is not on that classpath is not a dependency and does not need a state
  input.

---

## 8. Risks

- **Invisible dependencies.** Reflection, resource loads and `System.setProperty` are not in the constant
  pool. Mitigation: the unit rule forbids the integration surfaces; the contract test reports a unit test
  that uses `Class.forName`, `Files.read*`, `getResource*` or `System.setProperty`; those tests move to
  `component`.
- **Over-selection.** A transitive closure can select a superset; that is the safe direction and is still
  far smaller than the module.
- **Under-selection from a stale index.** Mitigation: index checksum + compile-output hash; stale → all
  unit; the selector's fallbacks are part of the gate.
- **Shared static state.** Unit tests must not mutate process-global configuration; that is already the
  tag rule for generator tests. The affected runner runs sequentially, so it does not add thread races.
- **A changed file that maps to no type.** Fallback to all unit tests and report the file, never silently
  select none.
- **Test index drift.** The contract test fails when a low-dependency test is not tagged `unit`, so the
  lane cannot shrink silently.

---

## 9. Done when

- `tests.json` is generated for the tooling module; every test has a lane and a dependency row.
- `@Tag("unit")` classes satisfy ≤3 production deps and the forbidden-surface rule; the contract test is
  green.
- `bun scripts/test-affected.js`:
  - no change → no-op, no Maven;
  - `ContentHash` change → `ContentHashTest` only;
  - helper change → its dependents;
  - POM change → all unit tests;
  - a failure does not advance the state;
  - `--explain` names the chain.
- The fast lane and the full 542-test gate are unchanged.
- The measured no-op / one-file / all-unit times are recorded, and watch mode remains out of scope.
