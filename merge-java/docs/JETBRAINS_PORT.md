# Porting JetBrains' merge resolution into `merge-java`

This document is **the reference for the JetBrains port** (plan steps 4.7–4.13). It names every
upstream file, what it does, what we take from it, and what we deliberately leave. It exists so that a
later implementer does not have to re-discover the repository layout, the test paths, or the licence
position — all three of which were wrong or unstated in the original instruction.

Everything here was read from a real checkout. Where the original instruction was inaccurate, the
correction is stated rather than silently applied.

---

## 1. Licence and provenance

| Fact                | Value                                                                     |
| ------------------- | ------------------------------------------------------------------------- |
| Repository          | <https://github.com/JetBrains/intellij-community>                         |
| Licence             | **Apache 2.0** (confirmed in `LICENSE.txt`: the open-source build "consist[s] of open source software subject to the Apache 2.0 License") |
| Pinned commit       | `9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5` (2026-10-07, `master`)         |
| Upstream build file | `intellij.idea.community.main.iml` (`*.iml` is the source of truth upstream; `BUILD.bazel` is generated) |

### Obligations for every derived file

Apache 2.0 permits the port. It requires, per derived file:

1. the **licence header**, retained and naming JetBrains;
2. a **notice** that the file is derived, naming the upstream path and the pinned commit;
3. the **`NOTICE`** file: `intellij-community` ships one, and a redistribution that includes derived
   source must carry its contents. `merge-java/THIRD_PARTY_NOTICES.md` is the home for it
   (created by step 4.7);
4. a statement of **what changed**, because § 4(b) of the licence requires modified files to carry
   prominent notices.

Step 4.7 owns all four. A derived file without them is a licensing defect, not a style problem.

---

## 2. Correcting the paths in the original instruction

The instruction pointed at `platform/diff-impl/test/com/intellij/diff/`. **There is no `test` directory
there.** The real layout is:

| Instruction said                             | Actually is                                                                               |
| -------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `platform/diff-impl/test/…`                  | `platform/diff-impl/tests/testSrc/…`                                                      |
| merge engine in `platform/diff-impl/src`     | **split**: the algorithms are in `platform/util/diff/src`; `diff-impl` holds the UI/model |
| `ApplyNonConflictingMode`                    | `ApplyNonConflictsAction.kt` **and** the model method `MergeConflictModel.resolveAllChangesAutomatically()` |
| `MergeModel`                                 | `MergeConflictModel` (text) and `MergeModelBase` (line bookkeeping)                       |

Two further facts the instruction did not mention, and both matter:

- The engine is **Kotlin**, not Java, for exactly the files worth porting.
- The instruction's three "design highlights" are real and are confirmed below, but two of them are
  implemented in files it did not name (`MergeResolveUtil.kt`, `TrimUtil.kt`, `ByWordRt.kt`).

---

## 3. Upstream source map

Paths are relative to the repository root; `util/` = `platform/util/diff/src/com/intellij/diff`, and
`impl/` = `platform/diff-impl/src/com/intellij/diff`.

### 3.1 The three algorithm files that carry the value

| Upstream file                            | Size    | What it is                                                                                     | Take?                    |
| ---------------------------------------- | ------- | ---------------------------------------------------------------------------------------------- | ------------------------ |
| `util/comparison/MergeResolveUtil.kt`    | 9.7 KB  | `tryResolve` (`SimpleHelper`) and `tryGreedyResolve` (`GreedyHelper`): word-level three-way auto-resolve, with a whitespace-insensitive retry | **Yes — the core prize** |
| `util/comparison/ComparisonMergeUtil.kt` | 6.4 KB  | Builds `MergeRange`s from two two-way diffs; `IgnoringChangeBuilder` re-emits ignored-but-not-equal runs as changes | **Yes** |
| `util/util/MergeRangeUtil.kt`            | 9.6 KB  | `getMergeType(...)`: the emptiness × equality decision table producing `{INSERTED, DELETED, MODIFIED, CONFLICT}` | **Yes — pure logic** |

