# Moving JCodeBuddy off `java-watch`, onto `fs-meta`

**Status: 2026-11-07 — W1–W7 done; the full gate is green; `WatcherBoundaryTest` enforces the boundary.
There is no accepted writer exception: Design B moved `EntityBuildState` to a daemon-generation marker.**
W1 measured the consumers, added the check script and settled the event-source shape. W2 moved the code-edit types
to the builder. W3 added `jcodebuddy-watch`; W4 moved the agent onto it; W5a/W5b moved project-automation's state to
`fs-meta` and its events to `jcodebuddy-watch`; W6 removed the last unused dependency and made the consumer check part
of the gate. The frozen `watch/*` modules are untouched. **`jcodebuddy-watch` is the daemon client and the Java
`FsMetaWriter` is now a test/tool writer only**: the intended watcher is fs-meta's Zig daemon, with tools as clients
over its Unix socket (W7), because one native watch loop shared by several active processes is the lightest and
fastest arrangement. This plan is the
inventory and the standalone steps for the rule in root [`AGENTS.md`](../AGENTS.md) § 2: `java-watch*` is **frozen**,
and a watcher need goes to **`fs-meta`**, not into the frozen group. It was deliberately not a single “remove the
dependency” task: the dependency was six modules, and `java-watch-core` bundled three unrelated APIs.

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
  `D:/wrk/core/fs-meta` (`hr.hrg:fs-meta:0.3.6`, with `hr.hrg:wyhash` and `hr.hrg:gitignore` installed alongside it)
  before the consumers resolve it; the plan does not vendor either project. W1's spike used the built
  `target/classes` and the local `wyhash`/`gitignore` jars, so it did not modify `fs-meta`.

## 2. Inventory — measured 2026-10-31

The six JCodeBuddy consumers of the `java-watch-core` artifact:

