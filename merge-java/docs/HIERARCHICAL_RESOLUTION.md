# Hierarchical resolution — a reliable answer resolves its region and removes the conflict

> **Status:** design, agreed with the maintainer on 2026-10-07. The decision is
> [`DEC-046`](../../doc-hipster-entity/architecture/decisions/DEC-046.md); the work is plan steps
> 4.18–4.20 in [`plans/unified-plan.md`](../../plans/unified-plan.md) § 4C.
>
> **Read this with** [`JETBRAINS_PORT.md`](JETBRAINS_PORT.md) § 5 (which upstream steps are SAFE),
> [`SUGGESTIONS.md`](SUGGESTIONS.md) (the channel a non-confident answer goes to) and
> [`DESIGN_NEVER_AUTO_RESOLVED.md`](../DESIGN_NEVER_AUTO_RESOLVED.md) (which types may never be
> applied — this design does not touch that list).

## 1. The requirement, as given

> "the change resolution must be hierarchical in a way where some may resolve confidently, and those
> blocks are then not tested by other merges. For example structural that knows two branches added one
> or more whole methods in same location should clear any conflicts they cover and do not need to be
> analyzed by text resolver, same goes for imports resolver, if it has success, then there is no need
> for lower tier to touch that part of the file"
> — the maintainer, 2026-10-07

Three things are asked for, and they are separable:

1. **An order.** Resolvers are ranked, and a higher rank runs first.
2. **Removal, not merely arbitration.** A confident answer *settles a region of the file*: the conflict
   is marked resolved and **removed from the working set**, so a lower tier never sees it and never
   produces an objection to it. A lower tier that is merely overruled has still done the work and still
   put a claim in the report — which is not what "do not want lower level resolver to even see conflict"
   asks for.
3. **No wasted work.** "do not need to be analyzed by text resolver" — the lower tier's analysis of a
   settled region does not happen at all, because the region is no longer in the set it is handed.

The two examples are the acceptance cases: **two branches adding whole methods at the same place**
(settled by structure, where a line comparison sees only "both sides replaced this region"), and a
**successful import resolution** (settled over the import block, where a text-level objection to those
lines is noise).

## 2. What exists today, and why it is not this

Four mechanisms are already in place, and each is a partial form of one of the three things above.
Naming them precisely is what keeps this design from re-inventing them.

| Mechanism                                                                              | What it actually does                                                              | What it is not                                                                        |
| -------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| Ten detectors in a **hardcoded call order** (`ConflictDetectionService.detect`), then the structural residual | Decides who *emits* a conflict first                        | Not an order of authority: emission order changes nothing about who may overrule whom |
| `AnalysisLevel` + `ConflictResolution` recording the level reached (DEC-045, step 4.6) | Ranks the **evidence** behind an already-produced claim                            | Not a run order — every resolver is still asked about every conflict                  |
| `MergeFileTool.residualSubsumed` (step 4.5)                                            | Suppresses the structural residual's veto when another conflict's region covers it | One named exception, for one conflict type, per block                                 |
| `MergeFileTool.outranking` (DEC-045)                                                   | Picks the one claim whose evidence strictly beats every other claim on the block   | **Per block, after every claim exists**                                               |

So the order exists as a call sequence, the ranking exists as evidence strength, and closure exists
only as a per-block veto exception. What does **not** exist is the thing the requirement names: a pass
that resolves in rank order and *removes a span from consideration* once it is settled.

### 2.1 The three costs of the current shape

