# Moving JCodeBuddy off `java-watch`, onto `fs-meta`

**Status: 2026-10-31 — proposed plan; no code changed.** This plan is the inventory and the standalone steps for
the rule in root [`AGENTS.md`](../AGENTS.md) § 2: `java-watch*` is **frozen**, and a watcher need goes to
**`fs-meta`**, not into the frozen group. It is deliberately not a single “remove the dependency” task: the
dependency is six modules, and `java-watch-core` bundles three unrelated APIs.

## 1. The constraint

- `java-watch*` is frozen. Do not add features, refactor it, or extend it for JCodeBuddy.
- `fs-meta` is the shared change-tracking primitive: reliable state, content hashes, batching, Git cooperation.
  Its checkout is `D:/wrk/core/fs-meta`; its Java artifact is `hr.hrg:fs-meta`.
- **`fs-meta` is not an OS event source.** Its Java API scans, publishes and diffs the trio; it does not deliver
  “this file changed” events. The migration therefore has two halves: an **event source** owned by JCodeBuddy, and
  **state/diff** owned by `fs-meta`. A plan that pretends `fs-meta` replaces `ManagedFileWatcher` as an event API
  would be wrong.
- The `watch/*` modules themselves are not in scope. They stay frozen and keep their own copies of the types they
  need; JCodeBuddy stops depending on them.
- **Prerequisite for W4/W5:** `hr.hrg:fs-meta` is not a reactor module. Build/install it from
  `D:/wrk/core/fs-meta` (`hr.hrg:fs-meta:0.1.0-SNAPSHOT`, with `hr.hrg:wyhash` installed alongside it) before the
  consumers resolve it; the plan does not vendor either project.

## 2. Inventory — measured 2026-10-31

The six JCodeBuddy consumers of the `java-watch-core` artifact:

