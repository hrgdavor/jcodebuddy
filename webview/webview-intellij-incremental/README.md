# JCodeBuddy Incremental Build

An IntelliJ IDEA plugin that replaces IntelliJ's Java auto-build with the repository's fs-meta + ECJ
incremental compiler.

The plugin bundles:

- `jcodebuddy-compile` and its **ECJ 3.46.100** dependency;
- `jcodebuddy-watch`, the fs-meta daemon client.

Compiler diagnostics are published directly by an `ExternalAnnotator`; there is no JSON hand-off for editor
squiggles. The last diagnostic set is also persisted as a small project-output TSV so IntelliJ can restore
the annotations after a project restart.

> This plugin is not part of the Maven reactor and not part of `GATE_MODULES`. It is a Gradle/IntelliJ
> Platform project under `webview/`, like `webview-jetbrains`.

## What it does

The plugin is installed for the IDE, but the build loop is **enabled per project**. Open
`Settings | Tools | JCodeBuddy Incremental Build` and check **Enable the fs-meta + ECJ incremental build
for this project**. The setting is stored in the project (`.idea/jcodebuddy-incremental.xml`), so another
project can keep it disabled.

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
| local JCodeBuddy artifacts | Gradle resolves `hr.hrg.jcodebuddy:jcodebuddy-compile` and `jcodebuddy-watch` from Maven local |

Install the two Maven artifacts once after changing them:

```sh
mvn -o -pl :jcodebuddy-compile,:jcodebuddy-watch -am install -DskipTests
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
retries every 2 seconds until the daemon appears.

This option is global: every project in that IDE then relies on fs-meta for native file-change notifications.
Keep the plugin installed while it is enabled. Without the option both watchers run and the plugin logs this
warning with the exact opt-in flag:

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
   - **Reconnect fs-meta** — drop the current subscription and retry daemon discovery;
   - **Detailed Classpath** — write every classpath entry for every module to the log on demand;
   - **Open Log Folder** — open `.jcodebuddy/cache/incremental-build/` in the system file explorer;
   - **Clear Log** — clear both the pane and `build.log`.

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
fs-meta daemon root: ...
fs-meta socket: ...
fs-meta filter: ...
Batch: changed=[...] deleted=[...]
```

`Hash-driven compile` is the normal startup path. `Full compile` is emitted only by the explicit
**Rebuild** button. `Restored N diagnostic(s)` means the previous compile's error annotations were loaded
from the diagnostics state file. Normal classpath lines show counts; use **Detailed Classpath** when the
per-entry `JAR`/`DIR`/`MISSING` list is needed.

A missing daemon, a source root outside the daemon root, or a compile failure is visible in both places.
By default the log reports per-module classpath **counts**. Press **Detailed Classpath** to append each
entry as `JAR`, `DIR`, or `MISSING`; use that when a Maven dependency appears unresolved. IntelliJ reports
a JAR root as `...jar!/`; the plugin strips that virtual suffix and logs the physical `.jar` path.

The log also prints the IDE heap maximum and current use at startup. If the build reports
`OutOfMemoryError`, increase the IDE heap (`Help | Change Memory Settings`); the compiler now shares one
lazy JAR/JDK index across the whole project and keeps only a bounded class-byte hot cache, so a large
reactor should not multiply dependency caches per module.

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
**Reconnect fs-meta** keeps the saved marker and resubscribes from it.

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

One root is still registered with fs-meta: the **project/aggregator root**. Do not register each Maven
module as a separate fs-meta root.

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
- **No automatic retry**: if the daemon is started after the project is opened, click **Reconnect fs-meta**
  in the build-log toolbar. The plugin prints the exact `fsmeta daemon start` and `fsmeta root add` commands
  when the daemon/root is missing.
- **Memory**: the plugin shares one dependency index across all source roots. JAR class bytes are read on
  demand from the JAR and limited to a bounded hot cache; the JARs themselves stay on disk. The initial
  full compile is chunked (default 32 files, `-Djcodebuddy.intellij.initialCompileChunk=<n>`) so the ECJ
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
