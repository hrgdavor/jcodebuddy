# java-watch-run

A lightweight Java watch-mode daemon. It monitors a source directory for `.java` file saves, **batch-collects** all changes within a configurable quiet window, **incrementally compiles** only the changed files with ECJ, and re-runs your `main` class in a fresh `URLClassLoader` — all without JVM restart.

Speed is a first-class concern. Every architectural decision optimises the latency between *file save* and *seeing the result* of the running program.

---

## Quick start

### 1 · Build the daemon fat jar

```bash
cd java_watch2
mvn package -pl java-watch-run -am -DskipTests
# → java-watch-run/target/java-watch-run.jar
```

### 2 · Prepare your project's runtime dependencies

Dependencies must be on the daemon's classpath. Easiest approach — collect them with the Maven Dependency Plugin:

```xml
<!-- in your project's pom.xml -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions>
    <execution>
      <id>copy-deps</id>
      <phase>generate-resources</phase>
      <goals><goal>copy-dependencies</goal></goals>
      <configuration>
        <outputDirectory>${project.basedir}/lib</outputDirectory>
        <includeScope>compile</includeScope>
      </configuration>
    </execution>
  </executions>
</plugin>
```

```bash
mvn generate-resources -f your-project/pom.xml
# → your-project/lib/*.jar
```

### 3 · Launch

```bash
java \
  -cp "java-watch-run.jar:your-project/lib/*" \
  hr.hrg.watch2.run.HotSwapDaemon \
  [--debounce=<ms>] \
  your-project/src \
  your-project/bin \
  com.example.YourMainClass \
  [args for main...]
```

> **Windows:** use `;` as separator and quote each `-D…` flag individually.

### Bundled sample (fastest way to try it)

```bash
bun run-watch-sample.js [--debounce=<ms>]
# All build + launch steps automated. Edit SampleMain.java → save → see reload.
```

---

## How it works: batching + incremental compilation

### Problem with naive watchers

A naive file-watcher delivers one event per file and triggers a compile immediately. This causes two problems:

1. **Multi-file saves**: saving three related classes in quick succession triggers three separate compile+reload cycles instead of one.
2. **Full recompile on every save**: passing the entire `src/` directory to ECJ means compiling 100 files when only 1 changed.

### Solution 1 — Batch debounce (`BatchedFileWatcher`)

All events within a configurable **quiet window** (default 300 ms) are accumulated into a single `ChangeSet`. Only one compile+reload cycle fires per save-burst.

The debounce uses a *trailing-edge* strategy: the timer resets on each new event, so the batch fires `debounce ms` after the **last** change in the burst.

```
t=0ms  SampleMain.java saved    ─── debounce resets
t=50ms PersonData.java saved    ─── debounce resets
t=350ms quiet window expires    ──▶ ONE compile of {SampleMain, PersonData}
```

This also handles **DELETE events**, which naive watchers ignore. When a `.java` file is deleted, the daemon removes the corresponding `.class` files and performs a full recompile so broken references are surfaced as errors.

**OS overflow events** (when the kernel drops events due to queue pressure) are forwarded as a `fullRecompile` signal so no change is silently missed.

### Solution 2 — In-memory Incremental Compiler

Standard `BatchCompiler` re-scans every JAR on the classpath for every invocation. For a project with even a few dependencies, this adds 30–60ms of overhead before compilation even starts.

The daemon solves this by maintaining a persistent **`CachingNameEnvironment`**:
1. **Eager JAR Indexing**: At startup, it loads all class bytes from dependency JARs into a `HashMap`.
2. **JRT Filesystem**: It indexes the `jrt:/` virtual filesystem to quickly resolve JDK module classes (Java 9+).
3. **In-Memory Cache**: Subsequent `findType` calls by the compiler are simple O(1) map lookups.

This drops the incremental compile work from ~80ms (standard ECJ) to **~15ms**, while still correctly handling transitive staleness.

