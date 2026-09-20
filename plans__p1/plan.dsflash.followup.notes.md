# `plan.dsflash.followup.notes.md` — execution notes for the follow-up round

Companion to [`plan.dsflash.followup.md`](plan.dsflash.followup.md) and
[`plan.dsflash.notes.md`](plan.dsflash.notes.md). Same conventions: `N-n` notes, `D-n` deliberate
deviations, `F-n` findings whose premise was wrong. This round executed the follow-up plan's § 1.2,
§ 2.1-§ 2.3, § 3.1, § 3.2 and § 5.3; the remaining items are listed at the end with their state.

---

## F-53 — the notes' "known remaining gap" for § 3.20 was already closed

**Plan:** § 1.2 — *"a view claimed by two markers reports its divergences once per marker"*, quoting the
follow-up plan's own status table.

**Finding.** Re-measured with a purpose-built fixture (two marker roots, and a view that derives from one
of them while carrying `@View`, so the discovery predicate's `@View` half also admits it to the other's
list): the view is processed **once**, and `nested_record_reused` is reported once. F-44's fix — hoisting
`alreadyFound` above the marker loop — plus the later refinement that an annotated view is a fallback
candidate only when no marker claims it by derivation, closed the reporting half as well as the emission
half. The note entries that said otherwise were written before one of those two changes.

**What was kept anyway.** The reporter-side dedup and the structured-entry rewrite (§ 1.2 items 1-3),
because the *property* — one entry per fact — was unasserted, and a property that is true by accident of
two other fixes is exactly what the notes' own recurring lesson (F-43) says will decay. `DivergenceReporterTest`
asserts the collapse and counts it (`duplicatesRemoved()`), and `MarkerClaimedViewReportingTest` asserts
the integration property on a real two-marker tree.

## F-54 — `DivergenceReporter.KINDS` was missing four kinds the generator actually emits

**How it was found.** § 5.3's exact-set test, on its first run.

**Finding.** The list is documented as "the recognized kinds … documentation plus tests", but it had drifted
from the code in both directions: four kinds were produced by the generator and absent from the list
(`nested_record_reused`, `adapter`-era `addon_field_collision`, `polymorphic_root_enum_preserved`, and
`source_not_parsed` once it was added), and two kinds in the list (`addon_on_non_view`,
`enum_order_shuffled`) are produced by the **validator/checker** channel rather than by a generation pass.
The class javadoc claimed the list was a complete vocabulary, which it was not.

**Resolution.** The four missing kinds are in `KINDS`. `ExampleDivergenceReportTest` now carries a
`KIND_PRODUCER` map naming, for every kind, the test that produces it, and a second map for the two
validator-channel kinds. The test fails if a kind is in neither map, and if a map names a kind the reporter
does not recognize. So the list can no longer drift silently in either direction — which is the same
"promise rather than a check" defect the plan's § 2.4 describes for emitters.

## F-55 — a bare `new JavaParser()` cannot read the generator's own output

**How it was found.** By F-34's own fix. Pinning `ParseResult.isSuccessful()` in § 2.3 made the generator
report `PersonSummaryBuilder.java`, `PersonSummaryBuilderTracking.java` and every `<View>Record.java` as
unparseable, with

```
(line 41,col 16) Switch expressions are not supported ... starting from 'JAVA_12'
(line 14,col 1)  Record Declarations are not supported ... starting from 'JAVA_14'
```

**Finding.** A bare `new JavaParser()` parses at `ParserConfiguration.LanguageLevel.POPULAR` — Java 11.
Every generated `get(int)` is a switch expression, the whole `RECORD` level is records, and the example's
payment-method family is `sealed`. So a large part of the tree was a *parse problem* at the default level,
and the five `getResult().orElse(null)` call sites swallowed it silently. This is **F-23 generalised**: F-23
found one occurrence (a JavaParser too old for `sealed` made five example files generate nothing, with no
error); this was the same class of bug in the parser's *configuration* rather than its *version*, and it had
survived every green run for the same reason — a partial parse's symptom is missing output, and nothing
asserted the report's contents.

**Resolution.** `SourceReader` owns one shared parser configured to `JAVA_25`, pinned to the root POM's
`maven.compiler.release=25`. Every parse site uses it — including the `validation` package's checker,
compactor and validator, which reach it through the now-public `SourceReader.parser()`. `SourceReaderTest`
asserts the configured level directly, so a future "tidy-up" that drops the configuration fails a test
rather than silently reintroducing invisible partial parses.

**This is the most consequential finding of the round**, because it means that before this change the
generator's *reads* of its own previous output were unreliable by default, and that is the mechanism every
cooperative-codegen guarantee (DEC-020) depends on.