### 3.2 Comparison primitives

| Upstream file                             | Size     | Role                                                                | Take?                |
| ----------------------------------------- | -------- | ------------------------------------------------------------------- | -------------------- |
| `util/comparison/ByLineRt.kt`             | 14 KB    | Line-level Myers comparison (`Rt` = runtime, dependency-free)       | Partially — see note |
| `util/comparison/ByWordRt.kt`             | 38 KB    | Word-level comparison (the two-pass second pass)                    | Partially — see note |
| `util/comparison/ByCharRt.kt`             | 8.7 KB   | Character-level comparison (inner fragments)                        | Partially            |
| `util/comparison/TrimUtil.kt`             | 19 KB    | Whitespace-insensitive matching and boundary expansion              | **Yes**              |
| `util/comparison/ChunkOptimizer.kt`       | 9.9 KB   | Merges adjacent fragments so chunks are not split arbitrarily       | Consider             |
| `util/comparison/ComparisonPolicy.kt`     | 243 B    | `DEFAULT`, `TRIM_WHITESPACES`, `IGNORE_WHITESPACES`                 | **Yes — enum only**  |
| `util/comparison/CharacterUtils.kt`       | 13 KB    | Character classification for word boundaries                        | Yes, as needed       |
| `util/comparison/ChangeCorrector.kt`      | 4.5 KB   | Nudges a change boundary to a better position                       | Consider             |
| `util/comparison/LineFragmentSplitter.kt` | 6 KB     | Splits line fragments for finer presentation                        | No (presentation)    |

**Note on `By*Rt.kt`.** These are large and are wired to IntelliJ's `DiffConfig`, `Range`,
`DiffFragment`, `FairDiffIterable` and cancellation model. Translating them wholesale would import a
framework, not an algorithm. Steps 4.8–4.9 therefore implement **the smallest Myers differ this module
needs** (line pass, then word pass over changed blocks) in native Java, and take from `ByWordRt` its
**word-boundary rule and policy handling** rather than its class structure. That is a deliberate
narrowing; it is recorded so nobody assumes it was an oversight.

### 3.3 The model layer (read for its decision rules, not for its code)

| Upstream file                                            | Why it is worth reading                                                           |
| -------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `impl/merge/MergeConflictModel.kt`                       | `canResolveChangeAutomatically` — the exact precondition for auto-apply (see § 6) |
| `impl/merge/MergeDiffBuilder.kt`                         | The **two-pass** orchestration: line fragments first, then a word pass in `MergeResolveUtil`; also `patchConflictTypes` |
| `impl/merge/MergeImportUtil.kt`                          | Import-block-specific handling — overlaps our `ImportConflictResolver`            |
| `impl/merge/LangSpecificMergeConflictResolver.kt`        | The `SEMANTIC` seam — the one part we cannot port (§ 7)                           |
| `impl/merge/LangSpecificMergeConflictResolverWrapper.kt` | How `TEXT` and `SEMANTIC` are promoted onto a conflict                            |
| `impl/merge/TextMergeChange.kt`                          | `isOnesideAppliedConflict` / `markOnesideAppliedConflict` — partial application   |
| `util/util/MergeConflictType.kt`                         | `Type` enum and `canBeResolved()`                                                 |
| `util/util/MergeConflictResolutionStrategy.kt`           | `DEFAULT` / `TEXT` / `SEMANTIC` — **the direct analogue of our `AnalysisLevel`**  |

### 3.4 Test files — the vectors to integrate

