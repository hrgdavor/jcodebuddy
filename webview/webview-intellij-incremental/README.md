# JCodeBuddy Incremental Build

An IntelliJ IDEA plugin that replaces IntelliJ's Java auto-build with the repository's fs-meta + ECJ
incremental compiler.

The plugin bundles:

- `jcodebuddy-compile` and its **ECJ 3.46.100** dependency;
- `jcodebuddy-watch`, the fs-meta daemon client;
- `jcodebuddy-ipc`, the live tool socket contract (DEC-053).

Compiler diagnostics are published directly by an `ExternalAnnotator`; there is no JSON hand-off for editor
squiggles. The last diagnostic set is also persisted as a small project-output TSV so IntelliJ can restore
the annotations after a project restart.

> This plugin is not part of the Maven reactor and not part of `GATE_MODULES`. It is a Gradle/IntelliJ
> Platform project under `webview/`, like `webview-jetbrains`.

## What it does

The plugin is installed for the IDE, but the build loop is **enabled per project**. Open
`Settings | Tools | JCodeBuddy Incremental Build` and check **Enable the fs-meta + ECJ incremental build
for this project**. The setting is stored in the project (`.idea/jcodebuddy-incremental.xml`), so another
project can keep it disabled. The second checkbox there, **Publish the live tool socket in .jcodebuddy/ipc
(DEC-053)**, controls the socket independently of the build and is on by default while the project setting is
enabled.

On project open, when that project setting is enabled, the plugin:

1. collects the project's Java source roots and module compiler outputs from IntelliJ, across all modules;
2. creates one `EcjIncrementalCompiler` per source root, writing into that module's existing compiler output;
3. loads the persisted **source-content hash map** of files that compiled successfully;
4. compares every current `.java` hash against it, plus missing output classes, and compiles only the
   changed/missing files;
5. discovers the fs-meta daemon and subscribes at its current head so later changes are watched;
6. publishes compiler diagnostics and restarts highlighting.

The fs-meta batch marker positions the subscription, but the **hash map is the startup authority**. A false
fs-meta change whose content hash matches the compiled generation is logged and ignored. Only the explicit
**Rebuild** button performs a full compile; the first run with no hash state is the one unavoidable bootstrap
compile.

On a successful compile the diagnostics for that source root are replaced by an empty list, so the next
highlighting pass removes the old red errors. IntelliJ's own **Build** is not invoked by this plugin.

The last diagnostic set is persisted in
`<project>/.jcodebuddy/cache/incremental-build/diagnostics.tsv` and restored when the project opens.
IntelliJ does not persist external-annotator output itself, so this is what restores the markers when the
resumed fs-meta batch contains no new compile. Disabling the project setting clears the saved diagnostics.

## Requirements

| what                       | why                                                                                            |
| -------------------------- | ---------------------------------------------------------------------------------------------- |
| JDK 25                     | the plugin and the compiler are compiled for Java 25                                           |
| IntelliJ IDEA 2026.2+      | the Gradle project uses the 2026.2 platform line (`pluginSinceBuild = 262`)                    |
| fs-meta daemon             | it owns watching, hashing and batching                                                         |
| local JCodeBuddy artifacts | Gradle resolves `hr.hrg.jcodebuddy:jcodebuddy-compile`, `jcodebuddy-watch` and `jcodebuddy-ipc` from Maven local |

Install the three Maven artifacts once after changing them:

```sh
mvn -o -pl :jcodebuddy-compile,:jcodebuddy-watch,:jcodebuddy-ipc -am install -DskipTests
```

The plugin build resolves the local Maven repository, so the installed artifacts are what gets bundled.

## Build the plugin

From this directory:

```sh
./gradlew buildPlugin
```

On Windows:

```text
gradlew.bat buildPlugin
```

The installable archive is:

```text
build/distributions/JCodeBuddy Incremental Build-0.1.0.zip
```

Install it with `Settings | Plugins | ⚙ | Install Plugin from Disk...`, then restart IntelliJ.

### Verify the bundle

The distribution should contain the compiler and watcher jars:

