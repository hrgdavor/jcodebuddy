# Eclipse auto-build benchmark

A reproducible harness that measures **one Eclipse auto-build cycle** on a synthetic Java project,
under the real Eclipse installation at `D:\programs\eclipse`. It is deliberately small: it measures
a project edit → JDT build round trip. There is no entity code generation here, and it is not part
of the repository gate.

## What is measured

`editNanos` is a `System.nanoTime()` taken immediately after one prepared edit to
`src/bench/C0.java` (`IFile.setContents`). `buildNanos` is the difference between it and the
`POST_BUILD` resource-change event caused by that edit. The initial `FULL_BUILD` is the warm-up and
is never included; only the edit-triggered build is measured.

The window also reports its own split:

- `queueNanos` — the edit until the `PRE_BUILD` event, i.e. how long Eclipse waits before the
  auto-build job starts;
- `builderNanos` — `PRE_BUILD` until `POST_BUILD`, i.e. the builder's own work.

For a single-file edit that split is the whole story: almost all of the window is Eclipse's
scheduling delay (`AutoBuildJob.computeScheduleDelay()` schedules the auto-build with a 1000 ms
coalescing delay — verified in `org.eclipse.core.internal.events.AutoBuildJob` of the installed
`org.eclipse.core.resources`), and the JDT incremental build is a few tens of milliseconds.

## Files

| path                                    | role                                                                   |
| --------------------------------------- | ---------------------------------------------------------------------- |
| `src/bench/BenchApplication.java`       | the headless Eclipse application (`bench.app.app`)                     |
| `META-INF/MANIFEST.MF`                  | bundle manifest: `bench.app`, requires the Eclipse bundles             |
| `plugin.xml`                            | declares the application under `org.eclipse.core.runtime.applications` |
| `run.js`                                | the Bun driver: compile, jar, install, run, clean up, report           |
| `README.md`                             | this document                                                          |

The driver is the only executable: Bun/Node APIs only, no `.ps1`, `.cmd` or `.sh` anywhere.

## Prerequisites

- **Eclipse 4.35** at `ECLIPSE_HOME` (default `D:\programs\eclipse`, override with the environment
  variable or `--eclipse-home`). The installation must use `simpleconfigurator`
  (`configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` exists).
- **JDK 25** for compiling only, because the harness is specified to build with a JDK 25 `javac
  --release 21` (Eclipse itself runs on its bundled Java 21). Default
  `C:\Program Files\Java\jdk-25`; override with `BENCH_JDK_HOME`, `JDK25_HOME` or `--jdk`.
- **Bun**.

`JAVA_HOME`/`PATH` are not used: on this machine `java` on `PATH` is a JDK 8, and the driver
resolves and version-checks the JDK 25 explicitly instead of trusting the shell.

## Running it

```
bun run benchmarks/eclipse-auto-build/run.js --classes 42
bun run benchmarks/eclipse-auto-build/run.js --classes 42 --classes 200 --repeat 2
bun run benchmarks/eclipse-auto-build/run.js --help
```

The exact Eclipse command line a run uses is printed by the harness (and is in the result's
`command` array), for `N=42`:

```
D:\programs\eclipse\eclipse.exe -data D:\wrk\java\jcodebuddy\.tmp\eclipse-auto-build\ws-42-<run> \
  -nosplash -consoleLog -clean -application bench.app.app \
  -vmargs -Dbench.classes=42 \
          -Dbench.result=D:\wrk\java\jcodebuddy\.tmp\eclipse-auto-build\ws-42-<run>.result.json \
          -Dbench.ready=D:\wrk\java\jcodebuddy\.tmp\eclipse-auto-build\ws-42-<run>.ready \
          -Dbench.timeoutMs=120000
```

The application's own budget is 120 s (`--timeout`, passed as `-Dbench.timeoutMs`, after which it
writes a `timedOut` result and exits 1). The driver kills Eclipse at `timeout + 30 s` of start-up
allowance, so a hung Eclipse cannot wedge the harness.

Each run writes, under `.tmp/eclipse-auto-build/`:

- `ws-<classes>-<run>/` — the fresh `-data` workspace (kept for inspection; `--clean-workspaces`
  deletes it);
- `ws-<classes>-<run>.result.json` — the application's result;
- `ws-<classes>-<run>.ready` — the ready marker, written after the initial build;
- `ws-<classes>-<run>.console.log` — Eclipse's stdout/stderr (`-consoleLog`).

## Result (2026-11-07, this machine)

Eclipse 4.35.0 (buildId 4.35.0.20250306-0811), JDT core 3.41.50 as registered in `bundles.info`,
runs on Java 21.0.6. Bundle compiled with `javac 25.0.3` at `--release 21` (class file major
version 65). Final bundle: 15 860 bytes; its sha256 is deliberately not quoted, because `jar`
stamps entry timestamps — the bytes differ per build while the compiled classes do not.