### When incremental is NOT used (fallback to full)

| Condition         | Reason                                                   |
| ----------------- | -------------------------------------------------------- |
| Startup           | `bin/` is empty, no prior state                          |
| File deleted      | `.class` removed; broken references need full error scan |
| OS overflow event | Unknown set of changes; safe to recompile all            |

---

## Measured latency benchmarks

Measured on 3-file sample project (Jackson + SLF4J), ECJ cold/warm, `--debounce=0`.

| Phase                            | Cold JVM   | Warm JVM    | Notes                                      |
| -------------------------------- | ---------- | ----------- | ------------------------------------------ |
| Debounce window                  | 0–300 ms   | 0–300 ms    | Configurable; 0 = immediate                |
| `stop` — async stop signal       | **0 ms**   | **0 ms**    | Now overlaps with ECJ compile              |
| `ecj` — Full compile (startup)   | **710 ms** | **~300 ms** | Cold = ECJ itself JIT-ing                  |
| `indexing` — JAR + JDK caching   | **754 ms** | **N/A**     | Background; happens *after* startup reload |
| `ecj` — Incremental (1st reload) | **23 ms**  | **~20 ms**  | After JIT priming run                      |
| `ecj` — Incremental (Hot cache)  | **15 ms**  | **14 ms**   | **The speed floor for feedback**           |
| `spawn` + `classload`            | **2 ms**   | **1 ms**    | Negligible                                 |
| **Total perceived latency (v3)** | **~17 ms** | **~30 ms**  | **Debounce=0, Warm JVM, save-to-output**   |

### What the daemon logs (sample project)

```
⚡ Reload [full]  total=710ms  stop=1ms  ecj=705ms  classload=3ms  spawn=1ms
CachingNameEnvironment: indexed 27688 JDK classes from jrt:/ in 600ms
CachingNameEnvironment: 2092 dep-types indexed in 754ms total
ECJ warm-up done (792ms total incl. indexing, ok=true) – incremental path is JIT-compiled.
...
⚡ Reload [1-file incremental]  total=15ms  stop=0ms  ecj=14ms  classload=1ms  spawn=0ms

╔══════════════════════════════════════════════════════╗
║  ⚡ Hot-reload at 23:05:53.102                       ║
║     VERSION  : v3 — edit me and save!                ║
║     Total latency   (save → output) :   30 ms        ║
║     Internals       (compile+load)  :   28 ms        ║
║     main() overhead (static init)   :    0 ms        ║
╚══════════════════════════════════════════════════════╝
```

### Perception vs. reality (Total Latency)

Perceived latency is `debounce + ecj + classload + startup_overhead` (app teardown is now done asynchronously in parallel with `ecj`).

| Scenario           | Total Latency | Bottleneck                 |
| ------------------ | ------------- | -------------------------- |
| Debounce 300, Cold | **~1000 ms**  | Debounce window + Cold ECJ |
| Debounce 300, Warm | **~315 ms**   | Debounce window            |
| Debounce 0, Warm   | **~15 ms**    | **Instantaneous feedback** |

---

## Configuring the debounce window

The debounce is the single biggest tuning knob for perceived latency. Lower = faster feedback, but too low risks triggering on a partial file write from your editor.

**To change it:**

```bash
# CLI flag (recommended — no rebuild needed)
java -cp "..." hr.hrg.watch2.run.HotSwapDaemon --debounce=50 src bin com.example.Main

# Via the sample script
bun run-watch-sample.js --debounce=50

# Via constructor (programmatic use)
new HotSwapDaemon(srcDir, binDir, mainClass, mainArgs, debounceMs, extraClasspath)
```

**Recommended values:**

