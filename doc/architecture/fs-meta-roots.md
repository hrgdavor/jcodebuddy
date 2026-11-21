# fs-meta roots: the tool audit

**Status:** audit and proposed tool contract, 2026-11-19. **fs-meta needs no change:** it already supports one
root per project or one per subproject (nested roots guarded), and the decision is that **tools resolve their own
serving root** — from `roots` while a daemon runs, from `roots.json` when it does not — and choose where their own
metadata lives. There is deliberately no `root.which` and no path-resolving operation; the client-side contract is
[`doc/root-discovery.md`](https://github.com/hrgdavor/fs-meta/blob/main/doc/root-discovery.md) (sibling checkout:
`D:/wrk/core/fs-meta` today). What follows is what the JCodeBuddy tools do today against that contract, and what is
still open **on the tool side**. The compiler changes are **not implemented**.

## Why the audit exists

fs-meta supports both layouts, and both are legitimate:

- **one root for the project** — `fsmeta root add <project>`; every module is a subfolder of that root;
- **one root per subproject** — `fsmeta root add <project>/module-a`, `…/module-b`, …; the parent directory may not be
  a root at all, or may be a root *and* have root children.

Nested roots are guarded: a parent's scan excludes a child root, so the parent's change stream does **not** contain the
child's files (`FORMAT.daemon.md` § 4, `doc/daemon.md`). A tool that assumes "the project root is the root, and its
stream contains every module" is therefore wrong under either of the second layouts — and JCodeBuddy has that
assumption in its compiler.

The tool contract, in one line: **resolve a path to its serving root, then use that root's socket, filter and
generation** — never assume the project root is a root, and never compare generations across roots. The full rule is
fs-meta's `doc/root-discovery.md`; the parts JCodeBuddy needs are summarised here.

## What the audit found

| consumer                                                                       | discovery today                                                                                   | under module roots                                                                          | verdict                                                                                  |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `FsMetaDaemonDiscovery`                                                        | exact (`discover`) and deepest ancestor (`discoverAncestor`) over `roots.json`; segment-safe, Windows case rule, nested-ancestor tests | resolves the deepest root correctly                    | correct — and it is the model: fs-meta deliberately has no `root.which`; clients resolve |
| `FsMetaDaemonState` / `EntityBuildState` (entity generator)                    | `discoverAncestor(sourceRoot)`, then a server-side filter relative to that root                   | each module's source root finds its own root; the state records that root                   | works for module roots; its javadoc now says serving root, not project root              |
| `EntityWatchLoop`, `WatchedRegeneration`, the generators' `--watch`            | same discovery per source root, trio fallback without a daemon                                    | per-module passes work                                                                      | works; messages say "the project root"                                                   |
| `IncrementalBuildService` (IntelliJ plugin)                                    | **one** `daemonRoot = discoverAncestor(projectRoot)`; one subscription; `filterFor(daemonRoot)` skips every source root not under it; batches resolve against it; one batch marker | **loses modules** (see below) | the compiler chooses one project root: a documented limitation, not a protocol gap |
| `BatchStateStore` (plugin)                                                     | one `daemonRoot` + one batch in one file                                                          | meaningless across roots                                                                    | needs `(root, generation)` per root                                                      |
| `FsMetaVfsWatcher` (plugin VFS watcher)                                        | one endpoint per daemon root, a subscription per endpoint, per-root filters                       | already multi-root                                                                          | the model the build service should copy                                                  |
| `ProjectEventStream` (`webviewd` SSE)                                          | exact `discover(project)`; filter `**/*` minus noise                                              | a project directory inside a root, or a project with only module roots, attaches to nothing | decision needed: exact-only, or ancestor + a `project/**` filter                         |
| `ProjectWatcher` (`jcodebuddy-agent`)                                          | exact `discover(root)`                                                                            | same as above                                                                               | same decision needed                                                                     |
| `project-automation/tools/daemon.js` (the peer playground)                     | `root add <project>`                                                                              | one root only                                                                               | fine for the playground; a scaffold must allow per-module adds                           |
| docs: `incremental-build.md`, the plugin README, `incremental-compiler-intellij.md`, `EntityBuildState` javadoc | "a project needs one root, not one per module", "add this project root", "two modules share one root" | state the opposite of the contract                     | update to the contract (done in this change)                                             |