| Upstream file                                            | Size    | What to take                                                                                 |
| -------------------------------------------------------- | ------- | -------------------------------------------------------------------------------------------- |
| `tests/testSrc/…/merge/MergeTest.kt`                     | 26.6 KB | **Concrete case tables** — see § 5                                                           |
| `tests/testSrc/…/merge/MergeTestBase.kt`                 | 16.3 KB | The `doTest1` / `doTest2` / `doTestN` harness shape and `assertType`/`assertRange`/`assertContent` vocabulary |
| `tests/testSrc/…/merge/MergeAutoTest.kt`                 | 4 KB    | Randomized undo/apply/ignore stress; **infrastructure-heavy, depends on EDT and undo stack** |
| `tests/testSrc/…/comparison/ComparisonUtilAutoTest.kt`   | 30 KB   | Randomized comparison stress                                                                 |
| `tests/testSrc/…/comparison/ComparisonUtilTestBase.kt`   | 18.4 KB | Comparison test harness                                                                      |
| `tests/testSrc/…/comparison/LineComparisonUtilTest.kt`   | 12.3 KB | Line-diff expectations                                                                       |
| `tests/testSrc/…/comparison/WordComparisonUtilTest.kt`   | 13.6 KB | Word-diff expectations                                                                       |
| `tests/testSrc/…/comparison/IgnoreComparisonUtilTest.kt` | 18.2 KB | Whitespace-policy expectations                                                               |
| `tests/testSrc/…/comparison/MergeResolveUtilTest.kt`     | 5.2 KB  | **Direct tests of `tryResolve`** — the closest analogues                                     |
| `tests/testSrc/…/comparison/*ComparisonMergeUtilTest.kt` | 3–7 KB  | Tests of the merge-range builder                                                             |
| `tests/testSrc/…/comparison/TrimUtilTest.kt`             | 2.5 KB  | Whitespace trimming expectations                                                             |

**The randomized tests are taken as a generator, not as a port.** `MergeAutoTest`'s property it checks
is worth having — *after any sequence of apply / ignore / resolve / edit, the change ranges stay ordered
and undo restores the prior state* — but its harness is bound to `ApplicationManager`, `Disposable` and
the editor stack. Step 4.13 re-expresses the **property** over our plain-text API with a seeded
generator, which is what makes it runnable here.

---

## 4. The architectural decision this port must respect

**Decision: we do not depend on the IntelliJ Platform; we translate the algorithms.**

Reasons, in order of weight:

1. **Dependency weight.** `platform/util/diff` and `platform/diff-impl` do not stand alone. They pull
   `platform/util`, `platform/core-api`, the `Range`/`DiffFragment` model, the cancellation model, and
   — for `diff-impl` — the whole editor and PSI stack. `merge-java` is a library with a CLI whose
   current dependency set is JDK + javac + Jackson.
2. **Language.** The valuable files are Kotlin; the reactor is Java, and the gate compiles at release 25
   with no Kotlin plugin. Translating keeps one language in the module.
3. **Root `AGENTS.md` § 1.** Source-visible, IDE-navigable wiring is the project's first rule. A
   translated, committed, navigable class satisfies it; a binary dependency on a platform the reader
   does not have does not.

Anything we depend on from outside must be a **pure-Java leaf with no IntelliJ types**, which is why
the port's landing zone is a `com.codebuddy.merge.jetbrains` package rather than a new module (§ 5.1).

---

## 5. Algorithms to port, in dependency order

### 5.1 The landing zone

`merge-java` gains a sub-package `com.codebuddy.merge.jetbrains`, with three tiers:

| Tier           | Contents                                                                               | Depends on                       |
| -------------- | -------------------------------------------------------------------------------------- | -------------------------------- |
| **text**       | `ComparisonPolicy`, `TextLine`, `WordTokenizer`, `MyersDiff` (line + word), `TrimUtil` | JDK only                         |
| **merge**      | `MergeRange`, `MergeType`, `MergeRangeUtil`, `MergeResolveUtil`                        | `text` only                      |
| **adapter**    | `JetBrainsMergeDetector`, `JetBrainsMergeResolver` — the `ConflictResolver` implementations that bridge into the existing model | `merge` + the module's own model |

The tiers matter: everything up to and including `merge` is **independently testable with no merge
model at all**, which is exactly how upstream's tests are written and what makes their vectors
portable.

