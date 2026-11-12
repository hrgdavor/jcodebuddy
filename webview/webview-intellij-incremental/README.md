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
3. discovers the fs-meta daemon and reads the last successfully applied batch marker from
   `.jcodebuddy/cache/incremental-build/batch.properties`;
4. on the first run, compiles all source roots once and saves the daemon head;
5. on later starts, subscribes from the saved batch and compiles only the replayed changes;
6. publishes the compiler diagnostics to IntelliJ and restarts highlighting.

The marker is written only after a successful compile. A failed **batch** remembers only that batch's
failed files (plus later changes), so fixing one error does not trigger a full rebuild. A source-root/layout
change, a cleaned compiler output, or a full-compile failure still forces a full compile. A marker for
another daemon root is ignored.

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
reopen the project after starting it; the plugin does not retry discovery.

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

If no daemon is found, the plugin still performs the initial compile and shows a warning; it cannot
watch changes until the daemon is available.

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
   source-root discovery, module classpaths, fs-meta daemon/socket/filter, batches, compile results and
   failures. Its toolbar has **Rebuild** (compile every source root again) and **Reconnect fs-meta**
   (drop the current subscription and retry daemon discovery). It is the quickest troubleshooting view.
2. **`idea.log`** — every line is also written through IntelliJ's `Logger`. Use
   **Help | Show Log in Explorer** (Windows) and search for `JCodeBuddy`.

The service logs:

```text
Starting incremental build for <project>
Resuming from fs-meta batch 1234 (.jcodebuddy/cache/incremental-build/batch.properties)
Restored N diagnostic(s) from .jcodebuddy/cache/incremental-build/diagnostics.tsv
Source root [module] <path> -> <output>
Module [module] classpath entries: N
Full compile: N source root(s)
Compiling [module] N file(s) from <root>
Compile OK [module] <root>
Compile FAILED [module] N diagnostic(s); first: ...
Saved fs-meta batch marker 1234 -> ...
fs-meta daemon root: ...
fs-meta socket: ...
fs-meta filter: ...
Batch: changed=[...] deleted=[...]
```

`Resuming...` means the startup did **not** recompile everything. `Full compile` is expected on the first
run, after a marker invalidation, or after a failed compile. `Restored N diagnostic(s)` means the previous
compile's error annotations were loaded from the diagnostics state file.

A missing daemon, a source root outside the daemon root, or a compile failure is visible in both places.
The log also lists every module classpath entry as `JAR`, `DIR`, or `MISSING`, with a per-module summary;
use it first when a Maven dependency appears unresolved. IntelliJ reports a JAR root as `...jar!/`; the
plugin strips that virtual suffix and logs the physical `.jar` path.

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
atomically only after a compile succeeds and all previously failed files are clean. If a batch fails, its
files are retried in the next batch instead of rebuilding the project. If the signature no longer matches,
the daemon root changed, the compiler output was cleaned away, or a full compile failed, the next start
performs a full compile instead of trusting the marker.

The **Rebuild** toolbar button deliberately performs a full compile and then saves the current daemon head.
**Reconnect fs-meta** keeps the saved marker and resubscribes from it.

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
- **No automatic retry**: if the daemon is started after the project is opened, reopen the project (or
  restart the IDE) so the startup activity can subscribe.
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
