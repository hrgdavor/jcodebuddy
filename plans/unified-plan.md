# Unified plan — every open item in the repository, in one ordered schedule

**Status: 2026-10-10 — live, and slimmed on 2026-10-09.** The **91 finished step records** moved to
`archive/plans/unified-plan-2026-10.md` ([the archive](../archive/plans/unified-plan-2026-10.md)): this file
answers what is left, and a plan that carries its own history makes every reader walk past work already done.
Each phase names the steps of it that were archived, so nothing is silently missing.

**What is left: 11 step ids, and only the editor ones need a person.** **3.11** was `[TBD]` because it waited on
3.8, then a **design step**: on 2026-10-10 its design landed as
[DEC-049](../doc-hipster-entity/architecture/decisions/DEC-049.md), the maintainer **accepted** it, and both
steps it scheduled were **done the same day** — **3.11a** (the graph page joins the class index and navigates)
and **3.11b** (the live-application entry point, documented and verified). What DEC-049 still leaves open is its
two *optional* steps, **3.11c** (the generator emitting a bean FQN, only if beans should be clickable) and
**3.11d** (wiring the page build into the recorded pass). The rest: **5.3** (the ACP spike, a person), **8.1–8.5**
(observations on a real editor), and **6.8, 6.9 and 6.10** — the remainders steps 6.1, 6.2 and 6.4 left behind
while being closed as steps. A step is ticked from a dated `Done` record in its own body, never from a
"**Done when:**" criterion.

