# Improvement proposal — merge-java

Status: **implemented**. See
[`IMPROVEMENTS_DELIVERED.md`](IMPROVEMENTS_DELIVERED.md) for what was actually
built, which of the measured limitations are now fixed, and which bugs the new
tests found. The analysis below is kept as the evidence and reasoning; the
proposal's workstreams are marked with their outcome there.

This document supersedes `IMPLEMENTATION_PLAN.md` phase 9 and 10 where they
overlap; the plan tracks delivery, this tracks *what to build next and why*.

---

## 1. Where the module stands

Working and tested:

- Ten conflict types, each with a declared handling policy.
- Automatic resolution of the additive cases (imports, comments, constants,
  distinct overloads, widening type changes).
- Fix paths for the ambiguous cases, with a recommendation and a stated cost.
- Per-branch decision history on disk, replayed when the conflict signature
  matches, and asymmetric on purpose: preferences and additive facts are
  remembered, judgement calls are not.
- A three-override extension pattern with a test that executes the guide.

The honest limitation: **resolution and detection are syntactic.** They work on
the conflicting hunk as text, with regular expressions. That is why the module is
fast, dependency-light and fully unit-testable — and it is also where every real
weakness comes from.

---

## 2. Measured limitations

The following were reproduced, not assumed. `LimitationProbe` was a temporary
test used to establish them; the findings are recorded here so the probe is not
needed. Suggested regression tests are named for each.

| # | Input | Observed | Should be |
|---|---|---|---|
| L1 | Branch 1 adds an import; branch 2 deletes an unrelated method | `STRUCTURAL_CHANGE` only — the import addition is **lost** | `IMPORT_ADD` (auto) *and* a structural conflict |
| L2 | A comment line sits between two method declarations | **nothing detected** | `OVERLOAD_ADD` |
| L3 | Opening brace on the following line | **nothing detected** | `OVERLOAD_ADD` |
| L4 | `Map<String, List<String>> x` vs `Collection<String> x` | **nothing detected** | `TYPE_CHANGE` (review) |
| L5 | Both branches add statements to the same body (control) | `METHOD_BODY_CHANGE` ✓ | — |
| L6 | Both branches add an import *and* edit the body (control) | `IMPORT_ADD` ✓ | — |

Two distinct root causes:

**Cause A — no cross-detector composition (L1).** `ConflictDetectionService`
falls back to a single `STRUCTURAL_CHANGE` whenever *any* unrecognised change is
present, and that fallback replaces nothing: the recognised conflicts are still
emitted, but the file is now reported as fully manual because one structural
conflict is present. A file where 90% is mechanical becomes 100% manual. Worse,
common real merges are heterogeneous ("add an import and fix a null check"), so
this is the normal case, not an edge case.

**Cause B — line-oriented regex parsing (L2, L3, L4).** The parsers assume one
declaration per line, a `{` on the same line, and a type that is a single token.
All three assumptions are violated by ordinary formatted code. These failures are
silent: detection returns an empty list and the conflict is either dropped or
escalated to structural.

There is no safety net for either cause. Nothing verifies that an automatic
resolution produces code that still compiles.

---

## 3. Objectives, in priority order

1. **Never lose an auto-resolvable change** because the same file also contains
   something unrelated (fixes Cause A).
2. **Never silently miss a conflict** because of formatting (fixes Cause B).
3. **Never apply a resolution that breaks the build** — add a verification gate.
4. **Make the history trustworthy over time** — schema versioning, validation,
   pruning.
5. **Close the loop from the command line**: fetch, merge, resolve, apply, verify.
6. **Make the value visible**: a reviewer-facing report.
7. **Make "many conflicts" tractable**: batch mode with a dry run.

---

## 4. Workstreams

### WS1 — Compose conflicts instead of replacing them *(highest value)*

**Problem.** One unrecognised change downgrades an entire file to manual.

**Design.** Detection already emits one `Conflict` per shape it recognises. The
change is to stop treating `STRUCTURAL_CHANGE` as a *substitute* and treat it as
a *sibling*:

