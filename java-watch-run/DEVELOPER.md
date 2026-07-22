# java-watch-run — Developer Notes

Architecture and implementation details for contributors or anyone reading the source.

---

## Module overview

`java-watch-run` is a submodule of the `java_watch2` multi-module Maven project.

```
java_watch2/
├── java-watch-core/          ← file watching + filtering primitives (shared library)
├── java-watch-run/           ← this module: ECJ compilation + hot reload daemon
│   └── src/main/java/hr/hrg/watch2/run/
│       ├── HotSwapDaemon.java   main watch loop
│       ├── Reloader.java        compile → classload → invoke cycle
│       ├── Stoppable.java       lifecycle hook interface
│       ├── StopRegistry.java    static registry for Stoppable instances
│       └── ecj/                 in-memory incremental compiler sub-package
│           ├── IncrementalCompiler.java     top-level API
│           ├── CachingNameEnvironment.java  persistent JAR/JDK cache
│           ├── SourceUnit.java              ICompilationUnit wrapper
│           └── ClassFileWriter.java         ICompilerRequestor wrapper
└── java-watch-run-sample/    ← standalone test project (not a parent submodule)
    └── src/hr/hrg/watch2/sample/
        ├── SampleMain.java      timing banner + heartbeat + Stoppable demo
        ├── PersonData.java      Jackson POJO
        └── DataProcessor.java   Jackson serialize/deserialize round-trip
```

Additional scripts at the project root:
- `run-watch-sample.js` — Bun/Node script that builds and launches the daemon against the sample

---

## Dependency: java-watch-core

`HotSwapDaemon` depends on two classes from `java-watch-core`:

| Class                | Role                                                         |
| -------------------- | ------------------------------------------------------------ |
| `ManagedFileWatcher` | Wraps `io.methvin.directory-watcher`, adds per-file debounce |
| `FileFilter`         | Glob-based include/exclude filtering (pattern: `**.java`)    |

The watcher is configured with a debounce window (default **300 ms**). Internally `ManagedFileWatcher` uses an `AtomicInteger` sequence number per file path — if a second change arrives before the timer fires, the old task discards itself and a new 300 ms window starts.

`ManagedFileWatcher.start()` calls `watchAsync()` internally and returns immediately. `HotSwapDaemon.start()` parks the calling thread in a `synchronized wait` loop and wakes it in `stop()` via `notifyAll()`.

---

## Reload cycle in detail

Triggered by `Reloader.reload()`, called from a **single-threaded** `ExecutorService` (`hot-swap-reload` thread). All reload operations are serialised through this executor, so rapid file-system events can never cause concurrent ECJ runs.

```
file saved
  └─ ManagedFileWatcher debounce (300 ms)
       └─ HotSwapDaemon.triggerReload()  [submits to reloadExecutor]
            └─ Reloader.reload()
                 1. StopRegistry.stopAll()      ← notify previous app
                 2. joinPreviousMainThread()     ← wait ≤5s, then interrupt
                 3. closePreviousLoader()        ← URLClassLoader.close() → GC eligible
                  4. System.setProperty(          ← stamp trigger time for latency
                         "hotswap.trigger.ms", now)
                  5. IncrementalCompiler.compile() ← in-memory ECJ compile
                  6. new URLClassLoader([bin/])   ← fresh classloader
                 7. activeLoader.loadClass(main) ← load target
                 8. new Thread(main::invoke)     ← spawn hot-swap-main thread
                       └─ main() runs here
```

### Why a separate `hot-swap-main` thread?

If `main()` blocks (e.g. a server accept-loop), the `reloadExecutor` task would be stuck, making the next reload impossible. By spawning a separate **daemon thread** for the user's `main()`:

- The reload executor is free immediately after spawning.
- The user's app can block indefinitely.
- `joinPreviousMainThread()` on the next cycle waits gracefully, then interrupts if needed.

### URLClassLoader parent chain

```
Bootstrap ClassLoader
   └── System/App ClassLoader  ← java-watch-run.jar, slf4j, ECJ, all deps
         └── URLClassLoader    ← bin/  (new instance each reload)
               └── SampleMain, PersonData, DataProcessor, …
```

Because `Stoppable` and `StopRegistry` are loaded by the **System ClassLoader**, they are the same class objects across all reload generations. This is essential: a `Stoppable` registered by generation N is still a valid `Stoppable` from generation N+1's perspective.

---

## ECJ compile configuration (Incremental)

`Reloader` uses `hr.hrg.watch2.run.ecj.IncrementalCompiler` for file-change reloads. It replaces the standard `BatchCompiler` invocation with a more persistent model:

### 1 · `CachingNameEnvironment`
The single biggest bottleneck in ECJ batch mode is classpath scanning. The daemon solves this by:
- **Index Once**: At startup, all class bytes from all dependency JARs are loaded into a `HashMap`.
- **JDK jrt:/ fs**: All JDK module classes (Java 9+) are indexed from the `jrt:/` virtual filesystem.
- **O(1) Lookup**: The compiler resolves types via a Map lookup instead of opening many zip files.

