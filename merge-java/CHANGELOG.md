# Changelog

## [Unreleased]

### Changed

- **2026-10-03 - the review page can record a decision, and a command replays it.** Plan step 4.3: per
  resolution the page offers a fix-path choice, an editable result and Accept, then exports what was accepted
  as `decisions.json`; `DecisionRecorder` records each one into `BranchConflictStore` as a sticky replay, so
  the next merge answers that conflict with it. The page writes nothing itself - it is opened from `file://`
  - and the recorder verifies the signature the page showed against the conflict it builds, refusing a
  mismatch rather than recording a decision against the wrong conflict. `--history` is the BRANCH directory
  (`<historyRoot>/decisions/`), which is what `MergeConflictResolver` is given; the command prints the
  absolute path it used because passing the parent fails silently.
- **2026-10-03 - the review display is a jsx6 page, and the vanilla `scripts/merge-report/render.js`
  it replaced is deleted.** DEC-027's 2026-10-01 amendment sends a per-item review workflow to jsx6, so
  the page lives in `review/` with a build of its own (`src_build/`), renders the three sides beside the
  resolved code, and emits one self-contained HTML file that opens from `file://`; `MergeReportWriter`
  gained the `sides` field it needed. Tests on both sides of the split hold it:
  `review/src_build/build.test.js` (Bun, run from that directory) and `MergeReportWriterTest` (JVM).

- **`requiresTypeContext()` now means "a classpath is a hard requirement *for this
  resolver*", and `TypeChangeConflictResolver` no longer declares it.** The entry below
  said the type-change resolver refuses to run without a context; that was true for one
  revision and is superseded here — changelog entries are appended, never edited.

  The rule is in two strengths, because a blanket requirement and a blanket silence are
  both wrong:
  - **`true` — cannot degrade.** `OverloadAddConflictResolver`: comparing resolved
    parameter types has no weaker form. A resolver set containing it is refused at
    construction, the message now names both the diagnostic name and the **class** so the
    caller knows what to remove, and the remedy is to supply a context or remove it by
    hand.
  - **`false` — degrades visibly.** `TypeChangeConflictResolver`: without a context it
    still decides the JLS 5.1.2 primitive conversions and the common JDK hierarchies from
    a built-in best-effort table (`ArrayList → List → Collection → Iterable`,
    `HashMap → Map`, the wrapper types into `Number`/`Comparable`/`Object`), and every
    resolution it reaches that way carries a warning saying the declared types were not
    checked against compiled types. With a context it resolves, and a type it cannot
    resolve — a project type missing from the caller's classpath arrives as
    `JavaType.Unknown` — escalates with its own warning instead of falling back to
    matching simple names.

- **Resolutions can carry warnings.** `ConflictResolution.Builder.warning(String)` /
  `getWarnings()` / `hasWarnings()`, written to the report JSON as a `warnings` array
  beside the explanation. They describe *how* the decision was reached rather than what
  was decided, which is why they are separate: a degraded or unresolved comparison reads
  exactly as confidently as a resolved one in the explanation alone.

- The fallback table is written to the invariant the removed table broke: every chain
  contains only true relations, and a type with two unrelated supertypes gets two chains
  (`Integer → Number → Object` and `Integer → Comparable → Object`, since `Number` is not
  a `Comparable`). A partial table can only add decisions; a wrong chain removes safety.

### Added