| Setting       | Value    | When to use                                                             |
| ------------- | -------- | ----------------------------------------------------------------------- |
| Default       | `300`    | Safe choice; works with all editors                                     |
| Aggressive    | `50–100` | Modern editors that write atomically                                    |
| Maximum speed | `0`      | Editor guaranteed to write atomically (e.g. IntelliJ with "safe write") |
| Conservative  | `500`    | Slow disk / network filesystem                                          |

> **IntelliJ "Safe Write":** When enabled (default), IntelliJ writes to a temp file then atomically renames it. The watcher sees only one event (the rename), so `--debounce=0` is safe.
>
> **VS Code:** Writes directly. Very fast saves may write in multiple chunks — `--debounce=50` is a safer minimum.

---

## Further steps to measure and optimise

Speed between file-save and visible output is the core metric. Here is a structured optimisation roadmap:

### `[DONE]` In-memory caching (`CachingNameEnvironment`)
Eliminated JAR/JDK re-scanning. Incremental compile overhead dropped to ~15ms.

### `[DONE]` hotswap.trigger.ms latency measurement
Accurate split of compile time vs classload time vs app startup time.

### `[DONE]` Reduce debounce to ~0 ms safely
**Goal:** eliminate the 300ms dead wait. Active atomic rename detection (DELETE followed symmetrically by CREATE) allows synchronous batch flush without delay on 'Safe Write' editors.

### `[DONE]` File-save timestamp as `T_save`
**Goal:** measure the *true* total latency from the moment the editor writes the file.
`hotswap.file.save.ms` captures the exact instant the OS filesystem event dropped into the daemon, giving a real-world end-to-end user latency measurement.

### `[DONE]` Async stop of previous run
**Goal:** overlap `StopRegistry.stopAll()` with ECJ compilation to hide the shutdown cost.
App shutdown runs in the background concurrently with compilation and is only joined immediately before the classloader swap.

---

### `[TODO]` Per-file dependency tracking

**Goal:** avoid recompiling files that transitively depend on the changed file when those dependants haven't actually changed in a semantically meaningful way.

**Approach:** after each compile, record a `Map<Path, Set<Path>> dependsOn` (which source files each compiled class referenced). On the next save, only add a file to the batch if it, or one of its dependencies, is in the changed set.

**Caveat:** ECJ already does transitive staleness detection via source-newer-than-class timestamps—this optimisation only matters for large projects where even the staleness scan is slow.

---

### `[TODO]` Parallel ECJ invocations for independent modules

**Goal:** if the source tree has naturally independent packages (no cross-package references), compile them in parallel.

**Approach:** add a `--modules` concept: multiple `(srcDir, binDir)` pairs compiled concurrently via a fixed thread pool, merged before classloading.

---

### `[DONE]` ECJ warm-up compile on startup

After the startup full compile, the daemon immediately submits an incremental compile of a single source file to the **same executor queue**. Because the executor is single-threaded, this runs *after* the app has started (no user-visible delay), forcing the JVM to JIT-compile all of ECJ's incremental code paths. The first real file-change reload benefits from already-warmed JIT paths.

Warm-up log output:
```
ECJ warm-up compile: hr\hrg\watch2\sample\DataProcessor.java
ECJ warm-up done (33ms, ok=true) – incremental path is now JIT-compiled.
```

---

### `[TODO]` Automated latency regression test

**Goal:** detect regressions in reload speed automatically (CI-friendly).

**Approach:**
1. Start the daemon programmatically in a test.
2. Write a known `.java` file to `src/`.
3. Wait for `SampleMain` to print the reload banner.
4. Assert that the latency printed is below a threshold (e.g. 500ms on warm JVM).

This can be done by having the test redirect the daemon's stdout and parsing the `Reload latency` line.

---

### `[DONE]` 📉 GraalVM Native Image & Espresso Capabilities

The `java-watch-run` daemon now officially embraces full statically-linked **GraalVM Native Image** while actively defeating the Native "Closed-World Assumption" by cleanly injecting dynamic classes on the fly into an embedded **Espresso (Java on Truffle JVM)** context! 

