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
framework, not an algorithm. Step 4.8 therefore implements **the smallest differ this module needs** (line
pass, then word pass over changed blocks) in native Java, and takes from `ByWordRt` its **word-boundary
rule and policy handling** rather than its class structure. That is a deliberate
narrowing; it is recorded so nobody assumes it was an oversight.

> **The differ is an LCS table, not Myers — decided during step 4.8, after measurement.** The plan said
> Myers, and Myers was built first. It is not what shipped, and the reason is a fact about *this* use
> rather than a preference: a frontier-based Myers search needs its tie-breaking to agree between the
> search and the walk back, and every disagreement produces output that still looks like a diff — ranges,
> in order, describing *a* difference — while placing a change a line away from the real one. Measured:
> four rounds of fixes each moved the error rather than removing it, on vectors as small as one line
> against two. A longest-common-subsequence table has no tie-break to agree on, the walk reads the same
> table it built, and its invariant is checkable by reading the code.
>
> **The trade, stated plainly:** LCS is O(n·m) in memory where Myers is O(n+m), so the comparison is
> **bounded by a cell budget** (4 million cells by default — a 2,000-line fully-rewritten region) and
> **refuses** beyond it rather than degrading. For a merge tool that is the right way round: the input is
> one conflict hunk, not a repository, the common edges are trimmed before the table is sized, and a diff
> that is provably the difference beats one that is faster and occasionally off by a line. § 5.6's parity
> gate is where a case LCS handles worse would surface, and the class javadoc says the same thing where
> the next reader will be.
>
> **Consequence for the vectors:** where two texts share a repeated line, more than one minimal script
> exists and they are all correct. The ported tests therefore accept any *minimal* description of a
> difference where upstream's vectors pin one — see `TextCompareTest.assertOneOf`, which still catches a
> wrong answer, and the note there on why asserting one split would assert the tie-break instead of the
> behaviour.

### 3.3 The model layer (read for its decision rules, not for its code)

| Upstream file                                            | Why it is worth reading                                                           |
| -------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `impl/merge/MergeConflictModel.kt`                       | `canResolveChangeAutomatically` — the exact precondition for auto-apply (see § 7) |
| `impl/merge/MergeDiffBuilder.kt`                         | The **two-pass** orchestration: line fragments first, then a word pass in `MergeResolveUtil`; also `patchConflictTypes` |
| `impl/merge/MergeImportUtil.kt`                          | Import-block-specific handling — overlaps our `ImportConflictResolver`            |
| `impl/merge/LangSpecificMergeConflictResolver.kt`        | The `SEMANTIC` seam — the one part we cannot port (§ 8)                           |
| `impl/merge/LangSpecificMergeConflictResolverWrapper.kt` | How `TEXT` and `SEMANTIC` are promoted onto a conflict                            |
| `impl/merge/TextMergeChange.kt`                          | `isOnesideAppliedConflict` / `markOnesideAppliedConflict` — partial application   |
| `util/util/MergeConflictType.kt`                         | `Type` enum and `canBeResolved()`                                                 |
| `util/util/MergeConflictResolutionStrategy.kt`           | `DEFAULT` / `TEXT` / `SEMANTIC` — **the direct analogue of our `AnalysisLevel`**  |

### 3.4 Test files — the vectors to integrate

| Upstream file                                            | Size    | What to take                                                                                 |
| -------------------------------------------------------- | ------- | -------------------------------------------------------------------------------------------- |
| `tests/testSrc/…/merge/MergeTest.kt`                     | 26.6 KB | **Concrete case tables** — see § 11                                                          |
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

## 3.5 The question this port actually answers

The instruction that produced this document asked for JetBrains' **test cases** to be integrated. That
is the wrong ask, and taking it literally would have produced a naive port: a faithful translation of
`tryResolve` that applies whatever it returns. The useful ask is the one this document is organised
around:

> **Read their code for resolution steps and resolution checks, decide per step whether its result is
> mechanically determined or merely plausible, apply the first kind, and deliver the second kind to a
> person as an open suggestion.**

So **every artifact is classified before it is ported**, on one axis with exactly two values:

| Class          | Meaning | Where it goes                                                          |
| -------------- | ------- | ---------------------------------------------------------------------- |
| **SAFE**       | The answer is *a function of the inputs*. Given the same three sides the same answer follows, and there is no reading of the code under which a human's intent had to be guessed. | An `AUTO` resolution, applied subject to `ResolutionVerifier` |
| **SUGGESTION** | The answer is *plausible and useful, and could be wrong*. Producing it costs the tool nothing and saves a reviewer real work; applying it would be inventing a decision. | The open-suggestion channel — [`SUGGESTIONS.md`](SUGGESTIONS.md) |

