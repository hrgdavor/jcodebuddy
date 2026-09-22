# Phase 8: Documentation

> **Not started.** This is the only phase of the migration with no code in it, and the only one whose
> deliverable is a claim about the rest of the repository: that a reader who follows the documentation
> ends up writing OpenRewrite. Section *Status* at the end carries the measured inventory this plan is
> scoped against - 49 tracked markdown files outside this `plans/` directory still name JavaParser, and
> three of them (`AGENTS.md`, `doc_knowledge/code.graph.md`, `java-watch-agent/record builder.md`) are
> live instructions to use it. Read that section
> before starting; the counts below were taken from the tree on 2026-09-22, after Phase 7.

## Overview

Phases 1-7 changed what the code does. Phase 8 changes what the documentation says the code does, and
closes the gap between them.

The migration is complete in the only sense a gate can measure: `javaparser-core` is declared by no
module, all 34 queue files are ported, 1326 tests pass, and `verify-migration.js` reports
`RESULT: PASS`. None of that stops `AGENTS.md` - the file every AI coding agent in this repository reads
first - from saying:

> **JavaParser as the AST of choice.** Read, write, and reformat source through `com.github.javaparser`
> so generated code and parsed code share the same representation. See `doc_knowledge/code.graph.md`.

That is not a stale sentence in a corner. It is a binding project rule pointing at a 17-mention guide
that teaches `JavaParser.parse`, `StaticJavaParser`, `LexicalPreservingPrinter`, `ParserConfiguration`
and the symbol solver. An agent that obeys it will re-add the dependency the migration removed, and the
gate will catch it only after the work is done. **Documentation that contradicts the tree is a defect
with the same blast radius as a code defect**, and this phase exists to remove it.

## Prerequisites (Phase 7)

- Phase 7 delivered: `07-Testing-Validation.md` § *Status*.
- The numbers this phase quotes come from `doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md`,
  which is generated. Regenerate it rather than copying numbers out of this file:
  `bun run scripts/rewrite-migration/generate-test-report.js --gate-out <capture>`.
- `scripts/rewrite-migration/curation.js` is the existing model for how this repository records "this
  file legitimately mentions the thing we removed, here is the reason and here is what retires it".
  Phase 8's documentation allowlist should look like it, not like a comment.

## Objectives

1. **No live document instructs a reader to use JavaParser.** Historical records keep their mentions and
   are marked as records; everything a reader would follow today names OpenRewrite.
2. **`AGENTS.md` says what the tree does.** The AST rule is rewritten, and the pointer it carries leads
   somewhere that agrees with it.
3. **The ported modules document their own API.** A reader who needs a position, a kind query or a
   validity check finds the method that answers it, in the module's README, without reading the source.
4. **The claim is checkable.** A gate check fails when a non-historical document teaches the removed
   API, so the property survives the next contributor.

## Deliverables

### D1 - `AGENTS.md` § 2: the AST rule

Replace the JavaParser bullet with the OpenRewrite equivalent, keeping the bullet's job (tell a
contributor which representation to use and where to read about it) rather than restating the
migration's history. The rewrite has to name what the tree actually offers, because that is what makes
it useful:

- reading source: `SourceReader.read(Path)` / `readText(String)`, whose `Read` carries **two** channels -
  `readable()` is the verdict, `problemsIn(...)` is detail and is often empty even for a file javac
  rejects (measured, `SourceReaderTest.OtherEntryPoints`);
- the fail-safe: a file javac recovers from is *not* a readable file (F-34), so "a parse produced a
  result" is never the test;
- positions come from javac (`com.sun.source`), not from the LST, which has none - `JavaSyntaxCheck`;
- traversal, kinds, annotations and type text: `TreeQueries`;
- writing source: generators splice into text (`SourceSplicer`) rather than reprinting a tree, because
  reprinting reformats hand-written code.

A one-line pointer to `doc_knowledge/code.graph.md` stays only if D2 rewrites that file; otherwise it
points at the module READMEs (D3).

### D2 - `doc_knowledge/code.graph.md`

