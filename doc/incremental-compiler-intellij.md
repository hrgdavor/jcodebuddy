# Using the incremental compiler from IntelliJ IDEA

The IntelliJ integration is a packaged plugin, not a JSON report:

[`webview/webview-intellij-incremental`](../webview/webview-intellij-incremental/README.md)

It bundles `jcodebuddy-compile` (including **ECJ 3.46.100**) and `jcodebuddy-watch`, replaces IntelliJ's
Java auto-build with the fs-meta + ECJ loop, and publishes compiler diagnostics directly to the editor.
There is no diagnostics JSON, no report file and no file-watching round trip for error squiggles.

## What the plugin does

The plugin is installed for the IDE, but it is enabled **per project**. Open
`Settings | Tools | JCodeBuddy Incremental Build` and check **Enable the fs-meta + ECJ incremental build
for this project**. The setting lives in the project (`.idea/jcodebuddy-incremental.xml`).

When enabled, on project open the plugin:

1. It collects the project's Java source roots and module compiler outputs from IntelliJ.
2. It creates one `EcjIncrementalCompiler` per source root, writing into the module's existing output.
3. It compiles the sources once.
4. It subscribes to the fs-meta daemon serving the project root.
5. It compiles exactly the changed `.java` files from each fs-meta batch.
6. It publishes diagnostics in memory and restarts IntelliJ highlighting.

A failed compile adds red annotations. A successful compile replaces that source root's diagnostics with
an empty list, so the next highlighting pass removes them.

## Setup

### 1. Install the plugin

Build the plugin from its project:

```sh
cd webview/webview-intellij-incremental
./gradlew buildPlugin
```

On Windows:

```text
gradlew.bat buildPlugin
```

Install:

```text
build/distributions/JCodeBuddy Incremental Build-0.1.0.zip
```

via `Settings | Plugins | ⚙ | Install Plugin from Disk...`, then restart IntelliJ.

The bundle must contain:

```text
lib/ecj-3.46.100.jar
lib/jcodebuddy-compile-1.0-SNAPSHOT.jar
lib/jcodebuddy-watch-1.0-SNAPSHOT.jar
```

The plugin resolves those two JCodeBuddy modules from Maven local at build time. Install them first when
working from this checkout:

```sh
mvn -o -pl :jcodebuddy-compile,:jcodebuddy-watch -am install -DskipTests
```

### 2. Start the fs-meta daemon

The plugin does not start the daemon. Register the IntelliJ project root before opening the project:

```sh
fsmeta daemon start
fsmeta root add /path/to/project --tick 25
```

`--tick` is per root. 10–25 ms suits a fast local editor; 50–100 ms is safer for many roots or slower
storage. The plugin never starts a Java `WatchService`.

If no daemon is found when the project setting is enabled, the plugin performs the initial compile and
shows a warning, but it cannot watch changes. Start the daemon and reopen the project.

### 3. Enable it for the project

`Settings | Tools | JCodeBuddy Incremental Build` → check
**Enable the fs-meta + ECJ incremental build for this project** → **Apply**.

The setting is project-scoped: another project can keep the plugin disabled. The plugin is not active
until this box is checked; it starts immediately when applied and does not require an IDE restart.

### 4. Disable IntelliJ's build

The plugin owns the compiler output, so IntelliJ must not compete with it:

1. `Settings | Build, Execution, Deployment | Compiler` → uncheck **Build project automatically**.
2. In every Run/Debug configuration → **Before launch** → remove the **Build** step.

IntelliJ remains the editor, test runner and debugger. Its own inspections still run; the plugin's
diagnostics are additive.

## Error hints

The plugin registers three pieces:

| component                        | role                                                                |
| -------------------------------- | ------------------------------------------------------------------- |
| `IncrementalBuildService`        | compiles changed files and publishes diagnostics                    |
| `IncrementalDiagnosticsService`  | in-memory map from file to diagnostics                              |
| `JcodeBuddyDiagnosticsAnnotator` | `ExternalAnnotator` that adds `HighlightSeverity.ERROR` annotations |

The data path is:

```text
EcjIncrementalCompiler.lastErrors()
        │
        ▼
IncrementalDiagnosticsService (in memory)
        │
        ▼
JcodeBuddyDiagnosticsAnnotator → IntelliJ editor
```

No JSON is involved. `IncrementalBuildService` calls `DaemonCodeAnalyzer.restart()` after each
compile, and the annotator reads the current in-memory diagnostics for the file being highlighted.
Line numbers are 1-based in compiler output and converted to zero-based editor offsets by the
annotator.

This is the part that cannot be done with IntelliJ's External Tools configuration alone. IntelliJ's
documented extension point for external validation is
[`ExternalAnnotator`](https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html.md#external-annotator);
the plugin implements exactly that.

## Configuration and limits

- **Source roots and output** come from the IntelliJ project. If a module has no compiler output path,
  the plugin falls back to `out/production/<module>` or `out/test/<module>`; set the module output in
  `Project Structure` if a run configuration must see it.
- **Classpath** is built from the project's `OrderRootType.CLASSES` order entries plus module output
  directories. `jcodebuddy-compile` resolves the JDK itself.
- **No classloader reload.** Unlike `examples/fsmeta-reload`, the plugin has no separate project
  classloader; IntelliJ loads the compiled classes when a run configuration starts.
- **No dependency recompilation.** Only files in the fs-meta batch are compiled.
- **Generated sources** are compiled only if their source root is part of the IntelliJ module.
- **No automatic daemon retry.** If the daemon starts after the project is open, reopen the project or
  restart the IDE so the startup activity can subscribe.
- **The plugin is a separate Gradle project.** It is not in the root Maven reactor or the Maven gate.

## Troubleshooting

| symptom                                  | cause                                                                 | fix                                                                                          |
| ---------------------------------------- | --------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| no squiggles at all                      | plugin disabled for this project, or the daemon was absent at startup | enable it in `Settings                                                                       | Tools | JCodeBuddy Incremental Build`; start `fsmeta`; reopen the project |
| old errors remain after a good compile   | highlighting was not restarted, or the file is not open               | check the notification/event log; reopen the file; re-run the compile                        |
| errors are on the wrong line             | line/column convention mismatch                                       | report it; compiler lines are 1-based and columns are 0-based here                           |
| IDE still builds on save                 | auto-build or a Run configuration's Build step is still enabled       | disable both                                                                                 |
| unresolved project classes               | plugin output is not the module output IntelliJ uses                  | align `Project Structure → Modules → Paths` with the plugin output, or set the module output |
| IntelliJ build overwrites plugin classes | an explicit Build still runs and shares the output directory          | remove the before-launch Build step, or use a separate module output for the external loop   |

For building, running in a sandbox IDE, and the exact bundled jars, see the plugin's own
[README](../webview/webview-intellij-incremental/README.md).

## Optional JSON report

`examples/fsmeta-reload` still writes
`project/target/jcodebuddy-diagnostics.json` because it is a standalone example and other tools may want
a file-based diagnostics contract. The IntelliJ plugin does **not** read that report. If you are using
the plugin, the report is unnecessary for editor errors.
