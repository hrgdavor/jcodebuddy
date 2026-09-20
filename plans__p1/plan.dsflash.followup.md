# `plan.dsflash.followup.md` — resolving the open items in `plan.dsflash.notes.md`

Companion to [`plan.dsflash.md`](plan.dsflash.md), [`plan.dsflash.baseline.md`](plan.dsflash.baseline.md)
and [`plan.dsflash.notes.md`](plan.dsflash.notes.md).

`plan.dsflash.notes.md` is a **completed execution record**: it says so itself in *Plan scope status*
("Every phase's tasks are complete, with one mechanism explicitly left open and two scope decisions
recorded rather than half-built"). It is therefore **not** a bug list. It is, however, the only place
where the residue of that execution is written down: open mechanisms, documented-and-unapplied
recommendations, silent hazards, decisions that live only in a note, and a toolchain that can report
a pass it did not earn.

This document is the plan to close that residue. Every item below:

* names the note entry it comes from (`F-n`, `D-n`, a status-table row, or a line reference);
* states the **observable** that is currently wrong (not the code smell);
* gives the concrete change, with file paths;
* gives the test that makes the item *checkable* — because the notes' own recurring lesson
  (F-34, F-38, F-43, F-47, F-50) is that a rule which is true but unasserted decays into a rule that
  is false;
* names the acceptance gate.

---

## 0. Scope, ground rules, and what this plan deliberately does *not* do

### 0.1 The evidence base, re-verified rather than trusted

The notes' claims were spot-checked against the working tree before this plan was written, so the
items below are defects that exist, not defects that are described. Confirmed at the time of writing:

| Note | Claim | Verified how |
|---|---|---|
| F-44 / status 3.20 | emission dedup **is** fixed; reporting dedup is **not** | `EntityMetadataGenerator.java:761,836` — `alreadyFound` is hoisted above the marker loop; `DivergenceReporter` has no dedup of any kind, and `EntityMetadataGenerator.java:900` still calls `divergences.report(...)` once per marker iteration |
| F-38 / F-45 | `classLiteral` is open-coded per emitter, `java.lang.Object.class` is the "unresolved" sentinel | `EntityMetadataGenerator.java:2287-2318` and `FieldBoilerplateGenerator.java:832` are two independent copies; the `default:` branch returns a bare identifier unchanged (`T` → `T.class`) |
| 1.16 / status table | `EnumConstantOrderCli --diff` is refused with exit 2, not implemented | `EnumConstantOrderCli.java:57-61` — `return 2` with a "not implemented as a baseline" message |
| F-12 / 7.1-7.4 | `FieldDef.column()` returns `null` for every unannotated field | `FieldDef.java:78-80` is `default String column() { return null; }`; `FieldBoilerplateGenerator.addFieldSourceOverrides` (line 596) emits `column()` **only** when `@FieldSource` is present, so the only two generated overrides in the tree are the annotated `age`/`departmentName` (`PersonSummary_.java:30,48`) |
| F-47 | the recorded gate does not `clean` | `scripts/mvn-jdk25.cmd:80,84` invoke `%JCODEBUDDY_MVN% -o -pl %HE_MODULES% -am %HE_ARGS%`; no `clean`, no `-Dmaven.compiler.useIncrementalCompilation=false`. No `.github/workflows` exists, so the gate is developer-local only |
| D-13 / `tmp/` | untracked regeneration debris exists | `tmp/` and `hipster-entity-example/tmp/` are present and untracked; `.gitignore` has no `tmp/` entry |
| D-16, D-17 | previous-value removal and SQL opt-in are done | `EntityUpdateTrackingArrayTest`/`EEnumSetBasicFunctionTest` reflection guards; `EntityMetadataGenerator.isGenerateAdapters()` + `ViewAdapterGeneratorTest#sqlGenerationIsOptInAndOffByDefault` |
| D-12 | R1's DEC **does** exist and is linked | `doc-hipster-entity/architecture/decisions/DEC-023.md` exists; `ordinal-array-contract.md:135,327` links it. The note's premise was stale |

**Baseline test counts**, read from the committed surefire reports (not from the notes, whose footer
is a moving snapshot): **core 88, tooling 214, jackson 26, test 31 = 359**. Item 5 in § 5 changes
whether even that reading is trustworthy.

### 0.2 Ground rules for whoever executes this

1. **Every gate run is `clean`.** `mvn -o clean test`. F-47 (the recorded gate was satisfied by a
   previous revision's class files) and D-16 (ECJ test classes carrying `Unresolved compilation
   problems` markers) both mean a non-clean green is not evidence. Once § 5.1 lands, this is the
   launcher's default and not a thing to remember.
2. **A generator change regenerates the example in the same pass.** `ExampleRegenerationTest` is the
   mechanism; a red one means regenerate and commit, never "fix the test".
3. **A claim in this plan's final report is worthless without the test that asserts it.** The
   follow-up's output is tests, not prose.
4. **Do not re-litigate the two accepted reversals** (§ 0.3).
5. **If an item turns out to be unreachable or already true, say so and delete it** — the notes have
   three precedents (D-13, F-12's DEC-023 claim, the "not started" rows of 2.3 and 1.16 that were
   already done). A stale item costs more than a missing one.

### 0.3 Explicitly out of scope (recorded so it is not reopened)

| Item | Why it stays as-is | Note |
|---|---|---|
| The tracker keeps no previous value; comparison is caller-side | A direct instruction reversed § 4.2/D2; DEC-012's revision section records it and reflection guards pin the removal | D-16 |
| SQL generation is a draft, opt-in via `--adapters` only | A direct instruction; pinned by `ViewAdapterGeneratorTest#sqlGenerationIsOptInAndOffByDefault` and by the example regenerating with adapters off | D-17 |
| `mvnd` is not the recorded launcher (Maven 3.9 is) | Environment-bound (mvnd's daemon registry is outside the workspace). The script's `JCODEBUDDY_MVN` override is the correct shape | D-1 |
| `mvn install` is not exercisable here; G4's ordering trap is documented, not demonstrated | Local repository is outside the workspace | D-2 |
| `scripts/mvn-jdk25.sh` is asserted by inspection only | No runnable bash in this environment | D-15 |
| `project-automation`'s reactor carries a pre-existing `MetadataServerTest.httpForyRoundTrip` failure | Different subsystem, outside the six-module gate | F-48 |
| `AGENTS.md` does not contain the tooling-must-not-depend-on-`hipster-entity-test` rule | The rule is stated in the tooling README with its evidence; editing AGENTS.md is a policy change, not a follow-up | D-12 |
| `.kilo/worktrees/` exclusion in the working-tree walk | Still correct for `Files.walk`-based scanners | GR-6, D-13 |
| The `tmp/` debris is *untidy, not dangerous* for the R1 checker | Measured: the checker's file set comes from `git ls-tree` of the baseline, so an untracked copy is never compared | D-13 correction |

### 0.4 Exit gate for the whole follow-up

```
scripts\mvn-jdk25.cmd                      # now `clean test` over the six modules
scripts\run-demo.cmd                       # exits 0, six sections
cd project-automation && <recorded run>    # EntityRegenerationWatcherTest 11/11, run explicitly
git status --porcelain                     # only intended changes; no new untracked debris
```

plus: the new tests of § 1-§ 5 are green, `ExampleRegenerationTest` is a byte-identical no-op, and
`DivergenceKindTest`'s clean-pass guard still asserts that **none** of the new kinds fire when nothing
is wrong.

---

## 1. Code-open mechanisms (the notes' own "still open" list)

### 1.1 `3.19` — per-member three-state comparison (the one mechanism the notes call open)

**Evidence.** Notes F-51, status row `3.19`, and *Where the execution stands*: *"a user-added
**non-type** member (a helper method, a field) and an edit to the body of a *generated* method are
still overwritten in the record and enum, and were never preserved in the builders either."* Nested
**types** are preserved (F-30 source-range slicing); everything else that DEC-020's three-state model
covers is not.

**Observable that is wrong.** A developer adds `public String displayName() { ... }` to
`PersonSummaryBuilderTracking.java` (or edits the body of the generated `build()`); the next
`generate-sources` pass silently deletes it. No diagnostic is produced, so the loss is invisible.
This is the same failure class as F-25 and F-51 item 2 — the class DEC-020 exists to eliminate.

**Change.**

1. In `CooperativeCodegen`, introduce the three-state decision at **member** granularity, alongside
   the existing type-level slicing:
   * **generated-and-canonical** → emit the generator's version (no divergence);
   * **generated-and-diverged** (the generator owns the name, the previous revision's text differs
     from what would be emitted today) → emit the canonical version **and**
     `divergences.report("generated_member_diverged", …)` in the DEC-022 six-pair shape;
   * **user-owned** (a name the emitter never produces) → keep the previous revision's text verbatim,
     by **source range** exactly as nested types are kept (F-30's lesson: never re-print from the
     AST, or javadoc is silently deleted).
2. The "generator owns the name" set is per emitter and must be **explicit and testable**, not
   inferred: `<View>Builder` owns its field declarations, `get`/`set`/`build`/copy-constructor;
   `<View>BuilderTracking` additionally owns `changes`/`changesBuilder`/`changedValues`/`currentValue`
   /`changesDeep`/`nestedTrackers`/`collectionDeltas`/`hasCollection` and its setters;
   `<View>Record` owns its components, the canonical constructor, accessors and `create`;
   the field enum owns its constants, constructor and the five `FieldDef` accessors.
3. Apply it in all four whole-file emitters (`ViewBuilderGenerator`, `ViewTrackingBuilderGenerator`,
   `ViewRecordGenerator`, `FieldBoilerplateGenerator`). The **field enum's constant list is already
   handled** by the R1 ledger — do not duplicate that logic; this item covers the *other* members
   only.
4. Add `generated_member_diverged` to `DivergenceReporter.KINDS`.

**Scope decision, deliberately taken (see § 8.2).** State 2 is implemented for a member whose
previous body was **already canonical at some point** — the practical test is that the member's
signature matches the generator's and only the body differs. A member whose signature drifted (the
author renamed a generated setter) is treated as state 3, user-owned, because "the generator owns this
name" cannot be asserted about a name it would not emit. Without that restriction, every hand-edited
signature becomes a revert-plus-divergence, which is a worse outcome than the silent replacement it
replaces.
5. `ViewInterfaceGenerator` (3.17a) is already "write only when missing", which is a weaker form of
   the same rule and is correct for a file the developer owns. **Do not** extend it: an author who
   narrows `toBuilder()`'s return type owns that decision (F-32).
6. Update `DEC-020.md` with the member-level three-state definition and the report format, and
   re-scope `plan.dsflash.md` `§ 8.7/3.19`'s annotation from "open" to "closed".

**Tests.** Extend `CooperativeCodegenTest`:
* `aUserAddedHelperMethodSurvivesEveryEmitter` — add one method to each of the four generated files,
  regenerate twice, assert byte-identical and assert the method's javadoc came back verbatim;
* `anEditToAGeneratedMethodIsReportedAndRevertedToOneCanonicalBody` — edit `build()`'s body, assert
  `generated_member_diverged` names the member, and assert the second pass is a no-op;
* the existing run-twice byte-identical assertion stays and must still pass on the real example.

**Acceptance.** `ExampleRegenerationTest` still byte-identical (the example has no non-type user
members today, so this is the "no false positives" half of the item); `CooperativeCodegenTest` green.

### 1.2 `3.20` — a view claimed by two markers reports its divergences once per marker

**Evidence.** Status row `3.20`: *"**Known remaining gap:** a view reached by more than one marker
reports its divergences once per marker — the *emission* duplication was fixed in F-44, the
reporting duplication was not."* `EntityMetadataGenerator` creates **one** `DivergenceReporter` per
run (line 510) and passes it into the marker loop, so the per-view reports emitted at lines 900/931/
942/953 land once for each marker that claims the view.

**Observable that is wrong.** `PersonSummary` sits under both `person.entity.Person` and
`example.Auditable`; a single `nested_record_reused` for it is printed twice, and a reader cannot
tell a duplicate from two genuinely different facts.

**Change.**

1. Deduplicate in `DivergenceReporter` at the point of insertion: two entries are the same iff their
   **`kind` and `location` pairs** are equal. Preserve first-occurrence order; expose
   `duplicatesRemoved()` (or equivalent) so a test can prove the collapse happened rather than
   assume it.
2. Do **not** merge entries that share kind+location but differ in `cause`/`current`: those are two
   facts and collapsing them would delete information. If such a case turns out to exist, keep both
   and disambiguate the location instead — record the decision either way.
3. Fix the adjacent latent defect while in the file, because the dedup rule depends on it: entries
   reach the reporter by two different routes — `report(...)`, and `add`/`addAll` of a
   pre-formatted line from the ledger (`FieldBoilerplateGenerator`'s `divergenceSink.accept(...)` at
   lines 100 and 123, built at lines 192-419). Those ledger lines *do* happen to start with
   `kind=`, so `ofKind` finds them today, but nothing enforces it and `ofKind` is a literal string
   prefix match on `"kind=" + kind` that silently returns an empty list for a line that does not.
   **Store the structured fields** (kind, location, cause, current, canonical, action) on the entry,
   render the DEC-022 line from them, and match `ofKind` on the stored kind. That makes both routes
   indentical, makes dedup a field comparison instead of a string comparison, and removes a class of
   "the report says nothing" bug that no test would catch.

**Tests.** In `DivergenceKindTest` (or a new `DivergenceReporterTest`): a fixture tree with one view
claimed by two markers produces each divergence exactly once; `ofKind` finds a kind that arrived via
`addAll` of a ledger line; an entry that does not follow the DEC-022 order is still matched by its
stored kind; the clean-pass guard ("no kind fires when nothing is wrong") still holds.

**Acceptance.** `DivergenceKindTest` green; `ExampleRegenerationTest` still byte-identical.

---

## 2. Silent hazards the notes record but did not close

These four share one shape: the code is *right*, the report is *wrong*, and nothing asserts the
difference. F-43's lesson is explicit — *"a guard that cannot fire is worse than no guard, because it
also stops the search for the real cause."*

### 2.1 `F-38` — `classLiteral`'s default branch can emit uncompilable source (`T.class`)

**Evidence.** Notes F-38: *"`classLiteral`'s default branch passes an unrecognised bare identifier
straight through, so a view accessor returning a **different** type parameter name (`T`, `E`) would
emit `T.class` in the enum — which does not compile."* Verified: the `default:` branch of
`EntityMetadataGenerator.classLiteral` (line 2311-2316) returns any dot-free identifier unchanged.

**Observable that is wrong.** A view whose accessor returns a type variable other than `ID` produces
a field enum that does not compile, with no diagnostic. Reachability is *shape-dependent*, not
impossible — which is precisely how F-42 (`name` shadowing `forName`'s parameter) survived 190 green
tests.

**Change.** Make the sentinel explicit and total:
1. Extract the two duplicated `classLiteral` implementations into one shared helper (they have already
   drifted conceptually: one maps `ID`→`Object` via a caller-side special case, the other does not).
2. A bare, dot-free identifier that is **not** a known `java.lang`/JDK/primitive name and **not**
   resolvable from the pass's type index is **unresolved**: emit `java.lang.Object.class` (the same
   spelling F-45's `emitSafeIdType` already uses for the same reason) and report
   `type_unresolved` — a **new** kind — naming the view, the accessor and the identifier.
3. Keep F-38's existing suppression exactly as it is: the `type_mismatch` comparison stays skipped
   when the canonical expression is `Object.class` and the on-disk type is not literally `Object`.
   The point of § 2.1 is to make the *emission* safe and the *report* honest, not to resurrect the
   false positive F-43 chased.
4. This **subsumes and replaces** the special case that maps the type parameter name `ID` to
   `Object` — `ID` becomes simply one unresolved identifier among others, and the `Object.class`
   sentinel that F-38/F-43/F-45 built around it stays exactly as it is. Do not keep both mechanisms:
   the `ID` special case is a hand-maintained list of one, which is the shape that produced F-43.

**Tests.** A fixture view declaring `<T> T value();` (and one declaring the `ID` case) must (a)
compile under `CompileHarness` and (b) produce a `type_unresolved` divergence naming `T`; the
existing example must remain divergence-free for this kind.

**Acceptance.** `GeneratedSourceCompilesTest`, `DivergenceKindTest`, `ExampleRegenerationTest` green.

### 2.2 `F-51` — the four deliberately-unresolved type-name categories should be pinned

**Evidence.** Notes F-51: *"**Not** resolved: on-demand (`import a.b.*;`) imports, a `java.lang` type,
a type variable, or a type from outside the source set. All of those are either correctly
import-free or genuinely unresolved, and the compile gate reports the last one."*

**Observable that is at risk.** The next reader cannot tell "deliberately unresolved, safe" from
"regression". A future change to `Property.typeImports()` could drop the `java.lang` case and
silently make every `String` field emit `String` with no import — currently correct, and correct only
by accident of a table in `JdkImportSupport`.

**Change.** One test class (or four cases in an existing one) that asserts, per category:
* on-demand import + a name from that package → emitted **without** an import and **with** the
  author's spelling, and the `CompileHarness` gate decides whether that is acceptable;
* a `java.lang` type → emitted with **no** import (the JDK table covers it);
* a type variable → § 2.1's `type_unresolved` + `Object.class` (the two items share a fixture);
* a type from outside the source set → *reported*, not silently guessed. If the compile gate is
  currently the only signal, state that in the test's name so the next reader does not "fix" it.
Update the `hipster-entity-tooling/README.md` type-resolution table with the same four rows.

**Acceptance.** The four cases are green and documented; no production behaviour change expected
(if one changes, that is a finding, not a test failure to paper over).

### 2.3 `F-34` / `F-36` — `ParseResult.isSuccessful()` must guard every read, not just the ledger's

**Evidence.** F-34's finding (a): `planLedger` treated *"the parse returned a result"* as *"the file is
readable"*, and JavaParser's error tolerance produced a **partial** unit with **no constants** — which
the planner read as "a fresh enum" and rebuilt from the resolved fields. That was a silent renumbering
of a persisted positional array, reached by the code written to prevent it.

**Change.** Audit every `new JavaParser().parse(...).getResult().orElse(null)` in the tooling and
require `ParseResult.isSuccessful()` before the result is used. Known sites to check:
`FieldBoilerplateGenerator.isPolymorphicRootEnum` (line 143), `planLedger`, `CooperativeCodegen`'s
previous-revision read, `auditExistingEnum`, `EnumConstantOrderChecker.readLedgers`,
`EnumCompactionCli`'s refusal path. A file that fails to parse must take the **fail-safe** direction
(preserve what is there, report `enum_not_parsed` or the nearest equivalent) — never the "treat as
empty" direction.

**Tests.** A malformed fixture per site asserting the fail-safe outcome. The ledger case already has
one (`enum_not_parsed`); the value of this item is the *other* sites, especially
`isPolymorphicRootEnum`, where a malformed `PaymentMethod_.java` would today return `false` and let
the generator clobber the one hand-written root the polymorphic path depends on.

**Acceptance.** Every parse site either uses `isSuccessful()` or carries a comment saying why the
tolerant path is the safe one, and a test pins the malformed-input behaviour.

### 2.4 `F-35`'s class of defect — one ordinal space, asserted at its source

**Evidence.** F-35 is described as *"the most serious defect this execution found"*: every emitter
computed a field's ordinal from the declaration-ordered property list while the enum defined it by
the ledger, so retiring a **middle** accessor made the JDBC binder bind `view.get(2)` (the retired
slot) into the `email` column, silently. The fix introduced
`EntityMetadataGenerator.ledgerOrderedProperties` as the single source of the ordinal space.

**Change — a guard, not a fix.** `ledgerOrderedProperties` is now the contract; nothing prevents a
new emitter from reaching for `fullProperties` again. Add a test that retires a middle accessor and
asserts, per artifact, that the ordinal space is the ledger's: the binder's `ORDINALS`, the tracking
setter's ordinal literal, the record's component count and `build()`'s positional pass-through, and
`get(int)` at the tombstone's own index. `TombstoneLedgerTest` covers most of this already — extend it
rather than duplicating, and add the one thing it does not assert: that a **newly added** emitter
cannot silently opt out. The cheapest enforceable form is a source-level assertion (the emitters that
map fields to ordinals must not reference the declaration-ordered list) in the shape
`DependencyBoundaryTest` already uses for scopes and imports.

**Acceptance.** `TombstoneLedgerTest` green and extended; `DependencyBoundaryTest`-style source
assertion added.

### 2.5 (found while implementing § 2.3) — the shared parser must be configured for the project's language level

**How it was found.** This item is not in the notes and was not in this plan's first draft. Pinning
`ParseResult.isSuccessful()` in `§ 2.3` made the generator start reporting *its own output* as
unparseable: `PersonSummaryBuilder.java`, `PersonSummaryBuilderTracking.java` and every
`<View>Record.java`, with

```
(line 41,col 16) Switch expressions are not supported ... starting from 'JAVA_12'
(line 14,col 1)  Record Declarations are not supported ... starting from 'JAVA_14'
```

**The defect.** A bare `new JavaParser()` parses at `ParserConfiguration.LanguageLevel.POPULAR`, i.e.
**Java 11**. Every generated `get(int)` is a switch expression, the whole `RECORD` level is records,
and the example's payment-method family is `sealed`. So a large part of the tree — including the files
the generator re-reads to preserve user edits — was a *parse problem* at the default level, and the
five `getResult().orElse(null)` call sites swallowed that silently. This is **F-23's failure mode
generalised**: F-23 found one occurrence (a JavaParser too old for `sealed` made five example files
generate nothing, with no error); this is the same class of bug living in the parser *configuration*
rather than the parser *version*, and it was invisible for the same reason — the symptom of a partial
parse is missing output, and nothing asserted the report's contents.

**Change.** `SourceReader` owns one shared `JavaParser` configured to
`ParserConfiguration.LanguageLevel.JAVA_25` — pinned to the root POM's `maven.compiler.release=25`, not
to "the newest constant", so parser and compiler are always told the same thing. Every parse site in
the tooling (generator **and** the `validation` package's checker/compactor/validator) now goes
through `SourceReader.parser()`.

**Tests.** `SourceReaderTest` — a switch expression and a record parse cleanly; the configured level is
asserted directly; genuinely broken source is `unparseable`; a missing file is an empty read, not an
unparseable one. `ParseGuardTest` — the four fail-safe sites.

**Lesson for the remaining items.** Whoever implements § 2.1/§ 2.2 should expect the *same* pattern
one layer down: a rule that was believed to be in force but was not, discovered only by making the
failure observable. That is the argument for § 5.3's exact-set assertion existing before those items
are attempted, which this implementation round confirms.

---

## 3. Small, self-contained, decided-now items

### 3.1 `1.16` — implement `--diff` as a baseline, or delete it from the contract

**Evidence.** `EnumConstantOrderCli.java:57-61` currently **accepts** `--diff` and exits **2** with
*"--diff is accepted but not implemented as a baseline"*. The class javadoc (line 18) still advertises
`[--diff <file>]`, and the tooling README documents the flag.

**Observable that is wrong.** The documented flag surface and the implemented flag surface disagree,
and the only "fix" so far is a message saying so. Either is defensible; leaving both is not.

**Change — preferred:** implement it. The baseline is a unified diff against the pre-change revision;
apply the `+++ b/<path>` and hunk lines to the baseline source text to obtain the "after" text, then
run the existing comparison unchanged. Constrain it explicitly: `--diff` is **mutually exclusive** with
`--baseline`, still exits 2 on an unparseable diff, and only supports the `-u` form git produces.
**Fallback if the parser starts to grow into a git implementation:** delete `--diff` from the usage
block, the README and the option parser, leaving one supported baseline mechanism (a git ref), and
record the removal. Do not ship "accepted but ignored" in any form.

**Tests.** `EnumConstantOrderCliTest` (6 tests today) gains: a diff that moves a constant → exit 1; a
diff that appends → exit 0; `--diff` + `--baseline` together → exit 2; a malformed diff → exit 2.
Keep the existing exit-code contract table in the test's javadoc in sync.

**Acceptance.** README, javadoc and behaviour agree; the test names the contract in one place.

### 3.2 `F-12` / `X2` / `7.2-7.4` — `column()` for an unannotated `COLUMN` field

**Evidence.** Notes F-12: *"`FieldDef.column()` returns `null` for every enum in the tree today …
An adapter driven purely by `column()` therefore sees no writable column for a view that carries no
`@FieldSource` at all. Flagged for Phase 7.2–7.4."* Verified: `FieldDef.column()` (line 78) returns
`null`, and `FieldBoilerplateGenerator.addFieldSourceOverrides` (line 596-616) emits an override only
when `@FieldSource` is present — the generated example carries exactly two overrides, both on
annotated fields.

**Decision to take (pick one, write it in DEC-023 and the API javadoc, implement it):**

* **(a) generator-side, recommended.** Emit the `column()` override for **every** field whose
  resolved `fieldKind()` is `COLUMN`, using `@FieldSource(column=…)` when present and the accessor
  name otherwise. `FieldDef`'s default stays `null` (the "not a column" answer), so a `DERIVED` or
  `JOINED` field keeps returning `null` — which is the semantic the adapters already rely on. Cost:
  every generated field enum grows four lines per column field, and the example must be regenerated
  (a legitimate "generator change Phase 4.1 has to commit").
* **(b) framework-side.** Change the `default` to return `name()` for `fieldKind() == COLUMN` and
  `null` otherwise. Zero emission cost and zero regeneration diff, but it puts a resolution rule in a
  default method that `FieldSource`'s javadoc currently says lives in the generator — so the same
  doc must change in the same commit.

**Recommendation: (a)**, because the notes' own reasoning for the existing overrides is *"Emitting
the resolved name rather than the empty label keeps adapter code from re-implementing the fallback"* —
and (b) does exactly the opposite by moving the fallback into the interface.

**Tests.** A fixture view with no `@FieldSource` anywhere: every `COLUMN` field's generated constant
answers `column() == <accessor name>`; a `DERIVED` and a `JOINED` field answer `null`; and the
draft adapter (`--adapters`) binds all of them without a manual name table. Extend the existing
`GeneratedAdapterRoundTripTest` rather than adding a parallel one.

**Acceptance.** DEC-023 + `FieldDef`/`FieldSource` javadoc + generator + regenerated example agree,
and the adapter test asserts the name for an unannotated column.

---

## 4. Toolchain and repository hygiene

### 4.1 `F-47` — the recorded gate must not be satisfiable by a previous revision's classes

**Evidence.** `scripts/mvn-jdk25.cmd` runs `-o -pl %HE_MODULES% -am test` with no `clean`;
`EEnumSetTrackingJmhBenchmark.java` demonstrably did not compile while repeated non-clean runs
reported `core 88 / BUILD SUCCESS` (F-47). F-49 records the same mechanism from the other side (the
JMH annotation processor never gets a second chance without a `clean`).

**Change.**
1. Add `clean` to both branches of `scripts/mvn-jdk25.cmd` (`:he_run`, `:he_default`) and to
   `scripts/mvn-jdk25.sh`, keeping the free-form `%*` passthrough unchanged so `-o -pl … package`
   still behaves as the caller asked.
2. Add `-Dmaven.compiler.useIncrementalCompilation=false` to the same two branches, so a *skipped*
   compile is not possible even for a partially-clean tree.
3. Update `README.md`'s *Building and Testing* table (line 50-53) to say the recorded gate is now
   `clean test`, and say **why** in one sentence (F-47), so the next editor does not "optimize" it
   back.
4. The `.cmd` constraints from D-11 stay binding: **pure ASCII + CRLF**, and a property argument must
   be quoted at the call site. Neither new argument contains `=`-with-a-dot in a way cmd splits —
   verify by running the script once with a filtered test before declaring the item done.

**Tests.** No unit test can assert a build script's flags cheaply; the verification is (a) the
corrected script runs the full gate green, (b) a deliberately broken test source now **fails** a
plain `scripts\mvn-jdk25.cmd` run (plant a syntax error in a scratch test, observe red, revert), and
(c) `scripts/run-demo.cmd` still exits 0. Record (b) in the notes as the measurement F-47 wanted.

**Acceptance.** The gate is red on a broken source without any manual `clean`; the README says so.

### 4.2 `F-23` — one pinned JavaParser, and a way to prove it

**Evidence.** F-23: the local repository holds **eleven** JavaParser versions (`3.25.1` … `3.28.2`);
an ad-hoc classpath globbed from the repository picked `3.25.1`, which cannot parse `sealed`, so five
example files failed to parse and **the generator silently produced nothing for them** — visible only
as missing files in a diff. The Maven build is correct (root POM property +
`dependencyManagement`; the tooling POM declares no version), and the mitigation is "do not glob the
repository".

**Change.**
1. Put the classpath rule where the next runner will read it: the `hipster-entity-tooling/README.md`
   gets the exact reproduction — `mvn -o -pl hipster-entity-tooling dependency:build-classpath`
   (plus the F-52 warning: the reactor's own `hipster-entity-{api,core}/target/classes` must come
   **first**, or the run compiles against stale installed jars).
2. Make the pin checkable: a test that asserts the tooling POM declares **no** `javaparser` version
   and the root POM's property resolves to exactly `3.28.0`. The POM-as-text technique is the one
   `DependencyBoundaryTest` already establishes (scopes and imports cannot be checked from a
   classpath).
3. Optional, cheap, and worth it: have the generator print the JavaParser version it loaded in its
   `--help`/startup banner, so a partial run has a visible explanation.
4. **Do not** delete the extra repository versions — they are not this repository's to manage.

**Acceptance.** A single test states the pin; the README states the classpath rule and the F-52
ordering caveat.

### 4.3 `D-13` / F-52 — `tmp/` debris: `.gitignore`, not code

**Evidence.** `tmp/` (`disc`, `regen3`, `regen4`, `Probe.java`) and `hipster-entity-example/tmp/` are
present and untracked; `.gitignore` has no `tmp/` rule. D-13's original hazard claim was **measured and
disproved** for the R1 checker (its file set comes from `git ls-tree`, so an untracked copy is never
compared). It remains a hazard for tools that *do* walk the working tree.

**Also verified while re-checking (a correction to the first draft of this plan).** There is **no
untracked `*.metadata.json` debris**: the three files the binding produces
(`Auditable|PaymentMethod|Person.metadata.json`) live under
`hipster-entity-example/target/entity-metadata/`, and `target/` is already in `.gitignore` — the only
other copies are inside the `tmp/` trees handled here. The metadata-report concern D-10 raised (three
untracked JSON files dropped into a source tree) is genuinely closed, not pending.

**Change.**
1. Investigate `hipster-entity-example/tmp/` **before** ignoring it: if it is an output directory some
   test or script creates, it needs a `target/`-like location or an explicit cleanup, not a
   `.gitignore` entry that hides it.
2. Add `/tmp/` (and `hipster-entity-example/tmp/` if item 1 says it stays) to `.gitignore`.
3. Delete `tmp/Probe.java` and the `disc`/`regen3`/`regen4` regeneration copies — they are pre-plan
   probes whose existence `ExampleRegenerationTest` has made unnecessary. Confirm nothing references
   `tmp/` first (`grep -r "tmp/regen\|tmp/disc" scripts hipster-entity-* project-automation`).
4. Leave `EnumConstantOrderCli`'s exclusion list alone (D-13's correction).

**Acceptance.** `git status --porcelain` shows no unexplained untracked directory after a full gate
run and a demo run.

### 4.4 `F-48` — verify the watcher without pretending the reactor is green

**Evidence.** F-48: `mvn -o -pl project-automation -am test` fails in `metadata-server`'s
`MetadataServerTest.httpForyRoundTrip` (HTTP 500), a module this plan does not touch. The watcher's 11
tests were run directly with
`-Dtest=EntityRegenerationWatcherTest -Dsurefire.failIfNoSpecifiedTests=false`.

**Change.** Put that invocation — the working one — into `project-automation`'s README with a sentence
saying why the reactor run is not the verification, and re-run it as part of this follow-up's gate so
the 11 tests do not silently rot (the notes call the watcher *"the clearest remaining risk"* for
exactly this reason). Do not attempt to fix `metadata-server`; it is out of scope (F-48).

**Acceptance.** 11/11 green, with the command recorded next to the module that owns it.

---

## 5. Making the report and the decision record match reality

### 5.1 `D-12` / `F-12` — the DEC-021 table still uses Jackson 2 spellings

**Evidence.** D-6: under Jackson 3 (`tools.jackson.*`, `3.0.0-rc4`) two constants were renamed —
`ALLOW_UNQUOTED_PROPERTY_NAMES` (not `..._FIELD_NAMES`), and the features are enabled directly on
`JsonMapper.builder().enable(feature)` (no `mappedFeature()`); `ALLOW_TRAILING_COMMA`,
`ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS`, `ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS`,
`ALLOW_NON_NUMERIC_NUMBERS` and `ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER` keep their names.
`EnumConstantOrderChecker` uses the Jackson 3 names; DEC-021's normative table does not.

**Change.** Update DEC-021 § 4 to the Jackson 3 spellings, with a one-line note that the pinned subset
is unchanged and only the constant names moved. A normative record that disagrees with the code is
the exact anti-pattern the DEC set exists to prevent.

**Acceptance.** DEC-021 and `EnumConstantOrderChecker` name the same constants.

### 5.2 `F-49` — the JMH profile's `clean`-dependency is a trap worth one paragraph

**Evidence.** F-49: `-proc:full` was added to `maven-compiler-plugin` **inside the `jmh` profile only**
(JDK 23+ does not run classpath-discovered annotation processors), and *"without `clean`, Maven's
incremental check skips the recompile and the processor does not get a second chance"*.

**Change.** Add the exact measured invocation and its four benchmark numbers to the `hipster-entity-core`
README (or the JMH section of the tooling README, wherever a benchmark runner would look), including
the two honest caveats F-49 records (the `shallowOnly*` indirection is a harness artefact, not a
push-vs-pull result; the error bars are ±15-30k on the noisiest configuration). § 4.1's `clean` makes
this land naturally.

**Acceptance.** A developer can rerun the benchmark from the README alone and get a comparable table.

### 5.3 Divergence-kind coverage: assert the *absence*, not only the presence

**Evidence.** F-43's diagnosis: *"It stayed invisible because the tests that would have shown it
assert **specific** kinds rather than the absence of others, and the example tests do not look at the
report at all."* Status row 3.20 lists a cleanup pass and a clean-pass guard, but the guard is one
test in `DivergenceKindTest`.

**Change.** One test that runs the **real example tree** through a default (non-`--adapters`) pass and
asserts the report is **exactly** the expected set — an `assertSameSet` helper that fails on an
unexpected kind *and* on a missing one, not a loop of `hasKind` calls. That single assertion would
have caught F-38, F-43, F-44 and F-34(a) at once, and it is the cheapest possible regression net for
this subsystem.

**Measure before you pin.** The expected set is **not** assumed to be empty. On a settled tree every
constant already carries the R1 header, so the ledger's append/tombstone/drop kinds should not fire
(F-20 shows `enum_constant_removed` firing on the *bootstrap* pass that dropped the leaked pre-R1
constants, which was a one-time event); but `nested_record_reused` is reported for every view with a
usable nested record, and `polymorphic_root_enum_preserved` fires for the hand-written
`PaymentMethod_` on **every** pass. So the first task here is to run the generator over the example
once and print the report. If the observed set is stable and legitimate, pin it literally, entry by
entry. If a kind in it is *not* legitimate, that is a finding — fix it under its own item rather than
widening the expected set. Do not leave the assertion at "some entries", and do not silently filter
kinds out of it: a report whose expected content is unfalsifiable is not a gate.

**Acceptance.** The test fails if any unexpected kind appears in a default pass over the example.

---

## 6. Suggested order and rationale

| Order | Item | Why here |
|---|---|---|
| 1 | § 4.1 (`clean` gate) | Everything after it is verified by a gate that cannot lie. Doing this first means every later "green" is real. |
| 2 | § 5.3 (example report is empty) | One test, immediate net for the whole divergence subsystem; it also gives § 1.2 and § 2.1 a failure detector to develop against. |
| 3 | § 1.2 (dedup reporting) + `ofKind` fix | Small, in one class, and it precedes § 5.3's exact-list assertion (a duplicate would otherwise have to be pinned as expected). |
| 4 | § 2.3 (parse guards) | Fail-safe direction; small, and it protects the hand-written polymorphic root. |
| 5 | § 2.1 + § 2.2 (unresolved type names) | One shared fixture, one shared helper extraction. |
| 6 | § 3.2 (`column()`) | Independent; touches the example, so do it after the report tests exist to prove the regeneration diff is the *only* change. |
| 7 | § 3.1 (`--diff`) | Independent and self-contained. |
| 8 | § 1.1 (per-member three-state) | The largest item; it changes all four whole-file emitters, so it wants a quiet tree and the report net already in place. |
| 9 | § 2.4, § 4.2, § 4.3, § 4.4, § 5.1, § 5.2 | Guards, documentation and hygiene; finish with them so the closing gate covers the whole set. |

Two scheduling constraints, both from the notes:

* **§ 1.1 must not run concurrently with another generator change.** F-41 records that two parallel
  workstreams against one repository blocked verification three times; per-member preservation
  rewrites the same four emitters that § 3.2 and § 2.1 touch.
* **Every generator change regenerates the example in the same commit** (F-39: *"the example was
  regenerated and committed in the same pass, which is why the test is green again"*).

---

## 7. What "done" looks like

1. The notes' three open-ended statements are closed: `3.19`'s mechanism is implemented for every
   shape DEC-020 covers, the reporting duplication of `3.20` is gone, and the `--diff` flag is either
   real or absent.
2. `FieldDef.column()` answers a column name for an unannotated `COLUMN` field, and DEC-023 records
   which of the two mechanisms was chosen and why.
3. No path through the tooling treats a failed parse as an empty file, and `classLiteral` cannot emit
   a name that does not compile.
4. The recorded gate is `clean test` and demonstrably red on a broken source without manual
   intervention; a default pass over the example produces an *exactly-known* divergence set.
5. `git status` is clean apart from intended changes; the `tmp/` debris is gone or explained; the
   JavaParser pin is asserted by a test and the classpath rule is written where a runner will read it.
6. Every item above has a test that fails if the item is undone — and the notes gain a short
   `plan.dsflash.followup.notes.md` in the same format, recording what was measured, what was
   disproved (the expectation is that at least one item here is already true or unreachable), and
   what remains.

---

## 8. Considerations to settle before the first commit

These are the fork-points that decide *how* § 1-§ 5 are executed. They are recorded here rather than
left to the implementer because each one changes what "done" means, and two of them change the
schedule.

### 8.1 Execution preconditions (these are not optional)

**The working tree is currently uncommitted work from the previous execution: `git status` reports
157 entries (62 modified, 3 deleted, 92 untracked) on top of a `main` whose last five commits are all
`Update plan.dsflash.md`.** That matters for three separate reasons:

1. **F-31's in-place regeneration compares against a previous revision.** With the tree dirty, a
   regeneration diff cannot be attributed to *this* plan's change.
2. **§ 5.3 pins the exact divergence report of the example.** If the tree is mid-edit, the first
   measured report is a mixture.
3. **F-47's lesson is about revisions, not just class files.** A clean run of *this* tree is not the
   same as a clean run of `HEAD`: F-47's own broken-source finding (`EEnumSetTrackingJmhBenchmark`
   missing an import) records a red `HEAD` that the working tree has since fixed. A fresh checkout
   would therefore be red before any item here is attempted.

**Precondition:** before the first code change, make one hygiene commit of the previous execution's
tree (or otherwise freeze it as the baseline ref) and record, in the follow-up notes, whether
`HEAD` alone is green on a clean checkout. Every byte-identical and exact-set assertion in this plan
depends on that baseline existing.

**Second precondition:** capture the *before* state of the items this plan is measured on. Run the
divergence report over the example, run `scripts/run-demo.cmd`, and record the four test counts
(core 88, tooling 214, jackson 26, test 31 = 359) **from a clean run** before touching anything. § 7's
"done" is a delta against those numbers, and the notes contain several examples of a claim that was
true, unasserted, and then silently false.

### 8.2 Scope decisions taken now (so they are not re-decided mid-implementation)

| # | Fork | Decision, and why |
|---|---|---|
| 1 | Is § 3.2 (`column()`) generator-side or framework-side? | **Generator-side** (emit the override for every `COLUMN` field; `FieldDef`'s default stays `null`). The notes' own justification for the existing overrides — "keeps adapter code from re-implementing the fallback" — argues against moving the fallback into the interface. Consequence accepted: every generated enum grows per column field, and the example's diff is large; that is a legible diff, not a silent one. |
| 2 | Does § 1.1 (per-member three-state) ship whole? | **Scoped, and scheduled last.** It ships for *user-added non-type members* (the actual defect) and for *body-only edits of a generated member*; a member whose **signature** drifted is treated as user-owned, never reverted. Rationale: reverting a hand-edited signature is a worse outcome than the silent replacement it replaces, and "the generator owns this name" is not assertable about a name the generator would not emit. If this cannot be made to hold, the honest fallback is to ship the user-member half only and say so — not to ship a mechanism that "corrects" developers. |
| 3 | Is § 5.3's example report allowed to be non-empty? | **Yes, measured, and pinned literally.** `nested_record_reused` and `polymorphic_root_enum_preserved` are legitimate steady-state entries. Empty is a *possible* outcome, not the assumption. |
| 4 | How is a divergence entry identified as a duplicate (§ 1.2)? | **By structured fields, after § 1.2's item 3.** The first draft of this plan said "kind + location"; the safer rule is that entries are duplicates only when they are *identically* the same fact, and the safe implementation is to compare the stored fields and keep both when any of `cause`/`current`/`canonical`/`action` differs. Never delete a fact to make a report tidier. |
| 5 | Does `--diff` get implemented or removed (§ 3.1)? | **Implement, but with a stop rule.** If the unified-diff applier needs more than ~80 lines, or needs to handle anything beyond git's `-u` output and a `+++ b/<path>` header, **delete the flag** from the parser, the javadoc and the README and record the removal. Shipping "accepted but ignored", which is today's state, is the only outcome that is not allowed. |
| 6 | Does § 2.1 change `type_mismatch` behaviour? | **No.** The `Object.class` suppression of F-38/F-43 stays byte-for-byte as it is; § 2.1 only makes emission safe and adds `type_unresolved`. F-43 exists because a guard was written against the wrong string — do not re-open a working suppression in the same change. |
| 7 | Is a new DEC needed? | **Yes, one, and only one.** § 3.2 is a rule change with two sides (a generator and every future adapter), so it needs a record. Prefer extending **DEC-023** (the field-enum contract) with an explicit section over minting a new number, since the field enum is the artifact the rule lives in; if the implementer judges that it fits DEC-021's header/config scope better, mint `DEC-026` and link it from `DEC-023` and `patterns/ordinal-array-contract.md`. D-16 and D-17 already have their records; nothing else in this plan is decision-worthy. |

### 8.3 Risks, and the specific thing that mitigates each

| Risk | Why it is real | Mitigation to build |
|---|---|---|
| **Byte-identity vs. line endings** in § 1.1's saved members | F-34 finding (c) measured exactly this: JavaParser prints with `Platform.lineSeparator()`, so generated files carry `\r\n` on Windows while hand-written text blocks carry `\n`, and a substitution written against `\n` matched nothing. A preserved member re-emitted with the wrong separator turns every pass into a whitespace-only diff. | Preserve **bytes**, not re-printed AST — slice from the previous file's text as `CooperativeCodegen` already does for nested types (F-30) — and add a test that runs a preserved-member fixture whose previous revision is LF-only **and** one whose revision is CRLF, asserting byte-identity in both. |
| **A "guard that cannot fire"** repeats F-43 in the new code | Every new check in § 2 and § 5 is of that shape. | For each new kind, plant the condition and watch the test go red **before** committing: `type_unresolved` (a `T value()` fixture), `generated_member_diverged` (an edited `build()` body), `enum_not_parsed` at a *new* site (a malformed `PaymentMethod_.java`), and the § 5.3 exact set (add one known-bad fixture to the tree and confirm the test fails). |
| **Extracting `classLiteral` into one helper** changes canonical output | It is used by two emitters with a hand-maintained `ID` special case on one side only; a shared version must produce byte-identical output for every name in the example or `ExampleRegenerationTest` goes red for a reason unrelated to the item. | Do the extraction as its own commit with **no behaviour change** (assert `ExampleRegenerationTest` still byte-identical), then add the unresolved-name rule as the next commit. Two commits, two diffs, two explanations. |
| **§ 4.1's `clean` doubles the gate's wall time** | `clean test` over six modules rebuilds everything, every time. | Accept it. F-47's finding is that the cheap gate reported green for a source that did not compile; a slower gate that is right beats a fast gate that lied. Do **not** optimize it back with `-DskipTests` or a module subset in the recorded command. |
| **A restored byte-identity may be masked by an unrelated red** | The tree has 92 untracked files, including new test classes and new core types; a compile error in any of them looks like a generator failure. | § 8.1's hygiene commit, plus: on any red, first confirm whether the failure reproduces on the frozen baseline before investigating the item. |
| **The `column()` change touches every generated enum** | It alters a committed artifact the whole example consumes, including the hand-written `PaymentMethod_` root that must never be regenerated. | Land it after § 5.3's exact-set test exists, regenerate from the example's own binding, and assert `PolymorphicGenerationTest`'s "the hand-written root is preserved" in the same pass (§ 2.3 is what makes that protection fail-safe). |

### 8.4 What this plan deliberately leaves to the implementer

* **The exact shape of `generated_member_diverged`'s `action` text.** The DEC-022 format is fixed; the
  wording is not, and it should say the one thing a reader needs — whether their edit was kept or
  reverted.
* **The `--diff` scope** (§ 8.2 item 5), because it is a cost/benefit call best made with the parser
  in front of you.
* **Whether § 2.2's four cases become one test class or four methods.** What matters is that each
  case's *current* behaviour is pinned with a name that says "this is deliberate".
* **Whether the watcher's recorded command lives in `project-automation/README.md` or the root
  README.** § 4.4 requires it to be written down next to the module; which file is a style call.

### 8.5 Items deliberately *not* added, recorded so they are not "found" later

* **No new reactor or CI wiring.** There is no `.github/workflows` in this repository, so § 4.1's gate
  is developer-local by design. Adding CI is a policy decision for the repository owner, not a
  follow-up to these notes.
* **No fix to `metadata-server`** (F-48), no change to `AGENTS.md` §2's wording (D-12), no re-opening
  of the previous-value removal (D-16) or SQL opt-in (D-17).
* **No attempt to make `scripts/mvn-jdk25.sh` executable-by-test here**, and no attempt to use `mvnd`
  or `mvn install` (D-1, D-2, D-15). Those are environment limits; asserting them again costs time and
  proves nothing.
* **No deletion of the extra JavaParser versions in the local repository** (F-23) — they are not this
  repository's to manage; the pin is asserted by test instead.

## 9. Implementation record (this round)

Everything below was executed against the plan above and verified by a **clean** six-module gate. Counts
are `core / tooling / jackson / test`, read from the clean run's surefire reports.

| Section | Status | What landed |
|---|---|---|
| § 5.3 exact-set report | **done** | `ExampleDivergenceReportTest` (4 tests): a default pass over the real example must report *exactly* `nested_record_reused` + `addon_field_collision`, no entry twice, and every kind in `DivergenceReporter.KINDS` must name its producing test. It immediately found that `KINDS` was missing four kinds the generator really emits (`nested_record_reused`, `polymorphic_root_enum_preserved`, `addon_field_collision`, and later `source_not_parsed`). |
| § 1.2 reporting duplication | **done, and the notes were stale** | `DivergenceReporter` now stores the six DEC-022 fields, renders from them, and de-duplicates by whole-entry identity (identical facts collapse, differing ones are kept — asserted in `DivergenceReporterTest`, 7 tests). `ofKind` matches the stored kind, so an entry is found however it arrived. **Measured while implementing:** the two-marker fixture is *already* reported once, because F-44's emission dedup closed the reporting half too — `MarkerClaimedViewReportingTest` (2 tests) now pins the property instead of the bug. |
| § 2.3 parse guards | **done, and it uncovered § 2.5** | New `SourceReader` is the only way an existing file is read: `isSuccessful()`-gated, shared, and language-level-configured. Guards at the enum ledger, the polymorphic-root check (unreadable ⇒ preserve + `source_not_parsed`, which used to return `false` and let the pass overwrite a root nobody could read), the builder setter check, the view-interface writer, the scan walk (F-23's silent-skip mode is now reported), and `ledgerOrderedProperties`. `ParseGuardTest` (4 tests) + `SourceReaderTest` (5 tests), each damage-verified. |
| **§ 2.5 (new)** | **done** | A bare `new JavaParser()` parses at Java 11, so the generator could not read its own output (switch expressions, records, `sealed`). Every parse site — generator and `validation` package alike — now uses `SourceReader.parser()` at `JAVA_25`, and `SourceReaderTest` asserts the configured level so the fix cannot be dropped. |
| § 3.2 `column()` | **done** | Every `COLUMN` field emits a `column()` override (annotation label, else the accessor name); non-column fields still return `null`. `ViewAdapterGeneratorTest` gained the assertion, the example was regenerated through its own Maven binding and committed, and the tooling README's label table now records that this one *is* refactor-sensitive by construction. |
| § 3.1 `--diff` | **done, with the stop rule applied** | Implemented, then rewritten: the hand-written unified-diff applier was withdrawn (three silent-mis-application bugs, each found by its own tests — hunk arithmetic, empty-context spelling, trailing-newline handling) and replaced with `git apply -R` against a scratch copy, so the format is implemented by git and a diff that does not apply is refused with exit 2. `--baseline`/`--diff` are mutually exclusive. `UnifiedDiffTest` (7) + `EnumConstantOrderCliTest` (11). |
| § 4.1–§ 4.4, § 5.1–§ 5.2, § 1.1 | **not done in round 1** | Tracked in the follow-up notes with the same item ids. § 1.1 is the largest and the plan schedules it after the report net — which is now in place. |

### Round 2 (after the first implementation round)

| Section | Status | What landed |
|---|---|---|
| § 2.1 unresolved type names | **done, and it reached further than the plan said** | The two duplicated `classLiteral` implementations are replaced by `TypeLiterals`, whose contract is three-way (KNOWN / DECLARED / UNRESOLVED) rather than the boolean "is the literal `Object`". A bare name that is a type parameter **declared in the source set** (indexed from interfaces, records and methods — no hand-maintained list) resolves to `java.lang.Object` and is reported as `type_unresolved`. `UnresolvedTypeNameTest` (5 tests). The three-way form is not decoration: an `Object` id and an unresolved type parameter produce the *same* literal, so the first version reported three views of the example as unresolved. |
| § 2.1, the half the plan did not anticipate | **done** | Fixing only the class literal left the **builders** uncompilable: a type parameter is not a declarable field type either (`cannot find symbol` on the builder's field). `EntityMetadataGenerator.dropUnresolvedTypeParameters` removes such an accessor from the property list — the single list every emitter is driven by — and reports it. The compile gate is what made the difference visible. Recorded limitation: a view that declares such an accessor can only materialize at `META`, because a record or builder claiming to implement it would be abstract. |
| § 4.1 `clean` + loud failure | **done** | `scripts/mvn-jdk25.cmd` now detects a property argument that cmd.exe split before the script saw it and **exits 2 with the quoted form in the message**, rather than forwarding fragments. Measured before the change: Maven dropped the `-pl` list and built the whole 23-module reactor, so `java-watch-scp`'s pre-existing failure looked like the recorded gate failing. Verified after: unquoted ⇒ exit 2 with the message; quoted ⇒ parent plus the six modules, BUILD SUCCESS. |
| § 4.2 parser pin + classpath rule | **done** | `DependencyBoundaryTest#javaParserIsPinnedOnceInTheRootPom` asserts the tooling POM carries no `<version>` for `javaparser-core` and that the root POM single-sources it. The tooling README gained a "Reading source outside Maven" section with the `dependency:build-classpath` recipe **and** the reactor-classes-first rule, each with the failure it prevents (F-23's silent nothing, F-52's stale-jar `cannot find symbol`). |

### Round 3 — § 1.1, the last open mechanism

| Section | Status | What landed |
|---|---|---|
| § 1.1 per-member three-state cooperative codegen | **done** | `CooperativeCodegen.reconcileMembers` implements DEC-020's three states for every whole-file emitter (both builders, the record, the field enum): a member the canonical emission does not contain is carried over **verbatim by source range**, a member it does contain whose text was edited is reported as `generated_member_diverged` and replaced with the canonical body. Members are matched by shape identity (name + arity, namespaced by kind), never by full signature. `CooperativeCodegenTest` grew from 6 to 9 tests: a user helper method survives all four emitters and is a fixed point, a user field survives, and an edit to a generated method is reported, reverted, and stops being reported on the next pass — with a compile gate on the result. |
| § 1.1, the two policy calls the plan did not anticipate | **done** | Two per-file policies, both forced by a failing test rather than chosen up front. (1) A field enum's **constant list belongs to the R1 ledger**, not to this reconciliation: carrying a "user" constant through resurrected the pre-retirement constants a tombstoning pass removed, and `TombstoneLedgerTest` failed on the ordinal contract immediately. The enum now reconciles methods and nested types only. (2) A member the generator **stopped** emitting — a retired field's setter — is not the developer's, so the builders pass the RETIRED names as "no longer emitted"; without that, `TombstoneLedgerTest` failed on both the compile gate and R1.4's "a retired field gets no setter". Both are recorded in the code's javadoc as measurements, not preferences. |
| § 4.1 `clean` half | **done** | The default invocation is now `clean test` (verified: the run prints `Deleting …\\target` for each of the six modules). Only the default cleans — an explicit goal list stays the caller's request, so `run-demo.cmd`'s `-DskipTests=true package` does not become a full rebuild, and the demo was re-run green after the change. |

### Round 4 — the drift and hygiene items

| Section | Status | What landed |
|---|---|---|
| § 5.1 DEC-021 Jackson 3 spellings | **done, and now asserted** | The normative table uses the Jackson 3 names and says which two moved. `DependencyBoundaryTest#theDec021FeatureTableAndTheCodeAgree` extracts the features from DEC-021's **table rows only** and from the checker's **code only** (comments stripped) and asserts the sets are equal — the scoping is the hard part, because both the prose and the checker's javadoc deliberately quote the wrong Jackson 2 spelling, and two earlier versions of the test reported that correction as drift. |
| § 5.2 JMH `clean` trap | **done, and measured** | `hipster-entity-core/README.md` gained a *Running the benchmarks* section. Two findings from writing it: without `-am` the module resolves `hipster-entity-api` from the installed jar and fails on a bogus `does not override` (F-52 a fourth time), and the correct command produces **52 `*_jmhTest` classes plus `META-INF/BenchmarkList`** — now stated, so F-49's failure mode (no harness, green build) has a visible signature. |
| § 2.4 ordinal-space guard | **done** | `DependencyBoundaryTest#everyOrdinalConsumingEmitterIsDrivenByTheLedgerOrderedPropertyList` asserts the pass hands every emitter the ledger-ordered list and never the declaration-ordered one. Behaviour is already covered by `TombstoneLedgerTest`; what no behaviour test can reach is an emitter that does not exist yet, which is exactly F-35's shape. |
| § 4.3 `tmp/` debris | **done** | The probe copies (`tmp/disc`, `tmp/regen3`, `tmp/regen4`, `tmp/Probe.*`, `tmp/argprobe.cmd`) and `hipster-entity-example/tmp/` are deleted, after checking nothing references them; the ignore rules from round 2 stay. |
| § 4.4 watcher verification | **done** | `project-automation/README.md` (new) records the working command, why the plain reactor run is not the verification (F-48), and that these 11 tests are outside the recorded gate. **Verified 11/11, exit 0.** |

**Objective close:** every item group named in the goal is done and each has a test (or a recorded,
re-runnable verification for the two that are not test-shaped: the watcher and the JMH harness). Final
state: clean six-module gate **405 tests green**, demo exits 0 with six sections, watcher 11/11, JMH
harness generated.

### Round 5 — § 1.1 completed for the last two emitters

| Section | Status | What landed |
|---|---|---|
| § 1.1, the two emitters it missed | **done** | `ViewMapperGenerator` and `ValidationGenerator` were writing whole files without the reconciliation the other four use, so a helper added to a generated mapper or `<View>Validator` was deleted on the next pass. Both now reconcile; `ValidationGenerator` gained a divergence-sink overload so it can report as well as fix, and the pass passes its reporter. Tests: a user member inside the generated validator and inside the generated mapper survives and is a fixed point, and an edit to the validator's `validate` is reported and reverted (F-61). |
| § 1.1's test coverage | **fixed** | Two emitter test classes built a **fresh output root per call**, which made any re-emission assertion vacuous — a clean directory has no previous revision to reconcile against (F-62, F-31's lesson in a test helper). Both gained `regenerateInPlace(...)`. |
| editing safety | **recorded** | A PowerShell round-trip wrote two sources back as cp1252 bytes; `DependencyBoundaryTest`'s UTF-8 read caught it, `read` now names the offending file, and the exact byte-level repair plus the rule (use the file tools for non-ASCII sources) are in the notes as D-22. A sweep now asserts all six modules' `.java` files are valid UTF-8. |

**Final state after round 5:** clean six-module gate **core 88, tooling 264, jackson 26, test 31 = 409
tests, BUILD SUCCESS**; demo exit 0 with six sections; watcher 11/11; JMH harness generated.



