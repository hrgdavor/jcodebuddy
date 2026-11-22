# `plans/`

Effort-sized plans and the repository's single schedule. Read the schedule first.

| File                                                                                       | What it is |
| ------------------------------------------------------------------------------------------ | ---------- |
| [`unified-plan.md`](unified-plan.md)                                                       | **The live schedule.** Every open item in the repository, one gated step per commit, ending with the plan-cleanup phase. If you are looking for "what is left to do", this is the file. |
| [`tbd.md`](tbd.md)                                                                         | **Deferred work, deliberately not scheduled.** ACP (5.3, 8.4 and 8.5's ACP item) plus the Eclipse observations (8.2) and the in-IDE handshake walk (8.3) were moved here on 2026-10-28; pick a step up by copying it back to the live schedule. |
| [`jcodebuddy-agent-integration-and-rename.md`](jcodebuddy-agent-integration-and-rename.md) | **Plan, 2026-11-22.** Keep the current name; make the agent the glue for generic/entity/IoC tools, add a single-writer proposal/apply coordinator, align with `CodeGenerator`/`hipster-entity-tooling`/`hipster-ioc-tooling`, give tools standalone UIs and optional integrated views, and rename only after those contracts are stable. |
| [`java-watch-to-fs-meta.md`](java-watch-to-fs-meta.md)                                     | **The migration off the frozen `java-watch*`.** W1–W6 are done (the six measured consumers are resolved, and the boundary is in the gate); **W7a–W7d are done, the full gate is green, and `WatcherBoundaryTest` enforces the boundary** (the Java `FsMetaClient` passes a two-client live test against `fsmeta daemon`, `WatchedRegeneration` and `ProjectWatcher` are daemon clients, `FileWatch` is retired, `webviewd` SSE is daemon-only, and `EntityBuildState` is a daemon-generation marker rather than a Java trio writer). There is no accepted writer exception. |
| [`hipster-entity-mvp.md`](hipster-entity-mvp.md)                                           | **The usable-MVP effort for `hipster-entity`** (E0–E9, with E10 as the post-MVP scale step): one-command consumer generation, `--check`, and a one-shot **incremental** pass driven by `fs-meta`. **Watch mode was out of scope for the original MVP batch; E11 landed it on the daemon (2026-11-07).** **E0–E11 are done (2026-11-05/07); the MVP is declared, parse reuse is in, watch mode is implemented, and the full gate is green (2026-11-07, 6:49 min, 617 tooling tests; 19 modules, including `jcodebuddy-compile`).** Design B (2026-11-07) replaced the entity build-state trio with a project-root daemon generation marker filtered to the module source root. |  |
| [`faster-java-tests.md`](faster-java-tests.md)                                             | **The measured test-cost plan, implemented 2026-11-05.** Baseline 542 tooling tests / 490.8 s; result **272.0 s serial / 1:49–1:55 four-fork**, top six **88.4 s**, fast lane **45.6 s**, all **542** tests still green. F0–F6 are done. Follow-up 2026-11-07: full gate **6:49 / 617 tooling tests** (19 modules, including the new compile module); the increase over 4:59 is added E5/E10/Design-B/E11 verification, not slower tests (see the plan's follow-up). |
| [`incremental-test-selection.md`](incremental-test-selection.md)                           | **The dependency-aware test layer.** A generated `tests.json` binds every test to the production/test classes it names (bytecode constant pool, cross-module classpath); `@Tag("unit")` is a ≤3-production-class contract; `test-affected` uses an `fs-meta` change set to rerun only affected unit tests, with no-op and safe fallbacks. **T0–T6 done 2026-11-06; full gate green 2026-11-07 (6:49 min, 617 tooling tests; 19 modules)**: `bun scripts/test-affected.js` is the default hipster-entity edit loop (no-op ~0.5 s, all-unit 94 tests in ~22 s) and the full suite is on demand. Watch mode landed in E11 and reuses the same state. |
| [`rewrite-migration/`](rewrite-migration/README.md)                                        | The **complete** JavaParser → OpenRewrite migration: the plan as it was written, the phase documents, and the verified delivery record. Historical; its README says what shipped. |
| [`enumset-overlap-jmh-plan.md`](enumset-overlap-jmh-plan.md)                               | The `EEnumSet` service-overlap benchmark: mission, method matrix, parity guards, and where the evidence landed. |
| [`inject-examples-feature-port.md`](inject-examples-feature-port.md)                       | The port of the documentation-injection mechanism to the published `@hrg/inject-examples` CLI. Status header at the top: the port happened. |
| [`hipster-sql-processor-port.md`](hipster-sql-processor-port.md)                           | **Draft plan (2026-11-17), implementation in progress.** New `hipster-sql/hipster-sql-tooling`: discovery/model, LF writer, Visitor/Immutable/Update/Builder/Meta + registry, `Meta.fromResultSet`/`visitResult`, optional Immutable Jackson serializer, `@Id` equality/primary, accessor annotation copying, Meta Jackson mapper/Mongo encode-decode, `genAnnotations`, metadata locations/options, `--classpath`/`--packages` and the simple-name oracle, 29 tests green. Core gained the extended metadata layer (`type-refs`, `type-uses`, `artifacts`, `packages`, `modules`, annotation defaults, richer `TypeDefinition`; 90 core tests). Remaining: annotated inheritance/package options, UI consumer coverage, watch phase. `sql` has only the approved annotation fix + its regression test; semantic (not byte) parity. |
| [`metadata-web-reports.md`](metadata-web-reports.md)                                       | **M1–M6 complete (2026-11-18).** Entity browser and IOC graph have live served pages; `graph.html` remains the static fallback. Catalog/atomic model revisions, targeted SSE, ETag, shared jsx6 and the headless gate are in place. M7 remains. User guide: [`doc/metadata-report-hosting.md`](../doc/metadata-report-hosting.md). |

**Conventions.** A plan states its own status in a banner at the top, with a date, and links to the record
that proves it. A plan whose work is done is kept as history or moved to [`../archive/`](../archive); a plan
whose work is open appears as steps in [`unified-plan.md`](unified-plan.md), and a step deliberately deferred
for later goes to [`tbd.md`](tbd.md). Anything an agent *runs* lives
in `scripts/` as Bun JavaScript — never here, and never as a shell script.

**Still live as of 2026-10-28 — slimmed on 2026-10-09, and 2 step ids are open here (4 more are in [`tbd.md`](tbd.md)).** The 91 finished step records now
live in [`../archive/plans/unified-plan-2026-10.md`](../archive/plans/unified-plan-2026-10.md), so
[`unified-plan.md`](unified-plan.md) answers one question — what is left — in ~700 lines instead of ~7,000.
**`hipster-entity-mvp.md` is a separate effort-sized plan** (E0–E9, plus the post-MVP E10) and is not
counted in the step ids above; like `java-watch-to-fs-meta.md`'s W7b, it carries its own gated steps rather
than duplicating them here.
**5.2 and 5.4 were closed on 2026-10-26** by the decisions their gates named — Q3 keeps the shared core, and Q2
accepts `dropins/` and declines Eclipse Phase 5 — after the slimming had counted them finished without a dated
record. **6.8, 6.9 and 6.10 were closed the same day, and 3.11c/3.11d followed**: the builders now implement their
view's `Write` so the patch applier recurses through child builders, the root family has a generated dispatcher, the
metadata JSON carries a field's constraints, the converter manifest is written by the pass and committed,
`bun scripts/ioc-gen.js --graph` regenerates and verifies the graph page, and the graph's bean types are FQNs.
What remains: **3.11 is closed, implementation and all** — its **design landed on 2026-10-10** as
[`DEC-049`](../doc-hipster-entity/architecture/decisions/DEC-049.md), the maintainer **accepted it the same day**,
and the two steps it scheduled were **done the same day**: **3.11a** (the graph page joins the class index and
navigates; `hipster-ioc-graph/src/locations.js` + `navigate.js`) and **3.11b** (the outside-the-browser entry
point, documented in `webview-host-api.md` § 4c and verified by `webview/tools/check-open-route.js`). What
DEC-049's two optional steps, **3.11c** (bean types as FQNs) and **3.11d** (the page build in the recorded pass),
were both closed on 2026-10-26. What remains here: **8.1** (JetBrains decisions answered 2026-10-28; the running-IDE
observations remain) and **8.5** (survey written; only its ACP item is open). **8.2 (Eclipse), 8.3 (the in-IDE walk),
5.3 and 8.4 (ACP) were moved to [`tbd.md`](tbd.md) on 2026-10-28 for later** — no interested Eclipse user, and the
other two need a person at an editor. The last agent remainder, **6.9** (the converter manifest), was closed on
2026-10-26.

Step 8.5 (Zed editor integration) was added the same day: Zed is the one editor in the suite that is neither
JetBrains nor VS Code, and the only one with a first-class agent protocol, so it gets its own survey and decision.

The plan therefore **stays here** rather than moving to [`../archive/`](../archive): step 9.6's own rule is that a schedule with open steps is a live document, applied to its own answer.
