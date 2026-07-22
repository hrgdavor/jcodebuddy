// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.run;

import hr.hrg.watch2.core.ChangeSet;
import hr.hrg.watch2.run.ecj.IncrementalCompiler;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import hr.hrg.watch2.core.ChangeSet;
import hr.hrg.watch2.run.ecj.IncrementalCompiler;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.slf4j.Logger;

/**
 * Manages the compile → classload → invoke cycle for a single hot-reload
 * iteration.
 *
 * <h2>Two compilation modes</h2>
 * <dl>
 * <dt>{@link #reload()} — full compile</dt>
 * <dd>Passes the entire {@code srcDir} to ECJ. Used on startup and as a
 * fallback after a DELETE event forces a full rescan.</dd>
 *
 * <dt>{@link #reload(ChangeSet)} — incremental compile</dt>
 * <dd>Passes <em>only</em> the changed files to ECJ, together with
 * {@code -sourcepath srcDir} so type references can be resolved from
 * source, and {@code bin/} added to {@code -cp} so ECJ can reuse the
 * previously compiled {@code .class} files for unchanged types. For
 * a project with N source files and k modified files (k ≪ N) this
 * reduces compile work from O(N) to O(k + transitive dependants of k).
 * </dd>
 * </dl>
 *
 * <h2>Reload cycle</h2>
 * 
 * <pre>
 *   1. StopRegistry.stopAll()       – notify previous application run
 *   2. joinPreviousMainThread()     – wait ≤5s, then interrupt
 *   3. deleteClassFiles() (if any)  – clean up .class for deleted .java files
 *   4. closePreviousLoader()        – URLClassLoader.close() → GC eligible
 *   5. set hotswap.trigger.ms       – timestamp property for latency measurement
 *   6. BatchCompiler.compile(...)   – ECJ full or incremental
 *   7. new URLClassLoader([bin/])   – fresh class loader
 *   8. new Thread(main::invoke)     – spawn hot-swap-main daemon thread
 * </pre>
 */
public class Reloader {
    private static final Logger log = LoggerFactory.getLogger(Reloader.class);

    /** Max wait for the previous main thread to exit before interrupting it. */
    private static final long SHUTDOWN_WAIT_MS = 5_000;

    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET = "\u001B[0m";

    /**
     * System property set to {@code System.currentTimeMillis()} immediately
     * before ECJ is invoked. Target applications read this to compute their
     * own hot-reload latency (compile + classload + thread-start overhead).
     */
    public static final String PROP_TRIGGER_MS = "hotswap.trigger.ms";

    // ── Configuration ─────────────────────────────────────────────────────────

    private final Path srcDir;
    private final Path binDir;
    private final String mainClass;
    private final String[] mainArgs;
    private final List<String> extraClasspath;

    // ── Mutable state ─────────────────────────────────────────────────────────

    /** Active URLClassLoader; {@code null} before the first successful compile. */
    private URLClassLoader activeLoader;

    /**
     * Active Polyglot Context when running inside GraalVM Espresso.
     */
    private Context activeContext;

    /**
     * Pre-calculated system property to determine if to use Espresso.
     * True if "hotswap.usePolyglot" is set to true OR running natively.
     */
    private final boolean usePolyglot;
    private final boolean color;

    /**
     * Thread currently running the target's {@code main} method.
     * Tracked for join/interrupt on the next reload cycle.
     */
    private volatile Thread activeMainThread;

    public Thread getActiveMainThread() {
        return activeMainThread;
    }

    /**
     * In-memory incremental compiler with a persistent name environment.
     * Created lazily on the first incremental compile, after {@link #warmUp(Path)}
     * has indexed the classpath.
     */
    private IncrementalCompiler incrementalCompiler;

    // ── Constructors ──────────────────────────────────────────────────────────