17 mentions, 7 of them teaching removed APIs. Two honest options, and the phase has to pick one rather
than half-do both:

- **Rewrite** it as the repository's "reading and writing Java source" guide: the two-channel read, the
  javac-position rule, the query helpers, the splice-not-reprint rule, and the parse cost measured in
  Phase 7 (a read is ~30-55 ms and does not scale with file size; the guard is ~60% of a cold read and
  ~0% warm). A guide with those numbers in it is one a reader trusts.
- **Retire** it, and move the surviving content into the module READMEs (D3), leaving a one-line
  tombstone that says where it went. Choose this if the file's real subject was JavaParser's object
  model rather than this repository's source handling.

Either way `AGENTS.md` and this file must agree on the same day, or the pointer in D1 leads somewhere
that contradicts it.

### D3 - Module READMEs

| Module | README state | What it must say after this phase |
| --- | --- | --- |
| `hipster-entity-tooling` | 6 mentions | The read path (`SourceReader`), the position path (`JavaSyntaxCheck`), the query path (`TreeQueries`), the splice rule, and how to run the module's gate and its `jmh` profile. Its naming-contract table is already required by DEC-022. |
| `merge-java` | 1-7 mentions across `README.md`, `VERSION_MAINTENANCE.md`, `IMPROVEMENTS_DELIVERED.md`, `IMPLEMENTATION_PLAN.md` | Which of these are records (leave, mark) and which are live (`README.md`, `VERSION_MAINTENANCE.md`: rewrite). |
| `java-watch-agent` | `record builder.md` 23 mentions / 21 removed-API, `plan.md` 1 | The builder guide is the worst single file in the repository: it teaches the removed API end to end. Rewrite against `jwa-builder`'s `RecordBuilderProcessor`, or retire it and point at `docs/RecordBuilderGenerator.md`. |
| `jwa-sidecar` | `README.md` 1, `modules.md` 2 | Module map agrees with the tree (the sidecar has 6 classes and no tests; say so). |
| `hipster-entity-core`, `hipster-entity-example` | 1 and 3 mentions | Any snippet that parses source uses the current API or is marked historical. |
| `scripts/rewrite-migration/README.md` | 10 mentions | Mostly correct by design - it documents the tool that found the mentions. Add: the migration is complete, the gate is the entry point, and Phase 8's doc check (D5) lives here. |
| root `README.md`, `README.java_watch_2.md` | 2 and 1 | Entry points. Highest reader-per-line value in the repository; do them first. |

### D4 - Architecture decisions

- `doc-hipster-entity/architecture/decisions/DEC-020.md` (2 mentions, 1 removed-API: it explains a
  `LexicalPreservingPrinter` failure that shaped the splice rule). The *reason* stays and is still true;
  the tense has to change from "JavaParser refuses" to "the previous parser refused, which is why the
  design does not reprint trees".
- `DEC-009.md`, `DEC-023.md`, `DEC-029.md`, `gen-freezing.md`, and the brainstorm files
  (`cooperative-codegen-preserve-user-tweaks.md`, `entity-metadata-generator.md`,
  `dec-009-source-visible-generation-strategy.md`, `typed-annotation-exposure.md`, `README.md`): 1-2
  mentions each. Decisions are records - **append a note, do not rewrite the decision**. The house rule
  is that a DEC states what was decided and why; a later phase adding a "superseded in part by" line is
  how this repository already does it (DEC-028 says exactly that about DEC-029).
- **Consider a new `DEC-030: OpenRewrite as the source representation`**, recording the decision the
  migration already made in code: one parse per read, javac for validity and positions, text splicing
  for emission, and the fail-safe rule. Phase 8 without it leaves the decision recorded only as an
  absence - `AGENTS.md` no longer mentioning JavaParser - which is a weak place for a rule to live.
- `doc/architecture/decisions-watch/DEC-W003/W005/W006/W007/W008.md` (1-10 mentions, `DEC-W007` is the
  heavy one) and `doc/architecture/module-map.md`: same treatment - note, do not rewrite.

### D5 - A gate check, so the property survives

