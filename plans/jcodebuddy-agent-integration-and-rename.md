# JCodeBuddy Agent: tool integration and rename plan

Status: **plan, 2026-11-22.** The name **`jcodebuddy-agent` stays** until the rename gate at the end of
this plan. This document schedules the work that must happen first: turning the agent into the glue
between the JCodeBuddy engine, `hipster-entity`, `hipster-ioc` and standalone tool UIs, without two
writers or two implementations of the same generator.

The rename is deliberately last. A name chosen before the tool boundary and the entity/IoC
integration are settled would either be a name for the old watch agent or a name that has to change
again.

## 1. Baseline already in place

- The watcher uses **fs-meta** (`ProjectWatcher` → `FsMetaDaemonWatch`); there is no local
  `WatchService` and no `java-watch*` wiring.
- The dashboard is **jsx6** (`jcodebuddy-agent/web/`) and builds from the local `.jsx6` checkout.
- The agent publishes **`jcodebuddy-agent`** on `.jcodebuddy/ipc/` and shares `AgentMethods` with
  the host's `/api/tools` path.
- `.jcodebuddy/app/jcodebuddy-agent.html` is installed for WebView Explorer; the standalone
  CommandServer still serves the same page and `/rpc`.
- The agent runs headless; there is no terminal UI.
- Existing tests cover the watcher, methods, tool socket, batches, refusal tracking and handshake.
- Production Java is already **JavaParser-free**: the agent tools, `jwa-builder`, entity tooling and
  IoC tooling read OpenRewrite LSTs. Historical migration documents may still mention JavaParser;
  they are not production sources.

## 2. Goals and non-goals

### Goals

1. **One writer per file.** No two of agent, `hipster-entity`, `hipster-ioc`, project-automation or
   the incremental compiler may write the same source concurrently or from stale input.
2. **One implementation per generator.** Entity/IoC/generic builders must have a single source of
   truth, callable both from a build and from the agent's preview/review path.
3. **Agent as glue.** `jcodebuddy-agent` owns discovery, review, audit, apply/reject and the catalog
   UI; it does not own the generator bodies once they are split out.
4. **Tools are independently usable.** Every tool can be built, tested and opened as a standalone
   page without the agent host or WebView Explorer.
5. **Integration is explicit.** An integrated SPA is an opt-in project-automation choice; a project
   may omit a standalone page only when the integrated view exists.
6. **Source-visible output.** Generated Java remains committed/navigable, with cooperative blocks and
   no reflection/discovery hidden in registries.
7. **Rename-ready contracts.** Tool ids, page routes, config and package names get compatibility
   seams before the rename, not after it.

### Non-goals

- No rename in this plan.
- No automatic ingestion of every entity/IoC page into one SPA.
- No second watcher, second type parser or second audit format.
- No new UI framework; the design system remains jsx6.

## 3. Current overlap inventory

| Area                    | Agent today                                                                          | JCodeBuddy today                                                                                    | Risk                                                          |
| ----------------------- | ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| Generic Java generation | `jcodebuddy-tool-builder` / `jcodebuddy-tool-java`                                   | `CodeGenerator<T>` / `ProjectGenerator` SPI; `jwa-builder` processors                               | One tool API; generated text comes from the same processors   |
| Entity generation       | No direct entity generator; can run the `hipster-entity` tool                        | `hipster-entity-tooling`: metadata, view builders, tracking builders, records, validators, adapters | Agent can propose a change to a file an entity pass also owns |
| IoC generation          | `hipster-ioc` tool                                                                   | `hipster-ioc-tooling`: context/implementation generation                                            | Same file ownership race as entity                            |
| Watching                | `ProjectWatcher` on fs-meta                                                          | Incremental compiler `FsMetaVfsWatcher`; daemon stream                                              | Two subscribers are fine; two **writers** are not             |
| File application        | `WriteCoordinator` + batches + `ToolRefusals`                                        | CLI/build writes; cooperative codegen and merge semantics                                           | Stale apply, audit divergence, marker corruption              |
| Type/source analysis    | `ContextualAnalyzer` through `jwa-builder`                                           | `SourceReader` / `TreeQueries` / `TypeResolver` / class index                                       | Two parsers and two notions of the same type                  |
| UI                      | jsx6 agent page                                                                      | Entity browser, IoC graph, compiler console, ChatApp, report pages                                  | Repeated shell/navigation/forms; no tool catalog              |
| Audit/undo              | `.jcodebuddy/metadata/watch/audit`                                                   | JCodeBuddy checkpoint/merge/undo concepts                                                           | Reject cannot safely reverse a file another writer changed    |