    public Reloader(Path srcDir, Path binDir, String mainClass, String[] mainArgs,
            List<String> extraClasspath, boolean color) {
        this.srcDir = srcDir;
        this.binDir = binDir;
        this.mainClass = mainClass;
        this.mainArgs = mainArgs == null ? new String[0] : mainArgs;
        this.extraClasspath = extraClasspath == null ? List.of() : extraClasspath;
        this.color = color;

        // Detect native image execution via system properties implicitly
        boolean isNative = "executable".equals(System.getProperty("org.graalvm.nativeimage.kind"));
        this.usePolyglot = isNative || "true".equals(System.getProperty("hotswap.usePolyglot", "false"));
    }

    public Reloader(Path srcDir, Path binDir, String mainClass, String[] mainArgs) {
        this(srcDir, binDir, mainClass, mainArgs, List.of(), true);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Full-recompile cycle. Used on startup or as a fallback when the
     * complete source state is unknown (e.g. after a DELETE event).
     *
     * @return {@code true} if compilation succeeded and a new main thread started
     */
    public boolean reload() {
        log.info("Full recompile of {}", srcDir);
        return doReload(null, 0L); // null signals: compile the whole srcDir
    }

    /**
     * Incremental reload driven by a {@link ChangeSet} from
     * {@link hr.hrg.watch2.core.BatchedFileWatcher}.
     *
     * <ul>
     * <li>If {@link ChangeSet#fullRecompile()} → falls back to full recompile.</li>
     * <li>If there are deletions → removes stale {@code .class} files, then
     * falls back to a full recompile so that references to the deleted
     * type are correctly reported as errors.</li>
     * <li>Otherwise → ECJ is given only {@link ChangeSet#changed()} files,
     * with {@code -sourcepath} and {@code bin/} on {@code -cp}, allowing
     * unchanged types to be served from the previous compile's output.</li>
     * </ul>
     *
     * @param changeSet batch delivered by the file watcher
     * @return {@code true} if compilation succeeded and a new main thread started
     */
    public boolean reload(ChangeSet changeSet) {
        if (changeSet.fullRecompile()) {
            log.info("Overflow event – falling back to full recompile.");
            return doReload(null, changeSet.firstEventMs());
        }

        boolean hadDeletes = !changeSet.deleted().isEmpty();

        if (hadDeletes) {
            // Remove .class files for deleted sources so the full recompile
            // doesn't re-link against definitions that no longer exist.
            for (Path deleted : changeSet.deleted()) {
                deleteClassFiles(deleted);
            }
            log.info("Deleted {} source file(s) – falling back to full recompile "
                    + "to surface any broken references.", changeSet.deleted().size());
            return doReload(null, changeSet.firstEventMs());
        }

        if (changeSet.changed().isEmpty()) {
            log.debug("ChangeSet is empty – nothing to do.");
            return false;
        }

        log.info("Incremental recompile: {} changed file(s)", changeSet.changed().size());
        return doReload(changeSet.changed(), changeSet.firstEventMs());
    }

    // ── Core reload cycle ─────────────────────────────────────────────────────

    /**
     * Shared implementation.
     *
     * @param changedFiles {@code null} → compile entire srcDir;
     *                     non-null → compile only those files (incremental)
     * @param firstEventMs timestamp of the first file modification (or 0L)
     */
    private boolean doReload(Set<Path> changedFiles, long firstEventMs) {
        final long t0 = System.currentTimeMillis();

        // Phase 1 — signal previous run to stop
        // We do not wait for it to exit yet; we overlap shutdown with ECJ compile.
        StopRegistry.stopAll();
        Thread prevMain = activeMainThread;
        URLClassLoader prevLoader = activeLoader;
        Context prevContext = activeContext;
        activeMainThread = null;
        activeLoader = null;
        activeContext = null;

        final long t1 = System.currentTimeMillis();

        // Phase 2 — ECJ compile
        // Set trigger timestamp *before* ECJ so apps can measure compile+load latency.
        System.setProperty(PROP_TRIGGER_MS, String.valueOf(t1));
        if (firstEventMs > 0) {
            System.setProperty("hotswap.file.save.ms", String.valueOf(firstEventMs));
        } else {
            System.setProperty("hotswap.file.save.ms", String.valueOf(t1));
        }

        boolean ok = (changedFiles == null) ? compileAll() : compileIncremental(changedFiles);
        final long t2 = System.currentTimeMillis();

        // Phase 2.5 — wait for previous run to exit and release resources
        // By joining *after* ECJ, we hide the shutdown latency behind the compile time.
        joinPreviousMainThread(prevMain);
        closePreviousLoader(prevLoader);
        closePreviousContext(prevContext);

        if (!ok) {
            log.warn("Compilation failed – skipping reload.  [stop={}ms  ecj={}ms]",
                    t1 - t0, t2 - t1);
            return false;
        }

        // Phase 3 — create class execution environment
        Runnable mainTask;
        if (usePolyglot) {
            mainTask = setupPolyglotExecution();
        } else {
            mainTask = setupUrlClassLoaderExecution();
        }

        if (mainTask == null)
            return false;
        final long t3 = System.currentTimeMillis();

        // Phase 4 — spawn hot-swap-main thread
        activeMainThread = new Thread(mainTask, "hot-swap-main");
        activeMainThread.setDaemon(true);
        activeMainThread.start();
        final long t4 = System.currentTimeMillis();

        // ── Timing summary ─────────────────────────────────────────────────────
        String mode = (changedFiles == null)
                ? "full"
                : changedFiles.size() + "-file incremental";
        String msg = String.format("⚡ Reload [%s]  total=%dms  stop=%dms  ecj=%dms  classload/context=%dms  spawn=%dms",
                mode,
                t4 - t0, // total (excludes time after spawn, e.g. static init in main)
                t1 - t0, // stop+join+close
                t2 - t1, // ECJ
                t3 - t2, // ClassLoader / Context init latency
                t4 - t3); // thread creation + start()
        
        if (color) {
            log.info("{}{}{}", ANSI_YELLOW, msg, ANSI_RESET);
        } else {
            log.info(msg);
        }

        return true;
    }

    private Runnable setupUrlClassLoaderExecution() {
        Class<?> clazz;
        Method main;
        try {
            URL binUrl = binDir.toUri().toURL();
            activeLoader = new URLClassLoader(new URL[] { binUrl }, ClassLoader.getSystemClassLoader());
            clazz = activeLoader.loadClass(mainClass);
            main = clazz.getMethod("main", String[].class);
            return () -> {
                try {
                    main.invoke(null, (Object) mainArgs);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptedException) {
                        log.debug("hot-swap-main interrupted (expected on reload).");
                    } else {
                        log.error("Exception in {}.main: {}", mainClass,
                                cause != null ? cause.getMessage() : e.getMessage(),
                                cause != null ? cause : e);
                    }
                } catch (Exception e) {
                    log.error("Error invoking {}.main: {}", mainClass, e.getMessage(), e);
                }
            };
        } catch (Exception e) {
            log.error("Failed to set up classloader for {}: {}", mainClass, e.getMessage(), e);
            return null;
        }
    }

