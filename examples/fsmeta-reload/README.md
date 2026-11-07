# fs-meta reload example

A runnable demonstration of the loop this repository's compiler and watcher libraries are built for:

```text
save a .java file
      │
      ▼
fs-meta daemon: watches the project directory, hashes the file, delivers one batch
      │
      ▼
EcjIncrementalCompiler: compiles exactly the changed files (no full rebuild)
      │
      ▼
ProjectClassLoader: a NEW classloader, seeded with the previous one's bytes for unchanged classes
      │
      ▼
App (Jackson JSON to the console) and CalculationTest (JUnit 5) run on the new classes
      │
      ▼
one measurement line: compile / reload / app / test / totalFromSave
```

There is no `WatchService` here and no `java-watch*` dependency: the change source is the **fs-meta daemon**,
and `jcodebuddy-watch` is the client that subscribes to it.

## Run it

Prerequisites:

| what                   | why the example needs it                                                                           |
| ---------------------- | -------------------------------------------------------------------------------------------------- |
| JDK 25                 | the reactor compiles at release 25; `scripts/lib/toolchain.js` resolves and verifies it            |
| Apache Maven (3.9)     | `exec:java` runs the runner; resolved through `JCODEBUDDY_MVN`, the recorded location, then `PATH` |
| the fs-meta executable | it is the watcher; `FSMETA_BIN` overrides the default `D:\wrk\core\fs-meta\zig-out\bin\fsmeta.exe` |
| fs-meta tick           | `FSMETA_TICK_MS` defaults to 50 ms; valid 5–1000; the scheduler’s 25 ms quiet floor still applies  |

**The tick should be chosen per editor/SSD rather than globally.** 50 ms is the new default; 10–25 ms is reasonable
for a fast local editor, while 50–100 ms is safer for many roots or slower storage.

```bash
bun examples/fsmeta-reload/run.js
```

That one command builds and installs the runner module, cleans `project/classes`, starts a **private** fs-meta
daemon under `.tmp/fsmeta-reload`, registers `examples/fsmeta-reload/project` with it, and then runs
`mvn -o -q -pl :fsmeta-reload-example exec:java`. Its output starts like this:

```text
[run] JDK 25: C:\Program Files\Java\jdk-25
[run] Maven: Apache Maven 3.9.0
[run] cleaned .../examples/fsmeta-reload/project/classes
[run] starting a private fs-meta daemon (base .../.tmp/fsmeta-reload/daemon)
[run] watching .../examples/fsmeta-reload/project (root: the project directory, filter: src/**/*.java, tick: 50 ms)
[run] edit and save: .../project/src/main/java/demo/project/Calculation.java
[init] daemon: .../.tmp/fsmeta-reload/daemon/roots/<id>.sock
[init] daemon root: .../examples/fsmeta-reload/project (this project)
[init] filter: src/**/*.java
[init] indexed dependencies once: 203ms
[init] compiled 2 main + 1 test source(s) in 167ms
[init] project loader #1: 3 classes in 2ms
[init] first run
[app] demo.project.App.main(String[])
{"calculation":{"units":6,"unitPrice":7},"total":42}
[app] ok in 27ms
[test] demo.project.CalculationTest
[test] 1 test(s), 0 failed, 0 skipped in 89ms -> PASSED

[watch] edit and save: .../project/src/main/java/demo/project/Calculation.java
[watch] demo.project.App and demo.project.CalculationTest run again on every fs-meta batch (Ctrl+C stops)
```

Now edit that file in any editor and save it. One batch later the console shows the recompiled result:

```text
[reload] batch: changed=[src/main/java/demo/project/Calculation.java] deleted=[]
[reload] project loader #2: 3 classes (1 changed, 0 removed, 3 seeded from the previous loader)
[app] demo.project.App.main(String[])
{"calculation":{"units":6,"unitPrice":7},"total":43}
[app] ok in 2ms
[test] demo.project.CalculationTest
[test] FAILED: theTotalIsTheProduct()
[test]         org.opentest4j.AssertionFailedError: expected: <42> but was: <43>
...
[test] 1 test(s), 1 failed, 0 skipped in 7ms -> FAILED
[reload] compile=6.5ms reload=0.8ms app=1.9ms test=6.8ms totalFromSave=113ms
[reload] fromBatch: compileStart=0.3ms reloaded=7.6ms appDone=9.7ms testDone=17.2ms
```

`Ctrl+C` stops the runner (its shutdown hook closes the daemon subscription) and `run.js` then stops the
private daemon. The example touches no other fs-meta daemon on the machine: its socket, config and root list
all live under `.tmp/fsmeta-reload`.

### Running it by hand instead

The private daemon keeps its socket, root list and config under `.tmp/fsmeta-reload`, which is why the two
`FSMETA_*` variables below matter: they are how the daemon and its clients agree on that private state without
touching a developer's own daemons. `run.js` sets them for every command it starts.

