# Using the fs-meta reload example

This is a runnable reference, not a framework or a build plugin. It shows the smallest complete loop:

```text
save -> fs-meta batch -> ECJ incremental compile -> new project classloader -> app + JUnit
```

Use it in three ways:

1. **Run the demo** to see the loop and its measurements.
2. **Use the two libraries** (`jcodebuddy-compile`, `jcodebuddy-watch`) in your own tool. The runner is a
   reference for the wiring; you do not need Maven or the example module.
3. **Use it as a template** for a project with the same source layout and package boundary, then change the
   constants listed below.

## 1. Run the demo

From the repository root:

```sh
bun examples/fsmeta-reload/run.js
```

The script:

- builds/installs the example module and its dependencies;
- removes `project/classes`;
- starts a **private** fs-meta daemon under `.tmp/fsmeta-reload`;
- registers `examples/fsmeta-reload/project` with it;
- starts the runner with `mvn exec:java`.

Edit `project/src/main/java/demo/project/Calculation.java` and save. One batch later the console prints the new
JSON, the JUnit result, and:

```text
[reload] compile=6.0ms reload=0.6ms app=1.7ms test=6.9ms totalFromSave=95ms
```

### Useful variations

```sh
# tune the daemon scheduler; 10-25 ms suits a fast local editor
FSMETA_TICK_MS=10 bun examples/fsmeta-reload/run.js

# opt into the internal ECJ START_BATCH parse/bind phase (one-file batches only)
FSMETA_EARLY_COMPILE=1 bun examples/fsmeta-reload/run.js

# use a different fs-meta build
FSMETA_BIN=/path/to/fsmeta bun examples/fsmeta-reload/run.js
```

`FSMETA_EARLY_COMPILE` is experimental and off by default. On a multi-file batch it falls back to the normal
changed-set compile, because ECJ cannot append units to a compiler that has already started.

### Try the failure paths

| change                                     | what it demonstrates                                                        |
| ------------------------------------------ | --------------------------------------------------------------------------- |
| `units * unitPrice` -> `units + unitPrice` | a passing reload followed by a test failure; the new app code still runs    |
| introduce a syntax error                   | the compile fails, the previous loader stays valid, diagnostics are printed |
| add a second changed file                  | the end-of-batch changed-set compile wins over the early one-file phase     |

The runner writes its diagnostics to `project/target/jcodebuddy-diagnostics.json`; success is an empty `files`
array. See [`../../doc/incremental-compiler-intellij.md`](../../doc/incremental-compiler-intellij.md) for using
that report to add and remove IntelliJ editor errors.

## 2. Use the libraries in your own tool

The example has two direct dependencies:

```xml
<dependency>
    <groupId>hr.hrg.jcodebuddy</groupId>
    <artifactId>jcodebuddy-compile</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>hr.hrg.jcodebuddy</groupId>
    <artifactId>jcodebuddy-watch</artifactId>
    <version>${project.version}</version>
</dependency>
```

The compiler side is independent of the watcher:

```java
Path sourceRoot = projectRoot.resolve("src/main/java");
Path binDir = projectRoot.resolve("classes");
String classpath = EcjIncrementalCompiler.buildClasspath(binDir, java.class.path);

EcjIncrementalCompiler compiler = new EcjIncrementalCompiler(sourceRoot, binDir, classpath);
compiler.index(); // once; the first compile does it automatically if omitted

boolean ok = compiler.compile(Set.of(changedFile));
if (!ok) {
    compiler.lastErrors().forEach(System.err::println);
}
```

The watcher side supplies the changed files. `FsMetaDaemonWatch` finds the daemon, subscribes at its latest
generation, applies the root-relative filter, and delivers a `WatchBatch` only at `END_BATCH`:

```java
FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discoverAncestor(projectRoot);
Path daemonRoot = attach.location().root();
PathFilter filter = PathFilter.of(List.of("src/**/*.java"), List.of("**/classes/**"));

FsMetaDaemonWatch watch = new FsMetaDaemonWatch(
        filter, attach.location().socket(), FsMetaClient.LATEST,
        batch -> {
            Set<Path> changed = new LinkedHashSet<>();
            for (String name : batch.changed()) {
                changed.add(daemonRoot.resolve(name));
            }
            compiler.compile(changed);
        });
watch.start();
```

`batch` paths are relative to the **daemon root**, not necessarily the project root. The example handles that
with `daemonFile(...)` and `relativePrefix(...)`; copy that logic if your daemon serves an ancestor directory.
The compiler's classpath also has to include the URLs of the loader that owns Jackson/JUnit when your tool runs
in a nested loader; the example's `runtimeClasspath()` shows that.