### 5.2 `MergeRangeUtil.getMergeType` — the conflict taxonomy

The whole decision table is emptiness and equality, which is why it ports cleanly. With `L`, `B`, `R`
the three sides and `eq(x,y)` a policy-aware content comparison:

| Base empty? | Left empty? | Right empty? | `eq(L,R)`?           | Type                                                                                   | Change on |
| ----------- | ----------- | ------------ | -------------------- | -------------------------------------------------------------------------------------- | --------- |
| yes         | yes         | no           | –                    | `INSERTED`                                                                             | right     |
| yes         | no          | yes          | –                    | `INSERTED`                                                                             | left      |
| yes         | no          | no           | yes                  | `INSERTED`                                                                             | both      |
| yes         | no          | no           | no                   | `CONFLICT`                                                                             | both      |
| no          | yes         | yes          | –                    | `DELETED`                                                                              | both      |
| no          | no          | no           | `eq(B,L) && eq(B,R)` | `MODIFIED`                                                                             | neither   |
| no          | –           | –            | `eq(B,L)`            | `DELETED` if R empty else `MODIFIED`                                                   | right     |
| no          | –           | –            | `eq(B,R)`            | `DELETED` if L empty else `MODIFIED`                                                   | left      |
| no          | no          | no           | `eq(L,R)`            | `MODIFIED`                                                                             | both      |
| no          | no          | no           | otherwise            | `CONFLICT`, resolvable iff **`L` and `R` are both non-empty** and the resolver says so | both      |

Two rules in that table are the ones we are missing today:

- **A conflict is only resolvable when both sides are non-empty** (`!isLeftEmpty && !isRightEmpty`).
  A deletion competing with a modification is never auto-resolved, which is upstream's own
  modify/delete guard and matches our `DESIGN_NEVER_AUTO_RESOLVED.md` § 2 exactly.
- **The `base empty` branch is four distinct cases, and its `CONFLICT` case (both sides non-empty and
  differing) is *not* resolvable.** Two branches inserting different content at the same point have
  no correct order, so upstream refuses. Our `MEMBER_ADD` resolver covers the case where the inserted
  content is *recognisably distinct members*; this table is the fallback for everything else, and it
  says refuse.

`getMergeType` also has the `trueEquality` parameter: when the policy-equal sides are *not*
byte-equal, the type is still `MODIFIED` but **both** sides are reported as changed. That is how a
whitespace-only difference stays visible without becoming a conflict.

### 5.3 `MergeResolveUtil.tryResolve` — the simple word-level resolve

This is the algorithm that answers "both sides edited this block — does the union exist?".

```
changes = ByWordRt.compare(leftText, baseText, rightText, policy)
for each fragment in changes:
    appendBase(range from last offsets to fragment start)          // unchanged: take base
    if !appendConflict(range up to fragment end, policy): return null   // CONFLICT -> refuse
appendBase(trailing)
return built text
```

with

```
appendBase(range):
    if range empty: return
    if isUnchanged(range): append(base)                    // both sides == base
    else:
        type = getConflictType(range)
        if type.isChange(LEFT):  append(left)
        elif type.isChange(RIGHT): append(right)
        else: append(base)

appendConflict(range):
    if type == CONFLICT: return false      // -> whole resolve refuses
    append(left if type.isChange(LEFT) else right)
```

**And the retry that makes whitespace safe:** `tryResolve` runs the whole thing with
`ComparisonPolicy.DEFAULT`; if that returns `null` it runs it again with
`ComparisonPolicy.IGNORE_WHITESPACES`. So a block that is a conflict only because of re-indentation is
resolved on the second pass. Upstream comments that "resolve results are explicitly verified by user
and can be safely undone" — **we do not have that property**, because our resolutions can be applied
automatically. See § 6 for how we restrict it.

### 5.4 `MergeResolveUtil.tryGreedyResolve` — the greedy variant

`GreedyHelper` does not use the three-way comparison at all. It computes **two independent two-way
diffs** (`base→left`, `base→right`), walks them, and collects each maximal *base-overlapping* run of
changes from both sides. For each such block of base offsets it then applies:

