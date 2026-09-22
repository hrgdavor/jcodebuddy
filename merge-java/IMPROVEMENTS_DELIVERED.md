# Improvements delivered

This records what was actually built from
[`IMPROVEMENT_PROPOSAL.md`](IMPROVEMENT_PROPOSAL.md). The proposal is left in place
as the reasoning and the evidence; this is the outcome.

Test count went from **431 to 573**, all green, with no test leaking state into the
repository tree.

A verification pass after the workstreams were implemented found one further defect,
described under "Found while verifying" below. It was the most serious of the set,
because the failure mode was a conflict *disappearing*.

---

## Summary by workstream

| # | Workstream | Status | What was built |
|---|---|---|---|
| WS1 | Compose conflicts instead of replacing them | **Done** | `Region`, region attribution, `MergeReport.getIndependentlyApplicable()`, `describeRegions()`, `isFullyAutomatic()` |
| WS2 | Replace line-based parsing | **Done** | Step 1: `DeclarationScanner`. Step 2: `ResolvedTypeReader` + `TypeContext`, comparing **resolved** types |
| WS3 | Verification gate | **Done** | `ResolutionVerifier`, downgrade-to-review, `ConflictResolution.Verification` |
| WS4 | History that stays trustworthy | **Done** | Schema versioning, load diagnostics, pruning, signature property tests |
| WS5 | Continuous resolution via JGit | **Done** | `MergeWorkflow`: branch and merge-base discovery, object-database reads, dry-run, working-tree writes |
| WS6 | Reviewer-facing report | **Done** | `MergeReportWriter` (JSON) + `scripts/merge-report/render.js` (Bun, self-contained HTML) |
| WS7 | Batch mode and dry run | **Done** | `MergeBatch`, `MergeBatch.Summary`, `exitCode()`, `applyTo` |
| WS8 | Type resolver gaps | **Done** | JDK supertype chains, varargs/array equivalence, generic-type canonicalisation |

---

## The measured limitations, and their status

Each row was reproduced before the work started; the tests named here now guard it.

| # | Limitation | Status | Guarded by |
|---|---|---|---|
| L1 | One unrecognised change downgraded a whole file to manual, losing the mechanical part | **Fixed** | `ConflictCompositionTest.composesRecognisedAndStructuralConflicts`, `appliesAutomaticSubsetIndependently` |
| L2 | A comment between declarations hid the conflict | **Fixed** | `DeclarationScannerTest.findsDeclarationDespiteComment` |
| L3 | A brace on the following line hid the conflict | **Fixed** | `DeclarationScannerTest.findsDeclarationWithAllmanBrace` |
| L4 | A generic type change was invisible | **Fixed** | `OverloadAddConflictResolverTest.resolvesEquivalentParameterSpellings`, `keepsOverloadsWithDifferentResolvedTypes` |
| — | Nothing checked that an automatic resolution was well formed | **Fixed** | `VerificationGateTest` (21 tests) |
| — | History had no version, validation or pruning | **Fixed** | `HistoryTrustTest` (16 tests) |

### L1 was the important one

Detection reported a single `STRUCTURAL_CHANGE` **instead of** the recognised
conflicts whenever anything unrecognised was present. "Add an import and fix a null
check" is an ordinary commit, so the common case lost its mechanical half and the
whole file became manual.

Structurally the fix is one line - the residual conflict is now emitted *alongside*
the recognised ones rather than replacing them - but it needed three supporting
pieces to be useful rather than merely different:

1. **Regions.** Without knowing which base lines each conflict covers, a caller
   cannot tell an auto resolution from a manual one applied to the same lines.
2. **`getIndependentlyApplicable()`.** The safe subset: automatic, region known, and
   disjoint from every review or manual conflict. Conservative by construction - an
   unknown region is never applicable.
3. **Honest reporting.** `summarize()` distinguishes *automatic* from *applicable*,
   so a caller cannot believe it may apply something a manual conflict overlaps.

One limitation is deliberately **kept**: a conflict caused purely by *insertion* -
both branches appended a member without dropping a base line - has no base span to
measure, so its region stays unknown and it blocks independent application for that
file. Modelling the class body to guess the insertion point would understate the
blast radius of the added code. Unknown is the safe answer, and it is documented on
`structuralRegion`.

---

## Bugs found while implementing

The new tests found more than the proposal anticipated:
- **`widens` consulted only the first chain** containing both types, so `Set` and
  `Collection` resolved to the wrong order. Now any chain that establishes the
  relation is authoritative, which is correct because every chain lists real
  supertype relationships. Two chains were also simply mis-ordered.