## 4. Target architecture

```text
                    ┌──────────────────────────────┐
                    │        jcodebuddy-agent       │
                    │  discovery · review · audit   │
                    │  apply/reject · catalog UI    │
                    └──────────────┬───────────────┘
                                   │ Tool API
              ┌────────────────────┼────────────────────┐
              │                    │                    │
      ┌───────▼───────┐    ┌───────▼───────┐    ┌───────▼───────┐
      │ generic tools │    │ entity tools  │    │  ioc tools    │
      │ builder/...   │    │ hipster-entity│    │ hipster-ioc   │
      └───────┬───────┘    └───────┬───────┘    └───────┬───────┘
              └────────────────────┼────────────────────┘
                                   │ proposals only
                         ┌─────────▼──────────┐
                         │  WriteCoordinator  │
                         │ lock · hash · apply│
                         └─────────┬──────────┘
                                   │ atomic writes
                         ┌─────────▼──────────┐
                         │ committed sources  │
                         └────────────────────┘
```

### The two contracts

1. **Tool contract** — what a tool can do:
   `tool.info`, `tool.discover`, `tool.preview`, `tool.apply`, `tool.reject`, `tool.status`, plus
   optional events. `preview` returns file proposals with `beforeHash`, `afterContent` and a stable
   idempotency key. **A tool never writes a file directly.**
2. **UI contract** — how a tool presents itself:
   a standalone page + optional integrated view, described by a manifest under the tool's own
   directory. The agent catalog reads the manifest; project-automation decides which form to emit.

### The agent's role

- discover markers/routes via the project class index and fs-meta batches;
- select a tool and request a proposal;
- dedupe, queue, lock and apply/reject through the coordinator;
- present the catalog, pending actions, diffs and tool pages;
- expose the tool socket and the host page.

The agent no longer contains a second implementation of entity, IoC or generic Java generation.

## 5. Single-writer and race design

This is the core of the integration; the rest is plumbing.

### 5.1 One agent per project

`ToolEndpoint` already refuses a second live endpoint for the same tool/project. The coordinator
should make that the only writer instance; a second agent process reports the existing socket and
stays read-only (or exits), rather than generating in parallel.

### 5.2 Proposal, not write

Every tool returns:

```text
FileProposal {
  path, beforeHash, afterContent, changeType,
  toolId, toolVersion, anchor, idempotencyKey
}
```

`WriteCoordinator` checks `beforeHash` against the current file. If it differs, the proposal is
**stale**: show a conflict/diff, do not overwrite. On apply:

1. acquire a per-file lock (project lock under `.jcodebuddy/agent/locks/`);
2. re-check hash;
3. write a temp file and atomically move it;
4. record before/after hashes and the audit entry;
5. release.

Reject reverses only when the current hash equals the proposal's `afterHash`; otherwise it reports
that another writer owns the change.

### 5.3 Idempotency and deduplication

`idempotencyKey = hash(toolId, path, anchor, sourceHash, toolVersion, generatedBlockId)`.
The coordinator keeps pending keys and refuses a duplicate proposal while one is open. Applying the
same proposal twice is a no-op, not a second edit.

### 5.4 Own-write suppression

After apply, the coordinator records the resulting hash and a short write identity. An fs-meta batch
for that path/hash is **observed but not re-triggered** by the agent. A change from the editor, a
build or another tool still triggers normal discovery.

### 5.5 Per-file ordering

Batches are coalesced by path; a file has one queue. Entity/IoC build passes and the agent share the
same coordinator when they run in the same process; a separate build process takes the same lock and
uses the same stale check.

### 5.6 What the incremental compiler does

The compiler owns **compilation**, not source generation. It may read while a write is in flight, so
write atomicity is mandatory; it must not run a generator that writes a source file in parallel with
the agent. If the compiler ever does generate, it goes through `WriteCoordinator` like any tool.

### 5.7 Cooperative codegen

Generated blocks retain the block shape expected by DEC-020/021/022. A proposal that cannot identify
its block is a conflict, not a whole-file replacement. This is what keeps user-edited blocks and
entity/IoC output from fighting.

### 5.8 Required tests

- two proposals for one path → one applies, the other is stale;
- editor changes the file between preview and apply → conflict, no overwrite;
- apply twice → one write;
- own write produces no second proposal;
- reject after another writer → refusal, not a revert of the other writer;
- two agent processes → one tool socket, one writer;
- entity pass + agent proposal on the same file → serialized by lock/hash.

## 6. Aligning `hipster-entity` and `hipster-ioc`

