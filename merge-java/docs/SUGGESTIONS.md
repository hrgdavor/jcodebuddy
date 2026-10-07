# Open suggestions: a general kind of resolution that helps without deciding

This document defines one idea that is **bigger than the JetBrains port** it was asked for alongside:
a resolution that carries **a concrete answer** the tool has worked out, which a person can accept,
edit or ignore — but which the tool **never applies on its own**.

The JetBrains port ([`JETBRAINS_PORT.md`](JETBRAINS_PORT.md)) is its first and largest source of
suggestions. It is not the only one, and the design below is deliberately open, because a second
source already exists (`ConflictProposer`, the LLM seam) and a third is expected.

---

## 1. The gap this closes, measured

The module can already compute useful answers and then **hides them behind a refusal**. Three
examples, all in the current tree:

| Where                              | What it computes                                                                | What happens to it                                                        |
| ---------------------------------- | ------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| [`MethodBodyChangeConflictResolver`](../src/main/java/com/codebuddy/merge/MethodBodyChangeConflictResolver.java) line 87 | A combined method body from two branches' disjoint edits, with an explanation naming what it combined | It is stored in `resolvedCode` on a `REVIEW`; `MergeFileTool.decide` returns `LEFT_REVIEW` with a `null` replacement, so the file keeps its markers and the answer is only visible if something reads the resolution |
| The same resolver, lines 57 and 66 | "both branches made the same edit" / "both branches changed the same statement" | Same — a `REVIEW` whose `resolvedCode` is branch 1's body                 |
| [`ImportConflictResolver`](../src/main/java/com/codebuddy/merge/ImportConflictResolver.java) lines 129, 157 | A union of two import sets | Same — `REVIEW`, never applied, code never shown as *the* proposed result |

So the loss is not that we cannot decide. It is that **we compute a proposal and then present it as a
refusal**, which forces a reviewer to re-derive by hand something the tool already worked out. That is
work the tool did and then threw away.

The refusals themselves are correct and must not change: none of these answers is *provably* right —
two individually-correct body edits can compose into behaviour nobody intended
([`DESIGN_NEVER_AUTO_RESOLVED.md`](../DESIGN_NEVER_AUTO_RESOLVED.md) § 5.1). What is wrong is the
**presentation**, not the decision.

---

## 2. What a suggestion is, and what it is not

A **suggestion** is: *a concrete result the tool produced, delivered to a person as the thing to look
at first, which the tool will not write without that person's word.*

It is defined by four properties, and each one is checkable:

1. **It carries code.** Not "consider combining these" — the actual resolved text, ready to be
   written. A suggestion with no code is a fix path, and fix paths already exist.
2. **It is not applied by default.** `MergeFileTool` leaves the markers. This is what keeps
   `DESIGN_NEVER_AUTO_RESOLVED.md` untouched: nothing about the file's outcome changes.
3. **It records why it was produced, and how strong the basis was.** The same
   [`AnalysisLevel`](../src/main/java/com/codebuddy/merge/AnalysisLevel.java) and
   `ConflictResolution.getWarnings()` the automatic path uses, so a reviewer can see whether the
   suggestion came from comparing words, from recognising structure, or from a model that has met
   neither author.
4. **Accepting it is one action, and so is refusing it.** Acceptance applies exactly that code;
   refusal is *remembered*, so the same suggestion is not offered again on the next merge of the same
   conflict (see § 6).

### The kind taxonomy it completes

| Kind         | Carries code?        | Applied by the tool? | The question it answers for a reviewer                       |
| ------------ | -------------------- | -------------------- | ------------------------------------------------------------ |
| `AUTO`       | yes                  | **yes**, if verified | "nothing for you to do"                                      |
| `SUGGESTION` | **yes**              | no                   | **"here is an answer — take it, edit it, or throw it away"** |
| `REVIEW`     | *not necessarily*    | no                   | "something here is viable; here are options"                 |
| `MANUAL`     | no (`MANUAL_MARKER`) | no                   | "nobody can propose anything; decide it yourself"            |
| `DEFERRED`   | yes                  | no (until opted in)  | "you already decided this once"                              |

`SUGGESTION` is not a rename of `REVIEW`. `REVIEW` today means *"viable, but confirm"* and is
frequently code-free — its payload is fix paths. `SUGGESTION` means *"there is a concrete answer, and
here it is"*. A resolution that has options but no answer is a `REVIEW`; one that has an answer is a
`SUGGESTION`.

### Why "a new kind" rather than "make `REVIEW` carry code"

Because the two need different rules at every level, and merging them would make both weaker:

- **Application.** `REVIEW` is never applicable; a `SUGGESTION` is applicable *on an explicit
  acceptance*. That is a different outcome (`APPLIED_SUGGESTION`, § 4), not a different field.
