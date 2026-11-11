# JCodeBuddy Incremental Build

An IntelliJ IDEA plugin that replaces IntelliJ's Java auto-build with the repository's fs-meta + ECJ
incremental compiler.

The plugin bundles:

- `jcodebuddy-compile` and its **ECJ 3.46.100** dependency;
- `jcodebuddy-watch`, the fs-meta daemon client.

Compiler diagnostics stay **in memory** in the plugin and are rendered directly by an
`ExternalAnnotator`. There is no diagnostics JSON, no report file and no file-watcher round trip for
editor squiggles.

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
3. compiles the source roots once, in module-dependency order;
4. subscribes to the fs-meta daemon serving the project root;
5. compiles exactly the changed `.java` files from each fs-meta batch;
6. publishes the compiler diagnostics to IntelliJ and restarts highlighting.

On a successful compile the diagnostics for that source root are replaced by an empty list, so the next
highlighting pass removes the old red errors. IntelliJ's own **Build** is not invoked by this plugin.

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
   failures. It is the quickest troubleshooting view.
2. **`idea.log`** — every line is also written through IntelliJ's `Logger`. Use
   **Help | Show Log in Explorer** (Windows) and search for `JCodeBuddy`.

The service logs:

```text
Starting incremental build for <project>
Source root [module] <path> -> <output>
Module [module] classpath entries: N
Initial compile: N source root(s)
Compiling [module] N file(s) from <root>
Compile OK [module] <root>
Compile FAILED [module] N diagnostic(s); first: ...
fs-meta daemon root: ...
fs-meta socket: ...
fs-meta filter: ...
Batch: changed=[...] deleted=[...]
```

A missing daemon, a source root outside the daemon root, or a compile failure is visible in both places.

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

## Development

```sh
./gradlew runIde      # sandbox IDE with this plugin
./gradlew compileJava # fast compile check
./gradlew buildPlugin # distributable zip
```

The build is a separate Gradle project. It is not added to the root Maven reactor and does not change
the repository's Maven gate.