| N     | runs | edit→POST_BUILD (ms) | queued (ms)      | builder (ms) | initial FULL_BUILD (ms) |
| ----- | ---- | -------------------- | ---------------- | ------------ | ----------------------- |
| 42    | 2    | 1063.38, 1059.27     | 1034.34, 1033.68 | 29.05, 25.59 | 995.21, 888.36          |
| 200   | 2    | 1065.80, 1079.32     | 1031.48, 1036.01 | 34.32, 43.31 | 1037.03, 985.03         |

Sixteen runs were completed on this machine (N=1, N=42 ×9, N=200 ×6), all `ok: true`,
`classFileChanged: true`, `initialErrors: 0`, `initialClassFiles = N`, Eclipse exit code 0. The
earlier batches agree: totals 1007–1079 ms with queue 981–1036 ms and builder 22–43 ms, the
pre-instrumentation runs N=1 1053.72 ms / N=42 1018.41 ms / N=200 1011.67 ms, and the last re-run of
`--classes 42`, after the final driver edit, 1008.33 ms (queue 984.17, builder 24.17).

Two facts this table states plainly:

1. the measured window is about **one second, dominated by Eclipse's auto-build coalescing delay**
   (~1.0 s) — the JDT incremental build of the edited file is only **22–43 ms**;
2. the window does **not** grow from N=1 to N=200, and only `C0.class` is rewritten (the other
   class files keep their initial-build timestamp): the measured build really is incremental.

## The known constraint: the bundle is installed into the Eclipse installation

**A bare bundle jar in `dropins/` is not recognised by this installation.** An earlier attempt in
this workspace (`.tmp/eclipse-app`, its log at `.tmp/eclipse-app/ws/.metadata/.log`) shows the p2
reconciler reading a dropins directory as a repository and failing
(`No repository found at file:/D:/programs/eclipse/dropins/bench/.`), after which the application
is reported as `Application "bench.app.app" could not be found in the registry` — the bundle never
resolves. A jar merely copied into `plugins/` is not enough either: it is not registered with
`simpleconfigurator`, so the same "could not be found in the registry" error follows (verified here
by running the harness jar with `plugins/bench.app.jar` in place and no `bundles.info` entry).

So the run needs **two** temporary changes to the installation, and both are undone afterwards:

1. `plugins/bench.app.jar` — the bundle itself;
2. one line in `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`:

   ```
   bench.app,1.0.0,plugins/bench.app.jar,4,false
   ```

   (bucket 4 = start level 4, not auto-started.)

The second file is the installation's own bundle index, so it is treated as borrowed state: the
driver keeps a byte copy of the file **without** the `bench.app` line, writes the registered
version for the run, and restores the copy afterwards through the same code path, keeping the
file's CRLF line endings. A jar left behind by an interrupted run is moved aside into
`.tmp/eclipse-auto-build/install-backup/` instead of being restored, so a crashed run cannot
re-install itself.

Verified after the last batch: `plugins/bench.app.jar` absent, `bundles.info` back to
61540 bytes / `sha256 A9F7EBC8B267F39E1F9C6F183D0A77488535007D25650F9FE5EF43EA3BA85DBF` with zero
`bench.app` lines, `dropins/` empty, no Eclipse process left running, and the p2 profile
(`p2/`) untouched. The only other thing a run touches is Eclipse's own OSGi bundle cache under
`configuration/org.eclipse.osgi`, which is exactly what `-clean` resets on every run — the same
effect as starting the IDE with `-clean`.

Runs must not overlap, because the installation is shared: the driver takes
`.tmp/eclipse-auto-build/install.lock` and refuses to start while another live run holds it
(`--force-unlock` breaks a stale one).

## Notes and limits

- **Not a gate, not a codegen benchmark.** Nothing here generates entity code and nothing runs the
  repository's Maven gate.
- **The compile classpath is `ECLIPSE_HOME\plugins\*`, as specified.** This installation holds
  several versions of some bundles (e.g. `org.eclipse.jdt.core_3.41.0` … `3.47.0`, while
  `bundles.info` registers 3.41.50), so `javac` resolves the first match alphabetically. The
  fixture only uses long-stable Eclipse APIs, so this is consistent with the running Eclipse; it is
  noted because the wildcard makes the compile classpath a property of what is installed.
- **The first build is a cold build.** Every run gets a fresh workspace and `-clean`, so
  `initialBuildNanos` (≈0.9–1.2 s) is a cold JDT full build of N trivial classes, and the measured
  auto-build starts from warm in-session state.
- **The workspace is left behind on purpose** for inspection; delete `.tmp/eclipse-auto-build/`
  freely (it is gitignored scratch space), or pass `--clean-workspaces`.
- **`--verbose`** echoes the Eclipse console of each run; without it the per-run console is in
  `ws-<classes>-<run>.console.log`.