- Always emit the recognised conflicts. `detect()` already does this; the bug is
  that `MergeReport` reports `hasUnresolvedConflicts()` for the whole file, so a
  caller applying auto-resolutions gets a file that is only partly resolved and
  has no way to know which parts.
- Add **region attribution** to `Conflict`: the base line range each conflict
  covers. Two conflicts whose ranges are disjoint can both be applied;
  overlapping ones must be presented together.
- Introduce `MergeReport.getIndependentlyApplicable()` — the auto resolutions
  whose regions do not overlap any manual conflict — so a caller can apply the
  safe subset and leave the rest for review.
- Report per-region status, so "3 auto, 1 manual in `Payment.java`" becomes
  "lines 12-14 auto; lines 40-52 manual".

**Acceptance.** L1 yields `IMPORT_ADD` **and** `STRUCTURAL_CHANGE`, and the
import resolution is reported as independently applicable. Regression test:
`ConflictDetectionServiceTest.mixedAddAndDeleteKeepsTheAdditiveConflict`.

**Effort.** ~2 days. **Risk.** Low — additive to the existing model, but
`Conflict` gains a field, so every construction site changes.

---

### WS2 — Replace line-based parsing with a real parse *(highest correctness)*

**Problem.** L2, L3 and L4 are silent misses on ordinary formatting.

**Design.** A staged migration, smallest step first:

- **Step 1 (cheap, no new dependency).** Whole-file normalisation before
  detection: join declaration headers split across lines, tolerate `{` on the
  following line, skip comment and annotation lines when scanning declarations.
  This alone fixes L2 and L3 and is contained in the extraction helpers.
- **Step 2 (the real fix).** Use OpenRewrite's Java parser — already a dependency
  and unused for this — to obtain a genuine AST per version, then compare
  declarations structurally:
  - overloads compared by *resolved parameter types*, so `List<String>` and
    `java.util.List<java.lang.String>` are equal and L4 becomes detectable;
  - imports taken from the AST rather than by string prefix;
  - method bodies compared as statement lists with positions, not as lines.
- Keep the regex path as a fallback for hunks the parser rejects (a partial file
  or an old language level) so behaviour degrades instead of failing.

**Acceptance.** L2, L3 and L4 all detect; every existing test still passes with
the fallback path disabled *and* with it enabled.

**Effort.** ~5 days for step 2 (the step that makes the module semantic).
**Risk.** Medium. This is the change most likely to alter existing behaviour, so
it must land behind the test suite, not beside it. Note the JCodeBuddy rule that
JavaParser is the project's AST of choice for *generators*; OpenRewrite's parser
is the right choice here because it is already on the classpath and this is
analysis, not codegen.

**Note.** This also unblocks WS4 and WS5, which need resolved symbols.

---

### WS3 — Verification gate: compile before applying *(new safety property)*

**Problem.** An automatic resolution that produces non-compiling code is worse
than a conflict, because it hides a failure behind a silent success.

**Design.**

- New SPI: `ResolutionVerifier` with `Verifier.Result verify(Path file,
  String resolvedCode)`.
- Default implementation: parse the resolved code; if it fails, downgrade the
  resolution from `AUTO` to `REVIEW` and attach the parse error as a fix path
  justification.
- Optional stronger implementation: run a compile of the affected module and
  downgrade everything that does not build. Off by default (slow), enabled for
  batch runs.
- `ConflictResolution` gains `verification` (passed / downgraded / skipped) so a
  caller can see *why* something expected to be auto is now review.

**Acceptance.** A resolver that emits syntactically invalid code results in a
`REVIEW` resolution carrying the parse error, not an applied one.

**Effort.** ~2 days (parse-level); ~2 more for compile-level.
**Risk.** Low. Purely additive, and it makes the module's central claim
defensible: automatic means "will not break the build".

---

### WS4 — History that stays trustworthy

**Problem.** The decision store has no schema version, has never validated
against the schema it ships, never prunes, and uses a normalisation that could in
principle collapse distinct code.

**Design.**

- **Version every decision file** (`"schemaVersion": 1`). On load, an unknown
  version is ignored and reported, never guessed at.