**A ported step that cannot be confidently placed in one class is not ported yet.** That is not a
formality: it is the mechanism that keeps [`DESIGN_NEVER_AUTO_RESOLVED.md`](../DESIGN_NEVER_AUTO_RESOLVED.md)
true while still taking everything upstream has to offer.

The classification is worked out step by step in § 5 and applied by the port steps it schedules. The "translate, do not depend"
decision that constrains how any of it lands is § 4.

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
the port's landing zone is a `com.codebuddy.merge.jetbrains` package rather than a new module (§ 6.1).

---

## 5. The analysis: every resolution step, classified

This is the substance of the port. Each upstream resolution step is stated, its guard named with
`file:line`, and classified by the rule in § 3.5. The upstream files are
`platform/util/diff/src/com/intellij/diff/comparison/MergeResolveUtil.kt` (cited as
`MergeResolveUtil.kt`), `.../MergeRangeUtil.kt`, `.../ComparisonMergeUtil.kt`, and
`platform/diff-impl/src/com/intellij/diff/merge/MergeConflictModel.kt`.

### 5.1 Resolution steps

| #   | Step (upstream)                                                                            | What it produces                                         | Class                        | Why                                                                              |
| -   | ---------------                                                                            | ----------------                                         | -----                        | ---                                                                              |
| R1  | `tryResolve` / `SimpleHelper` (`MergeResolveUtil.kt:22`, helper `:82`)                     | The three-way merge text, or `null`                      | **SAFE**, with one carve-out | Every appended region is chosen by `isUnchangedRange` (both sides equal base), by "only the left changed", or by "only the right changed". Both sides changed *differently* is refused outright (`appendConflict` returns false on `Type.CONFLICT`, `:142-154`). No decision about intent is made anywhere. **The carve-out:** the result is byte-identical to neither input when both sides changed identically, so it is safe to *apply* but its relationship to the original bytes must be recorded — see check C7 |
| R2  | The whitespace retry (`MergeResolveUtil.kt:29-32`)                                         | A second attempt under `IGNORE_WHITESPACES`              | **SUGGESTION**               | The result differs from the inputs by formatting. A reviewer confirming "yes, this whitespace difference is churn" is making a judgement, and upstream records nowhere that the retry fired |
| R3  | `tryGreedyResolve` / `GreedyHelper` (`MergeResolveUtil.kt:53`, helper `:175`)              | Merge text, or `null`                                    | **SPLIT** — see below        | Its composition is mechanical; its *scope* is what upstream itself calls a trade |
| R4  | Deletions applied unconditionally (`MergeResolveUtil.kt:238-239`, comment "merge and apply deletions") | Both sides' deletions merged into one block  | **SUGGESTION**               | The comment above the function states the trade explicitly (`:39-40`): *"we assume, that resolve results are explicitly verified by user and can be safely undone. Thus we trade higher chances of incorrect resolve for higher chances of correct resolve."* Applying a deletion neither side may have intended, with nobody watching, is the invisible-regression failure `DESIGN_NEVER_AUTO_RESOLVED.md` § 5.3 exists to prevent |
| R5  | Equal-insertion collapse (`MergeResolveUtil.kt:253-256`)                                   | The shorter of two policy-equal insertions               | **SAFE**                     | Policy-equal content, and the shorter is chosen deterministically. The only nuance is that "policy-equal" under a whitespace policy is not byte-equal — the same record-keeping as R1's carve-out |
| R6  | Differing-insertion refusal (`MergeResolveUtil.kt:259-260`, "we faced conflicting insertions - resolve failed") | `null`                              | **The refusal is the model** | Both sides non-empty and unequal is where a human's ordering decision lives. This is the rule that makes R1 and R3 honest, and it is the one we must port *first* |
| R7  | `ComparisonMergeUtil.buildSimple` / `FairMergeBuilder` (`ComparisonMergeUtil.kt:20,53,64`) | Ordered merge ranges                                     | **SAFE**                     | Pure bookkeeping: it walks each side's unchanged runs, marks overlaps equal, and advances whichever run ends first. It decides nothing about content |
| R8  | `IgnoringChangeBuilder.addIgnoredChanges` (`ComparisonMergeUtil.kt:149-185`)               | Sub-changes where the policy says equal but bytes differ | **SAFE**, and required       | It exists so that a whitespace-ignoring merge still *shows* the whitespace difference instead of silently swallowing it. Porting a whitespace policy without this would produce merges that quietly normalise formatting |

### 5.2 Why R3 is a split, and which half is which

`tryGreedyResolve` is two things wearing one name, and the instruction's naive reading conflates them.

**Mechanical half — SAFE.** It computes *two independent two-way diffs* (`base→left`, `base→right`,
`MergeResolveUtil.kt:183-184`), collects each maximal run of base-overlapping changes from both sides
(`:213-229`) and handles the block by a fixed rule (`:243-257`). Given the inputs, the answer follows.

