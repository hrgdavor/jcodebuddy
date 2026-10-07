# Hierarchical resolution — a confident answer closes its region

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
2. **Closure.** A confident answer *settles a span of the file*, and that span is closed: lower tiers
   are not consulted about it, and an objection inside it is cleared rather than arbitrated.
3. **No wasted work.** "do not need to be analyzed by text resolver" — the lower tier's analysis of a
   closed span does not happen at all.

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

**Tiers run strongest first.** The pass is ordered by the tier a claim declares it can reach, so the
answer most likely to settle a span is asked first and the cheapest, least-informed answer is asked
last.

**A claim closes the span its own evidence explains.** That is the safety half, and it is deliberately
not "the span it covers" — coverage alone would let a guess close a region. A claim may close a span
only when one of these holds:

| Way to close        | The evidence                                                                                      | Example                                                                       |
| ------------------- | ------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **Recognised span** | The resolver recognised the declarations forming the span, so it knows what is in it line by line | `MEMBER_ADD` recognised two additions; the span is exactly those declarations |
| **Kept lines**      | The applied text keeps every line of both sides in that span                                      | `KEEP_BOTH`/`MERGE_SAFE` over two additions                                   |
| **Owned domain**    | The span is the resolver's own domain and the resolver is authoritative there                     | The import block, for `ImportConflictResolver`                                |

A claim that can establish none of the three **closes nothing** — it is still a claim, and it still
goes to `outranking` as before. Nothing is closed on a guess, and an unknown region (`Region.unknown()`)
closes nothing by construction, because it spans no lines.

**Closure is per line, and partial closure is the normal case.** The ledger holds lines, not blocks:
a claim over base lines 10–20 inside a 10–40 block closes 10–20 and leaves 21–40 open. The block's
output is then **composed** — the applied text for the closed lines, the conflict markers for the
open ones. This is what retires `LEFT_PARTIAL_RESOLUTION` as a dead end, and it is why 4.9's line-range
machinery (`MergeRange`, `MergeRangeBuilder`, `MergeChange`) is this design's enabling layer rather
than a parallel port: composing a file out of settled ranges is exactly what it does.

**Only an `AUTO` claim closes anything.** `REVIEW`, `MANUAL` and an unresolved block close nothing —
so the hierarchy can never promote an answer into application, and
[`DESIGN_NEVER_AUTO_RESOLVED.md`](../DESIGN_NEVER_AUTO_RESOLVED.md) is untouched. A `DEFERRED` claim is
a recorded *human* decision, so it does close, and it closes at the top of the order.

**Arbitration survives, one level down.** Hierarchical closure decides *who is asked*; `outranking`
(DEC-045) decides *who wins when two claims at the same tier are asked about one open region*. The two
are not alternatives and neither replaces the other:

```
for tier, strongest first:
    for each resolver at this tier, over the still-open regions only:
        claim = resolve(...)
        if claim is AUTO and its evidence explains span S:
            close S                    # lower tiers will not see S
            audit("closed S by <resolver> at <level>")
        else:
            offer claim to the open regions it touches
for each open region with several claims:  outranking(...)   # DEC-045, unchanged
for each region still open:                markers + fix paths
```

**Every closure is reported.** The report names the span and the claim that closed it, in the same
spirit as `outranking`'s "Outranked on this block by stronger evidence (…)" sentence: a reviewer must
be able to see *that a tier was cleared and by what*, not merely that the block came out applied.
The clearing claim's own audit line is the record; a block whose closed spans account for all of its
lines has no markers left and reads as an ordinary automatic result.

## 4. The invariant, written before the mechanism

The failure this design can produce is a **line that is neither applied nor left open** — dropped
between tiers because two claims each believed the other had it. That is a silent data loss, so the
invariant is a test that exists before the ledger does:

> **Every line of a block is accounted for exactly once** — applied by exactly one closed claim, or
> open (offered to a lower tier, and in the end left to a human with markers).

Its companion:

> **A closed span is explained by the claim that closed it**, under one of § 3's three ways; and
> **no closure happens without being named in the report**.

Written as tests over the ledger's own data, these are cheap to check and they fail loudly on the
defect that matters. This is the same technique that paid off in step 4.9 (`rangesAreOrderedAndNonEmpty`
was written before the builder was trusted): the invariant test is what localises the defect, and
plumbing defects — not algorithmic ones — are what this module's history says go wrong.

## 5. Where this meets the JetBrains port

- **It is the machinery § 5.6's parity gate needs.** A vector upstream resolves and we escalate is a
  failure; "we escalated it because our text pass was never asked about a span structure had already
  settled" is exactly the kind of failure the gate must not paper over — either the structure claim is
  right, and the vector was ours all along, or it is wrong and the closure rule is what catches it.
- **It is where 4.10's deferred variant lands.** The typed-change count in `JETBRAINS_PORT.md` § 11.5
  needs the ported differ wired in as a classifier (4.12) *and* a pass that knows which spans are still
  its business (4.18–4.20). Both halves are named.
- **A `SUGGESTION` closes nothing.** The suggestion channel is a separate axis: a suggestion is an
  answer offered to a person, so by construction it is not a claim that settles a span. When 4.14–4.17
  land, a suggestion is produced *beside* the open region, never in place of it.

## 6. Ordering, and what each tier can close

The tiers below are the level a resolution **records**. A resolver whose basis varies appears twice,
which is the point: `MEMBER_ADD` comparing resolved signatures closes more confidently than one
comparing method names, and the ledger uses the record, not the declaration.

| Tier (recorded level) | Claims that can reach it today                                                                  | What it may close                                                     | What it may never close                                                                |
| --------------------- | ----------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| `PROJECT_TYPES`       | `TypeChange`, `OverloadAdd`, `MemberAdd` (with project classpath)                               | A span it recognised structurally and compared on resolved signatures | Anything outside the members it recognised                                             |
| `PLATFORM_TYPES`      | `TypeChange`, `OverloadAdd`, `MemberAdd` (platform classpath)                                   | Same, on platform-resolved signatures                                 | A question about a project type — that is unresolved, not answered                     |
| `STRUCTURE`           | `StructuralChange`, `MemberAdd` (names only)                                                    | Member spans it recognised                                            | A body it did not parse                                                                |
| `TEXT_FILE`           | `Import`, `ConstantAdd`                                                                         | The import block, the constant block — its own domain                 | Lines outside that block                                                               |
| `TEXT_LOCAL`          | `ApiIncompatibility`, `PackageChange`, `Rename`, `MethodBodyChange`, `CommentAdd`, the residual | Nothing, in practice                                                  | It is the tier that gets *cleared*; it closes only by keeping every line of both sides |

Read the table as the answer to "who is asked, in what order, and what may they settle": the two
examples in § 1 are the `STRUCTURE`/`PROJECT_TYPES` row (two member additions) and the `TEXT_FILE` row
(the import block). A tier with no resolver that reaches it (`TEXT_INTRALINE`, step 4.11) closes
nothing and costs nothing — the hierarchy degrades to today's behaviour wherever it has nothing to
say, which is what makes it safe to land in three slices.

## 7. Non-goals

- **Not a rewrite of detection.** The detectors and their order stay; what changes is that a settled
  span is removed from what the later ones are asked about.
- **Not a relaxation of the never-auto list.** Closure suppresses a *lower-tier objection*; it never
  turns a `REVIEW` or `MANUAL` into an application.
- **Not a second ranking.** `AnalysisLevel` is the order. If a future resolver needs a different rank
  from its evidence, that is a signal its evidence is being mis-recorded, not a signal for a new enum.