```text
JCodeBuddy Incremental Build/lib/ecj-3.46.100.jar
JCodeBuddy Incremental Build/lib/jcodebuddy-compile-1.0-SNAPSHOT.jar
JCodeBuddy Incremental Build/lib/jcodebuddy-watch-1.0-SNAPSHOT.jar
JCodeBuddy Incremental Build/lib/jcodebuddy-ipc-1.0-SNAPSHOT.jar
```

## Enable it per project

Start the fs-meta daemon first (next section). If the project is already open when the daemon starts,
click **Reconnect fs-meta** in the build-log toolbar.

1. Open the project in IntelliJ.
2. `Settings | Tools | JCodeBuddy Incremental Build`.
3. Check **Enable the fs-meta + ECJ incremental build for this project**.
4. Apply. The service starts immediately; no IDE restart is required.

The flag is persisted in `.idea/jcodebuddy-incremental.xml`. Unchecking it stops the watch loop and clears
the plugin's diagnostics for the project. The plugin can be installed globally while remaining disabled for
projects that do not want it.

## Start the daemon

The plugin does not start fs-meta itself. Register the IntelliJ project root before opening the project
(or use the daemon already serving it):

```sh
fsmeta daemon start
fsmeta root add /path/to/project --tick 25
```

`--tick` is per root; 10–25 ms suits a fast local editor, 50–100 ms is safer for many roots or slower
storage. The plugin never starts a Java `WatchService`.

Register **one root that contains the project's source roots** (the project/aggregator root). The plugin currently
discovers one root for the project; per-module (nested) roots are not attached yet — see
[`doc/architecture/fs-meta-roots.md`](../../doc/architecture/fs-meta-roots.md).

If no daemon is found, or the daemon is running but this root is not tracked, the plugin logs and notifies
the exact setup commands:

```text
Command 1 (start the daemon): fsmeta daemon start
Command 2 (add this project root if it is not tracked): fsmeta root add "<project>" --mode always --idle 0 --tick 25
Then click Reconnect fs-meta.
```

The plugin still performs the hash-based compile and restores diagnostics; only live watching is missing.
After running the commands, click **Reconnect fs-meta** — no IDE restart is needed.

## Single-watcher mode

IntelliJ's native VFS watcher cannot be disabled per project. To make IntelliJ depend on this plugin's fs-meta
stream instead, add this global VM option and restart:

```text
-Didea.filewatcher.disabled=true
```

Use **Help | Edit Custom VM Options**. The plugin registers `FsMetaVfsWatcher` as a
`vfs.local.pluggableFileWatcher`; it subscribes to fs-meta for the VFS roots and reports changed/deleted paths
through `FileWatcherNotificationSink`. Roots without a daemon are registered for manual refresh and the bridge
retries every 2 seconds until the daemon appears. With the flag set, the build service also refreshes every
batch's **changed/deleted files only** — the exact set, filtered to the project, non-recursive — plus the
**parent folder** when a file was added or removed, so the parent's child list updates. It uses IntelliJ's
**default IO refresh** (`LocalFileSystem.refreshIoFiles`), so a generated file or a source root the VFS bridge
did not register still appears in the editor and the Project view without a manual *Reload from Disk*.

This option is global: every project in that IDE then relies on fs-meta for native file-change notifications.
Keep the plugin installed while it is enabled. Without the option both watchers run and the plugin logs this
warning at startup in both the JCodeBuddy Build log and `idea.log`, with the exact opt-in flag:

```text
JCodeBuddy fs-meta VFS watcher is active, but IntelliJ's native VFS watcher is also running.
To opt in to using only fs-meta, add -Didea.filewatcher.disabled=true to
Help | Edit Custom VM Options, then restart IntelliJ. This option is global for every project in this IDE.
```

Duplicate VFS notifications are harmless, but the duplicate watch effort remains until the flag is set.

## Disable IntelliJ's build

The plugin owns the compiler output, so IntelliJ must not compete with it:

1. `Settings | Build, Execution, Deployment | Compiler`
2. Uncheck **Build project automatically**.
3. In every Run/Debug configuration, open **Before launch** and remove the **Build** step.