### 2 · `IncrementalCompiler`
Maintains the `CachingNameEnvironment` and `CompilerOptions` across reloads. 
- **Lightweight**: Creates a fresh ECJ `Compiler` instance per call (cheap), but reuses the heavy name environment.
- **Tuned Options**: Disables all non-error analysis (doc parsing, javadoc linting, unused variable warnings) to minimise AST work.

### 3 · Latency Floor
By moving the name environment into memory, the "cold start" tax is eliminated. Perceived latency drops from ~80ms to **~15ms** on a warm JVM.

---

## `StopRegistry` — surviving classloader generations

```java
// In the target app (executed by URLClassLoader generation N):
StopRegistry.register(this);

// Before generation N+1 starts, the daemon calls:
StopRegistry.stopAll();   // → calls stop() on all registered Stoppables
```

`StopRegistry` is a `final` class with only `static` state. Because it is loaded by the System ClassLoader (not the URLClassLoader), the `List<Stoppable>` it holds persists across reloads. Each reload clears it via `stopAll()` so stale references from the previous generation don't accumulate.

Thread safety is provided by a `synchronized (LOCK)` block around the list.

---

## Latency measurement system property

`Reloader.PROP_TRIGGER_MS = "hotswap.trigger.ms"` is set to `System.currentTimeMillis()` immediately before `BatchCompiler.compile()`. The sample reads it:

```java
long latency = System.currentTimeMillis()
             - Long.parseLong(System.getProperty("hotswap.trigger.ms"));
// ≈ ECJ compile time + URLClassLoader init + reflection + thread start overhead
```

This lets developers verify that incremental compilation is actually fast after the first (cold) run.

---

## Sample project — `java-watch-run-sample/`

Standalone Maven project (not a parent submodule). Its `pom.xml`:
- Declares `<sourceDirectory>src</sourceDirectory>` (flat layout, not `src/main/java`).
- Binds `maven-dependency-plugin:copy-dependencies` to `generate-resources` → populates `lib/`.
- Declares `java-watch-run` as `<scope>provided</scope>` so `Stoppable`/`StopRegistry` resolve during `mvn compile`, but the jar isn't copied to `lib/` (it comes from the daemon's classpath at runtime).

### SampleMain — what to watch

| Element                               | Purpose                                                                               |
| ------------------------------------- | ------------------------------------------------------------------------------------- |
| `private static final String VERSION` | Edit this string to confirm a reload happened                                         |
| `static final long CLASS_LOAD_MS`     | Captured immediately on class load; delta to `main()` entry shows class-init overhead |
| Timing banner                         | Shows `hotswap.trigger.ms` delta = compile+load latency                               |
| Jackson demo                          | Proves `DataProcessor` round-trips correctly after reload                             |
| Heartbeat thread                      | Daemon thread printing every 5s; stops when `Stoppable.stop()` is called              |
| `StopRegistry.register(...)`          | Wires the heartbeat into the reload lifecycle                                         |

---

## Build

```bash
# Compile only
mvn compile -pl java-watch-run -am

# Package fat jar (strips signature files from ECJ/other signed jars)
mvn package -pl java-watch-run -am -DskipTests

# Install to local repo (required before building the sample)
mvn install -pl java-watch-run -am -DskipTests
```

The Shade plugin is configured with:
```xml
<filter>
  <artifact>*:*</artifact>
  <excludes>
    <exclude>META-INF/*.SF</exclude>
    <exclude>META-INF/*.DSA</exclude>
    <exclude>META-INF/*.RSA</exclude>
  </excludes>
</filter>
```
This is required because ECJ's jar is **signed**. Repackaging it into an uber-jar without stripping the signature files causes `java.lang.SecurityException: Invalid signature file digest`.

---

## Key design decisions and rationale

| Decision                                     | Rationale                                                                                                              |
| -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Use ECJ instead of `javax.tools` (javac API) | ECJ is a self-contained batch compiler with no JDK dependency at runtime; ships as a single ~4 MB jar                  |
| In-memory caching for dependencies           | Standard ECJ `FileSystem` (BatchCompiler) re-scans JARs on every call; in-memory `CachingNameEnvironment` is 4x faster |
| Eager `jrt:/` indexing                       | Reliable way to resolve JDK classes on Java 9+ without classloader resource stream limitations                         |
| `URLClassLoader` per reload, not per file    | Simplest isolation model; entire `bin/` is the unit of atomicity                                                       |
| System classloader as parent                 | Ensures daemon classes (`Stoppable`, `StopRegistry`, SLF4J, Jackson) are shared across generations without duplication |
| `ManagedFileWatcher` from java-watch-core    | Reuses existing debounce + filter logic; avoids duplicating the `DirectoryWatcher` dependency wiring                   |
| Single-threaded reload executor              | Prevents two concurrent compiles from racing on `bin/`                                                                 |
| ECJ `-proc:none`                             | Skips annotation processing; saves 50–200 ms per compile cycle                                                         |
| Daemon thread for `hot-swap-main`            | Prevents a blocking `main()` from starving the reload executor                                                         |

---

## Further reading noted in source

- <https://foojay.io/today/hot-class-reload-in-java-a-webpack-hmr-like-experience-for-java-developers/>
- JRebel architecture (proprietary, but useful conceptual reference for class-unloading limitations in the JVM)
