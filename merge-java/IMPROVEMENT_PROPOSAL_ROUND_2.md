# Improvement proposal, round 2 — merge-java

Status: **F4 implemented and verified**; F1, F2, F3, F5 and F6 are open. See
[F4 resolved](#f4--a-malformed-last-sync-marker-produces-a-false-clean-highest-severity)
for what was built, and [§7](#7-f4-as-implemented) for the design decisions and the
evidence that the new tests catch the old behaviour. This is a fresh look at the
module after the first round was delivered
([`IMPROVEMENT_PROPOSAL.md`](IMPROVEMENT_PROPOSAL.md) →
[`IMPROVEMENTS_DELIVERED.md`](IMPROVEMENTS_DELIVERED.md)), not a restatement of it.
Every finding below was reproduced on the current tree; the commands and the
observed output are given with it, so nothing here has to be taken on trust.

The round also produced a tool, because building it was how several of the findings
surfaced: [`scripts/git-sample/`](scripts/git-sample/README.md) turns one fixture
into a real git repository with branches, a merge base and a last-sync marker, so a
fixture can be opened in a git GUI, driven from the CLI, or handed to another
conflict-resolution tool.

---

## 1. What this round measured

| Thing | Value |
|---|---|
| Main source | 7,345 lines across 33 classes |
| Tests | 5,565 lines across 32 classes; 612 test methods, all passing |
| Conflict types declared | 10 |
| Three-way fixtures on disk | 2 |
| Test classes that read a three-way fixture | 1 (`ThreeWayFixtureTest`, 12 tests) |
| Largest class | `ConflictDetectionService`, 787 lines |

## 2. What is already strong

Worth stating before the findings, because the findings are specific and the module
is not in bad shape:

- **612 tests, no failures, and they test the hard part.** Composition, verify-gate,
  history replay, region attribution, registry contract, the JGit workflow against a
  real repository. `ThreeWayFixtureTest` executing the guide is a pattern few
  modules get right.
- **The decision to keep three conflict kinds manual is written down and argued**
  ([`DESIGN_NEVER_AUTO_RESOLVED.md`](DESIGN_NEVER_AUTO_RESOLVED.md)). A module that
  knows what it must not do is rarer than one that resolves everything badly.
- **`WHAT_IS_BASE.md` exists at all.** "Base is the last-synced upstream state, not
  git's merge base" is the kind of distinction that silently produces wrong merges
  in every similar tool, and here it is documented, tested and reflected in
  `LastSyncMarker`.
- **The verification gate** (`ResolutionVerifier`) turns "the resolver produced
  something" into "the resolver produced something that parses".

The findings below are about *coverage and trustworthiness of the evidence*, not
about the resolution logic being naive.

---

## 3. Findings

### F1 — The fixture path covers one conflict type out of ten, and it is the least-exercised code path *(highest long-term value)*

**Evidence.** `ConflictType` declares ten types. `src/test/resources/fixtures/`
holds two directories, and both are import-shaped. Exactly one test class reads a
fixture from disk:

```
$ ls src/test/resources/fixtures
import-add-both  import-add-remove-same

$ grep -rl 'ThreeWayFixture.load("' src/test/java | wc -l   # 1
```

Meanwhile every conflict type has a resolver test that builds its conflict **inline
from strings** — `TypeChangeConflictResolverTest` (66 tests),
`OverloadAddConflictResolverTest` (40), `ConstantAddConflictResolverTest` (33),
`RenameConflictResolverTest` (33) and so on. So the *resolver* logic is well covered;
what is barely covered is the path that makes this module different from a text
differ — **three complete files where only the base reveals what each side did.**

The module's own guide says a case is worth having only when a two-way text
comparison gets it wrong, and its own §3 already lists four such shapes. Its own
naming examples — `method-add-both-same-body`,
`constant-same-name-different-value` — do not exist on disk.

**Consequence.** A change to detection that only shows up when a *complete* base is
present is not covered by anything but the twelve tests in `ThreeWayFixtureTest`. The
highest-value inputs in the module are the ones with the least evidence behind them.

**Fix.** Add one fixture per remaining conflict type, using the four shapes the guide
already names. This is now cheap: each fixture is three files, and
`scripts/git-sample/` immediately gives it a real repository to check the shape
against. Suggested acceptance: `fixtures/` holds at least one case per `ConflictType`,
and a test asserts that mapping, so a new conflict type cannot be added without a
case.

---

### F2 — A fixture's diffs are checked only for imports, so a fixture can carry an undocumented change and still pass its own guard *(highest value per unit of work)*

**Evidence.** The guide prescribes the guard, and `ThreeWayFixtureTest` implements
it — but only for the import block:

```java
Set<String> oursAddedInDiff   = ThreeWayFixture.addedImportsInDiff(fixture.oursDiff());
Set<String> theirsRemovedInDiff = ThreeWayFixture.removedImportsInDiff(fixture.theirsDiff());
```

`import-add-both` passes that guard, because its imports do agree with its diffs.
Measured against the base, however:

| Side | Differing lines vs base | Documented by the fixture's `.diff` |
|---|---|---|
| `ours` | 3 | 1 (`+import java.time.Instant;`) |
| `theirs` | 9 | 1 (`-import java.util.Set;`) |

The other 2 and 8 lines are the class javadoc, which both sides rewrote — `ours`
replaces *"Base version. Both branches start from exactly this file."* with *"Ours:
adds one import, changes nothing else."*, and `theirs` substitutes a six-line
explanation. Neither diff mentions it.

**Consequence, reproduced.** Ask the module to classify that fixture and it does
**not** report the `IMPORT_ADD` the fixture is named for and documents:
`MergeWorkflow` reports `2 conflict(s) - 1 auto (0 applicable), 1 manual`, and the
detected types are `COMMENT_ADD` and `STRUCTURAL_CHANGE`. Anyone reading the fixture
name and its diffs — including me, writing the round-2 tests, whose first assertion
was that an `IMPORT_ADD` resolution would be found — reaches the wrong expectation.
The fixture is not lying about the import change; it is silent about a second change
that changes the answer.

**Fix.** Widen the guard from "the imports agree" to "the fixture contains nothing
the diffs do not document". A workable form, which needs no diff parsing: assert that
the **non-import part of the three files is identical**, or state the extra change
explicitly in the fixture and assert the computed change set equals the documented
one. Fail with the extra change named, so the next person sees what is unexpected
rather than two long strings.

---

### F3 — Fixture names contradict fixture content, and the module's own naming rule is the one being broken

**Evidence.** `docs/THREE_WAY_FIXTURES.md` §Naming: *"Name the directory for the
**change shape**, not the outcome: `import-add-and-remove-different`, …"*.

`import-add-remove-same` does not contain an addition. Its own `ours.diff` says so
in as many words:

```
(no change)

Ours is identical to the base. This fixture exists to isolate the ambiguous case:
theirs removes `java.util.Set` while ours keeps it…
```

and the files agree: `git diff base feature` is empty, and the two files hash
identically. The name reads as "both sides touch the import block"; the content is
"one side did nothing".

**Consequence.** This is not cosmetic. The name is the only thing most readers will
see, and it is wrong — the first version of
[`scripts/git-sample/README.md`](scripts/git-sample/README.md) described this fixture
as *"ours adds an import, theirs removes a different one, and git merges it cleanly —
while the two sides genuinely disagree"*, which is precisely backwards: with one side
unchanged there is nothing to reconcile, and the clean merge proves nothing about any
tool. The prose survived review because the name made it plausible.

**Fix.** Rename to the shape — `import-remove-one-side-only` — and keep the
explanation in the diff, where it already is. Longer term, F1's per-`ConflictType`
mapping should carry the shape name, so the mapping and the name cannot diverge.

---

### F4 — A malformed last-sync marker produces a **false clean**, which is the most dangerous answer the module can give *(highest severity — RESOLVED)*

**Status: fixed.** See [§7](#7-f4-as-implemented) for the design, and
[§4](#4-priority-and-sequencing) for what remains. The finding is kept in full
because the reasoning is the reason the fix is shaped the way it is.

**Evidence, reproduced.** `LastSyncMarker.parse` accepts any non-blank string as the
base commit — it never checks that the value is an object id:

```java
if (commit == null || commit.isBlank() || "null".equals(commit)) {
    return Optional.empty();
}
return Optional.of(new LastSyncMarker(commit, ref, recordedAt, note));
```

`MergeWorkflow` then resolves that string with `Repository.resolve` — which resolves a
**branch name**, and (being JGit's general revision resolver) any other revision
expression it accepts, not just an object id:

```java
ObjectId recordedCommit = marker
    .flatMap(recorded -> resolveQuietly(repository, recorded.upstreamCommit()))
    .orElse(null);
```

Writing `upstreamCommit = upstream` into the marker — which the round-2 generator did
in its first version, before the mistake was caught — makes the base the *upstream
tip*, at which point neither side has changed anything relative to the base and the
workflow reports:

```
branch feature against upstream (base: recorded last-sync marker upstream)
  clean:                  1
  conflicts:              0
```

for a fixture that git conflicts on, that `git merge-tree` reports a conflict for, and
that the resolver reports `2 conflict(s) - … 1 manual` for when handed the same three
versions directly. A **false clean**: the module says the branch is up to date while
the merge is unresolved. Everything downstream of a clean report — an exit code of
`0`, a reported resolution — is then wrong.

**Why this is a module defect and not merely a caller error.** Three facts combine:

1. The marker is documented as something a human may edit — `LastSyncMarker.render`
   says *"this is a two-field file that a human may well inspect or edit during a
   merge, and the format should be obvious at a glance"*.
2. `upstreamRef` sits **next to** `upstreamCommit` and holds a branch name, so
   putting a branch name in the commit field is the obvious mistake to make — and it
   is exactly what an older writer, a hand edit, or a script (mine) will produce.
3. `parse` treats a malformed marker as *absent* ("a missing marker means first
   sync"), but a marker holding a resolvable ref is not malformed by any test the
   code applies, so it is silently believed.

**Fix (as implemented — see §7).** Require an object id, and fail loudly rather than
quietly on anything else:

- `LastSyncMarker` accepts only a full 40- or 64-hex value for `upstreamCommit`, via
  `requireCommitId(historyRoot)` — the only supported way to turn a marker into a base.
- `MergeWorkflow` distinguishes "marker absent" from "marker present but unusable",
  and reports the latter as a `SyncMarkerException` rather than as clean.
- A test per case: a branch name, a tag, `HEAD`, a short sha, an absent object, and an
  object that is not a commit — plus the cases that must **not** be errors (no marker,
  a marker with no commit field).

The generator that surfaced this is now correct, but nothing in the module prevented
the next writer from reintroducing it. That is what the fix is for.

---

### F5 — Two classes hold three jobs each and a third of the module

**Evidence.** Of 7,345 main lines: `ConflictDetectionService` 787,
`MergeConflictResolver` 654, `BranchConflictStore` 569, `MergeWorkflow` 516. The top
four are 34%.

The resolvers already show the pattern the module wants: a small
`AbstractConflictResolver` (197) plus ten focused classes of 171–358 lines. Detection
has no such decomposition — `ConflictDetectionService` contains region discovery,
per-type heuristics, conflict composition and ordering in one class, which is why the
first round's measured limitations (L1–L4) all landed in it.

**Fix.** Split by the axis that already exists: one detector per `ConflictType`
behind a thin composing facade, mirroring `AbstractConflictResolver`. The first
round's `ConflictCompositionTest` (14 tests) is the harness that makes this safe, and
it should stay green throughout. This is a refactor with no behaviour change, so it
should land *after* F1 and F2, which will add the fixtures that make it verifiable.

---

### F6 — Documentation that states a test count will drift, and has

**Evidence.** `README.md` §Tests says *"573 tests covering…"*. The suite runs **612**.
Of that, 11 are new in this round, so the drift predates it by ~28 tests.

**Fix.** Delete the number, or generate it. A reader who finds one stale fact has no
way to tell which others are stale, and this document is asking that reader to trust
measured evidence.

---

## 4. Priority and sequencing

| Order | Finding | Effort | Why here |
|---|---|---|---|
| 1 | ~~**F4** marker validation~~ | hours | **Done.** See §7. |
| 2 | **F2** widen the fixture guard | hours | Small, and it protects every fixture F1 adds. Adding fixtures before the guard would multiply the "silently carries a second change" problem. **This is now the next thing to do.** |
| 3 | **F1** a fixture per conflict type | days | The highest-value work, and now the cheapest it has been: three files per case plus one generator invocation to check the shape it produces. |
| 4 | **F3** rename to the shape | minutes | Do it with F1, so the naming rule and the fixtures are fixed together. |
| 5 | **F5** split detection | days | Verifiable only once F1 exists; the composition tests keep it honest. |
| 6 | **F6** remove the drifting number | minutes | Anytime. |

## 5. What this round delivered, and what it makes possible

[`scripts/git-sample/`](scripts/git-sample/README.md) plus
[`SampleRepoTest`](src/test/java/com/codebuddy/merge/SampleRepoTest.java):

- A fixture becomes a real repository: `base`, `upstream` and `feature` commits with
  the base as their common ancestor, `feature` checked out and tracking
  `origin/upstream`, the last-sync marker written, and the fixture's own diffs beside
  it. Openable in a git GUI, drivable from the CLI, and usable by any other
  conflict-resolution tool — which is the point: this module's claims can now be
  checked against something that is not this module.
- `--merge` leaves the repository mid-merge with diff3 markers, so a conflicting
  state can be inspected by hand.
- 54 checks in [`sample-repo.test.js`](scripts/git-sample/sample-repo.test.js) drive
  the generator in-process and ask **git** what appeared; 11 JUnit tests drive the
  command line and assert each commit holds the fixture file byte for byte, then run
  `MergeWorkflow` against the result unchanged.
- No shell and no pipes: git is executed directly with file descriptors, so the tool
  behaves identically on Windows, macOS and Linux and runs under a sandbox that
  permits only file writes. Removing the shell also removed the `PATHEXT` and
  `NUL`-device workarounds it had required.

It immediately found F4 (its own first version wrote the branch name into the marker,
and the workflow called a conflicting fixture clean), and F3 (the generated README
was about to assert a clean merge "proves" something, for a fixture where one side
had not changed a byte). It now reports which side changed the file, measured from
the three versions rather than inferred from the fixture's name.

## 6. What this proposal does not claim

- It does not propose using OpenRewrite's LST everywhere. The module reads and writes
  through it where a *type* question is being asked, and the previous round settled
  that deliberately; the remaining line-based work is not obviously wrong, and
  "parse everything" would be a rewrite argued from taste rather than from a
  reproduced failure. F6 in the previous round's numbering (type-widening from a
  hardcoded table) remains the one acknowledged approximation there.
- It does not ask for a rename of `ConflictDetectionService` or any public API.
  F5 is an internal decomposition.
- It does not claim the two existing fixtures are worthless. `import-add-both` is a
  legitimate case; it is the *documentation around it* that is narrower than the
  fixture, which is F2 and F3.

---

## 7. F4 as implemented

Three files changed and two added:

| File | Change |
|---|---|
| `SyncMarkerException` | **new** — unchecked, extends `IllegalStateException`, raised only for a present-but-unusable marker |
| `LastSyncMarker` | **new** — `isCommitId(String)` and `requireCommitId(Path)`; javadoc states the field's contract and why parsing stays permissive |
| `MergeWorkflow` | `recordedBase(repository, historyRoot, marker)` replaces the `resolveQuietly` lookup; `resolveQuietly` deleted |
| `LastSyncMarkerTest` | **+4** tests (16 total) |
| `MergeWorkflowTest` | **+8** tests (22 total) |
| `SampleRepoTest` | **+1** test (12 total) — the original symptom, on the original fixture |

### The four decisions

**1. Validate where the value acquires power, not where it is stored.**
`parse` stays permissive: it reads a file and decides nothing. A `LastSyncMarker` is
legitimately constructed and compared with no repository in sight, and five of its
existing tests do exactly that with values like `"abc123"`. Had the record itself
started rejecting non-ids, those tests would have had to be weakened to accommodate
the fix — trading real coverage for a rule enforced in the wrong place. The refusal
now sits in `requireCommitId`, which is the only supported way to turn a marker into a
base, so failing to check is no longer the easy path. There is a test pinning this
split (`parsingDoesNotValidate`), so a later reader can see it is deliberate.

**2. A value naming a *commit* is used; a value naming something that *moves* is an
error.** Absence and a marker with no commit field stay soft — they mean "never
synced", and the merge base is a documented, defensible fallback. A branch name, a
tag, `HEAD~3`, or an abbreviated id is an error, because each *looks* usable and
resolves to something that is not a record of the past. The error names the file, the
value, why believing it would have been worse, and what to write instead. There is no
flag to continue past it: continuing is the behaviour being removed.

**3. A marker that names a *blob* is an error too — this is beyond the finding.**
The review turned up a second silent path. `readVersions` reports an unreadable
version by returning empty, which the caller records as **skipped**; a run whose only
path was skipped still looked resolved. A marker holding a real blob's id is a
perfectly valid `Repository.resolve` result, so it reached `parseCommit`, threw, and
the file was quietly skipped. `recordedBase` therefore also requires the object to be
a commit this repository can read. On the pre-fix code, `markerNamingABlobIsRefused`
fails — the run succeeded with the conflicting file skipped.

**4. No check on *which* commit the marker names.** Two things had to be left alone,
and both are easy to get wrong in the other direction. The base is allowed to be a
commit the upstream no longer contains, because that is what a rebase leaves behind
and the marker exists to survive it (`WHAT_IS_BASE.md`). And the base is allowed to
*equal* the upstream tip, because the workflow itself records exactly that after a
sync — `LastSyncMarker.of(upstream.getName(), …)` — which is why the existing
`laterSyncUsesRecordedMarker` test passes. Only the marker's ability to name a commit
is checked, never its choice of one.

### Why the tests are known to catch the bug

A regression test that has never been seen to fail is a hypothesis. The pre-fix
behaviour was restored temporarily (`repository.resolve(marker.upstreamCommit())`,
returning `null` on `IOException`) and the suite re-run:

```
[ERROR] Tests run: 22, Failures: 5, Errors: 0, Skipped: 0
[ERROR]   markerHoldingAnAbbreviatedIdIsRefused:187 Expected SyncMarkerException ... nothing was thrown
[ERROR]   markerNamingABlobIsRefused:219            Expected SyncMarkerException ... nothing was thrown
[ERROR]   markerNamingABranchIsRefused:155          Expected SyncMarkerException ... nothing was thrown
[ERROR]   markerNamingAMissingCommitIsRefused:201   Expected SyncMarkerException ... nothing was thrown
[ERROR]   markerNamingARevisionExpressionIsRefused:176 Expected SyncMarkerException ... nothing was thrown
```

All five refuse-cases fail without the fix, and the fix was then restored and the
whole suite re-run: **625 tests, 0 failures** (`mvn -o test`), and 54/54 on the
generator's own Bun harness.

### What a caller sees now

```
SyncMarkerException: the sync marker at .jcodebuddy/merge-history/feature/last-sync
records the base as 'upstream', which is not a commit id. A branch name, a tag or a
revision expression means whatever it points at when it is read, and a base that has
moved up to the upstream makes a conflicting merge look clean - so this is refused
rather than resolved. Write the full 40-character commit id there, or delete the
marker to start again from a merge base.
```

That path, on the `import-add-both` fixture, used to print
`clean: 1, conflicts: 0`.