### The compiler failure, concretely

`IncrementalBuildService` starts with `discoverAncestor(projectRoot)` and then filters the project's source roots to
the ones **under that root**:

```java
// IncrementalBuildService.filterFor(daemonRoot)
for (SourceRoot sourceRoot : sourceRoots) {
    if (!sourceRoot.root().startsWith(daemonRoot)) continue;   // a sibling root is silently skipped
    ...
}
```

and `apply()` resolves a batch's names with `daemonRoot.resolve(name)`. Under a multi-root project:

1. **Modules are roots, project directory is not a root** — `discoverAncestor(projectRoot)` finds the first
   *ancestor* root (a monorepo root, or nothing). If it finds one, that parent's stream **excludes the nested module
   roots**, so the module source roots under it never see a batch; if it finds none, there is no watch at all.
2. **Project root is a root and modules are also roots** — the parent's guarded scan excludes the module roots, so
   every file under a module root is invisible to the one subscription. The hash-based compile still produces classes,
   but no change is ever delivered for those modules.
3. **One root at the project root, no module roots** — works today; it is the layout the docs recommend.

A single `fromBatch` is also meaningless across roots: each root numbers its own generations, so replay, resume and
the nudge marker must be per root.

**The playground is not this case.** The compiler's unit is the IntelliJ **project root**, not a source root: it
subscribes to the deepest root serving the project and filters the project's source roots. The playground is now a
**peer checkout** of this one (`D:\wrk\java\jcodebuddy_proto\entity-ioc-playground`) with its own fs-meta root (`f8b3ed4b`); opened
as the IntelliJ project, the compiler resolves that root and filters `src/main/java/**` — one root, one
subscription, correct. It also demonstrates the local-development wiring: local SNAPSHOTs via `install-libs`, and
the JCodeBuddy checkout as a `link:` dependency in `package.json` until the page toolkit is published. The fan-out
below is needed only when a project's source roots span several serving roots.

## If the compiler chooses to support module roots

The current choice — **one project root** — is valid and is what the docs now state. A tool that wants per-module
roots resolves and attaches per serving root instead: **per source root, resolve; per serving root, subscribe;
merge the results; keep state per root.**

1. **Group the source roots.** For each `SourceRoot`, resolve `discoverAncestor(sourceRoot.root())`; group the roots
   by the returned daemon root. A source root under a module root belongs to that module root; one under the project
   root (outside every module root) belongs to the project root.
2. **One subscription per daemon root**, `FsMetaDaemonWatch` with includes for the source roots in that group only
   (relative to that root), the existing `**/out/**`, `**/target/**`, `**/.jcodebuddy/**` excludes.
3. **One queue tagged with its root.** A batch is `(root, WatchBatch)`; when it is applied, resolve names against
   `root`, and use the generation of **that** root's subscription. The compile set is the union of the batches from
   all roots; the workers can be one (the compiler is shared) but the queue must not lose which root a name belongs to.
4. **Per-root batch state.** `BatchStateStore` stores a map `root → { generation, sourceSignature }` (the existing
   single-root file is read as the map with one entry, and a changed root refuses as it does today). `startWatch`
   replays each root from its own marker, or `LATEST` when it has none. The tool socket's state reports the roots
   array, not one root.
5. **Lifecycle.** Pause stops every subscription; Start Watch rediscovers all groups; a reconnect re-resolves every
   source root and re-attaches what changed; a root that disappears is reported and its group is skipped, not guessed.
6. **No new watcher.** The per-root subscriptions are the same `FsMetaDaemonWatch`; the daemon remains the only
   watcher (fs-meta `AGENTS.md` § 1).

`FsMetaVfsWatcher` already does 1–3 for the VFS watcher and is the shape to copy.

### Host and agent: exact or ancestor?