## F-56 — the polymorphic-root backstop returned "not a root" for a file it could not read

**Plan:** § 2.3, "audit every parse site".

**Finding.** `FieldBoilerplateGenerator.isPolymorphicRootEnum` did
`new JavaParser().parse(source).getResult().orElse(null)` and returned `false` on `null` — *"I could not read
this, so it is not the protected root"*. The one artifact § 9/4.9 designates as hand-written and
generator-untouchable would therefore have been overwritten by the append-only pass if it ever failed to
parse. The guard was in the "fail-open" direction where the whole subsystem's contract is fail-safe (DR-7).

**Resolution.** An unreadable file now returns "leave it alone" *and* is reported as `source_not_parsed`,
distinct from `polymorphic_root_enum_preserved` ("read it, and it is the root"). `ParseGuardTest` pins both
directions, including that an unreadable enum keeps its bytes exactly.

## D-18 — the hand-written unified-diff applier was withdrawn, per the plan's own stop rule

**Plan:** § 3.1 — *"Implement, but with a stop rule. If the unified-diff applier needs more than ~80 lines,
or needs to handle anything beyond git's `-u` output and a `+++ b/<path>` header, delete the flag."* And
§ 8.4 left the scope call to the implementer.

**What happened.** It was implemented (about 240 lines) and its own tests found three separate silent
mis-application bugs, each requiring a parser change:

1. **hunk arithmetic** — the body length was taken as `oldCount + newCount`, but a context line belongs to
   *both* sides, so that over-counts by the number of context lines;
2. **empty-context spelling** — unified diff writes "a context line whose content is empty" as a
   **zero-length line** (no prefix byte), so treating empty lines as trailing-newline noise dropped an
   expected line and shifted the comparison;
3. **terminating newline** — distinguishing a file's final newline from a real empty last line, where the
   first version dropped the wrong one and shifted every line of the file.

Each was caught only by the class's own fixture, and each is in the class whose *entire purpose* is to
detect a silently mis-applied migration. That is the stop-rule condition: a second implementation whose
correctness rests on details git already implements.

**Resolution.** `UnifiedDiff` now copies the working tree to a scratch directory and runs
`git apply -R -p1` on the diff, then reads the named files back. The diff is written to a temp file rather
than piped, so a failure is re-readable. A diff that does not apply, names no file, or cannot be
reverse-applied produces an `IOException` that the CLI turns into exit 2. `UnifiedDiffTest` (7 tests) runs
against git's real output for an append, a reorder and a removal, plus two refusals, plus an assertion that
recovering a baseline does not modify the caller's tree.

**Cost recorded honestly:** the class now shells out to `git`, so this test class (like
`EnumConstantOrderCliTest`) requires a usable `git` on `PATH`. That was already true of the CLI's primary
`--baseline <ref>` form, so it adds no new environment requirement.

**One more trap found while making the negative case real.** The first "diff does not apply" test damaged
the diff's *context* and expected a refusal. Git applied it anyway, because `git apply` searches with fuzz
for a hunk whose text is still findable nearby — so the damaged diff produced a baseline and the checker
reported a violation (exit 1) instead of the expected usage error (exit 2). The test now makes the patch
unapplicable in a way fuzz cannot rescue (a hunk claiming lines past the end of the file, and a working tree
whose text the patch cannot match). Worth recording because "an obviously wrong context" is *not* a valid
negative for git's applier, and the assertion that looked like a checker bug was a test-fixture bug.

## D-19 — § 3.2 (`column()` for every `COLUMN` field) changed every generated enum

**Plan:** § 3.2 and § 8.2 item 1 — generator-side, recommended, with the consequence "every generated enum
grows per column field, and the example's diff is large" accepted.

**What happened.** Exactly that. Twelve field enums in the example changed, and the emitter's constant
shape changed with them: a constant that carries any override now has a class body, so the constant list is
no longer a single flat line — it is one constant per entry with a leading `,` separator before all but the
first. The diff is about 900 added lines across the example.

**Consequences chased, all real:**