- **Bulk actions.** "Apply all resolved" must never sweep up suggestions — they are not verified
  answers (§ 5). If they shared a kind with anything bulk-eligible, the guard would have to be a
  subtype check instead of a kind check, which is exactly the kind of hidden distinction this module
  keeps turning into explicit ones.
- **Reporting.** "How much of this merge did the tool decide?" is a headline number. A suggestion is
  *not* a decision, and collapsing it into `REVIEW` loses the distinction between "we have an answer
  for you" and "we have no answer for you".

---

## 3. The `Suggestion` record

A suggestion is a small, self-describing value, so that a producer does not have to invent a
`ConflictResolution` to offer one — which is what makes the kind expandable to sources that are not
resolvers at all.

```
Suggestion
  code          the resolved text, ready to write
  explanation   why this is believed right, one or two sentences
  provenance    WHO produced it: a named resolver, a text-comparison pass, or a proposer
  analysisLevel the strongest evidence this suggestion actually rests on
  warnings      what was missing from its basis
  verification  NOT_RUN | PASSED | FAILED | SKIPPED, with the detail when not PASSED
  confidence    PROVEN | PLAUSIBLE   (see § 5)
```

`provenance` and `confidence` are the two fields that make this general rather than a
JetBrains-shaped feature:

- **`provenance`** is how a reviewer judges a suggestion they cannot derive themselves. "The
  word-level comparison of the three sides produced this" and "a model proposed this" deserve
  different amounts of trust, and the difference must survive into the report and the review page
  rather than living in a log.
- **`confidence`** separates *"the inputs mechanically determine this"* from *"this is a plausible
  reading"*. It is not a probability and must not become one — it is a two-valued statement about
  whether a proof exists, which is all the tool can honestly claim.

---

## 4. Where a suggestion enters and leaves

### Producers

Any of these may produce a suggestion, and they are all the same kind of thing to a consumer:

| Producer                              | Status                                                                      |
| ------------------------------------- | --------------------------------------------------------------------------- |
| A resolver that worked out an answer it cannot prove — `MethodBodyChangeConflictResolver`, `ImportConflictResolver`, `ConstantAddConflictResolver`, `TypeChangeConflictResolver`'s review paths | exists today, currently degraded to `REVIEW`/fix paths |
| The ported word-level merge pass, where its result is real but not mechanically forced ([`JETBRAINS_PORT.md`](JETBRAINS_PORT.md) § 6) | new (steps 4.9–4.10) |
| `ConflictProposer` — the LLM/ACP seam | exists today as a fix path; **moves onto this channel** (§ 7)               |
| A future source: a project-specific convention, a recorded decision from a sibling conflict, a policy file | by construction — it produces a `Suggestion` and needs to know nothing else |

That last row is the point of the general shape. A new source should not have to add a resolver, a
conflict type, a fix-path convention **and** a UI branch to be useful.

### The path to the file

```
producer → Suggestion → suggestion channel on the resolution
                              ↓
                    report JSON  +  review page (Accept / Edit / Reject)
                              ↓
                    decisions.json  →  DecisionRecorder  →  applied on the next run
```

This is the **same round trip step 4.3 already built** for recorded decisions: the page collects a
decision, a CLI records it, the next merge replays it. A suggestion rides that path rather than
adding a second one — which is also what keeps the page host-free, per the maintainer's
standalone-UI rule.

### The outcome it produces

`MergeFileTool.Outcome` gains **`APPLIED_SUGGESTION`**, distinct from `APPLIED_AUTO`. The distinction
is not cosmetic: a run's report must be able to say *"three blocks were decided by the tool, two were
applied from your accepted suggestions, one is still open"* — a single "applied" count would claim
credit the tool has not earned.

---

## 5. The rule that keeps this safe

A suggestion is applied **only** by an explicit human action, and the following four rules are what
make that meaningful. Each is a test.

1. **`SUGGESTION` is never swept up by a bulk accept.** "Apply all resolved" applies `AUTO`
   resolutions only. A reviewer accepting a suggestion is making a judgement about code they have
   read; a bulk action cannot stand in for that. The guard is on the kind, in the pure module
   (`review/src/decisions.js`) and in the CLI, and both are asserted — step 4.3's bulk rule already
   has this shape for the engine's manual marker.
2. **A failed verification does not suppress a suggestion, it labels it.** `ResolutionVerifier`
   already establishes a floor (`ResolutionVerifier.structural()` catches dangling braces and
   unterminated literals). A suggestion that fails it is shown **as failed**, with the reason, because
   a reviewer may still want to see what was proposed and repair it. Suppressing it would hide
   information; promoting it would be the error the gate exists to prevent. This is exactly the
   treatment `ProposerBehindTheGateTest` already requires of a refused proposal.
3. **A suggestion never becomes the resolution.** The property that makes the LLM seam safe is
   *structural* — the code that attaches a proposal cannot reach the resolution object — and it must
   stay structural for every producer. A suggestion lives in its own field; the resolution's
   `resolvedCode` and `kind` are not writable from the suggestion channel.