| Left block | Right block | Action                                                    |
| ---------- | ----------- | --------------------------------------------------------- |
| empty      | empty       | skip                                                      |
| non-empty  | empty       | append left's inserted content                            |
| empty      | non-empty   | append right's inserted content                           |
| non-empty  | non-empty   | if policy-equal: append the **shorter**; else **refuse**  |

Deletions are applied unconditionally by advancing `lastBaseOffset` — that is what makes it "greedy",
and it is exactly the behaviour our structural-residual rule must *not* adopt wholesale (§ 6).

The switch between the two is a global flag upstream: `DiffConfig.USE_GREEDY_MERGE_MAGIC_RESOLVE`.
**We must not port a mutable global.** The choice is a parameter of our resolver, defaulting to the
simple strategy, with the greedy one available and recorded on the resolution.

### 5.5 `ComparisonMergeUtil` — building merge ranges from two two-way diffs

`FairMergeBuilder.add(range1, range2)` is the core: given one unchanged-run from each side, it finds
their overlap in base coordinates, marks the overlap equal, and returns which side to advance. The
`IgnoringChangeBuilder` subclass is the interesting part — after marking a change, it walks the
*unchanged* run preceding it and **re-emits sub-runs where the policy says equal but byte equality says
different** as changes of their own. That is how "ignore whitespace" yields a merge that still shows
the whitespace difference rather than silently swallowing it.

### 5.6 `TrimUtil` — whitespace-insensitive matching

Provides the "expand to a better boundary" operations that make whitespace-insensitive diffing produce
sane hunks instead of empty ones. Take it for:

- `ComparisonPolicy.TRIM_WHITESPACES` semantics (leading/trailing whitespace of a line is ignored, but
  the line is not otherwise normalised);
- `IGNORE_WHITESPACES` semantics (all whitespace inside is ignored for matching);
- the boundary-expansion helpers, so a match that ignores whitespace still reports the *real* offsets.

Our current whitespace handling is `line.trim()` inside a `LinkedHashSet` (`ConflictDetectionService`),
which conflates "ignore leading and trailing" with "match anywhere in the file". These are different
operations and the port separates them.

---

## 6. The rule that must survive the port

Upstream comment, `MergeResolveUtil.kt`:

> Here we assume, that resolve results are explicitly verified by user and can be safely undone. Thus
> we trade higher chances of incorrect resolve for higher chances of correct resolve.

**We cannot assume that.** Upstream's auto-resolve is safe because a person sees the result in a
three-pane editor and can undo it; our resolutions can be written to a file by `MergeFileTool` with
nobody watching. So the port is bounded by three rules, each of which is a test:

1. **A resolution the word-level pass produces is `REVIEW` at most, never `AUTO`,** unless it is
   *exactly* the union of two non-overlapping additive changes — which is what `MEMBER_ADD` and
   `IMPORT_ADD` already are. A `tryResolve` result whose input had **any deletion on either side** is
   `REVIEW`.
2. **The two-pass whitespace retry never upgrades the kind.** An `IGNORE_WHITESPACES` pass that
   succeeds where `DEFAULT` failed is recorded with a **warning** naming the policy, because the
   result is not byte-identical to either input and the difference is formatting.
3. **A modify/delete-shaped block is never auto-resolved.** Upstream encodes this twice —
   `MergeRangeUtil` requires both sides non-empty, and `MergeConflictModel.canResolveChangeAutomatically`
   rejects `ConflictType.MODIFIED_DELETED` / `DELETED_MODIFIED` before anything else. We take both.
   This is a **cross-check on our own design, not a new rule**: it is `DESIGN_NEVER_AUTO_RESOLVED.md`
   § 2's substitutive/additive split, arrived at independently.