- **Validate on load** against `.jcodebuddy/metadata/schema.json`, so the schema
  is a contract rather than documentation. Invalid entries are skipped and
  counted.
- **Prune**: `BranchConflictStore.prune(Instant olderThan)` and a
  `staleAfter` setting, because a decision whose conflict has not been seen in
  N months is dead weight in a checked-in directory.
- **Audit the normalisation.** `ConflictSignature.normalise` strips spacing
  around punctuation. Prove with a property test that it never maps two
  semantically different hunks to the same signature (generate perturbations and
  assert distinct signatures), and if it can, narrow it.
- **Report replay authority.** A `--explain`-style dump: for each decision, what
  it will match and what it last matched. A reviewer must be able to answer "why
  did the tool do that?" without reading JSON.

**Acceptance.** A decision file with a bumped version is ignored with a
diagnostic; a corrupted entry is skipped and counted without failing a merge;
the normalisation property test passes.

**Effort.** ~3 days. **Risk.** Low.

---

### WS5 — Continuous conflict resolution, end to end *(the original goal)*

**Problem.** The module resolves a file when handed three versions. The user's
actual workflow — "keep this branch up to date with base, repeatedly, and stop
re-answering the same conflicts" — is still assembled by hand.

**Design.** A small `MergeWorkflow` facade on top of the existing pieces, using
JGit (already a declared dependency, currently unused):

1. Discover the repository and the **current branch** via JGit, so the branch
   name and history directory are never passed by hand.
2. `fetch` and locate the merge base.
3. For each conflicting path, read base / ours / theirs from the object database
   rather than requiring three files on disk.
4. Resolve, applying history, and **write back** the independently applicable
   auto resolutions (WS1) through JGit.
5. Leave review and manual conflicts in place, and print a per-file summary.
6. Exit non-zero when manual conflicts remain, so it composes in CI.

```java
MergeWorkflow.Result result = MergeWorkflow.open(Path.of("."))
    .upstream("origin/main")
    .dryRun(false)
    .run();

result.autoApplied();          // paths written
result.needsReview();          // conflicts a human must confirm
result.blocked();              // files left untouched
result.historyReplayed();      // decisions reused, for the log
```

**Acceptance.** A repository fixture with a base and a long-lived branch,
updated three times, reaches a fully auto-resolved state by the third update
because the first two recorded decisions.

**Effort.** ~4 days. **Risk.** Medium — this is the first code that writes to a
working tree, so `dryRun` must be exact and defaulted on in tests.

---

### WS6 — Reviewer-facing report

**Problem.** The fix-path model is rich and invisible.

**Design.** Follow this repository's reporting decisions (DEC-027/029): a Java
step writes JSON metadata; a Bun script renders one self-contained HTML file with
framework-free vanilla JS, no network, links relative to one base and verified
before writing. Per conflict: what clashed, both sides, the options, the
recommendation, the cost, and whether it came from history.

Output goes to `.jcodebuddy/metadata/` as derived, ignorable content.

**Acceptance.** Opening the file in a browser and in the JetBrains JCEF webview
shows every conflict with its options; no link is written unless verified.

**Effort.** ~3 days. **Risk.** Low. Depends on WS1 for region attribution to be
genuinely useful.

---

### WS7 — Batch mode and dry run

**Problem.** One commit's merge is a handful of files; a repository-wide
migration is hundreds.

**Design.** `MergeBatch.run(List<Path> files, Options)` with a per-file report,
aggregate counts, a machine-readable summary, and `--dry-run` to report without
writing. Deterministic ordering so output diffs cleanly in CI.

**Acceptance.** A 100-file synthetic merge reports in under a few seconds and
writes nothing in dry-run mode.

**Effort.** ~2 days. **Risk.** Low, once WS1 lands.

---

### WS8 — Two known gaps in the type resolver

Independent of the workstreams above, these are small and specific:

- **`emptyListIsA` chains for interfaces.** `widens` only knows numeric and
  boxed chains plus a few supertypes. Add `List → Collection → Iterable →
  Object`, `Map → Object`, `Set → Collection`, and so on. Cheap, and it turns
  more one-sided type changes into automatic ones.