4. **Declining is recorded, or the suggestion is noise.** If a reviewer rejects a suggestion and the
   next merge offers it again, the channel has made the tool worse, not better. A rejection is
   recorded against the **conflict signature** — the mechanism `BranchConflictStore` and
   `DecisionRecorder` already use — and suppresses that suggestion (by provenance and signature) on
   later runs. **Suppression is per `(signature, provenance)`**, because a rejection of a
   text-comparison answer is not a rejection of a future structural one.

Rule 4 is the one most likely to be skipped, and skipping it is what turns "helpful" into "nagging".
It is the reason this is a *kind* with state rather than a banner in a page.

---

## 6. What this must not break

| Invariant                        | Why it holds |
| -------------------------------- | ------------ |
| `DESIGN_NEVER_AUTO_RESOLVED.md`  | A suggestion is not an application. The file's outcome for every excluded case is unchanged: markers stay. § 9's test for moving a case to `AUTO` is untouched by adding a channel that is *never* `AUTO`. |
| `Outcome.LEFT_REVIEW` semantics  | Existing callers that read the outcome keep working; `LEFT_REVIEW` keeps meaning "markers kept, options offered". A suggestion is a *new* outcome, not a re-labelled one. |
| `ResolutionVerifier`'s asymmetry | It can only downgrade, never promote. A suggestion arrives already below `AUTO` and nothing in this design can raise it. |
| One serving host / standalone UI | Suggestions travel through the export-and-record files, so the page still needs no host, no port and no network. |
| `AnalysisLevel` arithmetic       | A suggestion's level is what it *reached*; it never outranks a competing claim into application, because arbitration only promotes `AUTO` claims (`MergeFileTool.decide`). |

---

## 7. What changes in the existing proposer

`ConflictProposer` (step 4.4) already has the right boundary and the right gate; it is expressed as
**one more fix path**, which was the correct minimal move when no suggestion channel existed. On this
design it becomes **one provenance of the suggestion channel**.

That move makes it *stronger*, not weaker:

- its output stops being a description among descriptions and becomes the concrete proposed result
  the page can prefill and accept — which is what the fix-path form was already straining to do
  through `suggestedCode` and `codeForOption`;
- its refusal-by-verification verdict becomes the channel's uniform `verification` field instead of
  living in a fix path's `impact` text beginning `NOT verified`;
- its "cannot reach the resolution" property is generalised into rule 5.3, so it is one guarantee
  rather than one implementation's promise.

`ProposerBehindTheGateTest`'s five assertions are kept as the channel's acceptance test, with their
subject changed from "a fix path" to "a suggestion". Nothing in `DESIGN_NEVER_AUTO_RESOLVED.md`'s
proposer section changes: a model still cannot decide.

**One thing that must be decided before a real endpoint plugs in stays as it was** — the code under
conflict leaves the machine, and a proposal is untrusted text — and the suggestion channel makes it
*more* visible, not less, because provenance now travels into the report.

---

## 8. Why this shape survives future expansion

The question a new source has to answer to use this is deliberately small: *what code do you
propose, why, on what evidence, and how sure are you that a proof exists?* That is four fields. It
does not have to know:

- what a `ConflictType` is, or whether one exists for its case;
- how the review page renders, or what a fix path is;
- how decisions are stored, or what a conflict signature is;
- whether it runs inside a resolver, a CLI, or beside a model.

That is the test for whether a general mechanism is general: **a new producer adds a producer, and
touches nothing else.** If adding a source requires editing `ConflictType`, `ConflictResolvers`,
`MergeFileTool.decide` and the page, then the channel was not general and this document needs
revising rather than the source being special-cased.

---

## 9. Scheduling

This is **steps 4.14–4.17** in [`plans/unified-plan.md`](../../plans/unified-plan.md), placed after
the JetBrains port's algorithm work (4.7–4.13) because the port is the channel's first real
producer and its graded analysis ([`JETBRAINS_PORT.md`](JETBRAINS_PORT.md) § 6) is what populates
`confidence` and `provenance` with something true to say.

| Step | What                                                                                   |
| ---- | -------------------------------------------------------------------------------------- |
| 4.14 | The `Suggestion` value, `ResolutionKind.SUGGESTION`, and `Outcome.APPLIED_SUGGESTION`  |
| 4.15 | Move the resolvers that already compute an answer onto the channel                     |
| 4.16 | The page and the decisions contract: Accept / Edit / Reject, and the bulk-accept guard |
| 4.17 | Rejection memory, and `ConflictProposer` as one provenance                             |

The work is ordered so that the *first* thing built is the value and the kind — which is testable
with no UI and no JetBrains code at all — and the UI follows, because a channel whose contract is
settled is much easier to render than one being discovered while it is rendered.