- **Overload detection required a *new* method name.** Adding an overload reuses the
  existing name, so the exact case the conflict type exists for was missed. Now keyed
  on `name(parameters)`.
- **The structural detector only checked for *added* members**, missing deletions -
  the more dangerous half, since a branch that deletes a method while another keeps
  calling it produces code that compiles nowhere.
- **A `REVIEW`-classified body change was masking a structural one.** Detection now
  requires at least one side to be a pure extension.
- **An in-memory auto resolution was replayed past the verifier**, so a resolution
  checked once would be applied unchecked forever. Auto resolutions are no longer
  recorded at all: the resolver reproduces them, so the gate stays on the path of
  every automatic change.
- **The in-memory history store loaded from disk**, letting a resolver whose
  decisions were never meant to persist pick up an unrelated earlier run's decisions
  - including ones recorded without passing its gate. `create()` is now
  instance-scoped.
- **The verification reason could be lost** when a resolver had already offered fix
  paths. The downgrade now appends to them.
- **Merge-base discovery via `ThreeWayMerger` treated a conflicting merge as
  failure**, so the workflow skipped exactly the files it was meant to resolve.
  Replaced with a `RevWalk`.

### Found while verifying

Re-running the original limitation inputs after the workstreams were implemented -
rather than trusting the suite - surfaced one more defect, and it was the worst kind:

- **A conflict could vanish entirely.** When both branches added a *different*
  statement to the same method body, detection reported **nothing**. Every line of
  the base still appeared somewhere in both branches, so the line-level residual
  check saw no divergence, while no detector named the case either.

  This is worse than a mis-classification: the change does not move to a review
  pile, it disappears, and the merge looks clean. Two causes had to be fixed
  together:

  1. The structural detector only considered *line-level* residual content, and
     content can change without any line disappearing. It now also compares changed
     content, and reports competing edits when neither change contains the other -
     a subset relationship is an extension, not a conflict.
  2. A recognised conflict was treated as accounting for the whole divergence, even
     when that conflict was only `REVIEW` and therefore still a human decision. A
     recognised-but-unresolved conflict must not make a file look resolved.

  Guarded by `StructuralResidualTest` (6 tests).

---

## Deliberately not done

`DESIGN_NEVER_AUTO_RESOLVED.md` is the authority on the three conflict kinds that
stay manual, and that is unchanged.

- **Only one resolver is type-aware, and on purpose.** Type context is optional and
  required only where a resolver declares it. Placing imports needs nothing but
  text — de-duplicate and keep both — and neither do comment or constant unions, or
  the two manual resolvers. `OverloadAddConflictResolver` is the sole declarer,
  because deciding whether `List<String>` and `java.util.List<java.lang.String>` are
  the same parameter list is a question about the language rather than about
  spelling. `TypeChangeConflictResolver` still uses its hardcoded JDK name table
  (`WIDENING_CHAINS`), which is a known approximation and the obvious next candidate.
- **The verification gate is parse-level, not compile-level.** It catches unbalanced
  delimiters, unterminated literals and leftover conflict markers. It is a floor, not
  a proof: it establishes well-formedness, never intent, which is exactly why it does
  not make the excluded conflict types safe to automate.
- **Structural, API and overlapping-body conflicts are still never resolved
  automatically.** Better detection and better explanations, never automation.

## WS2 step 2 — type-aware parameter comparison

The one remaining item from the first pass, now implemented.

### What it does

`OverloadAddConflictResolver` compares **resolved** parameter types instead of
parameter text, so two spellings of one signature are recognised as one:

```java
// branch 1                                // branch 2
void process(List<String> id) { }          void process(java.util.List<java.lang.String> id) { }
```

Text comparison calls those different and reports a collision that does not exist.
Resolved comparison makes them one signature and escalates correctly. Conversely
`List<String>` and `List<Integer>` remain distinct and are kept as overloads.

### Decisions taken, with their reasons

| Decision | Reason |
|---|---|
| Type context is **optional**; only declarers require it | The correction that shaped this: most conflicts are decidable from text alone, so demanding type information for them would burden every caller for nothing |
| `ConflictResolver.requiresTypeContext()` declares the need | Keeps the requirement next to the resolver that has it, and the extension pattern stays three overrides for everything else |
| A resolver set that needs types but has none **fails at construction** | A caller can fix a missing classpath immediately; discovering it conflict by conflict would look like the tool failing on ordinary input |
| Comparison is **AST-only** — the token path is deleted | One comparison path cannot disagree with itself, which was the point of the step |
| Parse failure escalates **that conflict** to `MANUAL` with the parser's message | A limitation in one method must not force a human to re-review an unrelated import merge in the same file |

### Findings that only surfaced by building it

