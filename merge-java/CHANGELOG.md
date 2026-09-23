# Changelog

## [Unreleased]

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