- **Varargs and arrays.** Compare `String...` and `String[]` as the same
  overload, otherwise two branches adding the same varargs method collide
  spuriously.

**Effort.** ~1 day. **Risk.** Low.

---

## 5. Sequencing

```
WS1 compose conflicts ──┬──> WS3 verification gate ──> WS5 workflow ──> WS7 batch
                        │
WS2 real parse ─────────┘
WS4 history trust ──────────> (independent, do any time)
WS6 report ─── needs WS1
WS8 type gaps ── independent, 1 day, can go first
```

Recommended order:

1. **WS8** — one day, removes two false behaviours immediately.
2. **WS1** — the largest correctness win per day spent; stops losing
   auto-resolvable work in mixed merges.
3. **WS2 step 1** — cheap normalisation, fixes the silent formatting misses.
4. **WS3** — makes "automatic" mean "verified".
5. **WS4** — protects the history the whole design depends on.
6. **WS2 step 2** — the semantic upgrade, now safe behind WS3.
7. **WS5** — closes the original goal end to end.
8. **WS6 / WS7** — visibility and scale.

Total: roughly **4 weeks** for one engineer, of which WS1–WS4 (~2 weeks) delivers
most of the value.

---

## 6. What this proposal does *not* do

- **No automatic resolution of structural or API conflicts.** Every attempt to
  generalise here produces confident wrong answers. They stay manual, with better
  descriptions.
- **No three-way text merge of method bodies.** Combining edits that touch the
  same statement is a research problem; the module combines disjoint edits and
  asks otherwise. WS3 makes that combination verified rather than assumed.
- **No conflict-free merge guarantee.** The promise is narrower and testable:
  additive changes are resolved, everything else is explained.
- **No new user-facing DSL or annotation.** The public surface stays
  `MergeConflictResolver`, `ConflictResolvers` and the `Conflict` /
  `ConflictResolution` / `FixPath` model.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| WS2 changes existing behaviour | Land behind the suite; parser fallback keeps old behaviour available; run both paths in CI |
| Region attribution (WS1) is approximate | Conflicts with unknown or overlapping regions are never reported as independently applicable — conservative by construction |
| Writing to a working tree (WS5) is destructive | `dry-run` default in tests, explicit opt-in to write, and every write reported |
| Checked-in history grows unbounded | WS4 pruning plus a size check in CI |
| Verifier gives false confidence | Parse-level verification is a floor, not a proof; documentation must say so |

---

## 8. Definition of done for the proposal as a whole

1. All six measured limitations in §2 have a regression test that fails today.
2. `ConflictResolution` carries region and verification status.
3. No automatic resolution is applied without passing the verification gate.
4. A three-update repository fixture completes with zero manual conflicts.
5. The test count grows from 431 to cover every new contract, and
   `ADDING_A_RESOLVER.md` still executes as a test.
6. `IMPLEMENTATION_PLAN.md` is updated to point here for future work.

---

## Appendix note — Phase 8 of the rewrite migration (2026-09-22)

**The rule this paragraph cites was superseded; the conclusion the same paragraph reaches was not.**

WS2 step 2 says "Note the JCodeBuddy rule that JavaParser is the project's AST of choice for *generators*; OpenRewrite's parser is the right choice here because it is already on the classpath and this is analysis, not codegen." There is no longer a JavaParser-versus-OpenRewrite split to appeal to: [DEC-030](../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md) records OpenRewrite's LST as the repository's representation for reading, querying and writing Java source, so the *generators* rule and the *analysis* exception now name the same parser. The recommendation the sentence draws — use OpenRewrite's parser because it is already on the classpath — is exactly what the module did, and it is unaffected.

This file is a proposal kept as the record of what was proposed and why; the outcome is in [`IMPROVEMENTS_DELIVERED.md`](IMPROVEMENTS_DELIVERED.md).

The representation decision is [DEC-030](../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md) and the reader's guide is [`doc_knowledge/code.graph.md`](../doc_knowledge/code.graph.md).