**(a) The work is done and then discarded.** A text-level objection to a span that a structural
resolver has already settled is still *detected*, still *resolved*, and only then overruled by
`outranking`. That is not merely wasted effort: because the objection exists as a claim, the winner
must pass `accountsFor` (its region covers the loser's, or its applied text keeps every line of both
of the loser's sides), and that rule has to be right for every pair. A closed span needs no such rule
— there is nothing to account for, because nothing was asked.

**(b) Closure is per block, so a certain answer over part of a block is thrown away.** `outranks`
requires `coversBlock(candidate, block)`. An answer that settles lines 10–20 of a 10–40 block fails
that test, so the block becomes `LEFT_PARTIAL_RESOLUTION` and goes to a human *whole* — including the
ten lines the tool had already got right. This is the opposite of the requirement: a partial but
confident answer should apply to its own span and leave the rest.

**(c) A claim that was never raised leaves no trace.** `residualSubsumed` filters the residual out of
`deciding` before arbitration, so a block where the residual lost reads exactly like a block where it
was never raised. Under a real hierarchy this gets worse, not better: an entire tier can go
unconsulted, and a reviewer reading the report cannot tell "no text-level conflict existed here" from
"a text-level conflict was cleared by the structure-level answer".

## 3. The design

**One scale, not two.** The rank of a resolver is the rank of the evidence it brings: the tier of a
claim is the `AnalysisLevel` the resolution **records** — the level it actually reached, not the
maximum it declares. `MemberAddConflictResolver` is the working example: it declares
`PROJECT_TYPES`, records `PROJECT_TYPES`/`PLATFORM_TYPES` when it compared resolved signatures, and
records `STRUCTURE` when it could only compare method names. A second scale would have to be kept in
step with this one by hand, and the two would disagree the first time a resolver's basis changed.

**Tiers run strongest first.** The pass is ordered by the tier a claim records, so the answer most
likely to settle a span is asked first and the cheapest, least-informed answer is asked last.

### 3.1 A resolved conflict leaves the working set — it is not outranked

The refinement the maintainer gave on 2026-10-07, and it is a change of kind rather than of degree:

> "we need to refine rankings, I do not want lower level resolver to even see conflict if structural
> knows reliably how to resolve, and if resolution spans whole merge conflict region it should be
> removed marked as resolved and not touched by textual resolvers"

`Outranked` and `resolved` are different states, and conflating them is what the current code does. An
outranked claim still **exists**: it was produced, it is in the report, it is one of the claims
`accountsFor` had to reconcile, and a lower-tier resolver spent its time on it. A resolved conflict
**stops existing**: it leaves the working set, no lower tier is offered it, and no claim about it is
produced.

So the pass operates on a **working set of live conflicts**, each carrying a state:

| State      | Meaning                                                                    | What a lower tier does with it                                       |
| ---------- | -------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| `OPEN`     | No claim has settled it; it is offered to the tiers below in order         | It is asked about it                                                 |
| `RESOLVED` | A reliable claim spans **the whole conflict region**                       | **Nothing** — it is gone from the working set, and the region is reported as resolved rather than as contested |
| `PARTIAL`  | A reliable claim spans **part** of the region; the rest is still contested | It is asked about the **open remainder only**, never the closed span |

**Whole-region span means the conflict is removed.** When a reliable claim covers an entire merge
conflict region, the block is resolved: it is applied, marked resolved, and no textual resolver is
consulted about it. The lower tiers are not merely overruled — they are **not run**, and their
objections are never constructed. That is what the instruction asks for, and it is strictly stronger
than `outranking`, which requires all claims to exist first.

`RESOLVED` is a **state of the working set, not a promise about the text**: the claim that removed the
conflict is still an `AUTO` resolution that `ResolutionVerifier` gates exactly as it is today. Removal
is about who gets asked, and never about skipping a check.

**`PARTIAL` is where the remainder is protected.** Closing a span and keeping the rest open is the
case that today becomes `LEFT_PARTIAL_RESOLUTION` — the whole block, including the settled part, goes
to a human. Under this design the closed span is applied and only the open remainder is contested. A
claim spanning the whole region takes the `RESOLVED` path instead, so the two are decided by one
comparison and not by two rules that can disagree.

### 3.2 What "reliably" means, and how a rank is refined

The instruction says *"if structural knows **reliably** how to resolve"*, so reliability has to be a
property the code can check — not a reputation a resolver carries. A resolution is **reliable over a
region** when all three hold:

1. **It may be applied** — it is `AUTO` (or `DEFERRED`, a recorded human decision). A `REVIEW` or
   `MANUAL` answer is never reliable over anything, whatever its level.
2. **It explains every line of the region** — under one of § 3.3's three ways. Coverage is not
   explanation: a claim whose region merely happens to span the block, without evidence for what is in
   it, is not reliable and removes nothing.
3. **It places the region in the file** — its region is known (`Region.unknown()` spans no lines and
   therefore explains nothing).

A resolver that *declines* is the third case in the same vocabulary: declining is how a resolver says
it is not reliable here, and it costs nothing — `MemberAddConflictResolver` declines a shared signature
rather than guessing, and `TypeChangeConflictResolver` declines rather than answer from a table it
cannot justify. This is the refinement the instruction asks for in the ranking itself:

| Refinement                                                                                     | What it replaces                              | Why |
| ---------------------------------------------------------------------------------------------- | --------------------------------------------- | --- |
| What a claim may **settle** comes from the level the resolution **records**, per resolution    | Treating a resolver's reputation as authority | `MEMBER_ADD` records `PROJECT_TYPES` with a classpath and `STRUCTURE` without one; the same resolver is two different claims in two runs |
| What **order** the pass runs in comes from the level each resolver **declares**                | Nothing — this is new, and see below          | A tier can only be skipped *before* it runs, and a record does not exist until the resolver has been called |
| A rank **entitles** a resolver to be asked earlier — it does not entitle it to remove anything | Treating a higher level as authority          | Only the reliability check removes a conflict; a high level with evidence for nothing removes nothing |
| Reliability is checked **per region and per claim**                                            | A per-resolution or per-resolver property     | The same resolver can be reliable over the members it recognised and unreliable over the lines around them |
| `RESOLVED` and `outranked` are **different states**                                            | One arbitration outcome                       | Outranked means "produced and overruled"; resolved means "never produced" — and only the second satisfies "do not want lower level resolver to even see conflict" |

**The run order cannot come from the record — a finding from implementing step 4.18.** The first version
of this section said the two were one thing, that a resolver's declared maximum was never a tier. That is
impossible, and the reason is structural rather than a matter of taste: *a tier can only be skipped
before it runs if it is known before it runs*, and a recorded level does not exist until the resolver has
been called. Deriving the order from the record means calling every resolver — which is exactly the
behaviour the requirement removes.

So the two facts are kept apart, and they stay on the same scale:

- **the declaration decides who is asked, in what order** — `ConflictResolvers.inTierOrder`, descending
  by `maxAnalysisLevel()`, the order a reader can follow in one place;
- **the record decides what the answer is worth** — what a claim may settle, what the report says, and
  what same-tier arbitration compares.

The declaration is safe in that role for two reasons, and both are already enforced rather than
promised: a resolution may never record a level **above** its resolver's declaration (`AnalysisLevelTest`
asserts it), so the declaration is an upper bound and never an overclaim; and a declaration settles
nothing by itself, because removal needs the reliability check. A resolver therefore cannot buy
authority by declaring a high level — it can only buy being asked early, and its answer is judged on
what it recorded and what it explained.

