# Plan — fix the `-Pjunit5` profile, and close the ignore-file residue

**Status:** Done (see § 6). Every item below is implemented and verified in this round.

**Motivation.** Two pieces of build/hygiene residue were found while relocating
JCodeBuddy output under `.jcodebuddy/` (DEC-026). Neither blocks a default build, so
both had gone unnoticed:

1. **The `-Pjunit5` profile is unloadable.** Activating it makes the reactor fail at POM
   validation, so the only profile the project documents for JUnit 5 cannot be used at
   all — and it is not needed to run JUnit 5.
2. **Dead ignore entries** left over from deleted scratch directories, including an
   untracked-plan entry that is the only thing keeping `.kilo/plans/*.md` out of git.

This plan records both, fixes the first, and states explicitly what is *not* fixed and
why.

---

## 1. Defect: the root `junit5` profile is invalid

**Observable.** With `-Pjunit5`, Maven aborts before building anything:

```
[ERROR] 'build.plugins.plugin[org.apache.maven.plugins:maven-surefire-plugin]
        .dependencies.dependency.scope' for org.junit.jupiter:junit-jupiter-engine:jar
        must be one of [compile, runtime, system] but is 'test'.
        @ line 394, column 40
[ERROR] The build could not read 1 project -> [Help 1]
```

**Cause.** `pom.xml` lines 382–400. The root `junit5` profile injects
`junit-jupiter-engine` into `maven-surefire-plugin`'s own `<dependencies>` with
`<scope>test</scope>`. A **plugin** dependency is a classpath entry for the plugin, not
for the project, so Maven's POM model only accepts `compile`, `runtime` or `system`
there. `test` is a project-dependency scope and is rejected at validation time — before
a single goal runs.

**Why it was unnecessary.** Surefire 3.2.5 detects the JUnit Platform automatically when
a JUnit Platform engine is on the *test* classpath. `surefire-junit-platform:3.2.5` (with
its `junit-platform-launcher` and `junit-jupiter-engine` entries) is already the
provider surefire selects. Meanwhile every module that runs JUnit 5 tests already declares
`junit-jupiter-engine` as an ordinary test dependency (`hipster-entity-core/pom.xml:29-33`
and siblings). So the profile's injection was doing nothing that the default build does
not already do.

**Measured, not assumed.** The default gate — `scripts\mvn-jdk25.cmd -o -pl <six modules>
-am clean test`, with no profile — is green and runs **433 tests**: api 4, core 88,
tooling 284, jackson 26, `hipster-entity-test` 31. JUnit 5 runs without the profile.

> **Correction to an earlier figure.** An earlier draft of this plan said 359, following
> the "core 88, tooling 214, jackson 26, test 31" footnote in
> `plans__p1/plan.dsflash.followup.md` § 0.1. That was a miscount on two counts: the
> footnote omits `hipster-entity-api`'s 4 tests, and the measured tooling count is 284,
> not 214. The authoritative reading is the surefire summary of a clean run: **433**,
> broken down as above. The 359 figure appears in the older plan's prose as well, so it
> is stale in both places; it is recorded here rather than edited there, because that
> document is a completed execution record.

## 2. Decision: delete the injection, keep the profile as a documented no-op

- The malformed `<dependencies>` block on `maven-surefire-plugin` **is removed**. There is
  no correct replacement: the profile has nothing left to contribute.
- The `junit5` profile itself **stays**, as an empty profile with a comment stating that
  JUnit 5 is the default. Deleting it was considered (see § 4) and rejected.
- The eight module-level `junit5` profiles are **left alone**. They are empty
  `<activeByDefault>true</activeByDefault>` stubs; they contribute nothing, and touching
  eight files for no behavioural gain would be churn.

## 3. Second item: dead `.gitignore` entries