Add `docs-honest` to `scripts/rewrite-migration/verify-migration.js`: every tracked markdown file that
names the removed library or teaches its API must be either (a) on a documentation allowlist with a
recorded reason and a retirement condition, or (b) free of live instruction. The allowlist belongs in
`curation.js` next to the source allowlist and uses the same `{reason, deferredTo, status}` shape, so a
reader learns one vocabulary instead of two.

The check must be fail-safe in the same direction as the rest of the gate: a file it cannot classify is
a failure, not a pass. Its first run should report the historical set below as allowlisted-with-reason
and everything else as work to do - which is what turns D1-D4 from a list into a checklist with a gate.

## What must NOT be rewritten

These mention JavaParser *because that is what they are about*. Rewriting them would destroy the record
of the migration, and the gate's `curation-freshness` check exists to stop exactly that kind of tidying:

- `doc/brainstorm/rewrite-migration/06-migration/` - `Checklist.md` (198), `MIGRATION-GUIDE.md` (45),
  `MIGRATION-CAVEATS.md` (38), `FOLLOWUP-06-Emission.md` (13), `tracker.md` (8).
- `doc/brainstorm/rewrite-migration/07-testing/` - `TEST-REPORT.md` (5), `BenchmarkReport.md` (2). Both
  are generated; the mentions explain why there is no comparative number.
- `plans/rewrite-migration/` - `README.md` (2) and the phase documents. Phase 7's § *Status* names the
  library while recording what was replaced.
- `doc/brainstorm/rewrite-migration/01-foundation/README.md`, `02-utilities/tests/README.md`,
  `03-codegen/README.md`, `03-codegen/USAGE.md` (1 each) - sketches, already marked as such by their
  phase documents.
- `merge-java/CHANGELOG.md` (1), `doc/continuation-plan-legacy.md` (4, legacy by name).
- The 11 allowlisted source files in `curation.js`, whose prose mentions are their provenance.

## Acceptance criteria

1. `AGENTS.md` contains no instruction to use the removed library, and every pointer it carries leads to
   a document that agrees with it.
2. `doc_knowledge/code.graph.md` is either rewritten or retired-with-tombstone, not left half-updated.
3. `java-watch-agent/record builder.md` no longer teaches a removed API.
4. Every module README in D3's table parses or reads source using the current API, or is marked as a
   record.
5. `bun run scripts/rewrite-migration/verify-migration.js` reports `RESULT: PASS` **including the new
   `docs-honest` check**, with every remaining mention on the documentation allowlist with a reason.
6. The reactor still runs `clean test` green - this phase touches no code, so a red build means a gate
   check was added wrongly.
7. `TEST-REPORT.md` regenerated after the phase still reports 1326 tests (or the current total) and
   `RESULT: PASS`: documentation work must not move a number.

## Architecture Compliance

- **DEC-019 / AGENTS.md § 1**: the phase changes prose, not wiring. Nothing it writes may describe a
  connection that is not committed source.
- **DEC-022**: naming-contract tables in module READMEs stay correct; a rename in code must still reach
  every name the documentation quotes.
- **DEC-026**: any run record this phase produces goes in the module's `.jcodebuddy/reports/`, not in a
  new top-level directory.
- **DEC-027 / DEC-028 / DEC-029**: reports stay Bun-rendered from generator JSON. A markdown guide is
  not a report and is written by hand - but if this phase produces anything tabular and generated (the
  documentation inventory is a candidate), it is rendered by a script under `scripts/`, never by Java.
- **DEC-020 / DEC-021**: the cooperative-codegen and class-header rules are unchanged by the migration;
  their documents get a tense correction, not a rule change.

## Status

**Current**: **Not started** (2026-09-22). Phases 1-7 are delivered and the migration gate passes; this
phase is the only remaining one, and it is the only one that can regress silently, because nothing in
the build fails when a document contradicts the tree.

**Measured inventory, 2026-09-22** (`git grep -ic javaparser` over tracked markdown, plus a scan for
`LexicalPreservingPrinter|StaticJavaParser|SymbolSolver|JavaParser\.parse|ParserConfiguration`):