- **`TypeChangeConflictResolver` classifies widening by resolution.** The
  hand-written `WIDENING_CHAINS` list of JDK type names is gone. A pair of
  reference types is now decided by resolving both declarations against the type
  context and asking javac whether the narrower type is assignable to the wider
  one; a pair involving a primitive keeps the JLS 5.1.2 conversion lattice, which
  is the language's own rule and the only table left. Three consequences worth
  naming:
  - a supertype the list never carried is now **answered instead of escalated** —
    `TreeSet` was listed against `AbstractSet`/`Set`/`Collection`/`Iterable`, so
    `NavigableSet` and `SortedSet`, the interfaces it actually implements, were
    absent, and a `TreeSet`/`NavigableSet` pair went to a reviewer for no reason;
  - the boxed-type entries were **wrong**, not merely coarse: the list read the
    primitive lattice across the wrapper classes, so `widens("Long", "Integer")`
    was `true` where javac rejects `Long x = anInteger`, because the two are
    siblings under `Number`. That pair escalates now, and a commit that would have
    auto-adopted the `Long` declaration no longer does;
  - the resolver now declares `requiresTypeContext()`, so a run with no type
    context is refused at construction with its name in the message rather than
    resolved by a weaker rule. A direct caller gets a manual resolution carrying
    the reason.
  The stale note at the end of the WS2 step 2 entry below — that the hardcoded
  `WIDENING_CHAINS` table and Phase 13 are what remain open — is corrected by this
  entry as far as that table is concerned; Phase 13 is still open.

### Added

- **Resolver reference documentation** (`docs/resolvers/`) — one folder per
  registered resolver with a README detailing its decision logic, and every
  example **included verbatim from test material** through the published
  `@hrg/inject-examples` package, which the root npm scripts drive with `npx`
  (`npm run inject:examples` / `npm run check:examples`). An injection
  marker is a single line that is nothing but a self-labelled link to its
  source, optionally with a `#region:name` fragment: the canonical
  per-type samples in `ConflictFixtures` (now named `//#region` blocks), the
  marked test methods that validate each behaviour, and the whole-file
  three-way fixtures. `ResolverDocsTest` makes drift a build failure: folders
  and registry must match in both directions, every include must resolve under
  `src/test/`, every rendered block must equal its source, fenced blocks in
  resolver READMEs must be include blocks (no hand-written examples), and
  every relative link must resolve. New examples enter the docs only by first
  entering the tests — e.g. the partly-overlapping method-body edit
  (`reportsOverlappingButDifferentEdits`) added for the method-body page.
- **`MergeFileTool`** — the marker-file entry point: give it a path to a file
  carrying git conflict markers and it resolves every block it safely can
  (single automatic answer, gate passed, resolution covers the whole block) and
  prepares everything that remains as a **private fixture workspace** under
  `${java.io.tmpdir}` — `.gitignore *`, the original file, whole-file three-way
  reconstructions, per-case slices and manifests, plus a bundled `AGENTS.md`
  that binds any LLM agent to the anonymize → build → re-verify loop. Only the
  anonymized fixture may ever enter this repository; `MergeFileTool.reverify`
  closes the loop by running a candidate resolver against the *original* case
  through the same verification gate. Dry by default, CI-shaped exit codes,
  diff3 bases and the git index stage-1 base honoured, never fabricated. With
  `ConflictMarkerParser` (merge + diff3 markers, loud on corrupt blocks),
  `ConflictFixtureWriter`, `RepositoryProbe` (branch + staged base through
  JGit) and `FixtureAgentInstructions`. Documented in
  `docs/CONFLICT_FILE_TOOL.md`.
- **Conflict composition** (`Region`, `MergeReport.getIndependentlyApplicable()`).
  A file that is mostly mechanical used to be reported as wholly manual, because
  any unrecognised change replaced the recognised conflicts rather than joining
  them. Detection now emits the residual structural conflict *alongside* the
  others, and each conflict carries the base lines it covers so the safe subset
  can be applied on its own. `summarize()` distinguishes *automatic* from
  *applicable* so a caller cannot apply something a manual conflict overlaps.
- **Verification gate** (`ResolutionVerifier`). Nothing is applied as automatic
  without passing a structural check. A failure downgrades the resolution to
  review and carries the reason as a fix path; a verifier can never promote. The
  scope is documented rather than implied: fragments whose delimiter balance is
  undefined are *skipped*, not failed, so the gate does not train callers to
  ignore it.
- **`DeclarationScanner`** — a formatting-tolerant declaration view. Previously a
  comment between two declarations, or an opening brace on the following line,
  made a conflict *vanish* rather than mis-classify, which is the worst failure
  mode a merge tool can have.
- **History trust**: schema version on every decision file, load diagnostics for
  skipped entries, `prune`/`pruneStale`/`oldestDecisionAt`, and property tests
  proving the conflict signature neither collides for distinct disagreements nor
  changes under reformatting.
