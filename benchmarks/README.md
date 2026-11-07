# Compile-latency measurements

Two harnesses measure the same thing: one prepared edit to a synthetic Java project, then the time
to a fresh `.class` file. No entity/code generation is involved.

| Harness                                               | Trigger                          | Compiler                                                   |
| ----------------------------------------------------- | -------------------------------- | ---------------------------------------------------------- |
| [`eclipse-auto-build/`](eclipse-auto-build/README.md) | Eclipse JDT workspace auto-build | the Eclipse installation's JDT builder                     |
| [`fsmeta-auto-build/`](fsmeta-auto-build/README.md)   | one fs-meta daemon batch         | `jcodebuddy-compile`'s standalone ECJ incremental compiler |

## Measured result (2026-11-07)

Environment: Windows, JDK 25, Eclipse 4.35 at `D:\programs\eclipse` (JDT **3.41.50** pinned by
`bundles.info`), fs-meta daemon from commit `2da83ae` (which includes the filter from `f4be95d`), ECJ
**3.37.0** for the standalone compiler.
Synthetic independent classes; one edit (`C0`); three runs per size for the fs-meta side and the
recorded Eclipse runs for the same sizes. Times are milliseconds, median.

| Classes | Eclipse edit→POST_BUILD | Eclipse queue | Eclipse builder | fs-meta edit→compile | fs-meta compile | End-to-end speedup |
| ------: | ----------------------: | ------------: | --------------: | -------------------: | --------------: | -----------------: |
| 42      | **1017.8**              | ~1000         | 24–29           | **103**              | 2.44            | **9.9×**           |
| 200     | **1024.1**              | ~1000         | 34–43           | **85**               | 2.21            | **12.0×**          |
| 1000    | **1044.0**              | ~1017         | 27.0            | **140**              | 2.28            | **7.5×**           |

fs-meta’s root scheduler now defaults to a **50 ms tick** (per-root, 5–1000 ms; the scheduler’s 25 ms quiet floor
still applies). With `FSMETA_TICK_MS=10`, the same fixture measured **46 ms** at 42 classes and **47 ms** at 200
classes for the fs-meta path, so the tick can be tuned down on a fast SSD/editor without changing the compiler.

The Eclipse number is dominated by `AutoBuildJob.computeScheduleDelay()`: the auto-build job waits
about **1000 ms** to coalesce changes. The JDT builder's own work is only **24–43 ms**. The fs-meta
path has no such fixed delay: its end-to-end window is **~0.08–0.14 s** at the default tick, and the warm ECJ
compile itself is **~2.2–2.4 ms**.

The standalone compiler's one-time `index()` is ~153 ms and is **excluded** from the measured
window, as is Eclipse's initial full build.

### What this does and does not prove

- It proves that in this synthetic, single-edit workload, controlling batching through fs-meta
  removes Eclipse's ~1 s auto-build coalescing delay and makes the end-to-end feedback **7.5–12×**
  faster at the default 50 ms tick, or more when the tick is lowered.
- It does **not** compare identical compilers: Eclipse's registered JDT is 3.41.50, the standalone
  module uses ECJ 3.37.0. The `builder`/`compile` columns are therefore directional, not a
  compiler-version verdict.
- It is **not** the example project and not a multi-file dependency graph. The next extensions are
  the committed `hipster-entity-example` classpath, a burst of N edits, and an ECJ-version-matched
  comparison.