| Module                            | POM                                                              | What it uses                                                           | Target                                               |
| --------------------------------- | ---------------------------------------------------------------- | ---------------------------------------------------------------------- | ---------------------------------------------------- |
| `jcodebuddy-agent`                | [`pom.xml:22`](../jcodebuddy/jcodebuddy-agent/pom.xml#L22)       | [`ProjectWatcher`](../jcodebuddy/jcodebuddy-agent/src/main/java/hr/hrg/watch2/agent/ProjectWatcher.java#L5-L6) uses `FileFilter` and `ManagedFileWatcher`; [`ToolSetAgent`](../jcodebuddy/jcodebuddy-agent/src/main/java/hr/hrg/watch2/agent/ToolSetAgent.java#L29-L40) uses `FileFilter` | event port + wildmatch filter + `fs-meta` state (W4) |
| `project-automation`              | [`pom.xml:27`](../project-automation/pom.xml#L27)                | [`WatchedRegeneration`](../project-automation/src/main/java/hr/hrg/jcodebuddy/automation/watch/WatchedRegeneration.java#L3-L5) uses `BatchedFileWatcher`, `ChangeSet`, `FileFilter`; [`EntityRegenerationWatcher`](../project-automation/src/main/java/hr/hrg/jcodebuddy/automation/entity/EntityRegenerationWatcher.java#L7) and [`IocRegenerationWatcher`](../project-automation/src/main/java/hr/hrg/jcodebuddy/automation/ioc/IocRegenerationWatcher.java#L116) consume `ChangeSet`; three tests construct it | event port + `fs-meta` diff/reconcile (W5) |
| `jcodebuddy-builder`              | [`pom.xml:53`](../jcodebuddy/jcodebuddy-builder/pom.xml#L53)     | [`BuilderTransformationEngine`](../jcodebuddy/jcodebuddy-builder/src/main/java/hr/hrg/jcodebuddy/builder/BuilderTransformationEngine.java#L5-L6) uses `CodeEdit`, `TransformationResult`; [`LineLookup`](../jcodebuddy/jcodebuddy-builder/src/main/java/hr/hrg/jcodebuddy/builder/LineLookup.java#L72) names `CodeEdit` in javadoc; two tests use `CodeEdit`/`CodeEditApplier` | JCodeBuddy-owned code-edit types (W2) |
| `jcodebuddy-watch-tools`          | [`pom.xml:44`](../jcodebuddy/jcodebuddy-watch-tools/pom.xml#L44) | [`RecordBuilderGenerator`](../jcodebuddy/jcodebuddy-watch-tools/src/main/java/hr/hrg/jcodebuddy/watchtools/RecordBuilderGenerator.java#L8-L11) uses `ActionTool` (from the agent, not `core`), `CodeEditApplier`, `TransformationResult` | code-edit types (W2); the agent port stays |
| `webview/jwa-sidecar`             | [`pom.xml:32`](../webview/jwa-sidecar/pom.xml#L32)               | [`JwaTextDocumentService`](../webview/jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java#L148-L158) uses `TransformationResult`, `CodeEdit` | code-edit types (W2) |
| `hipster-ioc/hipster-ioc-tooling` | [`pom.xml:39`](../hipster-ioc/hipster-ioc-tooling/pom.xml#L39)   | **No source import** of `hr.hrg.watch2.core`; the dependency is unused | remove the dependency (W6)                           |

The one-file sample that consumes `ChangeSet` in tests is
[`AutomationChainIntegrationTest`](../project-automation/src/test/java/hr/hrg/jcodebuddy/automation/AutomationChainIntegrationTest.java#L6);
the other two are the watcher tests named in the table.

### Not dependencies, despite the name

| Place                       | What it is                                   | Why it is not in scope                                                                        |
| --------------------------- | -------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `jcodebuddy/metadata-arena` | Package `hr.hrg.watch2.arena`                | No POM dependency and no import of `hr.hrg.watch2.core`; it is the arena's own namespace. A package rename is a separate cleanup, not this migration. |
| `webview/jwa-sidecar`       | Package `hr.hrg.watch2.sidecar`              | The sidecar's own namespace; its actual dependency is the code-edit types in the table above. |
| `jcodebuddy-agent`          | Package `hr.hrg.watch2.agent`                | The agent's own namespace; the dependency is its watcher use. Do not rename the package as part of this plan. |
| `webview/core/webview-core` | String `hr.hrg.watch2.sidecar`               | A protocol identity in `/health`, not a class dependency.                                     |
| `jcodebuddy-meta`           | Comment reference in `WatchMetadataProvider` | No POM dependency; it is historical wording.                                                  |
| `jcodebuddy-core`           | `EngineHasNoWatcherTest`                     | It asserts the engine has no watcher; it must stay watcher-free (DEC-037).                    |
| `watch/java-watch-*`        | The frozen modules                           | Not JCodeBuddy consumers; they keep their own copies.                                         |

## 3. The three APIs `java-watch-core` bundles

This is the finding that decides the step split — one dependency, three different homes:

| API                                                                                            | Used by                                                                              | Where it belongs |
| ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ | ---------------- |
| `ManagedFileWatcher`, `BatchedFileWatcher`, `FileFilter`, `DirectoryScanner`, `WatchConstants` | agent (`ProjectWatcher`, `ToolSetAgent`); project-automation (`WatchedRegeneration`) | An **event source** owned by JCodeBuddy (W3), with `wildmatch`/`fs-meta` filters. Not `fs-meta` itself, which has no events. |
| `ChangeSet`, `ChecksumDatabase`                                                                | project-automation (`WatchedRegeneration`, the two regeneration watchers, tests)     | **`fs-meta`**: the trio, `FsMetaDiff.between`, `FsMetaScanner.scanFile`, `FsMetaPair.load`; the loop-breaker's SHA-1 map becomes the trio's content hashes. |
| `CodeEdit`, `CodeEditApplier`, `TransformationResult`                                          | builder, watch-tools, sidecar                                                        | A **JCodeBuddy code-edit API** in `jcodebuddy-builder-api` (values) + `jcodebuddy-builder` (applier). This is not file watching and not `fs-meta`. |

`fs-meta`'s Java API is concrete: `FsMetaScanner.scan`/`scanFile` for an incremental entry,
`FsMetaPair.load` for the current trio, `FsMetaDiff.between` for changed/removed/touched, `FsMetaWriter.publish`
for the next generation. Its README is the authority, and its own rule — *the trio is a cache, never a
correctness input* — is why the event source may deliver a file, but the decision to regenerate reads the file
or the trio, never an assumed mtime.

## 4. Standalone steps

Each step is one commit with its own gate. W2 and W3 are independent and may be taken in either order; W4 and W5
depend on W3. W1 is a measurement/decision step, not a code change.

### W1 — Baseline the consumers and settle the event-source shape (S)
**Who:** agent · **Depends:** none

Add `scripts/check-java-watch-consumers.js` (Bun, exit non-zero on an unexpected consumer) that reports:
POM `<artifactId>java-watch-*` dependencies outside `watch/`, and Java imports of `hr.hrg.watch2.core` outside
`watch/`; it prints the allowed list above and names anything new. Record the event-source decision: a tiny
JCodeBuddy **`jcodebuddy-watch`** module with a `WatchService` event loop, debounce/batching and `wildmatch`
filters, or the same port inside an existing module if W1 proves the module overhead is not worth it. The
recommendation is the tiny module: both consumers need it, and `jcodebuddy-core` must stay watcher-free.

**Gate:** the script prints exactly the six consumers and no unknown; the decision is recorded in this plan (or
as an ADR if it changes a public boundary).

### W2 — Own the code-edit API (M)
**Who:** agent · **Depends:** W1

Define `CodeEdit` and `TransformationResult` in `jcodebuddy-builder-api`, and `CodeEditApplier` in
`jcodebuddy-builder`, with the same semantics as the frozen originals. Update `jcodebuddy-builder`,
`jcodebuddy-watch-tools` and `webview/jwa-sidecar` (and their tests) to the new types. Do **not** edit
`java-watch-core`: its copies remain for the frozen `watch/*` modules, and the two sets do not interact.

**Gate:** the three modules build and their tests pass; no `hr.hrg.watch2.core` import remains in them; the
generated builder output is byte-identical; `java-watch-core` is gone from their POMs.

### W3 — Add the JCodeBuddy event source (M)
**Who:** agent · **Depends:** W1

Create the event source chosen in W1: a `WatchService`-based loop, debounce and batch delivery equivalent to the
current `ManagedFileWatcher`/`BatchedFileWatcher` behaviour, with `wildmatch` filters. It publishes a batch of
paths; it does not own state, hashing or policy. `fs-meta` is the state half.

**Gate:** unit tests for create/modify/delete, debounce, stop/restart and an ignored path; no `java-watch*`
artifact or `hr.hrg.watch2.core` import in the new code; `jcodebuddy-core` still has no watcher.

### W4 — Move the agent's watcher to the event source + `fs-meta` (M)
**Who:** agent · **Depends:** W3

Replace `ProjectWatcher`'s `ManagedFileWatcher` and `ToolSetAgent`'s `FileFilter` with the W3 event source and
`wildmatch`/`fs-meta` filters; keep `ProjectWatcher`'s dispatch and `ToolSetAgent`'s on-file-change behaviour.
Drop `java-watch-core` from the agent POM. The agent's own package name (`hr.hrg.watch2.agent`) is not renamed
here.

**Gate:** agent tests pass (the current suite is 30/0, including the handshake); a live on-change dispatch still
reaches a tool; no `hr.hrg.watch2.core` import; POM has no `java-watch*`.

### W5 — Move project-automation's loop to the event source + `fs-meta` (M/L)
**Who:** agent · **Depends:** W3

Keep `WatchedRegeneration`'s public `Pass`/`Regenerator` contract and its one-no-op-pass loop breaker. Replace
`BatchedFileWatcher`/`ChangeSet`/`FileFilter` with the W3 batch and `fs-meta`: seed the state from
`FsMetaPair.load` (or `FsMetaScanner.scan` on first start), use `FsMetaDiff.between`/`scanFile` to decide whether
a batch is real, and publish the next trio after a pass. The two regeneration watchers and their tests move from
`ChangeSet` to the new batch type. Drop `java-watch-core` from the POM.

**Gate:** the watcher, entity and IOC tests pass; the loop breaker still ignores a self-write and a same-content
save; no `hr.hrg.watch2.core` import; POM has no `java-watch*`.

### W6 — Remove the unused dependency and enforce the boundary (S)
**Who:** agent · **Depends:** W2, W4, W5

Remove `java-watch-core` from `hipster-ioc-tooling` (no source import), make
`scripts/check-java-watch-consumers.js` a gate check (not report-only), and update the module map/READMEs that
still describe `java-watch-core` as the watcher for these modules. `watch/*` stays untouched.

**Gate:** the check passes over the tree; no JCodeBuddy POM depends on a `java-watch*` artifact and no JCodeBuddy
source imports `hr.hrg.watch2.core`; full `bun scripts/mvn-jdk25.js` green; the module's own tests and
`check-watch-standalone.js` still pass.

## 5. Done when

- The check script reports **0 JCodeBuddy consumers** outside `watch/`.
- The six POMs in § 2 no longer name `java-watch-core`; no JCodeBuddy source imports `hr.hrg.watch2.core`.
- The agent and project-automation use the event source + `fs-meta`; the code-edit types live in
  `jcodebuddy-builder-api`/`jcodebuddy-builder`.
- `fs-meta` is the only state/diff authority; the event source owns events and policy stays in the consumers.
- Full gate green, and the `watch/*` modules are byte-identical to their frozen state (no edits to them).

## 6. How the inventory was measured

Run from the repository root; the exclusions are the frozen modules, generated `target/`, and the agent's
derived `.watch/` audit copies:

```text
Get-ChildItem . -Recurse -Filter pom.xml | Where-Object { $_.FullName -notmatch '\\target\\|node_modules|\\.tmp\\|proto\\' } | Select-String '<artifactId>java-watch'
Get-ChildItem . -Recurse -Include *.java | Where-Object { $_.FullName -notmatch '\\target\\|node_modules|\\.tmp\\|proto\\|\\.watch\\' } | Select-String 'import hr\.hrg\.watch2\.(core|agent)'
```

The first command's six non-`watch/` results are the table in § 2. The second also finds the agent's own package
imports and the audit copies, which is why the inventory separates "own namespace" from "dependency" in § 2.