**Scope half — SUGGESTION.** What makes it "greedy" is line `:239`: `lastBaseOffset = baseOffsetEnd`,
with deletions applied and never questioned. Upstream can afford that because a person then looks at
the result. We cannot.

So the port takes it as: **the greedy pass is a producer of suggestions, not of automatic answers.**
That satisfies the instruction's "port safe merge results as such and open suggestions as a new type"
in one step rather than two, and it is the honest reading — R3's *result* is worth offering precisely
because it resolves cases the simple pass refuses.

### 5.3 Checks worth porting (and one that is missing)

| #   | Check (upstream)                                                                                    | What it prevents                                                                             | Class          | Where it goes in our model |
| -   | ----------------                                                                                    | ----------------                                                                             | -----          | -------------------------- |
| C1  | `canBeResolved = !isLeftEmpty && !isRightEmpty && conflictResolver()` (`MergeRangeUtil.kt:69-70`)   | Auto-resolving a deletion competing with a modification; or two differing insertions         | **SAFE**       | Already our rule, independently derived — port as a named guard and keep 4.5's `residualSubsumed` working against it |
| C2  | Modify/delete file guard (`MergeConflictModel.kt:366`, `:383-386`)                                  | Auto-resolving when one side deleted the whole file (`MODIFIED_DELETED`, `DELETED_MODIFIED`) | **SAFE**       | **A cross-check, not a change**: our equivalent is `MergeFileTool`'s refusal to apply where the residual is not subsumed. Port the guard *name* and its test so the two rules are visibly the same rule |
| C3  | `!change.isResolved(Side.LEFT) && !change.isResolved(Side.RIGHT)` (`MergeConflictModel.kt:372-373`) | Resolving a change a human has already partly resolved                                       | **SUGGESTION** | A suggestion must not be offered for a conflict a reviewer has already touched — offer, do not apply |
| C4  | `resolutionStrategy !== TEXT \|\| !isChangeRangeModified(change)` (`MergeConflictModel.kt:374`) plus `isChangeRangeModified` (`:393-406`) | Overwriting a result range that no longer matches the base | **SAFE**   | Our blocks have no editable output document, so the equivalent is the *signature* check `DecisionRecorder` already performs. Port the principle: **a decision made against different text is not a decision** |
| C5  | `patchConflictTypes` (`MergeDiffBuilder.kt:130-136`)                                                | Presenting a conflict as unresolvable by one strategy when a stronger one could reach it     | **SUGGESTION** | The `SEMANTIC` strategy is not portable (PSI), but the *shape* is our `AnalysisLevel` arbitration. Take the shape, not the strategy |
| C6  | Region-order `check(...)` assertions (`ComparisonMergeUtil.kt:119-139`, `:162-163`)                 | A merge range list that is out of order or whose three sides disagree in length              | **SAFE**       | Port as the property the randomized test of § 11.6 asserts — upstream asserts it inline, we assert it over a generator |
| C7  | **Missing upstream: no check on the resolved text**                                                 | —                                                                                            | **The gap**    | Searched: `MergeResolveUtil.kt` and `ComparisonMergeUtil.kt` contain no validation of the returned text — only internal `check(...)` invariants. Upstream's safety story is the human in the editor (`:40`), not a verifier. **We have a verifier and must use it**: `ResolutionVerifier` currently runs only on `AUTO` resolutions (`ResolutionVerifier.apply`, "only an automatic resolution carries the promise that is being verified"), so a suggestion would bypass it. A suggestion must therefore be verified *as if* it were automatic — exactly the treatment `ConflictProposer` already requires of a proposal — and a failing verdict **labels** the suggestion rather than suppressing it |

C7 is the most valuable finding in this document. It says that **the verification gate is our
contribution, not theirs**, and it is what lets the SUGGESTION class be safe at all: a suggestion is
shown with its verification verdict attached, and the reviewer decides with that in front of them.

### 5.4 What the classification means for the port, in one place

| Class          | Steps                                                     | Lands as                                                          |
| -------------- | --------------------------------------------------------- | ----------------------------------------------------------------- |
| **SAFE**       | R1 (with C7 recording), R5, R7, R8; checks C1, C2, C4, C6 | An `AUTO` resolution gated by `ResolutionVerifier`                |
| **SUGGESTION** | R2, R3, R4; checks C3, C5                                 | The open-suggestion channel of [`SUGGESTIONS.md`](SUGGESTIONS.md) |
| **The model**  | R6                                                        | A refusal that must be preserved, with its test                   |

**Nothing is left unclassified**, which is the point: the two classes between them cover every
upstream step, and the port's steps 4.9–4.10 are organised by this table rather than by upstream's
file layout.

### 5.5 JetBrains is the floor, not the target

The maintainer's instruction of 2026-10-07 is explicit about the ambition:

> *"this tool must be better than JetBrains, JetBrains is the benchmark of minimum that has to be
> achieved where we overlap with JetBrains."*

That is a **two-part obligation**, and keeping the parts separate is what stops either from being
quietly dropped:

1. **Where we overlap, parity is mandatory.** If JetBrains resolves a case at the word level and we
   escalate it to a human, we are **worse** — and a port that only added machinery while leaving such a
   case escalated would have failed while looking like progress. Parity is therefore a **test**, not an
   aspiration (§ 5.6).
2. **Where we can exceed them, exceeding is the point.** The port must not stop at "now we do what they
   do". § 10 names the capabilities we already have and they do not, and turns each into a case we must
   win with a measurement rather than a claim.

#### What "overlap" means, precisely

The overlap is **intra-line and line-level three-way text merge**: given the same three sides as text,
which changes compose, which conflict, and what the composed result is. That is the whole of
`ComparisonMergeUtil` + `MergeResolveUtil` and it is the thing we are behind on. Every R and C row of
§ 5.1–5.3 is inside it.

Everything else we do — `ConflictType`'s twelve domain kinds, type resolution against a classpath,
`AnalysisLevel` arbitration, signature-keyed decision replay, `ResolutionVerifier` — is **outside** the
overlap. Upstream has no equivalent, so there is nothing to reach parity with; those are the rows by
which we exceed. § 10 is that list.

#### The three places parity would be a regression, not a gain

Porting upstream's behaviour wholesale would lose something we already have. Each is a case where the
**floor must not lower the ceiling**:

| Ours today                                                                             | Upstream's rule                                                 | What must survive |
| -------------------------------------------------------------------------------------- | --------------------------------------------------------------- | ----------------- |
| `MEMBER_ADD`: two branches appending a distinct method are resolved at `AnalysisLevel.STRUCTURE` (step 4.6) | A line-level comparison calls adjacent insertions `CONFLICT`, resolvable only if the word-level pass can compose them | Member-level recognition outranks line-level composition, and step 4.12 must not let the ported shape **demote** a `STRUCTURE` answer to a text conflict |
| `TypeChangeConflictResolver` resolves a widening against a classpath (`PROJECT_TYPES`) | Nothing — upstream has no type model                            | The ported word-level pass must never outrank a `PROJECT_TYPES` claim, which `AnalysisLevel` arbitration already guarantees; the port must not add a competing rule |
| `ResolutionVerifier` downgrades an automatic answer that cannot compile                | Nothing — upstream validates no resolved text at all (check C7) | The ported `AUTO` results pass through the verifier like every other automatic answer; a ported pass does not get an exemption |

### 5.6 The parity gate

**Parity is a build gate, and it is the minimum bar for calling the port done.** It has to be a test
because the failure it prevents is invisible: a port that resolves *more* cases than before but still
escalates one that upstream resolves looks like an improvement in every commit message and every
dashboard.

**Do** (step 4.13 owns it):

1. **Import upstream's vectors as the shared corpus.** § 11 transcribes them. They are the only
   available definition of "what JetBrains resolves", so they are the corpus rather than a
   hand-written approximation of one.
2. **Grade three outcomes per vector, not one.** A vector's upstream outcome is one of:
   *auto-resolved to text X*, *refused*, or *invalidating edit makes it unresolvable*. Ours must be
   respectively: resolved, with **the same text X**; refused; and unresolvable. Equality of the
   resolved text matters — "resolved something" is not parity.
3. **Fail on any regression, and name the vector.** A case where upstream resolves and we escalate is a
   **failure**, not a skip. A case where upstream refuses and we apply automatically is also a
   failure, and the more serious one: it is a `DESIGN_NEVER_AUTO_RESOLVED.md` breach wearing a
   port's clothes.
4. **Allow a documented, argued exception — and no other kind.** There is exactly one legitimate way to
   be "worse than upstream" on a vector: our `AnalysisLevel` arbitration or `ResolutionVerifier`
   *deliberately* declines something upstream's editor-and-undo model accepts. Such a case is listed
   **by name** in the test with its argument, so the difference is a recorded decision rather than a
   silent regression. An exception that cannot be argued in one sentence is a bug.
5. **Record the parity number.** *Vectors matched / total*, plus the exception list, in the commit
   message and in the module's own test output. `§ 10`'s exceed-rows are then measured **on top of** a
   green parity gate, which is what makes "better than JetBrains" a statement with a floor under it.

**The gate's wording, for the test's own failure message:** *a vector JetBrains resolves and we do not
is a regression; fix it or record the argument for declining it.*

---

## 6. The landing zone and the algorithms in dependency order

### 6.1 The landing zone

`merge-java` gains a sub-package `com.codebuddy.merge.jetbrains`, with three tiers:

| Tier           | Contents                                                                               | Depends on                       |
| -------------- | -------------------------------------------------------------------------------------- | -------------------------------- |
| **text**       | `ComparisonPolicy`, `TextLine`, `WordTokenizer`, `MyersDiff` (line + word), `TrimUtil` | JDK only                         |
| **merge**      | `MergeRange`, `MergeType`, `MergeRangeUtil`, `MergeResolveUtil`                        | `text` only                      |
| **adapter**    | `JetBrainsMergeDetector`, `JetBrainsMergeResolver` — the `ConflictResolver` implementations that bridge into the existing model | `merge` + the module's own model |

The tiers matter: everything up to and including `merge` is **independently testable with no merge
model at all**, which is exactly how upstream's tests are written and what makes their vectors
portable.

**Built by step 4.7, as the package skeleton:** `jetbrains/package-info.java` (the tier map and the
isolation rule), `jetbrains/text/package-info.java`, `jetbrains/merge/package-info.java`, and
`jetbrains/JetBrainsProvenance.java` (the pinned revision, in one place — § 7a). The skeleton is
documentation-plus-constants on purpose: each package doc states what its tier may and may not know, and
`JetBrainsAttributionTest` walks it, so the attribution convention is enforced **before** the first
algorithm exists rather than retrofitted after — which is how a first file comes to be un-attributed.

### 6.2 `MergeRangeUtil.getMergeType` — the conflict taxonomy

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

### 6.3 `MergeResolveUtil.tryResolve` — the simple word-level resolve

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
automatically. See § 7 for how we restrict it.

### 6.4 `MergeResolveUtil.tryGreedyResolve` — the greedy variant

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
and it is exactly the behaviour our structural-residual rule must *not* adopt wholesale (§ 7).

The switch between the two is a global flag upstream: `DiffConfig.USE_GREEDY_MERGE_MAGIC_RESOLVE`.
**We must not port a mutable global.** The choice is a parameter of our resolver, defaulting to the
simple strategy, with the greedy one available and recorded on the resolution.

### 6.5 `ComparisonMergeUtil` — building merge ranges from two two-way diffs

`FairMergeBuilder.add(range1, range2)` is the core: given one unchanged-run from each side, it finds
their overlap in base coordinates, marks the overlap equal, and returns which side to advance. The
`IgnoringChangeBuilder` subclass is the interesting part — after marking a change, it walks the
*unchanged* run preceding it and **re-emits sub-runs where the policy says equal but byte equality says
different** as changes of their own. That is how "ignore whitespace" yields a merge that still shows
the whitespace difference rather than silently swallowing it.

### 6.6 `TrimUtil` — whitespace-insensitive matching

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

## 7. The rule that must survive the port

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

## 7a. The derived-file header, as built

Step 4.7 established the header here rather than describing one, so this is the format every file under
`com.codebuddy.merge.jetbrains` opens with. It is four `//` lines above the `package` declaration:

```java
// Licensed under the Apache License 2.0; see <relative path>/THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (<upstream path>).
// @derived Translated from Kotlin to Java, reduced to the algorithm, and stripped of the IntelliJ Platform dependency.
// {enabled:true, blockMarker: "implicit"} <what the file is>
```

Each line does one job, and each is required in order:

| Line | Obligation it discharges                                                  |
| ---- | ------------------------------------------------------------------------- |
| 1    | Apache 2.0 § 4(c) — the licence and the copyright notice are **retained** |
| 2    | The **citation**: which upstream file, at which revision. A path without a revision is not reproducible, and a revision without a path cannot be checked |
| 3    | Apache 2.0 § 4(b) — a prominent notice that the file **was changed**. The one-line "changed by" statement follows the marker |
| 4    | DEC-021's file marker and JSON5 config, per root `AGENTS.md` § 1. Here it is honest: the file *is* generated in the sense of being derived, and a parser reading this marker is told so |

**Two deliberate departures from the obvious reading of DEC-021**, both worth recording because a
reviewer will otherwise ask:

- **Line 4 is not `@generated file <generator-fqn>`.** DEC-021 describes whole-file emitters — a
  generator that owns the file and can regenerate it. Nothing here regenerates these files: they are
  hand-translated once and reviewed. Claiming a generator FQN would be a false statement about how to
  maintain the file, which is exactly the kind of marker rot DEC-035 exists to prevent. The
  `@derived` marker is the honest one, and it carries the "changed by" statement the licence requires.
- **The second line's parenthetical is the upstream path**, and it is what
  `scripts/verify-jetbrains-sources.js` reads. A file whose upstream path does not exist at the pinned
  revision fails that check — so the citation is verified against the real repository rather than against
  itself.

**The one file that is not a translation** carries `@derived none` instead of a path:
`JetBrainsProvenance.java` is this module's own record of the pin. The marker is explicit rather than
implied by an absent path, because an absent path is otherwise indistinguishable from an omission, and
the attribution test asserts both halves — a file with `@derived none` must name **no** upstream path,
and every other derived file must name exactly one.