IntelliJ remains the editor, test runner and debugger. Its own inspections still run; the plugin's
diagnostics are additive and are cleared when the external compile succeeds.

## Diagnostics and editor errors

The plugin registers:

- `IncrementalBuildService` — the compile/watch loop and diagnostic publisher;
- `JcodeBuddyDiagnosticsAnnotator` — an `ExternalAnnotator` that reads the service's in-memory
  diagnostics for the current Java file and adds `HighlightSeverity.ERROR` annotations;
- `JcodeBuddyStartupActivity` — starts the loop on project open.

There is no intermediate JSON. The service is the hand-off:

```text
EcjIncrementalCompiler.lastErrors()
        │
        ▼
IncrementalDiagnosticsService (in memory)
        │
        ▼
JcodeBuddyDiagnosticsAnnotator → IntelliJ editor
```

After each compile the service calls `DaemonCodeAnalyzer.restart()`. A failed compile adds annotations;
a successful compile replaces that source root's diagnostics with an empty list, which removes them.

Line numbers are 1-based in the compiler output and converted to zero-based editor offsets by the
annotator. `column: 0` means "whole line".

## Logs

There are two places to look:

1. **View | Tool Windows | JCodeBuddy Build** — an in-IDE log with the current session's events:
   source-root discovery, module classpath counts, fs-meta daemon/socket/filter, batches, compile results
   and failures. Every line has a full date/time. Its toolbar has:
   - **Rebuild** — compile every source root again;
   - **Stop Watch** — drop the fs-meta subscription so an external Maven run can rewrite sources and class
     outputs; the compile loop, the compilers and the batch marker stay as they are;
   - **Start Watch** — subscribe again and replay every batch recorded while the watch was stopped;
   - **Reconnect fs-meta** — drop the current subscription and retry daemon discovery;
   - **Detailed Classpath** — write every classpath entry for every module to the log on demand;
   - **Refresh Roots** — re-scan IntelliJ's module model after a branch/submodule change;
   - **Refresh Errors** — re-run editor highlighting from the current diagnostics and clear stale
     Project-view problem marks, without compiling;
   - **List Roots** — log every currently available source root and output;
   - **Open Log Folder** — open `.jcodebuddy/cache/incremental-build/` in the system file explorer;
   - **Clear Log** — clear both the pane and `build.log`.

   **Stop Watch** and **Start Watch** are a pair: **Start Watch** is enabled only while the watch is
   stopped, and **Stop Watch** only while it runs. [Running Maven with the watch stopped](#running-maven-with-the-watch-stopped)
   is the sequence they exist for.

   Source roots also refresh automatically when IntelliJ reports a module-root change, so switching to a
   branch that adds/removes a submodule does not require a manual rebuild. A manual **Refresh Roots** is
   available if the project model change is not delivered.

   Normal startup logs only counts such as
   `Classpath [module]: entries=277, jars=240, dirs=35, missing=2`. The durable log file
   `<project>/.jcodebuddy/cache/incremental-build/build.log` retains all lines after an IDE restart.

2. **`idea.log`** — every line is also written through IntelliJ's `Logger`. Use
   **Help | Show Log in Explorer** (Windows) and search for `JCodeBuddy`.

The service logs:

```text
Starting incremental build for <project>
Hash-driven compile (startup): N changed, M deleted
fs-meta false positive: <path> has the same hash as the compiled generation; ignored
Identical compiled hash; skipped ECJ write and cleared stale diagnostic: <path>
Source root [module] <path> -> <output>
Classpath [module]: entries=277, jars=240, dirs=35, missing=2
Full compile: N source root(s)          # only explicit Rebuild
Compiling [module] N file(s) from <root>
Compile OK [module] <root>
Compile FAILED [module] N diagnostic(s); first: ...
Saved fs-meta batch marker 1234 -> ...
Watch paused at batch marker 1234: fs-meta changes will not trigger a compile until Start Watch
Watch resumed: replaying fs-meta from batch marker 1234
fs-meta daemon root: ...
fs-meta socket: ...
fs-meta filter: ...
Batch: changed=[...] deleted=[...]
Source roots changed: 12 -> 14
Refreshing error markers (no compile)
Seed hashes from existing classes in new source root(s)
Available Java source roots (14):
  MAIN [module] <root> -> <output>
  TEST [module] <root> -> <output>
```

`Source roots changed` and the `Available Java source roots` list are also the quickest way to verify a branch
or submodule update. **List Roots** prints the current list on demand.

`Hash-driven compile` is the normal startup path. `Full compile` is emitted only by the explicit
**Rebuild** button. `Restored N diagnostic(s)` means the previous compile's error annotations were loaded
from the diagnostics state file. Normal classpath lines show counts; use **Detailed Classpath** when the
per-entry `JAR`/`DIR`/`MISSING` list is needed. Source roots are refreshed from IntelliJ's module model
automatically; **Refresh Roots** is the manual fallback and **List Roots** prints the current roots. If an
editor marker looks stale, **Refresh Errors** restarts highlighting without invoking the compiler or rebuilding.

### Project-view problem marks

The red wavy underlines the Project view puts under a file, package or module name are **not** editor
highlighting. IntelliJ keeps them in `WolfTheProblemSolver`, normally written by the IDE's own build, and the
Project view re-renders when that changes. They survive a successful external compile and a daemon restart,
which is why a clean build can still leave a repository looking broken.

This plugin replaces the IDE build, so it also owns that state: every successful compile clears the marks of
the files it compiled, and **Refresh Errors** clears every marked `.java` file under this project's source
roots. Files belonging to other compilers (Kotlin, JavaScript, …) are left alone. The log shows:

```text
Clearing 3 stale Project-view problem marker(s)
```

A full **Rebuild** clears all of them, because it compiles every source root. If a mark belongs to a file
outside the plugin's source roots, it is not this plugin's to clear; use the IDE build or that file's own
compiler once.

A missing daemon, a source root outside the daemon root, or a compile failure is visible in both places.
By default the log reports per-module classpath **counts**. Press **Detailed Classpath** to append each
entry as `JAR`, `DIR`, or `MISSING`; use that when a Maven dependency appears unresolved. IntelliJ reports
a JAR root as `...jar!/`; the plugin strips that virtual suffix and logs the physical `.jar` path.

The log also prints the IDE heap maximum and current use at startup. If the build reports
`OutOfMemoryError`, increase the IDE heap (`Help | Change Memory Settings`); the compiler now shares one
lazy JAR/JDK index across the whole project and keeps only a bounded class-byte hot cache, so a large
reactor should not multiply dependency caches per module.

## Running Maven with the watch stopped

A Maven run that generates sources or writes class files competes with the watch: every generated file
arrives as an fs-meta batch and is compiled while Maven is still writing it. The two watch buttons bound
that window:

1. **Stop Watch** — the fs-meta subscription is dropped. The compile loop, the compilers, the diagnostics
   and the batch marker stay as they are, so nothing is lost; generations published while stopped are
   replayed later.
2. Run Maven (`mvn generate-sources`, a full `mvn install`, …).
3. **Rebuild** — compile every source root against the new sources. This is the one action that compiles
   everything and is not driven by fs-meta batches.
4. **Start Watch** — subscribe again from the last applied batch marker. **Rebuild** saves the current
   daemon head when it can reach the daemon, so in the normal sequence there is nothing to replay; if the
   daemon was unreachable, the batches published during the pause are replayed and files whose content hash
   already matches the compiled generation are skipped without an ECJ write.

While the watch is stopped, IntelliJ's own VFS still receives the changes through the fs-meta VFS bridge,
so editors reload what Maven rewrote; only the compile loop is paused. The pause is in memory: reopening
the project starts watching again, and disabling the project setting clears it.

## Live tool socket (DEC-053)

While the project setting is enabled — and the second checkbox, **Publish the live tool socket in
.jcodebuddy/ipc (DEC-053)**, is on — the plugin publishes its state and actions on a Unix domain socket:

```text
<project>/.jcodebuddy/ipc/jcodebuddy-incremental-build.sock
<project>/.jcodebuddy/ipc/jcodebuddy-incremental-build.json   the descriptor
```

Protocol: newline-delimited JSON-RPC 2.0. No TCP port and **no token**: the socket is a file the operating
system scopes to your user. The full contract (paths, descriptor fields, error codes, the second-instance
rule) is [`jcodebuddy-ipc/README.md`](../../jcodebuddy/jcodebuddy-ipc/README.md) and DEC-053.

Every endpoint answers `tool.info` (its descriptor). This tool adds:

| method                                       | answers                                                  |
| -------------------------------------------- | -------------------------------------------------------- |
| `build.status`                               | enabled/running, watch state, batch marker, daemon root/socket, failed files, roots, diagnostic counts, log file/size/next |
| `build.log`                                  | `{since?, limit?}` → `{lines: [{sequence, text}], next}`; the cursor is the sequence, so a poller asks for new lines only |
| `build.diagnostics`                          | `[{file, line, column, message}]`                        |
| `build.roots`                                | `[{module, root, output, test}]`                         |
| `build.rebuild`                              | accepted; compiles every source root on the worker       |
| `build.watch.stop` / `build.watch.start`     | accepted; the Stop Watch / Start Watch buttons           |
| `build.refreshErrors` / `build.refreshRoots` | accepted; the same actions as the toolbar                |
| `build.listClasspath`                        | accepted; writes the detailed classpath to the build log |

Example (any JSON-lines client works):

```text
{"jsonrpc":"2.0","id":1,"method":"build.status"}
```

The log is also **pushed**. The endpoint declares the `log` topic, and `tool.subscribe` with
`{"topic":"log","since":<sequence>}` turns that connection into `tool.event` notifications — one per line, as
it is written — so a consumer does not poll. `since` is the last line already seen (absent means "from now"),
and the retained backlog is replayed under the same lock the log writes take, so no line falls in the gap. A
consumer that falls too far behind is ended with `tool.event-error` (`reason: overflow`) rather than silently
skipped, and re-reads the missing lines with `build.log`. `build.status.log.last` is the newest sequence.

The socket stops with the loop: disabling the project setting, or quitting the IDE, removes the socket and
the descriptor. If another IDE window already owns the socket for this project, this one does not bind — it
logs the occupant and leaves that endpoint alone (`BusyException`), because two answers for one project leave
a consumer no rule for choosing. The startup log names the socket, its descriptor and its methods.

### The page

`project-automation/compiler-console/` is the default solo page for this tool: the same log and buttons,
reached over a host's proxy of the socket, with the log **pushed over SSE**. Build it into the project's
derived output:

```sh
bun project-automation/compiler-console/src_build/build.js --link-base .
```

Then open `/app/incremental-build.html?token=<host token>` on a host that serves DEC-053's proxy: `webviewd`,
the JetBrains **WebView Explorer** host (its HTTP bridge), or the standalone Bun metadata server
(`project-automation/metadata`), which reads the token from `.jcodebuddy/webview/token`. The token is the
host's; without it the page explains why it cannot call the tool instead of rendering an empty log.

## Batch marker

The last successfully applied fs-meta generation is stored at:

```text
<project>/.jcodebuddy/cache/incremental-build/batch.properties
```

It records the batch number, daemon root and a source-root/output + classpath signature. It is written
atomically only after a compile succeeds and the failed-file set is empty. The batch marker is the
subscription position and a diagnostics/state reference; the compiled-hash map is what decides which files
need compiling. A failed batch retries its files on the next batch instead of rebuilding the project.

The **Rebuild** toolbar button deliberately performs a full compile and then saves the current daemon head.
**Reconnect fs-meta** and **Start Watch** keep the saved marker and resubscribe from it.

## Compiled hashes

The authoritative state is:

```text
<project>/.jcodebuddy/cache/incremental-build/compiled-hashes.tsv
```

It stores a SHA-256 of every successfully compiled `.java` file, plus a layout/classpath signature. Startup
compiles only files whose hash differs from this map (or whose primary `.class` is missing), and removes
classes for deleted sources. If the hash state is absent but existing class outputs are present, those hashes
are seeded first, so an upgrade does not rewrite every identical class. The fs-meta batch marker only
positions the subscription; it cannot cause a full rebuild on its own.

If a file's hash equals the last successful compile and its class exists, ECJ is not invoked at all. Even when
ECJ is invoked for another file, the class writer compares the produced bytes with the existing class and
leaves a byte-identical file untouched in incremental mode, so external output watchers do not see a change.
Stale diagnostics for an unchanged file are cleared and highlighting is refreshed. Only **Rebuild** enables
rewriting identical class files.

If fs-meta reports a changed file whose current hash equals the compiled hash, the plugin logs:

```text
fs-meta false positive: <path> has the same hash as the compiled generation; ignored
```

and does not invoke ECJ for it. A false deletion for a file that still exists is logged similarly.

A failed batch updates hashes only for files that actually compiled, and remembers only the files named by
ECJ diagnostics, not the whole changed batch. A file is retried only when its content hash or missing class
output requires compilation. If a failed file returns to the last successfully compiled hash, the plugin
clears the stale diagnostic and **skips ECJ entirely**, so no class file is rewritten for an external watcher.
Only the explicit **Rebuild** button rewrites identical outputs.

## Multi-module projects

The plugin uses IntelliJ's modules directly:

- every Java source root of every module gets its own compiler;
- each compiler writes to **that module's** compiler output;
- each module gets its **own** classpath (`OrderEnumerator.orderEntries(module)`);
- initial compilation follows module dependency order, so a dependency module is compiled before a
  module that uses its output.

**Current limitation:** the plugin discovers one fs-meta root for the project (the deepest root containing the
project directory) and filters every module's source root to it. Register the **project/aggregator root**, not one
root per Maven module: a nested module root is excluded from its parent's guarded stream, and a project directory
that is not itself a root has no stream of its own. The compiler needs one subscription per serving root before
that layout works; the audit and the fix are in
[`doc/architecture/fs-meta-roots.md`](../../doc/architecture/fs-meta-roots.md).

A batch that changes several modules is compiled in module dependency order. A change in module B does
not automatically recompile an unchanged dependent module A; that is the same no-dependency-graph
limitation as the standalone example.

## Configuration limits

- **Source roots and output** come from the IntelliJ project. If a module has no compiler output path,
  the plugin falls back to `out/production/<module>` or `out/test/<module>`; set the module output in
  `Project Structure` if a run configuration must see it.
- **Classpath** is built per module from that module's `OrderEnumerator.orderEntries(module)` and
  `OrderRootType.CLASSES`; all module output directories are included too. The JDK is resolved by
  `jcodebuddy-compile`.
- **No classloader reload**: unlike `examples/fsmeta-reload`, the plugin has no project-only classloader;
  IntelliJ loads the compiled classes when the project's run configuration starts.
- **No dependency recompilation**: only files in the fs-meta batch are compiled. A changed signature that
  requires recompiling an unchanged dependent is outside this plugin's scope.
- **Generated sources** are compiled only if their source root is part of the IntelliJ module.
- **Automatic daemon retry**: if the daemon is started after the project is opened, the plugin retries
  discovery/subscription with backoff (2s up to 30s). **Reconnect fs-meta** forces an immediate rediscovery.
  The plugin prints the exact `fsmeta daemon start` and `fsmeta root add` commands when the daemon/root is missing.
- **Memory**: the plugin shares one dependency index across all source roots. JAR class bytes are read on
  demand from the JAR and limited to a bounded hot cache; the JARs themselves stay on disk. The initial
  full compile is chunked (default 64 files, `-Djcodebuddy.intellij.initialCompileChunk=<n>`) so the ECJ
  AST peak is bounded. The IDE heap maximum is logged at startup and after compiles. Large projects may
  still need a higher IDE heap via `Help | Change Memory Settings`.

## Development

```sh
./gradlew runIde      # sandbox IDE with this plugin
./gradlew compileJava # fast compile check
./gradlew buildPlugin # distributable zip
```

The build is a separate Gradle project. It is not added to the root Maven reactor and does not change
the repository's Maven gate.