```text
# with FSMETA_CONFIG_HOME=.tmp/fsmeta-reload/config and
#      FSMETA_RUNTIME_DIR=.tmp/fsmeta-reload/daemon exported:

# terminal 1 - a private daemon for this project
fsmeta daemon start --base .tmp/fsmeta-reload/daemon
fsmeta root add examples/fsmeta-reload/project --mode always --idle 0 --tick 50 --base .tmp/fsmeta-reload/daemon

# terminal 2 - the runner (the POM supplies <example-root> and <classes-dir> as <arguments>)
mvn -o -pl :fsmeta-reload-example exec:java
```

`mvn -o -pl :fsmeta-reload-example exec:java` needs the module's dependencies installed (`-am install`), which
is what `run.js` does first; the two paths it passes are the POM properties `reload.exampleRoot` and
`reload.classesDir`, defaulted to `${project.basedir}` and `${project.basedir}/project/classes`.

## What to edit

| edit                                                                           | app output                 | test                                            |
| ------------------------------------------------------------------------------ | -------------------------- | ----------------------------------------------- |
| change `units * unitPrice` to `units + unitPrice` in `Calculation.total()`     | total becomes 13           | **fails** — `expected: <42> but was: <13>`      |
| change `new Calculation(6, 7)` to `new Calculation(3, 4)` in `App.main`        | 6/7 becomes 3/4, total 12  | still passes (it asserts its own numbers)       |
| change the `42` in `CalculationTest` to a value that matches your formula edit | unchanged                  | passes again                                    |
| introduce a syntax error, then save                                            | the old app/test still run | the loader is **kept**, diagnostics are printed |

Those four cover the interesting paths: a passing reload, a failing test, and a compile error that must not
destroy a working run.

## What each piece owns

| piece                                         | owns                                                                 | why it is separate                                                                             |
| --------------------------------------------- | -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| the fs-meta daemon                            | watching, hashing, batching                                          | one native watcher per root, shared by every client; the example never starts a `WatchService` |
| `FsMetaDaemonDiscovery` / `FsMetaDaemonWatch` | finding that daemon and turning its batches into `WatchBatch` values | `jcodebuddy-watch` is the client half of the protocol, so neither the runner nor a test has to speak the socket format |
| `EcjIncrementalCompiler` (two: main and test) | compiling the changed `.java` files into `project/classes`           | one index of the dependency JARs, reused; a fresh ECJ `Compiler` per batch; only the changed files are compiled |
| `ReloadRunner`                                | the cycle, the measurements, running the app and the test            | it is the only place that decides *when* a new generation is created                           |
| `ProjectClassLoader`                          | `demo.project.*` and nothing else                                    | the reload needs a new class identity per generation; the loader is where that identity lives  |
| the parent loader (the runner's own)          | Jackson, JUnit, the JDK, the runner                                  | the libraries must stay the *same* types across reloads — see below                            |
| `project/` sources                            | the app being edited                                                 | **not** part of the Maven build: Maven's source root is `examples/fsmeta-reload/src/main/java`, and nothing under `project/` is compiled by it |

### `demo.project` classes are not on the runner's classpath

This is a load-bearing fact, not an accident. `src/main/java` of the Maven module contains only
`demo/runner/ReloadRunner.java` and `demo/runner/ProjectClassLoader.java`; the project's sources live under
`project/src/main/java` and `project/src/test/java`, which Maven never sees. So:

* `Class.forName("demo.project.Calculation")` from the runner's own loader would fail;
* the only door to a project class is `ProjectClassLoader`, which makes "did the new classes get loaded?"
  impossible to answer wrongly;
* a stray `demo.project` class cannot accidentally be served by the parent, because there is none to serve.

### The classloader rule: new loader, reused bytecode

Java cannot replace the code of a loaded class. A `Class` object, its method bodies and its identity are fixed
once a loader defines it, and defining the same name again in the same loader is a `LinkageError`. So each
batch produces a **new** `ProjectClassLoader` and discards the old one. Anything still holding an old `Class`
keeps working with the old code; new code is only reachable through the new classes. That is also why JUnit
must run against the *class object loaded by the new loader* (`DiscoverySelectors.selectClass(testClass)` takes
a `Class`, not a name).

What is reused is the **bytecode**:

1. `old.classBytes()` is the snapshot of every class the old loader knew, including classes it never defined;
2. the new loader is seeded with that snapshot — unchanged classes are defined from bytes that were already in
   memory, so they are **not re-read from disk and their source was not recompiled**;
3. the classes the batch actually changed are re-read from `classesDir`, overwriting their seeded bytes (plus
   any `Foo$Nested.class` the compiler wrote next to them);
4. deleted sources are dropped from the seed and their class files are removed from `classesDir`.

The result is visible in the output: `3 classes (1 changed, 0 removed, 3 seeded from the previous loader)`, and
a `reload` phase that is usually under a millisecond because it is a map copy and a handful of `defineClass`
calls.

`ProjectClassLoader` is **child-first for `demo.project.*` and parent-first for everything else**. Loading
Jackson or JUnit itself in the project loader would give the project different `ObjectMapper`, `@Test` and
`Assertions` types than the runner uses: JUnit would see an `@Test` annotation that is not the one it knows,
and Jackson's annotation lookups would not match its own. That split — new bytecode inside, stable libraries
outside — is why the reload works at all.

## Reading the numbers

| field           | starts at                                             | ends at                              |
| --------------- | ----------------------------------------------------- | ------------------------------------ |
| `compile`       | the first compiler call                               | the last compiler call returns       |
| `reload`        | `old.classBytes()` is taken                           | the new loader's constructor returns |
| `app`           | `App.main` is invoked through reflection              | it returns (or throws)               |
| `test`          | the JUnit launcher is created                         | `execute` returns                    |
| `totalFromSave` | the **earliest source mtime** of the batch (the save) | the test run finished                |

`totalFromSave` is the number a person actually feels: fs-meta's batch latency (its tick + the 25 ms quiet floor)
plus the compile plus the classload plus both runs. The second line separates the runner's own work from that
latency:

| field                 | meaning                                                        |
| --------------------- | -------------------------------------------------------------- |
| `compileStart`        | batch receipt → the compile begins (mapping paths, stat calls) |
| `reloaded`            | batch receipt → the new loader exists                          |
| `appDone`, `testDone` | batch receipt → that run finished                              |

Two honest caveats: all phases use `System.nanoTime()`, but the save can only come from the file's mtime, so
`totalFromSave` is wall-clock and carries the file system's timestamp granularity (a few milliseconds on
NTFS/FAT); and `totalFromSave` includes the time between saving and the daemon delivering the batch, which is
the dominant term when nothing else changed.

