# jcodebuddy-compile — a standalone ECJ incremental compiler

A small library that compiles **the Java source files a caller says have changed** into a class output directory,
using Eclipse's ECJ compiler in process. It exists so a benchmark or a dev-time tool can measure and use
incremental compilation without pulling in a hot-swap daemon, an slf4j binding, a dynamic class loader or a
watcher.

## What this is, and what it is not

- It **is** a benchmark and dev-time compiler helper. `compile(Set<Path>)` is the whole job.
- It **does not start a watcher**, a daemon, a thread, a server or a dynamic class loader, and it never defines
  or instantiates a class. There is no `WatchService`, no relaunch/dynamic-reload code, and no
  `hr.hrg.watch2.*` import in this module. The only use of a class loader is reading `.class` resource bytes for
  type resolution.
- **fs-meta is the change source.** The fs-meta daemon owns watching (one Zig watch loop per root, one Unix
  socket, content hashes and batches). A tool subscribes to its change stream, decides which paths matter, and
  hands those paths to this compiler. This module only knows that a set of paths changed — never why or how it
  learned so.
- It is **not** a replacement for `javac`/Maven in a build, and it does not do annotation processing or code
  generation.

## API

Everything is in `hr.hrg.jcodebuddy.compile`; the only public type is `EcjIncrementalCompiler`.

| member                                                                                       | meaning                                                                                          |
| -------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `EcjIncrementalCompiler(Path sourceRoot, Path binDir, String classpath)`                     | source root, class output directory, effective classpath (JARs and directories)                  |
| `EcjIncrementalCompiler(Path sourceRoot, Path binDir, String classpath, SharedIndex shared)` | share the expensive JAR/JDK index with compilers over the same classpath                         |
| `SharedIndex`                                                                                | one index shared by several compilers; create it once, then pass it to each constructor          |
| `void index()`                                                                               | one-time, idempotent index of the classpath JARs and the JDK package set. Called automatically by the first compile if omitted |
| `boolean compile(Set<Path> changedFiles)`                                                    | compile exactly these files; `true` when all of them compiled without an error. Never throws for a compile/read/path problem |
| `boolean compileOne(Path changedFile)`                                                       | compile one file immediately (a complete public-API compile); same result contract as `compile(Set)` |
| `EarlyCompilation beginEarly(Path changedFile)`                                              | opt-in internal phase split: parse/build bindings now, complete resolution/cross-checks and codegen later |
| `boolean completeEarly(EarlyCompilation early)`                                              | finish a `beginEarly` compilation; the handle is single-use                                      |
| `long lastCompileNanos()`                                                                    | wall-clock duration of the last `compile()`, excluding the index — the number a benchmark quotes |
| `long lastIndexNanos()`                                                                      | duration of the last `index()` call                                                              |
| `List<String> lastErrors()`                                                                  | immutable diagnostics of the last compile: `file:line  message`, or a read/write failure         |
| `static String buildClasspath(Path binDir, List<String> extra)`                              | `binDir` + `java.class.path` + extras, skipping empty entries                                    |

A typical call sequence:

```java
var compiler = new EcjIncrementalCompiler(sourceRoot, binDir, classpath);
compiler.index();                                  // one-time cost

long start = System.nanoTime();
boolean ok = compiler.compile(Set.of(changedFile)); // this is what the benchmark measures
long nanos = compiler.lastCompileNanos();
if (!ok) {
    compiler.lastErrors().forEach(System.err::println);
}
```

`lastCompileNanos()` is measured inside `compile()` around reading the source files and ECJ's compile, so it does
not include `index()`. The compiler is not thread-safe; use it from one thread.

A multi-module host should create one `SharedIndex` for the project classpath and pass it to every
compiler. Without it, each compiler keeps its own copy of every dependency JAR's class bytes, which can
exhaust the IDE heap on a large Maven reactor.

### Starting before `END_BATCH` (opt-in internal phase)

`beginEarly(Path)` / `completeEarly(handle)` expose ECJ's protected phase split:

```text
beginEarly:      beginToCompile()    → parse + build type bindings
completeEarly:   processCompiledUnits() → resolution/cross-checks + codegen
```

The early phase is useful for a one-file fs-meta batch: its parse overlaps the rest of the batch window. It is
**opt-in** because it uses ECJ internals:

- a **one-file** batch can be completed from the same ECJ compiler at `END_BATCH`;
- a **multi-file** batch cannot append units to an already-started ECJ compiler, so it must fall back to the normal
  changed-set `compile(Set<Path>)` at `END_BATCH`; that is where ECJ resolves types that changed in the same batch;
- if `beginEarly` fails, `completeEarly` is never called and the caller falls back to `compile(Set<Path>)`.

The adapter is deliberately one small `PhaseCompiler` subclass. If a future ECJ changes or removes
`beginToCompile`/`processCompiledUnits`, the build fails at that boundary and only that adapter needs a fork or a
fallback; the normal `compile(Set<Path>)` path is independent of it.

`compileOne(Path)` is a separate, public-API convenience: it performs a complete compile of one file immediately.
It is also opt-in and is not the internal phase split.

ECJ 3.46.100 is the current dependency. Its `ICompilationUnit` asks for a module binding when the name
environment is not module-aware; this module's units answer the unnamed module explicitly, because they are
classpath sources. The compiler still uses ECJ's internal `Compiler` API deliberately, with the
`ClassFileWriter` null-safe for a unit that produces no code.