`DESIGN_NEVER_AUTO_RESOLVED.md` is **not relaxed by this port**. Its § 9 test for revisiting the
boundary — resolved symbols on both sides, additive/substitutive distinguished with evidence, a loud
failure mode — is not met by a word-level text comparison, and a word-level comparison is what this
port adds.

---

## 7. What we deliberately do not take

| Left upstream                                                                         | Why                                                                               |
| ------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `LangSpecificMergeConflictResolver` (SEMANTIC)                                        | Requires PSI and a running IDE; the `SEMANTIC` *level* is worth recording, the implementation is not portable |
| `BinaryMergeTool`                                                                     | Byte-level, a different problem; `merge-java` is a text module                    |
| `MergeThreesideViewer`, `MergeWindow`, all `*Action.kt`, `ThreesideMergeHighlighters` | UI. Our presentation is the `review/` jsx6 page, which already exists             |
| `MergeModelBase` line bookkeeping and undo                                            | Bound to `Document`, write commands and the undo stack; our equivalent is the result text plus `BranchConflictStore` |
| `MergeImportUtil` PSI import handling                                                 | Our `ImportConflictResolver` already does the text-level half; take only the *idea* of an import range if a test shows a gap |
| `MergeRequestProcessor`, `DiffRequest*`                                               | Request/IDE plumbing                                                              |
| The mutable `DiffConfig` global switches                                              | A library must not have process-global behaviour switches; they become parameters |
| `MergeAutoTest` / `*AutoTest` harnesses                                               | Taken as a **property** to re-express (§ 3.4), not as code                        |

---

## 8. The correspondence to our own concepts

This is the part worth reading twice, because it means the port **extends** our design rather than
competing with it.

| JetBrains                                                             | JCodeBuddy                                                  | Relationship                                            |
| --------------------------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------- |
| `MergeConflictResolutionStrategy.DEFAULT/TEXT/SEMANTIC`               | `AnalysisLevel` (`TEXT_LOCAL` → … → `PROJECT_TYPES`)        | Same idea, different axis. Upstream's `TEXT` = decided by intra-line text; `SEMANTIC` = decided by structure. Our `STRUCTURE` ≈ `SEMANTIC`, our `TEXT_LOCAL`/`TEXT_FILE` ≈ `DEFAULT`, and **`TEXT` (intra-line) has no level of ours at all** — that is the gap step 4.11 closes |
| `canResolveChangeAutomatically`                                       | `MergeFileTool.decide` + `AnalysisLevel` arbitration        | Ours is richer (it arbitrates between *competing* claims); theirs is a per-change precondition. Take theirs as an extra precondition |
| `resolveAllChangesAutomatically()`                                    | "Apply all resolved" in `review/src/decisions.js`           | Same operation, already built (step 4.3)                |
| `getAutoResolvableChanges()`                                          | `MergeFileTool`'s block loop                                | Ours is per block, theirs per change — ours is stricter |
| `isOnesideAppliedConflict`                                            | `LEFT_MANUAL` / `LEFT_DEFERRED` / `LEFT_MULTIPLE_AUTOMATIC` | Both model "one side applied, the other still open"     |
| `IgnorePolicy` (`DEFAULT`/`TRIM_WHITESPACES`/`IGNORE_WHITESPACES`)    | *(nothing)*                                                 | **A real gap.** Ours has no whitespace policy at all    |
| `MergeConflictType.Type` (`INSERTED`/`DELETED`/`MODIFIED`/`CONFLICT`) | `ConflictType` (12 domain types)                            | Orthogonal: theirs is the *shape* of the change, ours is the *domain kind*. Both are needed; a domain type without a shape cannot say "both sides inserted" |

The honest summary: **we have the domain taxonomy and the arbitration; they have the characterisation
and the intra-line comparison.** The port adds the second half.

---

## 9. Why this makes the tool better, stated as failures it removes

Each is a case measured or reasoned about in this repository, not a hypothetical.