- **`MergeWorkflow`** — the original goal, end to end, through JGit: discover the
  branch and merge base, read all three versions from the object database, apply
  the independently applicable resolutions to the working tree, and report what is
  left. Dry by default; writes only when asked; no staging or committing.
- **`MergeReportWriter` + `scripts/merge-report/render.js`** — the report split
  this repository prescribes: Java writes the facts as JSON, a Bun script renders
  one self-contained HTML file with framework-free vanilla JS, no network and no
  verified-missing links. Exercised end to end by a test that runs Bun.
- **`MergeBatch`** — many files in one pass, with aggregate counts, a dry run, the
  list of files needing attention, and `exitCode()` for CI.
- **`DESIGN_NEVER_AUTO_RESOLVED.md`** — why structural, API and overlapping-body
  conflicts are never auto-resolved, with worked examples and the enforcement map.
- **`IMPROVEMENTS_DELIVERED.md`** — what the improvement proposal produced.

### Changed

- **"base" is now defined explicitly as the last-synced upstream state**, not Git's
  merge base. The two are different reference points: a merge base is *derived* from
  the commit graph and a rebase silently changes it, whereas the last-synced point is
  *recorded* as a fact about the branch. `LastSyncMarker` records it per branch
  alongside the decision history, `MergeWorkflow` resolves against it and reports which
  reference it used, and it falls back to the common ancestor only on a branch that has
  never synced. See `docs/WHAT_IS_BASE.md`. Passing a stale base makes the upstream look
  as though it re-added everything already merged, and the branch look as though it
  deleted it - conflicts that do not exist, which is the failure this module exists to
  remove.
- **Upgraded OpenRewrite 8.40.1 → 8.90.4 and the parser `rewrite-java-21` →
  `rewrite-java-25`**, so this module builds and runs at the parent's Java 25 level
  instead of needing a release-21 override. `rewrite-java-25` was published in
  8.61.3; the Java 21 ceiling was a property of the old pin, not of OpenRewrite. No
  source change was needed - the API this module uses is unchanged - and the
  unchanged 573-test suite passes on JDK 25.
- **`MergeConflictResolver.create()` is in-memory and instance-scoped.** No branch
  was named, so there is no history it could legitimately read or write; it cannot
  be influenced by an earlier run, and one resolver's decisions cannot reach
  another's verification gate.
- **Automatic resolutions are no longer recorded in history.** They are
  deterministic, so the resolver reproduces them - and recording them would let a
  resolution skip the verification gate on every run after the first.
- **`Conflict` and `ConflictResolution` carry a `Region`**, so a report can prove
  which changes are independent.
- **Overload detection is keyed on `name(parameters)`**, not on the name, because
  adding an overload deliberately reuses the method name.
- **Structural detection checks for removed members as well as added ones.** A
  deletion is the more dangerous half: a branch that removes a method while another
  keeps calling it produces code that compiles nowhere.
- **`widens` consults every supertype chain**, not the first one mentioning both
  types, and now knows the common JDK collection and functional supertypes.
- **Merge-base discovery uses a revision walk**, not a three-way merge: "these tips
  conflict" and "I could not find the base" are different answers, and only the
  second should skip a file.

### Fixed

- **A conflict could disappear entirely.** When both branches added a *different*
  statement to the same method body, detection reported nothing: every base line
  still appeared in both branches, so the line-level residual check saw no
  divergence, and no detector named the case either. This is worse than a
  mis-classification - the change does not move to a review pile, it vanishes, and
  the merge looks clean. Two causes were fixed together: the structural detector
  now compares changed *content* as well as disappeared *lines* (reporting
  competing edits when neither change contains the other), and a recognised
  conflict only accounts for the divergence if it can actually be resolved, so a
  `REVIEW` conflict can no longer make a file look resolved. Guarded by
  `StructuralResidualTest`.
- A test no longer leaves decision files in the repository tree.
- `summarize()` reports "no conflicts detected" only when there is genuinely
  nothing to report, rather than whenever the conflict list is empty.
