# hipster-entity MVP — incremental generation with `fs-meta`, watch mode postponed

**Status: 2026-11-07 — E0–E10 done (the MVP is declared, parse reuse is implemented, and the full gate is
green); E11 watch mode remains unscheduled.** This plan takes `hipster-entity` from "a
feature-complete generator inside this repository" to "a project outside this repository can adopt it and
work in it", and it schedules the one-shot **incremental** generation pass that makes that workflow
practical. **Watch mode is deliberately out of scope**: the existing watcher path
([`java-watch-to-fs-meta.md`](java-watch-to-fs-meta.md), W7b) stays where it is, and the incremental pass
this plan builds is the thing watch mode will later call — not a second implementation beside it.

The plan is written against the tree at `fc369a5` and the measurements recorded there. It follows the
repository's step rules: one step per commit, an ADR before code that changes a boundary, and a gate that
is run rather than asserted. It is an effort-sized plan; it is not folded into
[`unified-plan.md`](unified-plan.md), which currently counts only its own remaining step ids.

---

## 1. What "usable MVP" means here

A developer **outside this repository**, with a JDK 25 and a Maven build, can do all of the following
without reading the generator's source and without a running daemon:

1. Add `hipster-entity-api` and `hipster-entity-core` (plus `hipster-entity-jackson` for JSON) to a
   project from the local Maven repository, at the version this checkout installs.
2. Write a marker interface and one or more `@View` interfaces, using record-style accessors.
3. Run **one documented command** that generates the field enum, `META`, record, builders and tracking
   builder into `src/main/java`, and writes the metadata JSON under the module's `.jcodebuddy/`.
4. Compile the committed generated source with an ordinary `mvn compile`, then use `META.create(...)`, a
   builder, a tracking builder and the Jackson adapter in application code.
5. Edit one view, run the same command again, and have **only the affected output** rewritten; run it
   again with no edits and have it do **no work at all**.
6. Ask the same tool whether the committed output is up to date (`--check`), and get a non-zero exit code
   with a named file when it is not.
7. Get a **full pass automatically** whenever incremental generation cannot prove that its answer is
   correct. The user never has to know which mode is safe.

That is the MVP. It is deliberately a **workflow** definition, not a feature count: the repository already
has more features than this list, and the plan does not remove them. It makes the main path usable and
puts a gate around it.

**What the MVP is not:**

- not a release to Maven Central, and not an API-stability promise (the root README already says no
  compatibility promise is made before a major release);
- not watch mode, a sidecar/LSP, or an IDE plugin;
- not SQL generation (`--adapters` is still a draft/exploration), patch appliers, merge, or generated
  mappers as part of the acceptance journey — they stay available and opt-in, and incremental support is
  limited to DTO projections and mappers (see § 3.5);
- not multi-module, cross-module resolution, or a repository-wide index;
- not a second parser, a second metadata cache, or a second watcher.

## 2. Where the tree is today — measured, not assumed

| Area             | What exists                                                                                  | Evidence                                                         | Gap for this MVP                                                                      |
| ---------------- | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| Runtime          | `hipster-entity-api`, `-core`, `-jackson`, `-test` with builders, tracking and Jackson tests | module READMEs; `hipster-entity-test` suite                      | none for the main path                                                                |
| Generator        | `EntityMetadataGenerator` emits field enum, record, builders, tracking builder, `META`, locations, class index, metadata JSON, validation | `hipster-entity-tooling/README.md`; example's 15 generated files | it is a **full pass**: every `.java` is parsed and every view is emitted on every run |
| Change signal    | `ClassIndex.changedSince(previous)` computes `ADDED` / `CONTENT` / `REMOVED` / `RENAMED_TYPE` and prints one summary line | `ClassIndex`; the pass prints `[index] …` | the result is **printed, not used** to decide what to regenerate                |
| Per-file cache   | `MetadataCache` / `FileMetadata` store one file's facts under `.jcodebuddy/cache/`, keyed by content hash; a warm scan parses nothing | DEC-041; `MetadataCache`; `MetadataAnalysis` | the **entity pass does not consume it**; parse reuse is unbuilt for this generator |
| Dependency edges | `artifacts[].inputs` is reserved in DEC-029 and the tooling README as "the other half of what an incremental pass needs" | DEC-029; tooling README "Reserved, not implemented" | not written; there is no graph to say which views a changed file affects |
| `fs-meta` state  | `WatchedRegeneration` keeps a trio under `.jcodebuddy/cache/watch-fs-meta`, diffs it, ignores `TOUCHED`, and republishes after a pass | `WatchedRegeneration`; W5a in `java-watch-to-fs-meta.md` | the state half is used **only by watch mode**; there is no one-shot build state |
| CLI              | full pass only; `--version`, `--validate`, `--mapper`, `--merge`, `--run-record`             | tooling README                                                   | no `--incremental`, no `--full`, no `--check`                                         |
| Consumer wiring  | `scripts/gen.js` is repository-specific; a consumer assembles `dependency:build-classpath` + `java -cp`, or uses a phase-less `exec:java` goal that resolves from `~/.m2` | example `codebuddy.md` § 3, § 6 | no clean-consumer acceptance test; the driver project's own `gen.js` **skips** the entity pass under `--check` because there is no dry-run mode |
| Docs             | extensive user, architecture and module docs                                                 | `doc-hipster-entity/`                                            | no incremental-build page and no page that says which surface the MVP gate covers     |