### 3.3 What a claim may close: explained, never merely covered

**A claim explains the span its own evidence accounts for.** That is the safety half, and it is
deliberately not "the span it covers" — coverage alone would let a guess remove a conflict. A claim
explains a span only when one of these holds:

| Way to explain      | The evidence                                                                                      | Example                                                                       |
| ------------------- | ------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **Recognised span** | The resolver recognised the declarations forming the span, so it knows what is in it line by line | `MEMBER_ADD` recognised two additions; the span is exactly those declarations |
| **Kept lines**      | The applied text keeps every line of both sides in that span                                      | `KEEP_BOTH`/`MERGE_SAFE` over two additions                                   |
| **Owned domain**    | The span is the resolver's own domain and the resolver is authoritative there                     | The import block, for `ImportConflictResolver`                                |

A claim that can establish none of the three **removes nothing and closes nothing** — it is still a
claim, and it still goes to `outranking` as before. Nothing is settled on a guess, and an unknown
region (`Region.unknown()`) closes nothing by construction, because it spans no lines.

**Closure is per line, so a partial answer is useful rather than discarded.** The ledger holds lines,
not blocks: a claim over base lines 10–20 inside a 10–40 block closes 10–20 and leaves 21–40 open. The
block's output is then **composed** — the applied text for the closed lines, the conflict markers for
the open ones. This is what retires `LEFT_PARTIAL_RESOLUTION` as a dead end, and it is why 4.9's
line-range machinery (`MergeRange`, `MergeRangeBuilder`, `MergeChange`) is this design's enabling layer
rather than a parallel port: composing a file out of settled ranges is exactly what it does.

**Only an `AUTO` claim closes anything.** `REVIEW`, `MANUAL` and an unresolved block close nothing —
so the hierarchy can never promote an answer into application, and
[`DESIGN_NEVER_AUTO_RESOLVED.md`](../DESIGN_NEVER_AUTO_RESOLVED.md) is untouched. A `DEFERRED` claim is
a recorded *human* decision, so it does close, and it closes at the top of the order.

### 3.4 Arbitration survives, one level down

Hierarchical resolution decides *who is asked*; `outranking` (DEC-045) decides *who wins when two
claims at the same tier are asked about one still-open region*. The two are not alternatives and
neither replaces the other:

```
live = every conflict detection produced            # each with its own region
for tier, strongest first:
    for each resolver at this tier:
        for each conflict in live whose region this resolver can see:
            claim = resolve(conflict)
            if claim is reliable over conflict.region:      # § 3.2
                apply(claim); state(conflict) = RESOLVED
                live.remove(conflict)                       # lower tiers never see it
                audit("resolved <region> by <resolver> at <level>")
            else if claim is reliable over a span of it:
                apply(claim) over that span; state = PARTIAL
                conflict.region = remainder                 # only the remainder is offered
            else:
                record claim against the still-open region
for each region with several claims:  outranking(...)        # DEC-045, unchanged
for each region still open:           markers + fix paths
```

**Every state change is reported.** The report names the region, the state it reached and the claim
that reached it — `resolved` by which resolver at which level, or `partial` with the remainder that
stayed open. Without it, "this tier had nothing to say" and "this tier was never asked" read the same,
which is precisely the confusion `residualSubsumed` creates today. A block whose every region reached
`RESOLVED` has no markers left and reads as an ordinary automatic result.