- **2026-10-07 — a residual `STRUCTURAL_CHANGE` no longer vetoes a block it is subsumed by.**
  Plan step 4.5. Detection emits the residual *alongside* the recognised conflicts on purpose — a
  residual that replaced them once lost a mechanical import addition — but the application rule
  needs one resolution to claim the block, so a block whose only real change was a decided
  widening came out `LEFT_MANUAL` while its own report said `[TYPE_CHANGE/AUTO] 'Widget' is a
  widening of 'Gadget'`. A subsumed residual is now dropped from the block's **decision** and stays
  in the report, so the resolutions that remain decide it. Two shapes, each on the strongest
  evidence the run has: **with a base side** (`diff3`/`zdiff3`) the residual is subsumed when
  another conflict's region covers the span of base lines neither branch kept; **without one** —
  git's default `merge` style writes no base, so every claim the residual makes is relative to a
  base this run does not have — it is subsumed only by a single `AUTO` conflict whose code accounts
  for the whole block. A residual that reaches a line no other conflict places keeps its veto, and
  so does one whose only sibling is a review or a partial answer. `MergeFileTool.residualSubsumed`
  holds the rule, and both halves are pinned by `MergeFileToolTest` — the subsumed block applies
  and the applied code compiles, the unexplained one stays marked. The expectation step 4.1 pinned
  (`classpathDecidesProjectTypes`: the block is left "regardless of the classpath") is updated,
  because changing exactly that is what this step is.
- **2026-10-07 — every resolution now records the evidence it rests on, and stronger evidence outranks a
  weaker objection.** Plan step 4.6, decision
  [DEC-045](../doc-hipster-entity/architecture/decisions/DEC-045.md). A block can be claimed by several conflicts
  at once and the application rule treated every claim alike, so a claim that read one line of text could veto an
  answer that had parsed the project's types. `AnalysisLevel` is the ordered scale that makes the two comparable —
  `TEXT_LOCAL` → `TEXT_FILE` → `STRUCTURE` → `PLATFORM_TYPES` → `PROJECT_TYPES`, ordered by what the answer was
  checked against — declared as a maximum on each resolver (`ConflictResolver.maxAnalysisLevel()`, all ten
  declare it now) and recorded on each resolution, because the same resolver is weaker without a classpath:
  `TypeChangeConflictResolver` and `OverloadAddConflictResolver` record `PLATFORM_TYPES` when no project
  classpath was supplied and `PROJECT_TYPES` when one was, and `TypeContext.hasProjectEntries()` — derived from
  the entries themselves, so every factory keeps its meaning — is the fact that separates them.
  `MergeFileTool.outranking` then applies an `AUTO` claim that covers the block and strictly outranks every other
  claim, but only where it *accounts for* what each claim was protecting: by covering that claim's region, or by
  keeping every line of both its sides. A `DEFERRED` claim is never outranked, and the level never promotes a
  `REVIEW` or `MANUAL` answer into application, so `DESIGN_NEVER_AUTO_RESOLVED.md` is untouched. One bug was
  found and fixed on the way, and it is load-bearing: the rule first read the regions the report *stamps* onto
  every conflict of a block, which makes all claims share one region and turned the protection into a formality —
  `unexplainedResidualStillVetoes` failed, the block applied, and `retries = 7` would have been dropped. It reads
  the detector's pre-stamp regions now. The report gained an `analysisLevel` key per resolution, and
  `AnalysisLevelTest` (6 tests) plus two new `MergeFileToolTest` tests hold both halves. Stated honestly: with the
  current resolver set the rule is nearly inert, because the type-level resolvers seldom share a block with a
  claim they fully account for — the payoff needs the structure-level resolver that is still to come, and step
  4.6 records that as outstanding.
