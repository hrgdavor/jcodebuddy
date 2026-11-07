# Compile-latency benchmark: Eclipse auto-build vs fs-meta + ECJ incremental

This benchmark measures **compile latency only**: one prepared source edit, then the time to a
fresh `.class` file. It does not run the entity generator or any other code generation.

There are two measured paths:

| Path                            | Trigger                                                                       | Compiler                                                   | What the number includes                       |
| ------------------------------- | ----------------------------------------------------------------------------- | ---------------------------------------------------------- | ---------------------------------------------- |
| `benchmarks/eclipse-auto-build` | Eclipse JDT workspace auto-build, exercised by a headless Eclipse application | the Eclipse installation's JDT builder                     | edit → auto-build scheduling → class file      |
| `benchmarks/fsmeta-auto-build`  | one fs-meta daemon batch                                                      | `jcodebuddy-compile`'s standalone ECJ incremental compiler | edit → daemon batch → ECJ compile → class file |

The synthetic fixture has `N` independent classes (`C0`…`C<N-1>`), and exactly one class is edited.
The first compiler index and the Eclipse initial build are **outside** the measured interval.

## Prerequisites

- JDK 25 (`JAVA_HOME` or `JCODEBUDDY_JDK25`).
- Maven on `PATH`.
- A built `fsmeta` binary for the fs-meta side (`FSMETA_BIN`, default
  `D:\wrk\core\fs-meta\zig-out\bin\fsmeta.exe`).
- `FSMETA_TICK_MS` (default 50, range 5–1000) is the per-root scheduler tick; the 25 ms quiet floor still
  applies. Use e.g. `FSMETA_TICK_MS=10` to see the shorter batch window.
- Eclipse for the auto-build side; `ECLIPSE_HOME` defaults to `D:\programs\eclipse`.

## Run

```sh
# fs-meta + ECJ
BENCH_CLASSES=42,200,1000 BENCH_REPEAT=3 bun benchmarks/fsmeta-auto-build/run.js

# Eclipse auto-build
bun benchmarks/eclipse-auto-build/run.js --classes 42 --classes 200 --classes 1000 --repeat 3
```

The fs-meta driver builds `jcodebuddy-compile` and its benchmark runner first; the Eclipse driver
temporarily installs its application bundle into the Eclipse installation and removes it again.
Both emit one JSON line per run.

## Interpreting the result

- `elapsedMs` is the user-visible interval: edit write → daemon/JDT completion → class file.
- `compileNanos` is ECJ's own compile call; `indexNanos` is the one-time JAR/JDK index and is not
  part of `elapsedMs`.
- Eclipse `buildNanos` is the auto-build's `POST_BUILD` interval, so it includes the workspace
  scheduler's delay. It is **not** pure compiler CPU time; that is the point when comparing “how
  fast the editor reacts”.
- The two paths use different ECJ versions today: Eclipse 4.35/JDT 3.46 and the reactor's
  `org.eclipse.jdt:ecj:3.37.0`. Treat the comparison as change-detection + compile feedback, not
  as a compiler-version benchmark.
- The fixture is synthetic and independent classes only. The committed example project and a
  multi-file dependency graph are the next extension; they are not claimed here.
- Run a warm-up and repeats. One run is not a result.