`webviewd`'s `ProjectEventStream` and `jcodebuddy-agent`'s `ProjectWatcher` use the **exact** locator on purpose: the
host serves one project directory and must not silently attach to a parent's daemon. That is right for a directory
that is a root, and wrong for a subdirectory of a root. With the multi-root layouts now supported, the options are:

- **(a) require the served directory to be a root** — keep exact discovery, document it, and let a project register
  the module it opens (the playground does this);
- **(b) ancestor + a project filter** — discover the deepest root containing the project directory, filter the stream
  to `project/**` (plus the existing noise excludes), and log the root it attached to; the "must not silently attach to
  a parent" concern is answered by the filter and the log, not by refusing to attach;
- **(c) exact first, then (b) with a warning** — the least surprising for an existing single-root project, and it
  still works when the directory is not a root.

This needs a decision before code; it is a host/agent contract, not a compiler detail. The recommendation is **(b)**
for a host that serves one directory: the filter is what confines the stream, and a project opened as a subdirectory
of a larger checkout is the common case the current exact check rejects.

## What to change in JCodeBuddy

1. **`IncrementalBuildService`** — the grouping and per-root subscriptions above, with `(root, batch)` and per-root
   generations; the tool socket's `build.status` reports the roots. **P1.**
2. **`BatchStateStore`** — per-root map, backward-compatible read of the single-root file. **P1.**
3. **Tests** — a fake daemon registry (like `FsMetaDaemonDiscoveryTest`) for two roots with a project root that is
   not a root, a project root with nested module roots, one missing root, and a batch from each root compiling only
   its own files. **P1.**
4. **`ProjectEventStream` / `ProjectWatcher`** — the (a)/(b)/(c) decision, then the filter and the log. **P2.**
5. **Docs** — this page; the "one root per project" sentences in
   [`doc-hipster-entity/user/incremental-build.md`](../../doc-hipster-entity/user/incremental-build.md),
   [`doc/incremental-compiler-intellij.md`](../incremental-compiler-intellij.md), the plugin README and the
   `EntityBuildState` javadoc. **P1**, done with this audit where the statement is simply wrong under the contract.
6. **`project-automation/tools/daemon.js`** — keep `root add <project>` for the playground, but a scaffold should
   accept one path per module (or document that one project root serves every module). **P3.**

## Open questions

- **Auto-follow or require one root?** Both layouts are valid; the compiler should follow whatever is registered. The
  question is whether a tool may *create* the per-module roots it wants, or only use those a person added. Today tools
  read; only `daemon.js`/`root add` writes. Keeping "tools do not register roots silently" is the safer default.
- **A source root under no root.** Generated sources, a dependency source root from a jar, a source root outside the
  project directory: skip it, report it in the log, and let the hash compile cover it. It must not make the tool
  refuse every other root.
- **A root added while the IDE is open.** There is no registry watch; the existing Reconnect action re-runs discovery,
  and Start Watch rediscovers. A file watcher on `roots.json` would be a second watcher; not planned.
- **Unstable identities.** The root id is derived from the canonical path; a moved project is a different root. State
  keyed by the root path therefore refuses after a move (as it does today); a map keyed by id would have the same
  problem. Recording both and reporting the mismatch is the honest behaviour.
- **A missing root.** A client that resolves a `missing` record should report "registered but its path is gone" and
  not fall through to a parent; that is client behaviour, and no daemon change is needed.

## See also

- fs-meta's tool contract: [`doc/root-discovery.md`](https://github.com/hrgdavor/fs-meta/blob/main/doc/root-discovery.md).
- fs-meta's daemon reference: `FORMAT.daemon.md` § 1b (the durable list), § 4 (nested roots, the control protocol).
- JCodeBuddy's discovery: [`FsMetaDaemonDiscovery`](../../jcodebuddy/jcodebuddy-watch/src/main/java/hr/hrg/jcodebuddy/watch/FsMetaDaemonDiscovery.java)
  and the multi-root VFS watcher, [`FsMetaVfsWatcher`](../../webview/webview-intellij-incremental/src/main/java/hr/hrg/jetbrains/jcodebuddy/incremental/FsMetaVfsWatcher.java).