#### Critical Build Metrics
- **Build Times**: Compiling via `mvn package -Pnative` with `organic Truffle dependencies` wraps the complete dynamic subset cleanly in **~2 mins 12 secs** leveraging completely parallel compilation!
- **Strict Version Match**: GraalVM explicitly verifies native-layer bounds. We resolved build errors perfectly by matching standard `org.graalvm.polyglot` Maven dependencies (e.g., `25.0.2`) purely 1:1 against the underlying Native engine host (JDK 25.0.0 LTS) disabling out-of-sync warnings trivially.
- **Resource Bundles & ECJ Missing Files**: We detected standard `URLClassLoader` implicitly fetches Eclipse metadata natively, while Native Image's aggressive static analysis fails when running `ECJ IncrementalCompiler` with AOT: `Missing resource : org/eclipse/jdt/internal/compiler/batch/messages.properties`.
  - **The Fix**: ECJ Native wrappers simply require standard *GraalVM Reachability Metadata* mapped so the static compiler injects exactly `.properties` bundles necessary to prevent early exceptions in standard JIT loop initializations!
  - **Summary**: The daemon C code executed the filesystem `WatchService` and the Espresso embedded subsystem beautifully on Windows executing instantaneously with a massive JVM footprint cutdown, representing true AOT performance!

## Graceful shutdown hook — `Stoppable`

Register a shutdown callback so the daemon can stop your app cleanly before each reload:

```java
import hr.hrg.watch2.run.Stoppable;
import hr.hrg.watch2.run.StopRegistry;

public class MyServer implements Stoppable {

    private volatile boolean running;
    private ServerSocket server;

    public void start() throws Exception {
        StopRegistry.register(this);         // ← hook into the daemon lifecycle
        running = true;
        server  = new ServerSocket(8080);
        // accept loop in a background thread so main() returns quickly
        new Thread(this::acceptLoop, "accept").start();
    }

    @Override
    public void stop() {
        running = false;
        try { server.close(); } catch (IOException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        new MyServer().start();
        // main() returns here — reload thread is immediately free
    }
}
```

- `StopRegistry` is in the daemon's classloader → persists across reload generations.
- The daemon waits up to **5 seconds** for the previous thread to exit, then interrupts.

---

## Latency measurement in your own app

```java
public static void main(String[] args) {
    // T0: when ECJ started (set by Reloader right before BatchCompiler.compile())
    long t0 = Long.parseLong(
        System.getProperty("hotswap.trigger.ms",
                           String.valueOf(System.currentTimeMillis())));

    long compileAndLoadMs = System.currentTimeMillis() - t0;
    System.out.printf("⚡ Reload latency (compile+load): %d ms%n", compileAndLoadMs);
}
```

To also capture the debounce time, log the timestamp when the watcher fires `triggerFullReload`/`triggerIncrementalReload` (visible in the daemon log line `--- Incremental reload triggered ---`).

---

## CLI reference

```
java -cp <classpath> hr.hrg.watch2.run.HotSwapDaemon [options] <srcDir> <binDir> <mainClass> [mainArgs...]

Options (must appear before positional args):
  --debounce=<ms>   Quiet window after the last save before reload fires.
                    Default: 300. Range: 0–∞. Recommended for most editors: 50–300.

Positional:
  srcDir      Directory containing .java source files.
  binDir      Output directory for .class files (created if missing).
  mainClass   Fully-qualified class with public static void main(String[]).
  mainArgs    Forwarded verbatim to main().
```

### Useful JVM flags

| Flag                                                   | Effect                       |
| ------------------------------------------------------ | ---------------------------- |
| `-Dorg.slf4j.simpleLogger.showDateTime=true`           | Timestamps on every log line |
| `-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS` | Millisecond precision        |
| `-Dorg.slf4j.simpleLogger.levelInBrackets=true`        | `[INFO]` bracket style       |