- **2026-10-07 — the payoff resolver: `MEMBER_ADD`, two branches adding a distinct member in the same place.**
  Plan step 4.6 continued. Two branches appending a method beside the one the base declared produce adjacent
  insertions, which is a conflict to a line-based tool and nothing of the sort to a reader; the block came out
  `LEFT_MANUAL` with the structural residual as its *only* claim, so there was no stronger answer for the
  evidence scale to arbitrate in — which is why the scale alone changed nothing.
  `ConflictDetectionService.detectMemberAddConflicts` now recognises the shape and `MemberAddConflictResolver`
  answers `KEEP_BOTH` at `AnalysisLevel.STRUCTURE`: nothing collides, each call site binds to the member it
  names, and a text-level claim has no business outranking that. Detection requires a **base side** and no
  removal on either side, so "both branches added it" is never confused with "one branch added it and the other
  deliberately deleted it" — the distinction the import resolver draws, for the same reason — and a shared
  signature is refused, because only one member can exist. **A defect fell out of this and is fixed: `KEEP_BOTH`
  was implemented as concatenation, and both sides carry the members the base already declared, so keeping both
  repeated them.** Measured: the merged class declared `audit()` twice and javac rejected it, and the same
  `branch1Code + "\n" + branch2Code` in `OverloadAddConflictResolver` declared `process()` twice on the
  canonical overload sample. Both now build the union through `SideUnion`, which takes the longest shared prefix
  once, appends only the remainders, and verifies structurally that no member appears twice — refusing the block
  when that cannot be proved, because a "keep both" that emits code the compiler rejects is worse than a block
  left for a human. New: `MEMBER_ADD` (declared handling `AUTO`), the resolver, its
  `docs/resolvers/member-add-conflict-resolver/` page with fixture-backed examples, `SideUnion`, a
  `ConflictFixtures` sample, and an end-to-end test asserting the shared member appears **once** and the merged
  class compiles. The `docs/resolvers/README.md` index gained each resolver's declared evidence level, and
  `ResolverDocsTest` now fails if a resolver's row omits it.
- **2026-10-07 — `MEMBER_ADD` reaches the shape a real merge actually produces, and covers fields.** Plan step
  4.6, second pass, and it began by finding the first pass short: measured on a `diff3` hunk whose base section is
  present but **empty** — two adjacent additions, the ordinary case — the block came out `LEFT_MANUAL`, because
  detection required a non-blank base and a pure insertion has none. A blank base means two different things and
  only the marker parser knows which: **present and empty** says the base had no lines there, so both sides
  inserted, while **no base section at all** says the base is unknown, where an addition cannot be told from a
  deletion. `ConflictDetectionService.detect(...)` gained an overload carrying that fact and `MergeFileTool`
  passes `block.hasBase()`; the four-argument detector overload keeps the conservative reading, so a caller who
  cannot tell the two apart is never assumed to know. With that, the ordinary insertion applies — and the
  text-level `API_INCOMPATIBILITY` objection is outranked, which is the evidence scale doing what it exists for.
  **Fields are members too**: `DeclarationScanner.membersOf` sees methods and fields, with two guards against
  reading a statement as one, because "keeping both" two locals would concatenate two competing bodies and call
  it a member addition — a fragment that declares a method or a type establishes the member level by depth, and a
  bare insertion without one is read from its *modifier*, since a local variable cannot be declared `private`. A
  package-private field in a bare insertion is declined rather than guessed. Parameter spelling is deliberately
  left alone: additions whose method names match are declined here so `OverloadAddConflictResolver` answers them
  from resolved types — verified rather than assumed, because `process(List<String>)` against
  `process(java.util.List<java.lang.String>)` resolves through that resolver to one signature and is refused as a
  collision instead of kept twice. Finally, the `analysisLevel` key the report already wrote is **now rendered**
  by the review page, with the wording owned by the page (DEC-027) — built from a real report and checked, so
  `evidence: resolved platform types` and `evidence: the block's own lines` appear beside the resolutions they
  describe.

### Earlier in this cycle

- **Resolver extension pattern.** `AbstractConflictResolver` reduces a new
  resolver to three overrides (`supportedType`, `doResolve`, `describeOptions`);
  `ConflictResolvers` is the single registration point. See
  `ADDING_A_RESOLVER.md`, and `ResolverExtensionTest`, which executes the
  documented pattern so the guide cannot drift from the code.