| Priority | File | Mentions | Teaches removed API | Why it matters |
| --- | --- | ---: | --- | --- |
| 1 | `AGENTS.md` | 2 | yes (by pointer) | Read first by every contributor and every agent; currently mandates the removed library |
| 1 | `doc_knowledge/code.graph.md` | 17 | 7 | The guide `AGENTS.md` points at |
| 1 | `java-watch-agent/record builder.md` | 23 | 21 | A module guide, end to end, for an API that no longer exists |
| 2 | root `README.md` | 2 | - | Repository entry point |
| 2 | `hipster-entity-tooling/README.md` | 6 | - | The module that was ported; must document the new API |
| 2 | `merge-java/VERSION_MAINTENANCE.md` | 7 | - | Live maintenance instructions |
| 2 | `docs/RecordBuilderGenerator.md` | 1 | 2 | The surviving builder guide; D3 may point the retired one here |
| 2 | `scripts/rewrite-migration/README.md` | 10 | - | Documents the gate; gains D5's check |
| 3 | `doc-hipster-entity/.../DEC-020.md` | 2 | 1 | Note, do not rewrite |
| 3 | `doc/architecture/decisions-watch/DEC-W007.md` | 10 | - | Note, do not rewrite |
| 3 | `jwa-sidecar/modules.md`, `hipster-entity-example/codebuddy.md`, `README.java_watch_2.md`, `doc/architecture/module-map.md`, `merge-java/{README,IMPROVEMENTS_DELIVERED,IMPLEMENTATION_PLAN,IMPROVEMENT_PROPOSAL,CHANGELOG}.md`, `doc-hipster-entity` decisions and brainstorms, `DEC-W003/W005/W006/W008`, `doc/continuation-plan-legacy.md` | 1-4 each | - | Sweep, one pass |
| record | `doc/brainstorm/rewrite-migration/06-migration/*`, `07-testing/*`, `plans/rewrite-migration/*` | >300 (302 in `06-migration/` alone) | - | Allowlist with reasons; never rewritten |

**Handed over by Phase 7**: the read-path contract and its measured quirks (`SourceReader`'s two
channels, `problemsIn` empty while `readable()` refuses, `readFragmentUnit` recovering instead of
throwing), the position contract (`JavaSyntaxCheck`, and the three defects its tests found: a reversed
enclosing chain, `@Foo`/`Outer.Foo` stealing a name line, a string literal stealing one), the query
contract (`TreeQueries`, including that `findAll` is root-inclusive and that enum-constant annotations
are not collected), the absolute performance baselines in `benchmarks/BenchmarkReport.md`, and the
generated `TEST-REPORT.md`. A documentation phase that writes from the code instead of from these
records will produce prose that is plausible and wrong.

**Decisions this phase has to make before starting**, in the shape Phase 7's re-scope used:

1. Rewrite `doc_knowledge/code.graph.md` or retire it (D2)? Half-updating it is the one outcome that
   must not happen, because `AGENTS.md` points at it.
2. Is there a `DEC-030` for the source representation, or does the rule live only in `AGENTS.md` § 2?
3. Does `java-watch-agent/record builder.md` get rewritten or retired in favour of
   `docs/RecordBuilderGenerator.md`? Two builder guides is worse than one, whichever survives.
4. Does the `docs-honest` check (D5) run in `verify-migration.js`, whose subject is the migration, or in
   a separate documentation gate that outlives it? The migration's queue is settled and its tracker is
   closed; a check that must keep running forever may belong somewhere that does not read as historical.
5. Which of the module docs listed at priority 3 are live and which are records? The inventory counts
   mentions; only a reader of each file can say which it is.

**Next**: pick the five answers above, then start with `AGENTS.md` and the two entry-point READMEs -
they are the documents with the highest chance of being obeyed, and the ones whose contradiction with
the tree costs the most.

## Quick Reference

