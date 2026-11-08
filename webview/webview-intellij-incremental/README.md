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

On project open, the plugin:

1. collects the project's Java source roots from IntelliJ;
2. creates one `EcjIncrementalCompiler` per source root, writing into the module's existing compiler
   output (`CompilerModuleExtension`);
3. compiles the source roots once;
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

## Configuration limits

- **Source roots and output** come from the IntelliJ project. If a module has no compiler output path,
  the plugin falls back to `out/production/<module>` or `out/test/<module>`; set the module output in
  `Project Structure` if a run configuration must see it.
- **Classpath** is built from the project's order entries (`OrderRootType.CLASSES`) plus the module
  output directories. The JDK is resolved by `jcodebuddy-compile`.
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