### What enforces it

| Check                                 | Where            | What it proves                                                                              |
| ------------------------------------- | ---------------- | ------------------------------------------------------------------------------------------- |
| `JetBrainsAttributionTest`            | the build        | Every derived file has the four fragments in order; its cited revision is the pin; the notices list it; the pin appears in this document and in `THIRD_PARTY_NOTICES.md` |
| `scripts/verify-jetbrains-sources.js` | run deliberately | The citation is **true** — each cited path exists at the pinned revision in a real checkout |

The split is the repository's usual one for anything network-shaped: the build asserts the citation is
**complete and self-consistent**, and a separate script asserts it is **true**, because a build that
reaches the network is a build that fails when the network does. The attribution test is not vacuous —
it fails if the package walk finds no files at all, which is the failure mode of a guard that silently
stops guarding.

---

## 8. What we deliberately do not take

| Left upstream                                                                         | Why                                                                               |
| ------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `LangSpecificMergeConflictResolver` (SEMANTIC)                                        | Requires PSI and a running IDE; the `SEMANTIC` *level* is worth recording, the implementation is not portable |
| `BinaryMergeTool`                                                                     | Byte-level, a different problem; `merge-java` is a text module                    |
| `MergeThreesideViewer`, `MergeWindow`, all `*Action.kt`, `ThreesideMergeHighlighters` | UI. Our presentation is the `review/` jsx6 page, which already exists             |
| `MergeModelBase` line bookkeeping and undo                                            | Bound to `Document`, write commands and the undo stack; our equivalent is the result text plus `BranchConflictStore` |
| `MergeImportUtil` PSI import handling                                                 | Our `ImportConflictResolver` already does the text-level half; take only the *idea* of an import range if a test shows a gap |
| `MergeRequestProcessor`, `DiffRequest*`                                               | Request/IDE plumbing                                                              |
| The mutable `DiffConfig` global switches                                              | A library must not have process-global behaviour switches; they become parameters |
| `MergeAutoTest` / `*AutoTest` harnesses                                               | Taken as a **property** to re-express (§ 11.6), not as code                       |

---

## 9. The correspondence to our own concepts

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

## 10. Parity, then beyond it

This section has **two halves**, and reading only the second is how a port talks itself into calling
parity a success. § 5.5 fixed the ambition in the maintainer's words — *"this tool must be better than
JetBrains, JetBrains is the benchmark of minimum that has to be achieved where we overlap"* — so the
first half is the floor and the second is the point.

### 10.1 The floor: overlap cases we must match

Each row is measured **inside** the overlap of § 5.5 — intra-line and line-level three-way text merge —
and each is settled by § 5.6's parity gate rather than by opinion. The **Class** column is § 3.5's
classification, and it is what decides whether a case becomes an automatic answer or an open
suggestion.