- **`ExampleRegenerationTest` went red**, as designed, and the example was regenerated through its own
  Maven binding and committed (F-39's rule).
- The regeneration did **not** take effect on the first attempt, because `exec:java`'s `compile`-scope
  classpath includes `${project.build.outputDirectory}` — the example's own stale `target/classes` ahead of
  the freshly compiled tooling. The fix was to run the generator on an explicitly ordered classpath
  (`hipster-entity-{example,tooling,core,api}/target/classes` first). This is **F-52's trap in a third
  place**: an out-of-band run must name the current revision's classes first.
- **`AddonAndInheritanceTest`'s `constants()` helper broke**, and in an instructive way: it scanned text for
  `name(` up to the enum body's first `;`, which was correct only while the constant list was one flat line.
  The first `;` inside a constant's new class body cut the scan short and turned override methods into
  "constants" (`[id, Override, column]`). It now reads the constant names from the parsed AST. **A
  text-scraping test helper was the thing that made a correct assertion look like a generator bug**, which is
  the same lesson `DivergenceKindTest.damaged` exists for.
- **`DivergenceKindTest`'s damage patterns had to be rewritten** against the real emitted shape, with the
  vacuity guard kept: the constant-removal case now uses a regex (`removeConstant`), because pinning the
  printer's spacing in a literal would fail on a cosmetic emitter change.

## N-1 — the parser-level fix also changed what "unparseable" means for the whole `validation` package

`EnumConstantOrderChecker`, `EnumCompactionCli`, `EntityRulesValidator` and `JavaParserTool` all used a bare
`new JavaParser()`. The R1 checker parses the generated enums — which contain `forName` switch expressions —
so it was reading them at Java 11. That it appeared to work is not evidence it was correct: the checker only
needs the constant list and the header comment, both of which a partial parse can still yield. It is now on
the shared configured parser, and `EnumConstantOrderCheckerTest` (11) and `EnumConstantOrderCliTest` (11) are
green.

## N-2 — what remained after round 1

| Item | State | Note |
|---|---|---|
| § 1.1 per-member three-state cooperative codegen | **not started** | The largest item; the plan schedules it after § 5.3's net, which now exists. The audit it needs (which members each emitter owns) is described in the plan. |
| § 2.1 `classLiteral` extraction + `type_unresolved` | **not started** | `KIND_PRODUCER` already names `UnresolvedTypeNameTest` as its producer, so the classification test fails until that test exists — deliberately, so the item cannot be forgotten. |
| § 2.2 the four unresolved-name categories pinned | **not started** | Shares § 2.1's fixture. |
| § 2.4 ordinal-space source assertion | **not started** | `TombstoneLedgerTest` already covers the behaviour. |
| § 4.1 `clean` in the launcher | **not started** | Every gate in this round was run with an explicit `clean`; the launcher still does not default to it. |
| § 4.2 JavaParser pin test + README classpath rule | **partly done** | `SourceReaderTest` pins the language level. The *version* pin (`3.28.0`, declared once) and the README's `dependency:build-classpath` rule (with F-52's ordering caveat) are not written yet, and this round is the third confirmation that they are needed. |
| § 4.3 `tmp/` debris | **not started** | `tmp/` now also contains this round's gate logs and probes (`tmp/impl/`), which should be deleted or ignored with the rest. |
| § 4.4 watcher verification recorded | **not started** | |
| § 5.1 DEC-021 Jackson 3 spellings | **not started** | |
| § 5.2 JMH `clean` trap documented | **not started** | |

## Round 2 — § 2.1/§ 2.2, § 4.1, § 4.2, § 4.3

### F-57 — a type parameter is not a field type, so fixing the class literal was only half the item

**Plan:** § 2.1 — *"`classLiteral`'s default branch can emit uncompilable source (`T.class`)"*.

**Finding.** Making the class literal honest (bare type parameter ⇒ `java.lang.Object.class`) fixed the
field enum and left the rest of the materialization broken: `PersonSummaryBuilder` and
`PersonSummaryBuilderTracking` declare their mutable fields from the same type name, so they contained
`T value;` — `cannot find symbol` — and the generated record could not implement the view's
`<T> T value()` at all, so it was abstract. Measured by the compile gate in
`UnresolvedTypeNameTest`, which failed with four diagnostics after the class literal was already
correct.