Removed in this round (their targets were deleted in the preceding round, with the
user's go-ahead):

| Entry | Why it is dead |
| --- | --- |
| `/sync_test` | directory deleted |
| `test_sort` | directory deleted |
| `/tmp/` | directory deleted |
| `hipster-entity-example/tmp/` | deleted in an earlier round |

Also removed: the "Local scratch" comment block that explained `/tmp/`, because it cited
`plans__p1/plan.dsflash.followup.md` § 4.3 for debris that no longer exists. The file was
regrouped by concern at the same time, since the new JCodeBuddy block had been spliced
into the middle of the "Logs" section.

**Deliberately left in place, and recorded here so the decision is not silently
re-litigated:** eight further entries have no target on disk today —
`zig-watch-scp/test_create/test.txt`, `zig-watch-scp/test_hex`, `test_padding`,
`test_tab`, `zig-watch-scp/build_output.txt`, `build_output.txt`, `cross_verify.bin`,
`agent_log*.txt`. None of those paths exists, and `zig-watch-scp/` is not in the reactor.
They are left alone because a stale ignore can only over-ignore, and removing entries
whose sibling paths (`java-watch-scp/bad.conf`) still exist is a separate tidy-up with its
own evidence burden. **`.kilo/plans` stays** — it is live: `.kilo/plans/` holds untracked
per-round plan files today, and that entry is the only thing excluding them from git.

## 4. Alternatives considered

- **Change the injected scope to `runtime`.** Rejected. It silences the validation error
  but keeps a redundant plugin-classpath injection that duplicates what the provider
  already resolves — and it resolves a *different* engine version than the modules
  declare, which is a second way to diverge from the tested classpath.
- **Inject the correct plugin-side artifact instead** (e.g. a
  `junit-platform-surefire-provider` entry). Rejected as unnecessary: measured above, the
  provider resolves and 433 tests run with no injection at all. Adding a plugin dependency
  to fix a profile nobody needs is the same over-engineering in a new place.
- **Delete the profile outright, plus the eight module stubs.** Rejected for scope
  discipline. `-Pjunit5` is an established invocation shape in this repository (every
  module defines the profile, and the docs reference the flag), so keeping a documented
  no-op preserves the invocation and a reader's expectations at the cost of three lines.
  Removing it would be defensible, but it is a larger change than the defect requires and
  it would silently change what `-Pjunit5` means.
- **Leave it broken and document the workaround.** Rejected: an unusable documented flag
  is worse than no flag, and the "workaround" (drive JUnit through the platform console
  with a hand-built classpath) is not something a contributor should have to discover.

## 5. Verification

The fix is verified by observable build behaviour, not by inspection:

1. `scripts\mvn-jdk25.cmd -o -pl <six modules> -am -Pjunit5 clean test` completes with
   `BUILD SUCCESS` — the profile is loadable again (it previously aborted the reactor at
   POM validation).
2. The `-Pjunit5` run and the default run produce the **same per-class results**: 59 test
   classes, 433 tests, 0 failures, 0 errors, 0 skipped. A `Compare-Object` of the two
   runs' `Tests run:` lines differs only in the `Time elapsed` field. That is the proof
   that the profile contributed nothing that was needed — not merely that the build is
   green.
3. The default gate (no profile) still passes 433 tests, so the default path is unchanged.
4. `mvn validate` with `-Pjunit5` no longer reports the
   `...dependency.scope ... must be one of [compile, runtime, system]` error.

## 6. Implementation record

| Step | Outcome |
| --- | --- |
| Remove the malformed surefire `<dependencies>` block from the root `junit5` profile | **Done** — `pom.xml` |
| Turn the profile into a documented no-op explaining that JUnit 5 is the default | **Done** — `pom.xml` |
| Drop the four dead ignore entries and the stale comment block; regroup `.gitignore` | **Done** — `.gitignore` |
| Record the eight remaining dead entries and the decision to leave them | **Done** — § 3 above |
| Re-run the six-module gate with `-Pjunit5` and confirm the suite is unchanged | **Done** — 433/433, per-class identical to the default run (§ 5.2) |
| Escape `<dependencies>` as `&lt;dependencies&gt;` inside the new XML comment | **Done** — raw `<` is illegal in an XML comment; `pom.xml` verified well-formed afterwards |
| Correct `doc/architecture/module-map.md`'s JUnit Strategy table | **Done** — it claimed JUnit 5 came "via `junit5` profile activation", which is now documented as false |

## 7. Out of scope

- The eight stale ignore entries listed in § 3. Recorded, not removed.
- Deleting the eight module-level `junit5` empty stubs.
- Any change to the modules' declared test dependencies; the default build already
  resolves the correct engine.
- `project-automation`'s pre-existing `MetadataServerTest.httpForyRoundTrip` failure,
  which is outside the six-module gate (recorded as F-48 in
  `plans__p1/plan.dsflash.followup.md` § 0.3).
- Registration of this plan in a plans index. There is no `plans__p1/README.md`; the
  directory is currently a flat set of round plans, and adding an index is a separate
  decision.