| Today                                                                                   | After the port                                                                                      | Class          | Step      |
| --------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | -------------- | --------- |
| Two branches edit **different words of one line** → we escalate to a human, because our unit is the line | The word-level pass resolves the union, or says plainly why not                    | **SAFE**       | 4.9       |
| A branch **re-indents** a block the other edited → whitespace churn reads as a conflict | `IGNORE_WHITESPACES` retry resolves it, with a warning naming the policy                            | **SUGGESTION** | 4.10      |
| "Both sides changed this region" is our finest diagnosis                                | "both inserted", "left modified / right unchanged", "both modified identically", "genuine conflict" | model          | 4.8       |
| No whitespace policy exists; `line.trim()` is the whole story                           | Three named policies, applied consistently across detection and resolution                          | model          | 4.10      |
| `STRUCTURAL_CHANGE` fires on adjacent-but-independent edits (step 4.5's residual problem, partly fixed there by region arithmetic) | The shape classification says `INSERTED`/`MODIFIED` and there is no residual to veto with | **SAFE** | 4.8, 4.12 |
| The evidence scale has no level for intra-line comparison                               | `AnalysisLevel` gains one, ordered below file-text evidence                                         | model          | 4.11      |
| An answer we already computed is **hidden behind a refusal** — `MethodBodyChangeConflictResolver` builds a combined body and `MergeFileTool` returns `LEFT_REVIEW` with no replacement | The same answer is delivered as a suggestion: shown, acceptable in one action, never applied alone | **SUGGESTION** | 4.14–4.17 |

### 10.2 Beyond it: overlap cases we must **win**, and why we can

This is the half that makes the tool better than the benchmark rather than equal to it. Every row is a
capability we have and upstream does not, applied to a case inside the overlap — so each is a case
where being merely at parity would be a **regression against ourselves**.

| What we have that upstream does not                                              | The overlap case it wins | How the win is measured                                                                   | Step       |
| -------------------------------------------------------------------------------- | ------------------------ | ----------------------------------------------------------------------------------------- | ---------- |
| **`AnalysisLevel` chains a text answer to a structure answer** (`TEXT_LOCAL` → … → `STRUCTURE` → … → `PROJECT_TYPES`) | Two branches appended a **distinct method** at the same point. Upstream's line pass calls it `CONFLICT` and recovers only if its word pass can compose the two insertions; we recognise the declared members and answer `MEMBER_ADD` at `STRUCTURE` — and if the word pass *does* compose it, arbitration still prefers the structural claim, because it read more | Vector resolved **and** `analysisLevel == STRUCTURE` reported; the same vector's text-only answer is recorded beside it | 4.12 |
| **`ResolutionVerifier`** — upstream validates no resolved text at all (check C7) | A ported pass produces text that fails the structural gate. Upstream shows it in an editor and lets a person notice; we label it. **The case we win is not detecting more — it is never applying broken text as a success.** | A deliberately corrupted pass output is refused rather than applied; upstream has no equivalent check to compare against, so the measurement is the guard firing | 4.9, 4.14 |
| **Twelve domain `ConflictType`s** — upstream has four *shapes* (`INSERTED`/`DELETED`/`MODIFIED`/`CONFLICT`) | A block whose real content is a **type widening** or an **overload addition**. Upstream can only say "modified"; we can say "this is a widening, and with the project's classpath it is provably safe". Both resolve the text; only one can **explain and justify** it, and only one can decide it at `PROJECT_TYPES` | The same fixture reports the domain type, the strategy and the level, where a shape-only engine reports one of four words | 4.12, 4.15 |
| **Signature-keyed decision replay** (`BranchConflictStore`, `DecisionRecorder`; `ConflictSignature`) | A conflict a reviewer already decided reappears on the next update. Upstream re-derives the conflict and asks again; we replay the recorded decision — and, after 4.17, remember a **refusal** too | A decided conflict is applied on the next run without re-asking, asserted end to end | 4.17 |
| **The open-suggestion channel** (provenance, confidence, rejection memory)       | A conflict neither engine can prove. Upstream offers the two sides and an editor; we offer a **concrete answer** with its basis and a one-action acceptance — and we learn from a refusal | Blocks where an answer was offered, and blocks accepted in one action | 4.14–4.17 |
| **Type resolution against a project classpath** (`TypeContext`, javac)           | A conflict whose resolution depends on the **project's own** hierarchies — a widening between two project types. Upstream has no type model; it can only compare text | The resolved code compiles against the project classpath and the level is `PROJECT_TYPES` | 4.12 |

**Two of these rows are the ones to argue about, and they are named so they can be.** The
`AnalysisLevel` row and the `ResolutionVerifier` row are the substantive claims; the domain-type and
replay rows are matters of degree. If a measurement later shows one of them is not a real advantage,
**the row comes out** rather than being reworded — a benchmark-exceeding list that survives contact
with measurement is the only kind worth writing.

**One honest asymmetry, stated rather than hidden:** upstream has years of real-editor use behind it and
we have a test corpus. Where a measurement disagrees with this table, the measurement wins, and the row
is removed with the disagreement recorded.

### 10.3 The measurable target

The port has two halves because the two classes are measured differently, and a **floor** under both:

- **The floor (§ 5.6).** *Vectors matched / total* against upstream's own corpus, with the justified
  exception list. **Not negotiable, and a regression is a failure.**
- **SAFE steps.** *Conflicts escalated to a human* and *blocks left `LEFT_MANUAL`* — both must fall,
  with **zero** new incorrect applications against `DESIGN_NEVER_AUTO_RESOLVED.md`.
- **SUGGESTION steps.** *Blocks where the tool had an answer to offer* and *blocks a reviewer accepted
  in one action* — the first must rise, and the second is the number that says whether the suggestions
  were any good.
- **The beyond-parity rows of § 10.2.** Each names its own measurement above; a row whose measurement
  does not beat the shape-only answer is removed or the claim is dropped.

All of it comes from step 4.13 (parity plus the port's vectors) and step 4.17 (the channel's), in the
commit messages. A claim that the port made the tool better without those numbers is an assertion, not
evidence — and a claim of **parity** without the gate is the specific assertion this document exists to
prevent.

---

## 11. Upstream test vectors, transcribed for `merge-java`

All from `MergeTest.kt` (`_` is a line separator; `!N` downstream denotes line N of the result
document; text is lowercase words for readability). These become
`src/test/resources/fixtures/jetbrains-*` following `THREE_WAY_FIXTURES.md`, so the existing
`ThreeWayFixture` harness runs them unchanged.

> **Corrected 2026-10-07.** They cannot, and the reason is that document's own first rule. A
> `THREE_WAY_FIXTURES` case is three **complete, compilable** Java files, because "a fragment cannot
> express a change" and type attribution needs a plausible source path. The vectors below are
> text-fragment ranges with expected **kinds**: they exercise the ported text machinery
> (`MergeRangeBuilder` + `MergeRangeUtil.getMergeType` + `MergeResolve`), not a `ConflictType`, and a
> whole-file fixture cannot state `y z | x y z | x y`. Forcing them into that layout would have broken
> the rule that makes those fixtures trustworthy — so they live in
> `merge-java/src/test/resources/parity/jetbrains-change-types.txt` and `jetbrains-resolve.txt`, as a
> data set with its own documented format, read by `JetBrainsParityGateTest` as the single source of
> truth. § 11.3 and § 11.4 are the rows that *are* whole-file cases, and they are the ones a
> `ThreeWayFixture` could carry.

### 11.1 Change-type vectors (`testChangeTypes`, `testLastLine`)

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

### 11.2 Word-level resolve vectors (`testResolve`) — **the most valuable set**

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

### 11.3 Non-conflicting auto-apply vectors (`testNonConflictsActions`)

A three-text case where applying non-conflicting changes from `BASE`, `LEFT` and `RIGHT` respectively
yields three different, exactly specified outputs, and the count of **remaining** changes is asserted
per side (`BASE` → 1 remaining, `LEFT` → 3, `RIGHT` → 2). There is a modify/delete block in the middle
plus an insert-left, a remove-right and a "both modified identically" block — a compact test of the
whole taxonomy.

This is the vector set to run against `MergeFileTool` first, because its expected outputs are literal
text and its remaining-change counts are exact.

### 11.4 Vectors that pin the *refusals* (`testDoNotAutoResolve*`)

| left    | base    | right   | conflict type      | expectation                                |
| ------- | ------- | ------- | ------------------ | ------------------------------------------ |
| `""`    | `A_B_C` | `A_B_C` | `DELETED_MODIFIED` | cannot resolve; auto-apply changes nothing |
| `A_B_C` | `A_B_C` | `""`    | `MODIFIED_DELETED` | cannot resolve; auto-apply changes nothing |
| `""`    | `A_B_C` | `A_B_C` | *(none)*           | **control**: the same one-sided change *does* auto-resolve when the type is not a modify/delete file conflict |

The control row is the point: it proves the refusal comes from the conflict *type*, not from the
text. Port all three, because a port that only took the first two would pass while being wrong.

### 11.5 Whitespace-policy vectors (`testIgnored`)

With `IGNORE_WHITESPACES` and the three texts `" x_new_y_z"`, `"x_ y _z"`, `"x_y _new_ z"`:
five changes with exact ranges, types (`MODIFIED` left, `INSERTED` left, `MODIFIED` both,
`INSERTED` right, `MODIFIED` right), a single auto-apply producing `" x_new_y_new_ z"` with all five
resolved, and an undo/re-apply cycle producing a *different* correct result. The same texts under
`DEFAULT` produce **one** `CONFLICT` covering lines 0–4 — the whitespace policy is the only difference.

That is the cleanest available demonstration that the policy pass earns its place, and step 4.10 uses
it as its acceptance test.

### 11.6 The randomized property (`MergeAutoTest`), re-expressed

Upstream's randomized suite checks a property rather than a table, and the property is the one that
catches spliced-range bugs that no single vector will:

> after **any** sequence of apply / ignore / resolve / edit operations, the change ranges stay ordered
> and non-overlapping, and undo restores the previous state.

We take the **first half** and drop the undo half: undo is an editor-stack feature we do not have, and
the ordering invariant is the part that guards the ported range arithmetic (check C6 of § 5.3). It is
re-expressed over the plain-text API with a **seeded** generator, because upstream seeds from
`System.currentTimeMillis()` (`MergeAutoTest.kt:18,30`) and a failing run that cannot be replayed is
worth very little.

The test is expected to *fail* when the ordering invariant is broken deliberately — that is how a
property test proves it is testing anything, and step 4.13 requires that demonstration.

---

## 12. Reproducing the upstream checkout

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

And the check that turns this into a verified citation rather than a comment — it confirms the checkout
is at the pin and that every upstream path a derived file names exists there (step 4.7's second half):

```bash
bun merge-java/scripts/verify-jetbrains-sources.js          # defaults to .tmp/jb-ic
bun merge-java/scripts/verify-jetbrains-sources.js <dir>    # or an explicit checkout
```

Exit 0 means the citation is true; exit 1 means a path or the revision does not check out; exit 2 means
nothing could be verified (no checkout, no git, no declared paths) — reported as a failure to verify
rather than as a pass, because a check that did not run is not a check that succeeded. The pin itself is
read from `JetBrainsProvenance.PINNED_COMMIT`, so the script has no second copy of it to drift.