### 6.1 Do not reimplement, adapt

- **Generic builders/accessors/constructors** stay generic tools, but expose the same
  `CodeGenerator`/`CodeContext` contract as the engine. The existing `jwa-builder` processors can
  sit behind that adapter.
- **Entity** generation stays in `hipster-entity-tooling`; the agent gains an adapter that calls its
  generators and turns their output into proposals. The entity tool id must not become a second
  implementation of `ViewBuilderGenerator`.
- **IoC** generation stays in `hipster-ioc-tooling`; the agent adapter offers context graphs and
  context implementation generation as proposals/page views.

### 6.2 Shared analysis

Replace duplicated source analysis with the engine's:

- `SourceReader`/`TreeQueries` for positions and declarations;
- `TypeResolver`/class index for types;
- `SourceMetadata` for what the metadata pass knows;
- no second JavaParser tree for a file the class index already describes.

`ContextualAnalyzer` becomes a thin discovery layer over these services, not a parser owner.

### 6.3 OpenRewrite is the only Java parser

The rule is already true in production and becomes an enforced contract in this plan:

- **Parsing/model:** `SourceReader` + `TreeQueries` (OpenRewrite LST) for declarations,
  annotations, fields, methods and interfaces. No `com.github.javaparser` import or POM coordinate
  in production Java.
- **Positions:** javac line facts (the LST has no positions), as `SourceSplicer` and `LineLookup`
  already establish.
- **Generation:** a generator derives its content from the LST/model, then writes through
  `SourceSplicer` so untouched formatting stays untouched (DEC-030). A template that re-parses Java
  is not allowed; a second AST is not allowed.
- **Not Java:** page/JSON generators are outside this rule; they do not parse Java at all.
- **Guard:** `bun scripts/check-no-javaparser.js` fails on a production JavaParser import or POM
  dependency. It ignores the historical `doc/brainstorm` and `plans/rewrite-migration` records.

The current agent's builder/accessor/constructor tools already delegate to `ClassMemberProcessor`
(OpenRewrite); the rename tool uses `SourceReader`/`TreeQueries`; entity and IoC tooling use the
same engine. The integration must preserve that, not add a new parser beside it.

**Decision recorded as an open item below:** if "OpenRewrite generation" is intended to mean using
OpenRewrite's *printer* for the emitted members rather than LST-derived text through
`SourceSplicer`, that changes DEC-030 and must be decided explicitly. This plan does not assume it.

### 6.4 Annotation boundaries

- File tools propose from fs-meta changes; there is no comment-marker trigger or marker-driven discovery.
- `@View`, `@HipsterContext` and the entity/IoC model annotations remain the model syntax.
- Entity/IoC tools are invoked explicitly; their tool id and generated source still belong to that
  generator.

### 6.5 Generated-file ownership

Each generated file gets one owner in a **generation manifest**:

```text
owner: hipster-entity-tooling | hipster-ioc-tooling | generic-tool:<id>
path: src/main/java/.../PersonSummaryBuilder.java
```

An agent proposal touching a file owned by another generator is rejected at preview time with the
owner named. This prevents “agent's builder” and “entity's builder” from both editing a view.

### 6.6 Audit and apply

Use one audit shape for both the agent and build generation:

- before/after content hashes;
- tool/generator id and version;
- source metadata generation;
- user edits protected by cooperative block detection;
- apply/reject obeying the coordinator.

The current `.jcodebuddy/metadata/watch/audit` path can be kept as the agent's audit root, but its
record shape and restore rules must match the shared semantics rather than being a second undo system.

## 7. Tool packaging and split

### 7.1 Tool package

```text
jcodebuddy-tool-<id>/
  src/main/java/.../tool/<Id>Tool.java       implements ToolProvider
  src/main/java/.../tool/<Id>Generator.java  CodeGenerator adapter, if it generates
  ui/                                        optional standalone/integrated UI
  tool.json                                  id, title, version, UI manifest
  README.md
```