This file exists because the repository had several plans, and the useful question — "what is left?" — had no
single answer: every open item in them was already planned but not done. It replaces the "which plan is still
open?" archaeology across `plans/`, `webview/`, `merge-java/`, `doc-hipster-entity/` and `.kilo/plans/`: each of
those documents keeps its own history, and every genuinely open item in them appears here exactly once, as a
numbered, gated step, in the order it should be done.

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
| the `.kilo` hipster-ioc-integration plan, [`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md), and [DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md) | steps 3.0a–3.0k (the one metadata engine in `jcodebuddy-core`, then moving this generator onto it **as one consumer**); steps 3.1–3.3 as a **prototype**; steps 3.4–3.11 were `[TBD]` until the shape is decided | **closed in steps 3.4–3.9 and 3.11** — 3.0a–3.0t, 3.1–3.10 are `[x]`; 3.11's design is written (DEC-049) with 3.11a/3.11b scheduled, so this row's last item is a maintainer's acceptance rather than an unstarted design |
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

**Finished in this phase**, with its records in [the 2026-10 archive](../archive/plans/unified-plan-2026-10.md): 0.1, 0.2.

## 5. Phase 1 — close the gaps that already have a decision

**Finished in this phase**, with its records in [the 2026-10 archive](../archive/plans/unified-plan-2026-10.md): 1.1, 1.2, 1.3, 1.4.

## 6. Phase 2 — metadata-arena: make it tested and measured

**Finished in this phase**, with its records in [the 2026-10 archive](../archive/plans/unified-plan-2026-10.md): 2.1, 2.2, 2.3.

## 7. Phase 3 — hipster-ioc: **PROTOTYPING** — a project-wide generator that consumes metadata

**Finished in this phase**, with its records in [the 2026-10 archive](../archive/plans/unified-plan-2026-10.md): 3.0a, 3.0f, 3.0g, 3.0h, 3.0i, 3.0j, 3.0k, 3.0l, 3.0m, 3.0n, 3.0o, 3.0p, 3.0q, 3.0r, 3.0s, 3.0t, 3.0b, 3.0c, 3.0d, 3.0e, 3.0u, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10.

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
> 3.1–3.3 are the prototype already delivered; 3.4–3.10 are delivered, and **3.11 is the one `[TBD]` left**: its
> design is now written ([DEC-049](../doc-hipster-entity/architecture/decisions/DEC-049.md), `Proposed`) and its
> implementation is scheduled as 3.11a/3.11b, which are ordinary steps — so no `[TBD]` row remains in this phase
> and the shape question the marker was waiting for has become a maintainer's acceptance rather than a blocker.
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

### 3.11 — Design: the graph page navigates to source over BOTH integration paths
**Who:** agent writes, maintainer decides · **Size:** M — **a DESIGN step: it produces a decision and
scheduled implementation steps, and no application code**

**Status 2026-10-10 — the design is written and accepted, in
[DEC-049](../doc-hipster-entity/architecture/decisions/DEC-049.md)
(`Accepted`); its implementation is scheduled as [3.11a](#311a--the-graph-page-joins-the-class-index-and-navigates)
and [3.11b](#311b--the-outside-the-browser-entry-point-is-documented-and-verified).** The step's blocker — 3.8's
page — is done, and the exploration of 2026-10-09 established that **neither integration path has to be built**:
the webview suite already serves pages, already navigates, and already publishes the port and token a live
application needs. So the design's whole job is to make the graph page **join** what exists, and to write down the
one entry point nobody could find.

**The maintainer's requirement, which decided the design (2026-10-09): both integration paths stay open** — the
page must be navigable **served over HTTP** *and* **inside an IDE host**, because a **live application** asks an
editor to open a location while a person browsing inside the editor needs the other. Neither path may be closed,
and the reuse-vs-new answer is **reuse**: a module-owned server would be a second way for one page to be served
and a second bridge to keep in step, and the host contract is frozen and versioned precisely so a page does not
learn which host it is in.

**The three questions, answered — the full reasoning and the alternatives are in DEC-049:**

1. **Where the source positions come from, and how a failure is reported.** The page's build
   ([`build.js`](../hipster-ioc/hipster-ioc-graph/src/build.js)) resolves every context, implementation and bean
   FQN against **`../index/classes.json`** — the module's class index (DEC-029), one directory up from the page —
   and extends the **inlined** model with `{path, line}`. Build time, not view time: a `file://` page cannot fetch
   a sibling (measured in 3.8), so a runtime join would close the standalone path the maintainer requires to stay
   open. An FQN the index does not know goes into a **`locationless`** report beside `model.js`'s existing
   `missing` list — never dropped, never aimed at line 1 (DEC-040 D4; DEC-016's "never a guessed position").
   **Bean types are settled as the page's rule**: the join resolves an **FQN** only. `contexts.json` records the
   bean `mapper` with the *simple* type `ObjectMapper`, and the index is keyed by FQN, so it is **reported** as
   not-in-index rather than matched by simple name — a guess among rows is a wrong jump that looks like a working
   link. If the maintainer wants beans navigable, the fix is upstream and is scheduled as the optional **3.11c**:
   the generator emits the bean's FQN in `contexts.json`. Verified on the real files, not assumed:
   `hr.hrg.hipster.ioc.test.CtxMain` → `CtxMain.java` **line 7**, `…CtxMainImpl` → `CtxMainImpl.java` **line 12**,
   and the `ObjectMapper` bean **not resolvable**.
2. **Both integration paths stay open, and both already exist — the design keeps them that way.**
   - **In a host**: the page is served through `/page/`, the bridge is appended
     ([`InjectedBridge`](../webview/core/webview-core/src/main/java/hr/hrg/webview/core/InjectedBridge.java)), and
     the page calls the frozen **`window.openFile(path, line, column)`** after **feature-detecting** it. The
     transport is the host's business — an image beacon to `/open` for `webviewd`, a `JBCefJSQuery` call for
     JetBrains, `postMessage` for a mirroring host. Opened as a `file://` document there is no bridge, so the page
     **says navigation needs a host** instead of failing: the page stays usable standalone (root `AGENTS.md` § 2).
   - **From outside the browser**: a live application calls **`GET /open?filePath=…&line=…&column=…`** directly
     (`WebviewServer`) — no custom agent, no new API. The statuses a caller must handle are the frozen ones: 200,
     400 (no path), **403** (outside the project), 404 (a location the file does not have), 405, **429**
     (rate-limited — `Navigator` allows 20 per 20 s, and that is part of the contract rather than an obstacle).
     **The discovery story, which is what "the app knows the port" was missing:**
     1. read `<project>/.jcodebuddy/webview/host.json` — the descriptor every host publishes, carrying the
        **port** it actually bound and a **`tokenPath`**, and **never the token itself** (DEC-032/033: host state,
        per checkout, not configuration);
     2. probe that port with a credential-free **`GET /health`**, and accept it only if `plugin` **and** `port`
        are present and `project` names the directory being served — **the file outlives the process that wrote
        it**, so the probe, not the file, answers "is a host there";
     3. fall back to the project's committed default (`<project>/.jcodebuddy/conf/webview.json`, optional) when
        the descriptor is absent, stale or another project's;
     4. read the secret from the file at `tokenPath` **only** for a route that requires it. A host may publish an
        empty `tokenPath` (VS Code keeps its token in editor settings), and then a live integration must be told
        the secret — stated rather than papered over.
   The **fragment** spelling of step 9.7 (a member or region name instead of a line) needs no new argument: it
   rides inside `filePath`, which `Navigator.open` already splits because the frozen contract calls that a path.
   The graph's join yields a **line**, so the page passes the three-argument form the contract has always had and
   **`bridgeVersion` stays 1**.
3. **What "editor-agnostic" is accepted as — a test, not a claim.** The same unmodified page navigates in a
   **`webviewd`**-served context and in a **JetBrains** tool window, with the difference confined to the injected
   transport; the page still renders and reports nav-needs-a-host at `file://`; and a call to **`/open` from
   outside the browser**, with port and token resolved by the order above, lands on the file and line asked for. A
   page that works in one IDE and not another means this answer was wrong.

**Gate:** the design is written into the plan and a decision record, with the three answers, the **port/token
discovery story for a live application**, and the acceptance test; the reuse-vs-new host answer is recorded with
its reason; `InjectedBridge`'s `POST /api/v1/open` javadoc is corrected; the implementation work is scheduled as
its own steps with gates; and **no application code is written in this step**.

**Also in scope, because it is the same confusion**: the ROADMAP's status line said the browsable page "does not
exist" three steps after 3.8 built it — corrected on 2026-10-09, together with the paragraph that called a
module-owned server "what is missing".

**Done when:** the maintainer has accepted or amended the design, and the implementation steps exist. Dropping the
whole item instead remains a legitimate outcome — `[-]` with a reason is a real answer.

### 3.11a — The graph page joins the class index and navigates
**Who:** agent · **Size:** M

1. **Join in the build.** [`build.js`](../hipster-ioc/hipster-ioc-graph/src/build.js) reads
   `../index/classes.json` beside `contexts.json` and passes a `{fqn: {path, line}}` map into the inlined model.
   A missing or older index **fails the build with a message naming `bun scripts/ioc-gen.js`** rather than
   producing a page whose lines have moved — a stale index is worse than no page.
2. **Model.** `src/model.js` gains `location` per context and implementation, and a **`locationless`** list for
   every FQN it could not resolve — including the bean types, today `ObjectMapper`. It keeps `missing` for a
   dependency naming a context that is not in the file.
3. **Navigation.** A block or bean whose FQN resolved calls `window.openFile(path, line, 1)` when
   `typeof window.openFile === 'function'`, and otherwise shows the location as text with a note that navigation
   needs a host. Nothing is fetched at view time and the page stays one self-contained file.
4. **Tests** in `src/graph.test.js`: the join against the real index (both FQNs resolve, the simple bean name is
   in `locationless`), a document with no `window.openFile` still renders, and — when Chrome is present — a click
   on a located block calls the injected stub with the joined path and line.

**Gate:** `GATE` green (it includes `hipster-ioc-tooling`/`hipster-ioc-test`);
`bun scripts/ioc-gen.js` and the graph build are green, with `build.js --check` a no-op when nothing changed;
`bun run hipster-ioc/hipster-ioc-graph/src/graph.test.js` green; and the `file://` page renders with navigation
reported unavailable rather than throwing.

**Done 2026-10-10 — the page joins the index and navigates, and the join is reported rather than guessed.**
What landed, in the shape this step specified:

- **The join is a module of its own** ([`src/locations.js`](../hipster-ioc/hipster-ioc-graph/src/locations.js)):
  `classRows` refuses a class-index format it does not know rather than half-reading it, `projectPath` prefixes
  the index's **module-relative** path with the module's directory (a host resolves against the **project**, so
  the unprefixed path would be refused by the jail), and `joinLocations` writes `location` onto each context,
  `implementationLocation`, and `location` onto each bean — or names it in **`locationless`**.
- **The build does the join, and refuses to guess the root**:
  [`build.js`](../hipster-ioc/hipster-ioc-graph/src/build.js) reads `.jcodebuddy/index/classes.json` beside
  `contexts.json`, takes the project root from `--project=<dir>` (or `JCODEBUDDY_PROJECT_DIR`), and **stops**
  with a message when it has neither — a joined path the host cannot resolve is worse than no join. It prints
  what it could not place, and `--check` stays a no-op when nothing changed.
- **The page carries the locations and calls the frozen contract**:
  [`model.js`](../hipster-ioc/hipster-ioc-graph/src/model.js) carries `location`, `implementationLocation` and
  `locationless` through; [`navigate.js`](../hipster-ioc/hipster-ioc-graph/src/navigate.js) is the one call
  (`window.openFile(path, line, 1)`, the contract's own column default) and the inert answer when there is no
  bridge; [`blocks.js`](../hipster-ioc/hipster-ioc-graph/src/blocks.js) and
  [`host.jsx`](../hipster-ioc/hipster-ioc-graph/src/host.jsx) put the target on the element as `data-location`,
  and [`main.js`](../hipster-ioc/hipster-ioc-graph/src/main.js) navigates from **one delegated listener** —
  which survives nodditor re-creating blocks through its factories on undo/redo, where a closure would not.
- **The page reports the case DEC-049 decision 4 named**: the example's bean `mapper` has the *simple* type
  `ObjectMapper`, the index is keyed by FQN, and the page says `ObjectMapper (bean mapper of
  hr.hrg.hipster.ioc.test.CtxMain)` in its status line instead of aiming at a guessed line. The verified join
  is `CtxMain` → `…/CtxMain.java`:**7** and `CtxMainImpl` → `…/CtxMainImpl.java`:**12**.
- **Evidence, and what is not claimed**: `locations.test.js` **15 passed / 0 failed** (including that each
  joined path exists on disk and its line carries the declaration) and `graph.test.js` **14 passed / 0 failed**,
  whose last two assertions run the built page in headless Chrome — the `file://` page renders with
  `class="graph-app"` and says it is **standalone**, and with the host's bridge stubbed in, a click reaches
  `window.openFile` with `hipster-ioc/hipster-ioc-test/src/test/java/hr/hrg/hipster/ioc/test/CtxMain.java`,
  line **7**, column **1**. The **recorded gate is green**: `bun scripts/mvn-jdk25.js` reports `BUILD SUCCESS`
  over the whole recorded module set (**8:09** on the cache miss, exit 0, and the cached re-run **1.96 s**,
  exit 0), with `IocContextGeneratorTest`'s **19 tests, 0 failures** — the check that the generator's own
  output is unchanged by a page-side join. (One stale report elsewhere is not this run's:
  `webview-eclipse`'s `BuiltJarContentTest.txt` is dated **2026-10-06** and that module is outside the
  recorded gate; the cache restore leaves the miss's own reports in place rather than rewriting them, which is
  why the tooling module's report is the miss's.)
- **Not done here, on purpose**: 3.11b (the live-application route documentation and its runnable check) and
  3.11c (the generator emitting a bean FQN, which is what would make the bean navigable).

### 3.11b — The outside-the-browser entry point is documented and verified
**Who:** agent · **Size:** S

The live-app path existed and was undocumented — `InjectedBridge` even sent a reader to a route that does not
exist (fixed 2026-10-09). This step makes it findable and keeps it true:

1. Document the discovery order of answer 2 in
   [`webview/doc/webview-host-api.md`](../webview/doc/webview-host-api.md) (the descriptor, the `/health` probe,
   the committed default, the `tokenPath` file and the empty-`tokenPath` case) and point at it from
   [`hipster-ioc-graph/README.md`](../hipster-ioc/hipster-ioc-graph/README.md) — the page's reader is the
   integrator.
2. A runnable check (Bun, per `AGENTS.md` § 2) that starts `webviewd` on an ephemeral port, resolves the port and
   token the way the document says, calls `GET /open` for a real file and line, and asserts the frozen statuses —
   including 403 for a path outside the project and 429 once the rate limit is spent.

**Gate:** the check is green in the recorded run and its output is in the commit; `LINKS` green; and the two
documents name `GET /open` as the entry point — with **no document calling `POST /api/v1/open` an existing
route**. (That string does appear elsewhere as a *plan*: `PLAN-webview-suite.md` folds `/jump` into a route of
that name, and `webview-host-api.md` § 8 repeats the plan. The defect this step's predecessor fixed was the
javadoc **claiming** the route; a recorded intention is not a claim about the code, and the plan's own words are
quoted rather than scrubbed.)

**Done 2026-10-10 — the entry point is documented, and its statuses are asserted against live hosts.**

- **The document**: [`webview/doc/webview-host-api.md`](../webview/doc/webview-host-api.md) gains **§ 4c, "A
  caller that is not the browser — how it finds the port and the token"** — the four-step discovery order
  (descriptor → credential-free `/health` probe → the optional committed default → the file at `tokenPath`), the
  table of statuses a caller must handle (`200`, `400`, `403`, `404`, `405`, `429` with the 20-per-20 s limit
  shared with the write routes), and two measured facts a caller would otherwise discover the hard way: the token
  file **exists only when the host generated the secret** (a `--token` run leaves `tokenPath` naming a file that
  is not there), and a stopped host **leaves its descriptor** (which is why the probe, not the file, answers "is
  a host there"). The page's reader is pointed at it from
  [`hipster-ioc-graph/README.md`](../hipster-ioc/hipster-ioc-graph/README.md).
- **The check**: [`webview/tools/check-open-route.js`](../webview/tools/check-open-route.js) — **18 assertions,
  0 failed, ~1 s**. It starts real `webviewd` processes on throwaway projects, resolves the port and token by
  the order above (**no `--token`** is passed, precisely because a caller that is told the secret has discovered
  nothing), and asserts: the descriptor names the bound port, the project, a `tokenPath`, and never the token;
  the token file holds the generated secret; `/health` confirms identity without credentials; `400` for no
  `filePath`; `403` for a path outside the project; `403` for no token and for a wrong token; `404` on a headless
  host rather than a false success (why a headless host does **not** answer `200` is in the document's table);
  `429` once the limit is spent, naming the limit and its window; and, with a Zed CLI on the PATH, the manifest's
  `open` capability with `lineNavigation: "file-only"` and a real **`200`** for a file in the project.
- **What could not be asserted everywhere**: the `200` needs an editor adapter, so that block runs only when the
  Zed CLI is found on the PATH — detected the way the Java adapter detects it — and prints
  `note no Zed CLI on the PATH — the 200 assertion was skipped, not passed` otherwise. Everything else is
  decided by the token check, the path jail and the rate limiter, which are in `webview-core` and need no editor.
- **Evidence**: the check green as above; `LINKS` green
  (`check-repo-links.mjs`: 285 files, 1901 links). The Maven gate is unaffected — this step changes no Java.
- **A side effect of the check, by design**: the host state it creates lives in the *throwaway* project, and the
  script removes any `host.json`/`token` a previous run left in this repository, saying so when it does. A check
  may not leave a port behind in a real checkout.

### 3.11c — Optional: bean types navigate (the generator emits an FQN)
**Who:** agent, only if the maintainer asks · **Size:** S

`contexts.json` records a bean's type as the simple name written in the context's own source (`ObjectMapper`),
which the class index cannot resolve. The projection fix is upstream: `IocGeneration` resolves the simple name
through the context file's own imports and writes the **FQN**, so the page's existing join covers beans with no
page-side rule. Taken only if beans should be clickable; without it the page reports them as `locationless`,
which is honest.

**Gate:** `GATE` green; `contexts.json` carries an FQN for the example's bean; the graph page shows it located; and
`ExampleRegenerationTest`/the generator's own tests are a byte-identical no-op where they were.

### 3.11d — The graph build is part of the recorded pass (optional, recorded so it is not forgotten)
**Who:** agent · **Size:** S

`graph.html` is written by `bun run hipster-ioc/hipster-ioc-graph/src/build.js`, and nothing in the recorded gate
runs it, so a change to the page's sources does not fail anything until someone builds by hand. Either wire the
build into `scripts/ioc-gen.js` (the pass that already writes `contexts.json`, with its `--check` mode as the
staleness guard) or record why it stays manual. This is a Bun step, never a Maven plugin run inside the gate.

**Gate:** one command regenerates and verifies the page, or the reason it stays manual is in the file that a
reader of the build would open.

**Done 2026-10-10 — the design settled, scheduled, and accepted; no application code, which is what this step was.**
The step's `[TBD]` was a design it did not have, and it now has one:

- **The design is [DEC-049](../doc-hipster-entity/architecture/decisions/DEC-049.md), and the maintainer accepted
  it on 2026-10-10** — the exact condition this step's "Done when" named, together with "the implementation step
  exists", which it does (3.11a and 3.11b below; 3.11c/3.11d recorded as optional so they are not forgotten).
  Nothing in the step was amended on the way in, so the record is the design rather than a summary of one.
- **The three questions are answered, not deferred.** Positions are joined **at build time** from
  `../index/classes.json` (a `file://` page cannot fetch), verified on the real files — `CtxMain` line 7,
  `CtxMainImpl` line 12 — with an unresolved FQN **reported** in `locationless`; both integration paths are
  **reused** (the frozen `window.openFile` contract in a host, `GET /open` from outside the browser) with the
  **port/token discovery story** written out — descriptor → `/health` probe → committed default → `tokenPath` —
  and "editor-agnostic" stated as an acceptance test rather than a claim.
- **The gate's other items**: the `POST /api/v1/open` javadoc correction landed on 2026-10-09
  (`InjectedBridge`), the ROADMAP's stale "does not exist" paragraph was corrected with it, and **no application
  code was written in this step** — 3.11a is where the page navigates for the first time.
- **What this record does not claim.** `Accepted` is about the design; the record's own acceptance table names
  which step observes each criterion (1, 2 and 4 at 3.11a; 3 at 3.11b; 5 already true and kept true by 3.11b), so
  nothing here says the graph page navigates today.
- **Evidence for the docs half**: `check-repo-links.mjs` green (285 files, 1890 links) and
  `webview/check-links.mjs` green (240 links), with `md-fix-tables` run over every changed document.

---

## 8. Phase 4 — merge-java: Phase 9's residue and Phase 13

**Finished in this phase**, with its records in [the 2026-10 archive](../archive/plans/unified-plan-2026-10.md): 4.1, 4.2, 4.3, 4.4, 4.5, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12, 4.13, 4.14, 4.15, 4.16, 4.17, 4.18, 4.19, 4.20, 4.21, 4.22.

## 9. Phase 5 — webview: close the suite

**Finished in this phase**, with its records in [the 2026-10 archive](../archive/plans/unified-plan-2026-10.md): 4.6, 5.1, 5.2, 5.4.

> **Every UI step in this phase builds on `jsx6`** (rule § 2.9, [`AGENTS.md` § 2](../AGENTS.md)): no UI is
> written against a remembered version of the library, and the checkout's own `AGENTS.md` is the guidance
> to follow. **Diagrams and relations use `jsx6`/`nodditor`** (DEC-027's 2026-10-01 amendment). Step 7.9
> sets the checkout up and step 7.10 records what the libraries can and cannot do — a capability they lack
> is **reported** there rather than worked around in a page. A webview step that finds the checkout missing
> should set it up rather than reach for another library.

### 5.3 — The ACP go/no-go spike (human)
**Who:** human (Zed 1.21, from a normal shell) · **Size:** S

Phase 5's spike "ends in a written go/no-go" and no such document exists. Run it as the plan describes
(`webviewd --acp` registered as a custom agent, `initialize` / `session/new` / `session/prompt`, one tool
calling the same command layer as `/api/v1`), then write the verdict — including "no, and here is why" —
into the plan's Phase 5 record.

**Gate:** the written verdict exists and the plan links to it.

**Done when:** ACP is either scheduled as its own plan or dropped with a reason.

## 10. Phase 6 — hipster-entity roadmap rows

**Finished in this phase**, with its records in [the 2026-10 archive](../archive/plans/unified-plan-2026-10.md): 6.3, 6.5, 6.6, 6.7.

> 6.1, 6.2 and 6.4 were **closed as plan steps by their own `Done` records while each still had named work
> outstanding**, which reading those records against the tree found on 2026-10-09. The findings and the
> maintainer's answers are in
> [`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) § 6; the readings are in the
> [archive](../archive/plans/unified-plan-2026-10.md) under each step's id. What remains of them is 6.8, 6.9 and
> 6.10 below, and each was a real remainder rather than a new wish.

Each step here is a row of
[`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) § 1 or a `Proposed`
row of its § 3. Close them in the tracker in the same commit.

### 6.8 — Nested, collection and polymorphic patch application
**Who:** agent · **Size:** L

DEC-048 § 2 specifies the applier, § 5 left the nested case unanswered, and its **2026-10-09 amendment settles
it**: a generated builder implements its view's `Write`, so a child's writer is reachable and the applier recurses
through the child's own applier. **Read that amendment before starting** — it is the decision this step
implements, and it names what is deliberately still open.

What to build, in this order, because each depends on the one before:

1. **Every generated `<View>Builder` implements `<View>.Write`.** This is the route from a view to a writer, and
   it is an emitter change to every builder, not an addition beside them. `PersonSummaryBuilder` is the example's
   first case.
2. **Nested recursion**: a path continuing past this view's field goes to `<Child>PatchApplier` with the child's
   `Write` obtained through the builder.
3. **Collection element writes** through the element's own applier, with an **index that no longer exists failing
   with a named finding** — never a silent skip, and never a guessed position (DEC-016).
4. **The polymorphic dispatcher on the root family**: read the value's discriminator through the root's
   `discriminatorField()`, match it against each `permittedSubtypes()` entry's `META.discriminatorValue()`, and
   call the matching concrete applier. The example's `paymentMethod` family is the case; `PaymentMethodController`
   is the same dispatch hand-written today.
5. **Only views reachable from a patch target** get an applier and a dispatcher.

**Gate:** `GATE` green; a nested patch, a collection-element patch and a patch into a polymorphic root all apply
in a test; a stale collection index produces the named finding rather than a partial patch reported as success;
and a reader with only the committed sources can follow every one of those operations from the applier to the
field it sets (AGENTS.md § 1).

**Done when:** the emitter change is in the example's committed sources, `ExampleRegenerationTest` is a
byte-identical no-op again, and the roadmap row for 6.2's nested half closes with this step's record.

### 6.9 — The converter manifest, generated and committed
**Who:** agent · **Size:** M

Step 6.4's only remainder, and its gate named it: a manifest of resolved converter pairs, **generated and
committed**. The decision is that it is **machinery output, byte-reproducible** — the pass writes it, and a
committed copy that differs from what a pass would write is a failure the way a stale committed enum is, not a
report that quietly drifts.

- **Source of truth**: `TypeDivergence` (`hipster-entity-tooling`'s `meta`), which the pass already collects via
  `EntityMetadataGenerator.typeDivergences()` and clears per pass, and which records **both** outcomes — a pair
  that needs a converter and one that was considered and needed nothing. `TypeDivergence.render()` is the
  intended manifest line; the question is the file, not the content.
- **Where it lands**: derived from the output root the pass already knows, under `.jcodebuddy/`. **DEC-026's
  rule and step 6.4's gate words point at different folders** — `conf/` is what must survive a clone, while
  `metadata/` is derived — so settle it in this step with the pass in front of you and record the answer where
  the next reader looks, rather than inferring it from either document.
- **Byte-reproducibility is the requirement, so it needs a test**: the same inputs produce the same bytes, and a
  hand-edited or stale manifest fails. Ordering must therefore be defined (by source type, then target, or by
  the mapping it came from) rather than left to iteration order.

**Gate:** `GATE` green; the manifest is written by a pass and committed; a test proves two passes over the same
inputs are byte-identical and that a changed pair changes the file; and the roadmap row closes with the record.

**Done when:** the manifest exists in the tree, its location and ordering are recorded, and 6.4's remainder is
named as closed in that step's record rather than inferred from the roadmap.

### 6.10 — The source → metadata JSON pass, so an annotation can reach a generated enum
**Who:** agent · **Size:** M

Step 6.1's blocked remainder, and the blocker is measured rather than suspected: `bun scripts/gen.js` runs the
CLI, the CLI builds its properties from the **metadata JSON** (`viewNode.path("properties")` →
`propNode.path("constraints")`), the JSON in the checkout is dated 2026-09-26, and the `generate(...)` API path
that the tests use parses **source**. So the two paths disagree about a freshly written annotation: source emits
the `annotations()` override, the CLI cannot, and `ExampleRegenerationTest` — the guard that "committed ==
generated" — is what made it visible. The example is unannotated because of it.

1. **Find and run the source → metadata JSON pass.** The CLI is the JSON → java half; the missing half writes
   that JSON from source. The step is not finished until the pass exists as a documented entry point (a Bun
   script, per AGENTS.md § 2) rather than a manual invocation.
2. **Annotate one example accessor**, regenerate, and confirm its enum constant carries the `annotations()`
   override.
3. **Confirm `ExampleRegenerationTest` is a byte-identical no-op again** — that is the check that both halves now
   agree, and it is the step's real acceptance test.
4. **The misleading diagnostic is in scope**: `gen.js` reported a `-pl` selector failure with a hint about
   `GeneratorPreflight` being older than the invocation, and its own log showed the preflight **ok** and the HTML
   index at "301 links verified". An error message that sends the reader after the wrong cause is a defect in
   the tool, and this step touches the pass.

**Gate:** `GATE` green; an example view's committed enum carries an annotation that a pass regenerates; the two
paths (CLI-from-JSON and API-from-source) produce the **same** enum for the same input, asserted by a test; and
`ExampleRegenerationTest` is green as a no-op.

**Done when:** the example carries the annotation, the pass that makes it reachable is a documented entry point,
and 6.1's gate — "`GATE` green, the generated example carries the annotations, and the tracker row says what
landed" — is met in full rather than in part.

## 11. Phase 7 — cross-cutting leftovers

**Finished in this phase**, with its records in [the 2026-10 archive](../archive/plans/unified-plan-2026-10.md): 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 7.10.
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
| 8.5 | Explore Zed editor integration         | agent proposes, maintainer observes  | step 8.5 below, and the decision it ends in                                     |

---

### 8.5 — Explore Zed editor integration
**Who:** agent proposes, maintainer observes in the editor · **Size:** M

Zed is the one editor in the suite that is neither JetBrains nor VS Code, and the only one with a
**first-class agent protocol** (ACP) rather than a plugin API — which is why step 5.3 spikes ACP and 8.4 runs
it. This step is the other half: what a *person using Zed* gets from JCodeBuddy once that spike answers.

Survey, then decide — in this order, because each answer constrains the next:

1. **What Zed can host today.** Zed renders Markdown previews and, through its extension API, can run a
   language server and a task. Establish which of our surfaces that covers: the generated report pages
   (`file://`, no host needed), `webviewd` over HTTP, and the LSP registration `webview-jetbrains` already
   does — which is the part most likely to port unchanged.
2. **What needs an extension, and what that costs.** A Zed extension is Rust compiled to WASM with a declared
   capability set; note which flows it can reach (open a page beside the code, jump to a conflict, run
   `metadata parse`) and which it cannot. Do not design around a capability Zed does not have.
3. **The ACP path, only once 5.3 has a verdict.** If the spike says ACP works, Zed is its natural first client
   and this step schedules the real integration; if it says no, record that here rather than re-litigating it,
   and the answer is the extension path or nothing.
4. **Our side of the boundary.** Whatever is chosen must keep the standalone rule (root `AGENTS.md` § 2): a
   page a person opens must work from `file://` with nothing running, and Zed is an enhancement on top of that.
   Any gap found in `jsx6`/`nodditor` is reported and fixed there, never worked around in the page.

**Gate:** a written survey with the four answers, and a decision — an integration plan with its own steps, or a
recorded "not now" with the reason. A survey that ends in "possible" without naming what would be built is not
done.

**Depends on:** 5.3 (the ACP verdict) for item 3. Items 1, 2 and 4 do not wait on it.

**Done when:** the decision is recorded and, if positive, its steps exist. This is the last editor in the suite
to get an answer, and the plan should not close with it unexamined.

## Progress

Legend: `[ ]` open · `[x]` done · `[~]` blocked (say why) · `[-]` dropped (say why) · `[TBD]` **waits on
a decision that is not made** — deliberately unscheduled, with the decision named (see Phase 3's banner;
it is not the same as `[~]`, which waits on something outside the plan, nor as `[ ]`, which is ready to
start)

| Step  | What                                                                                            | Who                         | Size | State                                                                                       |
| ----- | ----------------------------------------------------------------------------------------------- | --------------------------- | ---- | ------------------------------------------------------------------------------------------- |
| 3.11  | Design: graph-page source navigation over BOTH paths — served and in-host                       | agent writes, human decides | M    | `[x]` — **accepted 2026-10-10**: [DEC-049](../doc-hipster-entity/architecture/decisions/DEC-049.md) is `Accepted` and 3.11a/3.11b are its implementation (done record in the step) |
| 3.11a | The graph page joins the class index and navigates                                              | agent                       | M    | `[x]` — **done 2026-10-10** (`locations.js` + `navigate.js`, the join in `build.js`; 15 + 14 tests, a stubbed click reaching `window.openFile` at the joined line; done record in the step) |
| 3.11b | The outside-the-browser entry point is documented and verified                                  | agent                       | S    | `[x]` — **done 2026-10-10** (`webview-host-api.md` § 4c and `check-open-route.js`: 18 assertions, 0 failed; done record in the step) |
| 5.3   | ACP go/no-go spike                                                                              | human                       | S    | `[ ]`                                                                                       |
| 6.8   | Nested, collection and polymorphic patch application                                            | agent                       | L    | `[ ]` — implements DEC-048's 2026-10-09 amendment                                           |
| 6.9   | The converter manifest, generated and committed                                                 | agent                       | M    | `[ ]` — 6.4's only remainder                                                                |
| 6.10  | The source → metadata JSON pass, so an annotation can reach a generated enum                    | agent                       | M    | `[ ]` — 6.1's blocked remainder                                                             |
| 8.1   | JetBrains maintainer questions + IDE observations                                               | human                       | —    | `[ ]`                                                                                       |
| 8.2   | Eclipse observations, then Q2                                                                   | human                       | —    | `[ ]`                                                                                       |
| 8.3   | Agent IDE hooks                                                                                 | human decides               | S    | `[ ]` — **handshake DELIVERED 2026-10-10** (`WebviewHandshake`: the agent reads the port the host actually bound); what remains is the in-IDE walk, which needs a person |
| 8.4   | Zed ACP run                                                                                     | human                       | —    | `[ ]`                                                                                       |
| 8.5   | Explore Zed editor integration                                                                  | agent proposes              | M    | `[ ]`                                                                                       |

**8.3, what was built on 2026-10-10 — the handshake, because a reading of the code narrowed the step.**
The step's text said "lightweight IntelliJ/VS Code hooks"; reading the tree showed most of what that meant
already exists, and one load-bearing piece missing:

- **Existing**: the agent serves `POST /jump` and relays the sidecar's outcome (502 "sidecar unreachable" rather
  than a false success); the sidecar owns the editor's LSP connection and serves `GET /jump?uri=…&line=…`.
- **Missing, and it was a defect rather than a gap**: an IDE host binds the port it *can* — `jwa.sidecar.jumpPort`
  is a request, and when it is taken the next free port is used and **published** to
  `<project>/.jcodebuddy/webview/host.json`, keyed by project. The agent resolved the port from the system
  property alone, so a host on the next free port was **unreachable from the dashboard** — the exact case the
  handshake (§ 1.8's "Instance Discovery") exists for.

`WebviewHandshake` (agent, `server/`) now resolves it: a published descriptor that names **this project** and
comes from a **live pid** wins; anything else — no file, another project, a dead pid, truncated JSON — falls
back to the system property, else 7979. The token is read from the descriptor's `tokenPath` when that file
exists. The descriptor's format is read by name rather than modelled, so this module keeps **no dependency on
the webview stack** (it would have pulled gson in for a port number); the test pins the keys against the shape
`HostDescriptor` serialises, and says so where a reader would look.

**Measured**: agent module **30 tests, 0 failures** with the build cache disabled (9 new in
`WebviewHandshakeTest`; `RemoteJumpTest`'s 2 unchanged, which is what proves the property fallback still works).

**What remains here is the human half, and it needs a running editor**: with a host live on a *non-default*
port, open the agent dashboard and use a "jump to source" action, then confirm the caret lands. The expected
evidence is the port the dashboard used (the descriptor's, not 7979) and the editor's own tab. `describe()` and
the per-jump debug line exist to make "which port did it choose" answerable when it does not.

