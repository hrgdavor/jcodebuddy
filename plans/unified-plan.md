# Unified plan — every open item in the repository, in one ordered schedule

**Status: 2026-10-08 — live; 78 of 93 steps done.** 14 are not done (7 agent, 5 human, 1 agent + maintainer, 1 human decides), and after the maintainer's four answers of 2026-10-08 none of them is waiting on a decision this plan can make itself: they are implementations that follow the answers (6.1, 6.2, 6.4, 6.5), four human observations on a real IDE (8.1–8.4), the `jsx6`/nodditor pages (3.8, 3.9, which need the checkout), 3.11 (a decision deliberately not taken yet) and 4.19 (one named remainder). A step is ticked only from a dated record in its own body, never from a "**Done when:**" criterion.
already planned but not done. It replaces the "which plan is still open?" archaeology across
`plans/`, `webview/`, `merge-java/`, `doc-hipster-entity/` and `.kilo/plans/`: each of those documents
keeps its own history, and every genuinely open item in them appears here exactly once, as a numbered,
gated step, in the order it should be done.

Work it top to bottom, one step per commit. Do not start a step before the previous step's gate is
green. Tick the box in [§ Progress](#progress) in the same commit that finishes the step.

---

## 1. How to work this file

- **One step, one commit.** The step's *Gate* is its definition of done: the command must pass and its
  output belongs in the commit message.
- **A step that turns out to be a decision, not code, still ends in a commit** — the decision written
  into the record it belongs to (an ADR, the roadmap tracker, or the plan it came from).
- **If a step needs an architecture decision, the ADR comes first** (the decision record is written and
  registered in [`decisions/README.md`](../doc-hipster-entity/architecture/decisions/README.md) before
  the code that depends on it). `doc/AGENTS.md` is the rule.
- **Split a step if it does not fit one commit.** Renumber nothing: add `4.2a`, `4.2b` and say why.
- **Mark a step blocked rather than skipping it**, with the concrete blocker in the progress table, and
  move to the next step that does not depend on it. Phases 1–4, 6 and 7 are independent of each other;
  within a phase the steps are ordered.

### Command vocabulary

| Name         | Command                                                                | Notes                                                   |
| ------------ | ---------------------------------------------------------------------- | ------------------------------------------------------- |
| **GATE**     | `bun scripts/mvn-jdk25.js`                                             | the recorded gate: the hipster-entity module set, `clean test`, incremental compilation off. Resolves JDK 25 itself. |
| **MODULE**   | `bun scripts/mvn-jdk25.js -pl <modules> -am test`                      | any module set; add `install` instead of `test` when a consumer resolves it from the local repository |
| **LINKS**    | `node scripts/check-repo-links.mjs` and `node webview/check-links.mjs` | every relative link in every Markdown file must resolve |
| **EXAMPLES** | `npm run check:examples`                                               | the `@hrg/inject-examples` markers in `merge-java/docs/resolvers` and `materialization-levels.md` are not stale |
| **JMH**      | `bun run scripts/run-jmh.js --include "<regex>"`                       | decision-grade results need the default profile (3 forks, 6×2 s warmup, 8×2 s measurement); never record a trimmed run as evidence |

**Sizes** are rough: **S** ≤ half a day, **M** 1–2 days, **L** 3+ days. **Who** is `agent` (doable in a
checkout with no human) or `human` (needs a person, a running IDE, or an external tool).

### This run's charter — the maintainer's answers, 2026-10-03

Asked before a longer autonomous run, all ten answered, and they govern every step below until superseded:

1. **Scope: the engine + hipster-ioc line only** — 3.0t's remaining facts, 3.0u, 3.0e part two, 3.0j,
   3.0k, 3.0n, 3.0p/3.0q, 3.4–3.7, 3.9, 3.10, then the phase-9 cleanup. The UI, editor and phase-6/7 feature
   steps stay recorded as remaining.
2. **No UI work this run** — the jsx6/`nodditor` steps (3.8, 4.2, 3.11, 7.9, 7.10) are skipped because no
   `.jsx6/` checkout exists; they are not blocked, they are deferred.
3. **Steps are taken up to a deferred decision, not through it** — 4.5, 5.2, 5.4, 6.1, 6.3, 7.6 and 3.0c's
   transitivity question are the maintainer's, so work proceeds in order and stops to ask when it reaches one.
4. **The generated ioc shape may change** (already recorded at 3.0e), and `hipster-ioc-test`'s hand-written
   context may be retired once the regenerated contexts pass — 3.10's premise.
5. **`proto/business-logic` is the driver project** for end-to-end checks; nothing under `proto/` is ever
   committed, and `git add -f` is forbidden there (root `AGENTS.md`).
6. **Network is available** for build steps (Maven without `-o`, `pnpm install`, a Gradle download).
7. **JetBrains is installed** here; VS Code and Eclipse are not, so an editor-facing step is verified in
   JetBrains and **new steps may be added** for the other editors rather than blocking on them.
8. **The LLM/ACP steps get an interface and a fake proposer** (4.4, 5.3) — the wiring and the safety rule
   land deterministically; a real provider is a swap, not a rewrite.
9. **Deletions are allowed** as each step says (3.0q's sidecar attempts, 9.2's superseded plans, 9.3's
   scratch, 9.4's open-list sections), one commit naming everything that went.
10. **Autonomy: a persisted goal with a large round cap**, so the run continues across rounds and reports at
    each step boundary.

**What this charter does not change:** the rules above it — one step per commit, the gate green before the
next step starts, an ADR before the code that depends on it, and the maintainer's earlier answers recorded in
the steps they belong to.

### Build caching — an infrastructure change asked for on 2026-10-03

Not a numbered step: asked for directly, because the iteration builds had grown to 12–13 minutes each and the
time was going into **test execution**, not compilation (measured: a warm `-pl <mods> -am test` of the ioc tooling
took **12:43**, with the engine compiled already). What landed:

1. **`.mvn/extensions.xml` + `.mvn/maven-build-cache-config.xml`** enable Maven's build cache
   (`org.apache.maven.extensions:maven-build-cache-extension` 1.2.0, already in the local repository). A hit
   restores a module's compile, test **and jar** phases — the second run of a small chain skipped
   `compiler:compile`, `surefire:test` and `jar:jar` and finished in **1.07 s** against 3.27 s for the miss. The
   config is deliberately minimal and does **not** widen the input globs: a glob the extension's matcher reads
   differently than assumed would hash nothing and answer every build with the first revision it ever saw, so the
   default is kept and the behaviour is verified instead.
2. **`scripts/mvn-fast.js`** — the cached iteration build: the recorded module set, `clean package`, cache on,
   incremental compilation at Maven's default, `--tests`, `--no-clean`, `--off-cache`, `--no-parallel`.
3. **The gate uses the cache too** — the maintainer's decision of 2026-10-03, taken after the first version of this
   change had the gate switch the cache off. The reasoning is that the cache checksums a module together with all of
   its dependencies and invalidates the whole module when anything in that closure changes, so a hit *is* evidence;
   `scripts/lib/gate.js` carries the decision as `BUILD_CACHE_NOTE`, and `GateContractTest` now asserts the gate
   does **not** disable it.
4. **The cache was verified to be honest rather than argued to be — and the verification found a real gap, and then a
   mistake in how it was fixed.** A real source change produced a new checksum, a **miss**, and a deliberately failing
   test that **failed the build**: a cache may not answer with a stale SUCCESS. Then an A/B found the gap: `-Dtest=…`
   is not part of the checksum, so a run that executed one test class and saved left an entry a later **full** run hit
   — and that run reported BUILD SUCCESS with surefire skipped and **no tests executed** (cache on: zero tests,
   SUCCESS; cache off: 468 tests, one failure, FAILURE).

   **The first remedy was wrong, and the maintainer corrected it on 2026-10-03**: `mvn-fast.js` was changed to
   *disable* the cache for narrowed runs, which throws away the very mechanism the fast path exists for. The cache
   checksums a module together with all of its dependencies and invalidates the **whole module** when anything in that
   closure changes — it never reuses part of a changed module — so "disable it when unsure" is counter-productive
   rather than cautious. The fix is now `-Dmaven.build.cache.skipSave=true` for `--tests` and `--no-clean` runs: they
   still **read** the cache (a narrowed run resolves unchanged modules instantly from a previous full run) and simply
   never **write** it, which closes the false-green hole without giving up a single hit. `GateContractTest` asserts
   that the only way the fast path disables the cache is the explicit `--off-cache` flag.

   **Still open, and reported rather than papered over:** with the extension's default restore behaviour a
   cache-restored module has **no `target/classes` at all** (measured: 23/28/12/126 class files before a restore-only
   run, 0 after) while Maven still reports SUCCESS — Maven does not need them, but
   `CompileHarness.generatedSourceClasspath` reads `hipster-entity-{api,core,jackson,example}/target/classes` **by
   path**, so a run in which `hipster-entity-tooling` executes its tests while those four modules come from cache fails
   to compile its generated fixtures (`cannot find symbol: FieldDef, EntityBase, View, TypeUtils`). Two candidates,
   both for the maintainer to choose: exclude those four modules from the cache (`maven.build.cache.exclude`, so they
   always compile), or change the harness to resolve them from the restored artifacts.

**Two failures worth recording, because each was a broken build before it was a sentence:**

- **`package`, not `test`.** A cached module restored without a JAR cannot be depended on by the next module in the
  reactor — `Could not find artifact hr.hrg.jcodebuddy:jcodebuddy-builder-api:jar:1.0-SNAPSHOT`. `package` runs the
  tests anyway and produces the artifact.
- **Never populate the cache from a phase that produces nothing.** A `mvn … validate` run (mine, while checking the
  extension loaded) stored an entry per module with a near-empty output tree, and a later `package` build that hit
  such an entry compiled a sibling against a module with no classes — reported as `symbol: variable SourceReader`
  on sources that compile perfectly, which cost three runs to diagnose. The remedy is to delete
  `~/.m2/build-cache` (it is a cache; deleting it is always safe) and to populate with `package`. Both lessons are
  in the script's own header, where the next person meets them.

**Not adopted:** `mvnd`. It is installed, but the `mvn` on `PATH` *is* mvnd 1.0.0-m4 and it already warns
`Could not set the environment (java.lang.NoSuchFieldException: fs)` under JDK 25; the toolchain resolves the
recorded Apache Maven 3.9.0 instead, which is what the gate uses.

---

## 2. Rules a step must respect (they are not restated per step)

1. **Source-visible wiring** (root `AGENTS.md` § 1): generated connections are committed, navigable Java.
   No reflection-driven discovery, no `META-INF/services` registries doing the wiring.
2. **Cooperative codegen** (DEC-020/021/022/035): recognise previous output by shape, preserve user
   edits verbatim, opt back in by deleting the block. New whole-file emitters carry the two-line
   `@generated` header (DEC-035/DEC-021).
3. **Bun JavaScript only** for anything that runs a check: `.js`/`.mjs` with a `#!/usr/bin/env bun`
   header. Never `.ps1`, `.cmd`, `.bat`, `.sh`. Java build steps stay Maven/Gradle.
4. **OpenRewrite LST is the only Java source representation** (DEC-030): read via `SourceReader`, query
   via `TreeQueries`, positions from javac, and **splice text** to write — never reprint a tree.
5. **A `project-automation` module is strictly private** (§ 1.1): never installed, never depended on.
   Reusable pieces are promoted into a JCodeBuddy library instead.
6. **Generated `.java` stays under `src/main/java`**; `.jcodebuddy/` holds derived output and `conf/`
   is the only tracked subtree (DEC-026).
7. **Reports are rendered by Bun from the generator's JSON metadata**, never by a Java generator
   (DEC-027/028/029): every link verified before it is written. What the page is *built with* depends on
   what it is, per DEC-027's 2026-10-01 amendment — a page with **no UI or minimal UI** is one
   self-contained file of framework-free vanilla JavaScript, an **interactive or advanced page** is a
   **`jsx6`** page, and anything showing **relations or a diagram** uses **`jsx6`/`nodditor`** (rule 9).
8. **Generators come in two kinds, and the kind is declared.** A **file-scoped** generator reads only the
   file it is handed (`CodeContext.getFilePath()`) and its output is a function of that file: it may be
   offered to every file, one at a time, in any order, from a saved buffer with no tree around it. A
   **project-scoped** generator needs the project's *type relations* — who extends whom, who implements
   what, what is assignable — which no single file contains, so its input is the project's **metadata**
   (the engine's metadata, DEC-037 and steps 3.0a–3.0k) and never a lone `CodeContext`: it is run as its own pass and **never
   offered per-file** by a caller that believes it is asking about one file.

   Two clarifications that using this rule forced, because the earlier phrasing of it was wrong:

   - **The kinds are not two shades of one thing.** A generator that needs relations is not a file
     generator with a bigger appetite; it is a different kind of program. hipster-ioc cannot work on single
     files at all — which is why its prototype's `CodeGenerator` implementation is a category error that
     step 3.0e removes rather than re-labels;
   - **a generator's kind is what it reads; a pass's kind is what it writes.** The IoC generator returns
     text and `IocGeneration` writes the graph, so "needs another file *or* writes a tree artifact" would
     have called one generator two kinds depending on which half you looked at.

   The failure mode the rule exists for is specific: a generator that reads only its own file when it
   needed the project does not fail, it *infers an absence*. The hipster-ioc generator did exactly that in
   step 3.2 — it looked for the module interface inside the context's own compilation unit, found nothing,
   and reported no factories, producing an empty-wiring implementation rather than an error, because a file
   it never read looked like a file with nothing in it. So **metadata that cannot be resolved is reported,
   never inferred as absent**. The tree shows both kinds: the entity generator takes a source root and a
   package list and writes Java plus metadata JSON (project-scoped by construction, no per-file entry point
   to misuse), while `IocContextGenerator` wears the file-scoped SPI and reads whatever sits beside the
   context. Enforcement: steps 3.0a–3.0k (the engine, DEC-037) and 7.8.
9. **Any UI built uses `jsx6`, from a local checkout whose own `AGENTS.md` is the authority.** The
   instruction and its download directions are kept verbatim in
   [`AGENTS.md` § 2](../AGENTS.md) (the jsx6 bullet) — clone <https://github.com/hrgdavor/jsx6> into a
   temporary folder with git so it can be updated on demand to the latest, then read `AGENTS.md` from the
   jsx6 root and use jsx6 for UI. This plan does not restate jsx6's guidance; the checkout you actually
   read is what counts, and it is a **moving dependency** rather than a pinned version. The temporary
   folder is **not `target/`** — the recorded gate runs `clean test`, which would delete a checkout there
   on every run — and defaults to `<repo>/.jsx6/` (`JCODEBUDDY_JSX6_DIR` overrides). Enforcement: steps
   7.9–7.10.

   **How this meets rule 7 — a split, decided 2026-10-01 (DEC-027's amendment).** A page with **no UI or
   minimal UI** keeps the vanilla-JS rule; an **interactive or advanced page** — project-structure
   navigation, per-item review or accept workflows, state beyond a document, anything expected to be
   improved further as UI — **must** be `jsx6`; **relations and diagrams** use `jsx6`/`nodditor`. The
   no-bundler/no-`node_modules` clause does not bind the `jsx6` pages; **no network at view time** still
   does. The boundary is *further improvement*, not size: a page nobody intends to grow is the minimal
   case, and a page expected to acquire navigation or review state is an application from the start.

   **A capability either library lacks is reported, never worked around.** Minor gap → an improvement to
   be made in `jsx6` or `nodditor`, and the finding is worth more there than a workaround here; critical
   gap → the library cannot serve that specific output, so it is a decision about an additional library,
   taken with the evidence and recorded as its own decision rather than assumed. That is step 7.10's
   deliverable, not a note.
10. **The engine parses and analyses; a consumer projects.** `jcodebuddy-core` owns parsing and analysis in
   one place — the one source representation (DEC-030), the rich general model, the indexes with their
   relations, search and freshness — so that **no consumer duplicates the effort**. A consumer owns its
   **domain shape**: hipster-entity defines and serialises its own entity and relation data (the JSON a JS UI
   or a visual entity/relations page reads), because that is a *projection* of the engine's facts. What stays
   forbidden is a consumer parsing Java itself, running its own watch loop, or keeping its own type index
   because the engine's answer was inconvenient — and a fact a consumer needs three times is a signal to widen
   the engine's model rather than to derive it locally. The test for any change: **does it derive a fact from
   source (engine), or project a fact the engine already answers (consumer)?** DEC-037's "what a consumer
   owns" section is the decision this rule restates. Enforcement: steps 3.0f-3, 3.0g–3.0j, and every
   hipster-entity step that reads the engine.

---

## 3. What this plan schedules

| Document                                                                          | What it contributed here          | Its own state after this plan                                                                      |
| --------------------------------------------------------------------------------- | --------------------------------- | -------------------------------------------------------------------------------------------------- |
| [`plans/enumset-overlap-jmh-plan.md`](enumset-overlap-jmh-plan.md)                | step 0.1 — land the finished work | **done** — closed into step 0.1, which is `[x]`                                                    |
| the stale-document pass of 2026-10-01                                             | step 0.2 — commit it              | **done** — closed into step 0.2, which is `[x]`                                                    |
| [`doc-hipster-entity/architecture/decisions/DEC-021.md`](../doc-hipster-entity/architecture/decisions/DEC-021.md) § 6 | step 1.1 — `enabled: false` | **done** — closed into step 1.1, which is `[x]`                      |
| [`doc-hipster-entity/architecture/decisions/DEC-W008.md`](../doc/architecture/decisions-watch/DEC-W008.md), the `.kilo` metadata-server plan | steps 1.2–1.4 | **done** — steps 1.2–1.4 and 7.7 are all `[x]`; 7.7 (the manual-mode CLI) was the last item this row left open |
| the `.kilo` metadata-arena plan                                                   | steps 2.1–2.2                     | **done** — closed into steps 2.1–2.2, both `[x]`                                                   |
| the `.kilo` hipster-ioc-integration plan, [`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md), and [DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md) | steps 3.0a–3.0k (the one metadata engine in `jcodebuddy-core`, then moving this generator onto it **as one consumer**); steps 3.1–3.3 as a **prototype**; steps 3.4–3.11 are `[TBD]` until the shape is decided | **closed in step 3.11** — 3.0a–3.0t and 3.10 are `[x]`; 3.11 is the one item this row still owns (it is the maintainer’s), and 3.4–3.9 are `[TBD]` because they wait on other steps rather than on this plan |
| [`merge-java/IMPLEMENTATION_PLAN.md`](../merge-java/IMPLEMENTATION_PLAN.md)       | steps 4.1–4.4                     | **done** — closed into steps 4.1–4.4, all four `[x]`                                               |
| the JetBrains merge/diff port, asked for 2026-10-07 — the instruction and the research behind it are in [`merge-java/docs/JETBRAINS_PORT.md`](../merge-java/docs/JETBRAINS_PORT.md) | steps 4.7–4.13 (the `#### 4B` block after 4.5): the classification of every upstream resolution step, then the **SAFE** half — sources and licence, the text tier, the merge tier, whitespace policy, the intra-line evidence level, the conflict shape, upstream's test vectors | **closed in step 4.13** — 4.7–4.12 are `[x]`; 4.13 carries one named remainder (a reviewer accepting a block in one action) and the port’s vectors are the parity gate’s source of truth |
| the general suggestion channel, asked for 2026-10-07 — designed in [`merge-java/docs/SUGGESTIONS.md`](../merge-java/docs/SUGGESTIONS.md) | steps 4.14–4.17: a first-class `SUGGESTION` resolution that carries a concrete answer and is never applied on its own; the answers the module **already computes but hides behind a refusal**; the page's Accept/Edit/Reject; rejection memory; and the **SUGGESTION** half of the JetBrains port as its first producer | **done** — closed into steps 4.14–4.17, all `[x]` (the channel, the report, the refusal memory and the proposer as one provenance) |
| [`webview/PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md)               | steps 5.1–5.3                     | **closed in steps 5.2–5.3** — 5.1 is `[x]` (the headless parity test, extended and asserted to cover every verb); 5.2 is the maintainer’s decision and 5.3 is a person’s |
| [`webview/PLAN-eclipse-host.md`](../webview/PLAN-eclipse-host.md)                 | steps 5.4, 8.2                    | **closed in steps 5.4 and 8.2** — both are `[ ]`: 5.4 is the maintainer’s, 8.2 is a human observation on a real IDE |
| [`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) | steps 6.1–6.5                     | **closed in steps 6.1–6.5** — all `[ ]`: 6.1 and 6.3 are the maintainer’s decisions, 6.2/6.4/6.5 follow them in the phase, so none of them is unaccounted for |
| [`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md), [`jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md), [`todo.hipster-entity.md`](../todo.hipster-entity.md), [`todo.java_watch2.md`](../todo.java_watch2.md), [`doc-hipster-entity/doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md), [`webview/webview-jetbrains/plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) | steps 7.1–7.6, 8.1, 8.3 | **done for steps 7.1–7.10** (all `[x]`, and 7.7–7.10 are newer than this row’s own range), with **8.1 and 8.3 closed in themselves** (human observations on a real IDE) and **the two items nothing else scheduled decided in step 9.1b** — the sidecar’s "add more tools" (struck: the sidecar owns the host half, generators live in the agent’s tool registry) and the agent’s "lightweight hooks for IntelliJ and VS Code" (struck: delivered by `webview-jetbrains` and `webview-vscode`), the only two step 9.1’s coverage check found unaccounted for |
| [`plans/rewrite-migration/`](rewrite-migration/README.md)                         | nothing — it is **complete**      | **done** — every step it contributed is `[x]`, and step 9.2 (archive) is `[x]` too, so the plan is a historical record rather than open work |

---

## 4. Phase 0 — land what is already finished

### 0.1 — Commit the EEnumSet overlap JMH delivery
**Who:** agent · **Size:** S

The work is done and uncommitted: `plans/enumset-overlap-jmh-plan.md` says "implemented (2026-10-01)",
the parity gate is green, and the decision-grade numbers are written into
[`enumset-implementation-and-jmh.md`](../doc-hipster-entity/architecture/enumset-implementation-and-jmh.md).
Everything it names is on disk but nothing is committed: the plan, the workload summary, the fixtures,
the benchmark, the parity test, and the `scripts/run-jmh.js` extension are all untracked or modified.

**Do:** review the change set (`git status`), then commit it as one change — the benchmark, the shared
fixtures, the parity test, the runner extension, the plan, the new workload summary and the DEC-014 /
`enumset-implementation-and-jmh.md` updates, with the recorded numbers in the message.

**Gate:** `bun scripts/mvn-jdk25.js` green **and** `LINKS` green. Re-running the benchmark is optional
(the evidence is already recorded) — if you do re-run it, use the default profile and say so.

**Done when:** `git status` is clean for these paths and the plan's status line matches the commit.

### 0.2 — Commit the stale-document corrections
**Who:** agent · **Size:** S

The 2026-10-01 pass corrected status lines that contradicted the tree. It touched:
[`merge-java/IMPLEMENTATION_PLAN.md`](../merge-java/IMPLEMENTATION_PLAN.md) (the WS2 banner was stale),
[`merge-java/CHANGELOG.md`](../merge-java/CHANGELOG.md) (an appended correction, the entry itself
untouched),
[`webview/PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md) (the status line, and a
`webview/kit/kit/…` path typo),
[`webview/PLAN-eclipse-host.md`](../webview/PLAN-eclipse-host.md) (Phase 5/Q2/Q8 open),
[`doc-hipster-entity/doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md) (status
banner; items 12–13 open),
[`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md) (backlog, nothing implemented),
[`jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md) (the two Phase 4 boxes),
[`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md) (two Future Refinement boxes),
[`webview/webview-jetbrains/plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) (§ 8
unanswered),
[`todo.hipster-entity.md`](../todo.hipster-entity.md) and [`todo.java_watch2.md`](../todo.java_watch2.md)
(each item reconciled against the code: Wyhash and `text_extensions` are done, the LSP incremental item
is done),
[`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) (a pointer to this
plan), and
[`decisions/README.md`](../doc-hipster-entity/architecture/decisions/README.md) — **DEC-W006 – DEC-W009
were missing from the index entirely** and are now rows in the watch-subsystem table.

**Do:** commit all of it, with this plan, as one documentation change. No code changed.

**Gate:** `LINKS` green **and** `EXAMPLES` green.

**Done when:** committed; the corrections' commit message lists the documents and the one-line reason
each was wrong.

---

## 5. Phase 1 — close the gaps that already have a decision

### 1.1 — Honour `enabled: false` (the whole-file freeze, DEC-018 / DEC-021 § 6)
**Who:** agent · **Size:** M

[DEC-021 § 6](../doc-hipster-entity/architecture/decisions/DEC-021.md) recorded the gap in its own text:
the header **format** is implemented and emitted by every generator, `entityFieldEnum` and
`allowReorder` were decoded and honoured, and **`enabled` was not honoured — no code path read it**, so
`enabled: false` did not freeze a file and the next pass regenerated it.

**Done 2026-10-01.** What changed:

- `EnumConstantOrderChecker.HeaderConfig` gained an `enabled` component (default **true**), decoded from
  the header's JSON5. **A malformed header does not freeze the file** — the two fail-safe directions are
  deliberately opposite (still `marked`, so R1 keeps protecting the enum; still `enabled`, so one typo
  cannot silently stop generation).
- `CooperativeCodegen.isFrozen(Path)` / `isFrozen(J.CompilationUnit)` is the **single reader** of the
  knob, and `reconcileMembers` returns the previous text unchanged (plus one DEC-022 line,
  `kind=file_frozen`) before it consults `force`. **The freeze outranks `--force`**: force means "every
  member in this file belongs to the generator", and a file cannot be both; the way out is the freeze's
  own opt-out (`enabled:true`, or delete the line).
- **Two of the writers did not go through the reconciler, which this step's own text had assumed.**
  `ViewAdapterGenerator` writes `<View>RowAdapter.java` / `<View>Binder.java` directly and emits the
  DEC-021 header, so it now checks `isFrozen` (and skips the write, reporting `file_frozen`), taking the
  reporter through a new five-argument overload that the pass supplies. `ViewInterfaceGenerator` writes
  the developer's **own** view interface — no generated header, so `enabled` has nothing to freeze there —
  and is correctly untouched.
- `DivergenceReporter.KINDS` gained `file_frozen`, with its producer named in
  `ExampleDivergenceReportTest.KIND_PRODUCER` (that test fails on a kind nothing produces).
- New `CooperativeCodegenEnabledTest`: byte-for-byte freeze, the enabled path still regenerating *and*
  still preserving the developer's member, force not overriding the freeze while still regenerating an
  enabled file, the malformed-header direction, `isFrozen` for the four file states a pass meets, and an
  end-to-end pass over a tree with one frozen generated file (asserted byte-identical, reported, and
  still compiled).
- **DEC-021 was corrected rather than merely annotated**, because two of its claims were false:
  § 6's "aspirational" note is replaced by an implemented-status note; § 1/§ 2 and the worked examples now
  show DEC-035's `@generated file` first line instead of the `{@link …}` form it was written with; and the
  `blockMarker: "strict"` / `maxMethods` examples are removed with an explicit statement of the three keys
  this project actually reads (`enabled`, `entityFieldEnum`, `allowReorder`).
- `doc-hipster-entity/roadmap/README.md`'s DEC-018 row no longer says the freeze is "not yet honoured".

**Gate:** `GATE` green (8 modules), with `CooperativeCodegenEnabledTest` (7 tests) in the run.

**Done when:** ✅ done — see the commit for this step.

### 1.2 — Implement `MetadataProvider.parse` (the no-cache path, DEC-W008)
**Who:** agent · **Size:** M

**Done 2026-10-01.** DEC-W008's P0 half is implemented, and four points of the decision's text were
corrected in place (the amendment block at the top of DEC-W008 records all four):

- `MetadataProvider.parse(String relativePath, byte[] sourceBytes)` is on the interface. Its **default
  throws** `MetadataParseUnsupportedException` — DEC-W008 required a working default, which is not
  implementable in `metadata-server`: a default that parses needs the repository's one source reader
  (OpenRewrite's LST, DEC-030), which lives in `hipster-entity-tooling` and is not on this module's
  classpath. The decision's own boundary section had left the location open, so this is its "module
  dependency resolution" answer, recorded rather than assumed.
- The **reference implementation** is `project-automation`'s new `SourceMetadataParser`, called from
  `InMemoryMetadataCacheProvider.parse` — the override DEC-W008 names. Pure (nothing outside its two
  arguments is read or written), file-scoped facts only: the wayhash of the LF-normalised bytes via the
  tooling's `ContentHash` (so the CRLF/LF rule of DEC-029 § 4 is not re-implemented), the primary type
  chosen by Java's own file-name rule, its kind via `MetadataLocations.kindOf`, and its declared method
  names (sorted — declaration order is not part of the fact).
- **`SourceMetadata` does not exist**, so the entry carries the same `Map<String, Object>` payload cache
  entries already use; a parallel model would have made the interface's two halves disagree the day
  DEC-W007 lands.
- Additive surfaces: RPC `parseFile`, MCP `parse_file`. A provider with no parser gets a JSON-RPC error
  naming the provider (and the MCP tool an error result) rather than a silent `null`; the cache-backed
  methods are untouched, asserted in the same test.
- Tests: `SourceMetadataParserTest` (6) and two new `MetadataServerTest` cases (7 in that class).

**Still open from the decision:** the manual-mode CLI (`jcodebuddy metadata parse <file>`) — now step 7.7.

**Gate:** ✅ `MODULE` for `metadata-server,metadata-mcp-server,project-automation` green.

**Done when:** ✅ done — see the commit for this step.

### 1.3 — `WatchMetadataProvider`: the metadata server over the watch cache
**Who:** agent · **Size:** M

**Done 2026-10-01, with one deliberate deviation from this step's own text.**

What landed: `WatchMetadataProvider` in `metadata-server` — a snapshot view of a watch checksum cache built
from its three real facts (`WatchedFile(path, checksum, lastModified)`). It answers `get` by checksum (the
cache is keyed by path and the interface by hash, so identical content resolves to the first path in path
order while `listEntries` still lists every file), `listEntries` (path-ordered, so a cache with no order of
its own answers in a deterministic one), `hasChanged`, and `listClasses` — **empty**, deliberately, because
a checksum cache has never known a class name and paths that look like Java files would be a guess dressed
as a fact. `parse` is not overridden, so the interface's default answers with the named refusal. Tests:
`WatchMetadataProviderTest` (8), one of which drives a JSON-RPC round trip through the real transport.

**The deviation: the dependency this step asked for is not added, in either direction.** The step followed
the `.kilo` metadata-server plan ("promote `java-watch-agent` from test scope to compile scope"), which is
backwards twice over: it would make a **library** (`metadata-server`, which `metadata-mcp-server` and
`project-automation` both depend on) depend on an **application** that shades a jar and pulls OpenRewrite's
LST in through `jwa-builder`; and the reverse direction — `metadata-server` added to `java-watch-agent` —
would shade Fory into the watch daemon's fat jar for the sake of a three-line helper. So the **reusable
half is promoted here**, which is AGENTS.md § 1.1's rule doing its job, and the call site converts:

```java
WatchMetadataProvider.of(cache.getCache().entrySet().stream()
        .map(e -> new WatchMetadataProvider.WatchedFile(
                e.getValue().path(), e.getValue().checksum(), e.getValue().lastModified()))
        .toList())
```

**Left to the consumer, and not claimed as wired:** no production call site exists today, because nothing
yet runs a `MetadataServer` over a `MetadataCache` — the `.kilo` plan's own step 10 ("integration test
wiring `MetadataServer` with a real `MetadataCache`") never happened either. When such a call site appears,
the snippet above is the whole wiring, and that module's test is the place to prove it.

**Gate:** ✅ `MODULE` for `metadata-server` green — 15 tests (`MetadataServerTest` 7,
`WatchMetadataProviderTest` 8); `metadata-mcp-server` compiles unchanged.

### 1.4 — Test the MCP tool surface
**Who:** agent · **Size:** S

**Done 2026-10-01.** `metadata-mcp-server` has its first test directory, and all **six** advertised tools
are called rather than four: `get_entry`, `list_entries`, `get_metadata`, `has_changed`, `list_classes` and
`parse_file` (added by step 1.2, so leaving it untested would have made the newest tool the untested one).

- `MetadataMcpToolProviderTest` (8 tests) drives each handler directly and asserts the answer, including
  the two shapes that are easy to get wrong: a missing argument is an **error result**, not a thrown
  exception, and `parse_file` against a provider with no parser (DEC-W008's default) arrives as a tool
  error naming the provider rather than a broken session. The cache-backed tools are asserted still to work
  for that provider, which is what "additive" has to mean.
- A drift guard: `tools()` and `register()` are two hand-maintained lists of the same six names, so the
  test pins the names — a tool registered but not advertised (or the reverse) fails here.
- One small production change to make that possible: the six handlers are **package-private** rather than
  `private`, with a comment saying why. The wiring stays `register()` — one explicit `toolCall` per tool,
  each naming its handler method — so there is still no name-to-handler registry to keep in step; a test
  forced to assemble an `McpSyncServer` would have been testing the SDK instead of this class.

**Gate:** ✅ `MODULE` for `metadata-mcp-server` green — 8 tests.

---

## 6. Phase 2 — metadata-arena: make it tested and measured

### 2.1 — The unit tests the plan asked for
**Who:** agent · **Size:** M

**Done 2026-10-01 — and the tests found six real defects.** 37 tests in six classes (was 8 in one):

- `ArenaTest` (8) — both backends, through one set of assertions: sequential allocation, view round-trip,
  byte order, `reset`, growth, read-only refusal, idempotent `close`, and the growth/view caveat.
- `MemoryViewTest` (5) — typed and bulk access with array offsets, and the byte-level defaults.
- `CompactIndexFormatTest` (3) — the magic's exact eight bytes, the header budget, and the layout
  arithmetic. **The note this step inherited was wrong about the format**: the fixed region is
  `HEADER_SIZE + 16 * capacity` (keys *and* value pointers are 8 bytes per slot), not "12 × capacity".
- `IndexMmapRoundTripTest` (5) — values (the existing test asserted only size and capacity), the exact file
  size with no padding, bad magic, bad version, and the byte-order contract.
- `LongToLongsIndexLayoutTest` (8) — the sizing requirement, value-list growth, `totalSize()` accounting,
  the reserved key, the two constructor refusals, and both backends.
- The existing `LongToLongsIndexTest` (8) already covered six of the plan's seven intents, but two only
  nominally: its "capacity growth" case never actually grew anything (an oversized arena), and its mmap case
  never read a value back.

**The six defects, each fixed here and pinned by the test that found it:**

1. **`MemoryView.getBytes`/`putBytes` were wrong for any run longer than one word** —
   `offset + (long) i / 8` advanced the word base by one *byte* per byte instead of eight, so bytes ≥ 8 wrote
   into overlapping words and read back scrambled. Nothing noticed because the only caller
   (`IndexMmapWriter`'s tail) passes fewer than eight bytes.
2. **`FfmMemoryView` declared 8-byte alignment for a packed format**, so the FFM API rejected the index's
   data writes outright (`Target offset 132 is incompatible with alignment constraint 8`): `LongToLongsIndex`
   could not be built over an `FfmArena` at all, while the README claimed both backends work. The layouts
   now declare alignment 1.
3. **`FfmArenaImpl.view()` was sliced to `cursor`** — an empty view before the first allocation, which is the
   exact moment `LongToLongsIndex` writes its fixed region. It now covers the whole segment, like the
   `ByteBuffer` backend always did; the useful guard (refusing a write past the storage) is the segment's own
   bounds.
4. **`FfmArenaImpl.allocate` copied only the allocated prefix on growth**, losing anything written through a
   view without allocating — which is how the index writes its fixed region. `ByteBufferArenaImpl` copied its
   whole buffer; now both do.
5. **`FfmArenaImpl.close()` was not idempotent** (`java.lang.foreign.Arena.close()` throws "Already closed"),
   so `LongToLongsIndex.close()` followed by the caller's own `arena.close()` — the shape every test in the
   module uses — threw during teardown.
6. **`IndexMmapWriter` wrote the *arena's* byte order**, against the module's documented little-endian format,
   so a big-endian index produced a file the little-endian reader misread. It now writes little-endian and
   **refuses** a big-endian index rather than converting it: it transcribes 8-byte words, and the header's
   4-byte fields would be scrambled by a word-level copy, so the honest answer is a named failure.

Docs moved with the fixes: `Arena` and `MemoryView` now state their contracts (view extent, the growth
caveat, the byte packing, alignment), `LongToLongsIndex` gained the javadoc it never had (sizing requirement,
reserved key, rebuild-by-copy, `close` semantics), and the README's magic bytes, layout formula, test list and
byte-order rule were corrected.

**Gate:** ✅ `MODULE` for `metadata-arena` green — 37 tests, 0 failures.

### 2.2 — The JMH benchmarks
**Who:** agent · **Size:** S–M

**The decision this step asked for: yes, the numbers inform something** — which backend the metadata cache
should default to (the module ships two and nothing chooses between them), and whether a full rebuild
(DEC-W009's protocol) is cheap enough to run on every watcher batch.

**Done 2026-10-01.**

- `ArenaIndexJmhBenchmark` in `metadata-arena`: `getHot`, `getRandom`, `rebuild` (reset + re-insert, because
  that is how a caller performs it), `mmapLoad` (map the file written in setup, read every entry, unmap).
  Params: backend ∈ {`bytebuffer`, `ffm`} × entries ∈ {1 000, 100 000}. It declares no forks/iterations of
  its own, so the runner's profile governs.
- `jcodebuddy/metadata-arena/pom.xml` gained the `jmh` profile (`-proc:full`, without which the harness is never
  generated and the sources merely compile) and the same two surefire excludes `hipster-entity-core` has, so
  a `-Pjmh` build stays a build rather than running the benchmark as a test.
- `scripts/run-jmh.js`: `metadata-arena` added to the benchmark module list, to the classpath and to the
  generated-source collection, plus an `Arena` summary group. **The classpath file is now built from a named
  module** (`hipster-entity-test`) instead of "whichever module is last in reactor order" — adding an
  independent module is exactly what makes that ordering assumption fail silently, and the fix removes the
  need to reason about it. Two errors of mine on the way: `-Pjmh` was passed to a module that does not define
  the profile (Maven refuses, correctly), and the runner's own JDK check caught `JAVA_HOME` on 21 — both are
  the tooling working as designed.
- **The benchmark found a seventh defect, and a gap in the tests step 2.1 had just written.**
  `IndexMmapWriter` computed its byte-wise tail as "everything left in the file" rather than "a partial
  word", and the byte-level defaults transcribe a word most-significant-byte first — the reverse of memory
  order. Every file larger than one 64 KB chunk therefore landed on disk scrambled from 64 KB on, and a
  reader over it answered *no such key* rather than reporting corruption. `mmapLoad` at 100 000 entries
  (~5 MB file) threw; every unit-test file fitted in one chunk, which is why they passed. The writer now
  copies word by word and writes the ≤7-byte tail little-endian, and
  `IndexMmapRoundTripTest.aFileLargerThanOneWriterChunkRoundTripsToo` (capacity 8 192 → 131 KB fixed region)
  pins it. 38 arena tests green.
- **Evidence, and what is not evidence:** the smoke runs completed all 16 cases through the standard entry
  point (`bun run scripts/run-jmh.js --include ".*ArenaIndexJmhBenchmark.*"`), which is this step's gate.
  Their numbers are **not recorded** — 1 fork and 1×1 s, and the runner prints exactly that warning. They do
  raise one question worth a real run: `ByteBuffer` measured ~10× faster than FFM on `get` and `rebuild`
  (≈134 000 vs ≈13 000 ops/ms for a hot get), plausibly the alignment-1 var-handle access against an
  intrinsified `ByteBuffer`. That is a hypothesis, not a finding, and it is what step 2.3 exists to settle.

**Gate:** ✅ the benchmark compiles under `-Pjmh` and runs through the runner; `MODULE` for `metadata-arena`
green (38 tests).

### 2.3 — The decision-grade arena run, and the backend decision it settles
**Who:** agent, on a quiet machine · **Size:** S

**Done 2026-10-01 — run at the default profile, and the decision is in DEC-W009's implementation note.**

- **The run**: `bun run scripts/run-jmh.js --include ".*ArenaIndexJmhBenchmark.*"` — 3 forks, 6 × 2 s warmup,
  8 × 2 s measurement, JMH 1.37, **22 min 29 s**, written to `target/jmh/results.json`. The profile is
  verified **from the JSON itself** (`forks: 3`, `warmupIterations: 6`, `warmupTime: 2 s`,
  `measurementIterations: 8`, `measurementTime: 2 s`) rather than from the absence of a console line, and
  `results-smoke.json` — the marker of a run that must not be recorded — does not exist.
- **Step 2.2's hypothesis is now a finding, and it is large.** `ByteBufferArena` is ~10× `FfmArena` on the
  hot path: `getHot` 121 774 vs 11 989 ops/ms at 1 000 entries and 127 584 vs 10 989 at 100 000; `getRandom`
  86 943 vs 10 722 and 50 281 vs 9 942; `rebuild` 92.8 vs 7.3 and 0.579 vs 0.071. The intervals do not come
  within a factor of four of meeting. The **cause** stays a hypothesis (no profiler was run) — what is
  established is direction and magnitude.
- **The one mode where they are equal is the cold start**: `mmapLoad` 171 µs vs 180 µs at 1 000 and 4.72 ms
  vs 4.06 ms at 100 000, intervals overlapping — consistent with a path dominated by mapping and page
  faults rather than by the accessor.
- **Decision 1 — the allocator is `ByteBufferArena`, with `FfmArena` kept for the two cases a `ByteBuffer`
  cannot serve**: size (int-indexed, 2 GiB per arena, which binds at roughly 30–40 million entries) and the
  platform's own direction. `sun.misc.Unsafe` is rejected outright and `Chronicle Bytes` is rejected as
  buying nothing — both named options answered rather than deferred.
- **Decision 2 — rebuild on the existing debounced batch, and incremental updates are not needed for
  latency.** The watcher's debounce is 300 ms by default (`HotSwapDaemon.DEFAULT_DEBOUNCE_MS`); a full
  rebuild is 10.8 µs at 1 000 entries and **1.73 ms at 100 000** (0.6 % of the budget), ~17 ms at a million
  by the linear 17.3 ns/entry figure. A batch's budget holds ≈ 17 million entries on ByteBuffer and ≈ 2.1
  million on FFM. The constraint is therefore not the rebuild but the **arena size**, which is why the note
  gives the sizing formula (`HEADER + 16 × capacity + 12 × entries + slack`, capacity the next power of two
  at or above twice the entries) with its per-size figures — that is the third follow-up answered too.
- **One irregularity, disclosed rather than smoothed over**: `getRandom/bytebuffer/1000` produced **5, 5 and
  6** measurement iterations per fork instead of 8/8/8 — uniform across its three forks, no error in the
  JSON, cause not established (the console output was not kept). Every other case is complete. It does not
  carry the decision: its 100 000-entry sibling is complete, and even its wider interval (±10 400 on
  86 943) leaves the gap to FFM's 10 722 intact.
- **Recorded in DEC-W009, not in a README**: the record's implementation note carries the full table, both
  decisions, the sizing rule and a "what this run does not establish" section; its three follow-ups are
  marked settled and a fourth was added (the unexplained FFM gap — anything that puts FFM on a default path
  owes a profile first). `jcodebuddy/metadata-arena/README.md` keeps its rule that numbers live where the decision is
  and now points at that note.

**Gate:** ✅ the numbers come from a default-profile run (the profile recorded inside `results.json`, no
`results-smoke.json`, 24 samples per case in 15 of 16) and the decision — backend, why, and the batch
answer — is written into DEC-W009's implementation note.

---

## 7. Phase 3 — hipster-ioc: **PROTOTYPING** — a project-wide generator that consumes metadata

> **hipster-ioc cannot work on single files.** It is a **project-wide** generator: what it needs is the
> project's *type relations* — who extends whom, who implements what, which types are assignable — and no
> single file contains that. Reading a file and following what it happens to reference is not a smaller
> version of the job; it is a different job that produces confident wrong answers when the reference points
> outside the file, which is exactly what the prototype did on its first implementation (it looked for the
> module interface in the context's own compilation unit, found nothing, and emitted wiring with no
> factories rather than an error — step 3.2).
>
> **Extracting metadata is not hipster-ioc's job.** hipster-ioc *consumes* metadata to produce IoC code.
> Extraction, caching and indexing belong to the metadata side, and since 2026-10-02 that side is **one
> engine in `jcodebuddy-core`** ([DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md)) rather
> than the four modules that hold the pieces today: [`jcodebuddy-meta`](../jcodebuddy/jcodebuddy-meta) (the
> module 3.0m renamed from `metadata-server`)'s providers and
> RPC/MCP surfaces, the **class index** (`hipster-entity-tooling`'s `ClassIndex`/`ClassRecord`, DEC-029) with
> per-file checksums (`ContentHash`), [`metadata-arena`](../jcodebuddy/metadata-arena) for index storage, and
> `java-watch-core` for the watch loop. What is missing is the **engine and its contract** — steps 3.0a–3.0k
> below, which come *before* any consumer work, because a consumer migrating onto a moving model moves twice.
>
> > **This phase is not a delivery, and its steps are not a contract.** hipster-ioc is still in
> > **prototyping**: the point of the work here is to *find the shape* of the generated code, and that shape
> > is not settled. [DEC-036](../doc-hipster-entity/architecture/decisions/DEC-036.md) is `Trial`, the
> > generator emits one shape today, and some of the decisions it records are clauses nothing implements
> > yet. The prototype's own `CodeGenerator` implementation — a **per-file SPI** — is a category error kept
> > only as a shortcut; step 3.0e is what removes it.
> >
> > So this phase follows different rules from every other one in this file:
> >
> > - a step here is **shape-defining** when it changes what the generator emits. Its output is a prototype —
> >   expected to be rewritten — and the committed generated file in `hipster-ioc-test` is a sample, not a
> >   contract;
> > - a step whose content **depends on the shape being settled** is marked **`[TBD]`**: deliberately
> >   unscheduled, with the decision it waits on named, rather than written as though the shape were known.
> >   A `[TBD]` step that quietly becomes "implement whatever the generator emits today" is the failure this
> >   phase can have, because it would freeze the prototype by accident — the shape would be decided by
> >   nobody, in code, with no record saying so;
> > - **the way out is a decision, not a date.** When the shape stops changing, DEC-036 moves from `Trial`
> >   to `Accepted` (or is superseded), and the steps below become ordinary steps with real gates. Until
> >   then a `[TBD]` row is a *known* item, not a forgotten one — an empty cell would be the latter.
>
> **Order in this phase:** 3.0a settles the engine decision's open points; 3.0b–3.0e are the metadata work
> this phase always needed (relations, freshness, one resolver, moving the prototype onto it); **3.0f–3.0k are
> the engine itself** ([DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md): one metadata engine
> in `jcodebuddy-core` as the backbone for codegen, analysis, reporting, the watch loop and an LSP sidecar);
> 3.1–3.3 are the prototype already delivered; 3.4–3.11 are `[TBD]`, and each one's "waits on" now names the
> metadata piece it needs where that is the real blocker.
>
> **The engine (DEC-037) reframes what "the metadata contract" means here.** It is no longer only the seam a
> generator reads: parsing, the model, the indexes (with relations), search and the watch loop that keeps them
> fresh and fires events are **one library in `jcodebuddy-core`**, and every other module is a consumer or a
> transport — `jcodebuddy-codegen-api` dissolves into it. So 3.0f–3.0k precede nothing in this phase but are
> the reason 3.0b–3.0e are worth doing once, in the right place: build the engine, then move consumers onto it
> rather than each consumer growing its own path. DEC-037 is `Accepted` (2026-10-02; 3.0a settled its three open
> points, and DEC-038 records the two answers it needed) — this sentence said `Proposed` until 2026-10-03, which
> was the plan contradicting the record next to it.
>
> The step numbers `3.0a`–`3.0k` say *before 3.1* deliberately: this plan renumbers nothing, and these
> steps must land before the consumer ones to be worth anything.

### 3.0a — Settle the engine decision's open points (ADR first)
**Who:** agent + maintainer · **Size:** S–M

[DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md) records the direction: one metadata engine
in `jcodebuddy-core` as the backbone for codegen, code analysis, code reporting, the watch loop and an LSP
sidecar; `jcodebuddy-codegen-api` dissolves into it; consumers never keep a private metadata path. It is
`Proposed` because three points are the maintainer's call and each changes code:

1. **What stays a leaf** — recommendation: the marker vocabulary and its parser (`GeneratedCodeMarkers`,
   `GeneratedCodeParser`, `GeneratedBlock`) move to a small leaf module, because the tools that read generated
   regions must not resolve OpenRewrite, which `jcodebuddy-core` gains the moment it is the engine. The
   alternative keeps the three types in the engine and gives that case the dependency the leaf exists to
   avoid.
2. **How far `metadata-server` is absorbed** — recommendation: keep it and re-point its providers at the
   engine, so DEC-W006–W009's serving shapes survive while their ownership of the model does not.
3. **What happens to `java-watch-core`'s watcher and checksums** — recommendation: it becomes the engine's
   freshness implementation (the engine owns the contract), because DEC-W006's cache and DEC-W009's rebuild
   protocol are exactly the freshness semantics the engine must publish.

**Do:** settle the three, flip DEC-037 from `Proposed` to `Accepted` (or amend it where the answer differs),
and update the records the answers change — [`module-map.md`](../doc/architecture/module-map.md) (which
today states `jcodebuddy-core`'s leaf property as its reason to exist, and the gate's own comment in
[`scripts/lib/gate.js`](../scripts/lib/gate.js) names it), DEC-029, and DEC-W006/W008/W009. Also record the
fact this step starts from, because it shapes every later step: **the recorded gate covers none of the
modules the migration touches** (`GATE_MODULES` is `jcodebuddy-core` plus the six `hipster-entity` modules),
so each migration step carries its own build/test evidence until 3.0k grows the gate.

**Done 2026-10-02 — every point settled, and the answer produced a second record.**

- The maintainer answered all of them: `jcodebuddy-core` holds the engine **and what consumers need**,
  `jcodebuddy-codegen-api` is merged into it "to simplify", the **markers move to a leaf**, and the watch side
  is `metadata-server` **renamed `jcodebuddy-meta`** with `java-watch*` staying **its own library** (the engine
  takes no watcher).
- **[DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md) is `Accepted`** with both points
  answered, and the module layout they imply is **[DEC-038](../doc-hipster-entity/architecture/decisions/DEC-038.md)**
  (`Accepted`), which also absorbs the third thing the direction named: `jwa-builder`/`jwa-builder-api`.
- **The reconnaissance turned one of those into a finding worth the record**: there are **two
  `SourceSplicer` implementations** — `hipster-entity-tooling`'s and `jwa-builder`'s
  `hr.hrg.watch2.builder.SourceSplicer` (both package-private) — plus two read/position stacks, against
  DEC-030's "one representation, one splice path". So the `jwa-builder*` absorption is a **consolidation**,
  not a rename, and DEC-038 decision 5 makes one path survive.
- **Also recorded, because it shapes every step below**: `metadata-server`'s `groupId` is already
  `hr.hrg.jcodebuddy` while its package is `hr.hrg.watch2.server.metadata` (a half-rename waiting to happen),
  and the consumers of `jwa-builder*` — `java-watch-agent`, `project-automation`, `webview/jwa-sidecar` — are
  all **outside the recorded gate**.

**Gate:** ✅ DEC-037 is `Accepted` with every open point answered; DEC-038 carries the module layout; the
records that state the old shape are pointed at the new one
([`module-map.md`](../doc/architecture/module-map.md)'s "where this is going" note); `LINKS` green. (No code
gate: this step was a decision.)

**Done when:** 3.0f can start without a second decision — the engine's home, its leaf exceptions and its
consumers are all written down.

### 3.0f — The engine's skeleton in `jcodebuddy-core`, and the model it carries
**Who:** agent · **Size:** L

The generator-facing seam is **empty and too small**: `jcodebuddy-codegen-api`'s
[`TypeResolver`](../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/query/TypeResolver.java)
(`resolve(fqn)`) has no implementation but `EmptyTypeResolver`, and
[`TypeDefinition`](../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/query/TypeDefinition.java)
(qualified name, simple name, fields, field types) carries **no relations**, so it cannot answer the question
hipster-ioc actually has. That is a symptom: the model a consumer needs is spread across the tooling module
and two watch modules, and the SPI lives in a fifth. *(Those two links point where the types live today — the
engine's query seam; step 3.0i dissolved the module that held them and deleted it.)*

**Measured before starting (2026-10-02), so this step's real shape is visible:** `hipster-entity-tooling` is
**51 main sources** — 22 in the module's own package (the generators, the LST helpers, the divergence
reporter: the ones that need sorting into *engine* vs *consumer*), 12 in `meta/` (the representation and the
parse path: engine), 12 in `validation/` (the entity rules validators: consumer-side), 5 in `index/` (the
class index: engine) — plus **65 test sources**, and `jcodebuddy-codegen-api` is **5**, `jcodebuddy-core`
is **3 main + 3 test**. So most of this step is a **classification** ("is this the model, or is this a
generator reading it?"), and the move itself is small next to deciding what belongs where. Do not move a
package wholesale: `validation/` and the emitters stay consumers.

**The classification (2026-10-02), per file — the artefact this step starts with.** Read from the sources'
own summaries, not from their package names. It is deliberately not per package: `meta/` splits, and one
package-level guess in the measurement above was wrong (`meta/` is *not* all engine).

| Verdict                                  | Files                                                      | Why                                                                                            |
| ---------------------------------------- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| **Engine → `jcodebuddy-core`**           | `index/ClassIndex`, `index/ClassRecord`, `index/TypeFacts` | the class index: one row per type with kind, modifiers, enclosing, line (DEC-029). These are the engine's rows; 3.0b adds relations to them |
| **Engine**                               | `index/ContentHash`, `index/Wyhash64`                      | a file's content identity — the key the engine's invalidation is built on                      |
| **Engine**                               | `SourceReader`                                             | the one place an existing source is read, through DEC-030's one representation                 |
| **Engine**                               | `TreeQueries`                                              | the read-only queries over the parsed tree                                                     |
| **Engine**                               | `JavaSyntaxCheck`                                          | the javac positions the LST cannot answer (DEC-030: positions come from javac)                 |
| **Engine** *(judgement call 1)*          | `SourceSplicer`                                            | the write half of the same one representation — see below, and see 3.0f-2's evidence           |
| **Engine**                               | `meta/SourceMetadata`                                      | the file-scoped metadata a parse produces (DEC-W008's shape)                                   |
| **Engine**                               | `meta/SourceLocation`                                      | "one place a field is, as a pass recorded it" — a position record, engine-shaped               |
| **Consumer — corrected by the compiler** | `meta/InterfaceInfo`                                       | **was classified engine; it is not.** It holds `Property` and `ViewAttributes`, so it is the entity model's view of an interface. 3.0f-2's build proved it; the engine set is 11 files |
| **Consumer — emitters**                  | `EntityMetadataGenerator` (the pass), `FieldBoilerplateGenerator`, `ValidationGenerator`, `ViewAdapterGenerator`, `ViewBuilderGenerator`, `ViewInterfaceGenerator`, `ViewMapperGenerator`, `ViewRecordGenerator`, `ViewTrackingBuilderGenerator` | they *read* the model and *write* Java; moving them would put entity codegen inside the engine |
| **Consumer — the entity model**          | `meta/EntityMeta`, `meta/EntityFieldMeta`, `meta/ViewMeta`, `meta/ViewFieldMeta`, `meta/ArtifactMeta`, `meta/Property`, `meta/ViewAttributes`, `meta/FieldConstraint`, `meta/TrackableType`, `MetadataLocations` | views, entities, artifacts, constraints, tracking levels: **domain**, not engine. The measurement called `meta/` "the representation and the parse path" — true of the three engine rows above, wrong for these nine |
| **Consumer — generator behaviour**       | `CooperativeCodegen`, `DivergenceReporter`, `GenLevelResolver`, `GeneratorPreflight`, `TypeLiterals`, `JcodebuddyDirectory`, `ViewAnnotationReader` | DEC-020 preservation, DEC-022 diagnostics, entity gen-levels, preflight, literal spelling, output plumbing, the `@View` reader |
| **Consumer — rules**                     | all 12 of `validation/`                                    | the entity conventions and their CLIs                                                          |

**3.0f-2 landed on 2026-10-02** (its first attempt was reverted, and the compiler is why). The
twelve files were moved, the packages rewritten and every reference re-pointed (`scripts/extract-engine.js`,
kept and now correct); `jcodebuddy-core` then failed to compile with **five of the moved files reaching back
into classes that stay**. That is not a build accident — it is the dependency direction DEC-037 forbids, and
the evidence is better than the guess the classification made:

| Moved file             | Reaches back to (consumer, stays)                                       | What it means |
| ---------------------- | ----------------------------------------------------------------------- | ------------- |
| `meta/InterfaceInfo`   | `Property`, `ViewAttributes`                                            | **it is not engine at all**: it holds a view's properties and its `@View` attributes. The classification was wrong on this one; it belongs with the entity model that stays, and the engine set is **11 files**, not 12 |
| `index/TypeFacts`      | `MetadataLocations` (an import *and* a use)                             | the index row reaches into the artifact-location model — the engine needs its own answer to "where is this declaration", or that helper moves in |
| `index/ClassIndex`     | `EntityMetadataGenerator`, `JcodebuddyDirectory` (+ three more symbols) | the index reaches into the *pass* (a package filter?) and into the output-directory marker. `EntityMetadataGenerator` is unambiguously the consumer; `JcodebuddyDirectory` is arguably engine vocabulary, because the index **is** a file under `.jcodebuddy/index/` |
| `source/SourceReader`  | `DivergenceReporter`                                                    | the reader reports diagnostics through the entity pass's reporter — the engine needs a diagnostic channel of its own, or the DEC-022 vocabulary needs to move with it |
| `source/SourceSplicer` | `ViewInterfaceGenerator` (a static member)                              | a write helper that needs an *emitter* to work. This is evidence against judgement call 1 below: either the member it needs is engine vocabulary and moves, or `SourceSplicer` is a consumer and 3.0n's survivor is `jwa-builder`'s copy |

**So 3.0f-2 has four small decisions before it can be a move**, and each has three shapes: **move it in** (it
is engine vocabulary), **invert it** (the engine defines a minimal contract — a diagnostic sink, a package
filter, a source-location query — and the consumer supplies it), or **drop the dependency** (the engine can
answer without it). Recommended, from the evidence above: `InterfaceInfo` back to the consumer set;
`JcodebuddyDirectory` **into the engine**; `EntityMetadataGenerator` **inverted** (filter as a parameter);
`DivergenceReporter` **inverted** behind a sink, with DEC-022's diagnostic vocabulary moving into the engine,
because "a missing answer is reported, never inferred as absent" is an engine rule; `TypeFacts`'s
`MetadataLocations` use **inverted or moved**; and `SourceSplicer`'s `ViewInterfaceGenerator` member
**moved into the engine if it is a marker**, otherwise judgement call 1 flips and the splice stays a consumer.
The tree was left green (the attempt reverted) rather than either dragging consumer classes into the engine or
committing a build that does not compile; `scripts/extract-engine.js` stays, with its two heuristic bugs fixed
(it now adds an import only for a file that **shared the class's old package**, skips a class the file declares
itself, and ignores comment lines).

**Outcome — all six executed as recommended, and two of them were not the shape the recommendation guessed.**
The move landed with `jcodebuddy-core` holding the engine (**12 files**: the five index files including their
new `TypeKinds`, the four `source/` helpers, `meta/SourceMetadata`, `meta/SourceLocation`, and
`JcodebuddyDirectory`), and the six fixes were:

| Decision as recommended                     | What it actually was                                 |
| ------------------------------------------- | ---------------------------------------------------- |
| `InterfaceInfo` back to the consumers       | as recommended — the compiler had already decided it |
| `JcodebuddyDirectory` into the engine       | as recommended, and its `DIR` constant is **defined** in the engine now instead of read from the pass |
| `EntityMetadataGenerator` inverted          | **not a filter**: the index used three shared *utilities* (`escapeJson`, `OBJECT_MAPPER`, `JCODEBUDDY_DIR`), so they became the engine's `MetadataJson` (one escaping rule, one mapper) and `JcodebuddyDirectory.DIR`, with the pass's own members delegating — one definition each |
| `DivergenceReporter` inverted behind a sink | as recommended: the engine defines `DiagnosticSink` (one method, DEC-022's six parameters), the reporter implements it and keeps owning the format |
| `TypeFacts`'s `MetadataLocations` use       | **moved, not inverted**: `kindOf` became the engine's `TypeKinds`, because `MetadataLocations`' own javadoc said the method was public "only so `TypeFacts` can reuse it". The entity model delegates, so there is still one resolver |
| `SourceSplicer`'s member                    | **judgement call 1 stands**: the member was *data* (`EntryPoint`, two strings), not emission logic, so it moved into the engine and the emitter converts at its one call site — the emitter's API and its 11 test references did not move |

Two further consequences, recorded because they are the split's real cost: **(a)** helpers that were
package-private because they shared a package with their only callers became the engine's public surface
(`SourceSplicer`, `JavaSyntaxCheck` and its nested types — including two compact canonical constructors that
had to follow their records — `SourceReader.readFragmentUnit`/`reportUnparseable`,
`ClassIndex.moduleRelative`/`README_TEXT`, `Wyhash64`); **(b)** the engine's POM gains OpenRewrite and
Jackson 3 (`tools.jackson.core`), and `jcodebuddy-codegen-api` now depends on the **engine** instead of
`hipster-entity-tooling` — which is the cycle DEC-037's third fact described, gone.

Evidence: the recorded gate `BUILD SUCCESS` (`jcodebuddy-core` + the six `hipster-entity` modules, tooling
5:31 — its committed example regenerates byte-identically, which is 3.0f-4's evidence as well), a 20-module
build green for every module outside the gate before the visibility widening, and `LINKS` green.
`scripts/extract-engine.js` and `scripts/apply-engine-inversions.js` stay: each inversion is written there
with the reason it has the shape it has.

**3.0f-3 landed 2026-10-02 — the model's answer is a value, not a `null`.** The index had one way to answer
about a type, `ClassIndex.row(fqn)`, whose `null` means both *"this index has no such type"* and *"this type is
not something this index covers"* — a JDK type, another module's type, a type the pass has not reached. The
second case is the common one, and reading it as the first is what the prototype did in step 3.2. So:

- **`TypeAnswer`** (`engine.index`, a sealed interface): `Found(ClassRecord)` — a fact a consumer may act on —
  or `NotIndexed(fqn, cause)`, where the cause is **part of the value** and is phrased as what the index covers
  ("not a type in the index of module 'x'; it may be a JDK type, another module's type, or a type this module's
  sources do not declare — the engine is not saying it does not exist"), never as an absence.
- **`ClassIndex.answer(fqn)`** is the seam consumers should ask by; `row(fqn)` stays for the index's own use and
  is now documented as the shape *not* to ask in.
- **Members and relations are deliberately absent** from the model (3.0b, 3.0h), and the contract's point is
  that asking for them must also answer *cannot* rather than *none* — the same mistake in a new place. The
  type's javadoc says so, so the two steps that fill it know what to preserve.
- Evidence: `TypeAnswerTest` (4 tests: an indexed FQN answers with its row and a description naming file and
  line; a JDK FQN answers "cannot answer … not saying it does not exist"; `row()` still returns a bare `null`,
  pinned *as* the reason to prefer `answer`; and `type()` on a non-answer throws rather than handing back a
  null). Core: 53 tests, `BUILD SUCCESS`.

**The two stale-prose amendments 3.0f owed are done**: `DEC-009`'s "All three live in `hipster-entity-tooling`"
and `DEC-W008`'s "the tooling module is still where source bytes are turned into a `SourceMetadata` tree" both
now carry a dated note saying the tree is turned in the engine and the tooling consumes it.

**3.0f-4 is satisfied by the gate**: `ExampleRegenerationTest` regenerates the committed example
byte-identically, and the tooling's 65 test sources are green (`-pl :jcodebuddy-core,:hipster-entity-tooling -am test`,
`BUILD SUCCESS`). So **3.0f is complete** — 3.0f-1 the classification, 3.0f-2 the move and its six inversions,
3.0f-3 the answer contract, 3.0f-4 the pass unchanged. What it did *not* do, and what comes next: the model
still carries no members and no relations (3.0b), and no consumer has been rewired onto `answer()` yet — the
two places that should be are where the entity pass and `MetadataLocations` read the index, and they are
3.0b's or 3.0j's work rather than a silent follow-up here.

**3.0f-3's shape is set by rule 10 and DEC-037's "what a consumer owns" (clarified 2026-10-02)**: the model is
**general and rich** — the facts a *class* of consumers needs, not only what the entity pass wants today — and
it grows **no entity concept** (`@View`, views, artifacts, builder levels). hipster-entity keeps its own
entity/relation shape and serialises it for its UI and its visual documentation, because that is a projection
of the engine's facts; what it may not do is parse source or re-derive a fact the engine already answers. So
3.0f-3 is judged by two things: can a consumer extract what it needs **without touching source text**, and does
the model still contain nothing that is only one consumer's domain.

**Judgement calls, recorded rather than buried.** *(1) `SourceSplicer` goes to the engine.* DEC-030 names
the splice as the write half of the one representation, and DEC-037 lists parsing but not writing — so this
extends the record rather than following it. The reason to extend it: with the splice in the engine, "one
splice path" is structural and **3.0n** (absorb `jwa-builder*`) has an unambiguous replacement to point its
consumers at. The alternative — writing stays with generators, and `jwa-builder`'s copy becomes the survivor
in `jcodebuddy-builder` while the tooling's is deleted — is equally coherent and is recorded here so the
choice is visible rather than accidental. *(2) `meta/` splits*, above. *(3) `JcodebuddyDirectory` stays a
consumer* although "where output goes" sounds shared: it is the generator's output plumbing (DEC-026), and
the engine does not write files. *(4) What the engine gains*: OpenRewrite (`rewrite-core`, `rewrite-java`,
`rewrite-java-25`) and Jackson — exactly the dependency change DEC-037 predicted, and the reason the marker
leaf (3.0l) exists. *(5) `SourceMetadata` moving* is what lets `jcodebuddy-codegen-api`'s single-type
dependency on `hipster-entity-tooling` disappear at 3.0i — the cycle DEC-037's third fact described.

**Execution order for this step** (each lands with its own evidence, because nothing here is in the recorded
gate): **3.0f-2** `jcodebuddy-core` gains `index/` and the parse/position/write helpers plus the three
`meta/` engine files, packages re-imported, `hipster-entity-tooling` depending on core — behaviour unchanged;
**3.0f-3** the engine's *model* answers with one type (kind, modifiers, members, declaration file, checksum,
relations) and a *missing* answer stays distinguishable from an absent one; **3.0f-4** the entity pass
still regenerates the committed example byte-identically and the tooling's 65 test sources stay green. This
pass is **3.0f-1: the classification**, which moves no code.

**What 3.0f-2 will touch, measured (2026-10-02).** The engine types are imported by **five modules**, so the
move is cross-module even though it is small:

| Module                   | What it imports                                              | Note                                                                                     |
| ------------------------ | ------------------------------------------------------------ | ---------------------------------------------------------------------------------------- |
| `jcodebuddy-core`        | —                                                            | gains the engine packages, and OpenRewrite + Jackson with them                           |
| `hipster-entity-tooling` | the engine types throughout (`validation/*`, `MetadataLocations`, `EntityMetadataGenerator`, `index/*`) | internal imports; it stays a consumer and keeps depending on core, which it already does |
| `project-automation`     | `SourceReader`, `TreeQueries`, `index.ContentHash` (`SourceFacts`, `runner/SourceMetadataParser`), `meta.SourceMetadata` (`MetadataTypeResolver`), plus one test | outside the recorded gate → own build evidence |
| `jcodebuddy-codegen-api` | `meta.SourceMetadata` in `CodeContext` and `CodeContextImpl` | **this is the one-type dependency DEC-037's third fact describes**; after 3.0f-2 it points at core, and 3.0i dissolves the module |
| `hipster-ioc-tooling`    | `SourceReader`, `TreeQueries` (`ContextReader`)              | outside the gate → own build evidence                                                    |

Only `hipster-entity-tooling` is in the recorded gate, so 3.0f-2's evidence is the gate **plus** a targeted
build of `project-automation`, `jcodebuddy-codegen-api` and `hipster-ioc-tooling`.

**Do:** make `jcodebuddy-core` the engine's home and give it the model — one type with its kind, modifiers,
members, declaration file and checksum; relations expressed rather than implied; a *missing* answer reported,
never an inferred absence (the lesson from step 3.2 and `TypeChangeConflictResolver`'s `UNRESOLVED_WARNING`);
no file handles, no class loader, no second parser — the engine reads through DEC-030's one representation.
Move the representation and the parse path out of `hipster-entity-tooling` into it (and the marker
vocabulary out to the leaf 3.0a chose, if that is the answer), keep `hipster-entity-tooling` as a consumer,
and leave the entity pass working: this step moves code, it does not change what a generator emits.

**Gate:** the modules this touches are **outside the recorded gate** (DEC-037's second fact), so this step
publishes its own evidence: `MODULE` green for `jcodebuddy-core,jcodebuddy-core-leaf?,hipster-entity-tooling`
plus an explicit build of every module that referenced what moved, and the entity pass regenerating the
committed example byte-identically.

**Done when:** one module holds the model and the parse path, `TypeDefinition` (or its successor) can express
a relation, and nothing outside the engine parses Java for metadata.

### 3.0g — Freshness: the watch loop, its events and its invalidation
**Who:** agent · **Size:** L

A consumer that reads stale metadata is the failure this whole layer exists to prevent, and today freshness
lives in `java-watch-core` (the watcher, `ChecksumDatabase`, `ChangeSet`) and in `metadata-server`
(`WatchMetadataProvider`) while the model lives somewhere else entirely.

**Do:** the engine publishes the freshness contract — what a subscriber observes (**events**, with enough to
act: which file, which row, which relation, what kind of change), what is cached, and what invalidates it. A
row depends on the file that declares it **and on the types it names**, so an edit to `B` can stale `A`'s row
even though `A.java` was untouched; a rename, a delete and a new file each have a defined effect. Use 3.0a's
answer about `java-watch-core` (its watcher and checksums become the implementation beneath this contract, or
move in), and settle 3.0c with it rather than separately.

**Gate:** own evidence (outside the recorded gate) plus a test for the three transitions that matter: an edit
invalidates its own row; an edit that changes a relation invalidates the *dependent* rows; a deleted file
removes its row and the rows that named it (or marks them unresolved, as the contract says). And one test
that a subscriber actually receives an event — a freshness contract nobody can observe is a cache.

**Done when:** "is this metadata safe to read?" has a mechanical answer, and a consumer can subscribe instead
of watching files itself.

**Landed 2026-10-02 in the engine (`hr.hrg.jcodebuddy.engine.fresh`), and it is deliberately not a watcher.**

- **The division: the host watches, the engine says what it means.** DEC-038 settled that the engine takes no
  watcher, so nothing here polls a file or holds a watch service; `Freshness.report(kind, path)` is what the
  host calls after observing one, and it is the single entry point for all three transitions (a new file, an
  edit, a removal differ only by kind).
- **`FreshnessEvent(kind, path, rows, dependents, unresolved, cause)`** — and the second field is the whole
  point. A row depends on its file **and on the types it names**, so editing `Person.java` stales
  `Employee`'s row although `Employee.java` was never touched; that set is computed from 3.0b's relations
  (`ClassIndex.subtypesOf`), not by parsing anything. `kind` reuses `ClassIndex.ChangeKind` rather than
  introducing a second vocabulary for "what happened to a type".
- **The dependent search matches the FQN *and* the simple name**, because a relation is stored as written
  (`implements Person` contains no FQN) — and the fixture writes it that way on purpose, so a dependent match
  that only looked for `a.b.Person` would fail the test rather than pass silently.
- **`Freshness.stateOf(fqn)` answers SAFE / STALE / UNKNOWN**, with UNKNOWN kept apart from SAFE: a name the
  engine never saw is not a name it can vouch for, which is 3.0f-3's rule applied to freshness. A removal marks
  what named the gone type as **unresolved** rather than dropping the relation.
- **What is cached is the index and nothing else** — no parse trees, no source text between calls — so the
  invalidation unit is the row and a re-read of the file is always correct.
- **A file the index has no row for is still reported**, with a cause saying the index has no row for that path;
  silence would be indistinguishable from safety.
- **Evidence (own evidence, this is outside the recorded gate):** `FreshnessTest`, 6 tests — an edit stales its
  own row; an edit that touches a relation stales the **dependent** rows; a deleted file leaves what named it
  **unresolved**; a subscriber actually receives the event and a closed subscription stops; an unknown path is
  reported; and an unknown name is UNKNOWN rather than SAFE. `EngineHasNoWatcherTest`, 2 tests, makes DEC-038's
  rule mechanical: the engine's own POM declares no `watch` artifact and no engine source names
  `hr.hrg.watch2` or reaches for the JDK's `WatchService`. Core **67 tests `BUILD SUCCESS`**.
- **Additive, so nothing else was re-run**: this step adds a package and edits no existing engine type, so the
  tooling (and the gate path) cannot be affected — stated rather than claiming a run that did not happen.
- **What this step does not do:** it does not re-read a changed file (a pass does that), does not resolve
  relation names (3.0h), and does not decide *when* a pass runs (the host's policy). 3.0c's cache question is
  settled by the same answer: the row is the invalidation unit.

### 3.0h — Search: the queries every consumer asks
**Who:** agent · **Size:** M

Codegen, analysis, reporting and an LSP sidecar ask the same questions in different words, and today each
answers them its own way (`ClassIndex` lookups, sibling-file reads, classpath resolution in the merge tool).

**Do:** publish the engine's query surface over the model and the indexes: by fully qualified name; by kind
and modifier; by relation (supertype, subtype, implementor — in the direction the index stores and the
direction a consumer asks); by annotation; by member; by package. Say what a query returns when the metadata
is *unavailable* (beyond the sources the engine was given) and keep the distinction between "not yet indexed"
and "does not exist" visible, because collapsing them is what produces confident wrong answers.

**Gate:** own evidence, with tests per query against a fixture whose relations cross a module boundary (the
case a per-module index cannot answer), and a test that an unknown name is distinguishable from an
unindexed one.

**Done when:** a consumer can ask a relation question and get one answer, from one place.

**Landed 2026-10-02 as `hr.hrg.jcodebuddy.engine.query.MetadataQuery`, over a *set* of module indexes.**

- **Four of the six query families are answered from the model**: by FQN (`answer`, returning 3.0f-3's
  `TypeAnswer`), by kind, by modifier, by package, by path, and by relation in both directions
  (`extendersOf`, `implementorsOf`, `subtypesOf`, `supertypesOf`). Every answer carries `coverage()` — the
  modules searched — so "not found" reads as "not declared in these modules" and never as "does not exist";
  the two tests the gate asked for are `oneQuestionIsAnsweredAcrossModuleBoundaries` (the relation crosses a
  module boundary, which no per-module index can answer) and
  `aDeclaredTypeWithNoImplementorsIsAFactAndAnUnknownNameIsNot`.
- **3.0b's recorded gap is closed**: relation names are resolved against the indexed world — FQN first, then
  each package prefix of the declaring type (`a.b.Outer.Inner` included), then a simple name that exactly one
  indexed type has. Two candidates is not a tie to break but a question the engine cannot answer, and it is
  **reported** in `RelationAnswer.unresolvedNames()` instead of guessed. A relation naming something outside
  the modules (`java.io.Serializable`) is reported there too, so an answer's gaps are visible.
- **A correction the test forced, recorded because it is the useful part**: my first ambiguity fixture put the
  implementing class in the same package as one of the two `Person`s — where the *declaring package* settles
  the name exactly, so nothing was ambiguous and the engine was right to answer. A genuine ambiguity needs a
  package where neither candidate lives; the fixture now has `e.f.Orphan implements Person` for that, and
  keeps `c.d.Employee` and a full-FQN `g.h.Exact` to show exact resolution still winning.
- **Two families are not answerable from the model**, because a row carries no members and no annotations:
  `membersOf` and `annotationsOf` return **`NotCovered`** — question, reason and what to read instead — rather
  than an empty list, which would read as "this type has no such member". That gap is a DEC-029 format change
  and is scheduled as **3.0r** below, which is what the step's own Do asked for when it listed those two
  queries.
- **Evidence:** `MetadataQueryTest`, 6 tests (cross-module answer and coverage; declared-but-unimplemented vs
  declared-nowhere; an outside relation reported unresolved; ambiguity refused and reported; the kind, modifier,
  path and package queries over the whole project; and the two gaps reporting themselves). Core **73 tests
  `BUILD SUCCESS`**. Additive apart from one small addition to `TypeAnswer` (`fqn()`), which the answer types
  needed to report a question they could not answer.
- **What it does not do:** it does not grow the model (3.0r), does not cache query results (the index is the
  cache and `Freshness` says when it is stale), and does not search source *text* — a text search is a different
  question from "what is declared".

### 3.0i — Dissolve `jcodebuddy-codegen-api` into the engine
**Who:** agent · **Size:** M

DEC-037 decision 2: the five types split by what they are. `TypeResolver`/`TypeDefinition` are engine
metadata queries; `CodeGenerator`/`CodeContext`/`CodeContextImpl` are the generator SPI the engine publishes;
the `SourceMetadata` edge (the reason codegen-api depends on `hipster-entity-tooling` today, and the reason the
SPI cannot be implemented next to the index it reads) disappears with the move.

**Do:** move the SPI into the engine, delete the module, and update every consumer's POM and imports —
`project-automation` (the generator implementations and `MetadataTypeResolver`; `ActionToolAdapter`, the
watcher-side bridge, was already gone: step 3.0s removed it when the watcher kept its own `ActionTool` port),
`jcodebuddy-agent` (called `java-watch-agent` when this Do was written), and `hipster-ioc-tooling`. Update
[`module-map.md`](../doc/architecture/module-map.md) (which states the five-type leaf property) and the places
that cite codegen-api as the precedent for promoting a shared type out of `project-automation`
([`ProjectAutomationIsolationTest`](../hipster-entity/hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ProjectAutomationIsolationTest.java)
names it in a message). **`project-automation` stays private** — the SPI moving to the engine must not become
a reason for anyone to depend on a project's assistant.

**Gate:** own evidence: `MODULE` green for every module that moved to the new coordinates, the engine's own
tests green, and no `import hr.hrg.jcodebuddy.codegen.` left anywhere (a grep is the check that the move is
complete).

**Done when:** the SPI has one home, the module is gone, and nothing depends on a project's assistant to
implement a generator.

**Done 2026-10-03.** The module is gone, and the five types are the engine's: `TypeResolver`/`TypeDefinition`
in `hr.hrg.jcodebuddy.engine.query`, `CodeGenerator`/`CodeContext`/`CodeContextImpl` in
`hr.hrg.jcodebuddy.engine.codegen`. It left the root POM's `<modules>` and its managed `dependencyManagement`
entry, and [`scripts/dissolve-codegen-api.js`](../scripts/dissolve-codegen-api.js) did the move, the import
rewrite and the POM changes — so this is reproducible rather than a story about five files.

**The decision this step had to make that the record did not state: two packages, not one.** The script written
for the first attempt argued for keeping `hr.hrg.jcodebuddy.codegen`, because moving five files between modules
without changing an import is the cheapest possible migration. Two things beat that argument: the engine's own
convention is `hr.hrg.jcodebuddy.engine.<area>` (index, source, meta, fresh, query — and now codegen), and this
step's own gate is *no `import hr.hrg.jcodebuddy.codegen.` left anywhere*, which a surviving package cannot
satisfy and which would leave a module-shaped name inside a module that no longer exists. The churn was **two
consumer files**.

**What the Do named that had already changed, which is the part worth keeping.** Its consumer list was written
before 3.0s: it named `java-watch-agent` (renamed `jcodebuddy-agent` on 2026-10-03) and `ActionToolAdapter` as
things to update. `ActionToolAdapter` was deleted in 3.0s — nothing used it but its own test — so **the watcher
had no SPI dependency left to drop**: all that remained in its POM was a comment describing a dependency it no
longer declared, and rewriting that comment is the whole of the agent's part of this step. A finding is a
hypothesis with a date on it, and this one was two revisions old by the time the step ran.

**The consumer that was reaching the engine transitively.** `project-automation` imports `SourceReader`,
`TreeQueries`, `index.ContentHash` and `meta.SourceMetadata` while declaring no dependency on `jcodebuddy-core`
at all — it got them from `hipster-entity-tooling`. Its SPI dependency became an explicit `jcodebuddy-core` one
here, which is both the honest replacement and the fix for that reach. `hipster-ioc-tooling` already declared
the engine (for `GeneratedCodeMarkers`), so its SPI entry was **dropped rather than re-pointed**: a second
`<dependency>` on the same artifact is a POM smell, and Maven says so at every build.

**The gate caught something this step did not break, and the fix is not the move.** The first build failed in
`MigrationCompletenessTest` — *"the sweep is only evidence if it really walks the tree; found 243 files"*
against a floor of 250. `jcodebuddy-codegen-api` was never in that sweep, so the move could not have caused it;
the count was 243 before this step too. The cause was three names in the sweep's `MODULES` list that resolve to
no reactor module: `java-watch-agent` (renamed at 3.0s — **21 sources quietly left the sweep**, which is the
243) and `webview/jwa-sidecar` + `webview/eclipse/webview-eclipse` (written as *paths* in a list resolved by
*artifactId*, so 23 sources had never been swept at all). The count floor is what noticed, three steps late,
because the rename's own evidence was a targeted `-pl` build that never ran this test. Fixed here: the names
are artifactIds, the agent and the two webview modules are in the sweep (**287 sources**), and
`committedSources()` now asserts that *every* listed name resolves — so the next rename fails naming what
stopped resolving instead of reporting a bare count. The widened sweep passes unchanged, which is the honest
result: it was blind, not lenient.

**Evidence:** `bun scripts/mvn-jdk25.js -o -pl
:jcodebuddy-core,:hipster-ioc-tooling,:project-automation,:jcodebuddy-agent,:jcodebuddy-watch-tools -am clean
test` → **BUILD SUCCESS** (core 77 tests, `hipster-entity-tooling` 466, `project-automation` 92,
`hipster-ioc-tooling` 11, `metadata-server` 15, `metadata-mcp-server` 8); the recorded `GATE` →
**BUILD SUCCESS** (6:10) with `jcodebuddy-core` in it; `dissolve-codegen-api.js`'s own last check reports no
`hr.hrg.jcodebuddy.codegen` reference in any Java source; `LINKS` green.

**Docs in the same change:** `module-map.md` (the five-type leaf section replaced by where the SPI lives now;
`jcodebuddy-core` described as the engine it is — its "no dependencies at all" had been false since 3.0f — and
the tree and Layer tables brought to the reactor's actual shape, since the tree had not been re-derived since
the group move and `java-watch-agent` was still in the Layer 3 table), AGENTS.md § 1.1's precedent bullet,
DEC-037 decision 2 (dated execution note), DEC-W003 (amendment), DEC-036 (where the seam went), DEC-038 (both
halves done), `hipster-ioc/doc/ROADMAP.md`, `ProjectAutomationIsolationTest`'s failure message, and the two
places in this plan that still told a later step to put a type in the deleted module.

**Not this step:** nothing implements `TypeResolver` but `empty()` (3.0d), no consumer has moved onto the
engine's queries (3.0j), and the moved types are still the file-scoped seam they were. This step moved code and
deleted a module; it changed no behaviour.

### 3.0j — Move the remaining consumers onto the engine
**Who:** agent · **Size:** L

> **One addition from 3.0u (2026-10-03): the passes adopt the base cache here.** The engine's per-file cache exists
> and is proven — a warm rebuild parses nothing and produces a byte-identical table — but no pass calls it yet, and
> a cache nobody calls is a cache that lies about being used. So this step also re-points the dev-time passes at
> `MetadataCache.entryFor`/`store` (or `consume`), which is where reading a file stops being unconditional. The
> engine side of DEC-041 is done; the *adoption* is a consumer change and belongs in the step that touches
> consumers.

`metadata-server` (transport and providers), the dev-time passes (`project-automation`), the watch tools
(`java-watch-agent`), reporting (the Bun renderers that read the engine's JSON) and the future LSP sidecar are
all readers of the same facts; none of them may keep a path of its own.

**Do:** re-point each consumer at the engine, in the order 3.0a's answers allow, deleting the private paths as
they go (a consumer's own cache, its own sibling-file read, its own classpath resolution for types the engine
can answer). `metadata-server` keeps its transports and its serving shapes (DEC-W006–W009) and loses its
ownership of the model. Each consumer that moves gets its own commit, because a move that breaks it must be
visible as that move.

**Gate:** per consumer: its own build/test green plus the engine's contract test that it reads the engine
rather than a file (for the passes: the same regeneration output as before the move; for the server: the
existing RPC tests unchanged).

**Done when:** no consumer has a metadata path of its own — one model, one index, one freshness contract, many
readers.

> **The maintainer's answers, 2026-10-03 — the three questions this step's first slice needs.** (1) The server
> keeps its serving shapes: `MetadataProvider` / `CacheEntry` stay, and an engine-backed implementation goes
> behind them. (2) `jcodebuddy-meta` **may depend on `jcodebuddy-core`**, and `parse` goes through the engine's
> parse path — reversing DEC-W008's "this module has no source reader" amendment, because the reader now lives
> in the engine. (3) `WatchMetadataProvider` keeps what only the watcher knows — the files the index has no row
> for, their mtime, "what changed" — and delegates the class and type questions to the engine, which is what
> stops `listClasses()` answering with an empty list.

**Measured 2026-10-03, before starting, so the next pass does not hunt for paths that are not there.** Of the
five consumer groups this step names, two hold **no private metadata path today**:

- **reporting** — the Bun renderers read `.jcodebuddy/index/classes.json`, which *is* the engine's index
  artifact (DEC-029), and build a projection of it. That is exactly what DEC-037's "what a consumer owns" note
  permits, so there is nothing to re-point.
- **the LSP sidecar** (`webview/jwa-sidecar`) — the only `.jcodebuddy` it touches is
  `.jcodebuddy/webview/host.json`, the port it publishes for itself. It has no metadata path to delete; when it
  needs metadata it will read the engine, which is what "the future LSP sidecar" means.

So the work here is the other three, and it is not equal in size: `metadata-server` owns the model (a third
metadata shape in `MetadataProvider.CacheEntry`, and a provider whose `listClasses()` returns an empty list
because a checksum cache has never known a class name), `project-automation`'s `MetadataTypeResolver` is the
one `TypeResolver` implementation — which makes it **3.0d's** subject before it is this step's — and the watch
tools' own parse stack is **3.0n's** (the duplicate splice path). None of the three is a rename.

**3.0j-b landed 2026-10-03 — `project-automation` reads the engine, and the base cache has its first caller.**
This is also where 3.0u's cache stops being a cache nobody calls. What moved:

- **`SourceMetadataParser` builds its facts from `ClassIndex.factsOf`** — the same `TypeFacts` a table row comes
  from — so the entry's kind, its method names and its class names cannot disagree with the index the rest of the
  repository reads. It used to assemble a *third* metadata shape by hand and take the kind from
  `hipster-entity-tooling`'s `MetadataLocations`; that import is gone, and the entry now records **every** type the
  file declares (`classes`), which is what makes the next line possible.
- **`listClasses()` answers what the scan saw.** It returned two invented names — `com.example.Foo`,
  `com.example.Bar` — which is a stub that reads like data: a caller asking the server which classes exist got a
  plausible answer about a project that does not exist.
- **`hasChanged` compares the entry's own hash.** It looked for a `"checksum"` key inside the metadata map, which
  the parser never wrote, so **every file looked changed on every scan** — a cache that always misses, silently.
- **`MetadataAnalysis.scan()` is engine-backed**, and the numbers are the evidence: it used a SHA-1 checksum (this
  repository has one content identity, DEC-029 § 4, and it is not SHA-1) and parsed every file on every run. It now
  hands each file to `MetadataCache.consume` and publishes entries built from the index rows, so a second scan over
  an unchanged tree **reads nothing** — asserted, not claimed, in `MetadataAnalysisTest`.
- **Two warts the move exposed, both fixed**: `listEntries()` reported every entry twice (the provider keys each
  entry by hash *and* by path), and a rescan left the superseded content's entry behind under its old hash, so one
  file appeared several times with different identities. One entry per file now.
- **`MetadataTypeResolver` is deleted.** It had no reference anywhere — 3.0d's `IndexTypeResolver` replaced it —
  and a dead interface that documents a split nobody implements is exactly what "deleting the private paths as they
  go" means.

**3.0j-a landed the same day, and it closed DEC-W008's oldest open requirement.** The maintainer allowed this module
to depend on the engine, which did more than move a method:

- **`MetadataProvider.parse`'s default now parses.** DEC-W008 required a default that "works correctly regardless of
  whether overriding exists"; its amendment recorded why it could not have one — a default that parses needs the
  repository's one source reader, and this module deliberately had none on its classpath. The reader lives in
  `jcodebuddy-core` now, so the default is `IndexMetadataProvider.parseSource` and the requirement is **met rather
  than waived**. `MetadataParseUnsupportedException` is still reachable and still names the provider — it is a
  provider's explicit choice now, not the only option.
- **`IndexMetadataProvider` is the class-and-type surface**: entries, `get(hash)`, `hasChanged` and `listClasses()`
  from the index rows, plus `reading(indexFile, …)` for a table on disk (a missing one yields a provider that knows
  nothing rather than an exception). A static method cannot hide an inherited instance method, which is why the entry
  point is `parseSource` — the compiler caught the name before a reviewer had to.
- **`WatchMetadataProvider` delegates.** It keeps what only a watcher knows — the files the index has no row for,
  their mtime, "what changed" — and takes class and type questions from the model: `listClasses()` returns the
  engine's types, an entry's `fullClassName` comes from the model while its checksum and mtime stay the watcher's,
  and `parse` is inherited. With no delegate the answer is an empty list, which is now visibly a missing model
  rather than a missing capability.
- **The parse moved to where the contract lives.** `project-automation`'s `SourceMetadataParser` is deleted and its
  test moved with it: there is one engine-backed parse now, behind the interface, instead of two assemblies of the
  same facts. `InMemoryMetadataCacheProvider` inherits it, and its test asserts that byte for byte.
- **Three tests had to change their premise**, which is the point: a provider with no parser can no longer exist, so
  the RPC case became "a provider that *refuses* parse fails loudly, and only for `parseFile`", and a new case
  asserts the opposite — a provider overriding nothing but the cache methods still answers `parseFile`.

**3.0j is complete, closed 2026-10-07 by checking each named consumer against the tree rather than by
re-reading the slices above.** The five consumer groups, and what "moved" turned out to mean for each:

| Consumer                                   | State                                                          | Evidence |
| ------------------------------------------ | -------------------------------------------------------------- | -------- |
| `metadata-server` → `jcodebuddy-meta`      | **moved**                                                      | `IndexMetadataProvider` is the class-and-type surface and its `parseSource` calls the engine's `SourceReader.readText`, so the module parses through the engine rather than beside it. `MetadataProvider.parse`'s default delegates there, which is DEC-W008's oldest requirement **met rather than waived** |
| `project-automation` (the dev-time passes) | **moved**, and it is the base cache's only caller              | `MetadataAnalysis.scan()` reaches the engine's `MetadataCache.beside(...)`; `MetadataCache.consume`/`entryFor` are the read path, so a second scan over an unchanged tree reads nothing, asserted in `MetadataAnalysisTest` |
| the watch tools (`java-watch-agent`)       | **moved as a consequence of 3.0n**, not by a change of its own | its four generator tools (`AccessorGenerator`, `BuilderGenerator`, `ConstructorGenerator`, `ContextualAnalyzer`) import `hr.hrg.jcodebuddy.builder.*` — the renamed builder, which declares `jcodebuddy-core` for the one splice path. So the agent holds no parse stack of its own |
| reporting (the Bun renderers)              | **nothing to move**                                            | measured before starting: they read `.jcodebuddy/index/classes.json`, which *is* the engine's index artifact (DEC-029), and project it. That is what DEC-037's "what a consumer owns" permits |
| the LSP sidecar                            | **nothing to move**                                            | the only `.jcodebuddy` it touches is `.jcodebuddy/webview/host.json`, the port it publishes for itself; it has no metadata path |

**What this step did *not* need was a module-level rewrite**, and saying so is the honest result: the three
slices landed as (a) the providers and `parse` in `jcodebuddy-meta`, (b) the passes and the base cache in
`project-automation`, and (c) the watch tools, which turned out to be a *dependency* on the codegen modules
3.0n renamed rather than a metadata path of its own. **`Done when` is met**: no consumer keeps a metadata
path of its own — one model, one index, one freshness contract, many readers.

### 3.0k — Grow the recorded gate to cover the engine's contract
**Who:** agent · **Size:** S

DEC-037's second fact: `GATE_MODULES` is `jcodebuddy-core` plus the six `hipster-entity` modules, so every
step above lands in modules **no gate run covers**. The gate's own comment says why being in it is a decision
rather than an accident, which is the reason this is a step and not a line of configuration.

**Do:** add the modules that now carry the engine's contract to
[`GATE_MODULES`](../scripts/lib/gate.js) — the engine's consumers as they finish migrating — and update
`GateContractTest`'s recorded list in the same change, since it asserts the constants are still what the notes
recorded. State the cost honestly: a larger gate is a slower gate, so add the modules that hold the
*contract*, not every module that happens to compile.

**Done 2026-10-03 — six modules joined, and the test for membership is "does it hold part of the engine's
contract".** The set went from eight to fourteen, and each addition is named with its reason inside `gate.js`
itself, because that comment is where the next person asks "why is this one here?":

- **`jcodebuddy-meta`** owns the provider contract every metadata client reads, and since 3.0j its `parse` default
  is engine-backed — a change that breaks either must fail in the gate rather than in a consumer's build.
- **`jcodebuddy-meta-mcp`** is the MCP tool surface over that provider: what a client actually calls.
- **`project-automation`** is the dev-time pass and, since 3.0j, the first caller of the per-file cache
  (DEC-041) — the module where "a warm rebuild parses nothing" stops being an engine-level claim.
- **`hipster-ioc-api`** and **`hipster-ioc-tooling`** are the generator's API and its model-driven implementation
  (3.0e part two): it parses nothing now, so the tooling's 12 tests are what notices if the model stops answering
  what it used to. `hipster-ioc-api` has no test classes and is here for its build.
- **`hipster-ioc-test`** has no test classes either and is in the set **for its compile**: generated context source
  lands there, so a generator change that emits something which does not compile fails the gate. Two modules in for
  their compile is a deliberate price — a module that only builds still fails the gate when its code stops
  compiling.

**Every record of the set moved in the same change**, which is what the step asks for and what the stale "six
modules" sentence in four documents showed was necessary: `scripts/lib/gate.js` (with the reasons),
`GateContractTest`'s recorded list, and the prose in `doc/AGENTS.md`, `README.md`, the engine's README,
`scripts/mvn-jdk25.js`'s usage text and `hipster-entity-example/codebuddy.md`. Historical statements ("the six
modules", in earlier steps' records and in DEC-037/038/039) are deliberately left as written: they describe what
was true then, and rewriting them is the archaeology this plan exists to replace.

**The cost, paid and stated:** the gate's reactor went from 9 to 18 modules (the named set plus the dependencies
`-am` builds) and the run is slower. What stayed out is what does not hold the engine's contract — the `webview/`,
`watch/`, `merge-java` and `metadata-arena` families.

**What it buys, measured:** `jcodebuddy-meta`'s 24 tests, its MCP surface's 8, the ioc generator's 12, the compile
of the two ioc modules that hold generated source, and `project-automation`'s 89 now run on **every** gate
invocation instead of only when someone remembered the `-pl` incantation — which is how the MCP module's stale
"provider with no parser" test survived until 3.0j changed it.

> **One cruft note for step 9.3:** `scripts/extract-marker-leaf.js` is a one-off patcher from step 3.0l that
> embeds the old `GateContractTest` assertion string. It is harmless (nothing runs it) and it is exactly the kind
> of scratch the cleanup step should judge rather than inherit. So is an **untracked** `doc_knowledge/codebuddy.md`
> — a draft that duplicates the example module's tracked copy and wrote its links root-relative while living one
> directory down; this step repaired the five links so the LINKS check can pass, and left the file for 9.3 to
> decide about rather than deleting a working copy's document mid-step.

**Gate:** `GATE` green with the enlarged set, `GateContractTest` green, and the plan's gate line updated.

**Done when:** a later change that breaks the engine or a migrated consumer fails `bun scripts/mvn-jdk25.js`
rather than being discovered by hand.

### 3.0l — Extract the marker leaf out of `jcodebuddy-core`
**Who:** agent · **Size:** S
[DEC-038](../doc-hipster-entity/architecture/decisions/DEC-038.md) decision 1. `GeneratedCodeMarkers`,
`GeneratedCodeParser` and `GeneratedBlock` — three main types plus their test — move to a leaf of their own
(*proposed name: `jcodebuddy-generated`*), so the engine may gain OpenRewrite and Jackson while a tool that
only reads generated-region spans keeps resolving nothing heavy (DEC-035's consumers are not generators).

**Do:** create the leaf, move the three types, re-point the two dependents (`hipster-entity-tooling`,
`hipster-ioc-tooling`), add the leaf to the root POM and to `GATE_MODULES` (it is the module set that decides
what a gate run watches, and this is exactly the "being in the gate is a decision" case
[`gate.js`](../scripts/lib/gate.js) writes down), and update [`module-map.md`](../doc/architecture/module-map.md)
and DEC-035's "where the vocabulary lives" sentence if it names the module.

**Gate:** `GATE` green with the leaf in the set and `jcodebuddy-core` still green; `GateContractTest`'s
recorded list updated in the same change; `LINKS` green.

**Done when:** the engine's module can grow dependencies without giving them to the tools that only parse
markers.

**Done 2026-10-03.** The three types and their three tests are `jcodebuddy-generated`, which declares JUnit in
test scope and **no compile dependency at all** — the package imports `java.util` and nothing else, which is
what makes this a module split rather than a library extraction. The package stayed
`hr.hrg.jcodebuddy.generated`, so the whole change is a new POM, a reactor entry, a managed dependency, two
dependent POMs and one gate line: **no consumer edited an import**, and nothing outside the package referenced
the types.

**The gate line is the part worth keeping.** The leaf was already inside the gate's module set — as a package
of `jcodebuddy-core`, which is named there — so the moment it became a module of its own it would have left the
gate's *tests* while still being built, which is precisely the "reached only as a dependency" case the comment
above `GATE_MODULES` warns about. Naming it is 3.0k's principle applied to the first module that could use it.

**One thing the script refused to guess at, and the hand edit that did it.** `hipster-entity-tooling`'s POM
already carried a comment describing *this leaf* — written when the leaf was only planned — sitting above its
`jcodebuddy-core` dependency. The comment was right and the artifactId under it was wrong, so the leaf took the
comment and the engine got one of its own; `hipster-ioc-tooling`'s comment named `GeneratedCodeMarkers` as a
reason for depending on the engine, and that reason moved with it. A block-insertion script cannot make that
judgement, so it named the two files and left them to the edit.

**Evidence:** `bun scripts/mvn-jdk25.js` → **BUILD SUCCESS** with the leaf in the set and `jcodebuddy-core`
still green; `GateContractTest` green with the recorded list updated in the same change; `LINKS` green.

### 3.0m — `metadata-server` becomes `jcodebuddy-meta`
**Who:** agent · **Size:** M
[DEC-038](../doc-hipster-entity/architecture/decisions/DEC-038.md) decisions 2 and 3. The module's `groupId`
is already `hr.hrg.jcodebuddy`, but its package is `hr.hrg.watch2.server.metadata` — so the rename is the
directory, the `artifactId`, the `<name>` **and the package** (`hr.hrg.jcodebuddy.meta.*`), with the MCP
sibling following (`jcodebuddy-meta-mcp`), and with `java-watch*` staying an independent library it **depends
on** rather than absorbs.

**Do:** rename in that order (package first, so the compiler finds every reference), update every consumer
(`metadata-mcp-server`, `webview/jwa-sidecar`, `java-watch-agent`, `project-automation` — the POMs and the
imports), and keep the serving shapes and transports unchanged (DEC-W006–W009 stay true; what changes is the
module's name and, later, that its providers read the engine rather than owning the model). Re-pointing the
providers at the engine is 3.0j's work, not this step's — this step must not change behaviour.

**Gate:** own evidence (the module is outside the recorded gate): every consumer of the renamed module builds
and its tests pass, no `import hr.hrg.watch2.server.metadata` remains anywhere, and the module's own tests are
green under the new coordinates.

**Done when:** the family's names say what the modules are, and nothing has changed but names.

**Done 2026-10-03.** `jcodebuddy-meta` and `jcodebuddy-meta-mcp`, package `hr.hrg.jcodebuddy.meta.*`. The
rename is the directory, the two `artifactId`s, the two `<name>`s and the package — **no class, no method and
no wire shape moved**, so DEC-W006–W009 still describe the module exactly, and re-pointing the providers at
the engine remains 3.0j's work.

**The plan's Do named two consumers that do not exist.** It lists `webview/jwa-sidecar` and
`java-watch-agent` among the ones to update "POMs and imports"; neither imports the package nor declares the
dependency — they consume metadata over the protocol, not over the Java package. The real consumers are
`project-automation` (6 files) and `jcodebuddy-meta-mcp`. Recorded in DEC-038's 3.0m note as well, because a
rename is exactly when a wrong consumer list wastes an afternoon.

**A third thing the rename measured, and it is about this repository's own rules rather than the module.**
`MigrationCompletenessTest` — the sweep built at 3.0s to stop a rename from silently dropping sources — failed
on the first full run with *"these names are not reactor modules, so the sweep silently skipped their sources:
[metadata-server]"*. It was right: the sweep's module list is a list of **artifactIds**, and the rename
invalidated one of them. Fixed in the same change, so the check did its job rather than needing to be
remembered.

**And one about evidence hygiene.** The first `test` run after the move reported every test class twice — once
under the old package, once under the new — because `target/test-classes` still held the previous revision's
`.class` files. A `clean` run reports 15 and 8. That is F-47's trap appearing by itself: a rename is a
reliable detector for stale output, and a `test`-only verification is not evidence of a moved module.

**Evidence:** `-pl :jcodebuddy-meta,:jcodebuddy-meta-mcp clean test` → **BUILD SUCCESS**, 23 tests (15 + 8);
`-pl :project-automation -am test` → **BUILD SUCCESS** (the consumer chain, including the sweep above);
`no Java source or POM names hr.hrg.watch2.server.metadata any more`, asserted by the migration script;
`LINKS` green.

### 3.0n — Absorb `jwa-builder*` and collapse the duplicate splice path
**Who:** agent · **Size:** L

> **The maintainer's answer, 2026-10-03: rename, and collapse the duplicate.** Asked where the modules land,
> the answer was to rename them into the family — `jcodebuddy-builder-api` and `jcodebuddy-builder` as this
> step proposes — **and** to collapse the duplicate splice path, rather than the smaller option of keeping the
> `jwa-*` coordinates. So the proposed names below are decided, not suggested, and "one `SourceSplicer`
> remains" is a requirement of the change rather than a nice consequence of it.

[DEC-038](../doc-hipster-entity/architecture/decisions/DEC-038.md) decisions 4 and 5. `jwa-builder` and
`jwa-builder-api` are already modules here, named for the agent they were first written for
(`hr.hrg.watch2.builder[.api]`), and `jwa-builder` holds a **second** `SourceSplicer` (plus `LineLookup`)
against DEC-030's one splice path.

**Do:** rename them into the family (*proposed: `jcodebuddy-builder-api`* for `GenerateBuilder`, the
annotations; *proposed: `jcodebuddy-builder`* for `BuilderTransformationEngine`, `ClassMemberProcessor`,
`RecordBuilderProcessor`, and whichever of `LineLookup`/`SourceSplicer` 3.0f's classification leaves with the
generator), packages following (`hr.hrg.jcodebuddy.builder[.api]`), and **delete the duplicate splice path**:
one implementation survives and the other goes, rather than staying as a private helper the next generator
picks up by accident. Update the consumers (`java-watch-agent`, `project-automation`, `webview/jwa-sidecar`),
and give [`code.graph.md`](../doc_knowledge/code.graph.md) and DEC-030 the surviving home of the splice —
they name `SourceSplicer` today without saying which module it lives in, which is how there came to be two.

**Gate:** own evidence (all three consumers are outside the recorded gate): each builds and its tests pass;
one `SourceSplicer` remains in the tree, and a grep proves it; the record and the guide name its home.

**Done when:** the codegen modules are part of the family, and one splice path exists instead of two.

**3.0n-a landed 2026-10-03 — the rename, by a tool instead of by hand.** `jcodebuddy/jcodebuddy-builder-api` and
`jcodebuddy/jcodebuddy-builder`, packages `hr.hrg.jcodebuddy.builder[.api]`, six POMs and the root updated, the four
consumers re-pointed (`jcodebuddy-agent`, `jcodebuddy-watch-tools`, `webview/jwa-sidecar`, `project-automation`),
and the module map, DEC-038 and DEC-W003 carrying the new names. This was the **second** module rename in two steps,
so step 3.0m's one-off script became [`scripts/rename-module.js`](../scripts/rename-module.js): parameterised, and
carrying the four guards the 3.0m run earned by failing — `git mv` moving *into* an existing directory,
`git ls-files 'pom.xml'` matching only the root POM, a check that passes over an empty tree, and `rmdir`-if-empty
rather than a recursive delete that took the new tree with it. It reports what it did, verifies that every file's
directory is its package, and refuses to pass when it verified nothing.

**Three things only a build finds**, and each is a guard or a pattern doing its job:

- `MigrationCompletenessTest`'s module list still named `jwa-builder*`, and its own message named the problem:
  *"these names are not reactor modules, so the sweep silently skipped their sources"*. A guard that notices a
  module it cannot find is why a rename cannot quietly drop a module out of a sweep.
- The two POMs' `<name>` elements said **"JWA Builder"**, not `jwa-builder`, so the mechanical
  `<name>${oldArtifactId}</name>` rule missed them and the reactor printed the old name for renamed modules — a
  reminder that a rename's prose is not shaped like its coordinates.
- `check-watch-standalone.js` needed **no** change: its workspace-artifact pattern is
  `^(jcodebuddy|hipster|metadata|jwa)-`, so the renamed modules were already inside the boundary. The check that
  would catch a `watch/` module depending on the builder still would, which is the outcome to want — a rule that
  only catches the names it was written against is a rule with a hole in it.

**3.0n-b landed 2026-10-03 — one splice path, and the grep proves it.** `grep 'class SourceSplicer'` now returns
**one** file: `jcodebuddy-core`'s `engine.source.SourceSplicer`. What the deleted copy contained was not a second
splicer so much as a second *copy of the primitives* inside a domain class: `matchingBrace` and `lineIndentBefore`
were **character-for-character identical** to the engine's, and the table in
[`code.graph.md`](../doc_knowledge/code.graph.md) listed one "Splicer" per module — which is how two came to exist,
and why that table is now one row for the engine plus a row for a **generator** that borrows it.

**The collapse went both ways, which is the useful half of having had two.** The engine's anchor scanned for the
next `{` from the declaration name, so a bodyless record (`record Point(int x, int y);`) would have made it splice
into the *next* declaration's body. The deleted copy stopped at the `;` instead, and that behaviour moved into the
survivor before the copy went. The survivor also publishes the four primitives the anchor is built from —
`bodyOpenOffset`, `bodyCloseOffset`, `matchingBrace`, `lineIndentBefore` — because that is the difference between
sharing a splice path and copying one.

**What the builder keeps is what is not a splice question**: which members a record's builder needs, how a previous
copy is recognised by shape and stripped so a pass is idempotent, and how the text is rendered. It is now
`RecordBuilderEmitter` rather than `SourceSplicer` — as `SourceSplicer` it claimed to be the generic splicer, which
is precisely how a second one survives a review that is looking for two of the same thing.

**The dependency is declared rather than implied.** `jcodebuddy-builder` never needed the engine before, so its POM
did not depend on it, and the first build after the collapse failed exactly there: Maven built the builder *before*
the engine and reported `package hr.hrg.jcodebuddy.engine.source does not exist`. The fix is the honest one for a
codegen module that splices — declare `jcodebuddy-core`, the shape `hipster-entity-tooling` already has — and its
POM says why.

**The three documents that named the splicer without saying where it lives now say it**: DEC-030 § 4 (with the
amendment that closes DEC-038 decision 5), `code.graph.md`'s writing section, and `AGENTS.md` § 2's writing bullet.
That missing home is the root cause the step named, and it is closed rather than restated.

**Evidence:** `class SourceSplicer` → one file (the engine's, now 201 lines carrying the four primitives) ·
`-pl jcodebuddy-core,jcodebuddy-builder,jcodebuddy-agent,jcodebuddy-watch-tools,webview/jwa-sidecar,project-automation,hipster-entity/hipster-entity-tooling -am test`
→ BUILD SUCCESS, 16 reactors (builder 40, agent 15, sidecar 24, tooling 466, meta 24, mcp 8, project-automation 89) ·
the recorded gate → BUILD SUCCESS · LINKS green.

### 3.0o — Group the reactor's modules (DEC-039)
**Who:** agent · **Size:** M

**Done 2026-10-02 — the four groups, before the engine work, as directed.** [DEC-039](../doc-hipster-entity/architecture/decisions/DEC-039.md)
records it: `webview/` (already), `watch/` for `java-watch*`, `hipster-entity/` for `hipster-entity*`,
`jcodebuddy/` for `jcodebuddy*` **plus `metadata-*` and `jwa*`**; a module keeps its full name inside its
group; `java-watch*` stays its own library. "Others to be decided" is honoured — `hipster-ioc*`, `merge-java`
and `project-automation` are still at the root.

- **Group 1, `hipster-entity/`** (6 modules): gate `BUILD SUCCESS` (5:26 in tooling), links green.
- **Groups 2 and 3, `watch/` and `jcodebuddy/`** (12 modules): gate `BUILD SUCCESS` (5:59) **and** a targeted
  build of everything the gate does not cover — `BUILD SUCCESS` over 18 modules, including
  `project-automation` (92 tests), `java-watch-agent`, `metadata-server`, `metadata-mcp-server`,
  `metadata-arena`, `jwa-builder*` and `jcodebuddy-codegen-api` — because `GATE_MODULES` covers only
  `jcodebuddy-core` and the six `hipster-entity` modules until 3.0k. Links green.
- **Group 4, `hipster-ioc/`** (3 modules, added by the amendment to DEC-039 the same day): the group folder
  **is** the topic folder that already existed — `hipster-ioc/doc/`, its README and roadmap stay at the root
  and the modules go inside, which is the `webview/` shape. Evidence: a targeted build of
  `hipster-ioc-api,hipster-ioc-tooling,hipster-ioc-test` — `BUILD SUCCESS`, tooling's tests included (3.8 s) —
  and links green (the only docs change is `hipster-ioc/doc/ROADMAP.md`'s two module links).
- **Group 5, `webview/`** (5 directories, DEC-039's second amendment): `intellij-jwa`, `intellij-jswa`,
  `vscode-jwa`, `vscode-jswa` and `jswa-core` are **earlier attempts at the sidecar functionality** the suite
  provides today. None is a reactor module (Kotlin/Gradle plugins, VS Code extensions, a TypeScript library),
  so only their location changed; the three with a README now say at the top that they are an earlier attempt
  and not to build on them. Their duplication is audited by **3.0p** and resolved by **3.0q** below. Links
  green.
- **The `watch/` collision was real**: a module named `watch` already occupied that path (a small
  file-copying watcher app, no dependents). It was renamed into the family — directory
  `watch/java-watch-app`, artifactId `java-watch-app` — rather than left as a module sitting on its group
  folder.
- **Two layout assumptions had to die, and that is the lasting part.** `scripts/lib/gate.js` selects modules
  by `:artifactId` now (a bare directory name stops resolving once modules are nested), and
  `CompileHarness.findRepoRoot()` no longer guesses "the nearest `pom.xml` whose directory starts with
  `hipster-entity-`" — that returned the *module* directory the day the modules moved and left ~30 test
  classes looking for a classpath that cannot exist. Its replacement is `pom.xml` **and** `.git`, plus
  `CompileHarness.moduleDir(artifactId)`, which reads the root POM's `<module>` entries because that is the
  one record of where a module lives.
- **The move is scripted and the script is kept**: [`scripts/move-to-groups.js`](../scripts/move-to-groups.js)
  (`--dry-run`, `--group`, `--all`, `--fix-links`, `--fix-segments`), idempotent, repairing only the links it
  can prove and printing the rest. It also had to clean up after its own earlier non-idempotent run, which is
  why the doubling repair is in it and in the commit history rather than hidden.

**Gate:** ✅ both gates green, the 18-module build green, links green (256 files, 1555 links).

### 3.0p — Audit the five earlier sidecar attempts against today's webview
**Who:** agent · **Size:** M

> **The maintainer's answer, 2026-10-03: all three editors are actually driven** — JetBrains, VS Code and
> Eclipse. So "worth keeping" is not "pick the winner": the shape to audit against is **one shared host with a
> thin client per editor**, and what the audit may delete is a client that duplicates another's job or an
> attempt whose capability the shared host now provides. A capability that only one editor's client can reach
> stays, and the audit says which editor it is for.


`webview/intellij-jwa`, `webview/intellij-jswa`, `webview/vscode-jwa`, `webview/vscode-jswa` and
`webview/jswa-core` are **earlier attempts at the sidecar functionality** the current suite provides
(`webview/jwa-sidecar`, `webview/core/webviewd`, `webview/kit`, and the IDE hosts). They were moved under
`webview/` and three of them now say so at the top of their README, but **nothing has compared them**: an
earlier attempt can hold a capability the current one lacks — an IDE API it drove, a transport, a protocol
detail, a UI affordance, a known-bad interaction it documented — and it can equally be weight nobody should
carry.

**Do:** for each of the five, inventory what it actually does (entry points, what it talks to, what it
implements, whether it still builds) and compare it against the current implementation **capability by
capability**, naming the current counterpart or recording that there is none. Give every capability one of
three verdicts — *duplicated* (with the counterpart named), *unique and worth keeping* (with where it
belongs), or *dead* (superseded, with what replaced it) — and give every directory one of two: *has material
to merge* or *delete*. Read-only: this step merges nothing and deletes nothing, and it cites paths rather
than describing them.

**Gate:** the audit is written down with a verdict per capability and per directory, and `LINKS` green. No
build gate — this step reads.

**Done 2026-10-03 — the audit is [`webview/doc/earlier-attempts-audit.md`](../webview/doc/earlier-attempts-audit.md),
and 3.0q has a small list instead of a worry.** The five are 50 tracked files together (219 Java lines, 204 JS, 85
TS, no Kotlin sources) and **25 of those files are committed Gradle `.gradle/` cache state** — build output that no
clone reads. Against the maintainer's shape (one shared host, one thin client per editor) the verdicts are:

| Directory               | Verdict                   | What it holds                                                              |
| ----------------------- | ------------------------- | -------------------------------------------------------------------------- |
| `webview/intellij-jwa`  | **has material to merge** | an IntelliJ **LSP registration**: `ProjectWideLspServerDescriptor` (97 lines) + provider (15) + the `plugin.xml` entry, launching `java -cp <jar> hr.hrg.watch2.sidecar.SidecarApp` |
| `webview/vscode-jwa`    | **has material to merge** | a VS Code **language client**: `LanguageClient` on `onLanguage:java` (116 lines), the `jwa.java.home` → `JAVA_HOME` → `PATH` rule, and a **`mytool/jump` notification handler** |
| `webview/intellij-jswa` | **delete**                | the same registration shape for a JS/TS sidecar that was never implemented |
| `webview/vscode-jswa`   | **delete**                | the same client shape for that sidecar                                     |
| `webview/jswa-core`     | **delete**                | an 85-line LSP sketch: declared capabilities, one hard-coded `signal` snippet, placeholder diagnostics |

**Two capabilities are genuinely unique, and neither current host has them.** `webview/webview-jetbrains` is a JCEF
tool window that registers no language server; `webview/webview-vscode` is a sidebar plus an HTTP bridge with no
language client. So the audit is not "pick the winner" — the two `*-jwa` attempts hold the *LSP client half* of the
suite, and the Zed extension is the precedent that shows the shape: register the sidecar as a language server.
The **`mytool/jump` handler** is the sharpest find: the sidecar already sends that notification
(`JwaLanguageClient.java:12`) and nothing in the suite listens for it — today's VS Code navigation goes through the
HTTP bridge instead, which is a different route.

**The audit also found why deletion is safe rather than lossy**: the two IntelliJ attempts and `vscode-jwa` carry
development paths that the 3.0o move left pointing a level too high (`../webview/jwa-sidecar/...` →
`webview/webview/...`, which does not exist), and `vscode-jwa`'s `path.join` calls never import `path`, so its
discovery branch throws. Their *capabilities* are worth taking; their *code* is not fit to copy, and the audit says
so in the row rather than leaving a reviewer to discover it.

**The two `*-jswa` clients and `jswa-core` are dead, and the record says what died**: a JS/TS sidecar sketched as an
LSP server with `.`/`:` completion triggers and a `signal` snippet, wired for VS Code, IntelliJ, Zed and Neovim in
63 lines of `INTEGRATION.md` whose instructions (an absolute `java_watch2` path, `cd vscode-jswa` at the old root)
no longer describe this tree. Deleting them is not a judgement on the idea; the idea is recorded, and git history
is the archive.

**Read-only, as the step requires**: no build was run (the step's own gate says "this step reads"), no file was
merged and nothing was deleted — 3.0q does that.

**Done when:** 3.0q has a list it can act on: what to merge into which current implementation, and what to
delete with a reason.

### 3.0q — Merge what 3.0p found worth keeping, delete the rest
**Who:** agent · **Size:** M–L, decided by 3.0p

The second half of the direction: *"steps to merge them in new if there are functionalities worth
merging."* An earlier attempt that is not merged and not deleted is the worst of both — it compiles
sometimes, misleads a reader, and is nobody's responsibility.

**Do:** for each capability 3.0p marked *unique and worth keeping*, merge it into the current
implementation it belongs to (`webview/jwa-sidecar`, `webviewd`, `kit`, or the host that needs it) as an
ordinary change with a test, in its own commit; for each directory that is superseded, **delete it** —
git history is the archive, and a directory kept "just in case" is read by nobody. Record which
directory went, what replaced it, and which capability moved where, so the deletion is a fact rather than a
gap.

**Gate:** per merge, that module's own build and tests green; per deletion, no reference to it remains (a
grep is the check) and `LINKS` green; the record names what was merged and what was removed.

**Done when:** nothing under `webview/` is both an earlier attempt and unexamined — what survived is
merged, what did not is gone with a reason.

**Done 2026-10-03, in three commits, because the audit's list had three parts.** The gate the step asks for is per
merge (that module's own build and tests) and per deletion (a grep and `LINKS`):

**Merged — VS Code (3.0q-a):** `webview/webview-vscode` now runs the sidecar as a **language server** and listens for
its `mytool/jump` notification. The rules live in `SidecarPaths.ts` as pure functions over an injected `exists`
(setting → `JAVA_HOME` → `PATH`; configured → bundled → development) and the editor half in `SidecarClient.ts`.
`npm run compile` clean under the extension's `strict` + `noUnusedLocals` tsconfig, `npm run test:unit` **11/11**
with the two pre-existing suites green in the same `&&` chain. The new test failed its own first run (it hard-coded a
POSIX path while `path.join` on Windows produces backslashes) — the code was right and the assertion was wrong,
recorded because it is the kind of thing a copied test hides.

**Merged — IntelliJ (3.0q-b):** `webview/webview-jetbrains` registers `platform.lsp.serverSupportProvider`, with the
rules in `SidecarLaunch.java` (per-project property → IDE-wide property → bundled → development under the project
base path) and the platform half in the descriptor. `gradlew test` → **BUILD SUCCESSFUL, 35 tests**:
`SidecarLaunchTest` 9, `PluginDescriptorTest` 7 (was 6 — it now guards the `com.intellij.modules.java` runtime
`<depends>`, extending the F8 lesson this plugin's README records), plus the 19 existing. No `gradle.properties`
change was needed: the LSP API is in the base distribution, so only the runtime dependency was missing. The compiler
caught one real error on the way (`Files::exists` takes a `Path`, while the injected `Exists` answers about a String).

**Deleted (3.0q-c):** all five directories — `intellij-jwa`, `intellij-jswa`, `vscode-jwa`, `vscode-jswa`,
`jswa-core` — **50 tracked files, 25 of them the committed `.gradle/` cache**, plus their untracked build output,
each absolute path verified before removal. The two that were merged go because the capability moved; the three that
were dead go because the JS/TS sidecar they served was never implemented. Git history is the archive, and the audit
carries a **LANDED** note saying exactly that.

**The references the deletion would have left dangling, all fixed:** `module-map.md`'s "Excluded from Maven Reactor"
table listed the five as current (it now lists the projects actually outside the reactor, with a paragraph naming
what happened), three broken links in `README.java_watch_2.md`, `jwa-sidecar/how_to_test.md`'s three instructions to
open the deleted extension, the webview suite plan's inventory row **and its open question 5** — now answered — the
agent plan's note that nothing implements those IDE hooks, `plan.reimplement.md`'s "not in scope" sentence, and the
plan's own **Q5**, which is settled. The remaining mentions are prose that *describes* the deletion (this record, the
audit, DEC-039's history, the merged code's javadoc): no path that no longer exists is pointed at, which is what
`LINKS` and the grep are for.

**Evidence:** VS Code — compile clean, `test:unit` 11/11 plus the two existing suites · JetBrains — `gradlew test`
35 tests, 0 failures · `LINKS` green (257 files, 1591 links) and the webview folder's own check green (220 links) ·
the deletion: 50 tracked files removed, grep clean of live references.

**Added 2026-10-02, asked for directly: annotation info in the core model.** `ClassRecord`/`TypeFacts` now carry
`List<TypeAnnotation>` — the annotation's name as written plus its arguments as source text, unevaluated — the
writer always emits the field, `DEC-029` gained the field row and a dated amendment, and
`MetadataQuery.annotationsOf`/`annotatedWith` answer for real. So the **annotation half of 3.0h's gap is closed
and 3.0r is members only**. Eight tests: four in `TypeAnnotationsTest` (extraction from real source with
arguments; `@View()`'s single `J.Empty` read as an empty parameter list rather than one empty argument; a round
trip with the field always emitted, including `"annotations": []`; and a table written before the field reading
as not-recorded) plus the rewritten `MetadataQueryTest` case (an annotation declared nowhere in these modules
still answers by name — a dependency annotation is the normal case, not a refusal; and `membersOf` still reports
its gap). Core **77 tests `BUILD SUCCESS`**.
### 3.0r — The index grows members
**Who:** agent · **Size:** M

3.0h listed six query families and could answer five. Members are the one it could not,
because a class index row records a declaration's kind, modifiers, file, checksum and relations — and nothing
about what the declaration contains or what annotates it. `MetadataQuery.membersOf`/`annotationsOf` report that
gap rather than answering "none", which is honest but not useful.

**Do:** add members to the row and to the writer, the way 3.0b added relations: a name and a
kind per member (field/method/nested type, with the signature's shape and never its body), and the annotation
type names per declaration — all as written, resolved at query time by the same rules 3.0h established. It is a
**DEC-029 format change**, so it carries its own amendment: the same always-emitted rule as relations (an absent
field is "not recorded", never "none"), the same refusal of an unknown kind, and a version bump only if the new
fields cannot be tolerated by the reader — which is what the `relations` field proved for an additive change.

**Gate:** own evidence: a round trip per new field; a member query and an annotation query answering from the
index alone; a declaration with none of either reading as a fact rather than as "not recorded"; and
`MetadataQueryTest`'s `NotCovered` case replaced by a real assertion, so the gap cannot silently reopen.

**Done when:** `membersOf` and `annotationsOf` answer from the model, and no consumer has to parse a file to ask
what a type contains or what annotates it.

**Done 2026-10-03.** `ClassRecord` and `TypeFacts` carry `members`, the writer always emits the field, the reader
refuses an unknown member kind, `MetadataQuery.membersOf(fqn)` and `membersOf(fqn, kind)` answer from the model,
and the `NotCovered` vocabulary is **deleted** — members were the last family the index could not answer, and the
plan's own gate asked for that case to be replaced rather than kept.

**Where the richer row was chosen over the plan's minimum, and why.** The plan says "a name and a kind per
member". The maintainer's answer on 2026-10-03 was to record **return types, modifiers and per-member
annotations** as well, and the deciding argument is step 3.0d: `TypeDefinition` carries a type's fields *and
their types*, so a member row without a field's type would leave the resolver unable to answer and force it back
to parsing the file — the second parse this whole sequence exists to delete. The extra fields ride the same
always-emitted rule and the same closed vocabulary as the rest of the row.

**Four limits, recorded rather than discovered later** (all in DEC-029's amendment): no bodies or initialisers;
**no enum constants** (they are `J.EnumValue` statements, not variables — a consumer that needs them asks the
parse path); no parameter annotations; and no name resolution, so a member's type is the spelling the source
used. Each is a stated gap, because the failure mode here is a consumer reading an absence as a "no".

**Two test expectations were wrong before the code was**, and both are worth the sentence: the writer was
asserted to filter modifiers, when it faithfully writes what the record holds (the keyword filter belongs to the
extractor, and the test now says so); and a field-kind query was asserted to return one type when the fixture
declares two fields. Neither was a code defect — but a test that passes for the wrong reason is the thing this
repository keeps finding later.

**Evidence:** `-pl :jcodebuddy-core clean test` → **BUILD SUCCESS, 34 tests** (was 28; `TypeMembersTest` adds six:
extraction of fields/methods/constructors/nested with types, modifiers and annotations; a parameterless method
as *no* parameters rather than one `J.Empty`; a multi-variable declaration as two members; the round trip with
the field always emitted; an older table reading as not-recorded; and an unknown kind making the reader refuse
the table). The recorded gate follows in the same change.

### 3.0s — `java-watch*` is standalone: no Jackson, no OpenRewrite, nothing else from this workspace
**Who:** agent · **Size:** M

The maintainer's rule, given 2026-10-02 while 3.0i was being attempted:

> java-watch* must not know about jackson or openrewrite or anything else from this workspace

This is what 3.0i collided with, and the collision was real rather than formal: `java-watch-agent`'s
`ActionToolAdapter implements CodeGenerator<List<FileChange>>`, so **the SPI module existed to keep the watcher
compilable without `project-automation`** (AGENTS.md § 1.1 records exactly that promotion). Dissolving the SPI
into the engine would have made the watcher depend on OpenRewrite and Jackson in order to compile — so the
boundary had to be fixed first, and this step fixes it.

**Do:** make the check pass. It has been written and it measures **17 violations on 2026-10-02**, which is the
list this step works through:

| Module                  | Violation                                                                              | The shape of the fix |
| ----------------------- | -------------------------------------------------------------------------------------- | -------------------- |
| `java-watch-agent`      | depends on `jcodebuddy-codegen-api`, and `ActionToolAdapter` (main) + `ToolSeamTest` (test) import `hr.hrg.jcodebuddy.codegen.*` | the watcher keeps **its own** port (`ActionTool` already is one); the adapter that bridges it to a JCodeBuddy SPI moves into a module that legitimately depends on both (`project-automation`, the project's own dev-time assistant) |
| `java-watch-agent`      | depends on `jwa-builder-api` and `jwa-builder`                                         | find what uses them — the JWA builder is absorbed into `jcodebuddy/` (DEC-038), so a use in the watcher is either a bridge to move out or a leftover to delete, and the record says which |
| `java-watch-agent`      | Jackson: the dependency, plus imports in `AuditManager`, `CommandServer`, `WatchAgent` | the watcher writes its own audit JSON; either hand-rolled writing (it is a small, fixed document) or a JSON library the watcher chooses for itself — what it may not do is inherit this workspace's Jackson |
| `java-watch-run-sample` | depends on Jackson                                                                     | a sample module: either its JSON use is removed or it depends on a library it declares itself, with the same rule |

**Progress 2026-10-03 — the SPI is gone from the watcher, 17 violations down to 13.** `ActionToolAdapter` was
**deleted, not moved**, and that is the honest reading the check made possible: nothing in `java-watch-agent`'s
main code used it — its only user was its own test — so relocating dead code into `project-automation` would
have preserved a bridge to nothing. The three adapter cases went with it and the seam tests the watcher
genuinely owns (`ActionTool`, `SimpleToolContext`) stay: 15 tests green, `java-watch-agent` `BUILD SUCCESS`.
The `jcodebuddy-codegen-api` dependency went with the class. **This is what unblocks 3.0i**: with the watcher no
longer implementing the SPI, dissolving the five types into the engine gives `java-watch*` nothing.

**Progress 2026-10-03, slice 2 — the sample is clean: 13 down to 8.** `java-watch-run-sample` no longer knows
Jackson: its demo was "Jackson picks up your changes after a hot reload", and the demo's real point is that
*editing a class changes the output without a restart* — a hand-written rendering shows that exactly as well,
so `DataProcessor`/`PersonData` lost the annotations, the mapper and the round trip, `SampleMain`'s section
names the demo rather than the library, and the POM dropped `jackson-databind`. The module builds
(`BUILD SUCCESS`) and the check says 8.

**The remaining 8 are all `java-watch-agent`, and they are three slices with three different shapes** (measured,
so the next pass does not re-derive it):

1. **`AuditManager` — one write, the easy one.** Its whole Jackson use is
   `MAPPER.writeValue(manifest.json, Map.of("files", entries))` where `entries` is a `List<FileEntry>`; a
   hand-rolled writer emits the identical shape in about twenty-five lines, and the keys must match
   `FileEntry`'s components exactly because a consumer of the audit session reads that file.
2. **`WatchAgent` (config) and `CommandServer` (HTTP) — a format and a protocol decision, not a mechanical
   one.** `WatchAgent` *reads* a config file back (`readValue(...)` into `AgentConfig`), so it needs a parser,
   not just a writer; `CommandServer` reads request bodies into a `Map` and writes responses as JSON, i.e. the
   Jackson dependency there is the **wire format between this server and its clients**. Hand-rolling a small
   reader for the flat shapes actually used is feasible; changing the format is a decision about clients. This
   is the slice to decide before writing code.
**Renamed the same day, on the maintainer''s direction** ("ok to be moved and renamed if it clearly is better
fit for jcodebuddy, then the rules about java-watch* do not aply to it"): the module is now
**`jcodebuddy/jcodebuddy-agent`** — directory, artifactId and `<name>` (`JCodeBuddy Agent`), with the parent's
managed entry and `jcodebuddy-watch-tools`' dependency updated, fifteen doc paths rewritten, and
`hr.hrg.watch2.agent` left as the package (the code''s identity; a package rename is its own change). Evidence:
`-pl :jcodebuddy-agent,:jcodebuddy-watch-tools -am clean test` `BUILD SUCCESS`, 15 tests in the agent.

**Resolved 2026-10-03 — the remaining 8 were the wrong module in the wrong group.** The maintainer's read:
*"java-watch-agent may be better suited to be part of jcodebuddy family."* It is, and that is a better answer
than moving tools: the agent is **JCodeBuddy's code-action server** — `AccessorGenerator`,
`ConstructorGenerator`, `BuilderGenerator`, `ContextualAnalyzer`, `RecordBuilderGenerator`, an HTTP command
server and a codegen-session audit — and it consumes `java-watch-core`, which is the standalone library that
must stay clean. So it moved to **`jcodebuddy/jcodebuddy-agent`** (artifact unchanged, package unchanged: the
group is a fact the build reads, and the package is the code's identity), the root reactor points at the new
path, and `bun scripts/check-watch-standalone.js` now says **`OK: java-watch* is standalone`** over the six
modules left in `watch/`.

That is the honest accounting of the whole step: **17 → 0**, but by three different means, and only one of them
was "fix the leak":

| Violations                               | How they went                                                                                     |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 4 (SPI in the agent)                     | `ActionToolAdapter` **deleted** — nothing used it but its own test — which is what unblocked 3.0i |
| 5 (Jackson in the sample)                | the demo rewritten without Jackson: its point was hot-reload, not the library                     |
| 8 (Jackson + `jwa-builder` in the agent) | **the module left the group**: it was JCodeBuddy's server sitting in the watcher's library group  |

The check's scope is now stated in it and in AGENTS.md § 2: **the boundary is the `watch/` group**, so a future
module that needs a workspace artifact is moved to the group it belongs to rather than having its imports
rewritten. Recorded as a naming question rather than fixed here: the module keeps the name `java-watch-agent`
inside `jcodebuddy/`, which reads oddly for a JCodeBuddy server — renaming it (module directory, artifactId
and the group's docs, not the package) is its own small step if the name matters more than the churn.

**Progress 2026-10-03, slice 3 (started, not finished)** — the library exists; the `jwa-builder` boundary is a
*family*, not one class.** New module **`jcodebuddy/jcodebuddy-watch-tools`** (a small library the app may
depend on, per the maintainer's direction), depending on `java-watch-agent` — never the other way round — with
`RecordBuilderGenerator` as its first tool. `java-watch-agent` also lost four `Test*.java` files that were
manual-test scratch living in a library's `src/main/java`. Both modules build (`BUILD SUCCESS`).

**What the compiler then showed, which the recon had missed twice:** the agent's *other* tools —
`AccessorGenerator`, `ConstructorGenerator`, `BuilderGenerator` and `ContextualAnalyzer` — use
`ClassMemberProcessor`, which is also `jwa-builder`'s. So the jwa-builder dependency is not one bridge class
but the agent's **Java-codegen tool family**, and the honest fix is one of:

- **move the family** (`AccessorGenerator`, `ConstructorGenerator`, `BuilderGenerator`, `ContextualAnalyzer`,
  and anything else that reads Java) into `jcodebuddy-watch-tools`, leaving the watcher with its own port,
  the server, the audit and the non-Java tools. This is the coherent reading: *what to do about a Java file*
  is JCodeBuddy's work, and the watcher only knows a directory changed; or
- **invert a port**: the agent defines "read a Java member out of source" and the library implements it,
  which keeps the tools in the agent but means the watcher owns an interface only a JCodeBuddy module can
  implement.

The agent's POM was restored so the tree stays green, and the check is back to 8; this slice's value is the
library and the moved tool, not a lower count. The next pass picks one of the two options above and moves the
family in one go, because moving half of it is what the compiler rejected here.

3. **`jwa-builder*` — the bridge needs a *library* home, and that is a decision.** `WatchAgent` registers
   `new RecordBuilderGenerator()` (an `ActionTool` that drives `BuilderTransformationEngine`), and
   `TestDiscovery`/`TestStateSync` are `@GenerateBuilder` demos. The obvious home — `project-automation`,
   which already depends on `jwa-builder` — **is forbidden**: no other module may depend on a
   `project-automation` (AGENTS.md § 1.1), and the watcher's app would have to, to register the tool. So this
   is either a new small `jcodebuddy-*` library that the app may depend on, or the `record_builder` tool is
   dropped from the agent. Recorded as a decision to take, not a move to make.

**What remained (13, and the order they were taken in — all closed 2026-10-03; the check below exits 0):**

- `java-watch-agent` → `jwa-builder-api` and `jwa-builder` (2 dependency violations, plus
  `RecordBuilderGenerator` importing `BuilderTransformationEngine` and `TestDiscovery`/`TestStateSync`
  importing `GenerateBuilder`). These are *code actions* — "read a Java type out of source and complete it" —
  which is dev-time codegen, i.e. `project-automation`'s subject, and it already depends on `jwa-builder`; the
  slice is to move those generator classes (and their callers/registration) there and drop both dependencies.
- Jackson in the agent's own core: the dependency plus imports in `AuditManager` (7 uses), `WatchAgent` (2) and
  `CommandServer` (2). The watcher writes and reads its own small fixed documents, so this is hand-rolled
  writing (or a library the watcher chooses for itself) — never this workspace's Jackson.
- `java-watch-run-sample`: the dependency plus `DataProcessor`/`PersonData`/`SampleMain`, whose Jackson demo is
  the *point* of that module but cannot stay under `watch/`. Either the demo becomes self-contained or the
  sample module leaves the `watch/` group; the step's record should say which and why.

**Gate:** `bun scripts/check-watch-standalone.js` exits 0, plus each touched watch module's own build and tests
green, and `java-watch-agent` still passing whatever it tests about the seam it used to expose as
`CodeGenerator` (the port moves, its behaviour does not).

**Done when:** the watcher knows a directory changed and decides nothing about Java, and 3.0i can dissolve the
SPI into the engine without giving `java-watch*` anything.

### 3.0t — The model keeps what a consumer could ask (DEC-040)
**Who:** agent · **Size:** M

[DEC-040](../doc-hipster-entity/architecture/decisions/DEC-040.md) is a fidelity rule, and the tree owes it
several facts. It was widened by the maintainer on 2026-10-03 from "what the compiler erases" to "**anything a
consumer could ask**", so the list below is a contract rather than an erasure-only minimum. Every item is a
**DEC-029 format change** under the always-emitted rule, and each one has a consumer waiting for it:

| fact                                                                                              | why it is owed                                                                                 | who waits for it                                                                  |
| ------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| a **range** on a relation — so `ChildContext<AppContext>` is recovered by slicing the source      | type arguments are what erasure removes, and a stored copy is what goes stale; a range plus the row's checksum fails detectably (D2) | 3.0e's parent plumbing, and every interactive consumer (D6) |
| a **sealed** type's `permits` list                                                                | a class-file attribute with no dependable reflective equivalent                                | a generator that must not emit a subclass of a sealed type                        |
| a callable's **`throws` clause**                                                                  | partial at runtime, absent from most models                                                    | a generator emitting a call that must declare it (or must not)                    |
| **enum constants**, in declaration order, with their arguments                                    | a consumer projecting an enum needs them, and they are not fields in the same sense at runtime | entity generation (the enum-order and ledger work reads them today)               |
| a field's **initialiser**, as written                                                             | a class file keeps it as bytecode, which is not "as written"                                   | nobody yet — which is why it is last in the list, not why it is absent            |
| whether a callable **has a body**                                                                 | `abstract` is written on a class method but not on an interface one, so "no body" is not derivable from modifiers alone | 3.0e's factory-versus-accessor discrimination, half-answered already by `default` |
| the file's **import lines**                                                                       | D1: the generated class names the types the interface names, and no consumer should read the file for that | 3.0e part two                                                         |
| **loose matching for generics**                                                                   | the model records `List<String>`; a caller asks about `List`                                   | every query                                                                       |

**Do:** extend the row (a **range** for each relation — never its text, because a table holds pointers at the
source rather than copies of it — and `permits`; the file's imports in a **sidecar beside `mtimes.json`**,
because imports are a *file* fact like the checksum rather than a type fact), extend a member
(a `throws` list, enum constants as a member kind carrying their arguments as written, a field's initialiser, and
whether it has a body), extract each through `TreeQueries`, and give the query surface the loose generic match
the acceptance criteria name.

**Gate:** `MODULE` for `jcodebuddy-core` plus the recorded gate; and the contract test DEC-040 asks for —
**one** test that walks the format's own field list and asserts each field is emitted for an empty row and reads
as *not recorded* when absent, so a new field cannot be added without deciding that question. That test is the
mechanical form of D4, and it is the reason this step exists as a step rather than as a series of small ones.

**Done when:** every fact D1 lists round-trips, and a consumer can ask for it without reading a file.

**Part one landed 2026-10-03, with one of its three items corrected the same day.** What stands:

- **The bare name is derived, never typed in** (`TypeRelation.extendsType`/`implementsType` take the form the
  source wrote and strip the arguments), so a relation's name cannot disagree with the declaration's spelling.
- **Loose matching for generics** (`MetadataQuery.membersTyped`, over one public rule,
  `MetadataQuery.typeMatches`: equal text, equal bare name, or a qualified question against an unqualified
  declaration). A bare `List` finds `List<String>`; an unrelated name still finds nothing, because loose about
  arguments is not loose about names.
- **The D4 contract test** (`IndexFormatContractTest`): it walks the **records** — `ClassRecord`,
  `MemberRecord`, `MemberParameter`, `TypeRelation` — and asserts every component is emitted for an empty row,
  with DEC-029 § 3's two documented omissions (`generated`, `enclosing`) listed explicitly so that a third has to
  be argued for in a diff. A field added without deciding that question now fails the build.

**Corrected:** the first item was `TypeRelation.text` — the written form stored in the row, always emitted. The
maintainer removed it the same day, and the reason is the rule the step should have applied:

> it is not intended to bloat metadata with text, since metadata is reliant on being up-to-date with
> source-code, it only should carry ranges. Generators like code navigation diagrams will need line number for
> members to enable interactivity, so metadata must provide it

So the model stores **pointers, not copies**: the written form is recovered by slicing the declaration file at
the fact's range, and the row's own checksum makes that slice verifiable — a copy could disagree with the file
silently, a range cannot. The `ClassIndexTest` invariant that forbids source content in a table (which had been
narrowed to let the field through) is restored **and strengthened**. DEC-040 carries both halves: D2 rewritten
around ranges, and D6 added — a row must be able to point at the code.

**What remains, in the order of who is waiting:**

1. a sealed type's `permits` (a generator that must not emit a subclass);
2. a callable's `throws` (a generator emitting a call that must declare it);
3. enum constants (the entity work that reads them today);
4. a field's initialiser and has-a-body.

The **imports sidecar landed 2026-10-03** (`imports.json` beside `mtimes.json`) — 3.0e part two's last
prerequisite: a file's import lines, in order, as written, with `null` for *not recorded* and an empty list for
*writes none*. The lines come from the LST's `getQualid()`, and finding that took a probe: `getTypeName()` stops at
the class for a static import (losing its member) and `getAlias()` is null for every shape, so the two obvious
fields are both wrong.

**Positions landed 2026-10-03, for all four facts.** A **member** carries `line` (javac's line for its name, `-1`
when it cannot be located) and `span`; a **relation**, a **type** and an **annotation** carry their ranges; and
`SourceSlice` turns any of them into the source's own words with the row's checksum as the guard — the property a
stored copy cannot have. Four things worth keeping from the work: positions come from **one javac parse per file**
(`JavacPositions` wraps a single `JavaSyntaxCheck.inspect` and is threaded through the pass, because a per-member
ask parsed the same text once per declaration); **javac spells a constructor's name `<init>` in its method
positions while its span walk records the declared name**, so a constructor is identified by owner and arity;
**offsets index the file as read while the checksum is of the file normalised**, which is why the slice reads the
bytes as they are and normalises only to verify; and **an interface's `extends` lives in `getImplementsClause()`**
in javac just as DEC-030 records for the LST, so the clause — not the API method — decides the relation's kind.

**And the step is complete 2026-10-03: the D1 fact list has nothing left on it.** The second half landed the four
facts described above — a sealed type's `permits`, a callable's `throws`, enum constants, a field's initialiser
question and whether a callable has a body — pinned by `MemberFactsTest` as extraction *and* round trip. Four
measurements contradicted the reasonable guess and are recorded rather than discovered twice:

- **An enum's constants are one `J.EnumValueSet` statement, not one statement per constant.** `J.EnumValueSet`
  implements `Statement` and `J.EnumValue` does not, so the obvious `instanceof J.EnumValue` over a body's
  statements does not compile — the group is what carries declaration order.
- **"None" is a single `J.Empty`** for a parameter list and a `throws` clause.
- **An arbitrary expression has no faithful text form, so it is a range fact.** A field's initialiser was first
  stored as text, and an array initialiser came back as the LST's debug dump — `J.NewArray(padding=…, id=…)`, with
  a fresh UUID per parse, which made the index non-deterministic and failed the entity tooling's
  `theIndexIsDeterministic`. The row records `hasInitializer` and points at the member's `span`; an enum
  constant's arguments follow the same rule and are recovered by slicing. That is DEC-040 D2 deciding a format
  question, and it is the second time the entity tooling's determinism test caught a model change this sequence
  would otherwise have shipped.
- **The format's JSON keys are per record, not per component**: an annotation's `arguments` is written `args`
  while a member's is written `arguments`, so the contract test's key space is now `Record.component`.

**And the same test found a pre-existing bug.** javac's member walk recorded the **dotted owner chain** while
every lookup and javadoc uses the **simple name** — with one local `ownerSimpleName(...)` patch for constructors
hiding the shared cause — so a member of a *nested* type silently carried line `-1` and a null span. The walk
normalises once now and the lookup accepts either form.


### 3.0b — Class relations in the class index
**Who:** agent · **Size:** M

> **Under DEC-037 this is engine work.** The relations belong to the model the engine owns (3.0f moves it into
> `jcodebuddy-core`); until that move this step lands wherever the index lives then, and its gate is stated in
> terms of the index's own module so it stays true either way.

DEC-029's class index is real and tested: `classes.json`, one row per type, keyed by FQN, with the file's
path, its content checksum, the checksum instant, size, and the type's kind and modifiers
([`ClassRecord`](../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/index/ClassRecord.java),
[`TypeFacts`](../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/index/TypeFacts.java)).
It records **no relations**: nothing in a row says what a type extends or implements, and there is no
reverse index, so "what implements `CtxModule`" cannot be asked of it at all.

**Do:** add the relations to the index and to its writer: supertypes and implemented interfaces per type
(as resolved names, not as source text), and the **reverse** direction the consumers need (implementors,
subtypes) — with the storage decision from 3.0a in hand, and reusing the existing writer, format versioning
and validation rather than a second file. Keep the row's "one type, one row" property and keep relations
*derived* output: the index is regenerated, never hand-edited (DEC-026).

**Gate:** `GATE` green (the index lives in `hipster-entity-tooling`, so the recorded gate covers it), with
tests that a relation round-trips, that a relation to a type outside the module's sources is recorded as
the name it is (not dropped), and that an unresolvable supertype is visible as such rather than absent.

**Done when:** a consumer can answer "is A a subtype of B" and "who implements I" from the index alone,
without parsing a file.

**Landed 2026-10-02, in the engine (`jcodebuddy-core`), and the tests corrected the design twice.**

- **`TypeRelation(name, kind)`** (`engine.index`): one supertype, the name as written, and whether it came
  from an `extends` or an `implements` clause. `ClassRecord` and `TypeFacts` carry a `List<TypeRelation>`
  (`extends` first, then `implements`), `ClassRecord.sameTypeFacts` compares them — a type that starts
  implementing an interface must not look unchanged to a pass comparing only file content — and
  `ClassIndex.subtypesOf(name)` answers the reverse direction ("who implements I") from the table alone.
- **The writer always emits `relations`, even as `[]`.** `enclosing` and `generated` are omitted when they say
  nothing; this field is not, because *not recorded* must not read as *none* — a pre-3.0b table has no key at
  all, which is a gap rather than a fact. A test writes a table, takes the field back out, and reads it as
  empty to pin exactly that. An unknown `kind` makes the reader refuse the table, in the same spirit as an
  unknown format version.
- **Two corrections the tests forced, both recorded rather than smoothed over.** *(1)* `TreeQueries.supertypeNames`
  answers with the **last segment only** (`Serializable` for `java.io.Serializable`), so recording it threw the
  qualification away and made two different types one relation; the extraction now uses `supertypeTexts` and
  removes balanced `<…>` groups, so the name is the form written without its arguments — and balanced groups
  rather than a cut at the first `<`, because `Outer<T>.Inner` is two names and one argument. *(2)*
  `ClassIndex.write()` hashes every row's file and refuses a row "whose size and checksum describe a file
  nobody read", which the first version of the fixture tripped; the tests now write the sources they describe,
  which is better evidence anyway.
- **The interface trap is a test, not a comment.** `interface PersonSummary extends a.b.Person` must record
  one `EXTENDS` relation: the LST holds an interface's `extends` clause in `getImplements()` and its
  `getExtends()` is `null`, so a reader of `getExtends()` alone finds *no* supertype for the commonest
  declaration in this project (DEC-030's measured trap). A class keeps the two clauses apart in one list.
- **Evidence:** `TypeRelationsTest` (6 tests: round trip through write+read; an outside type recorded as
  written and honestly *not* a row; the interface trap; a class's two clauses; the reverse lookup, including
  that the match is the spelling used and that an unresolvable name finds no subtypes while staying visible as
  a name; and the pre-3.0b table reading as not-recorded). Core 59 tests `BUILD SUCCESS`.
- **DEC-029 amended** with the field, its three inner choices, the reverse lookup and the
  refuse-unknown-kind rule; and its two stale references to the tooling (`MetadataLocations.kindOf`,
  "vendored into the tooling") now name the engine.
- **What this step does not do:** the names are *not* resolved to FQNs, so a simple name is matched as
  written. That limit is in DEC-029, in `TypeRelation`'s javadoc and in `subtypesOf`'s, and closing it is
  search's work (3.0h) — a resolution helper here would be the confident-wrong-answer 3.0f-3 exists to
  prevent.

### 3.0c — The cache: what is cached, and what invalidates it
**Who:** agent · **Size:** M

> **Under DEC-037 this is the other half of 3.0g**, which is where it is implemented and where its answer is
> recorded: the engine publishes one freshness contract — change detection, invalidation, events — rather
> than a cache per module. Read this step's questions as the questions that contract must answer, and settle
> them once, in 3.0g, instead of here.

The checksum machinery exists (`ContentHash`/`Wyhash64`, the watch cache behind
`WatchMetadataProvider`, the step 1.3 work) and it answers "has *this file* changed". The relations from
3.0b introduce a second question the current cache cannot answer: a row for `A` depends on the file
declaring `A` **and on the types it names** — edit `B` so that `A extends B` stops resolving, and `A`'s row
is stale even though `A.java` was not touched.

**Do:** define what is cached (per-file facts, relations, the reverse index — and which of them are stored
or recomputed), the invalidation rules (checksum of the declaring file; a dependency edge for each named
relation; what happens on a rename, a delete and a new file), where the cache lives (`.jcodebuddy/`,
DEC-026) and in what format, and whether the arena-backed index (`LongToLongsIndex`, mmap) is the storage
for the relation half — which is what step 2.3's backend decision is meant to settle. Say explicitly what a
*partial* cache means: a stale index must be detectable, because a generator that reads a stale relation
and emits code is the failure this whole layer exists to prevent.

**Gate:** `GATE` green, with tests for the three transitions that matter: a file edit invalidates its own
row; an edit that changes a relation invalidates the *dependent* rows; and a deleted file removes its row
and the rows that named it (or marks them unresolved, whichever the decision says).

**Done when:** "is this index safe to generate from?" has a mechanical answer.

**Closed 2026-10-03, as answered by 3.0g — with the mapping written down rather than assumed.** This step's
four questions went to the freshness contract, and each has an answer there:

| this step asked                                | where it is answered                                                                          |
| ---------------------------------------------- | --------------------------------------------------------------------------------------------- |
| what is cached, and where                      | the class index itself (`engine.index`, DEC-029/DEC-026) plus the engine's freshness rows — 3.0g, `engine.fresh` |
| which invalidation rule applies to a file edit | the file's checksum against its row (`ContentHash`, DEC-029 § 4): an edit makes the row stale |
| a rename, a delete, a new file                 | the host reports the change and the engine answers `SAFE` / `STALE` / `UNKNOWN` for the row it holds; a deleted file's row is stale, and a new file has no row (an answer, not a silence) |
| what a *partial* cache means                   | `Freshness`'s whole point: a stale index is **detectable** before a generator reads it — the failure this step exists to prevent |

The one question that is **not** answered by a checksum — "editing `B` makes `A`'s row stale because `A` extends
`B`" — is a relation-edge invalidation, and 3.0g's contract deliberately does not guess it: it marks a row stale
from the file that declares it and reports `UNKNOWN` for what it cannot know, rather than claiming a freshness
it has not verified. Whether the engine should follow relation edges to widen that answer is a real question
with a real cost (it makes invalidation transitive, and a generator would then be waiting on a graph walk), and
nothing needs it yet: the relations are carried in the row (3.0b) so the query can answer "what implements X"
from the table, and a caller that must be certain asks the freshness contract first. Recorded here as the
**open half** of this step rather than closed with it.

### 3.0d — One implementation of `TypeResolver` over the index
**Who:** agent · **Size:** M

> **Under DEC-037 this is the engine's own resolver**, because the SPI and the index end up in one module
> (3.0f/3.0i) — which is what makes an implementation possible at all: it cannot sit next to the index it
> reads while the SPI lives above it. If 3.0f lands first, this step is a thin layer over 3.0h's queries
> rather than a second implementation.

The seam from 3.0a needs at least one real implementation before any consumer can be written against it,
and the honest one is the metadata pass: index first, sources only when the index cannot answer.

**Do:** implement the contract from 3.0a over the index from 3.0b with the cache from 3.0c — including
`knownPackages()`, which exists on `TypeResolver` today and is exactly what a generator needs to choose a
name that will resolve. A missing type must answer `null` (the interface's present contract) and the caller
must fail loudly; do not soften that into an empty definition.

**Gate:** `MODULE` for the module hosting it green — and *one generator test that runs against the resolver
without being handed a file at all*, which is the property the whole phase is about.

**Done when:** a generator can be written as a function of the project model plus its own inputs, with no
file parsing of its own.

**Done 2026-10-03.** `IndexTypeResolver` (`engine.query`) is the implementation: a projection of a row, reading
**the model and nothing else** — no file opened, no source parsed, no classpath consulted. It runs on 3.0r's
members, which is why the order above was 3.0r → 3.0d: the resolver could not have answered honestly before the
index carried a field's type, and the fallback the plan allowed ("sources only when the index cannot answer")
would have been a parse this step then had to keep.

**The seam grew two fields, and both are the gate's own argument.** `TypeDefinition` now carries `kind` and
`relations` beside its fields. Its javadoc already sanctioned the relations ("growing this seam over the index
— relations included — is plan step 3.0d"); `kind` came from the same test: a generator that cannot tell a
`record` from a `class`, or cannot see what a type implements, has to open the file to find out — the parse
this seam exists to make unnecessary. Both are snapshots of one type, and nothing in the record can reach
further, which is the property its javadoc protects.

**What it refuses, stated rather than discovered.** An unknown name answers `null` and never an empty
definition (the interface's contract, and the reason the test asserts a generator *throws* rather than emits a
fieldless constructor). A JDK type has no row here unless the modules searched index it, so `java.lang.String`
answers `null` too — reported as it is, because inventing a definition for anything with a dot in it is the
confident wrong answer this boundary exists to prevent. And a name is not resolved to an FQN: a row records the
spelling the source used (3.0h's rule), so the resolver answers for a name that *is* an FQN in the index.

**The nested-type package trap, found by writing the test.** `knownPackages()` cannot be "the FQN without its
last segment": `a.b.Outer.Inner` would then report `a.b.Outer` as a package, and offer it to a generator as a
place to put a class. The index already knows better — a member type's row names its enclosing type — so the
package is the enclosing type's package, however many segments follow. A table whose enclosing chain is broken
or cyclic answers with no package rather than a guess or a stack overflow.

**Evidence:** `-pl :jcodebuddy-core clean test` → **BUILD SUCCESS, 39 tests** (was 34;
`IndexTypeResolverTest` adds five: the generator test the gate names — a copy-constructor generator run from a
context whose directory does not exist, asserted not to exist; the unknown name answering `null` with the
generator refusing loudly; the kind and relations carried; `knownPackages()` for a member type; and an empty
index resolving nothing). The recorded gate follows in the same change.

### 3.0e — Move hipster-ioc onto the metadata contract
**Who:** agent · **Size:** M · *(shape-defining)*

> **The maintainer's answer, 2026-10-03: the generated shape MAY change.** Asked whether the committed example
> must regenerate byte-identically, the answer was to improve the shape where the model answers better,
> regenerate the example and record the change. So the byte-identical rule below is the *fallback*, not the
> requirement: what must hold is that the change is recorded and that the reason is the model rather than a
> rewrite nobody asked for. The conservative reading stays available — a byte-identical run is the strongest
> evidence that a model swap changed nothing — and the step should say which of the two it did and why.

**Measured 2026-10-03: this step's own Do was not yet possible, so it is two parts.** The Do says "replace the
sibling lookup in `ContextReader` with metadata queries (the module interface, the factories and the relations
all come from the index)". A probe of what a member row actually holds for an ioc shape — a
`default ObjectMapper buildMapper(@Circular CtxMain ctx, String name)` beside a plain `String name();` — came
back with three gaps:

| what the model needs       | what the row held                                                                 |
| -------------------------- | --------------------------------------------------------------------------------- |
| factory vs accessor        | `modifiers=[]` for **both** — `default` was not in the vocabulary, so the two were indistinguishable |
| parameter names            | absent, types only — and the emitted code writes the names                        |
| `@Circular` on a parameter | absent — and it is what decides how a bean is wired                               |

So **part one** is an engine change (DEC-029's 3.0e amendment: `default` joins the modifier vocabulary, and a
parameter becomes `{ "type", "name", "annotations" }`), landed 2026-10-03 with the engine's 40 tests and the
recorded gate. **Part two** is the rewrite this section describes. The split is recorded rather than hidden
because the finding is the useful half: the plan had assumed an index that could answer factories, and nothing
had checked.

> **Under DEC-037 this is one consumer of 3.0j.** The generator becomes a reader of the engine like every
> other consumer, which is the point of moving the engine first: hipster-ioc's own step should not have to
> negotiate what the model is.

The prototype implements the per-file `CodeGenerator` SPI and reads the sibling module interface itself.
Both go: hipster-ioc becomes a project-wide generator handed the metadata (3.0a–3.0d) and a context to
emit code for, and its passes stop pretending to be file passes.

**Do:** replace the sibling lookup in `ContextReader` with metadata queries (the module interface, the
factories and the relations all come from the index); remove `IocContextGenerator`'s `CodeGenerator`
implementation rather than re-classifying it (a project-wide generator is not a per-file generator with a
label); keep `bun scripts/ioc-gen.js` as the entry point; update `hipster-ioc/hipster-ioc-tooling/README.md`, DEC-036
§ 11 and the ROADMAP's status block to say what the generator consumes and what it no longer does.

**Part two needs two more model facts, and reading the generator is how they surfaced** (not a probe this time
— both are statements the code and its records already make):

| the model needs                          | what the engine has | why it is not enough                                                                 |
| ---------------------------------------- | ------------------- | ------------------------------------------------------------------------------------ |
| the `P` of a `ChildContext<P>` supertype | `TypeRelation.name`, and that record says *"the type argument is not part of the relation — it is source text, and this table's subject is names"* (`TypeFacts.withoutTypeArguments`) | `IocModel.Context.parentType` is what makes a child context emit its parent's accessors, so `<P>` is the fact rather than decoration: without it the generated child loses its parent |
| the interface file's import lines        | nothing — `ClassRecord` carries `path`, `size`, `checksum`, `hashCalculatedAt`, the type facts, `relations`, `annotations` and `members`, and no imports | `ContextSource` re-emits the interface's imports *and its module interface's*, so the generated class names the same types. Two honest ways out: **record the file's imports** as a file-scoped fact (like the checksum, and always emitted for the same reason), or **emit fully-qualified names** in generated source instead of copying imports — a shape change, which the maintainer's answer above permits |

Neither is a surprise about the engine's design; both are facts nobody had asked it for yet, and both are cheap
where they belong — a **range** on a relation (a pointer at the written form, never a copy of it), and one
file-scoped array. They are written down **before** the rewrite so that part two starts from a model that can
answer, rather than from a generator that reads the two things its model cannot give it — which is the failure
this whole sequence exists
to prevent.

**Gate:** `MODULE` for `hipster-ioc-tooling,hipster-ioc-test` green; the committed example still
regenerates byte-identically; and a test proves the generator never reads a source file itself (the read
seam is the metadata layer's).

**Done when:** hipster-ioc parses nothing, and the per-file SPI has no project-wide implementer.

**Part two landed 2026-10-03 — the generator reads the model, and the hand-written wiring is gone.** Concretely:

- **`ContextReader` takes a row and an index**, not a file: the context is the row carrying `@HipsterContext`, its
  beans are its no-argument methods, its module is the type a relation names (found **in the index**, so a module
  in another package is found now and an absent one is *reported* instead of yielding no factories silently), and
  the `P` of a `ChildContext<P>` is recovered by **slicing** the declaring file at the relation's range — which is
  what the ranges were built for (DEC-040 D2). Annotation arguments are text in this model, so the reader takes
  `dependencies = {A.class, B.class}` apart itself rather than the engine storing a second interpretation.
- **`IocContextGenerator` no longer implements `CodeGenerator`**, and that was not bookkeeping: the SPI asks "is
  this file yours, and what would you write for it", which forces a generator to read the file it is offered; the
  model-driven one is handed a row. What it still reads is the file it **writes**, because cooperative codegen has
  to recognise its own previous output (DEC-020) — a different question from where the facts come from, and
  conflating the two *was* the category error the tooling README described.
- **The test is the claim's proof**: after the index is built, the context's source file is deleted, and the
  generator still produces the right implementation. A generator reading its input file cannot do that.
- **`hipster-ioc-test` carries no hand-written wiring.** `CtxMainModule.default buildMapper()` was the last of it,
  and the regenerated `CtxMainImpl` constructs the bean directly. Regenerating the committed example after the
  rewrite produced the **same bytes except that one line** — the strongest evidence available that reading the
  model says what reading the source said.
- **A stale default fell out of it**: `bun scripts/ioc-gen.js` still pointed at the pre-DEC-039 paths
  (`hipster-ioc-test/`, `hipster-ioc-tooling/`), and its own "no such source root" message is what named the bug. A
  guard caught the rest: `GeneratorGuardTest` refused the `imports.json` sidecar appearing in a committed index
  directory — the guard working as designed — and its expectation now names all three files and why each is there.

### 3.0u — The base layer: per-file metadata with its own hash (DEC-041)
**Who:** agent · **Size:** M

**The direction, kept as given:** *"metadata needs to define a strict subset where all of it is derived from file
alone, so that result can be serialized in cache per Java source file, so rebuilding the full metadata cache and
extended metadata per Java File can reuse it. That base set must include a hash so we know it is up-to date with
source"* — recorded as [DEC-041](../doc-hipster-entity/architecture/decisions/DEC-041.md), which names the two
layers, closes the base set, requires the hash on every entry, and makes the class index a **projection** of those
entries. The engine already produces the facts (`TypeFacts` + the import lines, one parse per file); what is
missing is that they are a stored unit and that nothing parses a file whose entry is current.

**3.0u-a landed 2026-10-03 — the entry, and the criterion that matters most.** `FileMetadata` is a file's base set
(path, checksum, size, generated, its types as table rows, its imports) with a deterministic JSON document, no
source text, and a `describes(checksum)` answering the only reuse question there is. The table's own row writer and
reader are now shared with it (`ClassIndex.appendRow`/`readRow` are package-private), because a second row shape
for the cache would mean two writers, two readers and a class of bug where they drift; the cost — one file's entry
repeating its path, checksum and size on each row — is the cheaper mistake, and the entry's header stays the single
authority for reuse. **DEC-041's first acceptance criterion is met and asserted**: an entry for file `A` is
byte-identical whether file `B` exists, is edited, or is deleted, which is the check that fails the moment a
cross-file fact enters the base layer. Three more are met in the small: no source text, a stale entry refused by
its hash, and the document deterministic across four rebuilds of the same file.

**3.0u-b landed 2026-10-03 — the store and the consumption, so the criteria became measurements.** `MetadataCache`
keeps one entry per source file under `.jcodebuddy/cache/`, names it by the file's own relative path so a human can
find it, decides reuse with the content hash alone, and counts hits, misses and entries written so "a warm rebuild
parses nothing" is something a test *reports* rather than something a comment claims. `ClassIndex.absorb` takes an
entry's rows and imports without reading the file — DEC-041 D8 in code, the table as a projection of the entries.
All five acceptance criteria now have tests: **3 hits / 0 misses** and a byte-identical table on a warm pass;
**exactly one** entry rewritten per edit; an **identical table** after deleting the whole cache; a stale entry
refused by its hash; and an entry that never moves because a neighbour did.

**One boundary, stated rather than implied:** the *engine* can do all of this and proves it; the passes adopt the
cache in **3.0j**, which is where a consumer stops reading files it does not have to. A cache nobody calls is still
a cache, and this plan should not read as though the pass were already using it.

**Work, in order:**

1. A `FileMetadata` entry model: the file's path, `checksum`, `size`, `generated`, its `TypeFacts` and its import
   lines — the base set DEC-041 D2 enumerates, and nothing else.
2. Its writer and reader under the module's derived `.jcodebuddy/cache/` (DEC-026), one entry per Java source
   file, versioned like the table, and a missing or corrupt entry recomputed rather than fatal (D7).
3. `ClassIndex` consuming entries: a pass whose entries are all current **parses nothing** and produces its table
   from them, byte-identical to a cold rebuild (D4, D8).
4. The invariant test DEC-041 D6 makes measurable: **an entry for file `A` is byte-identical whether file `B`
   exists, is edited, or is deleted** — the check that fails the moment a cross-file fact is added to the base set.
5. The three remaining acceptance criteria as tests: one edit recomputes one entry and leaves the others
   byte-identical; a stale entry is never used (mutate the file under it and the answer is the new content);
   deleting the whole cache changes no answer, only the time.

**Where it sits:** independent of 3.0e, and before it in value — 3.0e part two needs the facts, and this decides
*where a fact lives*. Both land before 3.0j puts consumers onto the engine, because a consumer's own cache can
then be per file with the hash as its invalidation key.

**Gate:** `MODULE` for `jcodebuddy-core` green; the warm-rebuild test proving no parse; entry independence; and
`md-fix-tables` + LINKS after the documents.

**Done when:** a second pass over an unchanged tree parses nothing, one edit recomputes one file's worth of work,
and no entry in the cache can change because of a file other than the one it is about.

### 3.1 — The hipster-ioc ADR
**Who:** agent · **Size:** S

**Done 2026-10-01 — [DEC-036](../doc-hipster-entity/architecture/decisions/DEC-036.md), status `Trial`.**

- The number the `.kilo` plan asked for (`DEC-W008`) was already taken by the metadata no-cache decision, so
  the record is **DEC-036** in the main series — the watch series covers the watch subsystem, and this is a
  code-generation decision.
- It fixes the twelve things step 3.2 must not re-decide: `<Context>Impl` committed under the consuming
  module's `src/main/java` with DEC-035's file marker; the interface implemented directly (no proxy, no
  reflection, no name→bean registry, per DEC-019); creation order derived from `dependencies()` and each
  bean's type, with the same order driving `init*`; dependency-free beans in field initializers; `@Circular`
  as a settable field and an **unmarked cycle as a `circular_dependency_unmarked` diagnostic**; parent
  plumbing for `ChildContext<P>`; `impl` suppressing creation code; `Supplier`/`DynamicResource`/
  `StableValuePolyfill` as the **opt-in** lazy seam with eager creation as the default; region markers only
  above the design document's thresholds (fields > 5, exposed beans > 3, factory methods > 3) with DEC-020
  preservation and the `enabled:false` freeze from step 1.1; the graph as JSON under
  `.jcodebuddy/metadata/` and the page rendered by Bun (DEC-027/029); no `project-automation` dependency
  (§ 1.1) and no second Java parser (DEC-030); and the `<Context>` → `<Context>Impl` naming contract for
  DEC-022.
- Registered in [`decisions/README.md`](../doc-hipster-entity/architecture/decisions/README.md) with its
  Notes row, and cross-linked from
  [`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md) and
  [`architecture-and-design.md`](../hipster-ioc/doc/architecture-and-design.md) — the second says explicitly
  that DEC-036 is the decision and the design document is the intent behind it, so a later reader does not
  have to guess which wins.

**Gate:** ✅ `LINKS` green (252 files, 1459 links) and the record is listed in the index.

**Done when:** ✅ done — step 3.2 implements against this record.

### 3.2 — `CodeGenerator<GeneratedContext>` and dependency-graph computation
**Who:** agent · **Size:** L

**Done 2026-10-01 — the empty module has sources, and they generate Java the JDK accepts.**

- **`ContextReader`** reads one `@HipsterContext` interface through the LST (`SourceReader`/`TreeQueries`,
  DEC-030), into a small model (`IocModel`): beans (abstract no-arg accessors), factories (`default
  build<Bean>(...)` on the module interface, with their parameters and `@Circular` marks), `dependencies()`,
  the `ChildContext<P>` parent type, `impl()`, and both files' imports.
- **`DependencyOrder`** derives creation order topologically from the factory parameters, preferring
  declaration order among ready beans so the output is deterministic, and **refuses** a context whose beans
  form a cycle — leaving the file untouched — with `circular_dependency_unmarked` or
  `circular_dependency_marked_unsupported`. A factory parameter the context cannot provide becomes a
  **constructor parameter** of the generated class, so no generated code ever passes a `null`.
- **`ContextSource`** renders `<Context>Impl`: the DEC-035 file marker, creation in the computed order with
  each line naming its factory, one accessor per bean returning its field, and `getParent`/`setParent` when
  the context implements `ChildContext`.
- **`IocContextGenerator`** is the shared `CodeGenerator` (since 3.0i the engine's SPI,
  `hr.hrg.jcodebuddy.engine.codegen`), with a cheap
  `isApplicable` (a substring test, not a parse) and a `generate` that **returns text**; the text goes
  through `CooperativeCodegen.reconcileMembers`, so step 1.1's freeze and DEC-020's preservation apply to
  what this generator writes. It also refuses a `Supplier`/`DynamicResource` bean with no factory
  (`lazy_bean_needs_factory`) and a context that names its own implementation
  (`context_implementation_present`).
- **`IocGeneration`** walks a source root, writes only when the text changed, and writes the dependency graph
  to the **module** root's `.jcodebuddy/metadata/hipster-ioc/contexts.json`.
- Four new divergence kinds, registered in `DivergenceReporter.KINDS` **and** in the entity tooling's
  producer map (`ExampleDivergenceReportTest` fails on a kind nothing produces, which is the guard working).
- `IocContextGeneratorTest` (11) asserts the model, the order, the constructor-parameter case, idempotence,
  the freeze, all four refusals, the parent accessors — and **compiles the generated tree with the JDK's own
  compiler**, which is the assertion a string-matching test cannot make.
- **Three claims of DEC-036 were wrong and are amended in the record, not worked around:** the module
  interface is a **sibling file** (`CtxMainModule.java`), not a type in the context's compilation unit —
  which is why the first implementation found no factories at all; `impl` is a **context-level** attribute,
  so it suppresses the whole context rather than one bean; and a cycle marked `@Circular` **cannot** be
  generated as a settable assignment without a `Supplier` parameter, so both cycle cases are refused with
  distinct diagnostics and the `Supplier` form is named as the follow-up.
- One trap worth recording for whoever runs this module's tests: `-pl hipster-ioc-tooling` **without `-am`**
  silently uses the *installed* `hipster-entity-tooling` jar, so a change made there (step 1.1's freeze, the
  new kinds) appears absent and two tests fail for the wrong reason. With `-am` everything is green — this
  is doc/AGENTS.md's "changing a library means reinstalling it", met from the other side.

**Gate:** ✅ `MODULE` for `hipster-ioc-tooling` green — 11 tests, with the generated tree compiled by the
JDK; the entity tooling's divergence tests (`ExampleDivergenceReportTest`, `DivergenceReporterTest`,
`DivergenceKindTest`, 20 tests) green with the four new kinds.

### 3.3 — Make it runnable and documented
**Who:** agent · **Size:** M

**Done 2026-10-01 — the generator has an entry point, a README, and a committed example.**

- **`IocTool`** is the Java entry point: `--root <dir>`, `--indent <text>`, `--quiet`, `--help`, and an exit
  code that is **non-zero when any context was refused** — a cycle, a lazy bean without a factory, a named
  implementation — so a check can depend on it, while report-only divergences exit 0.
- **`bun scripts/ioc-gen.js`** is the documented way to run it (AGENTS.md § 2: the script is Bun JavaScript,
  the build step is Maven). It pins JDK 25 through `scripts/lib/toolchain.js`, compiles the module and its
  reactor dependencies, exports the classpath with `dependency:build-classpath -am` — so it runs *this
  checkout's* classes rather than whatever jar is in `~/.m2` — and forwards the generator's arguments. Its
  header records why it is not `mvn exec:java`, the same measured reasons `scripts/gen.js` gives.
- **The committed example**: `hipster-ioc/hipster-ioc-test/src/test/java/…/CtxMainImpl.java` — the real `CtxMain`, its
  package-private `CtxMainModule` sibling, and `this.mapper = buildMapper();`. `hipster-ioc-test` **compiles**
  with it, which is DEC-036's headline acceptance criterion, met on the module the decision names rather than
  only on a fixture tree. Found while doing it: the generated file inherited the marker annotation's own
  import, which it never uses — the reader now drops it.
- **`hipster-ioc/hipster-ioc-tooling/README.md`** is the module's front door: what it does, the generated layout, how to
  run it, the five refusals and why refusing is the answer, the graph's location, the **naming-contract table
  DEC-036 § 12 asked for**, and the boundaries (§ 1.1, DEC-030, DEC-026).
- **The ROADMAP now distinguishes what landed from what did not**: the generator and the graph are real; the
  browsable report page (DEC-027/029), the editor-agnostic navigation bullet, the embedded light HTTP server
  and the `@Circular` two-phase form are named as *not built*, rather than left as an implication of "Phase 2
  started".
- The `-am` trap from step 3.2 is worth repeating for this step too: `bun scripts/mvn-jdk25.js` needs
  `-DskipTests=true` (the wrapper refuses a `-D` token without `=`, by design), and a module built with
  `-pl` alone uses installed siblings.

**Gate:** ✅ `bun scripts/ioc-gen.js` runs from a clean checkout and reports `1 context, 1 implementation,
0 refused`; `MODULE` for `hipster-ioc-test` compiles with the generated file; the recorded `GATE` is green
(8 modules, `hipster-entity-tooling` 6:20), as are `GeneratorGuardTest` and `GateContractTest` (14).

**Two details worth keeping, because each cost a wrong answer before it was found:**

- The new README's relative links were written with a `../../` prefix copied from files two levels deeper,
  and the **link check** caught it — the gate did not, because the gate is the Maven set and does not read
  Markdown. Which check owns which mistake is worth knowing: `GATE` green did not mean the docs resolved.
- The graph's destination needed a **module-level** ignore. The root `.gitignore`'s `.jcodebuddy/metadata/`
  contains a slash, so it is anchored to the repository root and does **not** match
  `hipster-ioc/hipster-ioc-test/.jcodebuddy/metadata/` — the graph showed up as untracked until the module got its own
  `.jcodebuddy/.gitignore` in the shape `hipster-entity-example`'s states.

---

Every step below is real work somebody will have to do, and none of it can be written as a normal step
yet: each one's *content* is a function of a shape still moving, so its gate would have to be invented and
then rewritten. They are listed with the decision each waits on, so that "not scheduled" is visible and
specific rather than looking like an oversight. Nobody should start one before its "schedulable when" line
is true — and if one is started anyway, the first thing it must do is write that decision down.

### 3.4 — The `@Circular` two-phase form
**Who:** agent · **Size:** unknown until the shape is decided

DEC-036 § 5 was amended when the generator was built: closing a cycle needs the dependency resolved
*after* construction (a `Supplier`-style parameter, or a setter the generator may call), and neither shape
is expressible through the API today. Both cycle cases are refused with a diagnostic in the meantime —
`circular_dependency_unmarked` and `circular_dependency_marked_unsupported` — which is safe and permanent
for the unmarked case.

**Waits on:** how a lazily-resolved dependency is spelled in a context interface (`Supplier<Bean>`? a new
marker? a setter convention?) **and** the metadata contract from 3.0a — whether a type is even known to be
part of a cycle is a question about the relations index, not about one file.
**Schedulable when:** that spelling is in DEC-036 and an `@Circular` cycle generates running code.

**Done 2026-10-03 — the spelling is `@Circular Supplier<Bean>`, and the cycle runs.** The shape question was mine to
answer rather than one of the deferred decisions, and the maintainer's standing answer ("free to change the
generated shape", 2026-10-03) covers it; what the step demanded is that the answer be *written in DEC-036 first*, so
DEC-036 § 5 gained a second amendment naming the form before any code changed:

- **The annotation keeps its meaning and the JDK type supplies the mechanism.** `@Circular` says "this edge closes a
  cycle"; `Supplier<Bean>` says how — resolved after construction, by calling the context's own accessor. That was
  the gap the first amendment described, and the type is what closes it: no new marker, no setter convention, no
  change to the user's bean classes.
- **A marked edge is not an ordering edge.** The supplier is only called after the context exists, which is exactly
  what breaks the cycle: `buildA(@Circular Supplier<B> b)` with `buildB(A a)` now sorts as A then B, where before the
  two could not be sorted at all.
- **The generated argument is `() -> b()`** — a call to the accessor of the bean it supplies, so the deferral is
  ordinary source a stock IDE can navigate (DEC-019), not a lazy proxy and not reflection.
- **The two ways a marked edge can be wrong are answered rather than guessed.** `@Circular B b` (not a `Supplier`) is
  refused with the new **`circular_dependency_needs_supplier`**, which names the shape it wants; a `Supplier<X>` whose
  `X` names no bean this context builds becomes an ordinary constructor parameter, so the caller supplies it — the
  rule the generator already applied to every unresolved parameter.
- **`circular_dependency_marked_unsupported` is retired**, and the project's own guard made that a two-file change:
  `ExampleDivergenceReportTest` requires every kind in `DivergenceReporter.KINDS` to name a producer, so the retired
  kind left the vocabulary and the new one joined it in the same commit. A vocabulary entry nothing can produce is a
  promise rather than a diagnostic, which is why the bookkeeping could not be skipped.
- **`circular_dependency_unmarked` is untouched**: a cycle nobody marked is still refused, because accepting it would
  mean guessing which edge the user meant.

**The proof is that it runs, not that it renders.** The new test generates the cycle fixture, compiles it with the JDK
running the test, then loads it in a `URLClassLoader`, builds the context and asks each bean for its peer: `a.peer()`
is the very `b` the context built, and `b.peer()` is that same `a`. A string-matching test could not tell a closed
cycle from a lambda returning `null`, which is why the fixture's bean classes are public and in their own files rather
than package-private in one — that is what makes "run it" possible without reflection tricks a later reader would
weaken.

**Evidence:** `-pl hipster-ioc/hipster-ioc-tooling,hipster-entity/hipster-entity-tooling -am clean test` → BUILD
SUCCESS: `hipster-ioc-tooling` **13 tests** (one replaced by two — the generated-and-running cycle, and the
not-a-`Supplier` refusal) and `hipster-entity-tooling` 466 · the recorded gate → BUILD SUCCESS · LINKS green.

### 3.5 — `init*` methods in creation order
**Who:** agent · **Size:** S once the shape is known

DEC-036 § 3 says the creation order "drives the `init*` methods", and nothing emits any: the prototype
generates fields, a constructor and accessors. Whether `init*` means one method per bean, one method per
context, or a hook the user overrides is a shape decision, not an implementation detail — the wrong answer
puts generated code into the user's edit path.

**Waits on:** the shape of the initialisation seam, and whether it belongs in the context class at all.
**Schedulable when:** DEC-036 names the seam and the order contract it has to satisfy.

**Done 2026-10-03 — the seam is the user's own hook, and the order is proven by running it.** DEC-036 § 3
presupposed `init*` methods without saying what one is, which is why nothing emitted any; it now names the seam, and
the shape was chosen so that **no generated code lands in a user's edit path**:

- **An `init*` method is the user's hook on the module interface**, beside the `default build<Bean>(...)` factories:
  a `default` method named `init*`, returning `void`, taking exactly one parameter whose type is a bean this context
  builds. `default` is what makes it inheritable by the generated class, which is what makes the generated call
  compile — the mechanism the factories already rely on, so the seam needed no new API type.
- **The generated constructor calls it immediately after that bean's field is assigned**, so the guarantee is: a
  hook runs after its own bean exists and before anything that depends on it is created. The beans are still created
  by generated code; the initialisation is the user's, which is what "the same order drives them" meant.
- **Everything else named `init*` is left alone** — no parameter, a non-`void` return, or a parameter type that
  names no bean. The generator only ever *adds* a call to code it can place, so a user helper that happens to start
  with `init` is not swept in.
- **Two hooks for one bean are reported** as `init_hook_ambiguous` (the first is used), because which initialiser
  runs is not a question to answer by declaration order. The vocabulary guard forced the usual two-file bookkeeping
  in the same commit.
- **One boundary stated rather than discovered:** a hook for a bean whose factory takes a `@Circular Supplier<…>`
  runs before that supplier's target exists, so it must not call it — the supplier is what makes the cycle work
  later, and calling it during construction is the one thing the two-phase form cannot make safe.
- **A bean in a field initializer would have no hook position at all**, which is the second reason every bean is
  built in the constructor: DEC-036 clause 4's "SHOULD" is satisfied by an order that can be followed.

**The proof is the sequence, not the rendering.** A fixture whose factories and hooks both append to a trace gives
`[newB, initB, newA, initA]`: B is built and initialised before A is built, and each hook is handed a non-null bean
(a hook that received `null` would have recorded `initB:NULL`). A second fixture claims one bean twice and asserts
`init_hook_ambiguous`. Both contexts are compiled by the JDK running the test.

**Evidence:** `-pl hipster-ioc/hipster-ioc-tooling,hipster-entity/hipster-entity-tooling -am clean test` → BUILD
SUCCESS: `hipster-ioc-tooling` **15 tests** (13 + the two hook tests) and `hipster-entity-tooling` 466 · the recorded
gate → BUILD SUCCESS · `hipster-ioc/doc/ROADMAP.md` updated, so `init*` and `@Circular` are off the "what the
prototype does not do" list, leaving cross-context wiring (3.7) and region markers (3.6) · LINKS green.

### 3.6 — Region markers above the thresholds
**Who:** agent · **Size:** S

DEC-036 § 9 requires region markers only above thresholds (fields > 5, exposed beans > 3, factory methods
> 3) so a large context can be read and partially hand-edited. The prototype emits none, because the
thresholds only matter once the *layout* is settled — a marker pair around a layout that then changes is
churn in every generated file.

**Waits on:** the generated layout (field grouping, where the constructor sits).
**Schedulable when:** the layout stops changing and DEC-035's `region begin/end` ids are named.

**Done 2026-10-03 — three ids, their thresholds, and the one interpretation the clause left open.** DEC-035's
`region begin <id>` / `region end <id>` pair is what the generator now emits (through
`GeneratedCodeMarkers.regionBegin`/`regionEnd`, so the spelling has one home), with the ids **`fields`**,
**`accessors`** and **`factories`** — short stable tokens, as DEC-035 requires of a region id, at each section's own
indentation. The thresholds are DEC-036 § 9's: bean fields > 5, accessors > 3, creations > 3.

**The open question was which generated section "BeanFactory methods" means**, and it is answered rather than
guessed: a generated context has no factory method of its own — it *calls* the module's
`default build<Bean>(...)` factories — so the section the threshold applies to is the **creation block**, and
`factories` delimits exactly the statements that call those factories. A stricter reading would put the threshold on
a section that never exists in the file being generated, which cannot be what the clause meant; DEC-036 § 9 records
the reading.

**The counting boundary is stated too**: the thresholds count the context's beans, and the `fields` region wraps the
bean fields only — the extra-parameter fields and a `ChildContext` parent field stay outside it, because they do not
grow with the object graph the markers exist to make navigable.

**The example does not churn**: `hipster-ioc-test`'s context has one bean, so it is below every threshold and
regenerating it would be a no-op. The feature is proved by two fixtures instead — 6 beans (all three regions
present, each `begin` with its matching `end`, and the file still compiles) and 3 vs 4 beans, which pins both sides
of both thresholds.

**Evidence:** `-pl hipster-ioc/hipster-ioc-tooling -am clean test` → BUILD SUCCESS, `hipster-ioc-tooling`
**17 tests** (15 + the two region tests) · the recorded gate → BUILD SUCCESS · `hipster-ioc/doc/ROADMAP.md` updated:
region markers are off the "what the prototype does not do" list, leaving cross-context wiring (3.7) as the only
generator gap · LINKS green.

### 3.7 — Cross-context `dependencies()` and `ChildContext` parent assignment
**Who:** agent · **Size:** M

DEC-036 § 6 has `ChildContext<P>` generate parent plumbing and — "when a declared dependency is a
`ChildContext`" — the parent assignment where the child is created. The prototype generates the plumbing
(field, `getParent`, `setParent`) and no cross-context creation at all: a context's `dependencies()` are
recorded in the graph and never used to build anything.

**Waits on:** how a context names and receives another context (constructor parameter? a generated
factory? a `ChildContext` chain?), which is the same question as 3.4 for a different edge — plus the
metadata queries from 3.0a–3.0d, because "is this dependency a `ChildContext`" is a relation, and the
prototype answers it by reading whichever files happen to sit beside the context.
**Schedulable when:** the shape of context-to-context creation is decided, including who constructs the
parent.

**Done 2026-10-03, and the shape was decided rather than inherited** (DEC-036 § 6's amendment records it in the
decision, which is the order this repository builds in):

1. **A dependency is received, not constructed.** Each entry of `@HipsterContext(dependencies = …)` is a
   **constructor parameter** of the generated implementation, in declaration order and ahead of the parameters the
   context cannot answer at all, kept in a `private final` field. **The caller constructs it** — the answer to "who
   constructs the parent", chosen because the generator knows nothing about the other context's own dependencies and
   a generated factory for one would be the invisible wiring DEC-019 rejects.
2. **Its beans become resolvable.** A factory parameter whose type matches a bean of a declared dependency is
   satisfied through that context's accessor — the generated line is `this.report = buildReport(dataContext.rows());`
   — so a bean can be assembled from another context's bean. This context's own beans win a tie; a type **two**
   dependencies could answer stays the caller's, because the generator does not choose silently; and a dependency the
   index cannot resolve is reported (`dependency_context_not_indexed`) instead of being skipped, since the
   alternative is generated code that does not compile.
3. **`ChildContext` dependencies are adopted.** When the referenced context is itself a `ChildContext`, the
   constructor emits `setParent(this)` right after taking it — clause 6's "assignment where the child is created",
   for a child the generator did not create.
4. **A real bug surfaced with it and is fixed:** a parameter is source text (`List<String>`) while a bean's type comes
   from the index resolved (`java.util.List<java.lang.String>`), so comparing them literally matched nothing and the
   parameter silently degraded into an extra constructor parameter — code that compiles only if the caller happens to
   supply it. Types are now compared without package qualifiers, with that boundary recorded.

**Evidence:** `IocContextGeneratorTest` grew from 17 to 19 tests, both new ones compiling **and running** the
generated tree — one asserts the bean came from the passed-in context, the other that a received child's
`getParent()` is the receiving context at runtime — and a parameter the dependency answers is no longer also asked of
the caller. The recorded gate is green with the cache on (BUILD SUCCESS 1:06, 43 cached steps).

### 3.8 — The dependency-graph report page
**Who:** agent · **Size:** M

The graph exists as JSON (`contexts.json`, DEC-026's location) and there is no page. DEC-027/029 say the
page is a Bun renderer over that JSON, but the JSON's *shape* is the generator's model — it changed within
step 3.2 and it can change again with any of 3.4–3.7, so a renderer built now would be rewritten with it.
**Its kind is settled even while its shape is not**: this is the page DEC-027's 2026-10-01 amendment exists
for — project structure shown for navigation, relations shown as a picture — so it is a **`jsx6`** page and
its diagram uses **`jsx6`/`nodditor`**, not a self-contained vanilla file. Read jsx6's own `AGENTS.md` from
the checkout (rule § 2.9, steps 7.9–7.10) before starting. **Its renderer does not live under the root
`scripts/`**: that subtree stays dependency-free for the vanilla renderers, so this page gets a home of its
own that may declare a dependency (DEC-027's 2026-10-02 note, and DEC-036 § 10 amended to match).

**Waits on:** the graph model being settled (it is DEC-036 § 10's shape, and it follows the generated
shape).
**Schedulable when:** no step above would add or rename a graph key.

### 3.9 — Driving the generator from the dev-time pass and watch mode
**Who:** agent · **Size:** M

Today the generator runs from `bun scripts/ioc-gen.js` over a source root. DEC-036 § 11 says it is "driven
by the dev-time pass and by a CLI"; the pass and watch halves do not exist. Step 7.8 (the generator tiers)
is the precondition that *is* scheduled, because the tier is a property of what the generator reads.

**Waits on:** the generator's interface being stable — which is 3.0d/3.0e (it consumes metadata rather
than files, so the pass hands it the project model) plus step 7.8 for the file-scoped kinds, plus the shape
decision, since a watch-mode pass regenerates on save and would otherwise rewrite a prototype's output
repeatedly.
**Schedulable when:** 7.8 has landed and the emitted shape is settled.

**Part one done 2026-10-03 — the pass half.** 7.8 landed (the two kinds are a type), so the pass exists and drives the
generator: `IocRegeneration` in `project-automation` builds the model (the one step that reads sources, via
`IocGeneration.index`), hands it to each `ProjectGenerator` as a `ProjectContext`, and **writes** what they return.
The line charter § 2.8 draws is now visible in the code — `IocGeneration` was split into `render` (metadata in, code
out, writes nothing) and `write` (the pass's half) — and `IocProjectGenerator` is the first real implementation of the
engine's project-scoped kind: it decides applicability from the model (`ContextReader.contextsIn`) rather than from
the filesystem, and it cannot be handed a file. The dependency runs one way only: `project-automation` depends on
`hipster-ioc-tooling`, never the reverse (AGENTS.md § 1.1).

**The hazard the step named is answered by measurement rather than by suppression:** a second pass over an unchanged
tree writes **nothing** (`IocRegenerationTest`: one implementation on the first pass, `filesWritten == 0` on the
second while the model still holds the context), because `IocGeneration.write` writes only what differs. That is what
makes running the pass on every save safe, so the watch half can be a thin loop rather than a policy about when to
regenerate.

**Part two (the watch half) followed in the same day, and `EntityRegenerationWatcher` was the template:** a debounced `BatchedFileWatcher`
plus a content-hash check that breaks the loop its own output would otherwise create — and the IoC watcher will reuse
that mechanism rather than copy it.

**Gate (part one):** the recorded gate green with the cache on — BUILD SUCCESS in 33 s, 85 cached steps.

**Part two done 2026-10-03 — the watch half, reusing the mechanism rather than copying it.** `IocRegenerationWatcher`
runs the pass on every save (`--source`, `--module`, `--debounce`), and the loop that makes that safe is now
**shared**: `WatchedRegeneration` was extracted from `EntityRegenerationWatcher` — the debounced
`BatchedFileWatcher`, the SHA-1 snapshot taken after each pass, and the content check that recognises a batch as the
echo of the pass that wrote it — and both watchers delegate to it. That is the reuse the plan asked for, and the 3.0n
precedent: two copies of a subtle loop breaker is how they drift apart. **The extraction is verified by the class it
came from**: `EntityRegenerationWatcherTest`'s 14 tests pass unchanged, including the two that assert its own output
does not start another pass.

The IoC side is proven the same way, in `IocRegenerationWatcherTest`: an edit to a context regenerates and the
implementation appears; **a batch holding the generated implementations is recognised as that pass's echo and
ignored** (one pass, not two); a save whose bytes are unchanged is a no-op; and a deletion is acted on.
`project-automation` is 92 → **95 tests, 0 failures**, and the recorded gate is green with the cache on.

### 3.10 — Retire `hipster-ioc-test`'s hand-written context
**Who:** agent · **Size:** S–M

`hipster-ioc-test` still carries the hand-written `CtxMain`/`CtxMainModule` that the generated
`CtxMainImpl` was produced from — the whole point of the generator, per the `.kilo` integration plan, is
that a developer stops hand-writing the wiring. Doing it now would freeze the shape for a real consumer,
which is the opposite of prototyping.

**Waits on:** the shape being accepted (this is the step that would *make* it load-bearing) **and** 3.0a–3.0e
being real — a hand-written context retired in favour of a generator that still reads files by hand swaps
one hand-maintained thing for another.
**Schedulable when:** DEC-036 is `Accepted`, and the migration is the acceptance test for it.

> **The maintainer's answer, 2026-10-03 (this run's charter, item 4): the retirement is allowed.** The generated
> shape may change, and the hand-written `CtxMain`/`CtxMainModule` go once the regenerated contexts pass — which is
> the only way this step can be the acceptance test DEC-036 is waiting for rather than a second hand-maintained
> context. Both conditions the step lists are now met: DEC-036 is `Trial`→`Accepted` as 3.0e's rewrite lands, and
> 3.0a–3.0e are real (the engine answers, the generator stops reading files).

**Done 2026-10-08 — and the work was the *acceptance*, not the retirement.**

- **The hand-written pair was already gone**, and the commit says so: `997feaa` — *"3.0e part two: the ioc generator
  reads the model, and the last hand-written wiring is gone"*. `CtxMainModule.java` no longer exists anywhere in the
  tree, and `CtxMain` survives as the `@HipsterContext` **interface** the generator reads. So this step's remaining
  obligation was the one its own text names: **the migration is DEC-036's acceptance test**, and an acceptance that
  was never run is a claim.
- **The acceptance was run, and it is the strongest form of it**: `bun scripts/ioc-gen.js` reports
  `contexts read: 1`, `implementations: 0 written`, `refused: 0`, exit 0 — the generator **recognised its own
  committed output as canonical and rewrote nothing** (DEC-020's cooperative recognition), with the tree clean
  afterwards. `git status` clean *is* the assertion, because a divergence is reported rather than fatal (DEC-022), so
  the exit code alone would not prove it.
- **`hipster-ioc-test` carries no tests of its own** (`Tests run: 0`, `BUILD SUCCESS` — it is a test-support module
  with no module depending on it), so the generator run is its evidence rather than a test suite. Recorded because
  "the module's tests are green" would have been a misleading way to say it.
- **A documentation defect was found on the way and fixed**: `hipster-ioc-tooling/README.md` listed
  `CtxMainModule.java` **in its tree** while the note directly above that tree said the hand-written wiring is gone.
  A reader following the listing would have looked for a file that does not exist — which is exactly the drift this
  step was scheduled to clear. The listing is corrected, and the README now states the two-command acceptance
  (`bun scripts/ioc-gen.js`, then a clean `git status`) so the next person can reproduce it instead of trusting it.
### 3.11 — Editor-agnostic graph navigation, and the embedded host
**Who:** human decides · **Size:** unknown

The [ROADMAP](../hipster-ioc/doc/ROADMAP.md) lists "editor-agnostic context navigation" and an embedded
light HTTP server for the graph. Both were named as **not built** when this phase started, and neither has
a shape: the navigation depends on 3.8's page, and the host may not be wanted at all once that page can be
opened from the repository.

**Waits on:** 3.8, and a person deciding whether a host is worth having.
**Schedulable when:** it is either wanted (as a step with a gate) or dropped with a reason — `[-]`, which
is a real answer, not a deferral.

---

## 8. Phase 4 — merge-java: Phase 9's residue and Phase 13

### 4.1 — Replace the hardcoded `WIDENING_CHAINS` table
**Who:** agent · **Size:** S–M

**Done 2026-10-01 — the table is gone, and deleting it corrected a real defect.**

- **The JDK name list is deleted.** `PRIMITIVE_CHAINS` replaces `WIDENING_CHAINS` and holds only the
  JLS 5.1.2 widening primitive conversions, which are the language's rule and the one thing no class
  hierarchy expresses (`int` is not a subtype of `long`). Everything else is resolved:
  `ResolvedTypeReader.declaredType(...)` attributes each side's declaration against the type context,
  and `TypeUtils.isAssignableTo(wider, narrower)` answers the question — a semantic the existing
  expectations confirmed rather than one I assumed.
- **The reader grew one query, not a second parse path.** `parseInto(...)` was extracted so the method
  reading and the new declaration reading share one parser setup, one analysis context and one failure
  rule; `declaredType` returns empty for "no answer", and the caller treats that as escalate — never as
  "not assignable".
- **`requiresTypeContext()` means "a classpath is a hard requirement *for this resolver*", and this
  resolver declares `false`** — it degrades instead. This is a decision taken after reviewing the first
  cut, which had it declare `true`; the first cut's argument ("one resolver that answers differently
  depending on how it was built is worse than one that insists on being built properly") is not wrong,
  it is answered by *saying so* rather than by refusing to run. `OverloadAddConflictResolver` keeps
  `true`: comparing resolved parameter types has no weaker form, so a set containing it is still refused
  at construction — and its message now names both the diagnostic name and the **class**, because "remove
  that resolver by hand" is only actionable if the caller knows which class to remove.
- **The degraded mode is visible, not silent.** Without a context the resolver decides the JLS primitive
  conversions and the common JDK hierarchies from `JDK_SUPERTYPES` (best effort: `ArrayList → List →
  Collection → Iterable`, `HashMap → Map`, the wrapper types into `Number`/`Comparable`/`Object`), and
  every resolution it produces that way carries a warning. With a context, a declaration that cannot be
  resolved — a project type missing from the caller's classpath is `JavaType.Unknown`, **not** absent,
  which `ResolvedTypeReaderTest` now pins — escalates with its own warning *instead of* falling back to
  the table: matching simple names while a classpath is available would be the name-based guess
  resolution exists to replace. `ConflictResolution` grew `warnings` for this and `MergeReportWriter`
  writes them, so step 4.2's review page can show the basis beside the decision.
- **The fallback table is not the old table.** It is written to the invariant the old one broke: every
  chain holds only true relations, and a type with two unrelated supertypes gets two chains
  (`Integer → Number → Object` *and* `Integer → Comparable → Object`, because `Number` is not a
  `Comparable`). So the boxed-sibling defect stays fixed in both modes.
- `TypeContext`'s and `ConflictResolver`'s javadoc and `merge-java/README.md` were updated with it, and
  `merge-java/CHANGELOG.md` carries the correction as an **appended** entry — that file's own rule
  forbids editing the one written an hour earlier.
- **Follow-up delivered with it: `MergeFileTool --classpath`.** The single-file tool could not be told
  the project's classpath, so a conflict about the project's own types could only escalate. It can now
  (`--classpath <entries>`, path-separated, repeatable, jars or class directories, each checked to
  exist), added **to** the JVM classpath rather than replacing it — measured, not assumed: a parser given
  only the extra entries resolves neither the project's types nor `java.util`, so a replacing flag would
  make "resolve my types" mean "stop resolving everything else" (`TypeContext.withRuntimeClasspathAnd`,
  pinned by `ProjectClasspathResolutionTest`). The single-file variants and their classpath rule are
  tabulated in `docs/CONFLICT_FILE_TOOL.md`; none of them uses the degraded no-context mode, which stays
  what it was described as — a capability for a library caller and for future use.
- **A pre-existing veto came out of that work, and is now step 4.5.** With the classpath the type change
  *is* decided — the report shows `[TYPE_CHANGE/AUTO] 'Widget' is a widening of 'Gadget'` — and the block
  is still left, because detection emits the residual `STRUCTURAL_CHANGE` alongside the recognised
  conflict (deliberately: a residual that replaced the recognised ones once lost a mechanical import
  addition) and the tool's application rule requires exactly one resolution to claim the block. The
  classpath changes what the resolution says, which is what the fixture and the review render carry, not
  whether this tool writes the block. Pinned by a test so it cannot change unnoticed.
- **Two defects the table was hiding, both now pinned by tests.** (1) The boxed chain read the primitive
  lattice across the wrapper classes, so `widens("Long", "Integer")` was **true** — but javac rejects
  `Long x = anInteger`, and the two are siblings under `Number`; auto-adopting the `Long` declaration
  would have broken every reader assigning the result to an `Integer`. That pair now escalates.
  (2) `TreeSet` was listed against `AbstractSet`/`Set`/`Collection`/`Iterable`, so the interfaces it
  actually implements — `NavigableSet`, `SortedSet` — were absent, and a pair the resolver could have
  decided went to a human. Incompleteness was not neutral: it handed work back.
- **Generics changed meaning, deliberately.** The canonical token still drops type arguments (it is what
  the lattice compares), but resolution sees them, so `List<String>` → `Collection<String>` is a
  widening and `List<String>` → `Collection<Integer>` is **not** — a distinction the old string
  comparison could not make.
- **Docs updated with the code**: the resolver's page (its decision list, a new "What changed when the
  table was deleted" section, and the no-context example), the four live statements that called this
  table the remaining approximation (`merge-java/README.md`, `IMPLEMENTATION_PLAN.md` in two places,
  `IMPROVEMENTS_DELIVERED.md`), and an appended `CHANGELOG` entry — appended rather than edited, because
  that file's own rule says so.
- **The doc-injection gate caught the drift, not the Maven gate.** `ResolverDocsTest` failed with
  "rendered block is out of sync … re-run: npm run inject:examples" the moment the test regions changed,
  which is exactly what it exists for; `npm run inject:examples` re-rendered five blocks. Worth knowing
  which check owns which mistake, again: a green compile says nothing about a doc that quotes the code.

**Gate:** ✅ `MODULE` for `merge-java` green — **683 tests, 0 failures**, including the 68 in
`TypeChangeConflictResolverTest`, the resolver-docs suite and the whole three-way/verification set.
Which table entries became unreachable is in the commit message, as this step requires.

### 4.2 — Phase 13, step 1: the read-only per-conflict review render

**Part one landed 2026-10-03: the review page exists as jsx6 and renders a real report.** What is there:

- **`merge-java/review/`** — a package of its own, per DEC-027's 2026-10-02 note that a jsx6 page is *built* and may
  declare a dependency (the root `scripts/` subtree stays dependency-free for the vanilla renderers). It holds
  `src/index.jsx` (the page), `src_build/esbDef.js` (the documented esbuild shape plus the jsx6 aliases),
  `src_build/build.js` (inlines the report, bundles, emits one self-contained HTML) and `src_build/screenshot.js`
  (headless Chrome plus pixel measurement).
- **The writer now emits the three sides** that this step is about: `MergeReportWriter` gained
  `sides: {base, branch1, branch2}` on every resolution, from a `ConflictResolution` that already held them. Its
  javadoc records why a *report* may carry text where the class index may not (DEC-040 D2): the three states exist
  nowhere to point at, and the report exists to be read.
- **Real data, not a fixture**: `scripts/merge-report/sample-report.js` drives the same public API the module's tests
  use (`MergeBatch.using(resolver).add(path, base, branch1, branch2)` → `MergeReportWriter.write`) over three files
  built to exercise three shapes, so the page renders what the engine decided — real kinds, strategies, fix paths,
  warnings, and the `<<< MERGE-JAVA: MANUAL RESOLUTION REQUIRED >>>` marker on the cases it refuses.

**What remains before this step is done:** the vanilla `scripts/merge-report/render.js` is still the shipped
renderer, and `MergeReportWriterTest` drives it in two places (its "renderer half"). Retiring it is the rest of the
step: those tests should hold the jsx6 page to the same contract, which is a decision 4.3 informs, because the action
display may need a host that `file://` cannot provide.

**Done 2026-10-03: the vanilla renderer is retired and the step is complete.** The rest of the step was two
things, and both are done:

- **`MergeReportWriterTest` now drives the page** (its "renderer half"): it builds `review/` with a report
  the test itself wrote, and asserts one self-contained file — no external script, no stylesheet, nothing
  fetched, no map reference — that shows the file, the conflict type, the outcome, the recommendation and
  the branches' own code. Two measurement-driven corrections are recorded in that test rather than in a
  commit message: the old blanket "no `http://`" assertion was a **proxy** that the jsx6 runtime's SVG
  namespace (`createElementNS("http://www.w3.org/2000/svg")`) legitimately trips, so the check is now on
  `src`/`href` attributes — the actual property, "nothing is fetched"; and the page is built into
  `merge-java/target/`, not into a JUnit `@TempDir`, because **esbuild cannot write under `%TEMP%` in this
  environment** ("Failed to write to output file … Access is denied", with the directory created first).
- **`scripts/merge-report/render.js` is deleted**, and its last two tests with it. The renderer's own test now
  lives beside the renderer, which is the pattern this step's Gate names — `review/src_build/build.test.js`,
  four Bun tests: a self-contained page from a report, the page saying where its data came from, misuse
  (a report that cannot be read) exiting 2 with a usage line, and the build directory being the module's
  own. `bun test` is run from `merge-java/review`, because `bun test` reads `bunfig.toml` from the process
  cwd and would otherwise transform JSX with React's runtime.

**Gate, both halves:** `merge-java/review` — `bun test` **4 pass, 0 fail**; `mvn -f merge-java/pom.xml test`
— **705 tests, BUILD SUCCESS**.

**What this step does not do:** actions. The page is read-only, and 4.3's action display needs somewhere to
write — a `file://` page cannot — which is the question the next step answers.
**Who:** agent · **Size:** M

Today a resolution is reported as a count. The user sees *that* something was resolved, never *what was
decided and why* — the strategy, the explanation, the fix paths not taken, the verification outcome, the
sticky decisions replayed.

**Do:** render exactly that per conflict — base / branch 1 / branch 2 beside the resolved code, with the
resolver's explanation and fix paths — as a Bun renderer over the JSON
[`MergeReportWriter`](../merge-java/src/main/java/com/codebuddy/merge/MergeReportWriter.java) already writes, following DEC-027/029
(every link verified before it is written, output under the module's `.jcodebuddy/`). **This is a `jsx6`
page, not a vanilla one**: DEC-027's 2026-10-01 amendment classifies it as interactive/advanced — three
branches beside a resolution, an explanation, fix paths, navigation between conflicts — and 4.3 turns it
into an action display that writes decisions back, which is state. The merge *summary* page stays vanilla.
Read jsx6's own `AGENTS.md` from the checkout (rule § 2.9, steps 7.9–7.10) before writing it. Read-only
first: reviewing what the tool did must not require trusting it.

**Gate:** the renderer's own test (`merge-java/scripts/*.test.js` is the existing pattern) plus
`MODULE` for `merge-java` green.

**Done when:** a resolution can be reviewed after the fact, with the reason attached.

### 4.3 — Phase 13, step 2: the action display, writing back to `BranchConflictStore`

**Done 2026-10-03 - the action display, with the maintainer's answer on where a decision goes.** The page
collects what a reviewer accepts and exports it; a command records it into `BranchConflictStore`; the next merge
replays it. The maintainer chose that over a local endpoint ("export + cli is ok ... it is an ok separation,
could be useful"), which also keeps the page host-free: it stays one self-contained file.

- **`DecisionRecorder`** (main sources, with a `main`): reads the page's `decisions.json` - Jackson 3, now
  declared in the module's POM rather than inherited from OpenRewrite's transitive graph - and records each
  decision as a sticky replay (`STICKY_REPLAY`, kind `DEFERRED`, `sticky(true)`), which is what "remember this
  choice" means in this module. It **verifies the `signature`** the page displayed against the one it computes
  from the payload's own sides and refuses a mismatch, because the failure that prevents is a decision recorded
  against the wrong conflict.
- **The page** gained, per resolution, a fix-path picker, an editable result pre-filled from the report, and
  Accept, plus an export bar with a `decisions.json` download. The payload shape lives in `src/decisions.js`
  rather than in the component, so the contract is testable without a DOM.
- **A gotcha the gate test caught, and now documented where it bites:** the store's history root IS the branch
  directory (`<historyRoot>/decisions/`) and the resolver is handed the same path, so recording one level up
  writes successfully and is never replayed. The command prints the absolute directory it used, and the page's
  hint shows `.jcodebuddy/merge-history/<branch>`.

**Gate, both halves:** `DecisionRecorderTest` - a payload of exactly the shape the page exports is recorded and
**replayed** on the next resolve (kind `DEFERRED`, strategy `STICKY_REPLAY`, the reviewer's code); a mismatched
key is refused; an unknown schema version is refused; an empty export records nothing. `bun test` in
`merge-java/review` - **8 pass, 0 fail** (four page-build, four payload-contract). `mvn -f merge-java/pom.xml
test` - **709 tests, BUILD SUCCESS**.

**Operational follow-on, 2026-10-03 (maintainer request): the flow for ONE file, end to end, standalone.** 4.2
and 4.3 gave the page and the write-back; using them on a single stuck file still needed a chain of commands,
and the page had no input for that case at all — `MergeReportWriter` was only ever reached from a whole merge.

- **`MergeFileTool --report <path>`** writes the report the page renders, from the same analysis that decides the
  blocks. **`MergeFileTool --decisions <file.json>`** records the page's export and applies it, in one run: the
  store is read while resolving, so a decision that arrives with the run has to be recorded before the resolver
  is built. Both are on the same CLI as everything else, which is what the maintainer asked for.
- **`bun run merge-java/scripts/merge-report/review-file.js <file>`** is the one-command entry point: analyse,
  build the page, print the `file://` path, and (with `--apply-decisions`) come back to record and apply. The
  classpath comes from `--classpath` or `--classpath-from <file>` — the latter because the alternative is
  quoting a classpath in a shell, which is exactly the kind of thing § 2 keeps out of the workflow.
- **The UI gained the buttons the maintainer asked for**: each change has its own Accept, and one "Apply all
  resolved" accepts every conflict that already has an answer. The rule for that is in `src/decisions.js` and
  pinned by tests: the engine's manual marker is not an answer, an empty result is not an answer, and the bulk
  action never overwrites a hand-made decision for the same conflict.
- **Blocks nobody decided keep their markers**, so a merge continues in whatever editor the reviewer likes. The
  page is standalone by design: no webview, no server, no host — the maintainer's rule, and the reason the
  export-and-record split stays right rather than being a stopgap.

**Gate:** `MergeFileToolTest` (24 tests) proves the round trip — the report, then a decisions payload built
from that report's own facts (the shape `src/decisions.js` exports), then the same CLI recording and applying it
so the file changes. `bun test` in `merge-java/review` — **10 pass, 0 fail**. `mvn -f merge-java/pom.xml test` —
**711 tests, BUILD SUCCESS**.

**Then the concrete runs, which changed the design.** Driving the flow on a real mid-merge repository
(`sample-repo.js import-add-both --merge`) found three things:

- **A decision must be keyed on the CONFLICT, not the resolution.** A resolution that replayed a recorded decision
  holds that decision's sides and signature, so a payload built from the report's RESOLUTION named a signature the
  incoming conflict did not have — two decision files for one block, and the replay kept using the older one. The
  report now carries each conflict's own `signature` and `sides`, the two arrays are documented as index-parallel
  (they are: `resolveAll` maps one to one), and the page keys on the conflict, falling back to the resolution only
  when a report predates the field.
- **A block carrying several conflicts is never applied by the tool.** The sample's single block holds a
  `COMMENT_ADD` and a `STRUCTURAL_CHANGE`; the tool refuses to compose several conflicts into one block answer
  (`LEFT_MANUAL` / `LEFT_DEFERRED` / `LEFT_MULTIPLE_AUTOMATIC`), which is existing, deliberate behaviour. So the
  page must be read per BLOCK, not per conflict: **grouping the cards by block, and saying which conflict in a
  block still has no answer, is the next UI work** — today a reviewer can accept everything and still see the block
  unchanged without being told why.

**Done 2026-10-04 - the page reads per BLOCK.** The grouping the finding above asked for:

- **The report locates a conflict where its block is.** `MergeFileTool` detected on the block's slices, so a
  conflict's region was block-relative — a block at file lines 9–21 reported `1..1` for both of its conflicts,
  which made them indistinguishable and the page's flat list inevitable. The tool now stamps the block's own
  region (via the existing `Conflict.withRegion`), and `MergeFileToolTest` asserts a conflict is located where its
  block is and spans it.
- **The page groups by block and says what is missing**: `src/blocks.js` (a pure module, tested without a DOM)
  groups a file's resolutions by region, pairs each with the conflict it answers (the report's two arrays are
  index-parallel), counts decided against undecided — the engine's manual marker is not an answer — and flags a
  block that carries several conflicts, with `blockNote` wording the sentence: the tool will not compose several
  conflicts of one block into a single answer, so that block is finished in an editor even when every conflict in
  it is decided. `src/index.jsx` renders one section per block with its heading (`lines 9–21`), its counts and
  that sentence.
- **Verified visually** on the temporary test environment (`test-env.js`), which is what that environment is for:
  the sample block now reads `lines 9–21 · 2 conflicts · 1 decided · 1 needs you`, with both resolutions under it.

`bun test` in `merge-java/review` — **17 pass, 0 fail**. `mvn -f merge-java/pom.xml test` — **712 tests, BUILD
SUCCESS** (plus the new region assertion).- **A stale report reads exactly like a fresh one.** A second sample repository that merged cleanly left the first
  repository's report on disk, and reading it showed two conflicts for a file that had none. The entry point
  overwrites the report per file name, and the page prints the report path it used — keep both habits.
**Who:** agent · **Size:** M

The manual cases already carry machine-readable fix paths (named options, a recommendation, a
justification, an impact). The review display becomes an action display: pick a fix path, edit the
proposed result, accept — and the decision flows back into `BranchConflictStore` so it replays like any
other sticky choice on the next update.

**Gate:** a test that a choice made through the interface is replayed on the next merge run, and
`MODULE` for `merge-java` green.

**Done when:** a decided conflict stops being decided twice.

### 4.4 — Phase 13, step 3: the LLM as proposer, never applier
**Who:** agent (needs a model endpoint) · **Size:** M

The same interface can hand a conflict to an LLM and show its analysis as **one more fix path** — subject
to the same verification gate and the same human decision. The boundary in
[`DESIGN_NEVER_AUTO_RESOLVED.md`](../merge-java/DESIGN_NEVER_AUTO_RESOLVED.md) holds unchanged.

**Gate:** a test that an LLM proposal which fails the verification gate is refused and cannot be applied
without an explicit human accept.

**Done when:** the proposal path exists and cannot bypass the gate.
**Done 2026-10-04 - the proposer exists, behind the gate.**

- **`ConflictProposer`** is an optional seam: `Optional<Proposal> propose(Conflict)`, one small record, and a
  `Builder.setProposer(...)`. Without one, behaviour is exactly as before - and a test asserts that.
- **The hook covers both escalation paths** - a conflict no resolver claimed, and one a resolver escalated
  (`STRUCTURAL_CHANGE` and `API_INCOMPATIBILITY` always do; the first version of this only covered the former) -
  and only those two, so an automatic or already-replayed resolution is never second-guessed by an advisor.
- **A proposal is attached as one more fix path and NOWHERE else.** The code that attaches it cannot reach the
  resolution, which is what makes "cannot bypass the gate" structural rather than a promise; the tests assert
  the escalation stays `MANUAL`, that nothing about it becomes independently applicable (the report's own rule),
  and that the resolved code is never the proposal.
- **It is verified as if it were automatic**, because `ResolutionVerifier` gates only automatic answers - review,
  manual and replayed ones are already in front of a person - and the verdict travels with the fix path. A
  refused proposal is still shown, labelled as refused, with an impact that begins `NOT verified`: a reviewer may
  read what was suggested and why it was refused, and accepting it stays their decision. Nothing is applied on
  its own.
- **A proposer that throws keeps the escalation** and says so in a fix path, because an advisor's failure must
  not change a decision.
- **The code travels too**: the report now writes `suggestedCode` (empty for the fix paths that only describe a
  direction) and the page prefills its editor when a chosen option carries it (`codeForOption`), so accepting a
  proposal is a decision rather than a transcription.

**Gate:** `ProposerBehindTheGateTest` - five tests: a proposal is only a fix path; one the gate refuses is
refused and labelled; without a proposer nothing changes; a failing proposer keeps the escalation; an automatic
resolution is never offered a proposal. `mvn -f merge-java/pom.xml test` - **717 tests, BUILD SUCCESS**.
`bun test` in `merge-java/review` - **18 pass, 0 fail**. Written up in
[`merge-java/docs/LLM_PROPOSER.md`](../merge-java/docs/LLM_PROPOSER.md), including how a real endpoint plugs in
and the two things to decide before one does: the code under conflict leaves the machine, and a proposal is
untrusted text.

### 4.5 — The residual structural conflict should not veto a block it only partly overlaps
**Who:** agent · **Size:** S–M

Found while adding `--classpath` (step 4.1's follow-up). Detection emits the residual
`STRUCTURAL_CHANGE` **alongside** the recognised conflicts — deliberately, because a residual that
*replaced* them once lost a mechanical import addition — and `MergeFileTool` applies a block only when
**exactly one** resolution claims it. So a block whose real change is one resolvable thing (a widened
declaration, an added import) can be left `LEFT_MANUAL` by a residual that describes the same lines
without adding anything. Measured, on a two-line block whose only change is a widening that *was*
decided:

```
block 1 (lines 4-8): LEFT_MANUAL TYPE_CHANGE - [TYPE_CHANGE/AUTO] 'Widget' is a widening of 'Gadget', …
  [STRUCTURAL_CHANGE/MANUAL] StructuralChange could not resolve STRUCTURAL_CHANGE automatically;
  a reviewer must choose. Multiple conflicts claim this block and at least one is manual.
```

That is a property of the decision rule, not of the classpath: the resolution is right and the block
still waits for a human.

**Do:** decide what "claims" should mean. The candidates, in the order I would weigh them:
(a) a resolution claims a block when its region **overlaps** the block's changed lines, and a residual
that is fully *subsumed* by a recognised resolution's region is dropped from the block's decision — the
residual is still emitted and still reported, it just stops vetoing;
(b) keep the veto but let the report say which conflict caused it, so a human is not left guessing;
(c) leave it and document it (what this plan did for now — see the limitation in
[`CONFLICT_FILE_TOOL.md`](../merge-java/docs/CONFLICT_FILE_TOOL.md)).

Note what must **not** change: a residual that describes lines *no* other conflict explains must keep
vetoing, or the module loses the exact case the alongside-emission exists for.

**Gate:** `MODULE` for `merge-java` green, with tests for both halves — a subsumed residual no longer
vetoes (the type-change block applies, and the applied code compiles), and a residual covering lines no
recognised conflict explains still does.

**Done when:** `LEFT_MANUAL` for a decided conflict is explained by something other than a structural
residual with nothing of its own to say.

**Done 2026-10-07 — option (a), plus the half of it this step's own measurement needed.**

- **The veto was never in the resolver.** `StructuralChangeConflictResolver` was already right - the residual
  is `MANUAL`, and stays `MANUAL`. What left the block is `MergeFileTool.decide`, which replaces a block only
  when *exactly one* resolution claims it, and the residual is emitted alongside the recognised conflicts on
  purpose.
- **A subsumed residual is dropped from the decision and kept in the report.** `MergeFileTool.residualSubsumed`
  reads the detector's own regions - from `detected`, not from the copies whose region is stamped with the
  block's for the report, because that stamp is what a reader needs and is exactly what erases this evidence -
  and answers true when another conflict's region **covers** the residual's.
- **The base-less shape is not an afterthought: it is the shape this step measured.** Git's default `merge`
  style writes no base side (only `diff3`/`zdiff3` do), so the measured fixture runs with `baseCode = ""`,
  every region comes out **unknown**, and a region rule alone would have left that block exactly as it was.
  Without a base the residual can place no line at all, because every claim it makes is relative to a base the
  run does not have, so it is subsumed only by one **complete automatic** answer: a single other conflict that
  is `AUTO` and whose code accounts for the whole block.
- **What must not change, does not.** A residual that reaches a line no other conflict places keeps its veto -
  with a base the region test fails, and without one the type change is a `REVIEW` because both declarations
  are `Unknown` - and so does a residual whose only sibling is a review, a partial answer, or several
  automatic answers that cannot compose. Measured on fixtures: the subsumed block applies (exit 0) and the
  applied code compiles; the contested one stays `LEFT_MANUAL` (exit 1) with its markers, while its own report
  still shows the widening that *was* decided. `MIXED_CONFLICT_FILE` - an overload clash plus a residual - is
  unchanged at `LEFT_MANUAL` for the same reason, which is what makes this a rule about evidence rather than a
  loosening.
- **Step 4.1's pin is updated rather than deleted**, because it asserted the old behaviour by name: the block
  is no longer left "regardless of the classpath". Its halves now assert that the classpath changes what the
  tool *does* - with it the widening is a complete automatic answer and the block is written; without it the
  only answer is a review and the block waits. The old behaviour is recorded here rather than edited away in
  [`CONFLICT_FILE_TOOL.md`](../merge-java/docs/CONFLICT_FILE_TOOL.md), which now states the rule.

**Gate:** `MergeFileToolTest` — 26 tests, including `subsumedResidualDoesNotVeto` (the block applies, the
applied code compiles, and the residual is still in the report JSON) and `unexplainedResidualStillVetoes`.
`mvn -o -pl merge-java test` — **719 tests, 0 failures, 0 errors** (717 before this step). `bun test` in
[`merge-java/review`](../merge-java/review) — **15 pass, 3 fail**, and all three are
`EPERM: uv_spawn 'bun'` in `src_build/build.test.js`: the file sandbox refusing the piped-stdio spawn the page
build itself needs, not a result of this change.

---

#### 4B — the JetBrains port: steps 4.7–4.17

> **Why this is a lettered subsection and not `## Phase 9`.** Phases 0–8 and the `9.x` cleanup are already
> numbered, and the cleanup's own steps are cited by number across this file. Renumbering them to make room
> would break those references for a labelling gain, which [`§ 1`](#1-how-to-work-this-file)'s
> "renumber nothing" rule does not ask for. The JetBrains work is nonetheless a **phase-sized block of
> merge-java work** and is written as one; the title says so. Steps 4.7–4.17 are new and belong to Phase 4,
> placed here rather than beside 4.1–4.5 because they follow 4.5/4.6 and depend on both.

**This is not a port of an algorithm, and it must not be done as one.** The maintainer's instruction of
2026-10-07 was explicit: *"I do not want a naive port, I want analysis of the code that looks for more
useful resolution steps and resolution checks. We want to port safe merge results as such and open
suggestions as new type of resolution that needs user attention but still helps."*

So the work has **three parts, in this order**, and the order is the design:

1. **Analyse, and classify every upstream resolution step and check** as **SAFE** (the answer is a
   function of the inputs — apply it) or **SUGGESTION** (the answer is plausible and useful — offer it,
   never apply it). Done: [`JETBRAINS_PORT.md` § 5](../merge-java/docs/JETBRAINS_PORT.md) classifies
   eight resolution steps (R1–R8) and seven checks (C1–C7), with the guard named `file:line` and a reason
   per row. **A step that cannot be confidently classified is not ported yet** — that rule is what keeps
   [`DESIGN_NEVER_AUTO_RESOLVED.md`](../merge-java/DESIGN_NEVER_AUTO_RESOLVED.md) true while taking
   everything upstream offers.
2. **Port the SAFE half as such** — steps 4.8–4.13. These land as `AUTO` resolutions behind the existing
   `ResolutionVerifier`.
3. **Build the suggestion channel and move the SUGGESTION half onto it** — steps 4.14–4.17. The channel
   is a **general mechanism**, not a JetBrains feature: it is designed in
   [`merge-java/docs/SUGGESTIONS.md`](../merge-java/docs/SUGGESTIONS.md), and JetBrains' word-level
   passes are its first producer rather than its definition.

**The finding that makes part 3 necessary is in part 1.** The module already computes useful answers and
then **hides them behind a refusal**: `MethodBodyChangeConflictResolver` builds a combined method body
(line 87) and stores it in `resolvedCode` on a `REVIEW`, while `MergeFileTool.decide` returns
`LEFT_REVIEW` with a `null` replacement — so the file keeps its markers and the answer never reaches the
reviewer as *the* proposed result. The tool did the work and then threw it away. Part 3 fixes the
**presentation**, not the decision: none of those answers becomes automatic.

**The ambition, in the maintainer's words (2026-10-07):** *"this tool must be better than JetBrains,
JetBrains is the benchmark of minimum that has to be achieved where we overlap with JetBrains."* So the
port has a **floor and a point**, and the plan keeps them apart:

- **The floor is overlap parity, and it is a build gate** —
  [`JETBRAINS_PORT.md` § 5.5–5.6](../merge-java/docs/JETBRAINS_PORT.md), built in step 4.13. § 5.5 defines
  the overlap (intra-line and line-level three-way **text** merge — every R and C row). Where upstream
  resolves a case and we escalate it, we are worse, and a port that added machinery while leaving such a
  case escalated would have failed while looking like progress. § 5.5 also names the three places parity
  would be a **regression** (member-level recognition, `PROJECT_TYPES` claims, the verifier) because the
  floor must not lower the ceiling we already have.
- **The point is exceeding it where we already can** —
  [`§ 10.2`](../merge-java/docs/JETBRAINS_PORT.md) lists six capabilities we have and upstream does not
  (`AnalysisLevel` arbitration, `ResolutionVerifier`, twelve domain `ConflictType`s, signature-keyed
  replay, the suggestion channel, project-classpath type resolution), each tied to an overlap case it must
  **win with a measurement**. A row whose measurement does not beat the shape-only answer is **removed**,
  not reworded.

The full reference for parts 1 and 2 — every upstream path, licence obligation, algorithm, decision rule
and test vector — is in
[`merge-java/docs/JETBRAINS_PORT.md`](../merge-java/docs/JETBRAINS_PORT.md). **Read it before starting
4.7.** It corrects three inaccuracies in the original instruction (the test path is `tests/testSrc/`, not
`test/`; the algorithms live in `platform/util/diff`, not `diff-impl`; the engine is Kotlin, not Java)
and it records that upstream performs **no validation of resolved text at all** — its safety story is a
human in an editor (check C7), whereas ours is `ResolutionVerifier`, which is why the suggestion channel
verifies a suggestion *as if* it were automatic.

| Step | What                                                                                        | Who   | Size |
| ---- | ------------------------------------------------------------------------------------------- | ----- | ---- |
| 4.7  | Sources, licence and the pinned upstream checkout                                           | agent | S    |
| 4.8  | The text tier: line and word comparison, and the whitespace policies                        | agent | L    |
| 4.9  | The merge tier — **SAFE** results only: range building, the simple pass, and the refusals   | agent | L    |
| 4.10 | Whitespace policy as a caller-visible option, threaded through detection and resolution     | agent | M    |
| 4.11 | `AnalysisLevel` gains the intra-line evidence level                                         | agent | S    |
| 4.12 | The conflict **shape** ported onto detection, beside the existing domain taxonomy           | agent | M    |
| 4.13 | Upstream vectors, the parity gate, and the randomized property test                         | agent | M    |
| 4.14 | The suggestion channel: `Suggestion`, `ResolutionKind.SUGGESTION`, `APPLIED_SUGGESTION`     | agent | M    |
| 4.15 | Move the resolvers that already compute an answer onto the channel                          | agent | M    |
| 4.16 | The page and the decisions contract: Accept / Edit / Reject, and the bulk-accept guard      | agent | M    |
| 4.17 | Rejection memory, and `ConflictProposer` as one provenance                                  | agent | M    |

**Ordering.** 4.7 gates everything. 4.8 → 4.9 → 4.10 are a chain; 4.11 and 4.12 depend on 4.9; 4.13
measures parts 1–2. **4.14 depends on nothing but the existing model** and may be started at any point
after 4.7 — it is deliberately first in part 3 so that a channel whose contract is settled is what gets
rendered, rather than a channel being discovered while it is being rendered. 4.15 and 4.17 both feed
4.16, and 4.13's graded analysis is what gives 4.17's `confidence` and `provenance` something true to say.

### 4.7 — Sources, licence and the pinned upstream checkout
**Who:** agent · **Size:** S

Everything downstream needs the upstream files available and the licence position settled, and both are
missing today. This step produces a **reproducible, citation-quality reference** — not a vendored copy
of someone else's source.

**Do:**

1. **Recreate the checkout at the pinned commit.** The commands, and the one non-obvious failure mode
   (`--no-checkout` plus `sparse-checkout set` leaves an *empty working tree* until an explicit
   `checkout`), are in
   [`§ 12`](../merge-java/docs/JETBRAINS_PORT.md). It lives under `.tmp/` — gitignored
   scratch per root `AGENTS.md` § 2 — and is **never a build input**. Record the commit hash the port was
   read at (`9f5f0342…`, 2026-10-07) in the port document; a moving `master` is not a citation.
2. **Write `merge-java/THIRD_PARTY_NOTICES.md`** carrying: the Apache 2.0 text or a pointer to it, the
   upstream repository and commit, upstream's `NOTICE`, and the list of files derived from upstream with
   what changed about each. [`JETBRAINS_PORT.md` § 1](../merge-java/docs/JETBRAINS_PORT.md) states the four
   obligations; this step discharges all four.
3. **Establish the derived-file header and prove it is applied.** Every file under
   `com.codebuddy.merge.jetbrains` opens with: the retained Apache 2.0 / JetBrains line, the upstream path
   and pinned commit, and a one-line "derived; changed by translation from Kotlin and by removal of the
   IntelliJ dependency". Add a test (`JetBrainsAttributionTest`) that walks the package and fails on a file
   without it — the same enforcement shape `GeneratorGuardTest` uses, because an attribution rule that is
   only written down is the rule that rots.
4. **Confirm the package skeleton and the three tiers** named in
   [`§ 6.1`](../merge-java/docs/JETBRAINS_PORT.md) — `text`, `merge`, adapter — including
   the tier property that makes the vectors portable: everything up to `merge` must compile and test with
   **no reference to `Conflict`, `ConflictResolution` or any other type of this module**.

**Gate:** `MODULE` for `merge-java` green (the empty packages and the attribution test compile and pass);
`JetBrainsAttributionTest` fails when a header is removed, demonstrated once by hand; a fresh checkout of
the upstream repository at the pinned commit reproduces the file list in
[`JETBRAINS_PORT.md` § 3](../merge-java/docs/JETBRAINS_PORT.md).

**Done when:** a reader can reproduce the source material, and every derived file says where it came from
and what was changed.

**Done 2026-10-07 — the pin, the attribution and the skeleton, before the first algorithm exists.**

- **The upstream checkout is verified, not asserted.** `.tmp/jb-ic` is at the pinned commit
  `9f5f0342…` (2026-10-07) with exactly the four sparse-checkout paths § 12 names, so the reproduction
  commands in this plan produce what is on disk rather than something merely similar.
- **The header format is built here, not described** — four `//` lines above the `package` declaration,
  and [`JETBRAINS_PORT.md` § 7a](../merge-java/docs/JETBRAINS_PORT.md) is now the reference for it. Two
  departures from the obvious reading of DEC-021 are recorded there, because both would otherwise be
  read as mistakes: the file marker is `@derived`, **not** `@generated file <generator-fqn>`, since
  nothing regenerates these files and claiming a generator FQN would be a false statement about how to
  maintain them; and the second line's parenthetical is the upstream path, which is what the verifier
  reads.
- **`@derived none` is a real case, not a gap.** `JetBrainsProvenance.java` is this module's own record
  of the pin rather than a translation, so it names no upstream path. The marker is explicit because an
  absent path is otherwise indistinguishable from an omission — and the test asserts both halves, so a
  file carrying the marker **and** a path fails too.
- **The pin has one home.** `JetBrainsProvenance.PINNED_COMMIT` is read by the verifier; the two Markdown
  documents that cannot read a Java constant carry it literally, and `JetBrainsAttributionTest` fails if
  either loses it. A citation that lives in several files drifts, and one with *different* hashes is
  worse than one with none — it looks checked.
- **Two guarantees, split where the network is.** `JetBrainsAttributionTest` (in the build) proves the
  citation is **complete and self-consistent**; `scripts/verify-jetbrains-sources.js` (run deliberately)
  proves it is **true** — every cited path exists at the pinned revision in a real checkout. A build that
  reaches the network is a build that fails when the network does, so the second is not in the gate. The
  script exits 2 when it could not verify anything, because a check that did not run is not a check that
  passed.
- **The gate's "fails when a header is removed, demonstrated once by hand" was done on a real file**, not
  only on a fixture: the `@derived` line was removed from `merge/package-info.java` and the suite failed
  with `does not contain '@derived'` naming that file, then the file was restored byte-identically. The
  rule's own tests (`theRuleCanFail`, `noticesMustListEveryDerivedFile`) keep that property afterwards.
- **Two defects in the rule were found by running it**, which is why it is a test rather than a promise:
  the header contract had baked the commit hash into a required fragment, so a file citing a *different*
  revision was reported as missing a line — the failure named the wrong defect, and the two checks are
  now separate; and the failure message called `Path.relativize` on a temporary directory, which throws
  on Windows because the roots differ, so the rule could not report a problem in its own tests at all.

**Gate:** ✅ `MODULE` for `merge-java` — **754 tests, 0 failures, 0 errors** with the build cache off,
`JetBrainsAttributionTest` **4 tests** among them; the attribution test shown to fail on a real file and
then restored; ✅ `bun merge-java/scripts/verify-jetbrains-sources.js` → **exit 0**, `upstream sources
verified`, 2 declared paths confirmed against the pinned checkout; `LINKS` green.

**Done when:** met — a reader can reproduce the source material, and every derived file says where it came
from and what was changed.

### 4.8 — The text tier: line and word comparison, and the whitespace policies
**Who:** agent · **Size:** L

The module has **no diff algorithm**. `ConflictDetectionService` decides what changed with
`line.trim()` inside a `LinkedHashSet` — a set-membership test, not a comparison — so it cannot tell
"both sides replaced this region" from "both sides inserted here", cannot locate a change, and cannot see
below the line. JetBrains' first pass is a line diff and its second pass is a word diff over the changed
blocks; this step builds both.

**Do:** implement the `com.codebuddy.merge.jetbrains.text` tier described in
[`JETBRAINS_PORT.md` § 6.1](../merge-java/docs/JETBRAINS_PORT.md):

- `ComparisonPolicy` — `DEFAULT`, `TRIM_WHITESPACES`, `IGNORE_WHITESPACES`
  ([upstream: `ComparisonPolicy.kt`](../merge-java/docs/JETBRAINS_PORT.md)). Semantics per
  [`§ 5.6`](../merge-java/docs/JETBRAINS_PORT.md): `TRIM_WHITESPACES` ignores a line's leading and
  trailing whitespace only; `IGNORE_WHITESPACES` ignores whitespace throughout — **two different
  operations**, which is precisely what our `line.trim()` conflates.
- A **line diff** producing an ordered list of change ranges, and a **word diff** over a changed block
  producing inner fragments. Take from upstream's `ByLineRt` / `ByWordRt` the **word-boundary rule and
  the policy handling**; implement the Myers search natively rather than translating those classes, and
  say in the header why ([`§ 3.2`](../merge-java/docs/JETBRAINS_PORT.md)'s note is the reason: they are
  wired to `DiffConfig`, `FairDiffIterable` and the cancellation model).
- Refuse rather than degrade on pathological input: a bounded table or a size guard, with a named failure,
  in the shape upstream's `DiffTooBigException` uses.

**Do not** make the policy or the size bound a process-global switch. Upstream's `DiffConfig` is mutable
global state; [`§ 7`](../merge-java/docs/JETBRAINS_PORT.md) records that a library must not have one, so
both are parameters.

**Gate:** `MODULE` for `merge-java` green, with the tier's own tests: the line diff's ranges against the
expectations transcribed from `LineComparisonUtilTest` and `WordComparisonUtilTest`; the three policies
against `IgnoreComparisonUtilTest`; and a test that the tier compiles with no import from this module's
merge model.

**Done when:** "what changed, and where, and down to which word" is a question the module can answer.

**Done 2026-10-07 — the tier exists, with one recorded deviation from this step's own text.**

- **The text tier is built**: `ComparisonPolicy` (the three constants with their semantics),
  `TextLines`, `DiffRange`, `WordFragment`, `DiffTooBigException`, the differ, and `TextCompare` — the
  two-pass entry point (`compareLines`, `compareWords`). 14 vectors run against it, transcribed from
  `LineComparisonUtilTest`, and `JetBrainsTierIsolationTest` enforces the tier rule.
- **The diver is an LCS table, not Myers, and that is a deviation this plan must record.** The step said
  "implement the Myers search natively". Myers was built first and was abandoned **on measurement**: a
  frontier-based Myers needs its tie-breaking to agree between the search and the walk back, and four
  rounds of fixes each moved the error rather than removing it — on vectors as small as one line against
  two, an insertion came back a line away from where it belongs. An LCS table has no tie-break to agree
  on and its walk reads the table it built, so an equality bug is visible by reading the code.
  [`JETBRAINS_PORT.md` § 3.2](../merge-java/docs/JETBRAINS_PORT.md) carries the full argument.
- **What the deviation costs, stated rather than hidden.** LCS is O(n·m) memory where Myers is O(n+m), so
  the comparison is bounded by a **cell budget** and refuses beyond it rather than degrading. The common
  edges are trimmed *before* the table is sized, which is what keeps a one-line change in a 5,000-line
  file cheap — asserted, not claimed: `shrinkKeepsLargeNearlyEqualTextsCheap` passes a budget of 10,000
  cells for a 5,000-line text. **This is the one place to revisit if step 4.13's parity gate finds a case
  LCS handles worse**, and the reason it is recorded here rather than in a commit message is that a
  future reader will otherwise assume Myers was never wanted.
- **Where the vectors pin one of several minimal scripts, the test accepts any of them.** Two texts that
  share a repeated line have more than one minimal description of the same difference, and upstream's
  vectors pin one. `TextCompareTest.assertOneOf` accepts the valid set, which still catches a wrong
  answer — an absolute assertion there would be asserting the tie-break rather than the behaviour.
- **Three defects the vectors found, each in a place the code looked right:** `TextLines.of("")` returned
  *no* lines (so every comparison against an empty text reported a change at the wrong offset); the last
  line kept or dropped its terminator differently from every other line (so a CRLF file's lines differed
  from its LF twin's, and a trailing newline compared equal to its absence); and the one-sided branch of
  the differ placed its range from a fixed pattern rather than from the side that actually changed.

**Gate:** ✅ `MODULE` for `merge-java` — **769 tests, 0 failures, 0 errors** with the build cache off
(754 before this step). `TextCompareTest` **14 tests**, `JetBrainsTierIsolationTest` **1**,
`JetBrainsAttributionTest` **4** — the last of which caught that a derived file's javadoc may not name a
second upstream file, because the attribution rule reads the whole file.

**Done when:** met — the module can answer what changed, where, and down to which word.

### 4.9 — The merge tier, SAFE half only: range building, the simple pass, and the refusals
**Who:** agent · **Size:** L

This is where the classification is enforced, and it is deliberately **narrower than a port of the
upstream algorithm**. Read [`JETBRAINS_PORT.md` § 5](../merge-java/docs/JETBRAINS_PORT.md) first: it
classifies eight upstream resolution steps (R1–R8) and seven checks (C1–C7). This step takes the rows
marked **SAFE**; the rows marked **SUGGESTION** belong to step 4.14's channel and are wired in 4.15.

The prize is the false conflict we are worst at: two branches editing **different words of one line**
are, to us, one line-level conflict escalated to a human.

**Do:** implement `com.codebuddy.merge.jetbrains.merge` per
[`JETBRAINS_PORT.md` § 6.2–6.3 and § 6.5](../merge-java/docs/JETBRAINS_PORT.md) — **R1, R5, R7, R8 and
checks C1, C2, C4, C6**:

1. **`MergeRange` + `MergeRangeUtil.getMergeType`** — the emptiness × equality decision table
   transcribed as [`§ 6.2`](../merge-java/docs/JETBRAINS_PORT.md)'s table, including the two rules we are
   missing: a conflict is resolvable **only when both sides are non-empty** (check C1), and the
   both-sides-inserted case is a **refusal** (step R6), because two different insertions at one point
   have no correct order. Honour `trueEquality`: when sides are policy-equal but not byte-equal the type
   is still `MODIFIED` and **both** sides report as changed.
2. **`MergeResolveUtil.tryResolve` (R1) — the simple pass only.** The `SimpleHelper` walk of
   [`§ 6.3`](../merge-java/docs/JETBRAINS_PORT.md), whose every appended region is chosen by
   *is-unchanged*, *only-left-changed*, or *only-right-changed*, and which refuses outright when both
   sides changed differently. **That refusal (R6) is the model and lands first**, with its vector: a pass
   that cannot prove an answer must return nothing rather than a guess.
3. **Range building (R7) and the ignored-change re-emission (R8)** — `buildSimple` /
   `FairMergeBuilder`, and `IgnoringChangeBuilder`'s sub-changes. R8 is **required, not optional**: it is
   what makes a whitespace-ignoring merge still *show* the whitespace difference instead of silently
   normalising formatting.
4. **`ComparisonMergeUtil`'s region-order invariants (check C6)** as an asserted property over the range
   list — not as inline `check(...)` calls, but as the invariant step 4.13's generator tests.
5. **R1's carve-out, recorded rather than implicit (check C7).** Where the result is policy-equal but not
   byte-equal to either input, the resolution **says so**: a warning naming the policy, and the
   `AnalysisLevel` it actually reached. Upstream records nothing here because a human is watching; we may
   write the file, so an unrecorded formatting difference is exactly the invisible change to prevent.

**Explicitly NOT in this step — and say so in its commit message.** `tryGreedyResolve` (R3) and its
unconditional deletion application (R4), and the `IGNORE_WHITESPACES` **retry** (R2), are classified
**SUGGESTION** in [`§ 5.1`](../merge-java/docs/JETBRAINS_PORT.md) because their result is useful but not
mechanically forced. Porting them here as `AUTO` would be the naive port the maintainer rejected. They
are built in 4.14–4.15 and consumed by the channel.

**`DESIGN_NEVER_AUTO_RESOLVED.md` is not relaxed by this step.** The commit message says so, and the
tests prove it: check C1 and the modify/delete guard (C2) each get a vector that fails if the rule is
dropped, and C2 is written as a **named** guard with a comment naming the corresponding rule in
[`DESIGN_NEVER_AUTO_RESOLVED.md` § 2](../merge-java/DESIGN_NEVER_AUTO_RESOLVED.md) — upstream encodes it
independently, and the two being the same rule is worth making visible rather than coincidental.

**Gate:** `MODULE` for `merge-java` green, with `MergeResolveUtilTest`'s simple-pass vectors ported
([`§ 11.2`](../merge-java/docs/JETBRAINS_PORT.md)), the C1/C2/C6 tests, and a test asserting that the
greedy strategy and the whitespace retry are **not reachable** from this step's API — the negative test is
what keeps the classification honest.

**Done when:** a conflict inside a single line is resolved when the edits provably do not interfere, and
refused with a reason when they do.

### 4.10 — Whitespace policy as a caller-visible option
**Who:** agent · **Size:** M

[`§ 8`](../merge-java/docs/JETBRAINS_PORT.md) records this as a **real gap**: the module has no
whitespace policy at all. A branch that re-indents a block the other branch edited reads as a conflict
today, and there is no way for a caller to say otherwise.

**Do:** thread the policy from 4.8 through detection and resolution:

- `ConflictDetectionService`'s line-level primitives (`normalisedLines`, `residualLines`,
  `keptByBothBranches`, `spanNotKeptByBoth`) currently hardcode `line.trim()`. Give them the policy, and
  keep `DEFAULT` the default so existing behaviour is unchanged unless a caller asks.
- `MergeFileTool` grows the flag (`--whitespace=default|trim|ignore`), and it is reported in the merge
  report so a resolution records the policy that produced it — a resolution whose basis a reviewer cannot
  see is the failure mode `warnings` and `AnalysisLevel` already exist to prevent.
- Recording the policy on the resolution is what makes a replayed decision safe: the same decision under
  a different policy is a different decision.

**Gate:** `MODULE` for `merge-java` green. The acceptance test is
[`§ 10.5`](../merge-java/docs/JETBRAINS_PORT.md)'s pair: the same three texts produce **one `CONFLICT`
covering the whole block under `DEFAULT`** and **five well-typed changes under `IGNORE_WHITESPACES`**,
with the five-change case auto-applying to the exact expected content.

**Done when:** whitespace churn stops being a conflict, and the choice is visible where the decision is
read.

**Progress 2026-10-07 — the flag and the acceptance pair, and a limit found by writing the pair.**

- **The policy is threaded for the comparison and the residual.** `detect` takes a `ComparisonPolicy` and
  passes it to the residual questions (`residualLines`, `bothBranchesChangedContent`) and to region
  attribution (`structuralRegion` → `spanNotKeptByBoth` → `keptByBothBranches`). `MergeFileTool` grows
  `--whitespace=default|trim|ignore` plus `Builder.whitespacePolicy(...)`, and `whitespacePolicyOf`
  **refuses an unknown name** rather than silently defaulting — a caller that mistyped must be told, or it
  would believe it had chosen a policy it had not. The default is `TRIM_WHITESPACES`, which is what the
  tool did before the policy existed, and a test asserts that choosing nothing and choosing `trim` produce
  identical outcomes.
- **The acceptance pair is `WhitespacePolicyTest`** (7 tests): the same three-sided block is left for a
  human under `DEFAULT` and is not reported as a recognised conflict under `IGNORE_WHITESPACES`; the
  comparison underneath is asserted directly, so the wiring is shown to *do* something rather than merely
  to exist; and `applyingUnderDefaultLeavesTheMarkers` pins the safety half — the default must **not** write
  a block whose sides differ only in indentation, because that would discard a formatting intention
  somebody made on purpose.
- **A limit found by writing the pair, recorded rather than asserted away.** `§ 11.5`'s target is *five
  well-typed changes* under `IGNORE_WHITESPACES`. What this step delivers is **no conflict type at all** —
  the block comes out `LEFT_UNCLASSIFIED`. The reason is structural, not an oversight: **JCodeBuddy detects
  by domain shape** (twelve `ConflictType`s) and treats line-level divergence as the structural residual,
  whereas upstream **derives shape from a line diff** (`INSERTED`/`DELETED`/`MODIFIED`/`CONFLICT`). The
  policy reaches the content questions; the shape detectors ask their own line questions without it.
  Reaching *five well-typed changes* therefore needs the ported differ wired into detection as the
  classifier — **step 4.12's job**, which is where the shape taxonomy lands. So 4.10's criterion is met as
  *"whitespace churn stops being a conflict"* and **not** as the typed-change count; claiming otherwise
  would be overclaiming, and the gap is named rather than tested around.
- **A fixture defect worth recording**, because it produced a green test that proved nothing: the first
  version of the block used three **byte-identical** sides, and the tool correctly reported
  `APPLIED_IDENTICAL_SIDES` under every policy. A vector for a policy must differ *in what the policy
  ignores* — a different thing from not differing at all.

**Gate:** `MODULE` for `merge-java` — **793 tests, 0 failures, 0 errors** with the build cache off (786
before). `WhitespacePolicyTest` is 7 of them.

**Still open, precisely named:** wiring the ported differ into detection so the policy yields *typed*
changes (4.12), and reporting the policy in the merge report JSON so a reviewer reads it beside the
decision.

**Done 2026-10-08 — the second remainder is closed, and closing it found the first one was not complete.**

- **The policy now reaches the RESOLUTION and the REPORT**, which is what "a reviewer reads it beside the decision"
  needs: `MergeConflictResolver` gained `setWhitespacePolicy(...)`, `MergeFileTool` passes the run's policy to it and
  **stamps it on every resolution** at the same boundary where regions are restated for the report, and the report
  writes `whitespacePolicy` **per conflict and at file level**.
- **Threading it exposed an inconsistency my own earlier rounds introduced.** The tool detected under its own policy
  while the resolver's offer path composed under a hardcoded `DEFAULT` — two comparisons of the same three sides, one
  in the answer and one in the report, and nothing said which. Measured before the fix: a run under
  `IGNORE_WHITESPACES` reported `TRIM_WHITESPACES`, because nothing carried the caller's choice that far.
- **The measurement corrected the test I wrote, and the correction is the interesting half.** I asserted first that a
  lenient run's report names its policy *per conflict* — and it cannot, because under `IGNORE_WHITESPACES` this vector
  has **no conflicts at all**: the policy working, not the file being trivially clean. So the per-conflict key can
  never carry the case that needs it most, and the **file-level** key exists for exactly that: without it "nothing to
  decide" and "the comparison ignored what differed" read the same in a report. `theReportNamesThePolicy` asserts both
  levels, and the file-level one is asserted with `"conflicts": 0` beside it.
- **The page shows it when it matters**: ` · compared ignoring whitespace` (or `ignoring line edges`) appears on a card
  only when the policy was lenient, because `DEFAULT` is what a reader assumes when nothing is said and a fact shown on
  every card stops being read. Wording is the page's (DEC-027).
- **The first remainder was already satisfied and is now stated rather than assumed**: the policy reaches detection,
  which uses it for type recognition, regions and shapes (`ConflictDetectionService`'s `effective`), which is what step
  4.12 built the shape on.
- **Measured**: `merge-java` **923 tests**, 0 failures; the review page **22 tests**, 0 failures, and it builds.
### 4.11 — `AnalysisLevel` gains the intra-line evidence level
**Who:** agent · **Size:** S

Step 4.6 built the evidence scale so stronger analysis outranks a weaker objection. Its levels are
`TEXT_LOCAL` → `TEXT_FILE` → `STRUCTURE` → `PLATFORM_TYPES` → `PROJECT_TYPES`, and
[`§ 8`](../merge-java/docs/JETBRAINS_PORT.md) finds the hole: **`TEXT_LOCAL` is described as "the
conflicting sides compared as text … line sets", which is a line-level notion of "text".** A
word-characterised answer reads *within* a line — better than line sets, weaker than a whole-file region
— and today it has nowhere to be recorded.

**Do:** add `TEXT_INTRALINE` between `TEXT_LOCAL` and `TEXT_FILE`, with javadoc stating what makes it
strictly stronger than `TEXT_LOCAL` (it locates a change to word granularity inside the block rather than
comparing line sets) and strictly weaker than `TEXT_FILE` (it still reads nothing outside the block).
Its `strength` is stated per constant, as the enum's own rule requires, so inserting it cannot silently
reshuffle the order.

Then let 4.9's resolvers **declare** it: `maxAnalysisLevel()` is `TEXT_INTRALINE`, and a resolution
records the level it actually reached — `TEXT_LOCAL` when the pass refused and only line sets were read.

**Gate:** `AnalysisLevelTest` extended: the new level orders correctly in both directions;
no resolution of the new resolvers records a level above its resolver's maximum (the invariant 4.6
established); and a resolution that fell back to line sets records `TEXT_LOCAL`, not `TEXT_INTRALINE`.

**Done when:** the evidence scale can describe the analysis the port added, so arbitration can use it.

**Done 2026-10-07 — the level exists and is ordered; nothing reaches it yet, and that is the honest state.**

- **`TEXT_INTRALINE(2)`** sits between `TEXT_LOCAL(1)` and `TEXT_FILE(3)`, with its strength stated per constant
  (so every other level's number moved by one and none of them changed meaning) and javadoc stating both
  directions: strictly stronger than a line set, because it can say *where inside a line* the branches differ;
  strictly weaker than `TEXT_FILE`, because it still reads nothing outside the block. `AnalysisLevelTest` asserts
  **both** directions — one alone would leave the level free to drift on the other side, which is where a better
  answer quietly becomes a licence.
- **The producer this step was waiting for arrived in the same round, and it does not reach the level.** The
  greedy pass ported as step 4.17's producer compares whole **lines** under a policy-aware equality, so it records
  `TEXT_LOCAL` and warns that it read "line by line …, not word by word". Recording `TEXT_INTRALINE` for it would
  have been an overclaim of exactly the kind this session keeps finding, and a level is what a reviewer uses to
  judge the answer — so the record says what it was checked against rather than what it hoped to be. **Reaching
  the level needs the word-level half of the port** (`JETBRAINS_PORT.md` § 6.5–6.6: `IgnoringChangeBuilder`'s
  re-emission of sub-runs where the policy says equal and the bytes differ, and `TrimUtil`'s boundary expansion),
  which is where the intra-line comparison actually lives.
- **A step whose second half therefore cannot be completed as written**, and the plan should say so rather than
  pretend: "let 4.9's resolvers declare it" presumes resolvers reading within a line, and the port produced none —
  the intra-line machinery is a *pass*, and its result belongs on the suggestion channel (4.17). The step's own
  third gate clause, "a resolution that fell back to line sets records `TEXT_LOCAL`, not `TEXT_INTRALINE`", is
  satisfied in the strongest available form: the one producer there is records `TEXT_LOCAL` **because** it fell
  back to line sets, and a test asserts it is not the intra-line level.

**Gate:** `AnalysisLevelTest` — 6 tests, now including the new level in both directions and the two negative
assertions that pin it. `merge-java verify` — **869 tests, 0 failures, 0 errors** with the build cache off.

**Done 2026-10-08 — the level now has a producer that reads words, and all three gate clauses hold.**

- **`MethodBodyChangeConflictResolver` declares `TEXT_INTRALINE` and records it only when words were read.** When
  the statement sets cannot express the answer it asks the ported resolve pass, which composes **inside** a changed
  line, and the answer's level comes from `MergeResolve.Result.wordLevel()`: `TEXT_INTRALINE` when the word
  comparison was needed, `TEXT_LOCAL` when the line comparison alone reached it. The gate's three clauses are now
  facts: the level orders in both directions (`scaleOrdersEvidence`), no resolution exceeds its resolver's
  declaration (`resolutionsNeverExceedTheirResolver`), and **a resolution that fell back to line sets records
  `TEXT_LOCAL`** (`theIntraLineLevelIsRecordedOnlyWhenWordsWereRead`, which asserts both directions from one
  resolver).
- **A trap found while doing it, and it is general enough to write down.** `AbstractConflictResolver.reviewResolution`
  defaults the recorded level to `maxAnalysisLevel()` — so **raising a declaration silently raises what every answer
  of that resolver records**. The "never exceeds the declaration" test cannot see it, because the record then
  *equals* the declaration; the assertion that caught it was the one demanding the *weaker* level on the
  line-reading answer. Any future raise of a declaration has to audit that resolver's answers for the same reason.
  The three statement-set answers now state `TEXT_LOCAL` explicitly.
- **The finer reading had to be consulted FIRST, and a measurement moved it there.** The statement comparison works
  on whole **lines**, so two branches that edited the *same* line differently look like two unrelated statements:
  their change sets do not intersect, the resolver calls that "disjoint edits", and it combines both lines —
  producing a body with two conflicting statements and never asking the comparison that can read the line. For a
  single-statement body that was the common case rather than an edge one. The order is now: the ported pass first (it
  composes only what it can justify and **refuses** on a real disagreement), the statement-set logic when it refuses,
  and the level records which one answered.
- **The census did not move**, and saying so is the honest report: `escalated 3`, `offered 3`,
  `{AUTO=5, MANUAL=3, SUGGESTION=3}` — the `METHOD_BODY_CHANGE` sample was already offered through the generic offer
  path. What changed is the **route** and the **recorded level** (a resolver's answer at `TEXT_INTRALINE` instead of
  a generic pass's at `TEXT_LOCAL`), which is the step's subject rather than a new outcome.
### 4.12 — The conflict shape, ported onto detection
**Who:** agent · **Size:** M

The port adds a second, **orthogonal** taxonomy, and
[`§ 8`](../merge-java/docs/JETBRAINS_PORT.md) is explicit that both are needed: our `ConflictType` is the
*domain kind* (twelve types — an import addition, an overload clash), upstream's
`{INSERTED, DELETED, MODIFIED, CONFLICT}` is the *shape* of the change. A domain type without a shape
cannot say "both sides inserted here", which is exactly what a reviewer needs to see and what the
residual-veto problem at step 4.5 is a symptom of.

**Do:** every `Conflict` gains a shape, computed by 4.9's `getMergeType`, and it is:

- **reported** — `MergeReportWriter` writes it, and the `review/` page renders it beside the type, so a
  reviewer reads "import addition, both sides inserted" rather than only the first half;
- **used by the decision** — a shape that is `INSERTED` on **one** side with the other side unchanged is
  the case our line-level detection calls a structural change and the shape calls mechanical. That is
  the measured defect from 4.5/4.6 (`LEFT_MANUAL` for an edit that needs no decision) and this is the
  general form of its fix, with 4.9's word-level typing beneath it;
- **additive only** — the shape informs *what is claimed and how it is explained*. It never promotes a
  `REVIEW` or `MANUAL` into application; that remains `DESIGN_NEVER_AUTO_RESOLVED.md`'s rule and
  `MergeFileTool.decide`'s.

Keep `ConflictType` unchanged. Adding shapes is a widening of the model, not a replacement of the
taxonomy, and a conflict whose shape cannot be computed (a base-less merge, where every region is
unknown — the shape 4.5 measured) records that rather than guessing.

**Gate:** `MODULE` for `merge-java` green, with: a regression test that the step-4.5 and step-4.6
fixtures now report their shape and are still decided the same way (or better, with the reason named); a
test that a base-less conflict records an unknown shape and keeps its current behaviour; and a test that
no shape can turn a `MANUAL` resolution into an applied one.

**Done when:** the report says what kind of change a conflict is as well as what domain it is in, and the
decision uses it where it makes an answer mechanical.

**Done 2026-10-07, with one clause deliberately not met and named below.**

- **`ConflictShape`** — `INSERTED`, `DELETED`, `MODIFIED`, `CONFLICT` and `UNKNOWN` — is upstream's
  `MergeType.Kind` ported rather than invented, and it is **computed by the ported classifier**:
  `ConflictShape.of(...)` builds the ranges with `MergeRangeBuilder` and names each one with
  `MergeRangeUtil.getMergeType`. That makes it the ported classifier's **first caller in this module** — it was
  written and tested in step 4.9 and nothing had asked it anything until now.
- **`ConflictType` is unchanged**, which was the step's constraint: this is a widening of the model, not a second
  opinion about it. The report writes `shape` beside `type` per conflict, and the page renders the shape in its own
  words beside the domain type, because "import addition, both sides inserted" is two facts and the page owns the
  wording (DEC-027).
- **A copy helper that dropped the shape would have lost it silently**, and the trap was specific:
  `MergeFileTool` re-stamps every detected conflict with its block's file region, so `withRegion` (and
  `withFilePath`, and `withTypeContext`) now carry the shape forward. `theShapeSurvivesTheCopies` asserts it,
  because the symptom would have been "unknown" for every conflict in a diff3 file — plausible enough to pass for
  a base-less block.
- **The rule about merge ranges showed up a third time, and each appearance cost a defect.** A side that did **not**
  change has the **base's** lines over a range, not the empty extent the range records for it. Reading the extent as
  the content made every one-sided modification report as `CONFLICT` (and, in the greedy pass two steps ago, made
  every later slice of that side read from the wrong offset). The same rule now appears in
  `ConflictShape.sideLines`, `GreedyMergeSuggestion` and `BlockComposition` — three independent places, which is
  the sign that it is not a trick but the only reading of a range that exists.
- **A base-less block records `UNKNOWN` rather than guessing**, asserted at the detection level as well as on the
  factory: with no base, "both sides inserted" and "one side inserted while the other deleted" are the same two
  texts, and a wrong shape would be read as evidence.
- **The clause that is not met: the decision does not yet *use* the shape.** The shape is computed, recorded and
  rendered, and the step's own gate allows "still decided the same way, with the reason named" — which is what the
  regression shows: the suite is green with **zero** changes to the step-4.5 and step-4.6 fixtures. The general
  fix those steps measured (a one-sided insertion that a line comparison calls a structural change) is therefore
  *expressible* now and not yet applied; it belongs with 4.13's parity work, where the measurement that justifies
  it exists.

**Gate:** `ConflictShapeTest` — 10 tests: the four kinds including the identical-change case, both-insertions
differing as `CONFLICT` (R6 in shape form), the base-less `UNKNOWN`, agreeing sides having no shape, the shape
surviving every copy helper, detection attributing a shape on a real diff3 block and `UNKNOWN` on a base-less one,
and the boundary that a shape never makes a `MANUAL` resolution applied. `merge-java verify` — **884 tests, 0
failures, 0 errors** with the build cache off; `bun test` in `review` green and the page builds.

### 4.13 — Upstream vectors, the parity gate, and the randomized property test
**Who:** agent · **Size:** M

The instruction asked for JetBrains' test cases to be integrated, and this is how they earn their place:
**they are the benchmark, and the benchmark is a build gate.** The maintainer's rule of 2026-10-07 —
*"this tool must be better than JetBrains, JetBrains is the benchmark of minimum that has to be achieved
where we overlap with JetBrains"* — is [`JETBRAINS_PORT.md` § 5.5](../merge-java/docs/JETBRAINS_PORT.md),
and § 5.6 states the gate. **Read both before writing a line of this step**, because this is the step
where "we are at least as good as the benchmark" stops being a sentence and becomes a test result.

The distinction that makes this step matter: the failure it prevents is **invisible**. A port that
resolves *more* cases than before but still escalates one that upstream resolves looks like an
improvement in every commit message and every dashboard. Only a gate catches it.

**Do:**

1. **Land the four vector sets as fixtures.** `§ 11.1` change types, `§ 11.2` word-level resolves,
   `§ 11.3` non-conflicting auto-apply with its remaining-change counts, `§ 11.5` the whitespace pair —
   as `src/test/resources/fixtures/jetbrains-*` in the `THREE_WAY_FIXTURES.md` layout, so the existing
   `ThreeWayFixture` harness runs them without a new loader. Note that the upstream `_` is a line
   separator and that `§ 11.2`'s resolve vectors carry **expected content**, not just a verdict.
2. **Land the refusal vectors too** (`§ 11.4`), including the **control** row. The control is the point:
   it proves the refusal comes from the conflict *type* and not from the text, and a port that took only
   the two refusal rows would pass while being wrong.
3. **Build the parity gate itself** ([`§ 5.6`](../merge-java/docs/JETBRAINS_PORT.md)) — the minimum bar
   for calling this port done, and a separate test from the vector fixtures so its failure message can
   name the vector and the direction of the regression. Grade **three** outcomes per vector, not one:
   upstream *auto-resolved to text X* → we must resolve **to the same text X** ("resolved something" is
   not parity); upstream *refused* → we must refuse; upstream *invalidating edit makes it unresolvable* →
   we must become unresolvable. **Fail on any regression, in either direction:**
   - upstream resolves, we escalate → **failure** (we are worse than the floor);
   - upstream refuses, we apply automatically → **failure of the more serious kind**, a
     `DESIGN_NEVER_AUTO_RESOLVED.md` breach wearing a port's clothes.

   The **only** permitted exception is a case where our `ResolutionVerifier` or `AnalysisLevel`
   arbitration *deliberately* declines something upstream's editor-and-undo model accepts. Such a case is
   listed **by name** in the test with its argument, so the difference is a recorded decision rather than
   a silent regression — and an exception that cannot be argued in one sentence is a bug in the port, not
   an exception.
4. **Re-express the randomized property, do not port its harness.** `MergeAutoTest` checks that after any
   sequence of apply / ignore / resolve / edit, the change ranges stay ordered and undo restores the prior
   state. Its harness is bound to `ApplicationManager`, `Disposable` and the editor undo stack
   ([`§ 3.4`](../merge-java/docs/JETBRAINS_PORT.md)), so port the **property** over our plain-text API with
   a **seeded** generator — a failing run must be reproducible, which a `System.currentTimeMillis()` seed
   (upstream's choice) does not give.
5. **Measure both halves, not just the floor.** [`§ 10.3`](../merge-java/docs/JETBRAINS_PORT.md) names
   four numbers: the parity ratio with its exception list; *conflicts escalated to a human* and *blocks
   left `LEFT_MANUAL`* (both must fall, with **zero** new incorrect applications); and the § 10.2
   beyond-parity rows, each against the shape-only answer it must beat. Put all of them in the commit
   message. A claim of **parity** without the gate is the specific assertion this document exists to
   prevent, and a claim of being **better** without a § 10.2 measurement is the other one.

**Gate:** `MODULE` for `merge-java` green with every vector from `§ 11` executing; **the parity gate green,
with its ratio and its named exceptions in the test output and the commit message**; the seeded property
test green and shown to fail on a deliberately broken ordering invariant; the § 10.3 numbers in the commit
message; `LINKS` green for the new documents.

**Done when:** "at least as good as JetBrains where we overlap" is a test result with a floor under it,
and the § 10.2 rows are measured rather than asserted.

**Started 2026-10-07 — the gate exists, runs the benchmark's vectors, and on its first run it caught a
serious defect in this module.**

- **The gate is `JetBrainsParityGateTest`**, holding § 11.1's eighteen change-type vectors and § 11.2's three
  resolve vectors as data, graded per vector and in both directions, printing its own numbers on every run:
  `PARITY-METRIC: change types 16/18 … 2 recorded defect(s)` and
  `PARITY-METRIC: resolve vectors 0/3 resolved to the benchmark's text, 3 declined under a recorded exception,
  0 REGRESSION(S)`. **The second line is deliberately not "3/3 agree"**: a refused vector is not parity, and
  counting three expected refusals as agreement is precisely the dashboard arithmetic this step exists to
  distrust — the first version of the gate did exactly that, and the metric was split to stop it.
- **It grades the change types through `ConflictShape.typesOf(...)`**, which is the ported classifier's public
  face, so the vectors assert the exact kinds, sides and *count* of ranges rather than a summary shape. This is
  also what finally gave step 4.12's shape a consumer.
- **THE DEFECT, and it is the serious direction.** For `x_Y | x_z_Y | z_Y` the benchmark expects one conflict — a
  person decides — and **we apply `"Y\n"`**, silently dropping `x` and `z`: a line *each branch kept*. The result
  matches neither side, so this is not a missing answer but a wrong one, applied. **Cause:** a range's side extent
  is recorded as the side's *changed* lines only, so a side that deleted part of a base extent gets extent length 0;
  a range where each side deleted a **different** line therefore reads as "both sides deleted the same lines" and
  composes to nothing. That is core `MergeRangeBuilder`/`MergeRange` construction — the same *"an unchanged side has
  the base's content over the range"* rule this project has now met four times, here inside the range's own
  coordinates rather than at a consumer. It is fixed as its own measured change, not inside the step that found it.
- **The gate is a ratchet, not an amnesty.** Two vectors are recorded in `KNOWN_DEFECTS` with what we currently do,
  and the test **fails on any disagreement beyond that baseline**, so the entries can only be removed, never
  renegotiated. The three resolve vectors need word-level composition inside a range (§ 6.5–6.6) and are recorded as
  `NAMED_EXCEPTIONS` — a different list from the defects, because an exception is a difference we chose and a defect
  is one we owe.
- **Added 2026-10-07 — § 11.4 with its control, and the randomized property; the property found a second defect
immediately.**

- **§ 11.4 is ported at the level where our model has the rule, and the control is asserted as its proof.**
  The two refusal rows are *delete against edit*, which the pass refuses; the control is the **same shape of
  asymmetry** (one side changed, the other did not) with no deletion, and it resolves — so the refusal comes from
  the modify/delete **type**, not from the text, which is the whole point of porting the third row.
- **The route of that refusal is asserted, and it is not the route the port document describes.** The
  modify/delete guard reads a range's extents, and an **empty text is one empty line** to `TextLines.of("")` rather
  than nothing, so a whole-side deletion arrives as "replaced everything with a blank line" and the guard sees a
  modification. The refusal is carried by the **shape** instead — both sides have content and they disagree, which
  is a `CONFLICT`, and a conflict is never resolved. The rule holds; the guard is a second statement of it that a
  built range cannot reach, exercisable only on a hand-built range, which is how `MergeResolveTest` tests it.
- **A deliberate difference, recorded with its argument.** Upstream writes its two rows with the other side
  **unchanged**, which is their *file-level* `DELETED_MODIFIED`: one branch deleted the file and the other left it
  alone. At range level we **apply** that one-sided deletion, because a change only one side made is exactly what
  non-conflicting auto-apply is for — § 11.3's own "remove-right" vector requires it, and refusing here would make
  the two vector sets contradict each other.
- **The randomized property is ported as a property, not as its harness** (§ 11.6): `SeededMergePropertyTest`
  states over our plain-text API what upstream states over an editor — *the ranges tile all three sides exactly
  once, in order, without overlap* — on a **fixed, printed seed** (`System.currentTimeMillis()` would make a failure
  a story about a run nobody can repeat), over **5 seeds × 400 cases**, and it also asserts the text pass is
  deterministic per case. `theInvariantCheckerHasTeeth` feeds the checker a deliberately broken range list and
  asserts it says so, because an invariant nobody has seen fail is a claim rather than a check.
- **The property paid for itself on its first sweep**, which is the argument for having it: `PROPERTY-METRIC: 2000
  cases over 5 seeds from 20261007, 580 resolved, 1420 refused, 8 invariant violation(s)`. The first 400 cases were
  clean, so **one seed would have shipped this**. The violations were all the same defect: `MergeRangeBuilder`'s two
  absorb loops ran one after the other, so when the second extended the base extent past a change the first had
  already passed, that change stayed unconsumed and **the next range began before the previous one ended** — ranges
  overlapping in base coordinates, base lines claimed twice by the composition. Absorbing to a **fixpoint** fixes
  it: `0 invariant violation(s)` in the same 2000 cases. The benchmark's vectors cannot reach this shape — they all
  have at most one change per side per range — which is precisely what § 11.6 says the randomized suite is for.
- Metrics after the fix: `PARITY-METRIC: change types 18/18 … 0 REGRESSION(S)`;
  `PARITY-METRIC: resolve vectors 0/3 resolved to the benchmark's text, 3 declined under a recorded exception, 0
  REGRESSION(S)`; `PROPERTY-METRIC: 2000 cases …, 0 invariant violation(s)`; **890 tests**, 0 failures.
**Added 2026-10-07 — § 11.4 with its control, and the randomized property; the property found a second defect
immediately.**

- **§ 11.4 is ported at the level where our model has the rule, and the control is asserted as its proof.**
  The two refusal rows are *delete against edit*, which the pass refuses; the control is the **same shape of
  asymmetry** (one side changed, the other did not) with no deletion, and it resolves — so the refusal comes from
  the modify/delete **type**, not from the text, which is the whole point of porting the third row.
- **The route of that refusal is asserted, and it is not the route the port document describes.** The
  modify/delete guard reads a range's extents, and an **empty text is one empty line** to `TextLines.of("")` rather
  than nothing, so a whole-side deletion arrives as "replaced everything with a blank line" and the guard sees a
  modification. The refusal is carried by the **shape** instead — both sides have content and they disagree, which
  is a `CONFLICT`, and a conflict is never resolved. The rule holds; the guard is a second statement of it that a
  built range cannot reach, exercisable only on a hand-built range, which is how `MergeResolveTest` tests it.
- **A deliberate difference, recorded with its argument.** Upstream writes its two rows with the other side
  **unchanged**, which is their *file-level* `DELETED_MODIFIED`: one branch deleted the file and the other left it
  alone. At range level we **apply** that one-sided deletion, because a change only one side made is exactly what
  non-conflicting auto-apply is for — § 11.3's own "remove-right" vector requires it, and refusing here would make
  the two vector sets contradict each other.
- **The randomized property is ported as a property, not as its harness** (§ 11.6): `SeededMergePropertyTest`
  states over our plain-text API what upstream states over an editor — *the ranges tile all three sides exactly
  once, in order, without overlap* — on a **fixed, printed seed** (`System.currentTimeMillis()` would make a failure
  a story about a run nobody can repeat), over **5 seeds × 400 cases**, and it also asserts the text pass is
  deterministic per case. `theInvariantCheckerHasTeeth` feeds the checker a deliberately broken range list and
  asserts it says so, because an invariant nobody has seen fail is a claim rather than a check.
- **The property paid for itself on its first sweep**, which is the argument for having it: `PROPERTY-METRIC: 2000
  cases over 5 seeds from 20261007, 580 resolved, 1420 refused, 8 invariant violation(s)`. The first 400 cases were
  clean, so **one seed would have shipped this**. The violations were all the same defect: `MergeRangeBuilder`'s two
  absorb loops ran one after the other, so when the second extended the base extent past a change the first had
  already passed, that change stayed unconsumed and **the next range began before the previous one ended** — ranges
  overlapping in base coordinates, base lines claimed twice by the composition. Absorbing to a **fixpoint** fixes
  it: `0 invariant violation(s)` in the same 2000 cases. The benchmark's vectors cannot reach this shape — they all
  have at most one change per side per range — which is precisely what § 11.6 says the randomized suite is for.
- Metrics after the fix: `PARITY-METRIC: change types 18/18 … 0 REGRESSION(S)`;
  `PARITY-METRIC: resolve vectors 0/3 resolved to the benchmark's text, 3 declined under a recorded exception, 0
  REGRESSION(S)`; `PROPERTY-METRIC: 2000 cases …, 0 invariant violation(s)`; **890 tests**, 0 failures.

**Added 2026-10-07 (round 31) — the vectors are on disk, and the layout the plan assumed is the wrong one.**

- **The vector sets now live on disk as data**, in `merge-java/src/test/resources/parity/`: `jetbrains-change-types.txt`
  (§ 11.1, 18 vectors) and `jetbrains-resolve.txt` (§ 11.2, 3), each with its own documented format and its
  transcription provenance in the header. `JetBrainsParityGateTest` **reads them** rather than holding a second copy
  in code, so the table a reviewer reads is the table the gate grades — a fixture set that exists twice is the
  failure mode it invites.
- **The plan said they would become `THREE_WAY_FIXTURES.md` cases, and they cannot.** That document's rule 1 is that
  a fixture is three **complete, compilable** Java files — "a fragment cannot express a change", and type
  attribution needs a plausible source path. These vectors are text-fragment ranges with expected **kinds**: they
  exercise the ported text machinery (`MergeRangeBuilder`, `MergeRangeUtil.getMergeType`, `MergeResolve`), not a
  `ConflictType`, and a whole-file fixture cannot state `y z | x y z | x y`. Forcing them in would have broken the
  rule that makes those fixtures trustworthy, so `JETBRAINS_PORT.md` § 11 now carries the correction and points at
  the two files. **§ 11.3 and § 11.4 are the rows that genuinely are whole-file cases**, and they are what a
  `ThreeWayFixture` can carry — that is where the remaining fixture work belongs.
- **A cache-input gap was found while doing it, and closed.** Editing `merge-java/docs/JETBRAINS_PORT.md` left the
  module's checksum at `4e68997fff321f21` and the build was **restored from cache** — yet
  `JetBrainsAttributionTest` reads that document off disk to assert the pinned commit is recorded. That is exactly
  the hazard `AGENTS.md` § 2 names ("a document a test asserts against"), and the documented fix is to list the
  path: `.mvn/maven-build-cache-config.xml` now includes `docs`, `AGENTS.md`, and their `../../merge-java/…`
  counterparts, in the same dual form the existing `scripts` entries use. **Evidence:** the checksum went
  `4e68997fff321f21` → `ce9523ebab02de1b` → `520bca04c0e40863` across the doc edits where it previously did not
  move at all, and the run no longer restores from cache. The extension's 1.2.0 schema **rejects** an
  `<input><project>` block — it fails the whole build with "xml config is not valid or not available" — which is
  why the entries are in the global list, noted in the file.

**Added 2026-10-07 (round 32) — the invalidating edit, and an open question about the signature.**

- **§ 11.2's second table is asserted.** The rule is that *resolvability is a property of the current output, not
  of the original inputs*: upstream resolves the vector and then, once a person replaces a result line, the answer
  is no longer resolvable. Our expression of it is the **signature**, and `theInvalidatingEdit` asserts both halves
  that hold today — the same conflict has the same signature, and an edit to either **side** changes it, including
  the file name a recorded decision is looked up by.
- **It found a gap, and the gap is a policy question rather than a bug to fix quietly.** The base is **not** part
  of `ConflictSignature`, so a conflict whose upstream side moved — a rebase, a different merge base — keeps the
  same signature and a recorded answer still matches it. That matters because the sides decide the *text* but the
  base decides how the change is **read** (`ConflictShape`, the modify/delete rule, whether "both sides inserted"
  is even true), so an answer recorded before a rebase can be replayed under a description that no longer holds.
  Adding the base would invalidate every recorded decision whenever the base moves, which is a policy about **when
  a person's past decision stops counting** — the maintainer's call, with a real trade on both sides (a rebase of
  an unrelated part of the file should not throw an answer away; a base that changed under the conflict probably
  should). **The test pins today's behaviour with the question written into its message**, so taking the decision
  flips one assertion rather than being rediscovered. **This is a question for the maintainer, not a blocker:** the
  work continues, and the gate is honest about which way it currently leans.
- **Corrected a claim I made in the previous round's own correction.** I wrote that § 11.3 and § 11.4 were the
  whole-file rows a `ThreeWayFixture` could carry. Neither is, and the port doc now says why: **§ 11.4 is
  upstream's file-level conflict type** (`DELETED_MODIFIED`), and this module has no file-level conflict type at
  all — it has conflict *blocks*, with the rule expressed at range level; **§ 11.3 counts remaining changes per
  side**, a quantity from upstream's document model, while our surface counts **open conflicts after a run**. A
  fixture written to make the earlier sentence true would have been a correspondence invented rather than
  measured.

**Added 2026-10-08 (round 36) — the evidence level is recorded per ANSWER, which is what this step's declaration half can honestly mean.**

- **`MergeResolve.Result` reports `wordLevel()`** — true when the answer needed composition *inside* a line — and the
  producer that offers a ported answer records `TEXT_INTRALINE` for those and `TEXT_LOCAL` for the rest.
  `theOfferedLevelFollowsTheReading` asserts both **from the same producer**, so the level cannot drift into a claim
  about capability: a one-sided line change is offered at `TEXT_LOCAL` even though that producer *could* read words.
- **`maxAnalysisLevel()` stays honest per resolver, and the one that would have tempted me is refused.**
  `MethodBodyChangeConflictResolver` compares the two bodies' *statement lines as sets*, so its declaration stays
  `TEXT_LOCAL`. An earlier note hoped it would move to `TEXT_INTRALINE` once the word-level half landed — but landing
  the capability **elsewhere** does not make that resolver read words, and a level is a claim about what was read.
- **The offer path now prefers the ported tier's answer over the greedy pass**, because the two are not equivalent
  evidence: the tier is R1/R6 semantics with **no** unconditional deletion, while the greedy pass applies both sides'
  deletions as a stated trade. The tier's answer is offered first, with its own recorded level; the greedy pass stays
  the fallback, and its suggestions keep `PLAUSIBLE` and their warning about the trade.
- **Every metric held**: `18/18` change types, `2/3` resolve + 1 recorded defect + 0 regressions, the census
  unchanged (`escalated 3`, `LEFT_MANUAL 3`, `offered 3`, verified auto `5`), `PROPERTY 2000 cases, 0 violations`,
  **912 tests**. That the census did **not** move is the honest reading rather than a disappointment: the sample
  corpus's residual blocks are not word-level cases, so the new capability changed no outcome **on this corpus** — it
  changed what the tool can *say* about the answers it offers. A corpus with word-level residuals would show it, and
  building one is the honest next measurement.

**Added 2026-10-08 (round 37) — the word-level residual corpus, so the capability is a number rather than a claim.**

- **The measurement round 36 owed is in `PortMetricTest.theWordLevelCorpus`**, printing on every run:
  `WORD-LEVEL-METRIC: 3 residuals the line pass cannot reach, 2 offered as suggestions (2 at TEXT_INTRALINE),
  1 refused`. The type-keyed census could not move — its samples hold no word-level residual — so this corpus is
  stated separately rather than by weakening that one.
- **The two offered answers are the shapes the benchmark's vectors use**, and both record the level they earned:
  `int total = a + b + c;` with the sides deleting different words composes to `int total = b;`, and
  `if (value != null && value.isValid()) {` composes to **`if (value) {`**.
- **That second answer is the strongest argument yet for the channel's design, and it is recorded rather than
  hidden.** It is *mechanically* right — every word it keeps is a word neither branch deleted — and it is
  *semantically* a different condition from either branch's. A pass that applied it would be the invisible
  regression the whole design exists to prevent; a pass that refused it would withhold the one thing a reviewer
  needs to start from. Offering it, at `PLAUSIBLE`, with its basis on the screen, is the middle that
  `SUGGESTIONS.md` argues for — and this is the case that makes the argument concrete rather than theoretical.
- **The third is refused, correctly**, and the test names it: both sides edited the **same token** (one rewrote the
  format string, the other renamed the last argument), so their edits overlap and no combination of their words is a
  function of the inputs. R6 one granularity down, and the same rule that makes the two above offerable.
- **Every other metric held**: `18/18` change types, `2/3` resolve + 1 recorded defect + 0 regressions, the
  type-keyed census unchanged, `PROPERTY 2000 cases, 0 violations`, **913 tests**.

**Added 2026-10-08 (round 38) — the § 10.2 rows measured, and the one the measurement refuses.**

- **Row 2 measured, with its control** (`theVerifierRowIsMeasured`): a **deliberately corrupted** automatic answer
  becomes `REVIEW` with verification `FAILED` and the reason appended to its explanation, while the same conflict
  with a balanced answer stays `AUTO`/`PASSED`. The control is what makes the first half evidence rather than a
  statement about the fixture. § 10.2's own words for this row are *"the measurement is the guard firing"*, and this
  is the guard firing on purpose.
- **Row 3 measured** (`theRowByRowMeasurement`): the corpus's claims record `{TEXT_LOCAL=4, TEXT_FILE=2,
  STRUCTURE=1, PLATFORM_TYPES=1}` across strategies `{KEEP_BOTH=4, MERGE_SAFE=3, PREFER_BRANCH2=1}` — four levels
  and three strategies where a shape-only engine has four words and no strategy at all. The test also asserts the
  thing that would quietly undo the row: **no claim records an absent level**, because a claim with no level *is*
  the shape-only answer.
- **Row 1 already measured** (round 33): `MEMBER_ADD` resolves `AUTO` at `STRUCTURE` where the text-only answer for
  the same input **refuses**. Row 4 (signature-keyed replay, including a remembered refusal) is measured by
  `MergeFileToolTest`'s recorded-decision test and `RejectionMemoryTest`'s round trip. Row 5's first half is the
  census's `offered 3`; its second half needs an accept action that does not exist yet.
- **Row 6 is UNMET, and § 10.2 says what to do about that.** *"Type resolution against a project classpath:
  the resolved code compiles against the project classpath and the level is `PROJECT_TYPES`"* — the corpus resolves
  types against the **JDK only**, so `PROJECT_TYPES` never appears and the print says so on every run. The row is a
  capability the module has (`TypeContext` + javac) with **no fixture that exercises it at that level**, which by
  § 10.2's own rule means the claim is dropped rather than reworded: it stays in the document as **unmet pending a
  project-classpath corpus**, and the honest next step is a fixture with two project types rather than a sentence.
  This is the first § 10.2 row that measurement has taken away, which is what the rule was written for.
- **915 tests**, every other metric held: `18/18` change types, `2/3` resolve + 1 recorded defect + 0 regressions,
  the census unchanged, the word-level corpus `2 offered at TEXT_INTRALINE, 1 refused`,
  `PROPERTY 2000 cases, 0 violations`.

**Added 2026-10-08 (round 39) — row 6 is MET, and the previous round's "unmet" was a scope error of mine.**

- **`ProjectTypesLevelTest` measures the row where its claim lives**: the same `MEMBER_ADD` conflict resolved twice,
  once with a `TypeContext` carrying a **compiled project class** and once without —
  `PROJECT-TYPES-METRIC (row 6): with project entries -> AUTO at PROJECT_TYPES; without -> AUTO at PLATFORM_TYPES`.
  The control is the measurement: the claim is not "the tool can resolve types" (which `ProjectClasspathResolutionTest`
  already showed at the *parser*) but "the tool **says** whether it resolved the project's own types", and only the
  pair of levels shows that.
- **The correction matters more than the row.** Round 38 reported row 6 as **UNMET** on the strength of the census
  corpus, in which `PROJECT_TYPES` never appears — but that corpus resolves every sample against the **JDK only**, so
  the case *cannot* occur in it. **A measurement's scope is part of its claim, and a corpus that cannot contain the
  case is evidence of nothing.** The rule § 10.2 states ("a row whose measurement does not beat the shape-only answer
  is removed") was applied to the wrong evidence, and the census print now says which situation it is in — *"not
  measurable from this corpus (JDK-only); measured by `ProjectTypesLevelTest` instead"* — rather than pronouncing on
  the row.
- **So no § 10.2 row has been removed by measurement after all**, and the count of rows with a measurement behind them
  is: row 1 (round 33), row 2 with its control (round 38), row 3 and the level distribution (round 38), row 4 by the
  replay tests, row 5's first half by the census, and **row 6 now**. Row 5's second half still needs an accept action
  to exist before it can be measured at all.
- **916 tests**, every other metric held: `18/18` change types, `2/3` resolve + 1 recorded defect + 0 regressions, the
  census unchanged, the word-level corpus `2 offered at TEXT_INTRALINE + 1 refused`, `PROPERTY 2000 cases,
  0 violations`.

**Still open in this step:** one § 10.2 measurement (*blocks a reviewer accepted in one action* — it needs an
accept action to exist before it can be measured at all), and the **shape/classification question** the
maintainer is holding: whether a shape may override a type's declared handling, and whether an additive
one-sided change gets a type. The control that measures it reports `LEFT_UNCLASSIFIED` with `type null`, which is
weaker than the `LEFT_MANUAL` steps 4.5/4.6 recorded.

**Handover, 2026-10-08 — where this step stands and what comes first.**

- **Two questions are the maintainer's, and both are pinned by tests that state them rather than decide them.**
  (1) *May a shape override a type's declared handling, and does an additive one-sided change get a type?*
  `DeletionConflictTest` measures the case: a block where only one side changed is **`LEFT_UNCLASSIFIED` with
  `type null`**, weaker than the `LEFT_MANUAL` steps 4.5/4.6 recorded — so the fix has a **classification** half
  before its resolution half. (2) *Should the base be part of `ConflictSignature`?* Adding it would invalidate every
  recorded decision whenever the base moves; `JetBrainsParityGateTest.theInvalidatingEdit` pins today's behaviour
  with the question in its message, and the decision flips one assertion.
- **One measurement is blocked on an action, not on evidence**: § 10.3's *blocks a reviewer accepted in one
  action*. The page has a bulk accept and a test that it never takes a suggestion (4.16); no CLI accept action
  exists, so the number has nothing to count.
- **A future session should not re-derive these**: the parity gate prints its numbers on every run, the census and
  word-level corpus print theirs, and the seeded property test prints its seed. If a number moves, the commit
  message that moved it is where the reason is.
- **A documentation defect this round repaired**: the round-34 paragraph had been written into two sections (a
  whole-file replace matched two identical anchors), and both sections' "Still open" lists had gone stale. The
  step's own record is now the only copy, and each list names what is actually outstanding.

**Gate so far:** `merge-java verify` — **916 tests, 0 failures, 0 errors** (a clean run; see the count caveat below),
with the build cache **on**; `LINKS` green.

**Fixed 2026-10-07, and the gate proves it: the serious defect is gone and 18/18 change types now agree.**

- **`MergeRangeBuilder` records each side's extent as the lines that side *has* over the base extent**, not the
  lines it *changed*: `(baseLength - deleted) + inserted`. A side that deleted part of a base extent used to be
  recorded with extent length 0, which made *"the left kept `b`"* and *"the left deleted `b`"* the same range — and
  then a range in which each branch deleted a **different** line read as "both sides deleted the same lines" and
  composed to nothing. This is the fourth appearance of the rule *"a side with no change kept what the base had"* in
  this project, and the first one **inside the range's own coordinates** — the place the other three were working
  around.
- **The gate fails without the fix, and that exact failure was demonstrated**: with `MergeRangeBuilder` reverted,
  `noApplicationWhereTheBenchmarkNeedsAPerson` reports *"the benchmark needs a person here and we applied an answer:
  testChangeTypes: conflict around a base insertion (§ 11.1) — applied Y"*, and the change-type family drops to
  17/18. With the fix: **18/18, 0 recorded defects, 0 regressions**, and `KNOWN_DEFECTS` is **empty** — the entry
  was removed in the same commit, because a ratchet whose entries are never removed is a permanent excuse.
- **A new family covers the direction that matters**: *every change the benchmark calls a conflict, we refuse
  rather than apply*. The change-type vectors only compare our classifier's **naming**, so a range named
  `deleted both` and then composed to nothing passes them — which is precisely how this defect lived. Asserting the
  **outcome** is what makes the floor real, and it is the assertion that will catch the next one of these.
- **Two of my own errors were found and fixed in the fixtures rather than in the code**, and both decide how much the
  measurement is worth. First, the transcription helper appended `"\n"` to every vector, inventing a line on all
  three sides and moving a trailing insertion one base line later than the benchmark puts it — so two fixtures were
  testing a case the benchmark does not state. (§ 11 says "`_` is a line separator": `x` is one line and `x_` is
  two, with no trailing newline unless the vector writes one.) Second, an assertion in `MergeResolveTest`
  **encoded the defect as a description**: it asserted `leftIsEmpty()` for a range where the left had kept its line.
  It now asserts that the kept line is *inside* the range and that the type is `modified(false, true)`.
- **`clean test`, and a caveat about counts.** This round's runs used `clean`, because a **deleted scratch probe's
  class file** stayed in `target/test-classes` and kept running — a stale-class hazard of the same family as the
  incremental-compile one. **886** is the count from a clean build; earlier counts in this plan may have included
  stale scratch classes, so a count is only comparable against another clean run.
### 4.14 — The suggestion channel: a resolution kind for an answer that needs a person
**Who:** agent · **Size:** M

**The general mechanism the whole port exists to feed, and the part with the longest life.** The design
is [`merge-java/docs/SUGGESTIONS.md`](../merge-java/docs/SUGGESTIONS.md) — **read it first**; this step
builds its § 3 value and § 4 plumbing, and nothing in it is JetBrains-specific.

A **suggestion** is a concrete result the tool worked out, delivered to a person as the thing to look at
first, which the tool **never applies on its own**. It is defined by four checkable properties
([`SUGGESTIONS.md` § 2](../merge-java/docs/SUGGESTIONS.md)): it carries code; it is not applied by
default; it records why it was produced and on what evidence; and both accepting *and refusing* it are
one action.

**Why it is a new kind and not "make `REVIEW` carry code"** — the reasoning is
[`SUGGESTIONS.md` § 2](../merge-java/docs/SUGGESTIONS.md), and the short form is that the two need
different rules for application, for bulk actions and for reporting, so sharing a kind would force each
of those to become a subtype check. That is exactly the hidden distinction this module keeps turning
into an explicit one.

**Do:**

1. **`Suggestion`** — the record of [`SUGGESTIONS.md` § 3](../merge-java/docs/SUGGESTIONS.md): `code`,
   `explanation`, `provenance`, `analysisLevel`, `warnings`, `verification`, `confidence`
   (`PROVEN` / `PLAUSIBLE`). It is a **standalone value**, so a producer does not have to invent a
   `ConflictResolution` to offer one — which is what makes the channel open to sources that are not
   resolvers at all.
2. **`ResolutionKind.SUGGESTION`**, carrying the suggestion. `SUGGESTION` is **never** applied by the
   tool: `MergeFileTool.decide` returns a new `Outcome.LEFT_SUGGESTION` (markers kept) unless a caller
   has explicitly accepted that suggestion.
3. **`Outcome.APPLIED_SUGGESTION`**, distinct from `APPLIED_AUTO` — a run's report must be able to say
   *"three blocks were decided by the tool, two from your accepted suggestions, one still open"*, and one
   merged "applied" count would claim credit the tool has not earned.
4. **`ResolutionVerifier` runs on a suggestion as if it were automatic.** This is the load-bearing half
   of check C7 in [`§ 6]](../merge-java/docs/JETBRAINS_PORT.md): upstream validates
   nothing and relies on a human in an editor; we have a verifier, and today it skips every non-`AUTO`
   resolution ("only an automatic resolution carries the promise that is being verified"). A suggestion
   is not verified in order to be suppressed — a failing verdict **labels** it and it is still shown,
   which is the treatment `ProposerBehindTheGateTest` already demands of a refused proposal.
5. **The structural guarantee, generalised.** `ConflictProposer`'s "cannot reach the resolution" is a
   property of the code shape, not a promise; it must hold for every producer of a suggestion. The
   suggestion lives in its own field and the resolution's `resolvedCode` and `kind` are not writable from
   the channel. A test asserts it for the channel, not for one implementation.

**Not in this step:** the producers (4.15, 4.17) and the page (4.16). This step is testable with **no UI
and no JetBrains code at all**, which is why it is first: a channel whose contract is settled is much
easier to render than one being discovered while it is rendered.

**Gate:** `MODULE` for `merge-java` green, with three tests that encode the boundary directly:
a suggestion is never applied by `MergeFileTool` without an explicit acceptance; a suggestion that fails
verification is still present and marked as failed; and no code path from the suggestion channel can
write the resolution's `resolvedCode` or `kind`.

**Done when:** the module can carry "here is an answer, it is yours to accept or refuse" as a first-class
outcome, and a new producer can offer one without touching `ConflictType`, `ConflictResolvers` or the
page — the generality test in [`SUGGESTIONS.md` § 8](../merge-java/docs/SUGGESTIONS.md).

**Done 2026-10-07 — the channel, with no producer and no page.**

- **`Suggestion`** is the standalone value of [`SUGGESTIONS.md` § 3](../merge-java/docs/SUGGESTIONS.md):
  `code`, `explanation`, `provenance`, `analysisLevel`, `warnings`, `verification` with its detail, and
  `confidence` (`PROVEN`/`PLAUSIBLE`). It is deliberately **not** a field a producer must wrap in a
  `ConflictResolution` to offer — that is what keeps the channel open to sources that are not resolvers at
  all, and it is the generality the step's last line claims. `Confidence` is two-valued and the javadoc says
  why: the tool can honestly say whether a proof exists, and a number would be read as a probability and
  acted on as one.
- **`ResolutionKind.SUGGESTION`**, and it is a kind rather than "a `REVIEW` that carries code" because the two
  need different rules and sharing would turn each into a subtype check. The rule that bites first is the bulk
  accept: `AUTO` is what "apply all resolved" takes, and a suggestion is a judgement about code a person has
  read. `ConflictResolution.suggestion` is a field of its own with a getter and no setter — the structural
  guarantee, generalised from `ConflictProposer`, that the channel cannot write `resolvedCode` or `kind`.
- **`Outcome.LEFT_SUGGESTION`** (markers stay, and **no fixture is prepared** — the suggestion *is* the
  artifact a person works from, so a second copy would be noise) and **`Outcome.APPLIED_SUGGESTION`**.
- **The exit status and the tally ask different questions, and that is now two predicates.** `applied()` is
  what the *tool* decided — a suggestion is not in it — while `settled()` also counts an applied suggestion,
  and `fullyResolved()` uses `settled()`. One merged "applied" count would claim credit the tool has not
  earned, which is the whole reason `APPLIED_SUGGESTION` is distinct.
- **`ResolutionVerifier` now runs on a suggestion and *labels* it.** Upstream validates nothing and relies on a
  human in an editor; this module has a floor, so the answer a reviewer is about to read gets the same check an
  automatic one would — and a failure is written into the suggestion rather than suppressing it or turning it
  into something else. `ProposerBehindTheGateTest` already demanded that treatment of a refused proposal;
  `SuggestionChannelTest.aFailedSuggestionIsLabelledNotHidden` asserts it for the channel.
- **A suggestion is never replayable**, whatever its sticky flag: recording it would replay an answer nobody
  accepted, which is the one thing the channel must not do. `MergeConflictResolver.isWorthRemembering` says so
  in an exhaustive switch, so a future kind cannot be forgotten there.

**Deliberately not in this step, and named:** no producer offers a suggestion yet (4.15 moves the answers the
module already computes onto the channel; 4.17 brings the word-level passes and the proposer), the page that
shows one is 4.16, and **`APPLIED_SUGGESTION` therefore has no producer in this commit** — it is the vocabulary
the acceptance path will produce, and the acceptance path itself is the page's `Accept` action plus the
recorded-decision round trip. One more finding worth recording: a block whose other claim is `MANUAL` does
**not** reach `LEFT_SUGGESTION`, because `decide`'s several-claims path reports the manual objection. Whether a
suggestion should win the block's *outcome* in that case is a question for 4.15, not something to settle by
accident here.

**Gate:** `SuggestionChannelTest` — 3 tests, the three boundaries: a suggestion is offered and **never
applied** (markers stay, exit 1); a suggestion that fails verification is **still there and marked failed**,
with its proposed code intact; and a resolution whose only content is a suggestion carries **no code**, is not
an automatic answer, and is not replayable. `merge-java verify` — **853 tests, 0 failures, 0 errors** with the
build cache off.

### 4.15 — Move the answers we already compute onto the channel
**Who:** agent · **Size:** M

The finding that justifies the whole part: **the module computes useful answers and hides them behind a
refusal.** Measured in the current tree:

| Resolver                           | What it computes                                                             | What happens today |
| ---------------------------------- | ---------------------------------------------------------------------------- | ------------------ |
| [`MethodBodyChangeConflictResolver`](../merge-java/src/main/java/com/codebuddy/merge/MethodBodyChangeConflictResolver.java) line 87 | A combined method body from two branches' disjoint edits, with the reasoning | Stored in `resolvedCode` on a `REVIEW`; `MergeFileTool.decide` returns `LEFT_REVIEW` with a `null` replacement, so the answer never reaches a reviewer as *the* proposed result |
| the same resolver, lines 57 and 66 | "both branches made the same edit" / "they changed the same statement"       | Same               |
| [`ImportConflictResolver`](../merge-java/src/main/java/com/codebuddy/merge/ImportConflictResolver.java) lines 129, 157 | A union of two import sets | Same |
| `ConstantAddConflictResolver`, `TypeChangeConflictResolver`, `OverloadAddConflictResolver`, `RenameConflictResolver`, `PackageChangeConflictResolver` | Each computes a `resolvedCode` on its review path | Same |

**Do:** each of those review paths becomes a **suggestion** carrying the code it already builds, at the
`AnalysisLevel` it actually reached, with its provenance naming the resolver. The `REVIEW` kind stays for
resolutions whose payload is genuinely **fix paths with no answer** — the distinction
[`SUGGESTIONS.md` § 2](../merge-java/docs/SUGGESTIONS.md) draws. Where a resolver has both (an answer and
alternatives), it becomes a suggestion **with** its fix paths, not one or the other.

**The decision does not change.** These answers were `REVIEW` because none of them is provably right —
two individually-correct body edits can compose into behaviour nobody intended
([`DESIGN_NEVER_AUTO_RESOLVED.md` § 5.1](../merge-java/DESIGN_NEVER_AUTO_RESOLVED.md)). This step changes
the **presentation** and adds the acceptance path; it does not promote a single one of them. The commit
message says so and the tests pin it: every block these resolvers own keeps its markers until a decision
arrives.

**Gate:** `MODULE` for `merge-java` green, with: a test per moved resolver that its suggestion carries the
code the resolver computed (not a re-derivation), and a test that a run over the sample fixtures leaves
every such block marked and reports `LEFT_SUGGESTION` rather than `LEFT_REVIEW` — with the **count of
blocks that now have an answer to offer** in the commit message, which is
[`JETBRAINS_PORT.md` § 10](../merge-java/docs/JETBRAINS_PORT.md)'s first suggestion-side metric.

**Done when:** work the tool has already done reaches the person who needs it.

**Done 2026-10-07 — measured: 3 of 7 sampled review paths were computing an answer and hiding it.**

- **One rule, in the orchestrator, rather than twelve edits in the resolvers.** `MergeConflictResolver`
  converts a `REVIEW` that carries code into a `SUGGESTION` carrying **that same text**, with the resolver's
  own name as provenance and the level the resolution recorded. The conversion belongs where the outcome is
  decided, because that is what was wrong: the resolvers were computing the right answer and the *surface* was
  dropping it. Doing it per resolver would be twelve chances to forget, and would put a presentation decision
  inside the code that is answering a question. It runs **after** the proposer block, so a proposer's option
  stays among the suggestion's alternatives.
- **`resolvedCode` is cleared on the way**, so the text lives in exactly one place. Keeping a copy in a field
  other code reads as "the applicable answer" would leave a trap: the next reader would find code on a
  resolution the tool is forbidden to apply.
- **The count, which is the step's own metric.**
  `SuggestionChannelTest.everyComputedAnswerReachesTheChannelUnchanged` walks the shared sample fixtures, asks
  each resolver **directly** for the answer it computes, and requires the suggestion to carry *that* text — the
  only way to tell "moved" from "re-derived". It measures **3** of the seven sampled paths as review-with-an-
  answer; the rest either compute nothing or compute an answer the tool may apply, and an automatic answer was
  never hidden behind anything. The test prints the number, so the metric is re-measurable rather than a claim
  in a document.
- **Nothing is promoted, and the tests pin it.** Every block these resolvers own keeps its markers. The commit
  changed exactly three existing assertions, and each is the step's own point:
  `reviewResolutionLeavesTheBlock` (`LEFT_REVIEW` → `LEFT_SUGGESTION`), `doesNotPersistReviewResolution` (the
  kind, while the two properties it is about — not replayable, not recorded — are unchanged), and
  `classpathDecidesProjectTypes` (`[TYPE_CHANGE/REVIEW]` → `[TYPE_CHANGE/SUGGESTION]`).
- **A decision in 4.14 was wrong, and this step is where it showed.** That step said no fixture is prepared for
  a suggestion because "the suggestion *is* the artifact". The failing test showed the cost: the fixture is the
  case a person or an agent picks an unresolved block up from, and an answer without the case removes the
  workflow that consumes unresolved blocks. A suggestion is fixtured like every other left outcome.

**Gate:** `SuggestionChannelTest` — 4 tests: the three boundaries plus the per-resolver property above, with a
`SUGGESTION-METRIC` line in its output. `merge-java verify` — **854 tests, 0 failures, 0 errors** with the build
cache off.

### 4.16 — The page and the decisions contract: Accept, Edit, Reject
**Who:** agent · **Size:** M

A suggestion nobody can act on is a log line. This step makes acceptance one action on the surface the
module already ships — the `review/` jsx6 page (steps 4.2–4.3) — without changing the round trip it
established: the page collects a decision, a CLI records it, the next merge replays it. A suggestion
rides that path rather than adding a second one, which is what keeps the page standalone (no host, no
port, no network — the maintainer's rule).

**Do:**

1. **The report carries the suggestion.** `MergeReportWriter` writes `code`, `explanation`, `provenance`,
   `analysisLevel`, `verification` and `confidence` per suggestion, so the page renders it from the same
   JSON it already reads.
2. **The page renders a suggestion as the proposed result** — prefilled, in the existing editable result
   field, with its provenance and verification verdict beside it — and offers **Accept**, **Edit** (accept
   a modified version) and **Reject**. Accepting is one action; editing is the existing editor.
3. **The provenance is shown, not just stored.** "The word-level comparison produced this" and "a model
   proposed this" deserve different amounts of trust from the person reading them
   ([`SUGGESTIONS.md` § 3](../merge-java/docs/SUGGESTIONS.md)); a suggestion whose basis is invisible is a
   suggestion that invites blind acceptance.
4. **The bulk-accept guard, in the pure module and asserted.** "Apply all resolved" applies `AUTO`
   resolutions **only**. A reviewer accepting a suggestion is judging code they have read, and no bulk
   action can stand in for that. The rule belongs in `review/src/decisions.js` (testable with no DOM,
   like the existing bulk rule) and in the CLI, and both get a test — a guard implemented in only one of
   two places is the failure this rule exists to prevent.

**Gate:** `bun test` in [`merge-java/review`](../merge-java/review) green, with: a suggestion renders with
its provenance and verdict; accept and reject both produce a valid `decisions.json`; and **bulk accept
skips a suggestion while still accepting every `AUTO`** — the negative half is the assertion that
matters. `MODULE` for `merge-java` green for the report half.

**Done when:** a person can take the tool's answer in one action, and cannot take it by accident.

**Done 2026-10-07 — the report carries it, the page shows it, and the bulk guard is explicit.**

- **The report writes the suggestion as an object with its basis**, not the code alone: `code`,
  `explanation`, `provenance`, `analysisLevel`, `confidence`, `warnings`, `verification` **and its detail**, or
  `null` when there is none so a renderer can tell "no suggestion" from an empty one. Two `MergeReportWriterTest`
  cases pin it, including the detail — a reviewer deciding whether to trust an answer needs to know what the
  verifier said, not only that something was said.
- **A real defect the step found in the page.** `Actions` prefilled its editor from `resolution.resolvedCode`,
  which step 4.15 deliberately empties on a suggestion — so a reviewer would have been shown an **empty editor**
  and told to write the code the tool had already worked out. It now prefills from `proposedCodeFor`, the
  suggestion's code when there is one, and the whole 4.15 change becomes visible in one line of the diff.
- **The basis is rendered beside the answer**, and the note for a block with an offer now says so instead of
  "No answer yet": `blocks.js` exposes `suggestionFor(block)` with provenance, confidence, level and verdict,
  including `failed` and the verifier's reason. Those facts are the difference between an offer a reviewer can
  judge and a code block that invites blind acceptance.
- **The bulk guard is now on the KIND, and the old one was not a guard at all.** `acceptAllResolved` filtered on
  `isResolved` — "does a field happen to hold text" — which quietly included `REVIEW` resolutions and
  **contradicted this file's own javadoc**. `isBulkAcceptable` is `kind === 'AUTO' && isResolved`, so a
  suggestion (and a review) is never swept up, and the rule holds for the right reason rather than because a
  field is empty. The negative half is asserted: bulk accept takes the automatic answer and skips the suggestion
  in the same file.
- **A distinction the page needed and the fixtures pinned**: *"has an answer"* is not *"may be accepted in
  bulk"*. `blocks.js` keeps counting a block's decided entries by `isResolved` — a replayed decision **is** an
  answer — while the bulk action uses `isBulkAcceptable`. Two questions, two predicates, and the existing block
  tests are what showed the difference.
- **Reject is recorded, not merely "not accepted".** The page keeps a rejection list and exports it as
  `rejected: [{ signature, type, filePath, provenance }]` — refusal is per **provenance**, because refusing a
  text-comparison answer must not refuse a structural one later. Step 4.17 is what suppresses on it; recording
  it starts here, and accepting the same conflict retracts the refusal (a conflict cannot be both).

**A clause of this step has no counterpart, and it is worth saying rather than inventing one.** The plan asked
for the bulk rule "in `review/src/decisions.js` and in the CLI, and both get a test". There is **no CLI bulk
action**: `DecisionRecorder` records the explicit decisions a reviewer exported (`--decisions`, `--history`,
`--branch`) and has no "accept everything" mode, so there is nothing there to guard. The guard exists where the
bulk action exists — the page's pure module, used by its one "Apply all resolved" button — and the CLI's
protection is structural instead: it can only record what a person's decision file says.

**Gate:** `bun test` in `merge-java/review` — **22 pass, 0 fail**, including a suggestion rendering with its
provenance and verdict, the proposed result being the suggestion, and bulk accept skipping a suggestion while
taking every `AUTO`; the page builds. `merge-java verify` — **856 tests, 0 failures, 0 errors** with the build
cache off.

### 4.17 — Rejection memory, and `ConflictProposer` as one provenance
**Who:** agent · **Size:** M

Two things that turn the channel from a feature into a mechanism, and both are about what happens the
**second** time.

**Do:**

1. **Declining is recorded, or the suggestion is noise.** If a reviewer rejects a suggestion and the next
   merge offers it again, the channel has made the tool worse. A rejection is recorded against the
   conflict **signature** — the mechanism `BranchConflictStore` and `DecisionRecorder` already use — and
   suppresses that suggestion on later runs. Suppression is per **`(signature, provenance)`**, because
   rejecting a text-comparison answer is not rejecting a future structural one
   ([`SUGGESTIONS.md` § 5](../merge-java/docs/SUGGESTIONS.md), rule 4). This is the rule most likely to be
   skipped, and skipping it is the difference between helpful and nagging.
2. **`ConflictProposer` becomes one provenance of the channel.** It already has the right boundary and the
   right gate; it is expressed as one more fix path only because no suggestion channel existed. On this
   design its output is the concrete proposed result the page prefills, its verification verdict becomes
   the channel's uniform field instead of `impact` text beginning `NOT verified`, and its
   cannot-reach-the-resolution property becomes the channel's general guarantee (4.14 item 5).
   `ProposerBehindTheGateTest`'s five assertions are kept with their subject changed from "a fix path" to
   "a suggestion". **A model still cannot decide**, and
   [`DESIGN_NEVER_AUTO_RESOLVED.md`](../merge-java/DESIGN_NEVER_AUTO_RESOLVED.md)'s proposer section is
   unchanged.
3. **The SUGGESTION half of the JetBrains port lands here.** `tryGreedyResolve` (R3), its unconditional
   deletion application (R4) and the `IGNORE_WHITESPACES` retry (R2) — classified **SUGGESTION** in
   [`JETBRAINS_PORT.md` § 5.1](../merge-java/docs/JETBRAINS_PORT.md) and deliberately left out of 4.9 —
   become producers on the channel, with `confidence` `PLAUSIBLE` and their provenance naming the pass and
   the comparison policy that produced them. The strategy and policy are **parameters, never globals**
   ([`§ 8`](../merge-java/docs/JETBRAINS_PORT.md)), and each is recorded on the suggestion.

**Gate:** `MODULE` for `merge-java` green, with: a rejected suggestion is not offered again for the same
`(signature, provenance)` while a different provenance still is; the three SUGGESTION-class ports each
produce a suggestion with `PLAUSIBLE` confidence and are **not** reachable as `AUTO`; and the proposer's
existing five assertions still pass against the channel. `bun test` in `merge-java/review` green.

**Done when:** the channel learns from refusal, a model's proposal and a word-level pass are the same kind
of thing to everyone downstream, and **a new suggestion source adds a producer and touches nothing else**
— the generality test in [`SUGGESTIONS.md` § 8](../merge-java/docs/SUGGESTIONS.md).

**Started 2026-10-07 — the refusal half is in; the proposer and the ports are not.**

- **Refusal is remembered, which the design calls the rule most likely to be skipped.** `BranchConflictStore`
  keeps a `rejections` map keyed by signature and then provenance, persisted as **one sidecar**
  (`decisions/rejected.json`) beside the decisions — deliberately not one file per refusal, so a malformed
  refusal can only lose refusals, never a decision that would otherwise be replayed. `DecisionRecorder` reads
  the page's `rejected` array and files each one; `MergeConflictResolver` stops offering an answer whose
  `(signature, provenance)` was refused, **drops its code** (keeping it would put the refused text back in front
  of the reviewer through the editor, which is the nagging the refusal exists to stop), and says so in the
  explanation rather than leaving a silent gap.
- **The invisible half, and the one that would have looked like it worked.** The page's refusal entry carries the
  signature it showed the reviewer and **no sides**, so rebuilding the signature from the sides — which is what a
  *decision* requires, because a decision has to be replayable — would have filed the refusal under a name the
  resolver never computes. Suppression would then silently never match, and a refusal nobody can find again
  looks exactly like a refusal that worked. The store therefore takes the key **as written**, and
  `theRefusalIsFiledUnderTheKeyTheResolverComputes` asserts the two agree. The recorder also refuses a
  provenance-less refusal rather than storing it: an unnamed refusal would suppress the *next* producer's answer
  for the same conflict, which is the nagging problem inverted.
- **Gate so far:** `RejectionMemoryTest` — 5 tests: a refused answer is not offered again and says why; a
  different provenance is still offered; the key the recorder files equals the key the resolver computes; the
  refusal survives a write/read round trip; and a provenance-less refusal is rejected with a reported reason
  instead of suppressing everything. `merge-java verify` — **861 tests, 0 failures, 0 errors** with the build
  cache off.
- **The greedy producer is in and wired, and the wiring exposed a defect two steps back.** It runs only on a
  `MANUAL` resolution — where *nothing* decided — so it never second-guesses a resolver that produced an answer,
  and it produces a suggestion because its deletion handling is a trade (R4) rather than a proof. The policy is
  `DEFAULT` with the `IGNORE_WHITESPACES` retry, which is upstream's own order, and the retry's provenance names
  the lenient policy so a reviewer can refuse one attempt without refusing the other.
- **No existing fixture's outcome changed**, and that is the measurement rather than an assumption: the suite is
  green with **zero** assertion changes, where 4.15 needed three. The producer fires where nothing decided *and*
  the pass can compose; on the module's own fixtures those two do not coincide, so `LEFT_MANUAL` stays
  `LEFT_MANUAL`. `GreedyMergeSuggestionTest` proves both halves separately — it fires for a manual conflict whose
  two sides changed different places, and it stays manual when the pass refuses (R6).
- **The defect the wiring found, in 4.21's model.** Writing the walk made the cursor rule explicit: a side that
  **changed** inside a run advances by its own text for the run, while a side that **did not change** kept the
  run's base lines and advances by the run's **base length**. `BlockComposition.segments` had the first half only —
  an unchanged side's extent was the empty range the change flags imply, so the lines it kept were covered by a
  *later* segment. Every line still counted exactly once, which is why the coverage audit never saw it, and every
  later slice of that side would have been read from the wrong offset. Fixed, and `audit` now also reports an
  `UNCHANGED` stretch whose three extents have different lengths — the footprint a mis-advanced cursor leaves.
- **Still open in this step:** `ConflictProposer` as one provenance (item 2 — its answer becomes a suggestion
  rather than only a fix path, which changes step 4.4's "joins the fix paths and NOTHING else" boundary, with
  `ProposerBehindTheGateTest`'s five assertions moving subject), and the **word-level half** of the port
  (`JETBRAINS_PORT.md` § 6.5–6.6), which is what would let the producer record `TEXT_INTRALINE` instead of
  `TEXT_LOCAL`. A third, smaller gap: the channel holds **one** suggestion per resolution, so a greedy answer and
  a proposer's proposal cannot both be offered for one conflict — the model change item 2 needs.

---

#### 4C — hierarchical resolution: a confident answer resolves its region and removes the conflict (steps 4.18–4.22)

> **Why a second lettered subsection.** The same reason as 4B: renumbering is forbidden, and this is a
> phase-sized block of merge-java work that follows 4.6 and depends on it. Steps 4.18–4.22 belong to
> Phase 4 and sit here for the same cause 4.7–4.17 do.

**The instruction this block exists for**, given by the maintainer on 2026-10-07, and sharpened by them
the same day:

> "the change resolution must be hierarchical in a way where some may resolve confidently, and those
> blocks are then not tested by other merges. For example structural that knows two branches added one
> or more whole methods in same location should clear any conflicts they cover and do not need to be
> analyzed by text resolver, same goes for imports resolver, if it has success, then there is no need
> for lower tier to touch that part of the file"

> "we need to refine rankings, I do not want lower level resolver to even see conflict if structural
> knows reliably how to resolve, and if resolution spans whole merge conflict region it should be
> removed marked as resolved and not touched by textual resolvers"

**The design is [`merge-java/docs/HIERARCHICAL_RESOLUTION.md`](../merge-java/docs/HIERARCHICAL_RESOLUTION.md)
and the decision is [`DEC-046`](../doc-hipster-entity/architecture/decisions/DEC-046.md)** (its
amendment is the second quote above). Read both before starting 4.18. In one paragraph: **the tier of a
claim is the `AnalysisLevel` the resolution records** (not a second scale); tiers run strongest first;
a claim that is **reliable over a region** — it may be applied, it *explains* every line of the region,
and the region is known — resolves that region, and a region spanning a **whole** conflict region
**removes the conflict from the working set**, so the lower tier is not asked and no objection to it is
ever constructed; a claim reliable over only **part** of a region applies that part and leaves the
**open remainder** to the tiers below; and DEC-045's `outranking` survives one level down, deciding only
between claims **at the same tier** about a region that is **still open**.

**`resolved` is not `outranked`, and the difference is the requirement.** An outranked claim still
exists — it was produced, it is in the report, and the lower tier spent its time on it. A resolved
conflict stops existing. The pass therefore operates on a working set of live conflicts in one of three
states (`OPEN`, `RESOLVED`, `PARTIAL`) rather than on claims that are all produced and then arbitrated.
**A rank entitles a resolver to be asked earlier; it never entitles it to remove anything** — only the
reliability check does that.

**What this adds, and what it does not.** It adds no new taxonomy and no new permission: detectors
keep emitting, `ResolutionVerifier` keeps gating, and a `REVIEW` or `MANUAL` claim resolves nothing, so
[`DESIGN_NEVER_AUTO_RESOLVED.md`](../merge-java/DESIGN_NEVER_AUTO_RESOLVED.md) is untouched. `RESOLVED`
says who was **not asked**, never that a check was skipped: a resolution that fails verification is
downgraded exactly as it is today. What changes is that a settled region is **removed from what the
later tiers are handed** — which is both less work and fewer false objections, and it is the difference
between a resolver that was overruled and a resolver that was never called.

**Three costs of the shape being replaced**, all measured in the current code and argued in DEC-046's
Context: a text-level objection to an already-settled span is still produced and only then overruled
(so the winner must satisfy `accountsFor` against a claim that need never have existed); `outranks`
requires `coversBlock`, so an answer that settles part of a block becomes `LEFT_PARTIAL_RESOLUTION`
and the whole block — including the part already right — goes to a human; and `residualSubsumed`
removes a claim before arbitration, so "cleared" and "never raised" read identically.

**Ordering.** 4.18 gates 4.19; 4.22 gates on 4.21, which gates on 4.20. 4.18 is deliberately behaviour-neutral — the working set exists
and nothing is removed — so the invariant test is what lands first and the two removing steps are judged
against it. 4.21 and 4.22 need 4.9's line-range machinery (`MergeRange`, `MergeRangeBuilder`, `MergeChange`),
which is why it comes after the port's SAFE half rather than beside it. **4C is independent of
4.11–4.17** and may be landed before, after or between them; where the ordering matters it is stated per
step.

| Step | What                                                                                           | Who   | Size |
| ---- | ---------------------------------------------------------------------------------------------- | ----- | ---- |
| 4.18 | The working set: the three conflict states, the tier order, and the partition invariant        | agent | S–M  |
| 4.19 | Reliability and removal: a reliable claim spanning a whole region resolves it, and the lower tier is never asked | agent | M |
| 4.20 | The state of every conflict in the report — the shape 4.21's composition will need             | agent | S    |
| 4.21 | The position model: the block's lines in three coordinates, and the invariant that tiles them  | agent | S–M  |
| 4.22 | The splice: applied stretches beside markers, and the outcome the enum is missing              | agent | M–L  |

**Done 2026-10-08 — the channel holds more than one answer, and the proposer is one of its provenances.**

- **The prerequisite was the model, and it is a small one because it was made backward-compatible.**
  `ConflictResolution` now carries `suggestions` — an <b>ordered</b> list — and `getSuggestion()` is a view of its
  first element rather than a field of its own, so the two can never disagree: every consumer that predates the list
  (the report's `suggestion` key, the page's acceptance path, the verifier) sees the same primary, and only a
  consumer that asks for all of them learns that an alternative exists. That is what made a 49-hit page and 32 test
  uses safe to extend rather than rewrite.
- **`ConflictProposer` is now one provenance of the channel** (`SUGGESTIONS.md` § 7), from the **same single call**
  that produces its fix path: the proposal's code becomes a `Suggestion` with provenance `proposer`, confidence
  `PLAUSIBLE`, and the gate's verdict in the channel's uniform `verification` field instead of in an `impact` string
  beginning "NOT verified". Its recorded level is **`TEXT_LOCAL` deliberately**: the tool can vouch for nothing about
  what a model *read*, so the weakest reading is recorded rather than an assumed stronger one — recording `STRUCTURE`
  because the text looks structural would assert a reading nobody verified.
- **The order is the promise.** The tool's own composed answer is attached first and stays primary; a model's
  proposal is attached after it and sits beside it as the alternative. `theToolsAnswerStaysPrimary` asserts exactly
  the case the single-slot channel could not express: both producers answering one conflict, the primary at the level
  it earned (`TEXT_INTRALINE`) and the proposal second.
- **`ProposerBehindTheGateTest`'s five assertions are kept** — a proposal still cannot decide, a refused proposal is
  still refused and still says so, a failing proposer still keeps the escalation, and an automatic answer still
  never sees an advisor — **and two were added** for the subject's move: the proposal is *also* a suggestion, and two
  suggestions can coexist. Nothing in `DESIGN_NEVER_AUTO_RESOLVED.md` changed: a model still cannot decide.
- **The verifier labels every offer, not only the primary** — with two producers, verifying the first and leaving
  the rest unlabelled would put an unchecked answer beside a checked one, which is worse than showing neither.
- **The report writes `suggestions` as an array**, rendered by the same function that renders `suggestion`, so
  `suggestions[0]` and `suggestion` are the same answer by construction. **Measured**: `merge-java` 922 tests green,
  the page's 22 tests pass and it builds — the array is additive, so nothing that predates it broke.
- **One small remainder, named rather than implied**: the *page* still renders only the primary suggestion. Showing
  the alternative beside it is a page change (the JSON it needs is already there) and belongs with 4.18/4.20's report
  work rather than here.
### 4.18 — The working set: tiers, the three states, and the invariant that comes first
**Who:** agent · **Size:** S–M

**Do:**

1. **Write the partition invariant as a test before the working set exists.** This is step 4.9's lesson
   (`rangesAreOrderedAndNonEmpty` was written before the builder was trusted, and it is what localised
   the plumbing defects that cost that step its time):

   > every line of a block is accounted for exactly once — applied by exactly one resolved claim, or
   > open (offered to a lower tier and, in the end, left to a human with markers).

   Plus its two companions: **a resolved region is explained by the claim that resolved it**, under one
   of the three ways `HIERARCHICAL_RESOLUTION.md` § 3.3 names (recognised span, kept lines, owned
   domain); and **a conflict marked `RESOLVED` is never offered to a lower tier, while a conflict marked
   `PARTIAL` is offered only its open remainder**. The tests are over the working set's own data, so they
   run without a resolver set and without a fixture.
2. **The working set, and nothing else.** A `ResolutionPass` (name it for what it holds — a set of live
   conflicts, not a table of lines) carries, per block, each detected conflict and its state: `OPEN`,
   `RESOLVED` or `PARTIAL`, with `RESOLVED`/`PARTIAL` naming the claim and the region. **The run order
   comes from the level each resolver declares** (`ConflictResolvers.inTierOrder`, descending by
   `maxAnalysisLevel()`), because a tier can only be skipped *before* it runs and a recorded level does
   not exist until the resolver has been called; **what a claim may settle comes from the level it
   records**, which is the refinement DEC-046 clause 13 makes. Conflicts are offered to a tier in that
   order; at this step every conflict stays `OPEN` and every claim is recorded, so **nothing is
   removed** and the module's behaviour is unchanged.
3. **Make the existing order visible in one place.** `ConflictDetectionService.detect`'s ten calls and
   `ConflictResolvers`' registry are the order today; the pass is a method on the decision path a reader
   can follow ([`DEC-019`](../doc-hipster-entity/architecture/decisions/DEC-019.md)) rather than a table
   consulted at runtime.
4. **Say what an unknown region does**: `Region.unknown()` resolves nothing and is never removed — it
   spans no lines, so it cannot be accounted for by the partition and is reported as unattributed
   exactly as it is today. A conflict that merely cannot be placed is **not** a dropped line; a conflict
   marked settled *without* a location is the defect, and the report says which it is.

**Gate:** `MODULE` for `merge-java` green, with the invariant test present and **proved to fail** on a
deliberately dropped line and on a doubly-resolved region; a test that a `RESOLVED` conflict is absent
from what the next tier is handed and that a `PARTIAL` one is handed only its remainder; a test that the
run order is descending by **declared** level and ignores `priority()`, keeping registration order within
a tier; a test that the **recorded** level stays a separate fact (the same resolver records two levels in
two runs, and a record below a declaration is expressible); and the existing suite unchanged, because
nothing is removed yet.

**Done when:** the invariant and the working set exist, the module behaves exactly as before, and the
next two steps have something to be judged against.

**Done 2026-10-07 — the states, the invariant and the tier order, with no behaviour change.**

- **The invariant has teeth, and finding out how took the step's one real decision.** "Every line is
  accounted for exactly once" is trivial to state in a form that cannot fail: call any line with no
  settled span "open", and nothing can ever be dropped. So the check is made against the region a
  conflict **started** with — settled spans plus the open remainder must be exactly that region — which
  makes a line neither half carries an expressible, detectable defect. `ResolutionPass.withPartial`
  therefore takes **both** halves rather than the remainder alone: a state that could not represent the
  mistake could not detect it either. Likewise `withResolved` records an unplaceable span instead of
  refusing it, so `Region.unknown()` marked as settled is reported rather than made unsayable.
- **`PartitionReport` reports defects rather than a boolean** — dropped lines, lines settled twice,
  spans outside the block, spans with no location — because "which line" is the only useful thing to know
  about a dropped line, and a bare `false` sends the reader back to the spans to find out.
- **A finding from implementing it, and the design text was wrong: the run order cannot come from the
  record.** A tier can only be skipped *before* it runs, and a recorded level does not exist until the
  resolver has been called — so ordering the pass by the record means calling every resolver, which is
  the behaviour § 4C exists to remove. The two facts are therefore kept apart on one scale: the
  **declaration** orders the pass (`ConflictResolvers.inTierOrder`), and the **record** decides what a
  claim is worth. The declaration is safe in that role because a resolution may never record above it
  (already asserted) and because a declaration settles nothing by itself. Corrected in
  [`HIERARCHICAL_RESOLUTION.md` § 3.2](../merge-java/docs/HIERARCHICAL_RESOLUTION.md) and
  [`DEC-046` clause 13](../doc-hipster-entity/architecture/decisions/DEC-046.md).
- **`Region.covers(Region)` became the one definition of coverage**, with `MergeFileTool.coversRegion`
  delegating to it. The outranking rule and the partition ask the same question about different pairs of
  regions, and answering it twice is how the two answers would drift.

**Gate so far:** `ResolutionPassTest` — 13 tests: the fresh pass is entirely open; a block may be larger
than the union of its conflicts' regions without that being a defect; a resolved conflict is absent from
`live()`; a partial one keeps only the remainder; a dropped line reports lines 13 and 14; a line settled
twice reports the overlap; a span outside the block reports; an unknown region marked settled reports as
unplaced while an unknown region merely open does **not**; the run order is by declared level and ignores
`priority()`; registration order holds within a tier; the shipped resolvers are ordered strongest to
weakest with none dropped; and the declaration/record split is expressible. `merge-java` — **806 tests,
0 failures, 0 errors** with the build cache off.

### 4.19 — Reliability and removal: a resolved region takes the conflict out of the lower tiers' hands
**Who:** agent · **Size:** M

**Do:**

1. **Decide removal on explanation, never on coverage.** A claim resolves a region only where one of
   § 3.3's three ways holds, and the check is made against the claim's **own** evidence: the declarations
   it recognised, the lines its applied text keeps, or the block it owns. A claim that can establish none
   of the three resolves nothing and is arbitrated exactly as it is today. **`AUTO` resolves; `DEFERRED`
   resolves at the top of the order because a recorded human decision is a decision; `REVIEW` and
   `MANUAL` resolve nothing** — so the hierarchy can never promote an answer into application.
2. **Implement reliability as the one predicate the whole design rests on** (`HIERARCHICAL_RESOLUTION.md`
   § 3.2): the claim may be applied, it explains every line of the region, and the region is known. A
   rank entitles a resolver to be asked **earlier**; it never entitles it to remove anything. Declining
   stays the same vocabulary — the resolver saying it is not reliable here — so a resolver that declines
   removes nothing and needs no new mechanism.
3. **A conflict the claim spans completely leaves the working set.** Detection still emits; the pass
   decides what the lower tier is **handed**. The two acceptance cases from the instruction are the
   tests: (a) two branches adding whole methods at the same place — `MEMBER_ADD` recognises the
   declarations, the region is marked `RESOLVED`, and the `API_INCOMPATIBILITY` objection to those lines
   is **never produced**, asserted by the resolver not being called rather than by the outcome text;
   (b) a successful import resolution — `ImportConflictResolver` resolves the import region, and neither
   the residual nor a text-level claim is asked about it.
4. **Report the state of every conflict.** `open`, `resolved` (with the claim and its level) or
   `partial` (with the remainder that stayed open), beside the existing `analysisLevel` and `warnings`
   keys — because a removed conflict leaves no other trace, and "this tier had nothing to say" must not
   read like "this tier was never asked". The wording stays the page's (DEC-027).
5. **Keep `outranking` for what the hierarchy cannot separate.** Two claims at the same recorded level
   about one still-open region go through DEC-045's rule unchanged, including "equal evidence decides
   nothing". This is a narrowing of its blast radius, not a replacement.

**Gate:** `MODULE` for `merge-java` green, with: both acceptance cases showing the lower-tier resolver
was **not called**, asserted on the pass rather than on the outcome text; a **negative control** where a
claim whose evidence explains only part of the region removes nothing and the block is left as it is
today; a `REVIEW` claim proved unable to remove anything; a `DEFERRED` claim proved to resolve at the top
of the order; a test that a removed conflict still went through `ResolutionVerifier` and is downgraded
when it fails; and the existing `MergeFileToolTest` and step-4.6 arbitration tests unchanged.

**Done when:** a higher tier's reliable answer takes its region out of the lower tiers' hands — the
lower resolver is never called about it — and a reviewer reading the report can see the state each
conflict reached and the claim that reached it.

**Done 2026-10-07, in two commits — the predicate and the tiered pass.** The first half is the vocabulary
(`explainedSpan` and `Reliability`); the second is the behaviour change, and it is what makes "the lower
resolver is never asked" true rather than merely reported.

- **A claim now says what it explains** (`ConflictResolution.explainedSpan`, plan step 4.19's missing
  fact). It is *not* the region the resolver was asked about: a claim may settle a region only where its
  own evidence accounts for every line of it, and the previous model had no way to say that, which is why
  "spans the whole merge conflict region" was unanswerable. **The default is `Region.unknown()`, and the
  default is load-bearing** — a span that defaulted to "the whole region" would let every claim settle
  every conflict it was asked about, which is how a confident sentence becomes a licence. An answer that
  keeps every line of both sides needs no declaration at all, because that case is checked instead.
- **`AbstractConflictResolver.explainedSpanFor(Conflict)`** is the single place a resolver declares it,
  applied in `resolutionFor(...)` so every resolution a resolver builds carries it. The default is
  nothing, and the hook's javadoc says why the two declarations are different: `maxAnalysisLevel()` says
  what a resolver *reads* and orders the pass; this says the answer *is* the region's content and settles
  it. Overridden by exactly two resolvers, which are the instruction's own two examples:
  `MemberAddConflictResolver` (the declarations it parsed, after proving they do not collide) and
  `ImportConflictResolver` (the import block, its own domain).
- **`Reliability`** is the predicate, as its own type: may be applied (`AUTO`, or `DEFERRED` as a recorded
  human decision — **never** `REVIEW`/`MANUAL`), the region is known, and the claim explains every line of
  it — either its explained span covers the region, or its applied text keeps every line of both sides.
  The four verdicts (`RELIABLE`, `NOT_APPLICABLE`, `UNPLACED`, `UNEXPLAINED`) each carry a one-sentence
  reason, so a report can say *why* a conflict was or was not removed rather than only that it was.
  **It does not read the level**, which is clause 11 as code: a resolver can buy being asked early and can
  never buy authority.
- **`Reliability.normalisedLines`/`keepsEveryLine` are now the one definition** of "did this line survive",
  with `MergeFileTool` delegating (its private copies are deleted). The coverage rule and the new predicate
  ask the same question about the same text, and two answers is how they would drift.
- **`TieredResolution`** is the pass itself: it walks `ConflictResolvers.inTierOrder(...)` and asks a
  `ClaimSource` about each still-live conflict, so a conflict an earlier tier settled is **never offered**.
  A settled conflict's slot is left null — it has no claim of its own, because nothing was asked about it —
  and `MergeFileTool` decides on the claims that exist rather than on the conflicts that were detected. The
  two acceptance cases are `TieredResolutionTest`'s: the source is asked about index 0 and **not** about
  index 1. Asserting on the outcome could not tell "never asked" from "asked and overruled", which is the
  whole distinction this step makes.
- **The block's explanation now names every removal** (`removalNote`), which is DEC-046 clause 7 in the
  smallest honest form: a settled conflict has no claim, so nothing else in the report would mention it, and
  a block that came out applied would otherwise read as if the weaker tier had simply found nothing.

**Two bounds had to be added, and the tests are what found them. Both are in
[`HIERARCHICAL_RESOLUTION.md` § 3.2a](../merge-java/docs/HIERARCHICAL_RESOLUTION.md) and DEC-046's second
amendment.**

- **The kept-lines way was judged against the wrong sides.** The first `Reliability` read "keeps every line
  of both sides" as the sides the *claim* was built from — and a resolver's answer almost always contains
  those, so any claim was "reliable" over *any* region, including one three hundred lines away. The sides
  that matter are the settled conflict's, which is why the predicate takes a `Conflict` and cannot take a
  `Region`: a region is not enough information for the question.
- **Removal had no direction.** Running step 4.6's arbitration tests showed a claim that recorded
  `TEXT_LOCAL` settling a `STRUCTURE` conflict, which turned `equalEvidenceOutranksNothing` — a block left
  for a human — into `APPLIED_AUTO`. A mechanism that widens what the tool decides by itself, with nobody
  deciding it should, is the failure this module's history is a list of. So a claim speaks for conflicts at
  or below its own recorded level and never above it, a type no resolver in the set owns is settled by
  nothing, and a claim settling **its own** conflict is exempt (a resolver that recorded `STRUCTURE` while
  declaring `PROJECT_TYPES` is still the authority on the question it was asked). With the bound in place
  **no step-4.6 test needed changing** — `strongerEvidenceOutranksWeakerObjection` passes untouched, because
  its restricted resolver set owns no `STRUCTURAL_CHANGE` resolver, and the removal path appears only where
  the module's full resolver set is in play.

**Then the instruction's own example was driven end to end, and it did not work.** `HierarchicalAcceptanceTest`
runs the real resolvers over two branches adding a whole method in the same place, and the first measurement
was: the block applied, and the report read *"Outranked on this block by stronger evidence
(PLATFORM_TYPES)"* — the text tier **was** asked. Two more findings, both in `HIERARCHICAL_RESOLUTION.md`
§ 3.2b and DEC-046's third amendment:

- **An insertion has no base lines, so a conflict that cannot be placed is judged over its block.** `Region`
  is a range of *base* lines, and the instruction's example is a `diff3` hunk whose base section is present
  but **empty** — so every base-line question about it is unanswerable by construction, and the removal rule
  could never fire. The block is the coordinate system that can represent an insertion, and it is the region
  the instruction itself names. The claim must still explain it (explained span covering the block, or the
  applied text keeping every line of the settled conflict's two sides) and still be at or above its tier, and
  nothing here reads region *coverage* — so this is not the stamped-region trap the arbitration rule warns
  about. With it, the acceptance test passes and the text tier is **cleared**.
- **A resolver must be handed the region it actually recognised.** The stamp that restates a conflict's region
  in file coordinates exists for the report, and it was being applied *before* resolution — so every resolver
  declaring an explained span appeared to explain its **whole block**, and `ImportConflictResolver` settled an
  unrelated `COMMENT_ADD` conflict (`multipleAutomaticResolutionsLeaveTheBlock` caught it, coming out
  `LEFT_PARTIAL_RESOLUTION` instead of `LEFT_MULTIPLE_AUTOMATIC`). Fix: hand the resolver the detector's
  conflict and restate the region at the report boundary. **The stamp is a presentation fact; applying it to
  the input of a decision is how a presentation fact becomes a decision.**

**Still outstanding, and named rather than implied:**

- **The per-conflict `resolved`/`partial` state is in the block's explanation, not yet a report key** of its
  own beside `analysisLevel` and `warnings`. Clause 7 is satisfied in substance — every removal is named —
  but the report's *shape* does not carry the state, and 4.20 is where that shape changes anyway.
- **`DEFERRED` settles at its resolver's tier, not before every tier.** The gate asked for "at the top of
  the order"; a recorded decision arrives through `MergeConflictResolver`'s replay inside the tier loop, so
  moving it truly to the top means enumerating recorded decisions before the pass asks anything, which is a
  separate change. What holds today is the part that matters: a recorded human decision is reliable, it
  settles, and no lower tier touches the conflict afterwards.
- **The second example — imports — is served by a different mechanism, and that is worth knowing.**
  `StructuralChangeConflictResolver` declares `STRUCTURE`, which sits **above** `ImportConflictResolver`'s
  `TEXT_FILE`, so the residual is asked *before* the import resolver and cannot be settled by it; what stops
  the residual vetoing the import block is `residualSubsumed` (step 4.5). "No lower tier touches the import
  block" therefore holds for the `TEXT_LOCAL` conflicts, and the residual is a separate question: whether a
  catch-all that never decides anything should declare the weakest level instead of `STRUCTURE` is a decision
  for the maintainer, not a change to make quietly. **An import-region end-to-end fixture now exists** (`HierarchicalAcceptanceTest.aResolvedImportClearsTheTextTierForItsRegion`: one file, an import hunk both branches filled plus a body hunk nothing decides — `RESOLVED=1, OPEN=1`, `APPLIED_AUTO`, exit 1, both imports kept, the residue left for a person). **And building it showed the instruction's second example is not a *clearing* in the shape the sentence imagines**: detection produces ONE conflict over the import lines and `ImportConflictResolver` answers it, so there is no lower-tier conflict over that region for the hierarchy to clear, and "never asked" (the methods case) does not appear. What the fixture can assert is the guarantee that exists — settled, *scoped* to the region the claim explained, with the residue reported `OPEN` rather than removed — and what it therefore still cannot show is a file where a **lower-tier conflict overlaps the import region**. In this module that competition surfaces as `residualSubsumed` (step 4.5) rather than as a second conflict, which is why the fixture cannot be built the way the sentence reads..
- **A failed `ResolutionVerifier` run keeps a claim out of the hierarchy by construction**: verification
  runs inside `MergeConflictResolver.resolve`, a failure downgrades the answer to `REVIEW`, and a `REVIEW`
  claim settles nothing (`aReviewAnswerSettlesNothing`). **That composition is now fixtured**
  (`VerifierKeepsClaimsOutTest`): a stub answer with deliberately unbalanced code is downgraded to
  `REVIEW`/`FAILED` with the reason on the surface, and `Reliability` refuses it — while the control, the same
  stub with balanced code, stays `AUTO`/`PASSED`. The end-to-end half measured `LEFT_MANUAL`
  (`API_INCOMPATIBILITY`, markers left, exit 1) for a file with an unclosed member, so **unbalanced text is
  never applied as a success** whichever route it takes. **And the control taught one thing worth keeping**:
  the balanced answer is `PASSED` and still **not reliable** — verification asks whether an answer is
  well-formed, reliability asks whether a claim accounts for the region — so a well-formed answer that explains
  nothing settles nothing.

**Gate:** `MERGE-JAVA` green with: `TieredResolutionTest` — 8 tests, including the acceptance case (the
source is asked about one conflict and never about the settled one), the tier order, the negative control
(a claim that explains nothing settles nothing and both tiers are asked), `REVIEW` settling nothing, the
weaker-tier bound **as the defect it was**, self-settlement under the bound, an unowned type being neither
settled nor forgotten, and a declining tier being asked exactly once. `ReliabilityTest` — 13 tests whose
negative cases are the point. `HierarchicalAcceptanceTest` — the instruction's first example end to end with
the real resolvers: both methods kept, the block applied, and the text tier **cleared** rather than overruled.
And `MergeFileToolTest` — 31 tests, of which **one assertion changed and it had to**: the empty-base insertion
now reports "never asked" instead of "Outranked on this block", which is the improvement itself. Every other
step-4.6 arbitration test is untouched. `merge-java` — **917 tests, 0 failures, 0 errors** with the build cache
off.

### 4.20 — The state of every conflict in the report, and the shape the composition will need
**Who:** agent · **Size:** S · **Done 2026-10-07**

**This began as one step and is now two, and the split is the honest part.** The original 4.20 was "compose the
block from resolved regions and open lines, retire `LEFT_PARTIAL_RESOLUTION`, grow the outcome enum". Writing it
showed what that actually is: **text surgery on the person's file, in three coordinate systems**, where a wrong
offset does not fail loudly — it deletes a line of their code. The machinery exists (`MergeRangeBuilder` returns
`MergeChange` records carrying base, left and right line ranges, which is exactly the position information the
composition needs) but getting it wrong is the worst failure this tool can have, so it gets its own step with its
own tests. **That is 4.21.** What landed here is the part that needed no surgery and that a reviewer needs first:
the report can now say what became of every conflict, which is also 4.19's last named remainder.

**Do:**

1. **A `state` per conflict in the report**, index-aligned with the conflicts rather than with the resolutions.
   That alignment is the point: a conflict another claim settled **has no resolution of its own** — it was never
   asked about — so before this key it appeared nowhere in the report at all, and a reviewer saw a conflict that
   vanished rather than one that was cleared (DEC-046 clause 7). The key is omitted when a caller does not track
   states, because inventing one would be worse than saying nothing.
2. **The two states are asserted separately**, so "cleared" and "asked and undecided" cannot be confused: the
   instruction's own example reports `RESOLVED` for both of its conflicts, and a block nothing settles reports
   `OPEN`.

**Still to do in 4.21**, kept here so the split loses nothing: the composition itself, retiring
`LEFT_PARTIAL_RESOLUTION` for the shape it can now express, the outcome value for "applied in part", and the
file-level partition invariant over composed output.

**Gate:** `merge-java` green with `HierarchicalAcceptanceTest` asserting the report carries `RESOLVED` for the
cleared conflict and `OPEN` for an unsettled one. `merge-java` — **830 tests, 0 failures, 0 errors** with the
build cache off.

### 4.21 — The position model: the block's lines in three coordinates, and the invariant that tiles them
**Who:** agent · **Size:** S–M · **Done 2026-10-07**

**This is the "tests come first" the step demanded, and the split is again the honest part.** The composition
rewrites a person's file; the failure it can produce is not a wrong answer but a **deleted line**, with nothing
in the outcome text to show for it. So the coordinates were built and tested before anything splices, and this
step does no text manipulation at all.

**Do:**

1. **`BlockComposition`** places one block's lines in all three coordinate systems — base, ours, theirs — as an
   ordered list of `Segment`s. The coordinates come from `MergeRangeBuilder`, whose triples are
   `(left, base, right)` — the constructor argument order, which is not the order a reader guesses — each
   half-open and 0-based. The lines **both sides kept appear in no range at all**: they are the gaps, and a walk
   that emitted only the ranges would drop every one of them. That is the trap the type exists for, and
   `MergeRangeBuilder`'s own javadoc says so.
2. **`BlockComposition.audit` is the invariant**: every line of ours and of theirs — and every base line when the
   block has a base — is in exactly one segment. It reports the *lines*, not a boolean, because "which line" is
   the only useful thing to know about a lost one.
3. **`Kind` classifies who changed a stretch** (`UNCHANGED`, `LEFT_ONLY`, `RIGHT_ONLY`, `AGREED`, `CONTESTED`)
   from the range flags plus a text comparison rather than from `MergeRangeUtil.getMergeType`: that classifier
   answers "what is this change and could a text pass resolve it", which a *resolver* needs, while a composer
   needs only who changed it and whether the sides agree. Asking the bigger question here would drag a
   conflict's fate into a position model.
4. **It is consumed, not speculative.** The `LEFT_PARTIAL_RESOLUTION` explanation now describes the block in
   these terms — how many stretches are contested at all against how many neither branch touched — which is the
   distinction that says whether "rewrites only part of the block" is a fact about the answer or about the block.

**A finding that changes what 4.22 can do, and it is a fact about the module rather than a preference.** A
merge-style block has **no base** — git's default conflict carries only the two sides — and the ranges are built
*against a base*. With no base there are no coordinates to place a resolver's rendering between, so a base-less
block can be classified but **not composed**. The fixture that motivated this step,
`partialResolutionLeavesTheBlock`, is base-less: 4.22 will not fix it, and cannot. What 4.22 can fix is a
`diff3`/`zdiff3` block whose contested stretches are settled and whose untouched stretches merely need keeping.

**Gate:** `BlockCompositionTest` — 8 tests: eleven fixtures each proved to tile exactly (identical sides,
one-sided changes, both-same, both-different, an empty-base insertion, a base-less block, deletions, an empty
side, a shared tail), unchanged lines asserted to be **segments of their own** rather than gaps, an insertion at
an empty base placed rather than lost, and two invariants **proved to fail** — a dropped segment names the lines
that would have been deleted, and a doubled one names the side and the line. `merge-java` — **838 tests, 0
failures, 0 errors** with the build cache off.

### 4.22 — The splice: applied stretches beside markers, and the outcome the enum is missing
**Who:** agent · **Size:** M–L

**Do:**

1. **Compose the block from the segments 4.21 produced.** For a `diff3` block: `UNCHANGED` stretches from
   whichever side carries them (they are equal by construction), `LEFT_ONLY`/`RIGHT_ONLY` stretches from that
   side, `AGREED` stretches once, and `CONTESTED` stretches either from the claim that settles them (mapped
   through the claim's `explainedSpan`, which is in **base** lines and 1-based, against the segments' 0-based
   half-open base extents) or as conflict markers. `BlockComposition.audit` is the invariant the result is
   judged by, at the file level: every block line reaches the output exactly once.
2. **Retire `LEFT_PARTIAL_RESOLUTION` for the shape it can now express.** An answer that covers the contested
   stretches and leaves the untouched ones exactly as they were loses nothing, so it applies; the outcome stays
   only for an answer that leaves *nothing* settled. `MergeFileToolTest.partialResolutionLeavesTheBlock` stays as
   it is — it is base-less, and its explanation now says so — and a `diff3` fixture with two halves settled by
   different tiers is what the new outcome is asserted on.
3. **Grow the outcome vocabulary where a new result shape exists** (the maintainer's 2026-10-07 instruction that
   the outcome enum is to grow). "Some stretches applied, the rest left open" is a result the tool has never been
   able to report and is not any existing outcome; it gets a value of its own rather than overloading
   `APPLIED_AUTO`. An outcome the enum can no longer produce is **removed, not left as a synonym**.
4. **The markers the splice emits are the shape the parser reads back.** A composed block that keeps markers must
   still parse as a conflict block on a second run, or the tool's own output becomes input it cannot read — the
   `FixtureAgentInstructions` path and `MergeWorkflow` both re-read files. That round trip is a test, not an
   assumption.

**Gate:** `MODULE` for `merge-java` green, with a `diff3` fixture block whose two halves are settled by different
tiers, showing the applied half in the output and markers on the open half; the file-level partition invariant;
a test that the new outcome is reported for exactly this shape; the composed result **compiling**; and the
composed block **parsing back** as a conflict block.

**Done when:** a mixed block comes out with the part the tool understood applied and only the genuinely open
lines marked, the outcome says which of the two it was, and the result can be merged again.

**Done 2026-10-07 — `BlockSplice`, `Outcome.APPLIED_PARTIAL`, and both halves end to end.**

- **`BlockSplice`** composes a block from 4.21's segments: `UNCHANGED`/`AGREED` once, `LEFT_ONLY`/`RIGHT_ONLY`
  from their side, `CONTESTED`-and-settled as the settling claim's text **emitted once at the first stretch it
  answers** (a resolver's text is its answer for the whole conflict, not for each range), and
  `CONTESTED`-and-unanswered as the four markers with the block's **own labels**. It refuses — returning empty
  rather than guessing — when the block has no base, when a contested stretch is only partly inside a settled
  span, when two claims answer one stretch, and when nothing is settled at all.
- **`Outcome.APPLIED_PARTIAL`**, and it is deliberately *not* one of the `APPLIED_*` values that
  {@code applied()} counts: the file still carries markers, so `exitCode()` is non-zero and the block is still
  prepared as a fixture. What changed is that the answer the tool has is no longer thrown away together with
  the part it does not have.
- **The trigger is every outcome that keeps the block** (except `REVIEW`, whose confirmation is a human's), not
  only `LEFT_PARTIAL_RESOLUTION`. Measured rather than assumed: widening it left **the whole suite green**, so
  no existing left-block path is disturbed, and a `diff3` block with a settled import stretch and a contested
  method body now comes out `APPLIED_PARTIAL` with the imports written *outside* the markers and the body
  inside them.
- **A defect found by probing, and it would have reformatted every composed block.** `TextLines` lines
  **carry their terminators** — the type exists so splitting and joining are inverses — and the first
  `append` added a newline of its own, doubling every line break in the output. The probe printed the composed
  text and the doubled blank lines were plain; no assertion would have caught it, because every assertion I
  had written was a `contains`. `BlockSpliceTest.untouchedLinesAreKept` now asserts the composed text is
  **not reformatted**, which is the assertion that was missing.
- **The base-less limit stands, and the fixture says so.** `partialResolutionLeavesTheBlock` is unchanged and
  passing: it is git's default merge style, so there are no coordinates to compose in, and its explanation now
  reports how much of the block is contested against how much neither branch touched.

**Gate:** `merge-java verify` green with: `BlockSpliceTest` — 9 tests, both halves, every refusal, an insertion
inside a settled span answered by position, and the composed block **parsing back** through
`ConflictMarkerParser` with the labels it was given; `HierarchicalAcceptanceTest` — 5 tests, the last two being
the two halves end to end; `partialResolutionLeavesTheBlock` unchanged. `merge-java` — **850 tests, 0 failures,
0 errors** with the build cache off.

**The § 4C block is complete: 4.18–4.22 all land.** What it leaves open is named rather than implied, and none
of it is required for the block's own claim: the per-conflict `state` covers `OPEN`/`RESOLVED`/`PARTIAL` but no
detail of *which* stretches a partial result left open beyond the count in the explanation; `DEFERRED` settles
at its resolver's tier rather than before every tier; an import-vs-`TEXT_LOCAL` fixture is still missing; and the
question of whether the catch-all residual should declare `STRUCTURE` at all is the maintainer's
([`DEC-046` clause 17](../doc-hipster-entity/architecture/decisions/DEC-046.md)).

---

## 9. Phase 5 — webview: close the suite

> **Every UI step in this phase builds on `jsx6`** (rule § 2.9, [`AGENTS.md` § 2](../AGENTS.md)): no UI is
> written against a remembered version of the library, and the checkout's own `AGENTS.md` is the guidance
> to follow. **Diagrams and relations use `jsx6`/`nodditor`** (DEC-027's 2026-10-01 amendment). Step 7.9
> sets the checkout up and step 7.10 records what the libraries can and cannot do — a capability they lack
> is **reported** there rather than worked around in a page. A webview step that finds the checkout missing
> should set it up rather than reach for another library.

### 4.6 — Quality level: a resolution records the evidence it rests on, and stronger evidence outranks a weaker objection
**Who:** agent · **Size:** L

Approved by the maintainer on 2026-10-07, in their words: "we need to introduce quality level to resolver, where
quality of analysis can decide if they do not concur on change", with the kinds of context that matter — local,
same file, compile versus text compare, structure recognised ("two methods in same place added are very confident
resolution where text can fail"), and compile with a classpath.

**Do:** the decision is [DEC-045](../doc-hipster-entity/architecture/decisions/DEC-045.md). In short: an ordered
`AnalysisLevel` on every resolution (`TEXT_LOCAL` → `TEXT_FILE` → `STRUCTURE` → `PLATFORM_TYPES` →
`PROJECT_TYPES`), declared as a maximum per resolver and recorded per resolution; and an arbitration rule in
`MergeFileTool.decide` under which an `AUTO` claim that covers the block and strictly outranks every other claim
applies — but only where it already accounts for what each claim was protecting, by region or by keeping every
line of that claim's sides. A `DEFERRED` claim is never outranked, and the level never promotes a `REVIEW` or
`MANUAL` into application, so [`DESIGN_NEVER_AUTO_RESOLVED.md`](../merge-java/DESIGN_NEVER_AUTO_RESOLVED.md) is
untouched.

**Gate:** `MODULE` for `merge-java` green, with tests for both halves — a claim that strictly outranks a weaker
objection applies and names what it outranked, and equal evidence still leaves the block for a human. Plus the
invariant that no resolution records a level above its resolver's declared maximum.

**Done when:** a block whose real answer is better founded than the objection against it is applied, and a
reviewer can see which claim lost and on what evidence.

**Done 2026-10-07 — the scale, the declarations, the arbitration rule and the first structure-level resolver.**

- **`AnalysisLevel`** is a new ordered enum whose javadoc states, per level, what an answer was checked against and
  what it cannot know; nothing about it changes what may be applied.
- **The record is per resolution, the declaration is per resolver.** All ten registered resolvers now declare
  `maxAnalysisLevel()`; `TypeChangeConflictResolver` and `OverloadAddConflictResolver` record the weaker level they
  actually reached when no project classpath was supplied, and `TypeContext.hasProjectEntries()` — derived from the
  entries, so every factory keeps its meaning — is the fact that separates the two type levels.
- **The arbitration rule reads the detector's regions, not the report's**, and that is load-bearing: the first
  version read the stamped regions, every claim then shared one region, and `unexplainedResidualStillVetoes`
  failed — the block applied and `retries = 7` would have been dropped. The failing test is what located it.
- **The payoff resolver is `MEMBER_ADD`.** Two branches appending a method beside the one the base declared
  produce adjacent insertions, which a line-based comparison reads as "both sides replaced this region" — true and
  useless — while comparing the declared members shows the additions do not interact. Measured before it existed:
  `block 1 (lines 4-24): LEFT_MANUAL STRUCTURAL_CHANGE`, with the residual as the block's **only** claim, so
  nothing was available for the scale to arbitrate between; that is the honest reason the scale alone changed no
  behaviour. `detectMemberAddConflicts` recognises the shape, `MemberAddConflictResolver` answers `KEEP_BOTH` at
  `STRUCTURE`, and the same fixture now reports `APPLIED_AUTO` with `audit()` once, `charge()` and `refund()` both
  present, and the merged class compiles. Detection requires a **base side** and no removal on either side, so an
  addition is never confused with a deliberate deletion, and a shared signature is refused because only one member
  can exist.
- **A pre-existing defect fell out of it, and it was live.** `KEEP_BOTH` was implemented as concatenation, but both
  sides carry the members the base already declared, so keeping both repeated them: the merged class declared
  `audit()` twice and javac rejected it, and `OverloadAddConflictResolver` did the same to `process()` on the
  canonical overload sample. Both now build the union through `SideUnion` — longest shared prefix once, then each
  remainder, then a structural check that no member appears twice — and **refuse** the block when that cannot be
  proved, because emitting code the compiler rejects is worse than leaving the block for a human. Worth noting how
  it was found: the arbitration rule *prefers* the type-level overload answer over the structure-level one, so this
  step is what made the broken union reachable through the path it now favours.
- **The second pass fixed a gap in the first, and it was the ordinary case.** `MEMBER_ADD` as first shipped
  required a non-blank base, and a pure insertion has none: measured on a `diff3` hunk whose base section is
  present but empty — two adjacent additions, which is what a real merge produces — the block still came out
  `LEFT_MANUAL`. A blank base means two different things and only the marker parser knows which, so
  `detect(...)` gained an overload carrying it (`MergeFileTool` passes `block.hasBase()`, the four-argument
  overload keeps the conservative reading): **present and empty** is evidence that both sides inserted, **no
  base section** is an unknown base and is declined. The ordinary insertion now applies, and the text-level
  `API_INCOMPATIBILITY` objection is outranked — the evidence scale finally doing the job it was built for.
- **Fields are members too, with two guards against reading a statement as one.** A local variable and a field
  are the same text, and "keeping both" two locals would concatenate two competing bodies and call it a member
  addition — so a fragment that declares a method or a type establishes the member level by depth, and a bare
  insertion without one is read from its *modifier*, because a local cannot be declared `private`. A
  package-private field in a bare insertion is declined rather than guessed.
- **Parameter spelling is left to the resolver that resolves types.** Additions whose method names match are
  declined here, and `OverloadAddConflictResolver` answers them from resolved parameter types — verified:
  `process(List<String>)` against `process(java.util.List<java.lang.String>)` resolves to one signature and is
  refused as a collision rather than kept twice.
- **The `analysisLevel` key is now rendered** by the review page, with the wording owned by the page (DEC-027),
  verified by building the page from a real report: `evidence: resolved platform types` and
  `evidence: the block's own lines` appear beside the resolutions they describe.
- **Still outstanding, and named rather than implied:** additions whose lines carry no access modifier and no
  enclosing declaration in the fragment are declined, because there is then no evidence to tell a field from a
  local; and a merge-style file with no base section at all never reaches `MEMBER_ADD`, by design.

**Gate so far:** `AnalysisLevelTest` — 6 tests (the ordering; no resolution above its resolver's maximum; the two
never-deciding resolvers object at their own basis; a declining resolver records what it actually reached; only a
caller-supplied entry separates the two type levels; a type change records which classpath it had).
`MergeFileToolTest` — 28 tests, including `strongerEvidenceOutranksWeakerObjection` and
`equalEvidenceOutranksNothing`. Decision record:
[`DEC-045`](../doc-hipster-entity/architecture/decisions/DEC-045.md).

**Companion, and the next thing in this area: § 4C (steps 4.18–4.22).** This step says how claims are
*compared*; the instruction of 2026-10-07 says they must not all be *produced* in the first place. The
scale built here is the order § 4C runs in — a claim's tier is the level it records — and
`outranking` survives there for the one case the hierarchy cannot separate: two claims at the same
recorded level about a region that is still open. Read [`DEC-046`](../doc-hipster-entity/architecture/decisions/DEC-046.md)
before extending this step's rule.

### 5.1 — Phase 6: headless parity as a build gate
**Who:** agent · **Size:** M

[`PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md) Phase 6 asks that "headless lacks nothing" be
a **test result** rather than a README claim. Today
[`smoke-test.mjs`](../webview/kit/examples/smoke-test.mjs) contains no reference to `webviewd`,
`applyEdit` or `undo`, so it drives none of the verbs.

**Do:** extend it (CDP, no dependencies) to drive **every** verb against a headless
[`webviewd`](../webview/core/webviewd) and assert the result, and add the JetBrains/VS Code capability
declarations to the same test so a host that declares a verb it does not implement fails the build.
Point the parity claim in [`webview/README.md`](../webview/README.md) at the test that proves it.

**Gate:** the extended test runs green, and `bun scripts/mvn-jdk25.js -pl webview/core/webviewd -am verify`
plus `node webview/check-links.mjs` are green.

**Done when:** the parity claim is a test result.

**Done 2026-10-08 — the parity claim is a test result, and the test now checks its own coverage.**

- **The extension went into `webview-client.test.mjs`, not `smoke-test.mjs`, and the reason is worth keeping:** the
  step named `smoke-test.mjs`, but that file drives **pages** in Chromium with no host at all — the live headless
  `webviewd` harness is `webview-client.test.mjs`, which starts the host itself and calls the same client functions a
  page calls. Extending the page test with host verbs would have put a second host launcher in the repository.
- **`redo` was the verb the client exposed and nothing drove.** Measured before this: `proposeEdit`, `applyEdit`
  (disk and the refused buffer target), `undo`, `open`, `read` and `/health` were driven; `redo` existed in the client
  and had no test. It is driven now, with the pair asserted as a **pair** — undo, redo (the edit is back), undo again
  (the original bytes are back) — because a one-way undo would pass the old assertions.
- **The coverage assertion is what makes the claim hold tomorrow, not only today.** The test lists the verbs a
  headless host supports (`read`, `proposeEdit`, `applyEdit`, `undo`, `redo`, `open`), records every call, and asserts
  at the end that **none went undriven** — naming the missing verb rather than reporting a count. A verb added to the
  client is therefore either driven or deliberately left out; it cannot quietly stop being exercised.
- **The capability half is a *content* check, and it deliberately complements what already existed.** The repository
  already had `webview/tools/check-capabilities.js` (*declared ⇒ served, undeclared ⇒ refused*, 29 assertions) and
  `HostHealthParityTest` (every host builds its body through `HostHealth`, and refuses an unauthorized `/open` before
  reading anything — by source reading, and it says so). What the client test adds is the mapping from each capability
  name to **how it is proved**: `open` and `serveFile` driven here, `select` **IDE-only** with a pointer to
  `webview/doc/ide-observation-checklist.md` — and the assertion that the checklist **exists**, so "observed by a
  person" is a record rather than a promise. An unknown capability name fails the run.
- **The parity claim in `webview/README.md` now names the three checks that back it** instead of describing the test
  only.
- **Measured**: `node webview/kit/examples/webview-client.test.mjs` — **37 passed, 0 failed** (from 33: `redo` plus
  the four coverage/proof assertions); `bun scripts/mvn-jdk25.js -pl webview/core/webviewd -am verify` — **58 tests,
  BUILD SUCCESS**; `node webview/check-links.mjs` — 242 links, **ALL LINK RESOLVE**;
  `bun webview/tools/check-capabilities.js` — **29 passed, 0 failed**.
- **One environment note, because the test's own diagnostic is what found it:** this machine's `JAVA_HOME` is **JDK
  21** while the jar is JDK 25 bytecode, so `WEBVIEWD_JAVA` must point at `C:\Program Files\Java\jdk-25\bin\java.exe`.
  The test prints exactly that (with the `UnsupportedClassVersionError` behind it) rather than "no port appeared",
  which is why a minute of confusion did not become ten.
### 5.2 — Record the two open questions as decisions
**Who:** agent + maintainer · **Size:** S

Q2 ("which hosts are in scope for the write verbs") is answered by delivery — every host has them — so it
only needs the plan to say so. **Q3** (do the JetBrains and VS Code plugins become proxying adapters, or
keep their in-process implementations and share only the core?) is still unanswered, and it changes a boundary.
**Q5 is answered**: the `vscode-jwa` / `vscode-jswa` / `intellij-jwa` / `intellij-jswa` clients did move under
`webview/` (DEC-039's amendment), and steps 3.0p/3.0q then merged the two capabilities worth keeping into the
hosts this reactor ships and deleted all five directories.

**Do:** put Q3 to the maintainer, then record the answer where it binds (the webview host API document, or a DEC
if it changes the boundary). Q5 is settled and recorded above; its module-map half is done
([`doc/architecture/module-map.md`](../doc/architecture/module-map.md) no longer lists the five deleted
attempts). Update the plan's § 10 with the answer and drop the questions that are settled.

**Gate:** `LINKS` green; the plan's status line no longer lists Q3/Q5 as open.

**Done when:** no open question in the plan lacks an answer or a named owner.

### 5.3 — The ACP go/no-go spike (human)
**Who:** human (Zed 1.21, from a normal shell) · **Size:** S

Phase 5's spike "ends in a written go/no-go" and no such document exists. Run it as the plan describes
(`webviewd --acp` registered as a custom agent, `initialize` / `session/new` / `session/prompt`, one tool
calling the same command layer as `/api/v1`), then write the verdict — including "no, and here is why" —
into the plan's Phase 5 record.

**Gate:** the written verdict exists and the plan links to it.

**Done when:** ACP is either scheduled as its own plan or dropped with a reason.

### 5.4 — Eclipse Phase 5: the p2 update site (optional, gated on Q2)
**Who:** agent, after a human answers Q2 · **Size:** M

**Do not start this before Q2 is answered**: if a p2 update site is required from day one, this phase
moves ahead of the observations; if `dropins/` is acceptable, it stays optional and last. When it is
taken: a Tycho build of a feature + p2 update site (`category.xml`) as a **separate Maven profile**, so
the ordinary offline test loop keeps working and the packaging never becomes the thing that makes the
host buildable.

**Gate:** a clean Eclipse installs the host from the generated site with `dropins/` empty, and the Phase 1
navigation observation repeats against that install. `PLAN-eclipse-host.md`'s Phase 5 and Q2 record the
outcome either way.

**Done when:** either the site is delivered, or Q2 was answered "dropins" and the phase is closed as
declined.

---

**Re-done 2026-10-08 for the table as it stands — items 1–3, with item 4 still deferred by its named trigger.**

1. **§ Progress is ticked from records, and one row that never existed was added.** Four rows — **3.4, 3.5, 3.6,
   3.7** — said `[TBD] — waits on …` while their own bodies read **"Done 2026-10-03"** and named the shape they had
   demanded (for 3.4: *"the spelling is `@Circular Supplier<Bean>`, and the cycle runs"*). They are ticked from that
   record; **3.8, 3.9 and 3.11** stay `[TBD]`, which is now accurate rather than inherited. **Step 9.8 had no row at
   all** — the plan carried a § 9.8 section and no line in this table, so the one step reserved as the final
   validation could never be ticked; it has its row now, found by running 9.5 rather than by looking for it. The
   criterion is unchanged and kept: a tick needs a **dated record in the step's own body**, never a `**Done when:**`
   line, which is a condition rather than evidence.
2. **[`plans/README.md`](README.md) carries the outcome line again**, with the same numbers as the plan — **74 of 92**
   steps done, and the 18 that are not waiting on people rather than on the schedule. Both numbers are **derived from
   the table and written back**, not hand-added: an earlier attempt in this same step logged four ticks it never
   wrote into the file (the cell was rebuilt and not assigned), which would have left a status line that agreed with
   itself and not with the table. That is the failure this step is *for*, so it is recorded rather than tidied away.
   Replacing the old outcome line also revealed that it was multi-line: its first line was replaced and its tail was
   left orphaned below, which is fixed.
3. **The plan STAYS where it is**, by its own rule: 18 steps are not done, so it is a live document and not an
   archive candidate. The archive condition ("every step is closed or explicitly dropped") is false, and the reason
   is written in the file rather than left to a reader to infer.
4. **Item 4 stays deferred, with its trigger named**, exactly as the 2026-10-03 record left it: it exists so a
   cross-link does not outlive its schedule, and while the schedule is live those pointers are the index *to* it
   rather than residue of it. The trigger for doing it is the plan being closed or archived.

**The state this leaves**: 74 done, 1 in progress (4.19, one named remainder), 14 open and 3 `[TBD]`. The open ones
are the maintainer's decisions (5.2, 5.4, 6.1, 6.3) and the steps that follow them, four human observations (8.1–8.4),
the `jsx6`/nodditor pages (3.8, 3.9) and the decision deliberately not taken (3.11) — plus **9.8**, whose subject
the section itself describes as *"not verified and not meant to be"* until it runs.

## 10. Phase 6 — hipster-entity roadmap rows

Each of these is a row of
[`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) § 1 or a `Proposed`
row of its § 3. Close them in the tracker in the same commit.

### 6.1 — `FieldAnnotation` exposure in generated view enums
**Who:** agent · **Size:** M

The row is open and nothing named `FieldAnnotation` exists in the tree. Decide the shape first (a
generated enum constant carrying its annotations as metadata), because it changes generated output — a
DEC-022 naming-contract table entry comes with it.

**Gate:** `GATE` green, the generated example carries the annotations, and the tracker row says what
landed.

**Part done 2026-10-08 — DEC-047, the API, the emitter and its tests are in; the EXAMPLE half is blocked on a
finding about which generator path can see an annotation at all.**

- **The ADR came first, as the charter requires for a step that changes generated output**:
  [`DEC-047`](../doc-hipster-entity/architecture/decisions/DEC-047.md) decides the shape —
  `FieldAnnotation(String type, String arguments)` in `hipster-entity-api`, a **default**
  `FieldDef.annotations()`, and an override emitted only where a field carries something. Registering it exposed a
  second defect and fixed it: **the decisions index stopped at DEC-041** while DEC-042…046 exist on disk and are
  referenced by the plan and by `merge-java`'s docs, so the five most recent decisions were missing from the index a
  reader consults. All six rows (042–047) are in the table now, built from each ADR's own headers.
- **Implemented and verified**: the `FieldAnnotation` record (qualified name + the raw argument text, because the
  semantics of `@Size(min = 1, max = 64)` belong to the validation provider and not to this generator);
  `FieldDef.annotations()` defaulting to an empty list, so **every existing generated enum compiles unchanged**; and
  `FieldBoilerplateGenerator` emitting the override — in declaration order, `@Override`-annotated, spliced as text —
  **only** for a field that has annotations. `FieldAnnotationExposureTest` asserts all three properties: the
  metadata with its qualified names and raw arguments, the declaration order, and **exactly one** override for a view
  with one annotated field. The DEC-022 naming-contract table in the tooling README gained the row: `annotations()`
  is wired by `@Override`, so renaming the API method **breaks the generated file's compilation** rather than
  letting the metadata drift — the strongest form that table has, and the row says so.
- **The finding that keeps this step open, and it is worth more than the rest of it.**
  `EntityMetadataGenerator`'s CLI path builds its properties from the **metadata JSON**
  (`viewNode.path("properties")` → `propNode.path("constraints")`), while the `generate(...)` API path that the tests
  use parses the **source**. So the two paths disagree about a freshly written annotation: the source path emits the
  new `annotations()` override, and the CLI path cannot, because its input JSON does not carry the constraint yet.
  `ExampleRegenerationTest` is the guard that made this visible — annotating one example accessor made the committed
  enums differ from a source-based generation — and it is also why the example is currently **unannotated**: keeping
  the tree green was worth more than a demonstration that would have asserted a difference nobody can regenerate.
- **A correction I owe the record, measured rather than assumed.** I reported that the example half could be
  finished without a decision, by regenerating the committed enums through the *source*-parsing `generate(...)` path
  (which `ExampleRegenerationTest` treats as the definition of "committed == generated"). **That is not reachable
  from the documented entry point**: `bun scripts/gen.js` runs the CLI, the CLI is the JSON path, and its input JSON
  is stale — measured: the pass printed *"Writing generated java to: …"*, the index reported 16 content changes, and
  the enum was unchanged, with no `annotations()` override. So the example annotation and the `provided`
  `jakarta.validation-api` dependency it needs (mirroring the tooling's own scope) were **reverted rather than left
  in place**: a green tree with a named blocker beats a demonstration that cannot be regenerated. Both return with the
  fix, and that fix is now **step 6.6**.
- **Remainder, precisely**: (1) find and run the **source → metadata JSON** pass (the CLI is the JSON → java half; the
  example's JSON in the checkout is dated 2026-09-26 and the subtree is derived/ignored), (2) annotate one example
  accessor, (3) regenerate so its enum constant carries `annotations()`, (4) confirm
  `ExampleRegenerationTest` is a byte-identical no-op again, and only then (5) is the step's gate met — "`GATE` green,
  the generated example carries the annotations".
- **A misleading diagnostic, named and not fixed**: `bun scripts/gen.js` reports any late failure with the
  *"a failure naming GeneratorPreflight means…"* hint, while its own log showed the preflight **ok**, the generation
  successful and the HTML index at *"301 links verified, 0 rejected, 0 stale"*. The failing step was the last one, and
  the hint sent the reader after the tooling version instead — the kind of advice that costs an hour.
- **Measured**: `FieldAnnotationExposureTest` **3 tests** and `ExampleRegenerationTest` **9 tests** green together
  (12, BUILD SUCCESS) on a run that executed them, and the **recorded `GATE`** (`bun scripts/mvn-jdk25.js`,
  whole reactor) is **BUILD SUCCESS** with a real 13:42 rebuild of `hipster-entity-tooling`.
- **The gate failed once, on my own ADR, and the failure is worth recording because it is a defect class this
  repository tests for**: DEC-047 linked `DEC-030` as `DEC-030.md`, while that decision's file is
  `DEC-030-openrewrite-source-representation.md`. `check-repo-links.mjs` was green — the link is relative and the
  *file it names does not exist* — which is exactly what
  `DocConformanceTest.theDocumentationIndexesPointAtFilesThatExist` exists to catch ("a docs index that links to a
  missing file costs a reader a dead end"). One line, and the link checker alone would never have found it.

### 6.2 — Deep change tracking: the generator wiring (task 6.5) and a patch applier
**Who:** agent · **Size:** L

[DEC-024](../doc-hipster-entity/architecture/decisions/DEC-024.md)'s runtime half landed; the delivery
note says the **generator** wiring (task 6.5) and a patch applier are not part of it.

**Gate:** a generated view exposes the deep-tracking path end to end, the RFC-6902-like patch can be
applied, and `GATE` is green.

### 6.3 — Decide contract enforcement: advisory rules → hard failures
**Who:** agent + maintainer · **Size:** M

Two rows ask the same question: whether the marker/interface conventions become **enforceable** rather
than advisory. Today `--validate` reports and `--validate=STRICT` fails, and the example's build runs the
former. Decide per rule (the core entity contract; the view hierarchy naming rule), record the decision,
and make the example's build run whichever mode was chosen — a rule that is never fatal is a suggestion,
and saying so is the point.

**Gate:** the decision is recorded (DEC or tracker), and the example's build exercises the chosen mode.

### 6.4 — Type divergence analyzer + converter manifest generation
**Who:** agent · **Size:** L

Open, and unimplemented: no `TypeDivergence` type and no converter manifest exist. DEC-006 is the
governing decision (`Proposed`); this step starts by either accepting it or amending it, since it fixes
the diagnostics and the registry shape.

**Gate:** the analyzer reports divergence in DEC-022's format for the example module, the manifest is
generated and committed, and `GATE` is green.

### 6.5 — Projection + DTO marker pattern for SQL/NoSQL direct JSON output
**Who:** agent · **Size:** L

Open; DEC-003 and DEC-007 are both `Proposed` and both need their adapter shape and benchmark criteria
settled first.

**Gate:** the pattern is implemented on the example module, its DTO path is asserted by a test, and the
two decisions are updated to match what was built.

---

### 6.6 — One staleness pipeline: a tiered gate, a watch entry and an on-demand entry
**Who:** agent · **Size:** M

**Why it exists, and why it holds up 6.1's example half.** Step 6.1 emits an `annotations()` override from the
constraints the generator knows, and the two generator paths disagree about where those come from: the
`generate(...)` API path parses the **source**, while `EntityMetadataGenerator`'s CLI path builds its properties from
the **metadata JSON** (`viewNode.path("properties")` → `propNode.path("constraints")`). Measured 2026-10-08: with a
constraint newly written on an example accessor, the CLI run printed *"Writing generated java to: …"*, the class index
reported *"16 content change(s)"*, and **no `annotations()` override appeared** — because `<Marker>.metadata.json`
was last written **2026-09-26** and the pass that writes it is gated on `pendingDocuments`, which did not notice a
changed **view**. A pipeline that silently regenerates from stale metadata is the build cache's defect class (step
9.8) in another place: a green run answering for a change it never saw.

**The maintainer's design (2026-10-08), recorded as given — the gate is tiered, cheapest first:**

1. **mtime + size** from the ledger: one `stat`, no file read; identical means not stale, and the check stops there;
2. **hash** (wyhash over LF-normalised content, DEC-029 § 4) — computed only when mtime or size differ, because a
   touch is not a change;
3. **rebuild** the item — only when the hash differs.

The ledger today is `MetadataCache.FileInfo(path, checksum, lastModified)` with the line format
`checksum \t lastModified \t path`: **it carries no size**, so it gains one, and the reader tolerates legacy
three-column lines by backfilling the size on the next write. `.jcodebuddy/index/mtimes.json` (layout pinned by
`IndexLayoutTest`) is the other mtime ledger in play, and the two must not drift into different opinions about
"changed".

**Do — one staleness service, two entry points** (the shape chosen from the four offered):

1. a **`Staleness` component** owns the tiered gate and answers *is this item stale, and why* — one implementation of
   the question, so watch mode and tools cannot disagree about what "stale" means;
2. **watch mode** calls it incrementally per event batch with the paths the watcher already has: a `stat` per touched
   file, a hash only where the stat moved;
3. **an on-demand entry** — `jcodebuddy metadata stale` to report, `… rebuild --stale` to repair, riding the existing
   `metadata parse` CLI/RPC surface (step 7.7) — so a tool that needs fresh metadata asks and triggers a rebuild of
   exactly the stale items instead of running a whole pass;
4. **the generator pass uses the same component** for its `pendingDocuments` decision, replacing whatever check it has
   today. That is what makes the example's metadata JSON refresh when a **view** changes, and it is the piece step
   6.1 waits on.

**Gate:** a changed view makes its item stale and a touched (same-content) file does **not**; the CLI verb and watch
mode give the same answer for the same tree; `GATE` green; and the example's metadata JSON is refreshed by a pass
rather than by hand.

**Done when:** "is this stale?" has one implementation both entries use, and a tool can trigger a rebuild of a stale
item without running the whole pass.

**First slice done 2026-10-08 — the tiered gate itself, self-healing, with the saving proven by a counter.**

- **The ledger already existed, so nothing new was invented.** `ClassIndex` writes `classes.json` (each row
  carrying the file's `size`) beside `mtimes.json` (FQN → last-modified, documented there as what "a watcher"
  reads), so the first tier had a place to read from and the question was only the order of operations.
- **`FileMetadata` gained `lastModified` without a format bump**, which is the deliberate half of the change: a new
  field whose absence has a defined meaning (`-1` = "unknown, so hash and decide") does not make an older entry
  unreadable, and the next store backfills it. Bumping `ENTRY_FORMAT` would have invalidated every warm cache in
  every checkout to gain nothing.
- **`MetadataCache.entryFor` now decides cheapest first**, exactly as specified: (1) the entry's recorded `size` +
  `lastModified` against a `stat` — equal means **reused with no read of the source and no hash**; (2) otherwise the
  content hash — equal means **touched, not changed**, and reused; (3) otherwise a miss and the caller parses.
  Before this, every lookup hashed every file on every pass, which is the one cost a warm rebuild still paid in
  full. `statHits()` reports the tier-1 count separately from `hits()` so the saving is **provable rather than
  plausible** — a test can assert that an unchanged file was answered by the stat alone, and that a touched file was
  a hit decided by the hash with a zero stat count.
- **A gap in my own design that the test found, now closed.** On a tier-2 hit nothing rewrote the entry, so a
  touched file — and every entry written before the timestamp existed — would have been hashed on **every** pass
  forever: a permanent cost with no symptom. `FileMetadata.withStat` + a private `MetadataCache.refresh` rewrite the
  entry from the stat just observed, and the refresh is deliberately **not** counted in `entriesWritten()`, because
  that number answers "how many files did this pass parse and store" and a refresh is not a parse.
- **Measured**: `MetadataCacheStalenessTest` **4 tests** (stat-only reuse; a touch reusing via the hash with
  `statHits() == 0`; an edit going stale and writing exactly one entry; a legacy entry without the field staying
  usable and backfilling itself) plus `BaseLayerTest` **8 tests**, and `jcodebuddy-core`'s whole suite
  **72 tests, BUILD SUCCESS** — including the index format contract, which is what proves the additive field is
  safe. The saving is proven by the counter; it is **not** claimed as a wall-clock measurement, because none was
  taken.
- **Remainder, precisely**: (1) **the generator's refresh decision** — `EntityMetadataGenerator`'s `<Marker>.metadata.json`
  must be rewritten when a **view** changes, using this component, which is what step 6.1's example half waits on;
  (2) the **on-demand entry** (`jcodebuddy metadata stale` / `… rebuild --stale`); (3) the **watch-mode entry**;
  (4) the whole-reactor `GATE` run — **now done**: `bun scripts/mvn-jdk25.js` over the whole reactor is **BUILD SUCCESS** (tooling 13:40 min, a real rebuild; `JCodeBuddy Core` rebuilt).
- **Where the next slice starts, with the evidence rather than a hunch.** The metadata JSON write itself looks
  **unconditional**: the loop over `pendingDocuments` in `EntityMetadataGenerator` (`for (EntityMeta entityMeta :
  pendingDocuments)` → `Files.writeString(outputDir.resolve(entityMeta.entityName() + ".metadata.json"),
  toJson(entityMeta, classIndex))`) sits after the index-summary `if (previousIndex != null)` and is not inside a
  condition. So the JSON *was* being rewritten, and the constraint was missing from the **facts** rather than from
  the write — and there are, again, **two paths that read constraints**:
  `propNode.path("constraints")` (`EntityMetadataGenerator` ~line 264, building a `Property` from the **metadata
  JSON**) and `ValidationGenerator.constraintsOn(method)` (~line 2349, building one from the **parsed accessor**,
  and the only caller of the reader). A view-declared accessor therefore reaches the emitter with its constraint on
  the source path and without it on the JSON path, which is the same asymmetry the step was opened for — so the
  next slice is to make **one** of those two the authority and have the other stop being a second answer.
- **Settled by the maintainer on 2026-10-08**: *the source path is the authority for a view-declared accessor*, and
  where **classes** are involved the metadata prefers the **full class name**. **Only half of that second rule is
  implemented today, and the record must not imply otherwise**: `FieldAnnotation.type` is already the qualified name
  (`FieldConstraint.qualifiedName()`, DEC-047 § 1), while the **`arguments` text is carried verbatim** — so a
  class-valued argument written with a simple name (`@ShapeOf(Rect.class)`) reaches a consumer as `Rect.class` and needs
  the declaring file's imports to resolve. Resolving class references **inside** the argument text against the parsed
  accessor's import table, and emitting them fully qualified, is a **named remainder** of this step rather than
  something already true.
- **Three hypotheses about the example were then tested and eliminated**, which is the useful part of this record —
  the cause is **still unidentified**, and none of these is it: **"the CLI reads constraints from the metadata JSON"**
  (eliminated: `fromJson`, the method that builds `Property` objects from the JSON, has **no production caller** — every
  call site is a test); **"the emitter or the source path drops it"** (eliminated: `FieldAnnotationExposureTest` emits
  the override on the ordinary source path); and **"the cooperative reconciliation preserves the existing constant
  body"** (eliminated by a **new test** that asserts the exact example scenario — pass 1 writes the enum with no
  override, the constraint is then added to the accessor, pass 2 must add it — and it **passes**; it stays as a guard
  whatever the eventual cause is).
- **What that leaves, and the next probe — with one more guess eliminated by reading the code rather than trusting
  it.** The first thought was that the **agent's** cache was the cause, since `bun scripts/gen.js` drives the example
  through tooling that keeps its own ledger (`hr.hrg.watch2.agent.core.MetadataCache`, `checksum \t lastModified \t
  path`). **It is not**: `hasChanged` implements exactly the tiers this step builds — same `lastModified`, then the
  checksum — so it is correct, and reading it also confirms the design independently. **The real difference is the entry
  point**: `gen.js`'s generator step is a **direct CLI run** (`java -cp … EntityMetadataGenerator <src> <meta>
  --java-out <src> --packages … --validate`), while every passing test — including the new second-pass test — goes
  through the `generate(...)` API. So the frontier is the **CLI's own write path** with its flags: which artifacts it
  decides to write, and whether `--java-out` + `--validate` short-circuits the field enums when the metadata it reads
  first is older than the source. That is a bounded question with the command in hand, not a hypothesis about a cache.
  **One dead end to skip**: do not build that probe's classpath with a hand-rolled `dependency:build-classpath` export.
  An ad-hoc export over this reactor produced a one-entry file and `NoClassDefFoundError` for `org.openrewrite.java.tree.J`,
  because the goal ran per module and the last module's output won the file. Take the classpath from the repository's own
  `classpathFrom` / `scripts/gen.js` path, or reproduce the case as a test — which is how every hypothesis above was
  settled, and is the cheaper instrument.
- **The probe was then attempted properly, and it answered a different question — worth recording so nobody repeats
  it.** With every reactor `target/classes` on the path, the run first failed as **Java 8** (`UnsupportedClassVersionError
  … up to 52.0`: `java` on `PATH` here is Java 8 while `target/` holds Java 25) and then as
  `NoClassDefFoundError: hr.hrg.hipster.entity.api.GenLevel` — a class that plainly exists in the tree.
  **`hipster-entity-api/target/` contains only its JAR and no `target/classes`**: the module was **cache-restored**, which
  is exactly the restore behaviour step 9.8 documented, now seen from a new angle — a hand-built classpath that globs
  `*/target/classes` silently omits every restored module. Both traps are now in `doc/AGENTS.md`'s cache section, and
  they are why this probe is not a viable instrument here: **use `classpathFrom` or a test.** One useful negative
  result came with it — the restored `hipster-entity-api` jar **does** contain `FieldAnnotation.class`, so the 6.1
  artifact is built correctly and Maven-based runs have no problem with it.
- **The instrument that worked, and what it settled (2026-10-08, later).** A **test** drives the CLI's own entry point
  — `EntityMetadataGenerator.main` with `--java-out`, `--packages` and `--validate`, the flags `scripts/gen.js` uses —
  and it **passes**: the CLI path emits the override exactly like the `generate(...)` API. So **"the CLI write path
  drops it" is eliminated too**, and with it every code-path hypothesis this step could name. Three further
  measurements narrowed what is left: (1) the example's stale `<Marker>.metadata.json` is **neither read nor written**
  by that invocation — moving the whole directory aside and re-running changed nothing and produced no new file, so
  staleness is not the mechanism either; (2) `bun scripts/gen.js` exits 1 because the **entity-html renderer** returns
  non-zero *after printing a successful link check* — its own step, not the generator's, which is why the generator's
  log always looks healthy and the command still "fails"; (3) the example also needs `jakarta.validation-api`
  (`provided`) in its POM for the annotation to **compile** — that was the very first failure, and the two edits must
  be applied together.
- **What remains unexplored about the example, in order of suspicion**: it is `@View(gen = GenLevel.BUILDER_ALL)` (every
  passing fixture uses the default level), it **extends another view** (`Person`) rather than a plain marker, and it
  carries a nested `record Record` and a nested `interface Write`. Those three are the difference the next attempt
  should reproduce **in a test** — the instrument that has settled every question here — rather than in the example,
  where a red `ExampleRegenerationTest` is the only feedback.
- **Those three candidates were turned into tests, and all three passed — so the shape is eliminated as well.**
  `FieldAnnotationExampleShapeTest` generates five variants and asserts the override each time: the control (default
  level, plain marker, no nested types), `@View(gen = GenLevel.BUILDER_ALL)`, extends another view, a nested `record`
  plus `interface`, and the example's whole shape combined. **All five pass.** The one adjacent case the variants did
  not cover — a parent declaring the same accessor, so the union might take the unannotated declaration — was checked
  directly: `metadata()` is declared **only** in `PersonSummary`, so there is no redeclaration either.
- **What that leaves is a contradiction, and it is what the next attempt must explain.** Every code path emits the
  override in a test — the source path, the emitter, a second pass over an existing enum, the CLI's own `main`, and the
  agent's cache (read, and correct) — and every shape difference reproduces it, yet the example's own CLI run **wrote no
  file at all**: after it, `git status` showed only the two hand edits, while the run printed *"Writing generated java
  to: …"*. `ExampleRegenerationTest` agrees with that reading: it **failed** when the example source carried the
  annotation and **passes** when it does not, which is exactly what "the committed enum was never regenerated" means. So
  the question is no longer what the emitter does; it is **why that one invocation writes nothing while the same entry
  point writes the override in a test**. The instrument for it is a **manual CLI run with the reactor's `target/*.jar`
  files on the classpath as well as every `target/classes`** (the trap recorded above and now in `doc/AGENTS.md`),
  reading the generator's **own stdout** — the one view of that invocation nobody has seen yet, because every run so far
  has been read through `gen.js`'s summary or a log the renderer's non-zero exit truncated.

## 11. Phase 7 — cross-cutting leftovers

### 7.1 — jwa-sidecar: read the client's indentation
**Who:** agent · **Size:** S

The sidecar advertises incremental sync but never reads the client's formatting settings: a grep for
`tabSize` / `insertSpaces` / `formatting` across `webview/jwa-sidecar` finds nothing, so the generated
members use the indent the engine was constructed with
([`BuilderTransformationEngine(String indent)`](../jcodebuddy/jcodebuddy-builder/src/main/java/hr/hrg/jcodebuddy/builder/BuilderTransformationEngine.java),
default 4 spaces).

**Do:** read `tabSize`/`insertSpaces` from the client (LSP `FormattingOptions` on the request, or
`workspace/didChangeConfiguration`) and pass the resulting indent step to the engine for every code
action. Same family as [`todo.java_watch2.md`](../todo.java_watch2.md)'s "custom indentation in
toolsets".

**Gate:** a test that a two-space client configuration produces two-space output; `MODULE` for
`jwa-sidecar` green.

**Done 2026-10-08 — the sidecar reads the client's indentation, and the old behaviour is the default rather than gone.**

- **The value is a named type, not two fields in two places**: `ClientFormatting` (`tabSize`, `insertSpaces`) with
  `indent()` — the engine's one-level step — so the concept is navigable and there is exactly one place that knows what
  a client's settings mean. `ClientFormatting.defaults()` is **four spaces, the value the engine was hard-coded with**,
  so a client that never reports settings generates exactly what it generated before this step.
- **`didChangeConfiguration` was an empty stub and is now the route the settings travel.** The step offered two
  options — `FormattingOptions` on the request, or `workspace/didChangeConfiguration` — and the first is not available:
  a code action is a command a user picks, so there is no `FormattingOptions` on it to read. The setting is therefore
  remembered on the server (`volatile`, because the LSP reader thread writes it while a code action runs on another)
  and read **at the moment of generation**, so a client that sends settings after connecting is honoured.
- **What is accepted is stated rather than guessed**: the flat object (`{"tabSize":2,"insertSpaces":true}`) and the
  section-wrapped one (`{"jwa":{…}}`) are both read, a missing field keeps the default **for that field**, and an
  unrecognised shape — or `getSettings()` returning something that is not a JSON object, which is what LSP4J's
  `Object`-typed accessor can hand over — yields the defaults rather than an exception. A settings shape we do not
  know must not take the language server down. `insertSpaces:false` produces a tab, because that is what the client
  asked for.
- **The gate test asserts a RELATIONSHIP between two runs, not a golden string.** Two configurations are run over the
  same record and every emitted line must carry the same text with **half** the indentation, plus the two concrete
  anchors (four spaces for the default, two for the two-space client). A golden string would pass again the moment
  somebody hard-coded the other indent; this cannot, which is the property the step exists for. A third test pins the
  tab case: a tab-configured client must get a tab, not spaces.
- **Measured**: `SidecarCodeActionTest` **7 tests** (from 5, so both new ones ran), the module set green —
  `bun scripts/mvn-jdk25.js -pl webview/jwa-sidecar -am verify` **BUILD SUCCESS** (26 tests in the sidecar, and the
  closure's 3 + 199 + 68 + 20 green).
- **Two build lessons, paid for here and worth the next reader's minute.** (1) `-pl webview/jwa-sidecar` **without
  `-am`** cannot resolve its sibling `jcodebuddy-builder-api`: the module needs the closure. (2) A `-pl … -am **test**`
  run **poisoned the cache for that closure** — `test` saves entries with no JAR, the cache key does not include the
  goal list, so the next run resolved a sibling that was class-less and failed with
  *"Could not resolve dependencies"*. This is the trap [`doc/AGENTS.md`](../doc/AGENTS.md) already records
  (*"`package` rather than `test` since the build cache landed"*); the fix is `verify`/`package` plus deleting
  `~/.m2/build-cache/v1.1` when entries are already wrong, which costs only time.
### 7.2 — java-watch-agent: the remote-jump front-end
**Who:** agent · **Size:** S

The sidecar's `/jump` endpoint, its token/origin gate and its loopback bind all exist; the agent's web UI
never calls it (`jcodebuddy/jcodebuddy-agent/src/main/resources/web/` has no `jump` and no `7979`).

**Do:** make the dashboard send the jump request with the token the sidecar requires, and show the
outcome (the sidecar reports a navigation *outcome*, not an assumed success, since 2026-09-25).

**Gate:** a test or a scripted end-to-end run against a live sidecar; the dashboard's own test stays
green.

**Done 2026-10-08 — the dashboard jumps, and the page never holds the sidecar's token.**

- **The jump goes through the agent's own server**, not from the page to the sidecar: `CommandServer` gained a
  `/jump` route that forwards to the sidecar's `/jump` and **relays its answer verbatim** (a new `sendJson` writes a
  body that is already JSON, because re-encoding it through a map would be a second place for the two to disagree).
  That is what makes the token story right: the page posts to the origin that served it, and the **server** presents
  `X-WebView-Token`, so the sidecar's origin gate never has to allow this page and the token never reaches a browser.
- **The port and token are read per request** (`jwa.sidecar.jumpPort`, default 7979 — the sidecar's own default — and
  `jwa.sidecar.token`), because they are facts about one machine's running host rather than configuration of this
  server: a sidecar that restarts on another port does not need the agent restarted.
- **An outcome, never an assumed success.** Whatever the sidecar answered is what the caller gets; a sidecar that is
  not listening is **502 `unreachable`** with the port and the reason rather than a jump that "worked". The page shows
  it in the words the sidecar used — `detail`, then `reason`, then `error`, then the raw body — and the
  `.jump-failed` styling exists so "the sidecar refused or was not there" cannot look like "the editor moved".
- **The dashboard itself**: `API.jump`, a `Jump to editor` button per pending action beside `Review Diff`, and a
  result span. The page is the minimal vanilla one DEC-027 keeps framework-free, and this keeps it that way — no
  framework, no bundler, one `fetch` and one function.
- **The gate is a test, and it asserts the two halves that can rot quietly** (`RemoteJumpTest`, **2 tests**): a stub
  sidecar sees `X-WebView-Token` with the token the sidecar requires, the location the page asked for is what is
  forwarded, and the outcome comes back **relayed** (`"reason":"exact"` still in the body); and a sidecar that is not
  listening is reported as a 502. A stub rather than the real sidecar, deliberately: starting the real one would add
  a JDK-version dependency, a port-claim race and an LSP client to prove the same three things, and the sidecar's own
  suite already covers its half. **The dashboard's JS has no test** — the module has no JS test harness at all — so
  that change rests on the HTTP test above plus inspection, and it is said rather than implied.
- **Measured**: `bun scripts/mvn-jdk25.js -pl jcodebuddy/jcodebuddy-agent -am verify` — **BUILD SUCCESS**, and the
  new tests run: `RemoteJumpTest 2 tests, 0 failures` beside the module's existing 15.
- **Two traps this step cost time to, both worth the next reader's minute.** (1) **This module is JUnit 5.** The test
  was first written with JUnit 4 imports and surefire's JUnit Platform provider **ignored it entirely**: the build was
  green, the module's other 15 tests ran, and the new test silently ran nowhere. Nothing in the exit code said so —
  the **surefire reports** did, and that is why the count was checked per class rather than read from `BUILD SUCCESS`.
  (2) **A cache restore skips surefire**, so a re-run of the same command printed `Skipping plugin execution
  (cached): surefire:test` and executed nothing at all; a targeted re-run needs the file changed (`skipSave` withholds
  the save, not the read) and its numbers read from `target/surefire-reports/`.
### 7.3 — `View1Builder.merge(View2 other)`, and its proxy version
**Who:** agent · **Size:** M

[`todo.hipster-entity.md`](../todo.hipster-entity.md)'s open item: no `merge(` method exists in
`hipster-entity-core` or `hipster-entity-example`. `ViewMapperGenerator` is a different shape
(source→target conversion), and the build-time rule (a builder never returns a partially built instance)
has to be respected.

**Do:** generate the merge for fields with identical name **and** type, with the proxy variant, and a
diagnostic in DEC-022's format for a field that matches by name but not by type.

**Gate:** `GATE` green, with a test over a view pair that shares some fields and not others.

**Part done 2026-10-08 — the merge generator's core is in and tested; the wiring into the builders is the remainder.**

- **What landed**: `ViewMergeGenerator` — a `Partner` (a view to merge from), a `Plan` (which fields are copied and
  which matched by name only), `plan(...)` and `method(...)` — plus its two DEC-022 diagnostics registered in
  `DivergenceReporter.KINDS` with their producer named in `ExampleDivergenceReportTest`'s producer map, which is the
  check that exists so a recognized kind is never a promise. **Measured**: `ViewMergeGeneratorTest` **6 tests**,
  `DivergenceReporterTest` **7** and `ExampleDivergenceReportTest` **4** (the last of which audits the kind list), all
  green on a run that actually executed them.
- **The rule that made this its own generator rather than a reuse of `ViewMapperGenerator`**: a mapper converts a
  source view into a *different* type, so every target field needs a value and an absent one is a data-loss
  diagnostic. A merge copies the fields two views **happen to share** into a builder already being built, so a field
  the partner does not have is **not** merged and **not reported** — "merge some fields and not others" is the
  feature. `aFieldThePartnerDoesNotHaveIsNotReported` pins that, because the easy mistake is to reuse the mapper and
  bury the one case that matters under a diagnostic per unshared field.
- **The one diagnostic that matters** is a field matching **by name but not by type**: copying it would need a
  conversion, and a silent narrowing (or a widening that loses `null`) is the class of bug this repository refuses to
  generate. It is reported with both declared types and what to do, and it copies **nothing** — `nickname` in the
  test is `String` on one side and `Integer` on the other and is deliberately absent from the emitted method.
- **The emitted method respects the build-time rule by construction**: it sets fields on the builder and returns the
  builder, so a partially built instance is still unreachable; a `null` argument changes nothing and returns the
  builder, which is what makes `builder().merge(maybe).build()` composable without a null check per call site. Both
  are asserted, along with the absence of any `build()` in the emitted text.
- **A retired tombstone is excluded on both sides** (R1.4): a retired host slot has no setter to write through and a
  retired partner slot has no accessor to read with, so merging either would emit code that cannot compile.
- **Still open, precisely named**: threading the partners into `ViewBuilderGenerator` and
  `ViewTrackingBuilderGenerator` (the proxy variant) and driving them from a `--merge <host>:<partner>` request in
  `EntityMetadataGenerator`, mirroring `--mapper` — the request shape is the one the mapper already proves, and the
  builder generators already reconcile an existing file member by member, so the merge method is an ordinary member
  that a hand-edited builder keeps. Then the **full `GATE`** and a view pair in the example project.
- **A trap worth the next reader's minute, and it cost this round real time**: **`-Dtest=A+B` is not two classes.**
  The `+` separator selects *methods of the first class*, so `-Dtest=DivergenceReporterTest+ExampleDivergenceReportTest`
  selected a method named after the second class: surefire ran **nothing**, and with
  `-Dsurefire.failIfNoSpecifiedTests=false` the run still reported `BUILD SUCCESS`. The fix is a comma — and the
  lesson is the one this repository keeps re-learning: **counts come from `target/surefire-reports/`, never from the
  exit code**, and `-pl <module>` needs `-am` or the sibling artifacts cannot be resolved.
**Done 2026-10-08 — both halves are in: the merge reaches the untracked builder and the proxy variant, driven by `--merge`.**

- **The request shape mirrors `--mapper`** (which the step's own note points at): `--merge <hostView>:<partnerView>`, repeatable, applied **after every view is resolved** — a merge names two views and the partner may be resolved after the host, which is exactly the shape `generateRequestedMappers` already had. A host may name several partners and gets one `merge(ViewN other)` per partner, in request order; the same partner twice is emitted once, because two identical methods would not compile.
- **Both builders, one decision.** `ViewBuilderGenerator` and `ViewTrackingBuilderGenerator` gained the partners parameter and both compute their plans through `ViewMergeGenerator`, so the untracked builder and the tracking proxy **merge the same fields and report the same diagnostics**. What differs between them is what a setter *does*; that is not something a merge should decide. The proxy's merge calls the generated setters rather than the fields, so a merged field is tracked exactly like one set by hand — writing the fields directly would leave `changedValues()` silent about a change the merge made.
- **Re-emit, not patch.** The pass keeps each view's builder inputs as it emits it and re-runs the emitters with the partners attached once all views are known. That keeps the result identical to a pass that knew the partners from the start, and it inherits the property that matters for hand-edited files: the emitters reconcile an existing file member by member, so a merge method someone edited survives and an edited generated one is reported.
- **The wiring test is the gate's test**, over a pair that shares some fields and not others: `PersonSummary` and `PersonDto` agree on `name` and `age` by type, the summary has `score` alone, the dto has `extra` alone, and `nickname` matches by name with a different type (`String` vs `Integer`). Asserted: the merge reaches **both** builders; only `name` and `age` are copied; `score` and `extra` are neither copied nor reported; `nickname` is reported as `merge_field_type_mismatch` **and** not copied; the emitted method is null-safe, returns the builder and contains no `build()`; and **without the flag no builder gains a merge at all** — the opt-in property, which is what makes this safe to land.
- **Measured**: `ViewMergeWiringTest` **4 tests**, `ViewMergeGeneratorTest` **6**, `DivergenceReporterTest` **7**, `ExampleDivergenceReportTest` **4**, and the **recorded `GATE`** (`bun scripts/mvn-jdk25.js`, `clean package`, cache on) **BUILD SUCCESS**.
- **One deliberate omission, named rather than implied**: the merge is **not** wired into the example project's own views. The gate asks for "a test over a view pair that shares some fields and not others", which the test above is; adding a pair to the example would rewrite committed example output for a feature whose shape the request already decides, and the example's own regeneration tests are the place to do that when a real pair needs it.
- **Two harness lessons from this round, both of which cost a failing test that looked like a feature bug.** (1) A view fixture **must extend a root view** (`extends EntityBase<Long>, Identifiable<Long>`) or the pass emits nothing at all — the symptom is a missing file, not a diagnostic, so it reads as "the merge did not run". (2) `setGenerationPackages(...)` **filters the pass**, so a fixture written under a package directory plus a package filter yields no output; `ViewMapperGeneratorTest` writes its fixtures flat and sets no filter, and mirroring it is what made the test measure the merge rather than the harness. A third, smaller one: the tracking builder's class name is `<View>BuilderTracking` (for example `PersonSummaryBuilderTracking`), not `...TrackingBuilder`.
### 7.4 — The documentation front door
**Who:** agent · **Size:** S

[`doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md) items 12–13: the user guide
exists under [`doc-hipster-entity/user/`](../doc-hipster-entity/user/README.md) but the root
[`README.md`](../README.md) links none of it, and the architecture docs do not link back into it. A new
reader cannot reach `getting-started.md` or `why-hipster-entity.md` from the front page.

**Do:** add the front door (a short section that routes library users to the guide and library devs to
the architecture half), add the reverse cross-references, and update the plan's banner to say the two
items are closed.

**Gate:** `LINKS` green.

**Done 2026-10-08 — the front door exists, the architecture half points back, and the plan says both items are closed.**

- **Item 12, the front door.** The root [`README.md`](../README.md) now opens with a **Where to start** section that
  routes the two audiences apart: a **user** to [`user/why-hipster-entity.md`](../doc-hipster-entity/user/why-hipster-entity.md)
  and [`user/getting-started.md`](../doc-hipster-entity/user/getting-started.md) (or
  [`getting-started-new-project.md`](../doc-hipster-entity/user/getting-started-new-project.md)), with the guide
  indexed in [`user/README.md`](../doc-hipster-entity/user/README.md), recipes under
  [`user/patterns/`](../doc-hipster-entity/user/patterns/README.md) and answers in
  [`user/faq.md`](../doc-hipster-entity/user/faq.md); a **contributor** to
  [`architecture/README.md`](../doc-hipster-entity/architecture/README.md),
  [`architecture/TOC.md`](../doc-hipster-entity/architecture/TOC.md),
  [`architecture/DECISIONS.md`](../doc-hipster-entity/architecture/DECISIONS.md) and the two `AGENTS.md` files. The
  section names the separation plan it discharges, so the next reader can see why it is there.
- **Item 13, the reverse cross-references — measured first, then placed deliberately.** Before the change **2 of the
  11** non-decision architecture documents linked back into the user half
  ([`architecture/README.md`](../doc-hipster-entity/architecture/README.md) and
  [`materialization-levels.md`](../doc-hipster-entity/architecture/materialization-levels.md)). The pointer was added
  to the **navigation surface** — [`architecture/TOC.md`](../doc-hipster-entity/architecture/TOC.md),
  [`DECISIONS.md`](../doc-hipster-entity/architecture/DECISIONS.md),
  [`ADR-GUIDE.md`](../doc-hipster-entity/architecture/ADR-GUIDE.md) and
  [`naming-conventions.md`](../doc-hipster-entity/architecture/naming-conventions.md) — and **not** to all nine,
  because the rest are measurement records and implementation guides a newcomer does not land on first, and a pointer
  repeated everywhere is read nowhere. The wording matches the line the architecture README already carried, so one
  sentence now appears in five places rather than five sentences in five places.
- **The plan's banner is updated, keeping the measurement**: [`doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md)
  now reads *delivered, all items closed*, says items **1–13** are done, and records what item 13 found (2 of 11) and
  where the pointer went instead of only asserting that it was done.
- **One placement defect found and fixed while doing it**, worth naming because it is the kind a link checker cannot
  see: the note first landed **inside `TOC.md`'s bullet list**, between two entries, which splits the list in the
  rendered page. It now sits under the title, like the other index files, and the file's diff is exactly the four
  added lines.
- **Gate `LINKS` green**: `bun scripts/check-repo-links.mjs` — 280 markdown files, 1788 relative links,
  **ALL RELATIVE LINKS RESOLVE**.
### 7.5 — java-watch-agent: the OpenRewrite-based tool prototype
**Who:** agent · **Size:** M

Phase 4's second box. Nothing under `jcodebuddy/jcodebuddy-agent/` references OpenRewrite today. It must be built
the repository's way (DEC-030): read through `SourceReader`, query through `TreeQueries`, splice text —
not parse with a second parser and not reprint a tree.

**Gate:** one real tool (the plan's own example — a rename or a migration recipe) runs from the agent's
menu against a sample and is asserted by a test.

**Done 2026-10-08 — a real OpenRewrite-based tool runs from the agent's menu: `rename`.**

- **What landed**: `RenameMemberTool` (`getName()` returns `rename`) in the agent's `tools` package, implemented the
  repository's way (DEC-030) — `SourceReader.read(...)` for the tree, `TreeQueries.findAll(unit, J.Identifier.class)`
  for the query, and a **text splice** for the edit. It is registered in `WatchAgent`'s `ToolRegistry` **and** added to
  the default toolset's name list, so it appears in the menu; the test drives it with the same
  `SimpleToolContext` the menu passes.
- **Configured, never guessed**: `AgentConfig` gained `renameFrom`/`renameTo` (`.watch_agent.conf`), and with nothing
  configured the tool is **applicable to nothing**. A rename is a project decision, so a tool that invented one would
  rewrite identifiers nobody asked it to touch — being in the menu while doing nothing is the honest state.
- **The safety property is the point, and it is what makes this legitimate for an agent to run.** The tree is the
  authority on how many times the name is really used; the text is spliced with **Java identifier boundaries**; and the
  two counts must be **equal**. When they differ, the tool **refuses and names the difference** — a string literal, a
  comment, or a longer identifier — instead of producing a file that compiles and means something else. A rename onto
  a name the file already uses is refused for the same reason (this repository does not emit code it cannot vouch for).
- **An unparseable file is not applicable rather than an error**, because the watcher calls this on every change and a
  half-written file is normal while editing: filling the audit log with failures nobody can act on is worse than doing
  nothing quietly.
- **Measured**: `RenameMemberToolTest` **4 tests** (the rename rewrites the declaration and every use while leaving
  `nameValue` alone; the string-literal case, the collision case, and the unconfigured/unparseable/absent cases all
  refuse), and the module gate `bun scripts/mvn-jdk25.js -pl jcodebuddy/jcodebuddy-agent -am verify` — **21 tests,
  BUILD SUCCESS** (`ToolSeamTest` 15, `RemoteJumpTest` 2, the new 4).
- **A finding worth recording, and deliberately not fixed here**: the default toolset names **`record_builder`**, and
  **no registered tool answers to that name** (`hello`, `builder`, `getters`, `setters`, `accessors`, `constructor`,
  `rename`). A toolset entry that can never run is the same class of silent no-op this step's tool exists to avoid, but
  it is a question about tool identity and configuration rather than about the engine, so it is named rather than
  answered in this step.
### 7.6 — Decide the three remaining `todo.java_watch2.md` items
**Who:** agent + maintainer · **Size:** S

Each is a decision, and silence is the only wrong outcome:

- **A configurable delay for delete events** — a debounce exists and is configurable
  ([`BatchedFileWatcher`](../watch/java-watch-core/src/main/java/hr/hrg/watch2/core/BatchedFileWatcher.java),
  `ManagedFileWatcher`, `java-watch-scp`'s `watch_delay_ms`); a *delete-specific* delay does not. Decide
  whether one is wanted, and record why.
- **A memory-based LocalDB** — `local_db` today chooses *where the `.scpdb` file lives*, not whether the
  check database stays in memory. Decide, and record the measurement that justifies it.
- **The Zig port of the hashing logic** — there is no `*.zig` anywhere. Decide whether this is a real
  goal or a learning exercise that has served its purpose, and either schedule it or delete it.

**Gate:** each item in [`todo.java_watch2.md`](../todo.java_watch2.md) is either scheduled or struck
through with a reason; nothing stays silently unchecked.

**Done 2026-10-08 — all three decided with the maintainer, and the list has no unchecked item left.**

- **Item 1, a delete-specific debounce — struck.** Deletes already ride the same trailing-edge window as every other
  change: [`BatchedFileWatcher`](../watch/java-watch-core/src/main/java/hr/hrg/watch2/core/BatchedFileWatcher.java)
  documents that `DELETE` events land in `ChangeSet.deleted()` after the same `debounceMs`, measured from the *last*
  change. A delete-specific delay would be a second knob over one behaviour with no measurement behind it, so the item
  is struck rather than scheduled, and reopens only with a case the shared window demonstrably mis-batches.
- **Item 2, a memory-based LocalDB — struck, for the absence of a measurement rather than as impossible.** The
  premise does not match what `local_db` does: it chooses *where the `.scpdb` file lives* (the local machine or a
  remote absolute path), not whether the store is in memory. A true in-memory mode would drop the cross-run state that
  is the database's purpose — the next run compares against it — and nothing in the tree measures the file I/O as a
  cost worth that. Reopening it is a matter of bringing numbers; if it is ever reopened it changes the SCP store's
  contract and **would then want its own decision record**.
- **Item 3, the Zig port — struck, on the maintainer's own reason.** The ecosystem uses **wyhash**, chosen for
  compatibility with the wider wyhash family — Zig's own `std.hash.Wyhash` among them — and that compatibility is the
  goal. It is already delivered by *using the algorithm* rather than by porting code:
  `hr.hrg.wyhash:wyhash:1.0.0` is a dependency of `java-watch-core`, and `hipster-entity-tooling` vendors `Wyhash64`
  as `ContentHash` with the algorithm pinned by golden vectors (DEC-029 § 4). A second implementation in another
  language would be maintained forever with no consumer, so the item was a means whose end already exists.
- **Two stale statuses found and corrected while doing it, which is worth recording because they were exactly the
  silence this step's gate forbids**: the list's two "Immediate Priority" items still read *open* although this
  session closed them — the remote-jump front-end in 7.2 and the client indentation in 7.1. Both are now ticked with
  what closed them, so the file agrees with the tree instead of lagging it.
- **No ADR, and the reason is stated rather than assumed**: the ADR guide's triggers are the entity model, the
  generated code shape, a runtime contract and a module boundary. This step changes **no code** and touches none of
  those — it resolves three todo items and corrects two tick-boxes — so a decision record would be a record of a
  decision nobody has to implement. The one item that would qualify (a memory-based store) is struck precisely
  because there is nothing measured to decide on.
- **Gate**: `todo.java_watch2.md` now has **zero** unchecked items — each is done, struck through with a reason, or
  (for the two that were silently stale) ticked with what closed it — and `LINKS` is green. **No other step in the
  plan assumed any of the three**: the only other mentions of them are this step's own text.
### 7.7 — The manual-mode CLI for DEC-W008 (`jcodebuddy metadata parse <file>`)
**Who:** agent · **Size:** S

DEC-W008 requires a CLI entry point that calls `parse` directly and prints the resulting metadata, working
in a fresh checkout with no daemon, no cache folder and no prior `scan`. Step 1.2 built the method and the
RPC/MCP routes; the CLI is the piece the decision names and nothing implements — there is no `metadata`
command anywhere in the tree, which is why DEC-W008's amendment lists it as open.

**Do:** add the command where DEC-W008 says it belongs (`project-automation`, which already hosts
`MetadataAnalysisRunner` and the provider). It prints the entry as JSON on stdout and exits non-zero when
the file cannot be read. If it needs a launcher, that launcher is Bun JavaScript with JDK 25 pinned
(AGENTS.md § 2: the script is JavaScript, the build step is Maven) — never a `.cmd`/`.sh`.

**Gate:** the command runs against a file in a clean checkout with no `.jcodebuddy/` present, its output
round-trips as the same JSON the RPC returns, and `GATE` stays green.

**Done when:** DEC-W008's manual-mode paragraph is true and its status note drops the CLI from its
"not implemented" list.

**Done 2026-10-08 — `jcodebuddy metadata parse <file>` exists, and DEC-W008's "not implemented" item is closed.**

- **What landed, in the module DEC-W008 names**: `hr.hrg.jcodebuddy.automation.cli.MetadataCli` in
  `project-automation`, which calls `IndexMetadataProvider.parseSource` — the decision's own no-cache reference
  path — and prints the `CacheEntry` as **one line of JSON**. The entry point a person types is
  `bun scripts/jcodebuddy.js metadata parse <file>`, a Bun launcher that resolves JDK 25 through
  `scripts/lib/toolchain.js`, compiles the module with its dependencies and runs the class from `target/classes`.
- **The launcher follows `scripts/gen.js`, including its recorded refusals**: one
  `-pl project-automation -am compile dependency:build-classpath` invocation, then `java -cp`. `mvn exec:java` is
  deliberately not used (a direct goal runs on every module in the reactor, and it resolves a `provided`
  dependency from `~/.m2` instead of the reactor), nothing is packaged, and nothing is installed — so the command
  works in a checkout **nobody has built**, which is what "fresh checkout" has to mean here. A first run costs a
  compile; a warm one costs one JVM start.
- **Exit codes are part of the contract, not a detail**: `0` with the entry on stdout; `2` for a usage error or a
  file that cannot be read, with the reason on **stderr** and **nothing on stdout** — so `… parse f | jq` cannot
  read an error message as metadata. Both are asserted.
- **The fresh-checkout property is asserted rather than promised**: the test runs the command against a source
  file in an otherwise empty temporary directory and then asserts that **no `.jcodebuddy/` was created** and that
  nothing else was left behind. A manual-mode command that quietly started a scan would satisfy the output
  requirement while violating the one this step exists for, and a test that only checked the output would not
  notice.
- **The round-trip requirement is met as equality of the *answer*, not of the transport**: `MetadataCliTest`
  dispatches a real `parseFile` request through `RpcDispatcher` and asserts the CLI's JSON equals the RPC's
  `result` **field for field**. Four choices were confirmed with the maintainer rather than assumed — bare entry
  (not a JSON-RPC envelope), one line (not pretty), documented as `bun scripts/jcodebuddy.js …` (no `bin/` entry,
  no `.cmd`), and DEC-W008's stale item **marked closed while staying visible** rather than deleted.
- **DEC-W008 is updated in three places**: the status note's item 4 is struck through and closed with what
  implements it; § *Manual-mode CLI* now records the invocation and the four choices (script/build split, bare
  one-line output, exit codes, writes nothing); and the follow-up item *"Choose the CLI module and packaging"* is
  settled as `project-automation` + launcher-over-`target/classes`. The root [`README.md`](../README.md)'s table of
  Bun commands gained the line, with the reason a bare `jcodebuddy` needs the script on `PATH` by choice.
- **Measured**: `MetadataCliTest` **3 tests, 0 failures** (`bun scripts/mvn-jdk25.js -pl project-automation -am
  verify`); the launcher run by hand against `.tmp/cli-scratch/PersonSummary.java` printed
  `{"hash":"2566d7dcfb48bb12","fullClassName":"demo.hr.PersonSummary","relativePath":".tmp/cli-scratch/PersonSummary.java","metadata":{…"hashAlgo":"wyhash64"…}}` with exit `0`, created nothing beside the source, and a
  missing file exited `2` with `cannot read …` on stderr; the **recorded `GATE`** (`bun scripts/mvn-jdk25.js`,
  `clean package`) is green; `LINKS` green (280 files).
- **One cosmetic observation, recorded rather than hidden**: the launcher's *build* step lets Maven's JVM warnings
  (`sun.misc.Unsafe`, jansi native access) reach stderr. The CLI's own streams stay clean, and the warnings are
  Maven's rather than the command's, so nothing was added to suppress them — a caller checking exit codes or
  stdout is unaffected, and silencing another tool's diagnostics is the kind of thing that hides a real one later.

### 7.8 — Two kinds of generator: file-scoped and project-scoped
**Who:** agent · **Size:** M

Rule § 2.8 states the distinction; nothing in the tree expresses it. One SPI — `CodeGenerator<T>` with
`isApplicable(CodeContext)` and `generate(CodeContext)` — describes a **file-scoped** generator, and its
javadoc says so ("a generator is therefore safe to offer to every file — the cost of asking is the
predicate"). The kinds are not two shades of the same thing, and the correction that made this step clearer
is worth stating: **a generator that needs the project's type relations is not a file generator with a
bigger appetite — it is a different kind of program.** hipster-ioc is the example: it cannot work on single
files at all, because "who extends whom" is not in any file, and its input is the project's **metadata**
(steps 3.0a–3.0e), not source text it parses itself.

Three facts about the current tree:

- `IocContextGenerator` implements the file-scoped SPI and is **not** a file generator: it reads the
  sibling `<Supertype>.java` beside the context, and the pass writes a whole-tree artifact
  (`contexts.json`). A caller holding a list of `CodeGenerator`s cannot know that, and the generator's own
  `isApplicable` (a substring test on the file's text) is deliberately cheap precisely *because* the SPI
  promises the file is all that matters. Steps 3.0e removes that implementation rather than re-labelling
  it — a project-wide generator should not be wearing a per-file interface at all.
- `ActionToolAdapter` is a **wrapper** whose kind is its delegate's: it forwards `isApplicable`/`generate`
  to an `ActionTool`. All five wrapped tools read only `context.getFilePath()`, so they are file-scoped
  today — but a kind that cannot be delegated cannot describe the one implementer that already exists as a
  wrapper.
- `EntityMetadataGenerator` is project-scoped by construction and therefore safe: its entry points take a
  source root and a package list and write generated Java plus `.jcodebuddy/metadata/entity/…`, so there is
  no per-file door to walk through.

**Do:** put the distinction in the engine's SPI as a type rather than a comment (`hr.hrg.jcodebuddy.engine.codegen`,
which absorbed `jcodebuddy-codegen-api` at step 3.0i). A project-scoped
generator's contract is **metadata in, code out** — it is handed the project model (3.0d's resolver) and
what to generate for, and it never receives a single-file `CodeContext` as its input. Concretely: a second
interface for the project-scoped kind, the delegable case stated as a contract (the one wrapper that existed,
`ActionToolAdapter`, was deleted in step 3.0s, so this is a shape to define rather than a class to fix), and
the caller side refusing to offer a project-scoped
generator per file. Say which kind each generator is on its README, and amend DEC-036 § 11 with the
classification and with the rule this whole thread has been circling: **a type the metadata cannot resolve
is reported, never inferred as absent** — the step 3.2 evidence is the reason.

Also refine rule § 2.8 in this step, because using it exposed a conflation: **a generator's kind is what it
reads; a pass's kind is what it writes.** The IoC generator returns text (no side effects) while
`IocGeneration` writes the graph, and a rule that says "needs another file *or* writes a tree artifact"
would call the same generator two kinds depending on which half you looked at.

**Gate:** `MODULE` for `jcodebuddy-core,jcodebuddy-agent` green, with tests that a project-scoped
generator is not offered per file, that a wrapper's kind follows its delegate, and that an unresolvable
type produces a diagnostic instead of an empty answer.

**Done when:** a caller holding a generator list can tell the kinds apart without reading the generator's
source, and no project-wide generator implements the file-scoped interface.

**Done 2026-10-03.** The distinction is now a **type in the engine** rather than a paragraph: `engine.codegen`
holds `CodeGenerator`/`CodeContext` (file-scoped) and the new **`ProjectGenerator`/`ProjectContext`**
(project-scoped — metadata in, code out, handed the class index and the typed queries and never a single file).
Neither interface extends the other, so a caller holding file-scoped generators cannot be handed a project-scoped
one: the compiler enforces what the rule asked for, which is the point of putting it in the SPI at all.

`GeneratorKindsTest` (3 tests, `jcodebuddy-core`) pins the three things the Gate asked for: the kinds are unrelated so
no caller can mix them; **a wrapper's kind follows its delegate** and is exactly one kind (both wrappers are fixtures
in the test, since `ActionToolAdapter` was deleted in step 3.0s — a shape to hold to, not a class to fix); and **an
unresolvable type is reported rather than inferred absent** (`ClassIndex.answer` gives `TypeAnswer.NotIndexed` with a
cause saying what is missing — the `TypeAnswer` machinery already existed, so this was pinning it, not building it).

The two clarifications this step forced were already in the charter (§ 2.8) and are now in the decision as well:
**a generator's kind is what it reads, a pass's kind is what it writes** (the IoC generator returns text,
`IocGeneration` writes the graph), and the delegable case stated as a contract. DEC-036 § 11's amendment records the
classification, its "until 3.0e lands" sentence is discharged (3.0e landed: the generator no longer implements
`CodeGenerator` and parses nothing), and each generator's kind is now on its own README — `hipster-ioc-tooling`
(project-scoped), the engine's table in `jcodebuddy-core`, and `hipster-entity-tooling`'s `EntityMetadataGenerator`
(project-scoped by construction).

**Gate:** `bun scripts/mvn-jdk25.js -pl :jcodebuddy-core,:jcodebuddy-agent -am clean package` green, with the cache
on.

### 7.9 — Set up the `jsx6` checkout every UI must be built from

**Done 2026-10-03.** The checkout is at `.jsx6/` (cloned from `github.com/hrgdavor/jsx6`, HEAD
`a584e7a`), **gitignored** so a `git add -A` cannot swallow it, and placed beside `.tmp/` for the reason § 2 gives:
the recorded gate runs `clean`, so it must not live under `target/`. As the rule requires, the work follows the
checkout's own guidance rather than a remembered API — `.jsx6/AGENTS.md` (a router), `docs/stack/agent-rules.md`
(the rules that fail silently), `docs/stack/setup.md` (what a consumer configures) and `docs/stack/README.md`.

Three findings that cost time, kept because they will cost it again:

- **A consumer outside the jsx6 workspace cannot `file:`-link the packages.** They depend on each other with
  `workspace:*`, which only a workspace install satisfies, and installing published copies would pin a version —
  the opposite of what § 2 asks. Aliasing the checkout's `libs/*/index.js` in esbuild gives both: no
  `node_modules`, and always the source `git pull` last put there (15 libs aliased, no install of jsx6 itself).
- **An ES module loaded with `<script type="module" src=…>` is CORS-blocked on `file://`** (a file page has a
  null origin), so the page renders blank while the HTML looks perfect. The review page therefore **inlines** its
  bundle: one self-contained file, openable by double-click and inside the JCEF webview.
- **The screenshot technique works here, and it is a check rather than a picture.** `chrome --headless=new
  … --screenshot` plus a PNG decode in `node:zlib` gives content pixels and bounds, and the script **fails when the
  page is blank** — which is how the CORS failure above was caught instead of shipped.
**Who:** agent · **Size:** S–M

Rule § 2.9 and [`AGENTS.md` § 2](../AGENTS.md) settle *that* any UI built here uses
[`jsx6`](https://github.com/hrgdavor/jsx6); nothing in the tree settles *how an agent gets it*, and the
instruction is explicit that it is a **local checkout updated on demand**, not a version in a lockfile.
Nothing exists yet: there is no `jsx6` directory, no ignore entry for one, and no record anywhere that a
UI author is supposed to read jsx6's own `AGENTS.md` before writing JSX.

**Do:**

1. **The checkout.** `git clone https://github.com/hrgdavor/jsx6` into the temporary folder the rule
   names — default `<repo>/.jsx6/`, overridable with `JCODEBUDDY_JSX6_DIR` — and add the ignore entry so a
   clone is never committed. Update with `git -C <dir> pull --ff-only`. **Not `target/`**: the recorded
   gate runs `clean test`, so a checkout there would be deleted by every gate run — and the whole point is
   a local copy that survives until it is deliberately updated.
2. **Read, then record.** `<dir>/AGENTS.md` first — it is a router, so follow it to what *using* the stack
   needs (in the version read on 2026-10-01 that is `docs/stack/README.md`: setup, signals, the JSX/DOM
   contract and the rules that fail silently) — and write down in this step's record the facts a UI author
   here needs: the package names a consumer imports, whether a build/transform step is required, how a
   page or webview loads it, and how the checkout's own gate is run. Cite the jsx6 commit that was read,
   because a moving dependency means "what I read" is part of the evidence.
3. **Inventory the UI surfaces**, so the rule has a subject: the webview clients and kit
   (`webview/kit`, `webview/webview-jetbrains`, `webview/webview-vscode`, `webview/webview-eclipse` if
   present, `webview/jwa-sidecar`) versus the **generated report pages** (the entity index and the merge
   review render).
4. **The scope question is already decided — record it, do not re-open it.** DEC-027's 2026-10-01
   amendment splits the rule: a page with **no UI or minimal UI** stays vanilla, an **interactive or
   advanced page** is `jsx6`, **relations and diagrams** are `jsx6`/`nodditor` (the entity reference page
   and the merge summary stay vanilla; the per-conflict review render in step 4.2 and the dependency graph
   in 3.8 are `jsx6` pages, the latter with nodditor). Read that amendment and the pages it classifies
   before touching a renderer; a page that changes class is a change to that amendment, not a quiet drift.
5. **Point the UI work at it.** Phase 5's banner already sends a webview step here; make sure
   `webview/PLAN-webview-suite.md`, `webview/kit/doc/page-authoring.md` (which carries a *no bundler* line
   of its own) and any UI-facing README name the checkout + jsx6's `AGENTS.md` rather than repeating
   either, and reconcile that page-authoring contract with what the checkout says a `jsx6` page needs — the
   view-time no-network property stays, the build shape is whatever the checkout documents.
6. **Do not put a `jsx6` page's build under `scripts/`, and do not look for a guard that enforces the
   library choice — there is none, by decision (2026-10-02).** `scripts/` is the vanilla renderers' home and
   stays dependency-free; a `jsx6` page's build (and wherever a dependency is declared) gets its own home —
   a package beside the page's renderer, or the module that owns the page — rather than an entry added to
   `scripts/`. Say which home in the record, because the next agent will otherwise try the obvious thing.

   **The choice itself is an instruction, not a check.** *Simple page → vanilla JavaScript; more complex
   page → `jsx6`, never React / Svelte / Solid / Vue / Preact / Lit or another framework of that kind* —
   stated in `AGENTS.md` § 1–§ 2 and DEC-027's amendment, and deliberately not enforced by the build: how
   complex a page is, is a judgement, and a strict guard over a judgement is the kind of check this
   repository does not want here (contrast step 7.8, where a generator's *kind* is a mechanical fact and
   therefore worth a type and a test). `HtmlRenderBoundaryTest` is untouched by this rule and keeps only
   what it always had about the page it was written for — the entity reference is framework-free and its
   renderer's subtree is dependency-free, which is that *minimal* page's own contract. DEC-036 § 10's "a
   JavaScript renderer under `scripts/`" is amended to match, since the graph page is a `jsx6` page.

   **Read the guard's actual scope before deciding it needs an exception — it does not.** Its framework
   check runs over an explicit five-file list (`RENDERER_CODE`, the `scripts/entity-html/*.js` sources), so
   a `jsx6` page elsewhere is simply not its subject; no exception mechanism is wanted, and adding one
   would weaken the one check that keeps the minimal page honest. **Recorded deviation:** an earlier cut of
   this change hardened that test for the new rule — it walked the `scripts/` subtree for dependencies, and
   asserted that `AGENTS.md` and DEC-027 carry both halves of the split. Both were **reverted (2026-10-02)**
   when the decision became *instruction, not guard*: the assertions were a strict check over a judgement
   call, which is what the maintainer did not want. The test file is back to exactly what it was before, so
   nothing in the gate fails over which library a page uses.

**Gate:** the checkout reproduces from the documented two commands on a clean machine; `<dir>/AGENTS.md`
was read and the consumption facts above are recorded with the commit read; the surfaces are listed **as a
record** with their class (vanilla / `jsx6` / `jsx6`+nodditor), and the page-authoring contract is
reconciled. `LINKS` green.

**Done when:** a future UI task can start from the written instruction — checkout, read jsx6's
`AGENTS.md`, know which surfaces are in scope and which class each page is — without re-deriving any of it,
and no UI is written against a remembered version of the library.

### 7.10 — What `jsx6` and `nodditor` can and cannot do for our pages
**Who:** agent · **Size:** M

Rule § 2.9 requires this the moment a page needs something the libraries do not have: **report it**, and
say which of the two answers it gets — a minor improvement that belongs in `jsx6`/`nodditor`, or a critical
gap that forces a decision about an additional library for that specific output. Nothing about the libraries
can be assumed from their names: this step is the assessment, and it needs 7.9's checkout and the page list
that 7.9 records (DEC-027's amendment: what each page must show).

**Do:** for each page class the repository actually has or has scheduled — the minimal vanilla page, the
interactive review page (4.2/4.3), the project-structure/navigation page (3.8), and the diagram/relations
layer (3.8, nodditor) — take the capability list from the page's own requirements and check it against what
the checkout documents and implements (read the code, not only the READMEs, and cite the commit read). Then
write the outcome down: **sufficient**, or a gap with a recommendation. A gap is *minor* when the library
can plausibly absorb it and the finding belongs upstream; it is *critical* when the specific output cannot
be produced with them at all, which is a decision about an additional library for that output — taken with
the evidence, and recorded as its own decision rather than assumed in a page.

**Gate:** a written assessment covering each page class, naming the jsx6/nodditor commit read, with every
gap classified minor/critical and the critical ones routed to a decision (not to a workaround).
`LINKS` green.

**Not in this step, by decision (2026-10-02): the page *class* is an instruction and is never enforced by a
guard.** How complex a page is, is a judgement, and a strict check over a judgement is exactly what the
maintainer does not want here — so the statement of which page is what lives in DEC-027's amendment,
`AGENTS.md` § 1–§ 2 and this plan, and **no test fails because an agent chose vanilla where `jsx6` would
have been better or the other way round**. The contrast with step 7.8 is deliberate: a generator's *kind*
is a mechanical fact (what it reads), so it earns a type and a test; a page's complexity is not. What
`HtmlRenderBoundaryTest` keeps is what it always had and only about the page it was written for — the
entity reference is framework-free, its renderer's subtree dependency-free, and its links verified. A page
changing class is a change to DEC-027's amendment, never a quiet drift.

**Done when:** the answer to "can we build this page with jsx6/nodditor?" is written down with evidence,
and a missing capability has an owner (the library, or a decision) instead of a silent workaround in a page.
**Done 2026-10-08 — the assessment is written, and it names the commit it read.**

- **The deliverable is [`doc/jsx6-capability-assessment.md`](../doc/jsx6-capability-assessment.md)**, covering all
  four page classes the step names (the minimal vanilla page, the interactive review page, the project-structure
  navigation page, and the diagram/relations layer), each with its requirements taken from the page's own code or
  the step that schedules it, the capability check, and a verdict.
- **The commit read is `a584e7a`** (2026-10-02, *"document usage"*) — the checkout step 7.9 set up — and the
  assessment cites **source**, not READMEs, wherever a capability is claimed: `libs/virtual-scroll/src/virtual-scroll.js`
  for the fixed-height rule, `apps/nodditor/src/canvasLineLayer.js` and `libs/line-render/index.js` for the relation
  layer, `libs/editor-monaco/editor.worker.js` for the editor, `libs/popover/index.js` for popovers. Where a finding
  rests on a README alone the document says so, which is why the *navigation* page's verdict is marked prospective:
  the page is scheduled, not built.
- **One substantive limitation, classified minor, and it is an implementation fact rather than a documentation
  one**: `virtual-scroll` requires **fixed-height rows** (`itemHeight` places row *i* and computes the visible
  window by dividing by it), while the review page's cards differ in height per conflict. It is **minor** because
  that page does not need virtualisation at merge-report sizes; if it ever does, the fix belongs upstream in the
  library rather than in a page-specific workaround. **No critical gap is claimed, so no decision about an additional
  library is requested** — which is itself the answer the `Done when` line asks for.
- **Two operational findings from the checkout are carried into the document** because this repository has paid for
  both: headless Chrome needs unrestricted file access (so *"the page is blank"* can be a sandbox fact, and the
  checkout's screenshot-plus-calibration technique is the check), and a PowerShell `2>&1 | …` pipeline fakes a
  non-zero exit while a redirect re-encodes text files.

---

## 12. Phase 8 — human-gated observations (no code; a checklist)

These are not "later"; they are the only deliverable that needs a person. Each ends in a dated,
version-named record, because a claim that is not observed is not a claim
([`doc/ide-observation-checklist.md`](../webview/doc/ide-observation-checklist.md)).

| #   | What                                   | Who                                  | Record lands in                                                                 |
| --- | -------------------------------------- | ------------------------------------ | ------------------------------------------------------------------------------- |
| 8.1 | JetBrains § 8's five maintainer questions (vendor identity, the empty-allow-list default, one vs two settings services, dropping Kotlin, plan location) and acceptance criteria 2/5/7 in a running IDE | maintainer | [`plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) § 8, and `webview-jetbrains`' own docs |
| 8.2 | Eclipse 4.41 workbench observations — the caret landing, the unsaved buffer edit and the single `Ctrl+Z`, the dropins install layout, the two-live-hosts claim; then answer **Q2** (dropins vs p2) | maintainer | [`ide-observation-checklist.md`](../webview/doc/ide-observation-checklist.md) § 1a/§ 2a, then `PLAN-eclipse-host.md` |
| 8.3 | `java-watch-agent` Phase 4's lightweight IntelliJ/VS Code hooks (the `intellij-jwa`/`vscode-jwa` attempts were sidecar clients, not these; after 3.0q what a host has is `webview-vscode`'s language client and `webview-jetbrains`' LSP registration) | maintainer decides, agent implements | [`jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md) |
| 8.4 | The ACP spike's Zed run (see step 5.3) | maintainer                           | `PLAN-webview-suite.md` Phase 5 record                                          |

---

## 13. Phase 9 — CLEANUP (the final step)

Do this **after** the steps above are done or explicitly closed, never before: a plan is deleted when its
content lives somewhere better, not when it is inconvenient.

### 9.1 — Coverage check before anything is removed
**Who:** agent · **Size:** S

Re-read every document in [§ 3](#3-what-this-plan-schedules) and confirm that each open item in it is
either a step here, done, or explicitly dropped. Anything not accounted for gets a step **before** the
deletions below.

**Gate:** the checklist in [§ 3](#3-what-this-plan-schedules) is annotated: every row either "closed into
step N" or "done".

**Done 2026-10-08 — every row of § 3 is annotated, and the two items nothing scheduled got a step.**

- **The column already existed and was stale, which is the finding.** § 3's third column ("Its own state after
  this plan") was written at some earlier point and had drifted badly: it said *"still open — every step this row
  scheduled is unticked"* for the webview, eclipse, roadmap and sidecar rows, and **5.1, 7.1–7.6 and 7.7–7.10 are
  now `[x]`**; it said the merge-java row was *"partly done — done: 4.1"* while 4.1–4.4 are all `[x]`; and the
  suggestion-channel row carried a *status* ("new") where a disposition belonged while 4.14–4.17 are all `[x]`.
  So the step was not writing an annotation — it was **correcting** one, which is a stronger result than the gate
  asks for and the reason it was worth checking rather than trusting.
- **Every disposition was derived from the § Progress markers, not from memory**: a scratch script read each
  contributed step's marker out of the table and **asserted the value it expected before writing anything**, so a
  stale reading would have thrown rather than produced a confident wrong sentence. Rows whose steps are still `[ ]`
  are annotated **"closed in step N"** (which is what the gate asks: accounted for), and rows whose steps are all
  `[x]` as **"done"**. The stale "partly done" and "still open" phrasings are gone.
- **The roadmap row is the one that needed real work rather than bookkeeping**: it has **6** open items and step 6
  has **5** steps, so a count mismatch had to be resolved item by item. Resolved: `FieldAnnotation` → 6.1; the core
  entity interface contract **and** the view hierarchy rules → **6.3** (both are the one "advisory rules → hard
  failures" decision, which is why two items map to one step); type divergence + converter manifest → 6.4; the
  projection/DTO marker pattern → 6.5; and the API/core split is **explicitly deferred by its own text** ("Not a
  gap, a recorded scope decision"), so it is accounted for as dropped rather than pending.
- **Step 9.1's instruction for anything unaccounted for was followed literally**: exactly two items in the
  scheduled documents had no step — the sidecar's *"Add more tools (e.g., toString/equals generator) following the
  same surgical pattern"* and the agent's *"Create lightweight hooks for IntelliJ and VS Code"* — and they got
  **step 9.1b** ("Two open items nothing scheduled"), placed by this check *before* the deletions below, which is
  what the step demands. Both remain open boxes in their own documents, now each with a step that will decide them.
- **Three checkboxes were stale-but-done and are ticked with what closed them**: the sidecar's indentation item
  (7.1), the agent's OpenRewrite prototype item (7.5) and `todo.hipster-entity.md`'s merge item (7.3). This is the
  same class of staleness the 7.6 round found in `todo.java_watch2.md`, which suggests the pattern worth watching:
  **a checkbox in a source document is not updated by the step that does the work unless something makes it**.
- **Gate**: every one of § 3's **14** rows now carries a disposition — "done" or "closed in step N" — and `LINKS`
  is green. **Measured**: `bun scripts/check-repo-links.mjs` (280 files) resolves every link, including the ones
  that moved between the annotation and its record.

### 9.1b — Two open items nothing scheduled
**Who:** agent · **Size:** S

Step 9.1's coverage check found exactly two items in the source documents that no step accounts for, and its own
instruction is that such an item "gets a step **before** the deletions below" — so this is that step, added by the
check rather than after it:

1. [`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md) — *"Add more tools (e.g., toString/equals
   generator) following the same surgical pattern."*
2. [`jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md) — *"Create lightweight hooks
   for IntelliJ and VS Code."*

**Do:** decide each one **in the document that carries it**, so no open box is left without a disposition: either
schedule it as a named step with its scope, or strike it through with the reason it is not wanted. A strike is a
decision and must say what makes the item unnecessary or superseded — the two candidates are not obviously dead
(the sidecar has three surfaces a fourth tool could serve, and the host story changed when DEC-039's amendment
deleted the in-repo clients), which is exactly why they need deciding rather than deleting.

**Gate:** both documents show either a schedule reference or a struck-through item with a reason, and step 9.1's
§ 3 annotation for their rows names this step.

**Done when:** no document this plan schedules still has an open box that nothing accounts for.

**Done 2026-10-08 — both items struck with a reason, each in the document that carried it.**

- **"Add more tools (e.g., toString/equals generator)" in [`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md)** —
  struck because the sidecar is the **wrong home** for it rather than because nobody wants it: this plan owns the
  *host* half (the LSP surface, `/jump`, `/applyEdit`, the code action that hands a builder to the client), while a
  generator that rewrites members is a *tool*, and tools live in the agent's `ToolRegistry` where the engine does
  the editing. If it is wanted it is **one new `ActionTool`** registered in `WatchAgent`, modelled on
  `RenameMemberTool` (step 7.5) — a when-wanted improvement on infrastructure that already exists, not open work.
- **"Create lightweight hooks for IntelliJ and VS Code" in [`jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md)** —
  struck because it is **delivered**: the two hosts exist as `webview/webview-jetbrains` (the `WebView Explorer`
  tool window) and `webview/webview-vscode` (the extension, whose buffer edit — unsaved, in the editor's own undo
  stack — is asserted by its own nine tests). The item predates those modules, and this plan's own dashboard work
  (step 7.2) now reaches the editors through `webviewd` and the sidecar rather than through a hook per editor.
- **Both were checked against the tree before deciding**, which is what keeps a strike from being an opinion: the two
  host modules exist (`git`-tracked, with their own tests), and **no toString/equals generator does** — so the first
  item is a real gap in the *wrong* plan and the second is a real delivery the checkbox never noticed.
- **§ 3's annotation for the row carrying both documents now names the decision in the past tense**, with the two
  reasons, so a reader of the checklist is not sent looking for a step that has already run.
- **Gate**: both documents show a struck-through item **with its reason**; § 3 names step 9.1b for their row; and the
  four documents this plan schedules that carried open boxes — `jwa-sidecar/plan.md`, `jcodebuddy-agent/plan.md`,
  `todo.hipster-entity.md`, `todo.java_watch2.md` — are now at **zero** open boxes between them. `LINKS` green
  (280 files).

### 9.2 — Archive the superseded plans
**Who:** agent · **Size:** S

Move, do not delete — the repository's convention is
[`archive/`](../archive) (it already holds the retired root POMs and `FORY-SERIALIZATION-ISSUES.md`):

1. `git mv doc/continuation-plan-legacy.md archive/plans/continuation-plan-legacy.md`
2. `git mv doc-hipster-entity/roadmap/plan-for-continuation.md archive/plans/hipster-entity-plan-for-continuation.md`
3. Rewrite every relative link in the moved files (they were written for their old depth) and every link
   **to** them; a moved file is a link change, never just a file change.
4. **Leave [`plans/rewrite-migration/`](rewrite-migration/README.md) where it is.** It is not a plan to
   clean up: its README is the delivery record, `scripts/rewrite-migration/verify-migration.js` and the
   honest-docs check read it, and it is linked from the tree. It already carries the "plan as written"
   banner (added 2026-10-01).
5. Same treatment for [`plans/inject-examples-feature-port.md`](inject-examples-feature-port.md) if the
   port report's historical value is not worth the front-page clutter — otherwise leave it with its
   status header.

**Gate:** `LINKS` green, and `git status` shows the moves as renames (not delete+add).

**Done 2026-10-03.** Both plans are in `archive/plans/` as **renames** — `git status` shows `R`, not delete+add —
each carries an archive banner pointing at this plan as the single schedule, and the five relative links the move
broke (four in the legacy plan, one in the hipster-entity one) are repaired: the checker named them, so none was
guessed at. `plans/inject-examples-feature-port.md` stays where it is, with its status header, as item 5 allows —
archiving it would hide the outcome rather than tidy anything.

### 9.3 — Remove the local scratch
**Who:** agent · **Size:** S

These are not this repository's content and never were:

- **`.kilo/plans/*`** (six files, gitignored): their open items are now steps 1.2–1.4, 2.1–2.2 and
  3.1–3.3. Delete them — but only after step 9.1 confirms nothing is left behind, because once deleted
  they are gone from the working tree (git never had them).
- **`.kilo/worktrees/snapdragon-motorcycle`** — a stale Kilo worktree, clean, detached at `0c5f200`
  (eight commits behind `main`). Confirm it is clean, then `git worktree remove` it and `git worktree
  prune`; check `git worktree list` afterwards.
- **Root zero-byte files `explicit` and `hipster-entity`** — debris from a mangled command line
  (2026-09-20). Verify they are empty, then delete them.

**Gate:** `git status` clean of scratch; `git worktree list` shows only the main checkout.

**Done 2026-10-03.** The scratch is gone, and two of the three bullets had to be corrected before they could be acted
on — which is the reason this step says to confirm first:

- **`.kilo/plans/*` — six files deleted** (`archive-orphaned-poms`, `dec-w007-refinement`,
  `hipster-ioc-integration`, `metadata-server`, `metadata-no-cache-adr-plan`, `metadata-arena-module`; 61 KB
  together). All six were untracked, so git never had them and nothing is recoverable — which 9.1's coverage check had
  to confirm first, and did: every item they carried is a step in this plan, annotated in § 3.
- **The stale worktree at `.kilo/worktrees/snapdragon-motorcycle` is removed** (clean, detached at `0c5f200`), and
  `git worktree prune` leaves `git worktree list` showing the main checkout alone.
- **The two root zero-byte files are not there, and one of them was never debris.** `explicit` no longer exists —
  already cleaned up on some earlier pass. `hipster-entity` **is the repository's own module directory**, not a
  mangled-command-line leftover, and deleting it would have removed six modules; the bullet is obsolete as written and
  is left in place as the record of a hazard rather than acted on. That is why the step's own order is "verify they
  are empty, then delete" — a check that a zero-byte file cannot be a directory is the part worth keeping.


### 9.4 — Retire the per-plan "open list" sections
**Who:** agent · **Size:** S

The lists that this plan absorbed must stop reading as live work:

- [`todo.hipster-entity.md`](../todo.hipster-entity.md) and [`todo.java_watch2.md`](../todo.java_watch2.md)
  — after steps 7.3 and 7.6, delete them, or leave each with a one-line pointer to this plan.
- [`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md)'s "Future Refinement" and
  [`webview/webview-jetbrains/plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) § 8
  — after steps 7.1 and 8.1, replace the lists with the outcome ("both delivered in commit X" /
  "answered 2026-XX-XX, recorded here").
- [`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) § 1 rows — tick the
  ones steps 6.1–6.5 closed; a tracker whose rows outlive their work is the same defect this plan exists
  to fix.

**Gate:** no document in the repository lists an open item that is not in this plan's table or in
`proto/`.

**Done 2026-10-08 — the absorbed lists are records now, and the gate was verified by a repository-wide scan.**

- **The two todo files became records that name the plan.** [`todo.hipster-entity.md`](../todo.hipster-entity.md)
  was **reduced** to a closed record: its status line now says it is no longer a work list, the proxy-merge
  sub-item is ticked (step 7.3 delivered it on the tracking builder too), and the prose that still claimed the
  merge was *"open, and nothing has started it"* is gone — that paragraph had survived the very step that closed
  it. [`todo.java_watch2.md`](../todo.java_watch2.md) keeps its per-item reasons (they are the 7.6 decisions) and
  now names the live schedule. Both files are **kept rather than deleted**, which the step allows and which also
  keeps every link to them resolving; the step's intent — that they stop reading as live work — is met by the
  status lines.
- **The sidecar's "Future Refinement" is resolved, not pending.** Its heading says so, and its status blockquote —
  which still claimed *"both items below are genuinely unstarted"* — now records what closed each one (7.1 for the
  client indentation, 9.1b for the "more tools" item it struck). The item bodies keep their reasons, so the section
  reads as the record of a decision rather than a queue.
- **The roadmap tracker is annotated row by row**, because nothing could be ticked: all five work rows map to steps
  6.1, 6.3 (twice — the two rows ask the one "should the conventions be enforceable" question), 6.4 and 6.5, and the
  sixth row is **dropped by decision in its own words** ("Not a gap, a recorded scope decision"). A tracker whose
  rows outlive their work is the defect this plan exists to fix, and the fix here is that each row now names the
  step that closes it.
- **The jetbrains plan needed nothing**: [`webview/webview-jetbrains/plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md)
  already carries an **Implementation record** section and no open boxes, which is exactly the outcome 9.4 asks
  for — worth stating, because the step named it as work and the work had already been done by whoever wrote that
  record.
- **The gate was checked, not asserted.** A repository-wide scan (Markdown outside `proto/`, `archive/`, `.tmp/` and
  `target/`) leaves **eight** documents with unchecked boxes after these edits, and every one is either a **reader
  instruction** — `field-lookup-guide.md` ("Implementing field-name-to-ordinal dispatch"), `deep-change-tracking.md`,
  `ordinal-array-contract.md`, `ADDING_A_RESOLVER.md` ("The three steps"), `THREE_WAY_FIXTURES.md` (what a fixture
  must contain when you add one) and `page-authoring.md` ("the shapes, the skeleton, the checks") — or
  **historical-with-a-banner**: `plans/rewrite-migration/`'s plan states in its own banner that its deliverable
  boxes "are unchecked and stay that way", because they track the plan as written rather than today's tree. None of
  the eight lists project work that this plan's table does not account for, which is what the gate asks.
- **The pattern this round and the two before it found, worth carrying forward**: the *work* was never the stale
  part — the trackers were. § 3's column, three checkboxes in two plans, a roadmap row set and a sidecar status line
  had all fallen behind steps that were long finished. Anything that duplicates a status is a thing that can drift,
  and this plan's own § Progress table is the one copy that is kept true by the gate.

### 9.5 — The full sweep
**Who:** agent · **Size:** S

**Gate:** all of these green, in one run, after the moves:

```
bun scripts/mvn-jdk25.js          # GATE
node scripts/check-repo-links.mjs # LINKS
node webview/check-links.mjs      # LINKS
npm run check:examples            # EXAMPLES
```

**Done 2026-10-08 — all four checks green, and the GATE's verdict is the correct one rather than a lucky one.**

| Check      | Command                            | Result                                                       |
| ---------- | ---------------------------------- | ------------------------------------------------------------ |
| `GATE`     | `bun scripts/mvn-jdk25.js`         | **BUILD SUCCESS**, whole reactor                             |
| `LINKS`    | `bun scripts/check-repo-links.mjs` | 280 Markdown files, **all relative links resolve**           |
| `LINKS`    | `node webview/check-links.mjs`     | 31 files, 242 relative links, **all resolve**                |
| `EXAMPLES` | `npm run check:examples`           | **exit 0** — every marker in `merge-java/docs/resolvers` and |
  `materialization-levels.md` matches |

- **The `GATE` run was fully restored from the build cache — every module reported in under 0.5 s — and that is the
  correct verdict for this change set rather than a gap in the evidence.** It was checked, not assumed:
  [`.mvn/maven-build-cache-config.xml`](../.mvn/maven-build-cache-config.xml)'s global includes are `scripts`,
  `webview/conformance`, the config itself, `docs`, `merge-java/docs` and the two `AGENTS.md` files, so **`plans/`,
  the two todo files, `doc-hipster-entity/roadmap/` and `webview/jwa-sidecar/plan.md` are not inputs to any module**
  and a Markdown move cannot break a build. What the cached run proves is exactly that: the checksum matched, i.e.
  no module input changed. The last **executed** full gate (round 56, 08:29, with `MetadataCliTest` running inside
  it) is green, and nothing has touched a module input since.
- **Two bookkeeping findings, both in the neighbourhood of the final step, recorded here because this sweep is where
  they surfaced.** (1) The cache config's own comment points at *"the final plan step (9.7)"* for the checksum
  examination, while the plan's cache-validation step is **9.8** — a stale step reference in a file that is itself a
  cache input, so it is worth correcting when 9.8 runs. (2) **Step 9.8 has no § Progress row at all**, which means
  the one step reserved as the final validation could never be ticked; 9.6's item 1 is where a missing row is
  repaired, and this is the record that it is missing.
- **No document was edited by this step** beyond its own record and row: 9.5 is a verification, and its deliverable
  is the evidence above rather than a change.

### 9.6 — Close the books
**Who:** agent · **Size:** S

1. Tick every completed box in [§ Progress](#progress) and mark the plan's status line.
2. Update [`plans/README.md`](README.md) — it already says what lives in `plans/`; add the outcome line
   for this schedule (closed on <date> / still live) so the directory index and the plan agree.
3. If every step is closed or explicitly dropped: `git mv plans/unified-plan.md archive/plans/unified-plan.md`
   with a final status block recording the dates, and leave a pointer to it from `plans/README.md`. If any
   step is still open, the plan **stays** where it is; a schedule with open steps is a live document.
4. Remove the "scheduled in `plans/unified-plan.md`" pointers from the documents whose steps are now
   closed, so the cross-links do not outlive the schedule.

**Items 1–3 done 2026-10-03; item 4 waits on a condition that has not happened, and that is recorded rather than
fudged.**

1. **§ Progress is ticked from the records.** It reads 34 done / 32 open of 78, up from the 26 the 9.1 check started
   from: six rows whose own step bodies said done while their boxes said open (3.0e, 3.0k, 3.0p, 3.0q, 3.0t, 7.8),
   plus 9.2 and 9.3, whose changes were untracked or outside git and so could not tick themselves. The tick reads
   each step's body for a completion **record** — a date, or "the step is complete" — and refuses to count a
   criterion, so a `**Done when:**` line can never tick a box.
2. **[\`plans/README.md\`](../plans/README.md) carries the outcome line** — still live, 34 of 78, and what
   remains, so the directory index and the plan agree instead of the index describing an intention.
3. **The plan STAYS where it is.** Its own rule — archive it only if every step is closed or explicitly dropped —
   does not hold: 33 rows are open, and one of them (9.8) is reserved on purpose as the final validation. A schedule
   with open steps is a live document, so this is the decision the rule produces rather than a choice made here.
4. **Item 4 is deferred, with its trigger named.** It exists so a cross-link does not outlive the schedule, and the
   schedule is still live, so the pointers it targets are the index TO it rather than residue of it. There are ~57 of
   them across 29 documents (DEC-037, DEC-027, AGENTS.md, the watch decisions, the ROADMAP, the webview plans), and
   most are accurate history: a decision that says "scheduled as step 3.0j" is recording what was known on its date.
   The removal belongs to whichever change finally archives this plan — the same change that makes the pointers
   false — and doing it now would delete live navigation to satisfy a rule about dead links.


**Done when:** the repository has one place that says what is open, and it says "nothing".

### 9.7 — Webview navigation from generated markdown: every location syntax, and markdown rendering
**Who:** agent · **Size:** M

**Why.** The suite renders generated pages that link to source, and a link can only land on a **line** today: the
frozen contract carries `data-open` and `data-line`, `window.openFile(path, line, column)` and
`GET /open?filePath=&line=&column=`, and the JetBrains plugin resolves a `#L42` fragment (`NavigatorServiceTest`).
So a page can say "line 42" and cannot say "this method" or "this region" — while the repository's own
documentation is *written* that way, because `@hrg/inject-examples` markers name a real file and a location inside
it (`[Example.java](../merge-java/docs/resolvers/README.md#region:add)`). The maintainer asked for the whole
grammar, including the spellings that are only a location and not injection at all, and for it to be documented in
[`webview/README.md`](../webview/README.md).

**The grammar is the deliverable.** Every fragment below is a location; `L…` wins over `region:`, which wins over a
bare name, and a fragment that is none of them is not a location and stays an ordinary link:

| Fragment                        | Means                                                                                   |
| ------------------------------- | --------------------------------------------------------------------------------------- |
| `#L42`                          | line 42 (already supported)                                                             |
| `#L42-L58`                      | a line range                                                                            |
| `#someMethod`                   | the declaration named `someMethod` — a method, constructor, inner class                 |
| `#region:add`                   | the region, or the declaration, named `add`                                             |
| `#region:-add`                  | that declaration's **body only**                                                        |
| `#region:+add`                  | the declaration **and its annotations**                                                 |
| `#region:++add`                 | the declaration, annotations **and doc comment**                                        |
| `#region:name,scripts.test`     | dotted JSON key paths, for `.json` — inject-examples' JSON rule                         |
| `#add`                          | the declaration **or** the `#region add` directive — whichever the file has, because a link should not have to know which one the author wrote |
| `#-add` / `#+add` / `#++add`    | the same as `#region:…`, without the prefix: the prefix is never required, only allowed |

**Do:**

1. **One location grammar, in two languages** (JavaScript for pages, Java for hosts), with shared conformance
   vectors beside `webview/conformance/bridge-decisions.json` — this repository's way of keeping a rule that lives
   in two languages honest, and the reason a page and a host cannot drift apart on what `#region:++add` means.
2. **The page carries the target and nothing more.** The fragment rides inside `data-open`, which the frozen
   contract already calls a path: the page splits it off only to check the file exists, keeps a plain `#L42` as
   the page's own line, and passes every other spelling through verbatim — so a link spelling nobody has thought
   of yet still reaches the host. `data-member` stays exactly what the contract says, display metadata.
   `data-member` stays exactly the display metadata the contract says it is (never a routing key).
3. **The host resolves the fragment to a position and selects it.** JetBrains resolves a member through PSI where
   it can and falls back to a name search; a line range is selected as a range; a JSON key path lands on the key's
   line. A host that cannot resolve one says so rather than landing on line 1, because a wrong line costs a click to
   discover.
4. **A host advertises nothing about locations, and the page negotiates nothing.** The maintainer's
   correction of 2026-10-04: "the webapp part need not know and host should not need to advertize anything about what
   links there are - it just has to accept navigation to a file with line number as well as syntax for regions and
   method/field name and even sanitize inject prefixes to extract location marker." So there is no capability key and
   no second attribute: `Navigator` accepts the fragment, strips an inject prefix (`region:`) and a scope modifier
   (`-`, `+`, `++`) to find the marker, and resolves what is left.
5. **Markdown rendering**, so a document can be read in the webview at all: the vanilla renderer under
   `markdown-view/` in a page the host serves, with its links classified by the grammar. An
   `@hrg/inject-examples` marker keeps its inject half (the fenced block that follows is still the file's content)
   **and** becomes navigable, because "jump to the injection point" is the point.
6. **`window.openFile` and `/open` gain an optional fragment**, not a new required argument; `bridgeVersion` moves
   only if that turns out to be incompatible. The contract and the decision record are amended accordingly, and
   [`webview/README.md`](../webview/README.md) documents the grammar, the attribute, the fallback ladder and how a
   host reports its capability.

**Gate:** the shared vectors (both implementations agree on every fragment spelling, including the ones that are
not locations), a host test that a member fragment lands on the member's line rather than line 1, a page test that
a marker is both injected and navigable, and the documentation's own link checks green.


**Done 2026-10-04 - a link names a place, and a click reaches it.** What landed, in the order it was committed:

- **The grammar, as claims first**: `webview/conformance/location-fragments.json` holds 39 hand-written vectors for
  every spelling this step names - `#L42`, `#L42-L58`, `#someMethod`, `#region:add` with `-`/`+`/`++`, the same
  without the prefix, a `.json` key path, and the fragments that are deliberately not locations - and two readers
  assert them: `scripts/webview-location` (JavaScript) and `webview-core`'s `LocationFragment` (Java). Neither
  generates the file, per `webview/conformance/README.md`.
- **A name becomes a position** (`LocationResolver`): a region directive wins over a declaration of the same name,
  exactly as in inject-examples' code rule, so one spelling can name either; a declaration must open and close a
  brace, which is what tells it from a call to it; `-` lands in the body while `+`/`++` land on the declaration; a
  `.json` key lands on its key. **Nothing is invented**: a name the file does not have resolves to nothing, and
  every answer says how it was found.
- **The funnel resolves it for every host** (`Navigator`): a fragment may ride in the path inside `data-open`, which
  the frozen contract already calls a path, and the fragment wins over the line when it is a location. A marker the
  file lacks is refused (`LOCATION_NOT_FOUND`) and the host is never called; a fragment that is not a location in a
  document leaves the page's line standing, because a heading anchor is the viewer's business. **JetBrains reaches a
  method or a region through this**: both its `open` and its `openUrl` delegate to the Navigator, so no plugin code
  was needed.
- **The page renders location links** (`markdown-view`): `splitTarget` splits at `#`, keeps the file (to
  check it exists) and a plain `#L42`, and passes everything else through verbatim; `makeLink` puts the whole
  spelling on `data-open`. A marker keeps both halves, and a test asserts both: the marker line becomes a link to
  the file it names, and the fenced block after it is still the include.
- **Documented where it was asked for**: `webview/README.md` gains "Pointing at a place inside a file"; the frozen
  contract is amended rather than replaced (§ 1.1.1, and a sixth acceptance item); and DEC-028 - the decision that
  owns where a link points - carries an amendment naming who parses what.

**The maintainer corrected the design mid-step, and the correction is the design.** "The webapp part need not know
and host should not need to advertize anything about what links there are it just has to accept navigation to a file
with line number as well as syntax for regions and method/field name and even sanitize inject prefixes to extract
location marker." So the capability key and the `data-fragment` attribute this step first promised were removed, and
the page stopped reading the grammar at all: it carries the target, and the host strips `region:` and a scope
modifier to find the marker. A smaller contract than the one the step was written with.

**Evidence:** the page's own suites (markdown view 37, page grammar 6, markdown view's seven previously-red tests
fixed on the way), webview-core 200 tests, and the recorded gate green with the cache on; LINKS green. Two things
this session did **not** measure, and will not claim: the JetBrains plugin's own Gradle tests were not run here
(its code path is the shared Navigator, which is covered), and a live click in a real IDE is an **observation** -
the repository's own `webview/doc/ide-observation-checklist.md` is where that belongs, not a unit test.

**Done when:** a link in a generated markdown page can name any location inject-examples can name, and a click
reaches it in the editor.
### 9.8 — Validate what the build cache checksums, and add what it misses (LAST STEP)
**Who:** agent · **Size:** M, and it is the **final step of this plan**

**Why it is last, and why nothing before it validates this.** The maintainer's instruction of 2026-10-03, when the
build cache landed: *use the cache aggressively; do not validate how it computes its checksum until the end.* The
cache is correct about everything Maven can see — it checksums a module together with all of its dependencies and
invalidates the whole module when anything in that closure changes — and this repository has inputs that Maven
**cannot** see, because they are run by Bun and not by a plugin: the generator and tooling scripts, the
`entity-html` renderer, the ioc generator, and any tool an agent runs by hand before a test reads its output. A wrong
answer there is a green build from an entry that should have been invalidated, which is the F-47 failure class this
repository already has a scar for.

**What is in place until then, by assumption and deliberately unverified:** `.mvn/maven-build-cache-config.xml`
lists extra inputs under `input/global/includes` (the Bun tools under `scripts/`, the shared vectors under
`webview/conformance/`, and that config file itself). Some of those paths may not resolve relative to a module and so
hash nothing; the list is there because it can only under-cover, never corrupt an entry, and because it is the shape
the answer will take.

**Do:**

1. Establish what the extension actually checksums — its own documentation, and experiments on this reactor: which
   file globs, whether POM/plugin configuration and parent POMs participate, whether system properties (`-Dtest=…`,
   `-DskipTests`) do (**measured once already: they do not**), and how a dependency's checksum propagates to its
   dependents.
2. Verify the assumed include list resolves, and fix the paths that do not.
3. Decide the answer for inputs Maven cannot see: extend that list, or fold a hash of them into a **POM property** the
   checksum does cover (the mechanism the maintainer named), or — if the honest answer is "the cache cannot see
   these" — record the boundary and the purge rule instead.
4. Re-check the two harnesses that read a sibling module by path (`CompileHarness.generatedSourceClasspath` and
   `GeneratedTrackingBuilderContractTest.classpath`): they now accept the restored jar as well as `target/classes`,
   which is what makes a cached build and an uncached one agree.
5. Record the verdict in `doc/AGENTS.md` and in this plan, and delete whatever of the above turned out to be
   superstition.

**Gate:** the verdict is written down, the include list is either verified or replaced by something verified, and a
full gate run passes with the cache on.

**Done when:** a reviewer can answer "if I change file X, which modules rebuild?" from the repository alone, and the
answer is right for the files Maven cannot see as well as for the ones it can.

---

**Done 2026-10-08 — the examination found the include list was under-covering, not merely unverified, and the fix
is now a check rather than an assumption.**

- **Item 1 — what the extension actually checksums**, from the measurements this repository has accumulated and from
  this step: a module **together with its whole dependency closure**; **not** a command-line `-Dtest=` filter (the
  same key as a full run, which is how a narrowed run can poison a full one); **not** `-D` system properties; a
  restore leaves the jar and **no** `target/classes`; and a run that produces nothing (`validate`) must not populate
  an entry. All of that is in [`doc/AGENTS.md`](../doc/AGENTS.md), which is where a reader needs it.
- **Item 2 — the assumed list was verified, and it did not hold.** A global include is resolved against **each
  module's own basedir**, so a repository-relative path needs `../` repeated once per directory level. The list
  held a bare form and a `../../` form, which covered the root and the 24 depth-2 modules — and left
  **`project-automation` (depth 1, and in the recorded gate set) and the three depth-3 modules
  (`webview/core/webviewd`, `webview/core/webview-core`, `webview/eclipse/webview-eclipse`) resolving NOTHING**. A
  change under `scripts/` was therefore **invisible** to them: a cache entry could answer a build that never saw it,
  which is the F-47 failure this repository has a scar for. The list now carries a form per depth **that exists**
  (24 entries over depths 0–3), and one entry was deleted because it hashed nothing at all:
  `../../merge-java/AGENTS.md` names a file that is not in the tree.
- **Item 3 — the answer for inputs Maven cannot see: extend and verify, not fold a hash.**
  `${maven.multiModuleProjectDirectory}` would be depth-free and is the better answer *if* it interpolates in this
  extension's config schema — that is unverified, and an entry that silently resolves nowhere is worse than a
  complete list for the depths that exist, so the relative forms are used and a **new depth is caught by the
  checker** instead of by a silent gap. The POM-property mechanism the maintainer named is **not needed here**: it
  is for an input whose *path* cannot be named, and every input this repository has can be named.
- **Item 4 — both sibling-compiling harnesses were re-checked and both accept a restored build**:
  `CompileHarness.generatedSourceClasspath` and `GeneratedTrackingBuilderContractTest.classpath` contribute each
  module's `target/*.jar` **as well as** its `target/classes`, the second with the restore behaviour in its own
  javadoc. That is also what makes the four-module cache exclusion in `.mvn/maven.config` a **removal candidate** —
  recorded in the config itself, with the one experiment that would decide it (a full gate run with the exclusion
  gone) left unrun on purpose, because relaxing the build's correctness without that run is the trade this step
  exists to refuse.
- **Item 5 — the verdict is written down, and the superstition is deleted**:
  [`doc/AGENTS.md`](../doc/AGENTS.md) now states what is covered, what is not, the depth rule and the checker; the
  config's own header no longer says the list is unverified or points at step 9.7 for an examination that is 9.8.
- **`bun scripts/check-cache-inputs.js` is the artifact that keeps this true** — a Bun script (rule § 2), walking the
  repository the way git sees it (`scripts/lib/file-walk/`), failing when an entry resolves for no module **or** a
  module resolves no entry, and printing coverage per depth so a failure names the depth that is uncovered. It is
  the answer to the step's own `Done when` line: *"if I change file X, which modules rebuild?"*
- **Measured**: the checker reports **30 modules, 24 includes, 0 dead entries, 0 blind modules** across depths 0–3;
  the **recorded gate passes with the cache on** and did a real rebuild of every module (tooling 14:19,
  `project-automation` 28.9 s) because the config is itself an input; `LINKS` green.
- **One thing this step did not do, named rather than implied**: it did not remove the four-module cache exclusion,
  and it did not prove that `${maven.multiModuleProjectDirectory}` interpolates. Both are recorded with the
  experiment that would settle each, in the file that owns them.

## Progress

Legend: `[ ]` open · `[x]` done · `[~]` blocked (say why) · `[-]` dropped (say why) · `[TBD]` **waits on
a decision that is not made** — deliberately unscheduled, with the decision named (see Phase 3's banner;
it is not the same as `[~]`, which waits on something outside the plan, nor as `[ ]`, which is ready to
start)

| Step | What                                                                                            | Who                | Size | State                                                                                       |
| ---- | ----------------------------------------------------------------------------------------------- | ------------------ | ---- | ------------------------------------------------------------------------------------------- |
| 0.1  | Commit the EEnumSet overlap JMH delivery                                                        | agent              | S    | `[x]` (landed as `ff0dc49`, with 0.2, by the maintainer)                                    |
| 0.2  | Commit the stale-document corrections                                                           | agent              | S    | `[x]` (landed as `ff0dc49`)                                                                 |
| 1.1  | Honour `enabled: false` (DEC-018 / DEC-021 § 6)                                                 | agent              | M    | `[x]`                                                                                       |
| 1.2  | `MetadataProvider.parse` (DEC-W008)                                                             | agent              | M    | `[x]`                                                                                       |
| 1.3  | `WatchMetadataProvider` over the watch cache                                                    | agent              | M    | `[x]`                                                                                       |
| 1.4  | Test the MCP tool surface                                                                       | agent              | S    | `[x]`                                                                                       |
| 2.1  | metadata-arena unit tests                                                                       | agent              | M    | `[x]`                                                                                       |
| 2.2  | metadata-arena JMH benchmarks (or close as not needed)                                          | agent              | S–M  | `[x]`                                                                                       |
| 2.3  | Decision-grade arena run + the backend decision                                                 | agent              | S    | `[x]`                                                                                       |
| 3.0a | Settle the engine decision's open points (DEC-037, ADR first)                                   | agent + maintainer | S–M  | `[x]` — DEC-037 `Accepted`, DEC-038 created                                                 |
| 3.0b | Class relations (supertypes/interfaces + reverse) in the class index                            | agent              | M    | `[x]` — engine's `TypeRelation` + row `relations` (always emitted), `subtypesOf`; 6 tests; names stay as written, resolution is 3.0h |
| 3.0c | The cache: what is cached, and what invalidates it                                              | agent              | M    | `[x]` — closed as answered by 3.0g, which is where DEC-037 put it: one freshness contract instead of a cache per module, plus the mapping below |
| 3.0d | One implementation of `TypeResolver` over the index                                             | agent              | M    | `[x]` — `IndexTypeResolver` projects a row (kind, fields with their types, relations) and answers `null` for an unknown name; the seam grew `kind` + `relations`, because a generator without them has to read the file |
| 3.0e | Move hipster-ioc onto the metadata contract (parses nothing)                                    | agent              | M    | `[x]` (shape-defining) — **part one landed**: the index could not answer a factory (`default` vs abstract, parameter names, `@Circular`); the generator rewrite is what remains |
| 3.0f | The engine's skeleton in `jcodebuddy-core`, and the model it carries (DEC-037)                  | agent              | L    | `[x]` — 3.0f-1 classification, 3.0f-2 move + six inversions, 3.0f-3 answer contract, 3.0f-4 pass unchanged; members and relations are 3.0b's |
| 3.0g | Freshness: the watch loop, its events and its invalidation                                      | agent              | L    | `[x]` — `engine.fresh`: host reports, engine interprets; dependents from 3.0b relations; SAFE/STALE/UNKNOWN; 8 tests incl. DEC-038's "no watcher" made mechanical |
| 3.0h | Search: the queries every consumer asks                                                         | agent              | M    | `[x]` — `engine.query.MetadataQuery` over a set of indexes: FQN/kind/modifier/package/path + relations both ways, name resolution, `NotCovered` for members (3.0r); annotations answered, added 2026-10-02; 6 tests |
| 3.0i | Dissolve `jcodebuddy-codegen-api` into the engine                                               | agent              | M    | `[x]` — five types split into `engine.query` + `engine.codegen`, module deleted, consumers re-pointed; also fixed the migration sweep's blind spots (a rename had silently dropped 21 sources) |
| 3.0j | Move the remaining consumers onto the engine                                                    | agent              | L    | `[x]` — closed 2026-10-07 by checking each named consumer: `jcodebuddy-meta` parses through the engine's `SourceReader`, the passes are the base cache's caller, the watch tools moved with 3.0n's rename, and the renderers and sidecar were measured to have no private path |
| 3.0k | Grow the recorded gate to cover the engine's contract                                           | agent              | S    | `[x]`                                                                                       |
| 3.0l | Extract the marker leaf out of `jcodebuddy-core` (DEC-038)                                      | agent              | S    | `[x]` — `jcodebuddy-generated`: three types, no compile dependency, package unchanged (no import churn); named in `GATE_MODULES` so its 49 tests keep running |
| 3.0m | `metadata-server` becomes `jcodebuddy-meta` (DEC-038)                                           | agent              | M    | `[x]` — also the package (`hr.hrg.jcodebuddy.meta.*`) and the MCP sibling; no class, method or wire shape moved; the sweep's module list caught the stale name |
| 3.0n | Absorb `jwa-builder*` and collapse the duplicate splice path (DEC-038)                          | agent              | L    | `[x]` — `class SourceSplicer` → one file (the engine's); no POM depends on `jwa-builder*`; the three docs that named a splicer without its home now say it; 329 tests green over the affected modules |
| 3.0o | Group the reactor's modules: `watch/`, `hipster-entity/`, `jcodebuddy/`, `hipster-ioc/`, `webview/` (DEC-039) | agent | M   | `[x]` — `merge-java`, `project-automation` and the doc trees wait on "others to be decided" |
| 3.0p | Audit the five earlier sidecar attempts against today's webview (DEC-039 amendment 2)           | agent              | M    | `[x]`                                                                                       |
| 3.0q | Merge what 3.0p found worth keeping, delete the rest                                            | agent              | M–L  | `[x]` (content decided by 3.0p)                                                             |
| 3.0r | The index grows members and annotations (DEC-029 format change)                                 | agent              | M    | `[x]` — `members` always emitted, closed kind vocabulary, member types/modifiers/annotations; `NotCovered` deleted, so the last unanswerable question is answered |
| 3.0s | `java-watch*` standalone: no Jackson, no OpenRewrite, nothing from this workspace               | agent              | M    | `[x]` — 17 → 0: SPI deleted, sample rewritten, and the agent moved to `jcodebuddy/` and renamed `jcodebuddy-agent` (it was JCodeBuddy's server in the watcher's group) |
| 3.0t | The model keeps what a consumer could ask (DEC-040)                                             | agent              | M    | `[x]` — **part one landed**: the relation's written text (D2's fix), loose generic matching, and the D4 contract test over the records; `permits`, `throws`, enum constants, initialisers, has-a-body and the imports sidecar remain |
| 3.1  | The hipster-ioc ADR                                                                             | agent              | S    | `[x]` (prototype: DEC-036 is `Trial`)                                                       |
| 3.2  | `CodeGenerator<GeneratedContext>` + dependency graph                                            | agent              | L    | `[x]` (prototype: the emitted shape is provisional)                                         |
| 3.3  | Make the hipster-ioc generator runnable and documented                                          | agent              | M    | `[x]` (prototype)                                                                           |
| 3.4  | The `@Circular` two-phase form                                                                  | agent              | ?    | `[x]` — **ticked 2026-10-08 (step 9.6) from this step's own dated record**: its body reads "**Done 2026-10-03"** and names what the step demanded (the shape written into the decision before code changed). The row still said `[TBD] — waits on how a lazily-resolved dependency is spelled` while the body had answered exactly that, which is the stale-tracker defect steps 9.1 and 9.4 kept finding. |
| 3.5  | `init*` methods in creation order                                                               | agent              | S    | `[x]` — **ticked 2026-10-08 (step 9.6) from this step's own dated record**: its body reads "**Done 2026-10-03"** and names what the step demanded (the shape written into the decision before code changed). The row still said `[TBD] — waits on how a lazily-resolved dependency is spelled` while the body had answered exactly that, which is the stale-tracker defect steps 9.1 and 9.4 kept finding. |
| 3.6  | Region markers above the thresholds                                                             | agent              | S    | `[x]` — **ticked 2026-10-08 (step 9.6) from this step's own dated record**: its body reads "**Done 2026-10-03"** and names what the step demanded (the shape written into the decision before code changed). The row still said `[TBD] — waits on how a lazily-resolved dependency is spelled` while the body had answered exactly that, which is the stale-tracker defect steps 9.1 and 9.4 kept finding. |
| 3.7  | Cross-context `dependencies()` / `ChildContext` creation                                        | agent              | M    | `[x]` — **ticked 2026-10-08 (step 9.6) from this step's own dated record**: its body reads "**Done 2026-10-03"** and names what the step demanded (the shape written into the decision before code changed). The row still said `[TBD] — waits on how a lazily-resolved dependency is spelled` while the body had answered exactly that, which is the stale-tracker defect steps 9.1 and 9.4 kept finding. |
| 3.8  | The dependency-graph report page                                                                | agent              | M    | `[TBD]` — waits on the graph model being settled                                            |
| 3.9  | Drive the generator from the dev-time pass and watch mode                                       | agent              | M    | `[TBD]` — waits on 7.8 and the shape                                                        |
| 3.10 | Retire `hipster-ioc-test`'s hand-written context                                                | agent              | S–M  | `[x]` — **the retirement was already done** (`997feaa`, step 3.0e part two: `CtxMainModule.java` is gone, `CtxMain` survives as the `@HipsterContext` interface), so this step's work was its **acceptance**, which is now run and recorded: `bun scripts/ioc-gen.js` reports `contexts read: 1`, `implementations: 0 written`, `refused: 0`, exit 0 — the generator recognised its own committed output as canonical and rewrote nothing — with the tree clean afterwards, which *is* the assertion because a divergence is reported rather than fatal (DEC-022). `hipster-ioc-test` has **no tests of its own** (`Tests run: 0`; no module depends on it), so the generator run is its evidence. **A documentation defect was fixed with it**: `hipster-ioc-tooling/README.md` listed `CtxMainModule.java` in its tree while the note above the tree said the hand-written wiring is gone. |
| 3.11 | Editor-agnostic graph navigation + embedded host                                                | human              | ?    | `[TBD]` — waits on 3.8, or gets dropped with a reason                                       |
| 4.1  | Replace `WIDENING_CHAINS` with supertype resolution                                             | agent              | S–M  | `[x]`                                                                                       |
| 4.2  | merge-java Phase 13 step 1 — review render                                                      | agent              | M    | `[x]`                                                                                       |
| 4.3  | merge-java Phase 13 step 2 — action display + sticky decisions                                  | agent              | M    | `[x]`                                                                                       |
| 4.4  | merge-java Phase 13 step 3 — LLM proposer behind the gate                                       | agent              | M    | `[x]`                                                                                       |
| 4.5  | Residual structural conflict should not veto a partly-overlapping block                         | agent              | S–M  | `[x]`                                                                                       |
| 4.6  | Quality level: evidence scale and claim arbitration                                             | agent              | L    | `[x]`                                                                                       |
| 4.7  | JetBrains port: sources, licence, pinned upstream checkout                                      | agent              | S    | `[x]` — the pin is verified against a real checkout (`verify-jetbrains-sources.js` exit 0), the `@derived` header is enforced by `JetBrainsAttributionTest` and was shown to fail on a real file, and the three-tier skeleton exists with the pin in one home |
| 4.8  | JetBrains port: text tier — line + word comparison, whitespace policies                         | agent              | L    | `[x]` — 14 vectors from `LineComparisonUtilTest`; the differ is an **LCS table, not Myers**, a deviation decided on measurement and recorded in `JETBRAINS_PORT.md` § 3.2; tier isolation enforced |
| 4.9  | JetBrains port: merge tier, SAFE half — range building, simple pass, refusals                   | agent              | L    | `[x]` — `MergeRange`, `MergeType`, `MergeRangeUtil`, `MergeRangeBuilder` and `MergeResolve`; **C1, C2 and C6 all tested** (`modifyDeleteShape` is the named C2 guard, cross-referencing `DESIGN_NEVER_AUTO_RESOLVED.md` § 2, with a control proving an insertion is not a deletion); **`MergeTierScopeTest` asserts the greedy pass, `DiffConfig` and the whitespace retry are absent**, in code rather than in a comment. Two upstream vectors are kept as **expected refusals** because they need word-level composition (4.14–4.15) — a named limit, not a gap |
| 4.10 | JetBrains port: whitespace policy as a caller-visible option                                    | agent              | M    | `[x]` — **functionally complete, and both named remainders are now closed.** The flag, the wiring and the acceptance pair were already in (`--whitespace=default | trim | ignore`, an unknown name refused rather than defaulted); the policy reaches detection (type recognition, regions, shapes) **and now the resolution and the report**: `MergeConflictResolver.setWhitespacePolicy(...)`, `MergeFileTool` passing it and **stamping it on every resolution** at the boundary where regions are restated, and `whitespacePolicy` written **per conflict and at file level** — the file-level key because under `IGNORE_WHITESPACES` the vector has **no conflicts at all**, so the per-conflict key can never carry the case that needs it most. The page shows ` · compared ignoring whitespace` only when the policy was lenient. Threading it also fixed an inconsistency earlier rounds introduced: offers were composed under a hardcoded `DEFAULT` while detection ran under the run's policy — measured before the fix, an `IGNORE_WHITESPACES` run reported `TRIM_WHITESPACES`. **923 tests**, page 22, both green. |
| 4.11 | JetBrains port: `AnalysisLevel` gains the intra-line evidence level                             | agent              | S    | `[x]` — **the level exists and is ordered both ways, and it now has a producer that reads words.** `MethodBodyChangeConflictResolver` declares `TEXT_INTRALINE` and records it **only when the word comparison produced the answer** (`MergeResolve.Result.wordLevel()`), recording `TEXT_LOCAL` when the line comparison alone reached it; the ported pass is consulted **before** the statement-set branches, because two edits of the same *line* look "disjoint" to a line-set comparison and it combined both lines. All three gate clauses hold: both directions order (`scaleOrdersEvidence`), no resolution exceeds its declaration, and a fall-back records `TEXT_LOCAL`. **A trap found on the way**: `reviewResolution` defaults the recorded level to `maxAnalysisLevel()`, so raising a declaration silently raises every answer of that resolver — the "never exceeds" test cannot see it, the weaker-level assertion did. Census unchanged (`escalated 3`, `offered 3`): the route and the recorded level changed, not the outcome. |
| 4.12 | JetBrains port: the conflict shape, ported onto detection                                       | agent              | M    | `[x]` — **`ConflictShape` computed by the ported classifier**, which makes it `MergeRangeUtil.getMergeType`'s first caller in this module; `ConflictType` unchanged, the report writes `shape` per conflict and the page renders it in its own words. **A copy helper that dropped the shape would have lost it silently** — `MergeFileTool` re-stamps regions, so `withRegion`/`withFilePath`/`withTypeContext` carry it and a test asserts it. The merge-range rule (an unchanged side has the **base's** lines, not the range's empty extent) appeared a **third** time, here costing "every one-sided change reads as a conflict". **One clause named as not met:** the decision does not yet *use* the shape — the 4.5/4.6 fixtures are unchanged and green, and the measurement that would justify the general fix belongs with 4.13 |
| 4.13 | JetBrains port: **parity gate** + upstream vectors + randomized property test                   | agent              | M    | `[~]` — **gate**: § 11.1 `18/18`, § 11.2 `2/3` + 1 recorded defect + 0 regressions; word-level corpus `2 offered at TEXT_INTRALINE, 1 refused`. **§ 10.2 rows all measured**: row 1 (`MEMBER_ADD` → `AUTO` at `STRUCTURE` where text-only refuses), row 2 with its control (corrupted `AUTO` → `REVIEW`+`FAILED`; balanced stays `AUTO`/`PASSED`), row 3 (`{TEXT_LOCAL=4, TEXT_FILE=2, STRUCTURE=1, PLATFORM_TYPES=1}`, 3 strategies), row 4 by the replay tests, row 5 half (offered `3`), and **row 6 now MET** (`AUTO` at `PROJECT_TYPES` with a project classpath, `PLATFORM_TYPES` without) — round 38's "unmet" was a **scope error**: the JDK-only corpus cannot contain the case, and the census print now says so instead of pronouncing on the row. **Open:** *blocks accepted in one action* (needs an accept action), and the shape/classification question (control measured `LEFT_UNCLASSIFIED`, `type null`). |  |
| 4.14 | Suggestion channel: `Suggestion`, `ResolutionKind.SUGGESTION`, `APPLIED_SUGGESTION`             | agent              | M    | `[x]` — the channel with **no producer and no page** (4.15/4.17 produce, 4.16 renders): `Suggestion` as a standalone value, the kind and its own field (the structural guarantee that the channel cannot write `resolvedCode` or `kind`), `LEFT_SUGGESTION` + `APPLIED_SUGGESTION`, `applied()` vs `settled()` so the tally and the exit status ask different questions, and the verifier **labelling** a failed suggestion instead of hiding it. **`APPLIED_SUGGESTION` has no producer yet** — it is the vocabulary the accept path will produce |
| 4.15 | Move the answers we already compute onto the suggestion channel                                 | agent              | M    | `[x]` — one rule in the orchestrator converts a `REVIEW` carrying code into a `SUGGESTION` carrying **that same text** (provenance = the resolver's name, level = what it recorded, `resolvedCode` cleared so the text lives in one place). **Measured: 3 of 7 sampled review paths were computing an answer and hiding it**, asserted by comparing the suggestion against a direct resolver call, and printed by the test. Nothing promoted; three existing assertions changed, each the step's own point |
| 4.16 | Page + decisions contract: Accept / Edit / Reject, and the bulk-accept guard                    | agent              | M    | `[x]` — the report carries the suggestion with its basis and verdict detail; the page prefills from `proposedCodeFor` (**a real defect: it read `resolvedCode`, which 4.15 empties, so a suggestion showed an empty editor**), renders provenance/confidence/level/verdict, and exports rejections per provenance. **The bulk guard was not a guard**: it filtered on "a field holds text", which quietly included `REVIEW` and contradicted its own javadoc — it is now `kind === 'AUTO'`. **A clause has no counterpart**: there is no CLI bulk action to guard, and that is recorded rather than invented |
| 4.17 | Suggestion rejection memory, proposer as a provenance, the SUGGESTION-class ports               | agent              | M    | `[x]` — **all three items are in.** Refusal memory (a `rejections` sidecar keyed by signature + provenance, filed under the key *as written* because the page sends no sides); the SUGGESTION-class ports (the greedy pass with its R4 deletion trade, R6 refusals and R2's whitespace retry as a *second* suggestion naming its policy), wired where nothing decided; and **`ConflictProposer` as one provenance** — its answer is a `Suggestion` (provenance `proposer`, `PLAUSIBLE`, the gate's verdict in the channel's uniform field, level `TEXT_LOCAL` because the tool can vouch for nothing about what a model read) from the same single call that produces its fix path. **The channel now holds an ordered list**: `getSuggestion()` is a view of the first, so every consumer that predates it is unchanged, and `theToolsAnswerStaysPrimary` asserts the case the single slot could not express. **Open:** the page renders only the primary (the JSON for the alternative is already there). |
| 4.18 | Hierarchical resolution: working set, conflict states, partition invariant                      | agent              | S–M  | `[x]` — behaviour-neutral as designed: `ConflictState`, `ResolutionPass` and `Region.covers`, invariant proved to fail on a dropped line and on a doubly-settled one; **the run order had to come from the declaration, not the record** (DEC-046 clause 13) |
| 4.19 | Hierarchical resolution: reliability, and a resolved region the lower tier is never asked about | agent              | M    | `[~]` — **the behaviour change is in**: `Reliability` is the predicate, `TieredResolution` is the pass (a settled conflict is *never offered*), the block explanation names every removal, and the report carries `state` per conflict. **Both instruction examples are fixtured**: two methods added in the same place (the text tier *cleared*, `never asked`), and an import hunk beside an undecided body hunk (`RESOLVED=1, OPEN=1`, `APPLIED_AUTO`, exit 1 — settled, scoped, residue `OPEN`). **And the failed-verifier flow is fixtured**: unbalanced answer → `REVIEW`/`FAILED` → `Reliability` refuses it, with a balanced control that stays `AUTO`/`PASSED` **and is still not reliable** — verification is not authority. **Open:** the shape/classification question the control measures as `LEFT_UNCLASSIFIED`/`type null`. |
| 4.20 | Hierarchical resolution: the state of every conflict in the report                              | agent              | S    | `[x]` — a `state` key per conflict (`RESOLVED`/`OPEN`/`PARTIAL`), aligned with the **conflicts** because a settled one has no resolution of its own and appeared nowhere before; `HierarchicalAcceptanceTest` asserts both states separately |
| 4.21 | Hierarchical resolution: the three-coordinate position model and the tiling invariant           | agent              | S–M  | `[x]` — `BlockComposition` places a block's lines in base/ours/theirs as segments **including the gaps** (the lines in no range at all, which a walk emitting only ranges drops silently), `audit` is the tiling invariant, and the `LEFT_PARTIAL_RESOLUTION` explanation now uses it. **A base-less block can be classified but not composed** — no base, no coordinates — so 4.22 works on `diff3` blocks. **Corrected in round 26**: an unchanged side's extent was the empty range the change flags imply, so the lines it had kept were covered by a *later* segment — coverage-correct and positionally wrong, and every later slice of that side would have been read from the wrong offset; `audit` now also reports an `UNCHANGED` stretch with unequal extents, which is the footprint that leaves |
| 4.22 | Hierarchical resolution: the splice, and the grown outcome enum                                 | agent              | M–L  | `[x]` — `BlockSplice` composes the settled stretches and the kept ones, marks only what is contested, and **refuses** rather than guesses (no base, a partly covered stretch, two answers, nothing settled); `Outcome.APPLIED_PARTIAL` is deliberately not counted as applied, because markers remain; the trigger is every outcome that keeps the block, measured green across the suite. **A defect a probe caught**: `TextLines` lines carry their terminators, so the first `append` doubled every line break — every assertion I had was a `contains`, and none would have seen it |
| 5.1  | webview Phase 6 — headless parity as a build gate                                               | agent              | M    | `[x]` — **the parity claim is a test result.** `webview-client.test.mjs` now drives **every** verb a headless host supports (it lists them, records every call, and asserts at the end that none went undriven — naming the missing one), and **`redo` was the verb the client exposed and nothing drove**: it is driven as a *pair* (undo → redo → undo, bytes checked each time), because a one-way undo would have passed the old assertions. The capability half is a **content** check complementing what already existed (`check-capabilities.js`: declared ⇒ served, undeclared ⇒ refused, 29 assertions; `HostHealthParityTest`: every host builds `/health` through `HostHealth` and refuses an unauthorized `/open` first): each capability name is mapped to **how it is proved** — `open`/`serveFile` driven here, `select` IDE-only with an assertion that `webview/doc/ide-observation-checklist.md` exists — and an unknown name fails. The README claim names all three checks. The extension went to `webview-client.test.mjs` rather than `smoke-test.mjs` (which drives *pages* with no host) so the repository has one host launcher, not two. **Measured**: test `37 passed, 0 failed`; `-pl webview/core/webviewd -am verify` `58 tests`, BUILD SUCCESS; `check-links.mjs` 242 links resolve; `check-capabilities.js` 29 passed. **Note**: `WEBVIEWD_JAVA` must point at JDK 25 on this machine (`JAVA_HOME` is 21) — the test diagnoses that itself. |
| 5.2  | Record the webview Q3/Q5 answers (Q2 by delivery)                                               | agent + maintainer | S    | `[x]` — **closed 2026-10-08 by the maintainer's answers.** **Q2** ("which hosts are in scope for the write verbs") is answered by delivery — every host has them, and the Reactor ships the JetBrains plugin, the Eclipse view, the VS Code extension and `webviewd`. **Q3** (do the JetBrains and VS Code plugins become proxying adapters, or keep their in-process implementations and share only the core?) is answered: **keep the in-process implementations and share only the core.** The reason is what each host is for — a buffer edit that lands unsaved in the editor's own undo stack needs to be in the editor's process, and a proxy hop would trade that away for a uniformity the shared core already provides. **Q5** was answered earlier (3.0p/3.0q merged the two capabilities worth keeping and deleted the five client directories). Nothing here is left open, which is this step's whole content. |
| 5.3  | ACP go/no-go spike                                                                              | human              | S    | `[ ]`                                                                                       |
| 5.4  | Eclipse Phase 5 — p2 update site (after Q2)                                                     | agent              | M    | `[x]` — **closed 2026-10-08 by Q2's answer: `dropins/` is acceptable, so the p2 update site is optional and stays last.** The step was gated on that answer ("**Do not start this before Q2 is answered**"), and the answer makes its Do either optional or unnecessary rather than pending: the Eclipse host already works headlessly with its own tests, and a Tycho p2 build is packaging rather than capability. If a release ever needs an update site, the shape the step describes (a feature + `category.xml` in a **separate Maven profile**, so the offline test loop keeps working) is where to start — recorded here so the option is not lost by closing the row. |
| 6.1  | `FieldAnnotation` exposure in view enums                                                        | agent              | M    | `[~]` — **DEC-047, the API, the emitter and its tests are in; the EXAMPLE half is blocked on a real finding.** The ADR came first (it changes generated output) and deciding it exposed a second defect that is now fixed: **the decisions index stopped at DEC-041**, so DEC-042–046 were unregistered; all six rows are in. Implemented: `FieldAnnotation(type, arguments)` in `hipster-entity-api`, `FieldDef.annotations()` as a **default** (so every existing enum compiles unchanged), and the emitter writing the override **only** for a field that carries annotations, in declaration order — `FieldAnnotationExposureTest` (3 tests) asserts the metadata, the order and exactly one override; the DEC-022 naming-contract table gained the row (wired by `@Override`, so an API rename breaks the build rather than drifting). **The blocker is a real asymmetry, not a puzzle**: the CLI path of `EntityMetadataGenerator` builds properties from the **metadata JSON** (`propNode.path("constraints")`) while the `generate(...)` path the tests use parses the **source**, so a freshly written annotation reaches the emitter on one path and not the other; `ExampleRegenerationTest` caught it by making the committed example differ from a source-based generation, which is why the example is currently unannotated — a green tree beat a demonstration nobody could regenerate. **Held up by step 6.6** (the metadata-freshness defect: the CLI path reads the stale `<Marker>.metadata.json`, so a newly written annotation never reaches the emitter — the example annotation and its `provided` validation dependency were reverted rather than left red): the pass, annotate one example accessor, regenerate so its constant carries `annotations()`, and confirm `ExampleRegenerationTest` is a no-op again. **Measured**: 12 tests green (3 + 9) on a run that executed them; `GATE` recorded in the record above. |
| 6.2  | Deep tracking: generator wiring 6.5 + patch applier                                             | agent              | L    | `[ ]`                                                                                       |
| 6.3  | Decide advisory → hard rule enforcement                                                         | agent + maintainer | M    | `[~]` — **enforcement decided 2026-10-08, the example's build change open.** Per rule: the **core entity interface contract becomes STRICT** (it is a correctness boundary — a view that does not derive from the marker is wrong, not merely untidy), while the **view hierarchy naming rule stays advisory** (`--validate` reports it), because a naming preference is a style choice rather than a boundary, and a build that fails on taste is a build people work around. **Remainder, precisely**: record the decision where the validator's rules live, make the example run `--validate=STRICT` for the contract rule only, and note in DEC-036 (or the validator's own documentation) that the naming rule is deliberately not fatal. |
| 6.4  | Type divergence analyzer + converter manifest (DEC-006)                                         | agent              | L    | `[ ]`                                                                                       |
| 6.5  | Projection/DTO marker pattern (DEC-003/DEC-007)                                                 | agent              | L    | `[ ]`                                                                                       |
| 6.6  | One staleness pipeline: a tiered gate, a watch entry and an on-demand entry                     | agent              | M    | `[~]` — **the tiered gate is in and provable; the generator/CLI/watch wiring is the remainder.** `FileMetadata` gained `lastModified` **without a format bump** (absent = `-1` = "hash and decide", so old entries stay usable and backfill themselves), and `MetadataCache.entryFor` now decides cheapest first: **stat (`size` + `lastModified`) → hash only when the stat moved → parse only when the hash differs**. `statHits()` reports tier-1 reuse separately from `hits()`, so the saving is asserted rather than assumed — before this, every lookup hashed every file on every pass. A gap in the design the test found is closed: a tier-2 hit now **rewrites the entry** from the observed stat (`withStat` + `refresh`, deliberately not counted as a stored parse), so a touched file or a legacy entry pays one hash rather than one per pass forever. **Measured**: 4 new tests + 8 `BaseLayerTest`, and `jcodebuddy-core`'s whole suite **72 tests green** including the format contract. **Remainder**: wire the generator's `<Marker>.metadata.json` refresh to this component (what 6.1 waits on), add the on-demand `metadata stale` / `rebuild --stale` entry, add the watch-mode entry, and run the whole-reactor `GATE`. |
| 7.1  | jwa-sidecar reads the client's indentation                                                      | agent              | S    | `[x]` — the sidecar reads the client's indentation. `ClientFormatting` (`tabSize`/`insertSpaces` → `indent()`, defaults **four spaces**, the value that was hard-coded) is the one place that knows what a client's settings mean; `didChangeConfiguration` — an empty stub until now — is the route they travel, because a code action is a command the user picks and carries no `FormattingOptions`; the setting is remembered on the server (`volatile`, written by the LSP reader thread while a code action runs) and read **at generation**. Flat and section-wrapped settings objects are both accepted, a missing field keeps its own default, an unrecognised shape (or a non-JSON `getSettings()`) yields the defaults rather than an exception, and `insertSpaces:false` gives a tab. **The gate test asserts a relationship between two runs** — every emitted line identical with half the indentation, plus the four-space and two-space anchors — because a golden string would pass again if somebody hard-coded the other indent; a third test pins the tab case. **Measured**: `SidecarCodeActionTest` 7 tests (from 5), `-pl webview/jwa-sidecar -am verify` BUILD SUCCESS. **Note**: `-pl` needs `-am` here, and a `-pl … test` run poisons the closure's cache (JAR-less entries; `package`/`verify` is the documented fix, plus purging `~/.m2/build-cache/v1.1`). |
| 7.2  | Agent web UI remote-jump front-end                                                              | agent              | S    | `[x]` — the dashboard jumps, and the page never holds the sidecar's token. `CommandServer` gained `/jump`, which forwards to the sidecar and **relays its answer verbatim** (`sendJson`; re-encoding through a map would be a second place for the two to disagree), so the page posts to its own origin and the **server** presents `X-WebView-Token` — the sidecar's origin gate never has to allow the page, and no token reaches a browser. Port/token are read **per request** (`jwa.sidecar.jumpPort`, default 7979, and `jwa.sidecar.token`) because they are facts about one machine's running host. **An outcome, never an assumed success**: the sidecar's own words are shown (`detail` → `reason` → `error` → raw body) and a sidecar that is not listening is **502 `unreachable`** with the port and reason. The page keeps its minimal vanilla shape (DEC-027): `API.jump`, a `Jump to editor` button beside `Review Diff`, a result span. **Gate**: `RemoteJumpTest` **2 tests** — the token is presented by the server, the requested location is forwarded, the outcome is relayed (`"reason":"exact"`), and an unreachable sidecar is reported as 502; a stub sidecar deliberately, since starting the real one would add a JDK-version dependency, a port race and an LSP client to prove the same three things. **The dashboard's JS has no test** — the module has no JS harness — so that half rests on the HTTP test plus inspection. **Measured**: `-pl jcodebuddy/jcodebuddy-agent -am verify` BUILD SUCCESS, `RemoteJumpTest 2 tests, 0 failures` beside the module's 15. **Traps**: this module is **JUnit 5** — a JUnit 4 test is *ignored* and the build stays green (found only by reading the surefire reports); and a cache **restore skips surefire**, so re-runs must change an input and be read from `target/surefire-reports/`. |
| 7.3  | `View1Builder.merge` + proxy merge                                                              | agent              | M    | `[x]` — **both halves are in.** `--merge <hostView>:<partnerView>` (repeatable, mirroring `--mapper`, applied once every view is resolved because a merge names two views) drives `ViewBuilderGenerator` **and** `ViewTrackingBuilderGenerator`, both deciding through `ViewMergeGenerator` so the plain builder and the tracking proxy merge the **same fields** with the **same diagnostics** — the proxy's merge calls the generated setters, so a merged field is tracked like one set by hand rather than leaving `changedValues()` silent. The pass **re-emits** with the partners attached (deterministic emitters reconciling member by member, so a hand-edited merge survives and an edited generated one is reported) rather than patching. **Gate test** over a pair sharing some fields and not others: `name`/`age` shared by type, `score` host-only, `extra` partner-only, `nickname` a `String`/`Integer` name-only match — asserted that the merge reaches both builders, that only the shared fields are copied, that unshared fields are neither copied nor reported, that `nickname` is reported as `merge_field_type_mismatch` and **not** copied, that the method is null-safe, returns the builder and contains no `build()`, and that **without the flag no builder gains a merge at all**. **Measured**: `ViewMergeWiringTest` 4, `ViewMergeGeneratorTest` 6, `DivergenceReporterTest` 7, `ExampleDivergenceReportTest` 4, and the **recorded GATE** (`bun scripts/mvn-jdk25.js`, `clean package`) **BUILD SUCCESS** over the whole reactor. **Deliberate omission, named**: the example project's own views gain no merge — the gate asks for a test over a pair, which the fixtures above are, and wiring the example would rewrite committed output for a shape the request already decides. **Harness lessons recorded**: a view fixture must extend a root view (`extends EntityBase<Long>, Identifiable<Long>`) or the pass emits nothing and the symptom is a *missing file*; `setGenerationPackages(...)` filters the pass, so a fixture plus a package filter yields no output; and the tracking builder is `<View>BuilderTracking`. |
| 7.4  | Documentation front door + cross-references                                                     | agent              | S    | `[x]` — **both items are closed.** Item 12: the root [`README.md`](../README.md) opens with a **Where to start** section routing a **user** to [`user/why-hipster-entity.md`](../doc-hipster-entity/user/why-hipster-entity.md) → [`getting-started.md`](../doc-hipster-entity/user/getting-started.md) (or [`getting-started-new-project.md`](../doc-hipster-entity/user/getting-started-new-project.md)), the guide index, patterns and FAQ, and a **contributor** to [`architecture/README.md`](../doc-hipster-entity/architecture/README.md), [`TOC.md`](../doc-hipster-entity/architecture/TOC.md), [`DECISIONS.md`](../doc-hipster-entity/architecture/DECISIONS.md) and the two `AGENTS.md` files. Item 13: measured first — **2 of the 11** non-decision architecture documents linked back (README and materialization-levels) — then the pointer was added to the **navigation surface** (TOC, DECISIONS, ADR-GUIDE, naming-conventions) and deliberately **not** to all nine, since the rest are measurement records and implementation guides; one sentence in five places rather than five sentences. [`doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md)'s banner now says *delivered, all items closed* and keeps the measurement. One placement defect found and fixed: the note first landed **inside `TOC.md`'s bullet list** (a link checker cannot see that), now under the title. **Gate**: `LINKS` green — 280 files, 1788 links, all resolve. |
| 7.5  | Agent OpenRewrite tool prototype                                                                | agent              | M    | `[x]` — **a real OpenRewrite-based tool runs from the agent's menu.** `RenameMemberTool` (`rename`) is implemented the repository's way (DEC-030): `SourceReader.read(...)`, `TreeQueries.findAll(unit, J.Identifier.class)` for the query, and a **text splice** for the edit — registered in `WatchAgent`'s `ToolRegistry` **and** in the default toolset's name list, and driven by the test with the same `SimpleToolContext` the menu passes. It is configured (`AgentConfig.renameFrom`/`renameTo`) and **applicable to nothing when unconfigured**, because a rename is a project decision a tool must not invent. **The safety property is the point**: the tree's identifier count must equal the text's whole-word occurrence count, and when they differ the tool **refuses and names the difference** (a string literal, a comment, a longer identifier) rather than producing a file that compiles and means something else; a rename onto a name the file already uses is refused too, and an unparseable file is simply not applicable (a half-written file is normal while editing). **Measured**: `RenameMemberToolTest` **4 tests**, module gate `-pl jcodebuddy/jcodebuddy-agent -am verify` **21 tests, BUILD SUCCESS** (ToolSeamTest 15, RemoteJumpTest 2, the new 4). **Finding, named not fixed**: the default toolset lists **`record_builder`**, which **no registered tool answers** — the same class of silent no-op this step exists to avoid, but a tool-identity question rather than an engine one. |
| 7.6  | Decide the three `todo.java_watch2.md` remainders                                               | agent + maintainer | S    | `[x]` — **all three decided with the maintainer, and the list has no unchecked item left.** (1) **Delete-specific debounce — struck**: deletes already ride the same trailing-edge window ([`BatchedFileWatcher`](../watch/java-watch-core/src/main/java/hr/hrg/watch2/core/BatchedFileWatcher.java) puts `DELETE` events in `ChangeSet.deleted()` after the same `debounceMs` measured from the last change), so a second knob would be unmeasured; reopen only with a case the shared window mis-batches. (2) **Memory-based LocalDB — struck, for the absence of a measurement rather than as impossible**: the premise does not match `local_db`, which chooses *where the `.scpdb` lives*; an in-memory mode would drop the cross-run state that is the database's purpose, and no measurement in the tree justifies it — reopen with numbers, and it would then want its own decision record. (3) **Zig port — struck, on the maintainer's own reason**: the ecosystem uses **wyhash**, deliberately compatible with the wider wyhash family (Zig's `std.hash.Wyhash` among them), and that compatibility is already delivered by *using the algorithm* — `hr.hrg.wyhash:wyhash:1.0.0` in `java-watch-core`, `Wyhash64` vendored as `ContentHash` with golden vectors (DEC-029 § 4) — so the item was a means whose end exists. **Two stale statuses found and corrected**: the list's two "Immediate Priority" items still read *open* although 7.2 and 7.1 closed them. **No ADR, stated rather than assumed**: no code changes, and none of the ADR guide's triggers (entity model, generated shape, runtime contract, module boundary) is touched. **Gate**: `todo.java_watch2.md` has **zero** unchecked items; `LINKS` green; no other plan step assumed any of the three. |
| 7.7  | Manual-mode CLI for DEC-W008 (`metadata parse`)                                                 | agent              | S    | `[x]` — **DEC-W008's manual-mode CLI exists.** `hr.hrg.jcodebuddy.automation.cli.MetadataCli` (in `project-automation`, the module the decision names) calls `IndexMetadataProvider.parseSource` — the decision's own no-cache reference path — and prints the `CacheEntry` as **one line of JSON**; the entry point is `bun scripts/jcodebuddy.js metadata parse <file>`, a Bun launcher that resolves JDK 25, compiles the module and runs the class from `target/classes` via `java -cp` (never `exec:java`, per gen.js's two measured reasons; **no jar, no install**, so it works in a checkout nobody has built). **Exit codes are contract**: `0` with the entry, `2` with the reason on stderr and **nothing on stdout**. **The fresh-checkout property is asserted, not promised**: the test runs against a file in an empty directory and asserts **no `.jcodebuddy/` was created**. **The round-trip is equality of the answer**: `MetadataCliTest` dispatches a real `parseFile` through `RpcDispatcher` and asserts the CLI's JSON equals the RPC's `result` **field for field**. Four choices confirmed with the maintainer (bare entry, one line, documented `bun scripts/jcodebuddy.js …` with no `bin/`/`.cmd`, and DEC-W008's stale item **closed while kept visible**). DEC-W008 updated in three places (status note item 4, § *Manual-mode CLI* with the invocation and the four choices, and the follow-up item settled); the root README's Bun-command table gained the line. **Measured**: `MetadataCliTest` 3 tests, 0 failures; the launcher run by hand printed the entry JSON with exit 0, created nothing beside the source, and exited 2 on a missing file; the **recorded GATE** (`clean package`, whole reactor) **BUILD SUCCESS**; `LINKS` green. **Noted, not hidden**: Maven's own JVM warnings (jansi/`sun.misc.Unsafe`) reach stderr from the launcher's build step — the CLI's streams stay clean, and another tool's diagnostics are not silenced. |
| 7.8  | Two kinds of generator: file-scoped and project-scoped                                          | agent              | M    | `[x]`                                                                                       |
| 7.9  | Set up the `jsx6` checkout every UI is built from (rule § 2.9)                                  | agent              | S–M  | `[x]`                                                                                       |
| 7.10 | What `jsx6` and `nodditor` can and cannot do for our pages (report gaps)                        | agent              | M    | `[x]` — **the assessment is [`doc/jsx6-capability-assessment.md`](../doc/jsx6-capability-assessment.md)**, written against the checkout at **`a584e7a`** and citing source rather than READMEs where a capability is claimed. All four page classes are covered: the vanilla minimal page (**not applicable by decision**, and DEC-027's amendment keeps it framework-free), the interactive review page (**sufficient** — the base stack covers its state and interactions; Monaco exists and is unused), the navigation page (**sufficient, prospective** — it is scheduled, not built, and the document says so), and the relations layer (**sufficient** — nodditor's node/connector/zoom-pan/undo sources, plus `line-render` for shapes and connectors). **One limitation, classified minor**: `virtual-scroll` requires fixed-height rows, so the review page's variable-height cards cannot use it — unnecessary at merge-report sizes, and the fix would belong upstream. **No critical gap, so no additional-library decision is requested**, which is the answer the step's `Done when` asks for; two operational findings (headless-Chrome sandbox needs, and the PowerShell traps) are carried in the document. |
| 8.1  | JetBrains maintainer questions + IDE observations                                               | human              | —    | `[ ]`                                                                                       |
| 8.2  | Eclipse observations, then Q2                                                                   | human              | —    | `[ ]`                                                                                       |
| 8.3  | Agent IDE hooks                                                                                 | human decides      | —    | `[ ]`                                                                                       |
| 8.4  | Zed ACP run                                                                                     | human              | —    | `[ ]`                                                                                       |
| 9.1  | Coverage check                                                                                  | agent              | S    | `[x]` — **§ 3 is annotated, and every row is derived from the marker its steps actually carry.** The third column already existed and was **stale** (it called the webview/eclipse/roadmap/sidecar rows "still open — every step unticked" while 5.1 and 7.1–7.10 are `[x]`, the merge-java row "partly done" while 4.1–4.4 are `[x]`, and gave the suggestion-channel row a status instead of a disposition while 4.14–4.17 are `[x]`), so the step corrected it rather than writing it. Every disposition was **read out of the § Progress table with the expected marker asserted before writing**; rows whose steps are open are "closed in step N" and rows whose steps are all done are "done". The roadmap row needed real work: **6 open items, 5 steps** — resolved item by item (FieldAnnotation → 6.1; the entity contract **and** the hierarchy rules → 6.3, one decision; divergence → 6.4; projection → 6.5; the API/core split explicitly deferred by its own text). Exactly **two** items had no step — the sidecar's "add more tools" and the agent's "lightweight hooks for IntelliJ and VS Code" — and got **step 9.1b** before the deletions, as the step requires. Three **stale-but-done** boxes were ticked with what closed them (sidecar indentation 7.1, agent OpenRewrite prototype 7.5, `todo.hipster-entity.md` merge 7.3). **Gate**: 14/14 rows annotated; `LINKS` green (280 files). |
| 9.1b | Two open items nothing scheduled                                                                | agent              | S    | `[x]` — **both struck with a reason, in the document that carried each.** The sidecar's "add more tools (toString/equals generator)" is struck because the sidecar owns the **host** half (LSP, `/jump`, `/applyEdit`) while a member-rewriting generator is a **tool** that belongs in the agent's `ToolRegistry` — so if wanted it is one new `ActionTool` modelled on `RenameMemberTool`, not work on this plan. The agent's "lightweight hooks for IntelliJ and VS Code" is struck because it is **delivered**: `webview/webview-jetbrains` and `webview/webview-vscode` exist (the extension's buffer edit is asserted by its own nine tests), and 7.2 now reaches the editors through `webviewd` and the sidecar. Both were **checked against the tree first** — the host modules exist, no toString/equals generator does — and § 3's annotation names this step in the past tense with the reasons. **Gate**: both documents show a struck item with its reason, and the four scheduled documents that carried open boxes (`jwa-sidecar`, `jcodebuddy-agent`, `todo.hipster-entity`, `todo.java_watch2`) are at **zero** open boxes; `LINKS` green. |
| 9.2  | Archive the superseded plans                                                                    | agent              | S    | `[x]`                                                                                       |
| 9.3  | Remove local scratch (`.kilo` plans, worktree, stray files)                                     | agent              | S    | `[x]`                                                                                       |
| 9.4  | Retire the per-plan open lists                                                                  | agent              | S    | `[x]` — **the absorbed lists are records now.** `todo.hipster-entity.md` was reduced to a closed record (its "open, and nothing has started it" prose about the merge had outlived step 7.3, and the proxy sub-item is ticked) and `todo.java_watch2.md` keeps its 7.6 reasons with the live schedule named; both are **kept rather than deleted** so links resolve. The sidecar's "Future Refinement" heading and status line now say **resolved** (7.1 and 9.1b), with the item bodies kept as the record of the decision. The roadmap tracker is **annotated per row** — nothing could be ticked (all five work rows map to 6.1/6.3 twice/6.4/6.5 and the sixth is dropped by decision in its own words). The jetbrains plan needed **nothing**: it already carries an Implementation record and no open boxes. **Gate verified by a repository-wide scan**: eight documents still hold unchecked boxes and every one is a **reader instruction** (how to add a resolver, what a fixture must contain, what a page must do, the field-dispatch and ordinal-array guides) or **historical-with-a-banner** (`plans/rewrite-migration/`, whose own banner says those boxes stay unchecked) — none lists project work this plan does not account for. `LINKS` green. |
| 9.5  | Full sweep (gate + links + examples)                                                            | agent              | S    | `[x]` — **all four checks green in one sweep.** `GATE` (`bun scripts/mvn-jdk25.js`, whole reactor) **BUILD SUCCESS** — a fully **cached** run (every module under 0.5 s), which is the correct verdict here rather than a lucky one: measured from `.mvn/maven-build-cache-config.xml`, the global includes are `scripts`, `webview/conformance`, the config, `docs`, `merge-java/docs` and the two `AGENTS.md`, so `plans/`, the todo files and the plan documents this round moved and retired **are not inputs to any module** — what the run proves is that no module input changed, and the last **executed** full gate (round 56, `MetadataCliTest` inside it) is green with nothing having touched an input since. `LINKS`: `check-repo-links.mjs` **280 files, all resolve**; `webview/check-links.mjs` **31 files / 242 links, all resolve**. `EXAMPLES`: `npm run check:examples` **exit 0**, every marker matching. **Two findings recorded for the final step**: the cache config's comment still calls the cache-validation step "9.7" while the plan calls it **9.8**, and **9.8 has no § Progress row at all** (so the one step reserved as the final validation could never be ticked). |
| 9.6  | Close the books                                                                                 | agent              | S    | `[x]` — **items 1–3 re-done for the table as it stands; item 4 still deferred with its named trigger.** § Progress is ticked from **dated records in each step's own body**: four rows (**3.4–3.7**) said `[TBD] — waits on …` while their bodies read **"Done 2026-10-03"** and named the shape they had demanded, so they are ticked, and 3.8, 3.9 and 3.11 stay `[TBD]` — now accurately rather than by inheritance. **Step 9.8 had no row at all**: a § 9.8 section with no line in this table, so the step reserved as the final validation could never be ticked; it has one now, found by running 9.5. `plans/README.md` carries the same numbers as the plan (**74 of 92** done, 18 not), and both are **derived from the table and written back** — an earlier attempt in this step logged four ticks it never wrote into the file, which is precisely the failure this step exists for, so it is recorded rather than tidied away. **The plan stays** where it is: 18 steps are not done, so it is a live document, and that reason is written into it. **Item 4** stays deferred because the "scheduled in `plans/unified-plan.md`" pointers are the index *to* a live schedule; the trigger to remove them is the plan being closed or archived. |
| 9.7  | Webview navigation from generated markdown: every location syntax, and markdown rendering       | agent              | M    | `[x]`                                                                                       |
| 9.8  | Validate what the build cache checksums, and add what it misses                                 | agent              | M    | `[x]` — **the include list was under-covering, and the fix is now a check.** Verified: a global include resolves against **each module's own basedir**, so the old bare + `../../` pair covered the root and the 24 depth-2 modules while **`project-automation` (depth 1) and the three depth-3 modules resolved NOTHING** — blind to every `scripts/` change, the F-47 failure. The list now carries a form per depth **that exists** (24 entries, depths 0–3) and one dead entry (`../../merge-java/AGENTS.md`, a file not in the tree) is deleted. `${maven.multiModuleProjectDirectory}` would be depth-free but is unverified here, so **a new depth is caught by `bun scripts/check-cache-inputs.js`** — the artifact that answers "if I change file X, which modules rebuild?" and fails on a dead entry or a blind module. Item 4: both sibling-compiling harnesses accept `target/*.jar` as well as `target/classes`, which makes the four-module cache exclusion a **removal candidate** (recorded in the config with the experiment that decides it, deliberately unrun). Item 5: the verdict is in `doc/AGENTS.md` and the config header's stale step reference and unverified-list claim are gone. **Measured**: checker 30 modules / 24 includes / 0 dead / 0 blind across depths 0–3; **recorded gate passes with the cache on** (a real full rebuild, tooling 14:19); `LINKS` green. |