1. **`rewrite-java` is a facade.** A version-specific implementation
   (`rewrite-java-25` now, `-21` at the original OpenRewrite pin) must be an explicit
   dependency or `JavaParser` fails at runtime with "Unable to create a Java parser
   instance".
2. **A parser implementation must run on the JDK it was built for.** At the original
   pin the parser drove the JDK's own javac through internals and failed outright on
   JDK 25 with `NoClassDefFoundError: com/sun/tools/javac/code/Type$UnknownType`,
   which forced `maven.compiler.release=21`.
3. **A `JavaParser` cannot parse three versions of one file.** It caches parsed
   sources and refuses to parse another set declaring the same fully qualified
   names, so a pooled parser fails on the second version every time. One parser per
   read is slower and correct.

   The mechanism is now confirmed from source: `fromJavaVersion()` reads the running
   JVM's `java.version` and probes hardcoded class names in descending order
   (`Java25Parser`, then `21`, `17`, `11`, `8`), taking the first that loads. The
   chosen *builder* is cached in a static field, and each call invokes its
   `builder()` to produce a fresh parser — which is why one-per-read works and why
   caching the instance did not. Upstream is also explicit that parsers are compiled
   **only for LTS versions**, so no `rewrite-java-22/23/24` will ever exist and
   non-LTS language features become parseable only from the next LTS parser. See
   [`VERSION_MAINTENANCE.md`](VERSION_MAINTENANCE.md).

A fourth, smaller finding: OpenRewrite models an empty parameter list as a single
`J.Empty` placeholder, so a no-arg method must be rendered as an empty signature
rather than as the placeholder's tree text.

### Upgrade: OpenRewrite 8.40.1 → 8.90.4, parser 21 → 25

The Java 21 constraint above was a property of the *pinned OpenRewrite version*, not
of OpenRewrite. `rewrite-java-25` was published in **8.61.3** (2025-09-03), some
twenty minor releases after 8.40.1. The parent's managed version was therefore
raised to **8.90.4**, the parser swapped to `rewrite-java-25`, and the
`maven.compiler.release=21` override deleted — this module now builds and runs at
the parent's level, confirmed by the compiled bytecode being major version 69.

Upgrade evidence:

- the module compiles against 8.90.4 with **no API change**: `fromJavaVersion`,
  `parseInputs`, `SourceFile`, `JavaType.Parameterized` and the
  `org.openrewrite.requirePrintEqualsInput` execution-context key all behave as
  before;
- the unchanged 573-test suite passes on JDK 25 + `rewrite-java-25`;
- a re-run spike re-proved the property the whole step rests on: `List<String>` and
  `java.util.List<java.lang.String>` still both resolve to
  `java.util.List<java.lang.String>`, while `List<String>` and `List<Integer>` stay
  distinct, and unparsable input still reports a failure rather than an empty
  method list.

Repo impact was contained: the parent only *manages* the version, and `merge-java`
is the sole build module that consumes OpenRewrite — the `org.openrewrite` imports
under `doc/brainstorm/rewrite-migration/` and `plans/rewrite-migration/` are
reference material with no `pom.xml`, so they are not compiled.

---

## What a caller can do now

```java
// Keep a branch up to date, resolved through JGit, dry by default.
MergeWorkflow.Result result = MergeWorkflow.open(Path.of("."))
    .upstream("origin/main")
    .path("src/main/java/com/example/Payment.java")
    .dryRun(false)
    .reportTo(Path.of(".jcodebuddy/metadata/merge-report.json"))
    .run();

System.out.println(result.describe());
System.exit(result.exitCode());

// Render the report for a reviewer.
// bun run scripts/merge-report/render.js \
//     .jcodebuddy/metadata/merge-report.json .jcodebuddy/metadata/merge-report.html
```

`result.exitCode()` is non-zero when a human is needed, so the whole thing composes
in CI.

---

## Appendix note — Phase 8 of the rewrite migration (2026-09-22)

**A delivered-work record: `VERSION_MAINTENANCE.md` carries these same facts as live instructions, and this file carries them as history.**

Both `JavaParser` mentions under "Findings that only surfaced by building it" name OpenRewrite's `org.openrewrite.java.JavaParser`, not `com.github.javaparser`: a version-specific implementation (`rewrite-java-25`) must be an explicit dependency or the parser cannot be created, and one parser cannot parse three versions of one file, so a parser is created per read. Those are the two facts [`VERSION_MAINTENANCE.md`](VERSION_MAINTENANCE.md) exists to keep current; read them here as what the work found, not as the procedure to follow.

The representation decision is [DEC-030](../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md) and the reader's guide is [`doc_knowledge/code.graph.md`](../doc_knowledge/code.graph.md).