## Files

| file                                                      | what it is                                                                         |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `run.js`                                                  | the one-command driver: build, private daemon, `exec:java`, cleanup on exit/Ctrl+C |
| `pom.xml`                                                 | the `fsmeta-reload-example` module: `jcodebuddy-compile`, `jcodebuddy-watch`, Jackson, JUnit, `exec:java` |
| `.gitignore`                                              | keeps the generated `project/classes/` out of the tree                             |
| `src/main/java/demo/runner/ReloadRunner.java`             | the loop and the measurements                                                      |
| `src/main/java/demo/runner/ProjectClassLoader.java`       | the project-only classloader and its byte snapshot                                 |
| `project/src/main/java/demo/project/Calculation.java`     | the record you edit                                                                |
| `project/src/main/java/demo/project/App.java`             | the Jackson JSON console app                                                       |
| `project/src/test/java/demo/project/CalculationTest.java` | the JUnit 5 test                                                                   |
| `project/classes/`                                        | output of the runtime compiler; ignored by this example's `.gitignore` and cleaned by `run.js` at every start |

## Building and testing this module

The module is in the reactor but **not** in `GATE_MODULES`: it is a demo of two shipped libraries, not a tested
one. Its own Maven build compiles the runner; the project sources are compiled at run time, so Maven has no
test class for them (the module has no `src/test/java` at all). The targeted check is:

```bash
bun scripts/mvn-fast.js --off-cache -pl :fsmeta-reload-example -am test
```

`run.js` is the real test: it proves the compile → classload → run cycle end to end.

## Things that surprise people

* **`exec:java` shares Maven's JVM.** `System.getProperty("java.class.path")` inside it is Maven's *launcher*
  path, not the module's dependencies; those arrive as URLs on the exec classloader. That is why
  `ReloadRunner.runtimeClasspath()` starts from `java.class.path` **and** walks its loader chain — ECJ has to
  see Jackson for `App` and JUnit for the test. Launched as `java -cp ... demo.runner.ReloadRunner`, the walk
  adds nothing.
* **`junit-jupiter` is test-scoped, and `exec:java` therefore runs on the test classpath.** The reactor's
  `dependencyManagement` pins `junit-jupiter-api`, `junit-jupiter-params` and `junit-jupiter-engine` to scope
  `test`, and dependency management applies to *transitive* dependencies too — so a compile-scope
  `junit-jupiter` still cannot put them on a runtime classpath. `<classpathScope>test</classpathScope>` is the
  honest fix, and the runner's own Platform imports are compile scope, so the module compiles.
* **A batch is a set of paths, not a build plan.** Only the changed files are compiled. If a different,
  *unchanged* class used something you just deleted, it will not be recompiled and can fail at run time with
  `NoClassDefFoundError` — an incremental compiler's normal trade-off, and the reason a full rebuild still
  exists for real projects. `deleted` sources do have their class files removed and their names excluded from
  the next loader.
* **A changed file seeds by its primary type name.** `Calculation.java` seeds `Calculation.class` and every
  `Calculation$*.class`. A second top-level type declared in the same file would keep its old bytes — this
  example deliberately keeps one top-level type per file.
* **An overflow batch is a full pass.** If the daemon refuses a replay (`batch-too-old` / `batch-gap`),
  `FsMetaDaemonWatch` delivers an overflow batch and the runner recompiles every source and treats every
  existing class as changed.
* **A compile error keeps the running generation.** Nothing is reloaded, the diagnostics are printed, and the
  next save is compiled normally — the previous loader and its classes stay valid because class bytes are
  immutable once defined.