- **`MergeUtil`** — a convenience facade over `MergeConflictResolver`. It is an
  ordinary library class, not a Maven plugin, build extension or CLI. Replaces
  the misleadingly named `MergePlugin`.
- **Per-branch decision history** with real persistence.
  `BranchConflictStore` writes one JSON file per decision under
  `.jcodebuddy/merge-history/<branch>/decisions/`.
- **Sticky replay.** A recorded decision is replayed when the incoming conflict
  has the same `ConflictSignature`; replayed results are marked
  `ResolutionKind.DEFERRED` / `ResolutionStrategy.STICKY_REPLAY`.
- **`ConflictSignature`** — formatting-insensitive conflict identity.
- **`FixPath`** gained `recommended`, `impact` and a builder.
- **Four further resolvers** so every conflict type is handled:
  `CommentAddConflictResolver`, `ConstantAddConflictResolver`,
  `MethodBodyChangeConflictResolver`, `ApiIncompatibilityConflictResolver`.
- **`ConflictType` declares a `Handling` policy** (`AUTO`, `REVIEW`, `STICKY`,
  `MANUAL`) driving both the resolution kind and whether decisions are remembered.

### Fixed (earlier in this cycle)

- **Import conflict detection reported two *different* additions.** It previously
  required both branches to add the *same* import, so the motivating case - two
  branches adding different imports to the same neighbourhood - fell through to a
  structural conflict. This was the most important fix in the module.
- `TypeChangeConflictResolver.widens(wider, narrower)` returned the opposite of its
  contract, so a narrowing type could be adopted automatically.
- The overload declaration regex let the method-name group swallow part of the
  return type, so `void process()` parsed as a method named `oid`.
- Pre-existing overloads common to both branches were counted as collisions.
- `isReplayable()` excluded `DEFERRED`, so a decision that had been replayed once
  was forgotten on the next update.
- Import resolutions emitted bare qualified names instead of valid `import ...;`
  statements, so the resolved code would not have compiled.
- Two stray `ConflictResolution$Builder.java` / `ConflictResolution$Data.java`
  files in `src/main` were removed; they were orphan duplicates of nested classes.
- A broken placeholder `recipes` package that did not compile against
  OpenRewrite 8.x was removed. It contained no working logic.

### Notes

- `JavaParser` is deliberately **not** a dependency: OpenRewrite bundles its own
  parser.
- WS2 step 2 - semantic analysis through OpenRewrite's AST rather than the
  token-based scanner - remains the main outstanding item. See
  `IMPROVEMENTS_DELIVERED.md`.

---

## Appendix note — Phase 8 of the rewrite migration (2026-09-22)

**A changelog entry is appended, never edited: this mention describes a past release and stays.**

The Notes entry "`JavaParser` is deliberately **not** a dependency: OpenRewrite bundles its own parser" records a fact about the release it was written under — no `com.github.javaparser` artifact is declared, because OpenRewrite supplies its own parser — and that fact still holds. In this module the name means OpenRewrite's `org.openrewrite.java.JavaParser`.

The representation decision is [DEC-030](../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md) and the reader's guide is [`doc_knowledge/code.graph.md`](../doc_knowledge/code.graph.md).

---

## Appendix note — the WS2 status line in the Notes above (2026-10-01)

**A changelog entry is appended, never edited, so the Notes line "WS2 step 2 …
remains the main outstanding item" stays as written — and is corrected here.**

That sentence described the state when the entry was written. It is no longer true:
[`IMPROVEMENTS_DELIVERED.md`](IMPROVEMENTS_DELIVERED.md) records **WS2 as Done** —
step 1 `DeclarationScanner` (line-based scanning removed), step 2 `ResolvedTypeReader`
+ `TypeContext`, which compare parameter types as *resolved* types, so there is one
comparison path that cannot disagree with itself. The token-based comparison is gone.

What is still open in this module is **Phase 13 — analysis display and UI helper** and
`TypeChangeConflictResolver`'s hardcoded `WIDENING_CHAINS` table; both are listed in
[`plans/unified-plan.md`](../plans/unified-plan.md).