| Today                                                                                   | After the port                                                                                      | Step      |
| --------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | --------- |
| Two branches edit **different words of one line** → we escalate to a human, because our unit is the line | The word-level pass resolves the union, or says plainly why not                    | 4.9       |
| A branch **re-indents** a block the other edited → whitespace churn reads as a conflict | `IGNORE_WHITESPACES` retry resolves it, with a warning naming the policy                            | 4.10      |
| "Both sides changed this region" is our finest diagnosis                                | "both inserted", "left modified / right unchanged", "both modified identically", "genuine conflict" | 4.8       |
| No whitespace policy exists; `line.trim()` is the whole story                           | Three named policies, applied consistently across detection and resolution                          | 4.10      |
| `STRUCTURAL_CHANGE` fires on adjacent-but-independent edits (step 4.5's residual problem, partly fixed there by region arithmetic) | The shape classification says `INSERTED`/`MODIFIED` and there is no residual to veto with | 4.8, 4.12 |
| The evidence scale has no level for intra-line comparison                               | `AnalysisLevel` gains one, ordered below file-text evidence                                         | 4.11      |

**The measurable target** for the port is a before/after count over the ported JetBrains vectors plus
our own fixtures: conflicts escalated to a human, and blocks left `LEFT_MANUAL`, must both fall, with
zero new incorrect applications against `DESIGN_NEVER_AUTO_RESOLVED.md`. Step 4.13 produces that
number; a claim without it is not evidence.

---

## 10. Upstream test vectors, transcribed for `merge-java`

All from `MergeTest.kt` (`_` is a line separator; `!N` downstream denotes line N of the result
document; text is lowercase words for readability). These become
`src/test/resources/fixtures/jetbrains-*` following `THREE_WAY_FIXTURES.md`, so the existing
`ThreeWayFixture` harness runs them unchanged.

### 10.1 Change-type vectors (`testChangeTypes`, `testLastLine`)

Format: `left | base | right` → expected shape(s).

| left     | base     | right    | expected                          |
| -------- | -------- | -------- | --------------------------------- |
| `""`     | `""`     | `""`     | no changes                        |
| `x`      | `x`      | `y`      | 1 × `MODIFIED` right              |
| `x`      | `y`      | `x`      | 1 × `MODIFIED` both               |
| `x_Y`    | `Y`      | `Y_z`    | `INSERTED` left; `INSERTED` right |
| `Y_z`    | `x_Y_z`  | `x_Y`    | `DELETED` left; `DELETED` right   |
| `X_Z`    | `X_y_Z`  | `X_Z`    | 1 × `DELETED` both                |
| `X_y_Z`  | `X_Z`    | `X_y_Z`  | 1 × `INSERTED` both               |
| `x`      | `y`      | `z`      | 1 × `CONFLICT` both               |
| `z_Y`    | `x_Y`    | `Y`      | 1 × `CONFLICT` both               |
| `z_Y`    | `x_Y`    | `k_x_Y`  | 1 × `CONFLICT` both               |
| `x_Y`    | `Y`      | `z_Y`    | 1 × `CONFLICT` both               |
| `x_Y`    | `Y`      | `z_x_Y`  | 1 × `CONFLICT` both               |
| `x_Y`    | `x_z_Y`  | `z_Y`    | 1 × `CONFLICT` both               |
| `x`      | `x_`     | `x`      | 1 × `DELETED` both                |
| `x_`     | `x_`     | `x`      | 1 × `DELETED` right               |
| `x_`     | `x_`     | `x_y`    | 1 × `MODIFIED` right              |
| `x`      | `x_`     | `x_y`    | 1 × `CONFLICT` both               |
| `x_`     | `x`      | `x_y`    | 1 × `CONFLICT` both               |

### 10.2 Word-level resolve vectors (`testResolve`) — **the most valuable set**

These exercise `tryResolve`'s two passes directly.

| left        | base            | right       | resolved content | note                                      |
| ----------- | --------------- | ----------- | ---------------- | ----------------------------------------- |
| `y z`       | `x y z`         | `x y`       | `y`              | both sides deleted around `y`             |
| `y z_Y_x y` | `x y z_Y_x y z` | `x y_Y_y z` | `y_Y_y`          | two independent conflicts, both resolved  |
| `y z_Y_x y` | `x y z_Y_x y z` | `x y_Y_y z` | `y z_Y_y`        | left applied by hand, then right resolved |

And the **invalidating-edit** vectors — a result that stops being resolvable once a person edits the
output:

| left  | base    | right | action                          | expectation                              |
| ----- | ------- | ----- | ------------------------------- | ---------------------------------------- |
| `y z` | `x y z` | `x y` | resolve                         | content `y`                              |
| `y z` | `x y z` | `x y` | replace result line 2 with `U`  | `canResolveConflict()` becomes **false** |

That second row is the one that matters most for us: **resolvability is a property of the current
output, not of the original inputs.** Our step 4.3 already re-checks the signature; this makes the
upstream rule explicit and gives it a vector.

### 10.3 Non-conflicting auto-apply vectors (`testNonConflictsActions`)

A three-text case where applying non-conflicting changes from `BASE`, `LEFT` and `RIGHT` respectively
yields three different, exactly specified outputs, and the count of **remaining** changes is asserted
per side (`BASE` → 1 remaining, `LEFT` → 3, `RIGHT` → 2). There is a modify/delete block in the middle
plus an insert-left, a remove-right and a "both modified identically" block — a compact test of the
whole taxonomy.

This is the vector set to run against `MergeFileTool` first, because its expected outputs are literal
text and its remaining-change counts are exact.

### 10.4 Vectors that pin the *refusals* (`testDoNotAutoResolve*`)

| left    | base    | right   | conflict type      | expectation                                |
| ------- | ------- | ------- | ------------------ | ------------------------------------------ |
| `""`    | `A_B_C` | `A_B_C` | `DELETED_MODIFIED` | cannot resolve; auto-apply changes nothing |
| `A_B_C` | `A_B_C` | `""`    | `MODIFIED_DELETED` | cannot resolve; auto-apply changes nothing |
| `""`    | `A_B_C` | `A_B_C` | *(none)*           | **control**: the same one-sided change *does* auto-resolve when the type is not a modify/delete file conflict |

The control row is the point: it proves the refusal comes from the conflict *type*, not from the
text. Port all three, because a port that only took the first two would pass while being wrong.

### 10.5 Whitespace-policy vectors (`testIgnored`)

With `IGNORE_WHITESPACES` and the three texts `" x_new_y_z"`, `"x_ y _z"`, `"x_y _new_ z"`:
five changes with exact ranges, types (`MODIFIED` left, `INSERTED` left, `MODIFIED` both,
`INSERTED` right, `MODIFIED` right), a single auto-apply producing `" x_new_y_new_ z"` with all five
resolved, and an undo/re-apply cycle producing a *different* correct result. The same texts under
`DEFAULT` produce **one** `CONFLICT` covering lines 0–4 — the whitespace policy is the only difference.

That is the cleanest available demonstration that the policy pass earns its place, and step 4.10 uses
it as its acceptance test.

---

## 11. Reproducing the upstream checkout

`.tmp/` is the scratch directory (`AGENTS.md` § 2) and `.tmp/` is gitignored, so the checkout is
disposable and must never be a build input. To recreate it:

```bash
git clone --filter=blob:none --no-checkout --depth 1 --sparse \
  https://github.com/JetBrains/intellij-community .tmp/jb-ic
git -C .tmp/jb-ic sparse-checkout set \
  platform/diff-impl platform/diff-api/src platform/util/diff platform/util/src
git -C .tmp/jb-ic checkout master
```

Note the order: `--no-checkout` plus `sparse-checkout set` leaves an **empty working tree** — the
files appear only after the explicit `checkout`. A `sparse-checkout set` that "succeeds" against an
empty tree is the failure mode to expect, not an error to debug.

Pin verification:

```bash
git -C .tmp/jb-ic rev-parse HEAD
# 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5
```