## 4. The invariant, written before the mechanism

The failure this design can produce is a **line that is neither applied nor left open** — dropped
between tiers because two claims each believed the other had it. That is a silent data loss, so the
invariant is a test that exists before the ledger does:

> **Every line of a block is accounted for exactly once** — applied by exactly one closed claim, or
> open (offered to a lower tier, and in the end left to a human with markers).

Its companion:

> **A closed span is explained by the claim that closed it**, under one of § 3.3's three ways.

And the one the refinement adds, which is the requirement in testable form:

> **A conflict marked `RESOLVED` is never offered to a lower tier**, and no claim about it is
> produced; **a conflict marked `PARTIAL` is offered only its open remainder**; and **no state change
> happens without being named in the report**.

Written as tests over the ledger's own data, these are cheap to check and they fail loudly on the
defect that matters. This is the same technique that paid off in step 4.9 (`rangesAreOrderedAndNonEmpty`
was written before the builder was trusted): the invariant test is what localises the defect, and
plumbing defects — not algorithmic ones — are what this module's history says go wrong.

## 5. Where this meets the JetBrains port

- **It is the machinery § 5.6's parity gate needs.** A vector upstream resolves and we escalate is a
  failure; "we escalated it because our text pass was never asked about a span structure had already
  settled" is exactly the kind of failure the gate must not paper over — either the structure claim is
  right, and the vector was ours all along, or it is wrong and the reliability rule is what catches it.
- **It is where 4.10's deferred variant lands.** The typed-change count in `JETBRAINS_PORT.md` § 11.5
  needs the ported differ wired in as a classifier (4.12) *and* a pass that knows which regions are still
  its business (4.18–4.20). Both halves are named.
- **A `SUGGESTION` removes nothing.** The suggestion channel is a separate axis: a suggestion is an
  answer offered to a person, so by construction it is not a claim that settles a region. When 4.14–4.17
  land, a suggestion is produced *beside* the open region, never in place of it.

## 6. Ordering, and what each tier can settle

The tiers below are the level a resolution **records**. A resolver whose basis varies appears twice,
which is the point: `MEMBER_ADD` comparing resolved signatures is reliable more often than one
comparing method names, and the ledger uses the record, not the declaration.

| Tier (recorded level) | Claims that can reach it today                                                                  | What it may settle                                                    | What it may never settle                                                                |
| --------------------- | ----------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| `PROJECT_TYPES`       | `TypeChange`, `OverloadAdd`, `MemberAdd` (with project classpath)                               | A span it recognised structurally and compared on resolved signatures | Anything outside the members it recognised                                              |
| `PLATFORM_TYPES`      | `TypeChange`, `OverloadAdd`, `MemberAdd` (platform classpath)                                   | Same, on platform-resolved signatures                                 | A question about a project type — that is unresolved, not answered                      |
| `STRUCTURE`           | `StructuralChange`, `MemberAdd` (names only)                                                    | Member spans it recognised                                            | A body it did not parse                                                                 |
| `TEXT_FILE`           | `Import`, `ConstantAdd`                                                                         | The import block, the constant block — its own domain                 | Lines outside that block                                                                |
| `TEXT_LOCAL`          | `ApiIncompatibility`, `PackageChange`, `Rename`, `MethodBodyChange`, `CommentAdd`, the residual | Nothing, in practice                                                  | It is the tier that gets *removed*; it settles only by keeping every line of both sides |

Read the table as the answer to "who is asked, in what order, and what may they settle": the two
examples in § 1 are the `STRUCTURE`/`PROJECT_TYPES` row (two member additions) and the `TEXT_FILE` row
(the import block). A tier with no resolver that reaches it (`TEXT_INTRALINE`, step 4.11) settles
nothing and costs nothing — the hierarchy degrades to today's behaviour wherever it has nothing to
say, which is what makes it safe to land in three slices.

## 7. Non-goals

- **Not a rewrite of detection.** The detectors and their order stay; what changes is that a settled
  region is removed from what the later ones are asked about.
- **Not a relaxation of the never-auto list.** Removal suppresses a *lower-tier objection*; it never
  turns a `REVIEW` or `MANUAL` into an application, and a claim that needs a person removes nothing.
- **Not a second ranking.** `AnalysisLevel` is the order. If a future resolver needs a different rank
  from its evidence, that is a signal its evidence is being mis-recorded, not a signal for a new enum.
- **Not a promise about the text.** `RESOLVED` says *who was not asked*, and nothing more: the applied
  answer still goes through `ResolutionVerifier`, and a resolution that fails verification is
  downgraded exactly as it is today.