A tool is a **published library** with a small, explicit registry entry. It is not discovered by
reflection, `META-INF/services` scanning or a classpath scan; the agent's `AgentToolCatalog` names
each tool class in committed source (DEC-019's navigability rule).

### 7.2 Agent dependency direction

`jcodebuddy-agent` depends on the tool API and on the catalog it composes; tool packages do not
depend on the agent. The agent's `AgentMethods` transport is unchanged: it can expose the same
`tool.*` verbs for one tool, many tools, or one socket per tool later.

### 7.3 Split order

1. Generic builder/accessor/constructor/rename tools.
2. Entity generator adapter and its standalone UI.
3. IoC generator adapter and its standalone UI.
4. Any project-specific tool a driver project adds.

Each split is a separate commit with the tool's own tests and a catalog entry. The agent keeps a
temporary in-process adapter during the transition, then removes it.

## 8. Standalone and integrated UI

### 8.1 Standalone is the basis

Every tool must be usable as a page without the agent host and without WebView Explorer:

- a self-contained page (or a page served by the project's local server);
- its own data-loading path;
- no dependency on a terminal UI or a token.

This is the existing AGENTS rule and the reason an integrated SPA is a later, optional enhancement.

### 8.2 Tool UI manifest

```json
{
  "id": "builder",
  "title": "Builder",
  "standalone": "standalone.html",
  "integrated": "view.jsx",
  "navigation": { "mode": "auto", "home": "standalone.html" }
}
```

- `standalone` is the page the agent catalog links and a person can open directly.
- `integrated` is optional and is what a shell can render in place.
- `navigation` uses the design system's navigation module; a tool can choose `query`, `hash`,
  `path`, `memory` or its own parser/formatter.

### 8.3 Agent catalog and shell

The agent dashboard becomes a **catalog**:

- tool cards with title, status, pending actions and “Open”;
- pending actions across tools, grouped by tool;
- per-tool standalone links;
- integrated views only when the app has opted in.

### 8.4 Project-automation choice

Project-automation gets a per-tool UI mode:

```text
ui = standalone | integrated | both
```

- `standalone`: emit the tool page; do not register an integrated route.
- `integrated`: emit the view/component and register the route; **do not emit the standalone page**.
- `both`: emit both; the app may link either.

Omitting a page is only valid when the integrated view exists; the check must fail otherwise. The
default remains **standalone**.

### 8.5 URL and navigation

- A tool page uses the design system navigation module.
- The app shell's `window.jcodebuddyNavIntercept` can handle a `kind: "tool"` target and embed/render
  the integrated view, or return `false` and load the standalone page.
- `navigation.json` chooses query/hash for the tool family as it does for files.
- A standalone tool page must still work when `navigation.json` is absent.

## 9. Rename plan (later, after phases 0–5)

### 9.1 Trigger

Do not rename until:

- the tool API and coordinator are stable;
- entity/IoC adapters and standalone UIs exist;
- project-automation can choose standalone/integrated;
- the compatibility seams below are in place.

### 9.2 Scope inventory

A rename must enumerate and update:

- Maven module/artifact `jcodebuddy-agent`;
- Java package `hr.hrg.watch2.agent` and its imports;
- shaded jar/main class/CLI name;
- tool id `jcodebuddy-agent` in `.jcodebuddy/ipc/` and page URLs;
- config `.watch_agent.conf` and old field names;
- page file names, titles and asset names;
- audit/state paths under `.jcodebuddy/`;
- docs, plans, tests, scripts and driver-project references.

### 9.3 Compatibility seam

Before changing a public name, publish both:

- **tool id alias**: the descriptor and host resolve the new id and the old id for one release;
- **config fallback**: read the new config, fall back to `.watch_agent.conf`;
- **state migration**: copy/migrate audit and metadata paths once, idempotently;
- **route alias**: old page URL redirects/loads the new page;
- **Maven relocation**: keep the old artifact coordinates as a relocation/alias for one release.

### 9.4 Naming criteria (decision deferred)

The new name must:

- describe the **orchestrator/agent** role, not the obsolete “watch” mechanism;
- cover generic tools, entity/IoC tools and tool UIs;
- not be tied to `java`, `watch2` or one tool;
- fit the JCodeBuddy family without implying it is the engine;
- be checked against module/artifact/page/config names before selection.

Candidate names are deliberately not chosen here; choosing one is the rename phase's first decision.

### 9.5 Rename gates

- all tool ids/aliases resolve;
- old config/state can be read and migrated;
- old page URL and new page URL both work for one release;
- agent, entity and IoC tests pass with old and new ids;
- docs link check passes;
- the old artifact relocation is verified in a clean consumer.

## 10. Phases and acceptance

### Implementation progress (2026-11-22)

| Area                                           | State                                                                                             |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| `jcodebuddy-tool-api` + `WriteCoordinator`     | **implemented**: proposal, lock, stale check, atomic write, revert, audit                         |
| Generic builder tool                           | **implemented** as a separate module with a jsx6 standalone page                                  |
| Generic accessor/constructor/rename tools      | **implemented** as proposal tools in `jcodebuddy-tool-java`; tested through previews              |
| Agent catalog                                  | **implemented**: `tool.list`, `tool.preview`, `tool.apply`, `tool.reject`; builder is linked      |
| hipster-ioc tool                               | **implemented** as a proposal adapter over `IocGeneration.render`                                 |
| hipster-entity tool                            | **implemented** as a proposal adapter over the temporary-source entity pass                       |
| Activation config                              | **implemented per tool**: `.jcodebuddy/conf/agent.json` has `explicit`/`auto`/`disabled` for each tool; auto creates proposals, never applies |
| Legacy tool path                               | **removed**; there is one `Tool` API and one `WriteCoordinator`                                   |
| Generated-file ownership                       | **implemented**: `.jcodebuddy/agent/owners.properties` refuses a conflicting generator            |
| Project-automation standalone/integrated modes | **implemented for builder** in both drivers: playground is `integrated`, business-logic is `both` |
| Change batches and diff                        | **implemented**: per-event batch IDs, jsdiff line diffs, microlighter highlighting, per-file/batch apply and revert |
| Active/review mode                             | **implemented per tool** via `mode: review                                                        | active`; active applies immediately and remains revertible |
| Permanent refusal                              | **implemented**: stable case keys and `.jcodebuddy/conf/tool-refusals.json`; tools without a stable case leave it off |
| Rename                                         | **deliberately not started**                                                                      |

The remaining work is the rename phase. The tool split, coordinator, legacy migration, entity/IoC
adapters, per-tool activation and project-automation UI modes are in place.


| Phase | Work                                       | Acceptance                                                                                       |
| ----- | ------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| 0     | Baseline (done)                            | fs-meta watcher, jsx6 page, tool socket, `/rpc`, 36 tests                                        |
| 1     | Tool API + `WriteCoordinator` contracts    | pure proposal/apply/stale/lock tests; no tool writes directly; `bun scripts/check-no-javaparser.js` passes |
| 2     | Single writer + batch/refusal model        | all proposals go through `WriteCoordinator`; stale/duplicate/refusal tests                       |
| 3     | Generic tool split                         | builder/accessor/constructor/rename in separate packages with catalog entries                    |
| 4     | Entity/IoC adapters                        | one generator per output; ownership manifest; no duplicate output; OpenRewrite-only parser check |
| 5     | Standalone tool UIs + agent catalog        | each tool page works without host; catalog links it; integrated mode opt-in                      |
| 6     | Project-automation UI modes                | `standalone`/`integrated`/`both`; omission check fails if no integrated view                     |
| 7     | Rename                                     | compatibility aliases, migration tests, then remove old names                                    |

The current name is used until phase 7. Every phase before it is written under `jcodebuddy-agent`,
`hr.hrg.watch2.agent` and the existing tool id.

## 11. Open decisions

1. **Coordinator home:** `jcodebuddy-agent` or `jcodebuddy-core`? The coordinator is a library
   concern; the agent is its first caller. If a build must use it without the agent, promote it.
2. **Tool execution:** in-process adapters first, one socket per tool later? The agent's method
   transport supports either.
3. **Ownership manifest:** generated by each generator, or assembled by project-automation from
   the class index? It must be committed/source-visible either way.
4. **Integrated UI:** component import vs iframe/embed. Both must keep a standalone fallback.
5. **Rename:** name, artifact relocation window, and whether old tool id remains permanent.
6. **Audit unification:** whether the agent's audit becomes the JCodeBuddy undo journal or remains
   a review log that delegates final apply to JCodeBuddy.
7. **Generation writer:** keep LST-derived text through `SourceSplicer` (DEC-030), or switch to
   OpenRewrite's printer. The latter is a formatting/DEC-030 change, not a parser choice.

## 12. Risks

- **Two writers slip through** if project-automation generation bypasses the coordinator. The lock
  must be part of the generation API, not a convention.
- **Stale proposals** are the normal failure; the UI must show the conflict, not retry silently.
- **Generated source ownership** is easy to duplicate when entity and generic builders both target a
  class; the ownership manifest is the guard.
- **Standalone UIs drift** from integrated views if they are maintained twice; generate both from the
  same tool manifest/view source.
- **Rename before contracts** would multiply the compatibility surface; the phase order is deliberate.

## 13. References

- [jcodebuddy-agent README](../jcodebuddy/jcodebuddy-agent/README.md)
- [design system proposal](../doc/design-system/README.md)
- [navigation module design](../doc/design-system/navigation.md)
- [webview app contract](../webview/doc/webview-app-contract.md)
- [jcodebuddy-ipc](../jcodebuddy/jcodebuddy-ipc/README.md)