    private Runnable setupPolyglotExecution() {
        try {
            String guestCp = binDir.toAbsolutePath().toString() + File.pathSeparator
                    + System.getProperty("java.class.path");
            activeContext = Context.newBuilder("java")
                    .allowAllAccess(true)
                    .allowHostAccess(HostAccess.ALL)
                    .option("java.Classpath", guestCp)
                    .build();

            Value guestMainClass = activeContext.getBindings("java").getMember(mainClass);
            if (guestMainClass == null) {
                log.error("Class '{}' not found in guest context.", mainClass);
                return null;
            }
            if (!guestMainClass.hasMember("main")) {
                log.error("No public static main in '{}'", mainClass);
                return null;
            }

            return () -> {
                try {
                    guestMainClass.invokeMember("main", (Object) mainArgs);
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("InterruptedException")) {
                        log.debug("Guest context interrupted (expected on reload).");
                    } else {
                        log.error("Error executing {}.main via Espresso: {}", mainClass, e.getMessage(), e);
                    }
                }
            };
        } catch (Exception e) {
            log.error("Failed to setup Polyglot context: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Indexes the classpath (if not already done) and performs a warm-up
     * incremental
     * compile of {@code file} using the {@link IncrementalCompiler}.
     *
     * <p>
     * Calling this after startup forces:
     * <ol>
     * <li>{@link hr.hrg.watch2.run.ecj.CachingNameEnvironment#index()} — loads all
     * JAR class bytes into memory (~200–500 ms, one time only).</li>
     * <li>One incremental compile of a real source file — primes ECJ's JIT
     * code paths so the first real file-change compile is fast.</li>
     * </ol>
     *
     * @param file any {@code .java} file that exists in {@code srcDir}
     */
    void warmUp(Path file) {
        if (!Files.exists(file)) {
            log.debug("Warm-up skipped – file does not exist: {}", file);
            return;
        }
        long t0 = System.currentTimeMillis();

        // Ensure IncrementalCompiler exists and JAR index is loaded.
        if (incrementalCompiler == null) {
            String cp = buildClasspath(true);
            incrementalCompiler = new IncrementalCompiler(srcDir, binDir, cp);
        }
        incrementalCompiler.index(); // idempotent; logs timing on first call

        // One incremental compile to prime ECJ's JIT.
        log.info("ECJ warm-up compile: {}", srcDir.relativize(file));
        boolean ok = incrementalCompiler.compile(Set.of(file));
        log.info("ECJ warm-up done ({}ms total incl. indexing, ok={}) – incremental path is JIT-compiled.",
                System.currentTimeMillis() - t0, ok);
    }

    // ── Compilation helpers ───────────────────────────────────────────────────

    /**
     * Full compile: passes {@code srcDir} as the source root.
     * ECJ discovers and compiles all {@code .java} files under it.
     */
    private boolean compileAll() {
        File binFile = ensureBinDir();
        if (binFile == null)
            return false;

        String fullCp = buildClasspath(false); // bin/ NOT added for full recompile

        List<String> args = baseEcjArgs(binFile, fullCp);
        args.add("-sourcepath");
        args.add(srcDir.toAbsolutePath().toString());
        args.add(srcDir.toAbsolutePath().toString()); // source root to compile

        log.info("ECJ full compile: {} → {}", srcDir, binDir);
        return runEcj(args);
    }

    /**
     * Incremental compile: uses the in-memory {@link IncrementalCompiler} which
     * keeps a persistent {@link hr.hrg.watch2.run.ecj.CachingNameEnvironment}.
     *
     * <p>
     * On the first call the compiler is created and classpath-indexed
     * ({@code CachingNameEnvironment.index()}). Subsequent calls reuse the
     * cached JAR bytes, reducing per-compile overhead from ~30–80ms to ~5–15ms.
     */
    private boolean compileIncremental(Set<Path> changedFiles) {
        if (incrementalCompiler == null) {
            String cp = buildClasspath(true); // bin/ first so ECJ finds previous classes
            incrementalCompiler = new IncrementalCompiler(srcDir, binDir, cp);
            incrementalCompiler.index();
        }

        log.info("ECJ incremental compile (cached env): {} file(s) → {}",
                changedFiles.size(), binDir);
        if (log.isDebugEnabled()) {
            for (Path f : changedFiles)
                log.debug("  ± {}", srcDir.relativize(f));
        }
        return incrementalCompiler.compile(changedFiles);
    }

    /**
     * Builds the ECJ {@code -cp} string.
     *
     * @param includeBinDir when {@code true}, prepends {@code bin/} so ECJ can use
     *                      previously compiled class files for unchanged types
     */
    private String buildClasspath(boolean includeBinDir) {
        String runtimeCp = System.getProperty("java.class.path");
        StringBuilder cp = new StringBuilder();
        if (includeBinDir) {
            cp.append(binDir.toAbsolutePath()).append(File.pathSeparatorChar);
        }
        cp.append(runtimeCp);
        if (!extraClasspath.isEmpty()) {
            cp.append(File.pathSeparatorChar)
                    .append(String.join(File.pathSeparator, extraClasspath));
        }
        return cp.toString();
    }

    /** Returns a list containing the ECJ flags common to both compile modes. */
    private List<String> baseEcjArgs(File binFile, String classpath) {
        List<String> args = new ArrayList<>();
        args.add("-21"); // Java 21 language level
        args.add("-encoding");
        args.add("UTF-8");
        args.add("-proc:none"); // skip annotation processing
        args.add("-d");
        args.add(binFile.getAbsolutePath());
        args.add("-cp");
        args.add(classpath);
        return args;
    }

    /** Invokes ECJ and logs its output. Returns {@code true} on success. */
    private boolean runEcj(List<String> args) {
        log.debug("ECJ args: {}", args);

        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();

        boolean success = BatchCompiler.compile(
                args.toArray(new String[0]),
                new PrintWriter(outSw),
                new PrintWriter(errSw),
                null);

        String out = outSw.toString().trim();
        String err = errSw.toString().trim();
        if (!out.isEmpty())
            log.info("ECJ:\n{}", out);
        if (!err.isEmpty()) {
            if (success)
                log.warn("ECJ warnings:\n{}", err);
            else
                log.error("ECJ errors:\n{}", err);
        }
        return success;
    }

    // ── Lifecycle helpers ─────────────────────────────────────────────────────

    /**
     * Removes the {@code .class} and inner-class {@code .class} files that
     * correspond to a deleted {@code .java} source file.
     *
     * <p>
     * Example: deleting {@code src/com/example/Foo.java} will remove
     * {@code bin/com/example/Foo.class} and {@code bin/com/example/Foo$Bar.class}
     * (and any other {@code Foo$…class} variants).
     */
    private void deleteClassFiles(Path javaFile) {
        // e.g. javaFile = src/com/example/Foo.java
        // relative = com/example/Foo.java
        // classBase = com/example/Foo
        Path relative = srcDir.relativize(javaFile.toAbsolutePath());
        String relStr = relative.toString();
        if (!relStr.endsWith(".java"))
            return;

        String classBase = relStr.substring(0, relStr.length() - ".java".length());
        Path classDir = binDir.resolve(classBase).getParent();
        String baseName = Path.of(classBase).getFileName().toString(); // e.g. "Foo"

        if (classDir == null || !Files.isDirectory(classDir))
            return;

        try (var stream = Files.list(classDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.equals(baseName + ".class") || n.startsWith(baseName + "$");
            }).forEach(p -> {
                try {
                    Files.delete(p);
                    log.debug("Deleted class file: {}", p);
                } catch (IOException e) {
                    log.warn("Could not delete class file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Error scanning class dir {} for cleanup: {}", classDir, e.getMessage());
        }
    }

    private File ensureBinDir() {
        File f = binDir.toFile();
        if (!f.exists() && !f.mkdirs()) {
            log.error("Cannot create bin directory: {}", binDir);
            return null;
        }
        return f;
    }

    private void joinPreviousMainThread(Thread prev) {
        if (prev == null || !prev.isAlive())
            return;

        log.debug("Waiting up to {}ms for previous main thread to exit...", SHUTDOWN_WAIT_MS);
        try {
            prev.join(SHUTDOWN_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (prev.isAlive()) {
            log.warn("Previous main thread still alive after {}ms – interrupting.", SHUTDOWN_WAIT_MS);
            prev.interrupt();
        }
    }

    private void closePreviousLoader(URLClassLoader loader) {
        if (loader != null) {
            try {
                loader.close();
                log.debug("Previous URLClassLoader closed.");
            } catch (Exception e) {
                log.warn("Could not close previous URLClassLoader: {}", e.getMessage());
            }
        }
    }

    private void closePreviousContext(Context context) {
        if (context != null) {
            try {
                // Synthetically trigger the guest's StopRegistry before closing
                try {
                    Value guestRegistry = context.getBindings("java")
                            .getMember("hr.hrg.watch2.run.StopRegistry");
                    if (guestRegistry != null && guestRegistry.hasMember("stopAll")) {
                        guestRegistry.invokeMember("stopAll");
                    }
                } catch (Exception ignore) {
                }
                context.close(true);
                log.debug("Previous Polyglot Context closed.");
            } catch (Exception e) {
                log.warn("Could not close Polyglot Context: {}", e.getMessage());
            }
        }
    }
}