**Resolution.** `EntityMetadataGenerator.dropUnresolvedTypeParameters` removes such an accessor from the
property list — the single list every emitter is driven by — and reports it as `type_unresolved` naming
the accessor. That also keeps the ordinal space consistent, since all ordinal consumers already read this
one list (F-35's fix).

**The limitation, recorded rather than worked around:** a view that declares an accessor whose type is an
unbound type parameter can only be materialized at `META`. A record or builder claiming to implement such
a view would have to implement `<T> T value()`, which it cannot, so the abstract-method error is inherent
rather than a gap in the emitters. The test asks for `META` for that reason, and says so.

### F-58 — "is the literal `java.lang.Object`" is the wrong question

**How it was found.** By § 5.3's exact-set test, immediately: adding the unresolved check made the
example report three `type_unresolved` entries — for `PersonCreateForm`, `PersonAuditable` and
`PaymentMethodAuditable`, whose id type is genuinely `java.lang.Object`.

**Finding.** An unresolved type parameter and a type actually named `Object` produce the *same* class
literal, so `isUnresolved(name) = "java.lang.Object".equals(classLiteral(name))` cannot distinguish them.
`TypeLiterals.Resolution` (KNOWN / DECLARED / UNRESOLVED) now answers the real question, and
`UnresolvedTypeNameTest#theTypeActuallyNamedObjectIsNotReportedAsUnresolved` pins it.

**This is F-43's lesson once more, in the new code**: the boolean was written to be a shortcut and it made
a correct type look like a defect. The three-way form costs three enum constants.

### D-20 — the launcher now refuses a split property instead of degrading the gate

**Plan:** § 4.1 — *"add `clean` … and make the script fail loudly when an argument it cannot faithfully
forward is present"*.

**What was measured first.** Running `scripts\mvn-jdk25.cmd hipster-entity clean test` as the previous
round did produced a **full 23-module reactor** build that failed in `java-watch-scp`. The mechanism:
cmd.exe splits `-Dname=value` at the `=` before the script runs (D-11), the script re-quotes the
fragments, and Maven then reads a malformed property assignment — which caused it to drop the `-pl`
module list entirely. So the recorded gate silently became a build of everything, and the failure looked
like a repository problem.

**Resolution.** The `:he` branch now checks each forwarded argument: a token starting with `-D` that
carries no `=` is the wreckage of a split property, and the script prints the quoted forms and exits
**2**. Two earlier attempts are recorded because each looked right and was not:

- `echo %HE_ARGS% | findstr …` let cmd redirect on the embedded `>` (findstr tried to open a file named
  `>nul`); and
- `echo %HE_ARGS%>"%PROBE%"` expanded, once the quotes were in place, to `echo "…">"…"` — the
  redirection sat *inside* a quoted string, so the probe file was never written and findstr read nothing.

A per-argument loop in cmd needs no scratch file, no redirection and no regex, which is why it is the
version that survived.

The rule is deliberately exact rather than heuristic: **every faithfully forwarded property contains an
`=`**, because cmd only splits at the `=` when the argument was not quoted at the call site. One
consequence is a real restriction — a boolean property must be written `"-DskipTests=true"` through the
shortcut — and it is stated in the script's message and in the root README rather than left to be
discovered.

Verified after: unquoted `-Dtest=X` => exit 2 with the message; quoted `"-Dtest=X"` => parent plus the six
modules and BUILD SUCCESS; `-DskipTests` => refused (documented). The file is still pure ASCII + CRLF,
asserted because an em dash slipped into a comment during this very change and cmd mis-parses such a file
— D-11's lesson, hit again.

**Call site now broken by that restriction, and the reason it is listed as remaining work:**
`scripts/run-demo.cmd` passes `-DskipTests` unquoted, so the demo currently exits 1 with the refusal
message instead of running. The fix is one line (`"-DskipTests=true"`), and it belongs with § 4.1's other
half so the demo is verified after both changes rather than twice.

**Not done here:** `clean` is not yet added to the script's default arguments. Every gate in both rounds
was run with an explicit `clean` (non-clean runs are demonstrably untrustworthy — F-6, F-47, and one
`NoClassDefFoundError` on a stale class in this round), so the remaining work is to make the default
include it, which changes what `scripts\mvn-jdk25.cmd -o -pl … package` means for callers like
`run-demo.cmd` and needs its own verification.

### N-4 — the pin test found that a file-wide version check was wrong

`DependencyBoundaryTest#javaParserIsPinnedOnceInTheRootPom` first asserted "the tooling POM contains no
`<version>3.`", which failed on a **correct** POM: the module legitimately pins
`jakarta.validation-api:3.0.2` (F-39 requires that pin so the offline build works). The assertion is now
scoped to the `javaparser-core` dependency block. Same class of mistake as F-43 — a check written against
the wrong string — caught this time by the check's own first run.

### N-5 — what remains after round 2

| Item | State |
|---|---|
| § 1.1 per-member three-state cooperative codegen | not started. The largest item; § 5.3's net and the audit it needs are in place. |
| § 4.1 `clean` in the launcher's default arguments | the loud-failure half is done (D-20). The `clean` half is not, for the reason in D-20. |
| § 4.3 deleting the `tmp/` probe copies | `/tmp/` and `hipster-entity-example/tmp/` are now ignored (verified with `git check-ignore`), which is the safety half. The probe copies themselves (`tmp/regen3`, `tmp/regen4`, `tmp/disc`, `tmp/Probe.java`) are still present and still untidy; deleting them is safe but is scratch cleanup, and both rounds' gate logs live under `tmp/impl/`. |
| § 4.4 recording the watcher's verification command | not started. |
| § 5.1 DEC-021's Jackson 2 spellings | not started. |
| § 5.2 the JMH `clean` trap | not started. |
| § 2.4 ordinal-space source assertion | not started; `TombstoneLedgerTest` still covers the behaviour. |

**Two call sites were fixed in the same change as D-20** rather than left for a later round, because the
guard would otherwise have broken them silently: `scripts/run-demo.cmd` now passes
`"-DskipTests=true"` (verified: the demo exits 0 and prints its six sections), and the root README states
the quoting rule and that unquoted `-Dtest=X` is now refused earlier than Maven would have refused it.

**Verification counts after round 2** (clean six-module run): **core 88, tooling 255, jackson 26, test
31** — 400 total, all green. Round 2 added `UnresolvedTypeNameTest` (5) and
`DependencyBoundaryTest#javaParserIsPinnedOnceInTheRootPom` (1).

### N-6 — an undo probe was run, because "the test is green" is not "the test would fail"

The objective asks for a test per item *that fails if the item is undone*, and green is not evidence of
that — F-43 is the project's own record of a guard that could never fire. So two mechanisms were
deliberately broken and the suite re-run, then restored:

| Mechanism broken | Result |
|---|---|
| `DivergenceReporter.addEntry` appends instead of collapsing duplicates | `DivergenceReporterTest.theIdenticalEntryIsCollapsedAndCounted` **failed** (1 of 7) |
| `ViewBuilderGenerator.reportMissingSetters` ignores an unreadable file | `ParseGuardTest.anUnreadableBuilderIsReportedInsteadOfEverySetterLookingMissing` **failed** with the null unit it would have dereferenced (1 of 4) |

Both were restored and the full clean gate re-run green. The remaining new tests were each observed
failing during development for their own reason, so they were not probed again; the two chosen are the
ones whose failure mode is silent (a report that merely gets longer, a read that merely returns
nothing).

---

## N-3 — the launcher had a real defect that round 1 measured, and D-20/D-21 fixed

Running the recorded gate as `scripts\mvn-jdk25.cmd hipster-entity clean test` produced a **full 23-module
reactor build** that failed in `java-watch-scp` — not the six-module gate. Root cause: cmd.exe splits an
argument at `=` and `.` before the script runs (D-11), the script re-quoted the fragments, and Maven read a
malformed property assignment — which caused it to drop the `-pl` module list entirely. The failure then
looked like a repository problem (`java-watch-scp`'s `ConfigTest.testSshConfigResolution` compares a path
with mixed separators, and `metadata-server`'s HTTP test fails — both pre-existing and outside the six
modules, per F-48).

D-20 replaced that with a loud refusal, and D-21 added `clean` to the default gate.

## Round 3 — § 1.1 (the last open mechanism) and § 4.1's `clean` half

### F-59 — a field enum's constant list is the ledger's, not the reconciliation's

**How it was found.** By `TombstoneLedgerTest`, on the first run after the enum emitter was wired to the
new member reconciliation — three tests, including the compile gate.

**Finding.** Reconciling a field enum by member shape looked obviously right and was wrong. A
tombstoning pass removes a retired accessor's constant and keeps a `@Deprecated` tombstone in its place;
the previous revision still contains the *old* constant, so "a member the canonical emission does not
contain" read it as the developer's and carried it back — resurrecting exactly the constants R1's
tombstone exists to retire, and leaving two constants for one slot. The ordinal space every other
artifact is driven by (F-35's `ledgerOrderedProperties`) would then no longer be the ledger's.

**Resolution.** `CooperativeCodegen.Reconciliation` makes the file's ownership explicit instead of
implied: a field enum reconciles **methods and nested types only**, while the builders and the record
reconcile everything. The enum's constants and its own fields (`javaType`, `META`) belong to the R1 ledger
and to the emitter respectively. `ConstantsAndFieldsPreservedInFieldEnumTest` is deliberately *not* what it
would have been — the assertion lives in `TombstoneLedgerTest`, where the conflict surfaced.

### F-60 — a member the generator *stopped* emitting is not the developer's

**How it was found.** The same test class, one run later: with constants excluded, three tests still
failed, now on `incompatible types: java.lang.Object cannot be converted to java.lang.String` and on
R1.4's "a retired field gets no setter".

**Finding.** The retired field's **setter** is a method whose name the canonical emission no longer
contains — so a reconciliation by shape alone preserved it, which is precisely what R1.4 forbids ("a
retired field gets no setter, or a caller could write a value no column accepts"), and which also failed
to compile because the preserved setter wrote to a field the record now declares as `Object`.

**Resolution.** `reconcileMembers` takes a `noLongerEmitted` set, and the two builders pass the RETIRED
property names. The general rule this establishes, and the reason it is in the code's javadoc: *shape
alone cannot distinguish "the developer added this" from "the generator removed this"* — only the caller
knows which members it used to emit, so it must say.

**Both of these were preference-shaped decisions that the tests turned into measurements.** Neither was
visible from the plan, and both would have shipped as silent data corruption (a resurrected ordinal, a
setter for a column that no longer exists) if the ledger tests had not existed.

### D-21 — § 4.1's `clean` half lands on the default invocation only

**Plan:** § 4.1 — *"Add `clean` to both branches of `scripts/mvn-jdk25.cmd`"*.

**Deviation.** `clean` is added to the **default** invocation (`:he_default`, the recorded gate) and
deliberately not to the explicit-goal branch. The plan's wording assumed both branches are "the gate",
but they are not: `scripts/run-demo.cmd` calls the shortcut with `-DskipTests=true package` and derives
its classpath from the freshly built `target/classes`, and an implicit `clean` on every invocation would
also turn a caller's incremental rebuild into a full one. The property § 4.1 is actually after — *the
recorded gate cannot be satisfied by a previous revision's class files* — is satisfied by cleaning the
default.

**Verified:** the default invocation prints `Deleting …\target` for each of the six modules and finishes
BUILD SUCCESS; the demo still exits 0 with its six sections; the unquoted/quoted property behaviour from
D-20 is unchanged.

### N-7 — what remains after round 3

| Item | State |
|---|---|
| § 4.3 deleting the `tmp/` probe copies | the ignores are in place and verified; the probe copies themselves are still there (scratch). |
| § 4.4 recording the watcher's verification command | not started. |
| § 5.1 DEC-021's Jackson 2 spellings | not started. |
| § 5.2 the JMH `clean` trap | not started. |
| § 2.4 ordinal-space source assertion | not started; `TombstoneLedgerTest` covers the behaviour, and round 3 strengthened it (F-59/F-60). |

**Verification counts after round 3** (clean six-module run): **core 88, tooling 258, jackson 26, test
31** — 403 total, all green. `CooperativeCodegenTest` grew from 6 to 9 tests.

## Round 4 — the doc-drift and hygiene items, and the objective's close

The remaining items were the ones that are easy to declare done and hard to *check*. Each was turned into
something that fails if it drifts.

### § 5.1 — DEC-021's Jackson 2 spellings, now asserted rather than corrected once

**Plan:** § 5.1 — update the normative table to the Jackson 3 names (notes D-6).

**What landed.** DEC-021 § 4 now uses the Jackson 3 spellings (`ALLOW_UNQUOTED_PROPERTY_NAMES`, features
enabled directly on the mapper builder), with a short note that the two names moved and that the
*pinned subset* is unchanged by the rename.

**What makes it stay true.** `DependencyBoundaryTest#theDec021FeatureTableAndTheCodeAgree` extracts the
feature list from DEC-021's **table rows only** and the enabled list from
`EnumConstantOrderChecker`'s **code only** (comments stripped) and asserts the two sets are equal. That
scoping is the whole difficulty: the prose around the table deliberately quotes the Jackson 2 spelling
that was wrong, and the checker's javadoc names it too — a naive whole-file scan reports the correction
itself as drift. The first two versions of this test failed on exactly that, which is why the scoping is
in the code as a comment.

### § 5.2 — the JMH `clean` trap, verified rather than copied

**Plan:** § 5.2 — record the measured invocation and its caveats where a benchmark runner will find them
(notes F-49).

**What landed.** `hipster-entity-core/README.md` gained a *Running the benchmarks* section with the
`clean test-compile -Pjmh -pl hipster-entity-core -am` recipe and the `exec:java` run command.

**Measured while writing it, and both findings went into the section:**

* without `-am`, the module resolves `hipster-entity-api` from the installed `~/.m2` jar and dies on
  `method does not override or implement a method from a supertype` for correct code — F-52's trap in a
  fourth place;
* with the correct command, `target/test-classes` contains **52 `*_jmhTest` classes** and
  `META-INF/BenchmarkList`, which is what "the harness exists" means. The README now says that, so the
  F-49 failure mode (benchmark sources compiled as ordinary classes, no harness, green build) has a
  visible signature instead of a description.

### § 2.4 — the ordinal-space assertion, which is about the *next* emitter

**Plan:** § 2.4 — a source-level guard so a newly added emitter cannot silently opt out of F-35's fix.

**What landed.** `DependencyBoundaryTest#everyOrdinalConsumingEmitterIsDrivenByTheLedgerOrderedPropertyList`
asserts that the pass hands every emitter the ledger-ordered list and never the declaration-ordered one,
plus the positive half (the builder and the draft adapter are reached with `ordinalProperties`).

**Why a structural test and not more behaviour.** `TombstoneLedgerTest` already pins the behaviour for
every emitter that exists. What no behaviour test can reach is an emitter that does not exist yet, and
that is precisely F-35's shape: the defect was a *new call site* using the other list, with every existing
assertion still green.

### § 4.3 — the probe debris is gone

Deleted, after checking nothing references them: `tmp/disc`, `tmp/regen3`, `tmp/regen4`, `tmp/Probe.java`
(+ `.class`), `tmp/argprobe.cmd`, and `hipster-entity-example/tmp/` (a stray `cp.txt`). `/tmp/` and
`hipster-entity-example/tmp/` were already ignored in round 2 (verified with `git check-ignore`), so the
hazard and the untidiness are both closed. The older `tmp/*.log` files and `tmp/impl/` (this session's
gate logs) are left: they are ignored scratch, and the logs are the evidence the notes cite.

### § 4.4 — the watcher's verification command is now written down and was re-run

**Plan:** § 4.4 — record the working command next to the module, and re-run it so the 11 tests do not rot.

**What landed.** `project-automation/README.md` (new) documents `EntityRegenerationWatcher`, the
`-Dtest=EntityRegenerationWatcherTest -Dsurefire.failIfNoSpecifiedTests=false` command, and **why** the
plain reactor run is not the verification (`metadata-server`'s HTTP test, F-48) — including the
consequence that this module's tests are not in the recorded entity gate, so nothing there would catch
their regression.

**Verified:** **11/11 green**, exit 0.

## Round 4 (continuing) — the two emitters § 1.1 missed, and an encoding trap

### F-61 — § 1.1 was applied to four whole-file emitters out of six

**How it was found.** By grepping the tooling for `Files.writeString` against `reconcileMembers` while
looking for remaining work, not by a failing test: nothing tested the two.

**Finding.** `ViewMapperGenerator` and `ValidationGenerator` each write a whole committed `.java` file
from their own entry point, and neither went through the reconciliation the other four use — so a helper
method a developer added to a generated mapper or `<View>Validator` was deleted on the next pass,
silently. That is F-25's loss and F-51's remaining mechanism, in the two files those fixes did not reach.
The plan's § 1.1 named "all four whole-file emitters" because that was the count when it was written;
the real set is six (plus the two draft adapter classes, which stay out on purpose — D-17's opt-in draft).

**Resolution.** Both now reconcile: `reconcileMembers(file, className, canonical)` plus the divergence
sink. `ValidationGenerator` gained a `DivergenceReporter` overload so the emitter can report rather than
only fix; `EntityMetadataGenerator` passes the pass's reporter.

**Tests.** `ValidationGeneratorTest#aUserMemberInsideTheGeneratedValidatorSurvives` and
`#anEditToTheGeneratedValidatorMethodIsReportedAndReverted`, and
`ViewMapperGeneratorTest#aUserMemberInsideTheGeneratedMapperSurvives`.

### F-62 — a re-emission test that builds a fresh output root can never fail

**How it was found.** The validator edit test failed with an empty divergence list, and the trace said
`fileExists=false` on the reconciliation path even though the test had just written the file.

**Finding.** `ValidationGeneratorTest.generate(viewSource)` and `ViewMapperGeneratorTest.generate(...)`
each call `Files.createTempDirectory(...)` and return a **new** tree per call. So the second
`generate(...)` in my test wrote into a different directory from the first: the "previous revision" was
never there, cooperative codegen was vacuous by construction, and the assertion could not see it because
the file it inspected belonged to the first tree. This is F-31's lesson (regeneration must be in place to
be observable) reappearing in two test helpers that were written for a different purpose.

**Resolution.** Both test classes gained `regenerateInPlace(generated, ...)`, which re-runs the pass over
the existing tree and returns that pass's divergences, plus a javadoc saying why the distinction matters.
The emitters' own preservation tests (`CooperativeCodegenTest`) already generated in place, which is why
they caught the real behaviour.

### D-22 — an editing accident corrupted two source files, and the byte-level repair is recorded

**What happened.** Two `CooperativeCodegen.java` edits and one `ValidationGenerator.java` edit were made
through a PowerShell `Get-Content -Raw` / `Set-Content` round-trip, and PowerShell wrote the text back as
**cp1252** bytes rather than UTF-8. Both files (untracked, so `git checkout` could not restore them)
ended up with non-UTF-8 sequences where they had `§` and `—`.

**How it surfaced, in two places.** `DependencyBoundaryTest.read` threw
`MalformedInputException: Input length = 1` while scanning the tooling's sources — the class already reads
files as UTF-8, so the damage was caught by an existing test rather than by a compiler that does not care.
The message named no file, which cost the time to find it; `read` now names the offender in the exception.

**The repair, stated because it is reproducible.** The damage is deterministic mojibake: UTF-8 bytes read
as cp1252 and re-encoded as UTF-8. Reversing it is exact — read the file's bytes as cp1252, encode that
string back to UTF-8, write those bytes:

```powershell
$bytes = [IO.File]::ReadAllBytes($path)
$text  = [Text.Encoding]::GetEncoding(1252).GetString($bytes)
[IO.File]::WriteAllBytes($path, (New-Object Text.UTF8Encoding($false)).GetBytes($text))
```

Both files were recovered, verified by re-reading with the write tool (which rejects invalid UTF-8) and by
a sweep asserting that **all 0** `.java` files across the six modules are valid UTF-8.

**The rule for the next editor.** Use the file tools, not a shell round-trip, for any source file that
contains non-ASCII text; or read and write with `-Encoding utf8`. This is the second encoding incident in
these notes (D-4/D-11 record a cp1252 contamination and the ASCII+CRLF rule for batch files), which is
why it is written down rather than just fixed.

**Final verification for the round:** clean six-module gate **core 88, tooling 264, jackson 26, test 31 =
409 tests, BUILD SUCCESS**; `scripts/run-demo.cmd` exit 0 with its six sections.


### Objective status

Nine item groups were named in the goal. As of this round:

| Item group | State |
|---|---|
| per-member three-state cooperative codegen | done (round 3, § 1.1) |
| divergence-report dedup | done (round 1, § 1.2) |
| unresolved type names | done (round 2, § 2.1/§ 2.2) |
| parse guards | done (round 1, § 2.3 + the § 2.5 parser-level fix) |
| `column()` default | done (round 1, § 3.2) |
| `--diff` CLI | done (round 1, § 3.1) |
| clean gate | done (round 2 loud refusal, round 3 `clean` on the default) |
| pin | done (round 2, § 4.2) |
| hygiene | done (round 4, § 4.3) |
| doc drift | done (round 4, § 5.1/§ 5.2/§ 4.4, plus § 2.4's structural guard) |

**Final verification, all in this round:** clean six-module gate **core 88, tooling 260, jackson 26, test
31 = 405 tests, BUILD SUCCESS**; `scripts/run-demo.cmd` exit 0 with its six sections; the watcher's 11
tests green; the JMH harness generated (52 `*_jmhTest` classes + `BenchmarkList`).



Running the recorded gate as `scripts\mvn-jdk25.cmd hipster-entity clean test` produced a **full 23-module
reactor build** that failed in `java-watch-scp` — not the six-module gate. Root cause: the script's `:he`
branch re-quotes each collected argument (`set "HE_ARGS=%HE_ARGS% "%~1""`), and for a property written
`-Dname=value` cmd.exe has *already* split it at the `=` before the script runs (D-11), so the fragments are
re-quoted into `"-DskipTests" "-Dmaven.test.skip" "true"`, which Maven reads as a malformed property
assignment. The remedy used throughout this round was to invoke the recorded module list directly:

```
mvn -o clean test -pl hipster-entity-api,hipster-entity-core,hipster-entity-tooling,\
hipster-entity-jackson,hipster-entity-test,hipster-entity-example
```

D-11 already documents the *quoting* workaround for `-Dtest=…` at the call site; what this round adds is
that **an unquoted or partially-quoted property silently degrades the gate to a full-reactor build**, whose
failure then looks like a repository problem (`java-watch-scp`'s `ConfigTest.testSshConfigResolution`
compares a path with mixed separators, and `metadata-server`'s HTTP test fails — both pre-existing and
outside the six modules, per F-48). § 4.1's change should therefore also make the script **fail loudly** when
an argument it cannot faithfully forward is present, rather than passing fragments through.

**Verification counts for this round** (clean six-module run): **core 88, tooling 249, jackson 26, test 31**
— 394 total, from 359 at the start of the round. The tooling count grew by 35: `DivergenceReporterTest` 7,
`ExampleDivergenceReportTest` 4, `MarkerClaimedViewReportingTest` 2, `ParseGuardTest` 4, `SourceReaderTest` 5,
`UnifiedDiffTest` 7, `EnumConstantOrderCliTest` +5, `ViewAdapterGeneratorTest` +1.