`FsMetaDaemonWatch` also has a streaming constructor that reports `START_BATCH` and each accepted
`CHANGE_ENTRY` through `FsMetaDaemonWatch.BatchObserver`. Use it only for an opt-in one-file early phase; see
`beginEarly`/`completeEarly` in [`jcodebuddy-compile`'s README](../../jcodebuddy/jcodebuddy-compile/README.md).

## 3. Use it as a template

The runner is [`src/main/java/demo/runner/ReloadRunner.java`](src/main/java/demo/runner/ReloadRunner.java).
The classloader is [`src/main/java/demo/runner/ProjectClassLoader.java`](src/main/java/demo/runner/ProjectClassLoader.java).

Change these before pointing it at a different project:

| what                    | current value                                  | where                                                 |
| ----------------------- | ---------------------------------------------- | ----------------------------------------------------- |
| project layout          | `<root>/src/main/java`, `<root>/src/test/java` | `ReloadRunner` constructor / `projectRootOf`          |
| class output            | `<root>/classes`                               | `reload.classesDir`, or the second CLI argument       |
| child-first package     | `demo.project.`                                | `ProjectClassLoader.PROJECT_PREFIX`                   |
| app entry point         | `demo.project.App.main(String[])`              | `ReloadRunner.APP_CLASS`                              |
| test class              | `demo.project.CalculationTest`                 | `ReloadRunner.TEST_CLASS`                             |
| source shown in prompts | `demo/project/Calculation.java`                | `ReloadRunner.EDIT_ME`                                |
| daemon filter           | `src/**/*.java` plus the project's prefix      | `ReloadRunner.run`                                    |
| diagnostics report      | `<project>/target/jcodebuddy-diagnostics.json` | `writeDiagnostics`, or `-Djcodebuddy.diagnostics=...` |

The POM exposes the two runner arguments as Maven properties:

```sh
mvn -o -pl :fsmeta-reload-example exec:java \
  -Dreload.exampleRoot=/path/to/example \
  -Dreload.classesDir=/path/to/example/project/classes
```

`run.js` does the build/install step first. A hand-run Maven invocation needs the module's dependencies
installed (`-am install`).

### Keep the classloader boundary

`ProjectClassLoader` is child-first only for `demo.project.*`; change that prefix to your project's package.
Everything else — Jackson, JUnit, the JDK and the runner — stays in the parent loader. Loading a library in the
child gives the project different `Class` identities for the same library types, and JUnit/Jackson stop
recognising their own annotations and descriptors.

The new loader is seeded from `old.classBytes()`. Unchanged classes are defined from the old bytes, changed
classes are re-read from `binDir`, and deleted names are removed. The old loader is discarded; the old `Class`
objects remain valid for code that still holds them.

### What the template does not do

- **It does not recompile dependents.** Only files in the batch are compiled. If changed `A` requires
  recompiling unchanged `B` for a new signature, that is a dependency-graph feature not present in this example.
- **It assumes one top-level type per file** for the changed-class name mapping. A second top-level type in the
  same file keeps its old bytes.
- **It does not do annotation processing.**
- **It does not replace Maven/Gradle.** Maven builds the runner and the library dependencies; the project
  sources are deliberately outside Maven's source roots and compiled at run time.
- **It does not start a `WatchService`.** fs-meta is the only watcher.
- **The project output directory must be on the IntelliJ module classpath** if the editor is expected to resolve
  the project's types. The external compiler owns that directory; remove IntelliJ's Build before-launch step.

## 4. IntelliJ

Use [`../../doc/incremental-compiler-intellij.md`](../../doc/incremental-compiler-intellij.md) for:

- disabling IntelliJ's auto-build and its before-launch Build step;
- adding the external class output to the module;
- installing the `ExternalAnnotator` plugin that adds and removes editor error hints from the diagnostics report.

## 5. When to use which API

| you need                                                    | use                                                   |
| ----------------------------------------------------------- | ----------------------------------------------------- |
| compile a known set of changed files                        | `EcjIncrementalCompiler.compile(Set<Path>)`           |
| compile one file immediately                                | `compileOne(Path)`                                    |
| opt-in ECJ internal start/complete phase                    | `beginEarly(Path)` / `completeEarly(handle)`          |
| find and subscribe to a daemon                              | `FsMetaDaemonDiscovery` / `FsMetaDaemonWatch`         |
| react to `START_BATCH`/`CHANGE_ENTRY` before the batch ends | `FsMetaDaemonWatch.BatchObserver`                     |
| a reloadable project-only classloader                       | copy `ProjectClassLoader` and set your package prefix |

The compiler API details are in
[`jcodebuddy-compile/README.md`](../../jcodebuddy/jcodebuddy-compile/README.md); the example's README covers the
classloader design and the measurement fields.