Files to change first:
- `AGENTS.md` § 2 (the AST rule and its pointer)
- `doc_knowledge/code.graph.md` (rewrite or retire)
- `README.md`, `README.java_watch_2.md` (entry points)
- `hipster-entity-tooling/README.md`, `merge-java/README.md`, `jwa-sidecar/README.md` (module APIs)
- `java-watch-agent/record builder.md`, `docs/RecordBuilderGenerator.md` (builder guides)

Files to add:
- `doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md` (proposed)
- a documentation allowlist in `scripts/rewrite-migration/curation.js`
- a `docs-honest` check in `scripts/rewrite-migration/verify-migration.js`

Files to leave alone, with a recorded reason:
- `doc/brainstorm/rewrite-migration/06-migration/*`, `07-testing/*`
- `plans/rewrite-migration/*`
- the 11 allowlisted source files
- every DEC and brainstorm document, except for an appended note

Evidence this phase quotes:
- `doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md` (generated)
- `doc/brainstorm/rewrite-migration/07-testing/benchmarks/BenchmarkReport.md`
- `plans/rewrite-migration/07-Testing-Validation.md` § *Status*
- `plans/rewrite-migration/PLAN-SUMMARY.md` § *Phase status*

---

## Status

**Delivered** (2026-09-22). The five "Decide first" questions are answered below, then what was built
and the two places the phase departed from this plan.

### The five decisions, and the answer taken

| # | Question | Answer |
| --- | --- | --- |
| 1 | Is the seven-year-old AI survey in `doc_knowledge/code.graph.md` appropriate to keep, and can I edit it? | **Rewrite it, keep the filename.** The survey taught `StaticJavaParser`, `LexicalPreservingPrinter` and the symbol solver, and `AGENTS.md` pointed at it as *the* guide — so it was live instruction, not history. The filename is what `AGENTS.md`, the tooling README and two example docs link to, so retiring it would have left four dead links and no guide. The file is now the repository's reading/querying/writing guide, and because it is a live document it names nothing removed — the gate needs no exemption for it. |
| 2 | Should `plans/rewrite-migration/*` be excluded from the inventory? | **Excluded, and from the gate as well as the counts.** The scanner classifies `plans/rewrite-migration/**` and `doc/brainstorm/rewrite-migration/**` as `record` from the path, so they need no allowlist entry and **cannot be laundered into one** — an entry for a record is reported as stale. This plan itself is excluded by the same rule, which is why it may name the old library freely. |
| 3 | What is `java-watch-agent/record builder.md`? | **A retired AI transcript, not a spec.** It was an early design chat (February 2026) that specified the builder's *behaviour* — idempotency, sync rather than replace, `build()` refreshed, minimal diffs — against the removed library. The body is now a tombstone: what it was, what it got right (tabulated against the classes that implement it today), where the surviving guide is, and how to read the original out of git history. The behaviour did not lose a home: `docs/RecordBuilderGenerator.md` was rewritten from it against `jwa-builder`'s `RecordBuilderProcessor`. |
| 4 | Does `docs-honest` belong in `verify-migration.js` or a separate `docs-gate.js`? | **In `verify-migration.js`, as a ninth check.** The deciding argument is that the risk in a gate is being skipped, not being crowded: one command that is already run keeps running, and a second gate is a second thing to forget. The cost is real and recorded here: this gate's name says *migration*, and `docs-honest` is the check that outlives it. When the migration tooling is retired, `docs-honest` should move to its own entry point rather than go with it. |
| 5 | Does `AGENTS.md` § 1 need changes? | **No.** It is about generated code being committed, navigable source and is unaffected; the `{@link}` header rule in DEC-021 is still the generator-side fulfilment of it. Only § 2's AST bullet changed. |

### What was built

- **`AGENTS.md` § 2** — the JavaParser bullet became the OpenRewrite rule: `SourceReader` and its two
  channels, javac for positions, `TreeQueries` for traversal and kinds, and text splicing for writes,
  with the guide and DEC-030 named at the end.
- **`doc_knowledge/code.graph.md`** — rewritten as *Reading and writing Java source*: the read contract
  and why the two channels are not redundant (F-34), javac positions and the `(simpleName,
  enclosingChain)` match, the `TreeQueries` trap table, the splice rule and the two splicers, and the
  measured read cost from `benchmarks/latest.json`.