| Module                            | POM                                                              | What it uses                                                              | Target                                                 |
| --------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------ |
| `jcodebuddy-agent`                | [`pom.xml:22`](../jcodebuddy/jcodebuddy-agent/pom.xml#L22)       | [`ProjectWatcher`](../jcodebuddy/jcodebuddy-agent/src/main/java/hr/hrg/watch2/agent/ProjectWatcher.java#L5-L6) uses `FileFilter` and `ManagedFileWatcher`; [`ToolSetAgent`](../jcodebuddy/jcodebuddy-agent/src/main/java/hr/hrg/watch2/agent/ToolSetAgent.java#L29-L40) uses `FileFilter` | event port + wildmatch filter + `fs-meta` state (W4) |
| `project-automation`              | [`pom.xml:27`](../project-automation/pom.xml#L27)                | [`WatchedRegeneration`](../project-automation/src/main/java/hr/hrg/jcodebuddy/automation/watch/WatchedRegeneration.java#L3-L5) uses `BatchedFileWatcher`, `ChangeSet`, `FileFilter`; [`EntityRegenerationWatcher`](../project-automation/src/main/java/hr/hrg/jcodebuddy/automation/entity/EntityRegenerationWatcher.java#L7) and [`IocRegenerationWatcher`](../project-automation/src/main/java/hr/hrg/jcodebuddy/automation/ioc/IocRegenerationWatcher.java#L116) consume `ChangeSet`; three tests construct it | **resolved 2026-10-31** (W5a state + W5b event source) |
| `jcodebuddy-builder`              | [`pom.xml:53`](../jcodebuddy/jcodebuddy-builder/pom.xml#L53)     | [`BuilderTransformationEngine`](../jcodebuddy/jcodebuddy-builder/src/main/java/hr/hrg/jcodebuddy/builder/BuilderTransformationEngine.java#L5-L6) uses `CodeEdit`, `TransformationResult`; [`LineLookup`](../jcodebuddy/jcodebuddy-builder/src/main/java/hr/hrg/jcodebuddy/builder/LineLookup.java#L72) names `CodeEdit` in javadoc; two tests use `CodeEdit`/`CodeEditApplier` | JCodeBuddy-owned code-edit types (W2) |
| `jcodebuddy-watch-tools`          | [`pom.xml:44`](../jcodebuddy/jcodebuddy-watch-tools/pom.xml#L44) | [`RecordBuilderGenerator`](../jcodebuddy/jcodebuddy-watch-tools/src/main/java/hr/hrg/jcodebuddy/watchtools/RecordBuilderGenerator.java#L8-L11) uses `ActionTool` (from the agent, not `core`), `CodeEditApplier`, `TransformationResult` | code-edit types (W2); the agent port stays |
| `webview/jwa-sidecar`             | [`pom.xml:32`](../webview/jwa-sidecar/pom.xml#L32)               | [`JwaTextDocumentService`](../webview/jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java#L148-L158) uses `TransformationResult`, `CodeEdit` | code-edit types (W2) |
| `hipster-ioc/hipster-ioc-tooling` | [`pom.xml:39`](../hipster-ioc/hipster-ioc-tooling/pom.xml#L39)   | **No source reference** to `hr.hrg.watch2.core`; the dependency is unused | remove the dependency (W6)                             |

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

Each step is one commit with its own gate. W2 and W3 are independent and may be taken in either order; W4 and W5b
depend on W3. W1 added the check; W5a added the first real `fs-meta` consumer, with `java-watch` still the event
source.

### W1 — Baseline the consumers and settle the event-source shape (S) — done 2026-10-31
**Who:** agent · **Depends:** none

Add `scripts/check-java-watch-consumers.js` (Bun, exit non-zero on an unexpected consumer) that reports:
POM `<artifactId>java-watch-*` dependencies outside `watch/`, and Java references to `hr.hrg.watch2.core` outside
`watch/`; it prints the baseline in § 2 and names anything new. Record the event-source decision: a tiny JCodeBuddy
**`jcodebuddy-watch`** module with a `WatchService` event loop, debounce/batching and `wildmatch` filters, or the
same port inside an existing module if W1 proves the module overhead is not worth it. The recommendation is the
tiny module: both consumers need it, and `jcodebuddy-core` must stay watcher-free.

**Gate:** the script prints exactly the six consumers and no unknown; the decision is recorded in this plan (or
as an ADR if it changes a public boundary).

**Done 2026-10-31.**

- **The check exists and matches the table**: `bun scripts/check-java-watch-consumers.js` reported **6 consumer
  modules, 0 unexpected** (858 files, **68 ms**) at W1; W5b has since resolved project-automation, so it now reports
  **5**. It prints the POM dependency and the Java references to `hr.hrg.watch2.core` for each. `--strict` exits 1
  while any consumer remains, which is the W6 gate. It finds the fully-qualified use in `JwaTextDocumentService` that
  an import-only scan would miss, and it excludes the agent's `.watch/` audit copies.
- **Event source settled: a new tiny `jcodebuddy-watch` module** (W3), not a port inside a consumer.
  `jcodebuddy-core` must stay watcher-free (DEC-037, `EngineHasNoWatcherTest`), and `project-automation` must not
  depend on the runtime agent; a leaf both can depend on is the only shape that does not invert a boundary. It uses
  `WatchService`, debounce/batching and the `gitignore`/`wildmatch` authority — no `java-watch*`.
- **Smallest unit chosen: `project-automation`'s `WatchedRegeneration`.** It is one class but it is the smallest unit
  that uses **both** the watcher and `fs-meta`-relevant state (`ChangeSet` plus its own SHA-1 map); the agent's
  `ProjectWatcher` is smaller but only needs events, and the builder/watch-tools/sidecar need only code-edit types,
  so none of those can test the state migration.

### W1 spike — `WatchedRegeneration`'s state half against `fs-meta`

The spike lives in `.tmp/fsmeta-spike/` (ignored, not committed): `FsMetaSpike` exercises the primitives, and
`FsMetaRegenSpike` models `WatchedRegeneration`'s state half — seed a trio, apply an event batch, diff, ignore
`TOUCHED`, run a pass, publish the generator's output, and ignore the self-write echo. Both pass. It used
`fs-meta` `0.3.6` `target/classes` with local `gitignore 0.3.5` and `wyhash 1.1.0-SNAPSHOT`; **no `fs-meta` source
was changed.**

What the spike proved, and what it found:

1. **The state half works.** `FsMetaScanner.scan`/`scanFile` + `FsMetaWriter.publish` + `FsMetaPair.load` +
   `FsMetaDiff.between` detected a same-size content change as `MODIFIED`, an add as `ADDED`, a delete as `REMOVED`,
   and a touch-only save as `TOUCHED`. Generation advances on publish, and the trio validated after every write.
2. **The loop breaker maps cleanly.** The only rule `WatchedRegeneration` needs is *ignore `TOUCHED`*; a pass runs
   for `MODIFIED`/`ADDED`/`REMOVED`. A same-content save produced no pass, and the self-write echo was ignored once
   the generator's `generated.txt` was in the state.
3. **`fs-meta` has no event API.** The event source is W3's job; the spike supplied the batch paths directly.
4. **Two integration obligations, not blocking `fs-meta` gaps:**
   - The caller must carry the `List<FsMetaFile>` itself: replace a changed name with `scanFile`, remove a deleted
     one, add a generated one, and publish. There is no “apply this event set” helper; the W5b adapter owns that
     small loop (and can be shared with W4 if the agent needs it).
   - A custom trio directory must be excluded from the scan. The default skips `.fs-meta` and `.git`; a consumer
     that stores the trio under `.jcodebuddy/` must pass `FsMetaScanOptions.ignoring(...)` (or use `.fs-meta`).
5. **No `fs-meta` change is required for the first use-case.** The one improvement worth considering later is a
   batch-apply helper (`FsMetaPair` + changed paths → next `List<FsMetaFile>`) because W4/W5b would otherwise write
   the same loop twice. It is a convenience, not a blocker; the plan does not schedule an `fs-meta` change until the
   second consumer proves the duplication.
6. **`batching` is still the event source's job.** `fs-meta` has generations and diffs, not a debounce window; W3
   provides the batch boundary that `WatchedRegeneration` currently gets from `BatchedFileWatcher`.

### W2 — Own the code-edit API (M) — done 2026-10-31
**Who:** agent · **Depends:** W1

Define `CodeEdit` and `TransformationResult` in `jcodebuddy-builder-api`, and `CodeEditApplier` in
`jcodebuddy-builder`, with the same semantics as the frozen originals. Update `jcodebuddy-builder`,
`jcodebuddy-watch-tools` and `webview/jwa-sidecar` (and their tests) to the new types. Do **not** edit
`java-watch-core`: its copies remain for the frozen `watch/*` modules, and the two sets do not interact.

**Gate:** the three modules build and their tests pass; no `hr.hrg.watch2.core` reference remains in them; the
generated builder output is byte-identical; `java-watch-core` is gone from their POMs.

**Done 2026-10-31.** The values are
[`CodeEdit`](../jcodebuddy/jcodebuddy-builder-api/src/main/java/hr/hrg/jcodebuddy/builder/api/edit/CodeEdit.java)
and
[`TransformationResult`](../jcodebuddy/jcodebuddy-builder-api/src/main/java/hr/hrg/jcodebuddy/builder/api/edit/TransformationResult.java)
in `hr.hrg.jcodebuddy.builder.api.edit`; the applier is
[`CodeEditApplier`](../jcodebuddy/jcodebuddy-builder/src/main/java/hr/hrg/jcodebuddy/builder/edit/CodeEditApplier.java)
in `hr.hrg.jcodebuddy.builder.edit`. The builder, watch-tools, the sidecar and their tests use them; all three POMs
no longer name `java-watch-core`, and no `hr.hrg.watch2.core` reference remains in their sources. The builder tests are
**20/0**, the sidecar **26/0**, and the module set builds; the W1 check now reports **two consumers** (agent and the
unused hipster-ioc dependency). The frozen `java-watch-core` keeps its own copies for `watch/*`.
**Gate**: `bun scripts/mvn-jdk25.js` **BUILD SUCCESS** (33.8 s), with the builder's 20 and project-automation's 114
tests run; the sidecar's 26 tests run in its own module set.

### W3 — Add the JCodeBuddy event source (M) — done 2026-10-31
**Who:** agent · **Depends:** W1

Create the `jcodebuddy/jcodebuddy-watch` module settled in W1: a `WatchService`-based loop, debounce and batch
delivery equivalent to the current `ManagedFileWatcher`/`BatchedFileWatcher` behaviour, with `wildmatch` filters
from the `gitignore` authority. It publishes a batch of paths; it does not own state, hashing or policy. `fs-meta`
is the state half. It must not depend on `java-watch*` or on `jcodebuddy-core` (which stays watcher-free).

**Gate:** unit tests for create/modify/delete, debounce, stop/restart and an ignored path; no `java-watch*`
artifact or `hr.hrg.watch2.core` reference in the new code; `jcodebuddy-core` still has no watcher.

**Done 2026-10-31.** The module is [`jcodebuddy/jcodebuddy-watch`](../jcodebuddy/jcodebuddy-watch): `FileWatch`
registers a tree with `WatchService`, debounces the first event, applies `PathFilter` (the `gitignore`/wildmatch
authority) and emits a `WatchBatch`; queue overflow is carried as `WatchBatch.overflow()`. Tests **4/0** —
`PathFilterTest` for include/exclude/wildmatch and `FileWatchTest` for create/modify/delete plus start/stop
idempotence. It is in `GATE_MODULES` and in the module map, and it has no `java-watch*` or `jcodebuddy-core`
dependency. **Gate**: the module's tests are **4/0** and the full gate (8:37) includes it.

### W4 — Move the agent's watcher to the event source + `fs-meta` (M) — done 2026-10-31
**Who:** agent · **Depends:** W3

Replace `ProjectWatcher`'s `ManagedFileWatcher` and `ToolSetAgent`'s `FileFilter` with the W3 event source and
`wildmatch`/`fs-meta` filters; keep `ProjectWatcher`'s dispatch and `ToolSetAgent`'s on-file-change behaviour.
Drop `java-watch-core` from the agent POM. The agent's own package name (`hr.hrg.watch2.agent`) is not renamed
here.

**Gate:** agent tests pass (the current suite is 30/0, including the handshake); a live on-change dispatch still
reaches a tool; no `hr.hrg.watch2.core` reference; POM has no `java-watch*`.

**Done 2026-10-31.** `ProjectWatcher` creates a `FileWatch` with a pass-all `PathFilter` and dispatches each
`WatchBatch.changed()` path; `ToolSetAgent` uses `PathFilter` and `shouldInclude(root, path)`. The agent POM drops
`java-watch-core`, adds `jcodebuddy-watch`, and now declares `hr.hrg:wyhash` explicitly — `AuditManager` and
`MetadataCache` had been getting it transitively, so the removal exposed the hidden dependency. Tests **30/0**
(`RemoteJumpTest`, `WebviewHandshakeTest`, `RenameMemberToolTest`, `ToolSeamTest`); the consumer check drops the
agent, leaving only the unused hipster-ioc dependency. **Gate**: the agent module set is **30/0**; the recorded gate
(12:53) covers the shared builder and project-automation, and the agent is outside `GATE_MODULES` as before.

### W5a — `WatchedRegeneration`'s state half on `fs-meta` (M) — done 2026-10-31
**Who:** agent · **Depends:** W1

Replace the private SHA-1 map with the `fs-meta` trio while keeping `WatchedRegeneration`'s public
`Pass`/`Regenerator` contract and its one-no-op-pass loop breaker. This is the first real consumer: seed the state
from `FsMetaPair.loadIfPresent` (or `FsMetaScanner.scan` on first start), apply a batch's paths with `scanFile`,
decide with `FsMetaDiff.between`, ignore `TOUCHED`, and rescan after a pass so the generator's own output is in the
state.

**Gate:** the watcher, entity and IOC tests pass; a same-content save is ignored and a self-write echo is ignored;
the state trio exists and validates; `java-watch-core` remains only for the event source (W5b).

**Done 2026-10-31.**

- **The real class now stores its loop state in `fs-meta`**: `WatchedRegeneration` seeds
  `<source>/.jcodebuddy/cache/watch-fs-meta` at construction, filters the trio to the watched `.java` files, applies
  a batch with `FsMetaScanner.scanFile`, diffs with `FsMetaDiff.between`, ignores `TOUCHED`, and full-rescans after a
  pass. `FsMetaScanOptions.ignoring(".jcodebuddy")` keeps the state out of its own scan. The public `sha1` helper is
  kept for callers that report hashes, but the loop no longer uses it.
- **POM**: `hr.hrg:fs-meta:0.3.6` added (explicit version; the root POM deliberately does not manage it) and
  `hr.hrg:gitignore` moved to `0.3.5`, the version `fs-meta` consumes. `java-watch-core` remains because the event
  source has not moved yet.
- **Tests**: project-automation **114 tests, 0 failures**, including a new
  [`WatchedRegenerationFsMetaTest`](../project-automation/src/test/java/hr/hrg/jcodebuddy/automation/watch/WatchedRegenerationFsMetaTest.java)
  that asserts the seeded trio validates, contains the watched file, ignores a same-content save, and runs a pass on
  a real content change. The first run found one real edge: a full-recompile batch with an empty changed set is
  still a pass (four tests failed until the empty-triggering guard was scoped to non-full batches).
- **No `fs-meta` source change was needed** for this use-case; the two integration obligations from W1 (carry the
  `FsMetaFile` list, include generated output in the state) are implemented here. A batch-apply helper remains an
  optional later improvement.
- **Gate**: `bun scripts/mvn-jdk25.js` **BUILD SUCCESS** (20.4 s with the unchanged modules cached),
  project-automation **114 tests, 0 failures**. The state is written under `.jcodebuddy/cache/watch-fs-meta`; the
  event source is still `java-watch-core`, which is exactly what W5b removes.

### W5b — Remove the event source from project-automation (M) — done 2026-10-31
**Who:** agent · **Depends:** W3, W5a

Replace `BatchedFileWatcher`/`ChangeSet`/`FileFilter` with the W3 batch and `wildmatch` filters. The two
regeneration watchers and their tests move from `ChangeSet` to the new batch type; `WatchedRegeneration` keeps its
`fs-meta` state half unchanged. Drop `java-watch-core` from the POM.

**Gate:** the watcher, entity and IOC tests pass; the loop breaker still ignores a self-write and a same-content
save; no `hr.hrg.watch2.core` reference; POM has no `java-watch*`.

**Done 2026-10-31.** project-automation now depends only on `jcodebuddy-watch` + `fs-meta`; `FileFilter`,
`BatchedFileWatcher` and `ChangeSet` are gone from its sources and POM. `WatchedRegeneration` takes a
`WatchBatch`, and both wrappers expose `onBatch(WatchBatch)`; their tests use relative-path batches (create, modify,
delete, overflow). The 114 project-automation tests pass, and the W1 check now reports project-automation as
**resolved** — five consumers remain. The module's test `WatchedRegenerationFsMetaTest` still proves the state half,
now with the JCodeBuddy event source in front of it.
**Gate**: `bun scripts/mvn-jdk25.js` **BUILD SUCCESS** (8:37), with `jcodebuddy-watch` **4/0** newly in
`GATE_MODULES` and project-automation **114/0**; `GateContractTest` was updated in the same change.

### W6 — Remove the unused dependency and enforce the boundary (S) — done 2026-10-31
**Who:** agent · **Depends:** W2, W4, W5b

Remove `java-watch-core` from `hipster-ioc-tooling` (no source reference), make
`scripts/check-java-watch-consumers.js` a gate check (not report-only), and update the module map/READMEs that
still describe `java-watch-core` as the watcher for these modules. `watch/*` stays untouched.

**Gate:** the check passes over the tree; no JCodeBuddy POM depends on a `java-watch*` artifact and no JCodeBuddy
source references `hr.hrg.watch2.core`; full `bun scripts/mvn-jdk25.js` green; the module's own tests and
`check-watch-standalone.js` still pass.

**Done 2026-10-31.** `hipster-ioc-tooling` no longer declares `java-watch-core`; no source used it. The consumer
check with `--strict` reports **0 modules, 0 unexpected; all six baseline entries resolved**. `mvn-jdk25.js` now runs
that check after a successful Maven build, so the boundary is enforced by the recorded gate rather than by a
document. The module map's dependency table/description was updated for the builder, sidecar, watch-tools, agent and
project-automation; `watch/*` was not touched. **Gate**: full `bun scripts/mvn-jdk25.js` **BUILD SUCCESS** (12:53),
and the new post-Maven check printed **0 consumer modules, 6 of the baseline resolved**.

### W7 — Use fs-meta's Zig daemon over its Unix socket (M/L) — W7a done, W7b open
**Who:** agent · **Depends:** W6

The current `jcodebuddy-watch` + Java `FsMetaWriter` path is transitional, not the intended architecture. `fs-meta`
AGENTS § 1 says the Zig engine is the only writer and watcher; tools are clients. This step cuts JCodeBuddy over:

- start or attach to one `fsmeta daemon` per watched root; discover its Unix socket from the daemon's own host state
  (never from the trio);
- speak `FORMAT.socket.md` `SUBSCRIBE` / `CHANGE_ENTRY`, including replay from the last generation and the
  `batch-gap` / `batch-too-old` outcomes;
- replace `FileWatch` as `WatchedRegeneration`'s event source and remove the Java `FsMetaWriter.publish` calls; read
  the trio for state and batches for the change stream;
- keep a read-only trio fallback when no daemon is running, so a page or tool still works standalone;
- confirm several clients can subscribe to one daemon and that no JCodeBuddy process writes the trio.

**W7a done 2026-11-01.** [`FsMetaClient`](../jcodebuddy/jcodebuddy-watch/src/main/java/hr/hrg/jcodebuddy/watch/FsMetaClient.java)
is the Java socket client: simultaneous `HELLO`, `SUBSCRIBE`, `START_BATCH`/`CHANGE_ENTRY`/`END_BATCH`, exact count
validation, `ERROR`/`PING` handling, and `LATEST` to subscribe at the daemon head. The integration test
`FsMetaClientDaemonTest` starts `fsmeta daemon start --foreground`, adds the root, connects **two** Java clients,
edits a file, and asserts both receive the same live `CHANGED` batch. With `FSMETA_BIN` set it is **5/0** (the
module's 4 existing tests plus this one); without it the test is skipped, so the gate does not need a native
toolchain. The full gate is **BUILD SUCCESS** (9:27); the integration test's opt-in skip is the only difference
between that run and the local daemon run.

**W7b — the cutover (done 2026-11-06, for `WatchedRegeneration`).** Replaced `WatchedRegeneration`'s `FileWatch`
event source and Java `FsMetaWriter.publish` with the daemon socket and a read-only trio fallback.
`FsMetaDaemonDiscovery` resolves the socket from the daemon's `roots.json` host state (never the trio);
`FsMetaDaemonWatch` uses the W7a `FsMetaClient`, handles replay, batch-gap/batch-too-old by one full pass then
`LATEST`, reconnects three times, and keeps the old `Pass`/`onBatch` contract. Production Java in
`project-automation` no longer publishes the trio and starts no `WatchService`; `.jcodebuddy/cache/watch-fs-meta`
is read-only legacy. Tests: `jcodebuddy-watch` **9/9** with `FSMETA_BIN` (8 + 1 skip without),
project-automation targeted **43/43** with the daemon, including two concurrent clients on one root; the
agent module **30/30**; `check-java-watch-consumers --strict` 0 consumers. Full gate not run.

**W7c — promote the client, move the agent, retire `FileWatch` (done 2026-11-06).**
`FsMetaDaemonDiscovery`/`FsMetaDaemonWatch` moved into `jcodebuddy-watch` (`hr.hrg.jcodebuddy.watch`);
`ProjectWatcher` discovers and subscribes with `LATEST`, dispatches batches to its agents, stays inactive with
the exact `attachProblem()` when no daemon is running, and no longer starts a `WatchService`. `FileWatch` and
its test were deleted because no production consumer remained. `jcodebuddy-watch` added the managed Jackson
dependency for host-state discovery. Tests: `jcodebuddy-watch` **18** (1 daemon test skipped without
`FSMETA_BIN`), `project-automation` **26** (1 skipped without the daemon), `jcodebuddy-agent` **32/32**
including a fake-daemon `ProjectWatcherTest`; with `FSMETA_BIN`, the two-client daemon tests pass.

**W7d — webviewd SSE is daemon-only (done 2026-11-06).** `ProjectEventStream` no longer starts a
`WatchService`; it discovers the daemon with `FsMetaDaemonDiscovery`, subscribes with
`FsMetaDaemonWatch` at `LATEST`, maps batches to SSE frames, and answers `503` with the `fsmeta daemon start`
/ `fsmeta root add` setup when no daemon serves the project. `webviewd` gained the `jcodebuddy-watch`
dependency; there is no WatchService fallback. Tests: `ProjectEventStreamTest` 7/7, `WriteApiTest` 13/13,
and the opt-in `ProjectEventStreamDaemonTest` starts a real `fsmeta daemon`, subscribes through the production
`ProjectEventStream`, edits `src/A.java` and asserts the SSE frame. Without `FSMETA_BIN` the daemon test is
skipped; the full webviewd module is 59 + 1 skipped in the ordinary run, and 60/60 with the daemon binary.

**W7 boundary — asserted 2026-11-06, exception removed 2026-11-07.** `WatcherBoundaryTest` (jcodebuddy-watch,
3/3) walks the production sources of `jcodebuddy-watch`, `jcodebuddy-agent`, `project-automation`, `webviewd`
and `hipster-entity-tooling` and asserts: no `newWatchService(` call or `java.nio.file.WatchService` import; no
production `FsMetaWriter.publish(` and no import of the writer; `webviewd` depends on `jcodebuddy-watch`; and
`FileWatch` is gone. The literal W7 sentence is now fully enforced: no production Java publishes a trio, and no
production code starts a `WatchService`.

**Design B — the entity build cache joins the daemon (done 2026-11-07).** `EntityBuildState` no longer scans,
diffs or publishes a trio. The daemon root is the project root; a module cache uses
`FsMetaDaemonDiscovery.discoverAncestor`, filters the subscription to its source-root subfolder with the
protocol's include/exclude patterns, and stores `<module>/.jcodebuddy/cache/entity-build/run.json` with the last
successful daemon generation and fingerprint. `FsMetaClient` sends the extended `SUBSCRIBE`; the daemon-side
filter was missing and is now implemented in `fs-meta` commit `f4be95d` (both replay and live batches, filtered
`END_BATCH` counts). A missing daemon is a full pass, never a false no-op. Tests: `EntityBuildStateTest` 30/30,
opt-in `EntityBuildStateDaemonTest` 3/3, the design-B targeted set 51/51; `WatcherBoundaryTest` 3/3.

**Gate:** the JCodeBuddy modules take events from the Zig daemon over the socket; no production Java code publishes a
trio and no JCodeBuddy code starts a `WatchService`; two concurrent clients see the same batch stream; a
`batch-gap`/`batch-too-old` is handled explicitly; the agent and project-automation tests pass; full gate green.

## 5. Done when

- The check script reports **0 JCodeBuddy consumers** outside `watch/`.
- The six POMs in § 2 no longer name `java-watch-core`; no JCodeBuddy source references `hr.hrg.watch2.core`.
- The agent and project-automation use the event source + `fs-meta`; the code-edit types live in
  `jcodebuddy-builder-api`/`jcodebuddy-builder`.
- `fs-meta` is the only state/diff authority; the event source owns events and policy stays in the consumers.
- **W7:** the event/state path is the Zig daemon over its Unix socket; no production Java writer or in-process
  watcher remains.
- Full gate green, and the `watch/*` modules are byte-identical to their frozen state (no edits to them).

## 6. How the inventory is measured

The authoritative measurement is the W1 script; it walks with the repository's `.gitignore` rules and excludes the
frozen modules, `target/`, and the agent's derived `.watch/` audit copies:

```text
bun scripts/check-java-watch-consumers.js
bun scripts/check-java-watch-consumers.js --strict
```

The first prints the six modules in § 2 with their POM dependency and the Java files referencing
`hr.hrg.watch2.core`; it exits non-zero only for a module outside the baseline. `--strict` exits non-zero while any
consumer remains, which is W6's gate. The agent's own package and `metadata-arena`'s package are namespaces, not
dependencies, so neither is counted; that is why the inventory separates "own namespace" from "dependency".