## Why it is incremental

One `CachingNameEnvironment` lives for the whole compiler lifetime:

- `index()` scans each classpath JAR's central directory and records the **type name and JAR entry location**,
  not the class bytes. A dependency class is decompressed only when ECJ first asks for it and is kept in a
  bounded LRU hot cache (default 32 MB, `-Djcodebuddy.compile.jarCacheBytes=<bytes>`). Unused classes stay in
  the JAR and are served by the OS page cache.
- JDK packages are enumerated from `ModuleLayer.boot()`; JDK class bytes are read on first use from the module that
  owns the package and cached, so each type is read once and resolution does not depend on a class loader a
  launcher may have replaced.
- Classes this project compiled earlier are resolved from `binDir` on every lookup, so a change in one file does
  not force the files that depend on it to be recompiled.

Each `compile()` then:

1. builds `SourceUnit`s for the changed files only,
2. resets and reuses the same `ClassFileWriter`,
3. creates a **fresh** ECJ `Compiler` (per-compilation state must not leak between rounds; the name environment
   is the expensive half and is reused),
4. compiles, writes the class files and records problems.

### Why JARs are read on demand instead of mmapped

JAR entries are usually compressed, so mapping the JAR file does not give ECJ a usable class byte array
without decompression. The index therefore stores entry locations, and `JarFile` reads one entry when ECJ
asks for it; the operating system's page cache keeps hot JAR pages resident without a Java-heap copy. Only
the bounded LRU hot set is in heap. A future decompressed cache could use a memory-mapped file, but it
would add a cache format, invalidation and cleanup for little benefit over the OS page cache.

ECJ options are built once: Java 21 source/compliance/target, warnings and Javadoc analysis off, annotation
processing off, debug attributes on. A project that needs a newer language level changes `buildOptions()`.

## Dependencies

The module declares exactly two: `org.eclipse.jdt:ecj` (version managed by the root POM's `${ecj.version}`) and
JUnit 5 (test scope). There is **no** slf4j, no Jackson, no OpenRewrite, and no dependency on
`java-watch-run`, `java-watch-core` or any other `hr.hrg.watch2.*` artifact. Diagnostics are reported through
`lastErrors()`, not through a logging framework.

The module itself builds with the reactor's `maven.compiler.release=25`; its sources use no syntax newer than
Java 21, and the class files it *emits* for compiled projects are Java 21.

## Tests

`EcjIncrementalCompilerTest` writes its fixtures into a JUnit `@TempDir` (they are compiler input, not test
source) and covers:

- one changed source compiles and its `.class` appears in the package directory;
- a second compile, with no re-index, resolves a type compiled by the first;
- a compile error returns `false` with a diagnostic, throws nothing, and writes no class file;
- a failed compile does not poison the next one;
- nested packages land in their directory;
- a differently-cased source directory on Windows still yields the on-disk package name;
- a source outside the source root, and a missing source, are reported as failures.

Run it with:

```text
bun scripts/mvn-fast.js --off-cache -pl :jcodebuddy-compile -am test
```

## Divergence from `watch/java-watch-run/src/main/java/hr/hrg/watch2/run/ecj/`

The implementation is copied and adapted from the frozen watch module. Behavioural changes, all deliberate:

| area                    | old implementation                                                            | here                                                                                       |
| ----------------------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| dependency              | `java-watch-core`, slf4j, daemon/relaunch code around it                      | only `ecj`; slf4j replaced by `System.Logger`, no watcher/relaunch code                    |
| package                 | `hr.hrg.watch2.run.ecj`                                                       | `hr.hrg.jcodebuddy.compile`                                                                |
| `binDir` hand-off       | package-private field assigned after construction                             | constructor argument on `CachingNameEnvironment`                                           |
| JDK class bytes         | eagerly walked the whole `jrt:/` tree into memory (~27k classes) at `index()` | loaded on demand through the boot-layer module that owns the package and cached; JDK packages still come from `ModuleLayer.boot()`, so `index()` stays cheap and memory stays small |
| JAR class bytes         | every class of every classpath JAR kept in a heap map                         | only type names/locations indexed; class bytes read on demand and held in a bounded LRU hot cache, so unused dependency classes stay out of heap |
| index lifecycle         | caller had to call `index()` first                                            | `compile()` indexes once if the caller did not                                             |
| package lookup          | `isPackage` answered from JAR/JDK packages only                               | also probes `binDir`, so packages this project compiled itself are recognised              |
| package prefixes        | a side effect of the eager `jrt:` walk (`java`, `javax`, …)                   | `registerPackagePath` registers every prefix of every `ModuleLayer.boot()` package         |
| package/case derivation | `srcDir.relativize(file)`                                                     | `toRealPath()` first, so a caller's case spelling cannot become a wrong package name       |
| diagnostics             | logged through slf4j                                                          | `lastErrors()` snapshot; null-safe formatting; clear message for a source outside the root |
| timing                  | none                                                                          | `lastCompileNanos()` and `lastIndexNanos()`                                                |
| `buildClasspath`        | always appended `java.class.path`, even when empty                            | skips empty entries (an empty entry means "current directory")                             |

The incremental properties the old implementation was built for are unchanged: JARs are indexed once, a fresh
ECJ `Compiler` is created per call, the `ClassFileWriter` is reused, and no compile re-scans a JAR.