**The one number to keep in view:** the example's pass was recorded at **599 ms** for 42 source files, 15
generated files and 315 locations (`codebuddy.md` § 5.6). That is fast enough that a **no-op pass** is the
first useful incremental result, and slow enough that a real project — hundreds of views — will feel a
full parse on every save. The plan therefore separates two things that are easy to conflate:

- **incremental generation** — do not re-emit views that cannot have changed (this plan's MVP);
- **incremental parsing** — do not re-parse files whose bytes are unchanged (E10, after the MVP gate).

The first is a correctness problem with a dependency graph; the second is a cache problem. Doing them in
that order keeps the failure mode ("stale generated source") out of the first deliverable.

## 3. The incremental contract

This is the decision E0 records as an ADR; it is written here first so the steps have something to gate
against.

### 3.1 The full pass is the reference

An incremental pass must produce **byte-identical output** to a full pass over the same tree, generator
and flags. Any difference is a bug, not an acceptable reformat. The test is the same one
`ExampleRegenerationTest` already uses: regenerate a copy and compare bytes.

The corollary is the safety rule: **the incremental state is a cache, never a correctness input.** A
missing, corrupt, older-format or unreadable state costs a full pass. It never changes an answer. This is
the `fs-meta` contract applied one layer up (its trio is a cache too), and it is why the fallback matrix
in § 3.5 is part of the decision rather than an implementation detail.

### 3.2 State: one `fs-meta` trio, written after the pass

> **Superseded 2026-11-07 by Design B.** The state is now a daemon-generation marker
> (`<module>/.jcodebuddy/cache/entity-build/run.json`); the daemon root is the project root, and the module
> filters to its source-root subfolder. There is no Java-written trio. The text below records the original E2
> decision before the daemon cutover.

- Location: `<module>/.jcodebuddy/cache/entity-build/` — a new derived subdirectory beside the existing
  `watch-fs-meta` state, ignored like every other `cache/` subtree (DEC-026). It never holds `.java`.
- Content: the module-relative paths and content hashes of every `.java` file under the source root,
  including generated files. The pass scans with `FsMetaScanner.scan`, loads the previous pair with
  `FsMetaPair.loadIfPresent`, diffs with `FsMetaDiff.between`, ignores `TOUCHED`, and publishes with
  `FsMetaWriter.publish` after a successful pass.
- The state is published **after** the pass so the generator's own writes are already in it. A failed
  pass does not publish, so the next run retries the same work rather than recording a half-state.
- `FsMetaScanOptions.ignoring(".jcodebuddy")` keeps the state out of its own scan. The scan deliberately
  **does not honour `.gitignore`**, because the pass reads every `.java` under the source root; a file a
  project ignores is still an input. A trio whose recorded root, format or hash contract does not match this
  pass is treated as missing, so a copied or stale `.jcodebuddy/` costs a full pass rather than a wrong
  answer.

**The transitional-writer caveat, stated honestly.** `fs-meta`'s intended writer is the Zig daemon; the
Java writer is transitional until W7b. This plan uses the Java writer for the same reason W5a did: a
one-shot build cannot assume a daemon is running, and inventing a second change tracker would violate the
repository's rule that `fs-meta` is the shared change-tracking primitive. The use is confined to one
class, the state format is the trio either way, and W7b replaces the writer without changing the
incremental pass's inputs. If the maintainer prefers, E2 may use the daemon when it is present and fall
back to the writer only when it is not; that is a decision in E0, not an implementation detail to leave
implicit.

### 3.3 Fingerprint: a generator change is a full pass

The state also records a fingerprint of the things that change output without changing a source byte:

- generator identity/version, the artifact its classes came from, and the JDK major version it runs on;
- source root, metadata output, `--java-out`, package filter;
- every flag that changes emitted code (`--adapters`, `--dto-projections`, `--mapper`, `--merge`,
  `--patch-appliers`, `--force`, validation policy);
- the validation result of the pass that published the state (`clean` / `reported` / `not run`).

A fingerprint mismatch forces a full pass. This is the same "options are part of the fingerprint" rule the
class index's `hash` header already follows.

### 3.4 Inputs: what a view was generated from

An incremental pass can skip a view only if it can name **every file whose content can change that
view's output or metadata**. The pass records this as a set of fully-qualified names, resolved through the
module's class index — never as paths, for DEC-029's reason.

For each view, the input set contains:

- the view's own source file (declared in it);
- the marker it derives from;
- every supertype and addon it inherits fields from, transitively;
- every type named by a field's declared type, including generic arguments;
- the generated artifacts that belong to the view (so a missing or hand-edited generated file is
  detected);
- mapper/merge partner views, when those opt-in features are requested.

The shape is `views[].inputs` (view-level, what the incremental pass reads) plus the reserved
`artifacts[].inputs` (per emitted artifact, what the index promises). Both are FQNs; both are sorted; both
resolve through `classes.json`. A name that cannot be resolved is reported rather than silently dropped,
and a view with an unresolved input is **always affected** (a conservative answer, not a guessed one).

### 3.5 The fallback matrix

| Situation                                                                                   | Action                                                                     |
| ------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| No content change and the fingerprint matches                                               | **No-op**: no parse, no validation, no writes                              |
| `TOUCHED` only (same bytes)                                                                 | no-op; content identity is unchanged, so the next run no-ops too           |
| Existing `.java` files modified, in place                                                   | **Incremental**                                                            |
| A file or type is added, removed or renamed                                                 | **Full pass**                                                              |
| Generator identity/version or any output-changing flag changed                              | **Full pass**                                                              |
| State missing, corrupt, unreadable, or an unknown format                                    | **Full pass**                                                              |
| A generated artifact is missing or its content hash differs from the state                  | That view is affected; if it was edited by hand, report and regenerate     |
| An incremental-unsupported emitter is enabled (`--adapters`, `--patch-appliers`, `--merge`) | **Full pass** in the MVP                                                   |
| A source file cannot be parsed                                                              | **Full pass**, with the existing parse diagnostic                          |
| `--validate=STRICT` and the state does not record a clean validation of the same tree/generator/policy | Validation runs over the whole tree; generation proceeds only if it passes |
| `--full` / `--force`                                                                        | **Full pass**                                                              |
| A changed file cannot be mapped to a type FQN                                               | **Full pass**                                                              |

Two consequences are deliberate:

- **A no-op may skip validation** only because the state says the *same* tree, generator and policy
  already passed it. An explicit `validate` subcommand always runs, and a state that records a reported or
  skipped validation does not license a no-op. This is what keeps `--validate=STRICT` from becoming a
  formality that is skipped exactly when it is cheap.
- **Incremental with `--validate=STRICT` still validates the whole tree.** Validation is a whole-tree
  question ("is this interface a view?"), so its cost is not skipped when some files changed. The no-op
  case is the one that can skip it safely.

**DTO projections and mappers are view-scoped, so they stay incremental.** `--dto-projections` emits a
`<View>Json` for a view marked `@View(dto = true)`, so it follows that view. `--mapper` emits a class
between two views, so it follows either endpoint. This matters beyond elegance: the example's own pass
uses both, so a blanket "optional emitters force full" would make the repository's canonical tree unable
to demonstrate incremental generation. The converter manifest is still complete, because every mapper
request is resolved against the full interface map and the manifest is rewritten from that resolution
whenever any mapper exists. SQL adapters, patch appliers and merge stay full-pass in the MVP because they
are either draft or family-wide.

### 3.6 What the incremental pass does

1. Load the state and the previous class index / metadata / generated artifacts.
2. Scan the source root with `fs-meta`; diff against the state.
3. Apply the fingerprint and fallback matrix. If the answer is "full", run the existing pass unchanged
   and publish a fresh state.
4. Otherwise parse the whole source set (the MVP keeps the full parse), build the interface map, and
   compute the set of affected views from the changed FQNs and the recorded inputs.
5. Emit only the affected views. For every unaffected view, reuse its previous metadata document and
   verify that each generated artifact still exists with the recorded content; a missing or changed
   artifact promotes that view to affected (or the whole pass to full, if it cannot be attributed).
6. Write the class index once, as today. Write the converter manifest whenever mapper requests exist:
   every request is resolved against the full interface map, so the manifest is complete even when only
   the affected mapper classes are written.
7. Publish the new state, including the generated files the pass wrote.
8. Report `mode`, `changed`, `affected`, `emitted`, `reused` and `fallbackReason` in the run record and on
   the pass log.

The class index must stay complete even for views that were not emitted. The pass will mark a walked file
as generated from its DEC-021 header (the existing header is the marker) rather than assuming every walked
file is hand-written, or merge the previous rows for skipped views. E3/E4 decide which; the test is that a
no-op index is byte-identical and an incremental index still resolves every document.

## 4. Steps

Each step is one commit with its own gate. Sizes follow the repository's convention: **S** ≤ half a day,
**M** 1–2 days, **L** 3+ days. Start at E0 and do not start E2 before DEC-052 is accepted; E0 is the only
step that needs a maintainer decision rather than an agent's code.

### E0 — Record the incremental contract (S)
**Who:** agent · **Depends:** none

Write the architecture decision for § 3 as **DEC-052** (the next free id after DEC-051), register it in
both decision indexes, and amend any DEC-029 / DEC-041 text that the implementation will make stale. The
record decides, at minimum:

- the full pass is the reference and incremental output must be byte-identical;
- the state is a daemon-generation marker under `.jcodebuddy/cache/entity-build/run.json` (Design B);
- the fallback matrix in § 3.5;
- the input-record shape (`views[].inputs` plus the reserved `artifacts[].inputs`);
- the emitters incremental does **not** cover (`--adapters`, `--patch-appliers`, `--merge`) force a full
  pass; DTO projections and mappers stay incremental (§ 3.5);
- watch mode is not part of this plan; the incremental pass is the API watch mode will call later.

**Gate:** the maintainer accepts DEC-052 and it is indexed; the fallback matrix is the one in this plan;
no production behaviour changed. If the decision rejects any part of § 3, the plan is amended before E1
starts.

**Done 2026-11-05.** [DEC-052](../doc-hipster-entity/architecture/decisions/DEC-052.md) is written and
indexed in both decision indexes; the state scan was tightened during implementation to ignore
`.gitignore` (the pass reads every `.java`, so the state must too). No production code was touched by E0
itself.

### E1 — A repeatable MVP acceptance harness (M)
**Who:** agent · **Depends:** E0

Add a test (or a Bun check) that builds a **clean consumer-shaped project** in a temporary directory: a
marker, one `@View(gen = GenLevel.BUILDER_ALL)` view, a field enum to be generated, and a tiny `main`
that uses `META.create`, the plain builder, the tracking builder and the Jackson adapter. The harness runs
a full pass, compiles the generated source with the existing `CompileHarness` (or an equivalent),
executes the main, and asserts the committed output is what the generator emitted.

This is the harness the later incremental steps extend; it is deliberately built before the incremental
code so "usable" has a failing test to satisfy rather than a paragraph.

**Gate:** the harness is green; it fails if generation is missing, if the generated source does not
compile, or if `META`/builder/tracking/Jackson do not work; its runtime is recorded in the plan or the
tooling README. It does **not** require `mvn install` and does not read a path under `proto/`.

**Done 2026-11-05.** `MvpAcceptanceTest` runs the full consumer-shaped flow — generate, compile (including a
hand-written `Use` that names `EntityJacksonMapper`), execute `META.create`, the plain builder and the
tracking builder through a child loader, and assert a second pass is byte-identical. It passed **1/1 in
4.9 s** in the module set and is independent of the incremental code.

### E2 — `fs-meta` build state and the no-op pass (M)
**Who:** agent · **Depends:** E1

Add `hr.hrg:fs-meta` to `hipster-entity-tooling` (explicit version; the root POM deliberately does not
manage it) and one class — name it `EntityBuildState` or similar — that owns § 3.2's load/scan/diff/publish
loop. The dependency is **compile** scope and must be present in the shaded tooling jar, because the
documented one-command path is `java -jar hipster-entity-tooling.jar …`; a `provided` scope would make
that path fail at runtime. Add `--incremental` and `--full` to the CLI. `--incremental` first loads the
state, scans, and applies the fingerprint and fallback matrix **before** validation or parsing; if the
answer is "no changes", it prints a one-line reason and returns without touching output. `--full` forces
the existing pass and publishes a fresh state.

The state records the validation outcome of the pass that published it, so § 3.5's no-op-with-`STRICT`
rule is enforceable.

**Gate:** a first `--full` pass followed by `--incremental` over an unchanged tree performs no pass and
writes nothing (state validated, `git status` clean); a same-content save is a no-op; an added, deleted or
renamed file falls back to full; a corrupt state falls back to full; a changed generator flag falls back
to full; the tooling tests and the example's regeneration test are green.

**Done 2026-11-05; state superseded 2026-11-07 by Design B.** [`EntityBuildState`](../hipster-entity/hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityBuildState.java)
implements the trio, the fingerprint sidecar, the fallback matrix and the after-success publish;
`EntityMetadataGenerator` gained `--incremental`/`--full`, the no-op decision before validation/parse, and
the run-record fields. **Design B replaced the trio with a daemon-generation `run.json` marker and a
project-root subscription filtered to the module source root; the fallback matrix and byte-identity
guarantees are unchanged.** `EntityBuildStateTest` is **16/16** at E2; the current design-B suite is
**31/31** fake-daemon and **3/3** opt-in real-daemon.

### E3 — Record what each view was generated from (M)
**Who:** agent · **Depends:** E2

Implement § 3.4: the pass records `views[].inputs` and the per-artifact `artifacts[].inputs` as sorted
FQN sets, resolved through the class index, and reports an input it cannot resolve. Cover the marker,
supertypes, addons, field types (including generic arguments), the view's own file, its generated
artifacts, and the mapper/merge partners when those flags are on. Add the reader side the incremental
pass needs and a test that a changed input file maps to the view that names it.

**Gate:** the example's metadata carries resolved inputs for every view; the class index resolves every
FQN; two passes over an unchanged tree produce byte-identical metadata; changing a marker, an addon, a
supertypes file or a field's declaring type is reported as an affected input; no document contains a path
in an input list.

**Done 2026-11-05.** `ViewInputs` writes `views[].inputs` and per-artifact `artifacts[].inputs` as sorted
FQNs; `ViewInputsMetadataTest` (4/4) proves every input resolves in `classes.json`, no path leaks in, the
marker/addon/supertype are present, and a second pass produces the same sets. The example metadata is
unchanged in test count and divergences (3 known); the tooling README's "reserved" note now says the
dependency edges are implemented and only the generator header/pointer remain reserved.

### E4 — Selective emission for modified-only changes (L)
**Who:** agent · **Depends:** E3

Implement § 3.6 steps 4–7. Parse the whole source set in the MVP, compute the affected views from the
changed FQNs and recorded inputs, emit only those views, and reuse the previous metadata and generated
files for the rest after verifying their existence and content. Keep the class index complete, including
generated files that were not emitted this pass. Report the counts in the run record.

The step is deliberately **modified-only**: additions, deletions and renames already force a full pass in
E2, so the first selective-emission implementation has one case to get right.

**Gate:** on the example, editing one accessor's field list and running `--incremental` emits only that
view's artifacts; a copy of the tree regenerated by a full pass is byte-identical to the incremental
result; every unaffected generated file is untouched; a second run is a no-op; existing example and
tooling tests pass. The test asserts **which** views were emitted, not only the bytes.

**Done 2026-11-05.** `generateIncremental` computes affected views from the changed FQNs and `views[].inputs`,
skips every emitter for the rest, and reports `mode`, `affected`, `emitted` and `reused` in the run record.
The class index now marks generated files from their DEC-021 header during the walk (and registers a
newly emitted artifact once), so a skipped view's generated rows stay complete. `IncrementalEmissionTest`
adds one accessor to the leaf `PersonDetails`, asserts the report emits exactly that view, forges a sentinel
mtime on `PersonCreateForm_` to prove it was not rewritten, and compares the incremental tree byte-for-byte
with a following full pass. The six metadata/regeneration classes, `ClassIndexTest`, `EntityBuildStateTest`
and `GeneratorRunRecordTest` are green; the full suite is not run per the AGENTS affected-test policy.

### E5 — CLI, `--check`, and the repository's default (S/M)
**Who:** agent · **Depends:** E4

Complete the command surface:

- `--incremental` (from E2) and `--full`;
- `--check`: run a **full** pass into a temporary copy and compare the committed generated `.java` (and
  the tracked `conf/` manifest) with it; write nothing; exit `0` when current, `1` when a file differs or
  is missing, `2` on usage. This is the dry-run the driver project's `gen.js --check` currently has to
  skip.
- Decide whether `--incremental` becomes the CLI default. The recommendation is: keep it explicit in
  E5, make it the default in `scripts/gen.js` after E6's fallback tests are green, and keep `--full`
  documented as the escape hatch.
- `scripts/gen.js` uses `--incremental` by default, forwards `--full`, skips the HTML render when the pass
  reports a no-op, and prints the mode/counts. The consumer-facing docs use the tooling CLI, not
  `scripts/gen.js`.

**Gate:** `bun scripts/gen.js` twice leaves the second run a no-op; `--check` exits 0 on the committed
example and 1 after a deliberate generated-file edit, with the file named; `--full` still regenerates;
`--run-record` carries `mode`, `changed`, `affected`, `emitted`, `reused` and `fallbackReason`.

**Done 2026-11-05.** `--check` copies the `.java` tree into a temp module, runs a full pass there, and
compares the generated files (DEC-021 header only) and `conf/converters.json`; `CheckCommandTest` proves a
current tree is 0, an edited view is 1, and the committed file is untouched. `scripts/gen.js` now passes
`--incremental` by default, forwards `--full`, passes `--check` through, and skips the HTML render on a
no-op. The example's first run records `full, emitted 12, reused 0`, the second `no-op` with no HTML
rewrite, and `--check` exits 0 on the committed tree.

### E6 — Prove the fallback matrix and byte-equality (M)
**Who:** agent · **Depends:** E5

Turn § 3.5 into tests, not prose. At minimum: add a file, delete a file, rename a type, edit a generated
file by hand, delete a generated artifact, corrupt the state, change the generator fingerprint, enable an
emitter that forces a full pass (`--adapters`), enable the view-scoped emitters that stay incremental
(`--dto-projections`, `--mapper`), introduce an unparseable file, and run with `--validate=STRICT` after
a clean and an unclean state. For every incremental case, compare the tree against a full pass. Add a
write counter or mtime check proving that an unaffected file is not rewritten.

**Gate:** every row of the matrix has a test; every incremental result is byte-identical to a full pass;
the tests name the fallback reason; the tooling README and DEC-052 carry the same matrix.

**Done 2026-11-05.** The state rows were already `EntityBuildStateTest` (add/delete/corrupt/unknown
format/fingerprint/`--adapters`, validation clean/reported); E6 adds the generated-file row in
`IncrementalFallbackTest` (a hand-edited `PersonDetails_` forces a full pass, names the reason and is
restored) and a mapper-endpoint incremental case in `IncrementalEmissionTest` (the mapper is re-emitted and
the tree matches a following full pass byte-for-byte). DEC-052's matrix and prose now say a missing or
hand-edited artifact is a **full pass**, matching the implementation, and the tooling README's
`--incremental` row says the same. Add/delete/rename, an unparseable file and a changed emitter flag are
the existing state/path rows; the full suite was not run per the AGENTS affected-test policy.

### E7 — The MVP surface and the incremental guide (M)
**Who:** agent · **Depends:** E6

Write the user-facing half:

- `doc-hipster-entity/user/incremental-build.md` — what incremental does, the state directory, the
  fallback rules, `--incremental`/`--full`/`--check`, and the explicit statement that **watch mode is
  later** and will call the same pass.
- An "MVP surface" page (or a section in the user index) that says what the MVP gate exercises and what
  remains opt-in/experimental. Do not delete the existing feature docs; mark their status.
- Update `hipster-entity-tooling/README.md`'s CLI table, `getting-started.md`,
  `getting-started-new-project.md`, the example's `codebuddy.md`, and the root README status paragraph.
- Update DEC-052 with whatever the implementation learned.

**Gate:** `node scripts/check-repo-links.mjs` passes; the documented commands are the ones the E5/E8
checks run; the tables are normalised with `md-fix-tables` (or the run reports that the tool was missing).

**Done 2026-11-05.** Added [`user/incremental-build.md`](../doc-hipster-entity/user/incremental-build.md)
(state, modes, fallback matrix, `--check`, postponed watch mode) and
[`user/mvp-surface.md`](../doc-hipster-entity/user/mvp-surface.md) (what the MVP proves, what stays
opt-in or draft). Updated the user index, both getting-started guides, the example's `codebuddy.md`, the
root README's status links and DEC-052's fallback/inputs wording. Link check: 299 files, all relative links
resolve; tables normalised.

### E8 — Clean-consumer acceptance (M)
**Who:** agent · **Depends:** E7

Add `scripts/check-entity-mvp.js` (Bun, per the repository's script rule). It creates a scratch project
under `.tmp/` (not `target/`, which `clean` deletes), resolves the installed `hipster-entity` artifacts
from the local repository, copies the E1 fixture, runs the documented command, compiles with Maven, runs
the main, edits one view, runs incremental, runs `--check`, and asserts the result. It must not use a
repository path as a shortcut: the point is the consumer's view.

**Gate:** the script exits 0 from a clean local install; its output records the full and incremental
durations and the number of emitted/reused views; it prints an actionable message when JDK 25, Maven or
the installed artifacts are missing; it is documented in the root README's script table. It is a recorded
check, not part of the Maven gate, because it needs an install.

**Done 2026-11-05.** Added [`scripts/check-entity-mvp.js`](../scripts/check-entity-mvp.js) and a committed
consumer fixture under `hipster-entity-tooling/src/test/resources/mvp-consumer/`. The run resolved the
installed entity artifacts from `~/.m2` (not reactor `target/classes`), built the consumer, ran the
generated `Main`, edited `PersonDetail`, ran `--incremental`, ran the main again and finished `--check`.
Measured: full **3064 ms**, incremental **4313 ms** with `emitted 1, reused 1`, check **4952 ms**, total
**20.3 s**; the last line is the timing report. The check also exposed and fixed a real consumer bug:
`EntityBuildState.stateDirectory` preferred an existing `.jcodebuddy` marker, so the first pass put the
state under `src/main/java` and the second looked for it under the module. It now matches the report path's
`.jcodebuddy` component even before the directory exists.

### E9 — Declare the MVP (S)
**Who:** agent · **Depends:** E8

Update `doc-hipster-entity/roadmap/README.md`, `hipster-entity/README.md` (or the root
`README.hipster-entity.md`), and the plan index. Record the measured full/incremental/no-op timings from
E8, the supported surface, and the fact that watch mode and Maven Central are still out of scope. Link
DEC-052 from the roadmap and the tooling README.

**Gate:** full recorded gate (`bun scripts/mvn-jdk25.js`) green; docs links pass; the roadmap and plan
agree; no code changed except the status records. The step is done when a reader can answer "is this MVP,
and what is not in it?" from the roadmap alone.

**Done 2026-11-05, with the full gate now green (2026-11-07).** The roadmap's new § 0 declares the MVP,
records the measured full/incremental/no-op/`--check` timings from E8, lists the supported surface and what is
out of scope, and links DEC-052; the root README and the plan index point at it. The full 18-module gate was
run on 2026-11-07: **BUILD SUCCESS, 7:26 min**, all modules green, with **596 tooling tests** (the evidence
for E3–E6 and E10 included).

### E10 — Parse reuse (post-MVP scale step, L)
**Who:** agent · **Depends:** E9

The MVP still parses every file. This step makes the pass parse only files whose content changed, using
the engine's existing `MetadataCache` / `FileMetadata` layer (DEC-041) and the same `fs-meta` state. The
base entries are file-local by construction; a view's extended facts are recomputed when an input file
changes. The target is the DEC-041 acceptance criteria applied to this generator: a warm pass parses
nothing, one edit parses one file, and the output is byte-identical to a cold pass.

This is scheduled after the MVP because it changes how the pass discovers views and is the step most
likely to introduce a stale-metadata bug. It is the step that makes incremental generation fast at scale;
the MVP makes it **correct**.

**Gate:** a counter shows zero parses for unchanged files and one for a one-file edit; the generated tree,
class index and metadata are byte-identical to a cold pass; the E6 fallback matrix still passes; the
example's regeneration test is green. If the base entries cannot reconstruct a view faithfully, the step
stops and records the exact fact that is missing rather than adding it to the base layer (DEC-041 D6).

**Done 2026-11-06.** `BaseLayerReconstructionProbeTest` proved 22/24 view facts come straight from
`FileMetadata`; the other two — nested-record components and a discriminator's returned literal — are
decoded from recorded spans plus the checksum-matched source. Declared type parameters, the one fact with no
base field, are now decoded from `ClassRecord`/`MemberRecord` spans by a strict `DeclarationReader`; no
`FileMetadata` field was added. `ParseReuseTest` measures **cold 42 parses**, **warm 0 parses / 0
fallbacks / 42 hits (25 stat hits)**, and a one-file `PersonDetails` edit as **1 miss / 1 parse / 0
fallbacks**. The independent cold copy produces the same generated source, metadata documents, imports
sidecar and converter manifest byte-for-byte; `classes.json` rows are field-identical apart from
`hashCalculatedAt` and `mtimes.json` for the two rows the edited pass re-hashed — wall-clock readings, the
same exception `MetadataFileIdsTest` documents, and no other difference is excused. `TypeParameterRangeDecoderTest`
is 6/6, the probe 2/2, and the tooling suite (590 tests) plus the affected core index tests are green;
`bun scripts/gen.js` twice is a clean no-op. Full gate **BUILD SUCCESS** on 2026-11-07: 18 modules, **7:26
min**, **596 tooling tests**, 0 failures.

### E11 — Watch mode (explicitly not scheduled here)
The watch path remains [`java-watch-to-fs-meta.md`](java-watch-to-fs-meta.md)'s W7b. When it lands, it
should feed a batch to the same incremental pass E2–E6 build and reuse `EntityBuildState`; it must not add
a second dependency graph or a second state directory. This plan does not edit `project-automation`, does
not start a watch loop, and does not cut over the daemon.

## 5. The MVP surface

| Surface                                                                   | MVP gate                        | Status outside the gate                                      |
| ------------------------------------------------------------------------- | ------------------------------- | ------------------------------------------------------------ |
| Marker + `@View` discovery; `GenLevel.META` / `RECORD` / `BUILDER` / `BUILDER_TRACKED` / `BUILDER_ALL` | exercised by E1 | supported                                       |
| `FieldKind.COLUMN` / `DERIVED` / `JOINED`; `@FieldSource`; addons         | exercised by E1/E3              | supported                                                    |
| Generated field enum / `META` / record / plain builder / tracking builder | exercised by E1                 | supported                                                    |
| `META.create`, array-backed read/update, `ViewChangeTracking`, `EEnumSet` | exercised by E1                 | supported                                                    |
| Jackson read + change-set serialization                                   | exercised by E1                 | supported                                                    |
| Validation (`--validate`, `validate`) and the R1 order checker            | used by the example pass and E6 | supported tooling                                            |
| **Incremental generation** (`--incremental`, `--full`, `--check`)         | E2–E8                           | **new in this plan**                                         |
| Deep tracking (`changesDeep`, `ChangePath`, `ListDelta`)                  | not in E1                       | available; DEC-024                                           |
| Polymorphic family / generated dispatcher                                 | example and tooling tests       | available; example                                           |
| DTO projections (`--dto-projections`)                                     | not in E1                       | available, opt-in; **incremental** (follows the view)        |
| Mappers (`--mapper`)                                                      | not in E1                       | available, opt-in; **incremental** (follows either endpoint) |
| Merge (`--merge`)                                                         | not in E1                       | available, opt-in; forces a full pass incrementally          |
| SQL adapters (`--adapters`)                                               | not in E1                       | **draft/exploration**, opt-in; forces a full pass            |
| Patch appliers (`--patch-appliers`)                                       | not in E1                       | available, opt-in; forces a full pass                        |
| Watch mode / sidecar / LSP                                                | out                             | W7b / `tbd.md`                                               |
| Maven Central / semver                                                    | out                             | local install is the MVP consumption path                    |

"MVP gate" is not a claim that everything outside it is unsupported; it is the list the acceptance test
actually runs. A feature that is available but not exercised here keeps its recorded status.

## 6. Decisions this plan needs

These are the questions E0 must answer; the recommendation is given so the step can move.

| #   | Question                                                                   | Recommendation                                                                                      |
| -   | --------                                                                   | --------------                                                                                      |
| 1   | Is the transitional Java `FsMetaWriter` acceptable for the one-shot state? | **No longer applies (Design B, 2026-11-07):** the state is a daemon-generation `run.json` marker; the daemon is the writer, and a missing daemon is a full pass. |
| 2   | Should `--incremental` be the CLI default?                                 | Not in E2–E5; make it `scripts/gen.js`'s default after E6, keep `--full`.                           |
| 3   | Is parse reuse part of the MVP?                                            | No — E10 is the scale step. The full pass is the reference and 599 ms is the current measured cost. |
| 4   | `views[].inputs` or only `artifacts[].inputs`?                             | Both: view-level for the incremental decision, artifact-level for the reserved per-artifact contract. |
| 5   | Should `--check` compare metadata JSON?                                    | No. Metadata is derived and ignored; compare committed `.java` and the tracked `conf/` manifest.    |
| 6   | Does incremental cover optional emitters?                                  | DTO projections and mappers yes (the example uses both); SQL adapters, patch appliers and merge force a full pass. |
| 7   | Multi-module?                                                              | Out. One module, one source root, one class index.                                                  |
| 8   | Is a Maven plugin part of the MVP?                                         | No. The shaded tooling jar is the one command; a goal-only `hipster-entity:generate` plugin is a post-MVP convenience, and it must stay phase-less if it is added (the generator is a side tool). |

## 7. Risks

- **Stale output is the failure that matters.** Mitigated by the full-pass reference, the fallback
  matrix, byte-equality tests, and the rule that state is a cache. A "clever" incremental pass that cannot
  prove an answer must fall back; the plan treats a fallback as a correct outcome, not a failure.
- **The dependency graph can be wrong in the conservative direction or the unsafe direction.** The
  first is acceptable (more work); the second is not. An unresolved input makes a view affected, and an
  unmappable changed file makes the pass full.
- **The Java `fs-meta` writer is transitional.** The plan confines it to one class and does not add a
  watcher; W7b remains the cutover.
- **Full parse remains in the MVP.** The no-op and affected-emission wins are real, but a large project
  still pays one parse per content change. E10 is where that is addressed.
- **A clean-consumer check needs a local install.** E8 is a recorded check rather than a Maven-gate test;
  the gate still covers the generator and the example. The root README must say which command installs
  what, and that `project-automation` is never part of it.
- **`--check` is a full pass by design.** It is a CI/verification command, not the edit loop; the docs
  must not present it as the fast path.

## 8. Done when

- E0–E9 are complete, each with its own dated `Done` record and gate.
- A clean consumer can follow the documented command, compile the generated source, and use the main
  runtime surface; E8's script is the evidence.
- The example's second pass is a no-op; a one-view edit emits only the affected view; both are
  byte-identical to a full pass.
- The fallback matrix is tested, not just written, and a fallback prints its reason.
- DEC-052 is accepted and indexed; the tooling README, user guide and roadmap agree with it.
- `bun scripts/mvn-jdk25.js` is green.
- Watch mode is still untouched, and `java-watch-to-fs-meta.md` W7b remains the plan for it.