- **Entry points and module APIs** — `README.md`, `README.java_watch_2.md`, `hipster-entity-tooling`
  (a new *Reading, querying and writing source* table, and *Reading source outside Maven* corrected from
  the `javaparser-core` pin to the `rewrite-java-25` artifact), `jwa-sidecar/README.md` +
  `modules.md`, `hipster-entity-core/README.md`, `hipster-entity-example/codebuddy.md`,
  `scripts/rewrite-migration/README.md` (the migration marked complete, the gate documented, and
  `docs-honest` described), and `doc/architecture/module-map.md` + `DEC-W003` (dependency lists that
  still claimed `javaparser-core`).
- **`docs/RecordBuilderGenerator.md`** — rewritten against `jwa-builder`, and
  `java-watch-agent/record builder.md` retired into it.
- **`DEC-030-openrewrite-source-representation.md`** — the representation decision: the LST, javac for
  validity and positions, splicing over printing, the language level as an artifact, and the rejected
  alternatives (two parsers, a symbol solver, whole-file printing). `AGENTS.md` § 2 points at it, and it
  is registered in the decisions index. **DEC-029 had tentatively reserved the number 030** for the
  generated-artifact index pointer; that follow-up is renumbered to 031 in place, with a note.
- **DEC and record notes** — appended, never woven in, to DEC-009, DEC-020, DEC-023, DEC-029,
  `gen-freezing.md`, six brainstorms, five watch-series DECs, the `merge-java` records, the legacy
  continuation plan and `java-watch-agent/plan.md`. Each note states what changed in the tense and what
  implements it now; none rewrites the decision it annotates.
- **`docs-honest`** — `documentation.js` (scan + classification), a 28-entry `DOC_ALLOWLIST` in
  `curation.js` with the same `{reason, deferredTo, status}` shape as the source allowlist, and the
  check in `verify-migration.js`.

### Where this phase departed from the plan

1. **`docs-honest` lives in `verify-migration.js`** rather than a `docs-gate.js` (decision 4), so the
   gate grew to nine checks instead of gaining a sibling. The gate's own README and the report's
   "Build gate" section say so.
2. **`merge-java/VERSION_MAINTENANCE.md` is allowlisted rather than rewritten.** The plan listed
   `merge-java/README.md` as a rewrite target and was right about it, but the module's most
   JavaParser-dense live document is `VERSION_MAINTENANCE.md`, where every mention is *OpenRewrite's own*
   `org.openrewrite.java.JavaParser` — the class the module's parser-selection notes are about. Rewriting
   it would rename OpenRewrite's class; the exemption records that instead. `IMPROVEMENTS_DELIVERED.md`,
   `IMPLEMENTATION_PLAN.md`, `IMPROVEMENT_PROPOSAL.md` and `CHANGELOG.md` are records and were left as
   written, with a note on the one sentence in `IMPROVEMENT_PROPOSAL.md` that stated the superseded rule.

### The measured result

`bun run scripts/rewrite-migration/verify-migration.js` — **`RESULT: PASS`** (with one warning):

```
  markdown scanned   284
  still mentioning   51 (520 lines)
  live documents     13 (1 teaching the removed API)
  doc allowlist      28 entries
```

The 51 mentioning documents are 13 live, 17 decisions and 21 records/generated pages; the live 13 are
all allowlisted with a reason, and **exactly one live document still shows a removed call** —
`docs/RecordBuilderGenerator.md`'s "Why the previous implementation was replaced", where
`LexicalPreservingPrinter` is named as the primitive whose removal is the reason the current design
generates text. It is a warning rather than a failure because the reason for keeping it is recorded, and
the warning is the point: a reader will see it.

Nothing in the phase changed a `.java` file, so the 1326-test figure Phase 7 measured carries over
unchanged; the gate's own Bun suite gained 8 tests (`scripts/rewrite-migration/rewrite-migration.test.js`,
41 tests green).
