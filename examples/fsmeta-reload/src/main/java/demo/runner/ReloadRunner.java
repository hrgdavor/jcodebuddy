package demo.runner;

import hr.hrg.jcodebuddy.compile.EcjIncrementalCompiler;
import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The fs-meta reload loop: batch, incremental compile, new project classloader, app + JUnit, with timings.
 *
 * <h2>The loop, in one place</h2>
 * <ol>
 *   <li>Find the fs-meta daemon that serves the project directory ({@link FsMetaDaemonDiscovery}) and subscribe
 *       to its batch stream through {@link FsMetaDaemonWatch} at {@link FsMetaClient#LATEST}. There is no
 *       {@code WatchService} anywhere: the daemon owns watching, hashing and batching.</li>
 *   <li>Compile the initial sources with two {@link EcjIncrementalCompiler}s (main and test), one per source
 *       root, both writing the same classes directory, then build the first {@link ProjectClassLoader}.</li>
 *   <li>Run {@code demo.project.App} and the JUnit test on that loader.</li>
 *   <li>For every batch: map the changed and deleted paths to fully qualified names, compile exactly the
 *       changed files, build the <em>next</em> loader from the previous one's bytes plus the new class files,
 *       discard the old loader, and run the app and the test again.</li>
 * </ol>
 *
 * <h2>What the classloaders own</h2>
 * <p>{@code demo.project.*} is loaded by {@link ProjectClassLoader} and by nothing else. The module's own
 * classpath has no project classes — Maven never compiles them — so the loader is the only door to them.
 * Jackson, JUnit, the runner and the JDK come from the parent loader ({@link ProjectClassLoader}'s parent is
 * this class's loader). That split is what makes the reload work: new bytecode in a new loader, while the
 * libraries that interpret it stay the same types.</p>
 *
 * <h2>Measurements</h2>
 * <p>Every batch prints one line with the phases it measured:
 * {@code compile= reload= app= test= totalFromSave=}. {@code totalFromSave} starts at the <em>earliest source
 * file mtime</em> of the batch (the save that triggered it) and ends when the test run finished, so it includes
 * everything a person waits for: daemon latency, compilation, classloading and both runs. The second line
 * ({@code fromBatch:}) measures from the moment the batch was received instead, so daemon latency is separated
 * from the runner's own work. All phase times use {@link System#nanoTime()}; the save time can only come from
 * the file's mtime, so it is wall-clock and carries the file system's timestamp granularity.</p>
 *
 * <h2>Arguments</h2>
 * <pre>
 *   ReloadRunner &lt;example-root&gt; [&lt;classes-dir&gt;]
 * </pre>
 * <p>{@code example-root} is {@code examples/fsmeta-reload} (its {@code project/} directory is accepted too);
 * the sources are resolved as {@code project/src/main/java} and {@code project/src/test/java}, and the classes
 * directory defaults to {@code <project>/classes}.</p>
 */
public final class ReloadRunner {

    private static final String APP_CLASS = "demo.project.App";
    private static final String TEST_CLASS = "demo.project.CalculationTest";
    private static final String EDIT_ME = "demo/project/Calculation.java";

    private final Path projectRoot;
    private final Path mainRoot;
    private final Path testRoot;
    private final Path classesDir;

    /** The daemon's root: the project directory itself, or an ancestor that serves it. Set once, in run(). */
    private Path daemonRoot;

    private EcjIncrementalCompiler mainCompiler;
    private EcjIncrementalCompiler testCompiler;

    /** The current generation of the project's classes. Replaced, never mutated, by a batch. */
    private ProjectClassLoader loader;

    private long reloads;

    /**
     * The START_BATCH fast path. A single worker compiles each changed unit as its CHANGE_ENTRY arrives, while the
     * watcher reader continues to collect END_BATCH. The final changed-set compile waits for this worker, so the
     * two compilers never run at the same time.
     */
    private final ExecutorService fastCompiler = Executors.newSingleThreadExecutor(thread -> {
        Thread worker = new Thread(thread, "reload-first-compile");
        worker.setDaemon(true);
        return worker;
    });
    private final Object compilerLock = new Object();
    private final List<Future<?>> fastTasks = new ArrayList<>();
    private final AtomicLong batchStartNanos = new AtomicLong();
    private final AtomicLong firstClassNanos = new AtomicLong();
    private final AtomicBoolean earlyBlocked = new AtomicBoolean();
    private final boolean earlyCompile = Boolean.getBoolean("reload.earlyCompile");
    private final java.util.concurrent.atomic.AtomicReference<EcjIncrementalCompiler.EarlyCompilation> earlyHandle =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<EcjIncrementalCompiler> earlyOwner =
            new java.util.concurrent.atomic.AtomicReference<>();

    private ReloadRunner(Path projectRoot, Path classesDir) {
        this.projectRoot = projectRoot;
        this.mainRoot = projectRoot.resolve("src/main/java");
        this.testRoot = projectRoot.resolve("src/test/java");
        this.classesDir = classesDir;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: ReloadRunner <example-root> [<classes-dir>]");
            System.err.println("  <example-root>  examples/fsmeta-reload (its project/ dir is accepted too)");
            System.err.println("  <classes-dir>   where the project's .class files are written"
                    + " (default: <project>/classes)");
            System.exit(2);
        }
        Path exampleRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path projectRoot = projectRootOf(exampleRoot);
        if (projectRoot == null) {
            System.err.println("[init] no project sources under " + exampleRoot.resolve("project/src/main/java")
                    + " or " + exampleRoot.resolve("src/main/java"));
            System.exit(2);
        }
        Path classesDir = args.length > 1
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : projectRoot.resolve("classes");

        try {
            new ReloadRunner(projectRoot, classesDir).run();
        } catch (Exception failed) {
            System.out.println("[init] the reload runner failed: " + failed);
            failed.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /**
     * The project directory for either accepted argument shape, or {@code null} when neither holds sources.
     *
     * <p>The example root's own {@code src/main/java} holds the runner module, so the containing {@code project/}
     * directory is checked first: it is the layout the README describes. Passing the project directory itself is
     * the other accepted shape, and it is what the one-argument form resolves to.</p>
     */
    private static Path projectRootOf(Path exampleRoot) {
        Path nested = exampleRoot.resolve("project");
        if (Files.isDirectory(nested.resolve("src/main/java"))) {
            return nested;
        }
        return Files.isDirectory(exampleRoot.resolve("src/main/java")) ? exampleRoot : null;
    }

    private void run() throws Exception {
        System.out.println("[init] project sources: " + mainRoot + " and " + testRoot);
        System.out.println("[init] classes dir: " + classesDir);

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discoverAncestor(projectRoot);
        if (attach == null || !attach.attached()) {
            System.out.println("[init] no fs-meta daemon serves " + projectRoot);
            System.out.println("[init] " + (attach == null ? "discovery returned nothing" : attach.problem()));
            System.out.println("[init] start one and register the project root:");
            System.out.println("       fsmeta daemon start --base <dir>");
            System.out.println("       fsmeta root add " + projectRoot + " --base <dir>");
            System.out.println("[init] or run `bun examples/fsmeta-reload/run.js`, which does both for you.");
            System.exit(3);
        }
        FsMetaDaemonDiscovery.Location location = attach.location();
        daemonRoot = location.root();
        System.out.println("[init] daemon: " + location.socket());
        System.out.println("[init] daemon root: " + daemonRoot
                + (daemonRoot.equals(projectRoot) ? " (this project)" : " (ancestor of this project)"));

        // The filter is relative to the daemon's root, and so are the batch paths. With the project directory
        // registered that is exactly `src/**/*.java`; with an ancestor registered, the project folder is prefixed.
        String prefix = relativePrefix(daemonRoot, projectRoot);
        String sourcePattern = prefix.isEmpty() ? "src/**/*.java" : prefix + "/src/**/*.java";
        PathFilter filter = PathFilter.of(List.of(sourcePattern), List.of("**/classes/**"));
        System.out.println("[init] filter: " + sourcePattern);

        // ── The compilers: one per source root, one shared output directory, indexed once ──────────────────
        String classpath = EcjIncrementalCompiler.buildClasspath(classesDir, runtimeClasspath());
        mainCompiler = new EcjIncrementalCompiler(mainRoot, classesDir, classpath);
        testCompiler = new EcjIncrementalCompiler(testRoot, classesDir, classpath);
        long indexStartedAt = System.nanoTime();
        mainCompiler.index();
        testCompiler.index();
        System.out.printf(Locale.ROOT, "[init] indexed dependencies once: %dms%n",
                millis(System.nanoTime() - indexStartedAt));

        Set<Path> mainSources = javaSources(mainRoot);
        Set<Path> testSources = javaSources(testRoot);
        long compileStartedAt = System.nanoTime();
        boolean mainOk = mainCompiler.compile(mainSources);
        boolean testOk = testCompiler.compile(testSources);
        long compileNanos = System.nanoTime() - compileStartedAt;
        if (!mainOk || !testOk) {
            System.out.printf(Locale.ROOT, "[init] initial compile FAILED in %dms%n", millis(compileNanos));
            printCompileErrors("main", mainCompiler, mainOk);
            printCompileErrors("test", testCompiler, testOk);
            System.out.println("[init] if the errors name Jackson or JUnit types, the runner is not on the"
                    + " module's runtime classpath: use `mvn -pl :fsmeta-reload-example exec:java`.");
            System.exit(1);
        }
        System.out.printf(Locale.ROOT, "[init] compiled %d main + %d test source(s) in %dms%n",
                mainSources.size(), testSources.size(), millis(compileNanos));

        // The first generation's changed set is everything the initial compile produced: all of it is new.
        long reloadStartedAt = System.nanoTime();
        loader = new ProjectClassLoader(classesDir, Map.of(),
                ProjectClassLoader.classNamesIn(classesDir), Set.of());
        System.out.printf(Locale.ROOT, "[init] project loader #1: %d classes in %dms%n",
                loader.classCount(), millis(System.nanoTime() - reloadStartedAt));

        System.out.println("[init] first run");
        runApp(loader);
        runTest(loader);

        // ── The live loop ────────────────────────────────────────────────────────────────────────────────
        BlockingQueue<WatchBatch> batches = new LinkedBlockingQueue<>();
        FsMetaDaemonWatch.BatchObserver streaming = new FsMetaDaemonWatch.BatchObserver() {
            @Override
            public void startBatch(long number, long parent) {
                beginFastBatch();
            }

            @Override
            public void change(FsMetaClient.Change change) {
                fastChange(change);
            }
        };
        FsMetaDaemonWatch watch = new FsMetaDaemonWatch(filter, location.socket(), FsMetaClient.LATEST,
                batches::add, streaming);
        // Ctrl+C (or Maven's own shutdown) must not leave a subscribed client behind. stop() is idempotent.
        Runtime.getRuntime().addShutdownHook(new Thread(watch::stop, "reload-shutdown"));

        System.out.println();
        System.out.println("[watch] edit and save: " + mainRoot.resolve(EDIT_ME));
        System.out.println("[watch] " + APP_CLASS + " and " + TEST_CLASS
                + " run again on every fs-meta batch (Ctrl+C stops)");
        watch.start();

        try {
            while (true) {
                WatchBatch batch = batches.take();
                long receivedNanos = System.nanoTime();
                long receivedWall = System.currentTimeMillis();
                try {
                    apply(batch, receivedNanos, receivedWall);
                } catch (RuntimeException failed) {
                    // A bad batch must not kill the loop: report it and leave the current loader running.
                    System.out.println("[reload] batch failed: " + failed);
                    failed.printStackTrace(System.out);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            watch.stop();
            fastCompiler.shutdownNow();
            System.out.printf(Locale.ROOT, "[watch] stopped after %d batch(es), %d reload(s)%n",
                    watch.deliveredBatches(), reloads);
        }
    }

    // ── One batch ─────────────────────────────────────────────────────────────

    /** Resets the per-batch early-phase state on START_BATCH. */
    private void beginFastBatch() {
        synchronized (fastTasks) {
            fastTasks.clear();
        }
        earlyHandle.set(null);
        earlyOwner.set(null);
        earlyBlocked.set(false);
        batchStartNanos.set(System.nanoTime());
        firstClassNanos.set(0);
    }

    /**
     * Starts ECJ's internal parse/binding phase for the first changed source without blocking the daemon reader.
     * A second change in the same batch means the early phase cannot be completed as a one-file compile, so the
     * batch falls back to the normal changed-set compile at END_BATCH.
     */
    private void fastChange(FsMetaClient.Change change) {
        if (!earlyCompile || earlyBlocked.get()) {
            return;
        }
        Path file = daemonFile(change.name());
        Path sourceRoot = file.startsWith(mainRoot) ? mainRoot : (file.startsWith(testRoot) ? testRoot : null);
        if (sourceRoot == null || !Files.isRegularFile(file) || !file.toString().endsWith(".java")) {
            return;
        }
        synchronized (fastTasks) {
            if (!fastTasks.isEmpty() || earlyOwner.get() != null) {
                earlyBlocked.set(true);
                return;
            }
            fastTasks.add(fastCompiler.submit((Runnable) () -> {
                synchronized (compilerLock) {
                    EcjIncrementalCompiler compiler = sourceRoot.equals(mainRoot) ? mainCompiler : testCompiler;
                    EcjIncrementalCompiler.EarlyCompilation early = compiler.beginEarly(file);
                    if (early != null) {
                        earlyOwner.set(compiler);
                        earlyHandle.set(early);
                        firstClassNanos.set(System.nanoTime() - batchStartNanos.get());
                    }
                }
            }));
        }
    }

    /** Waits for the START_BATCH early phase to finish before the changed-set cross-check. */
    private void awaitFastCompilation() {
        List<Future<?>> pending;
        synchronized (fastTasks) {
            pending = new ArrayList<>(fastTasks);
            fastTasks.clear();
        }
        for (Future<?> task : pending) {
            try {
                task.get(10, TimeUnit.SECONDS);
            } catch (Exception failed) {
                // The final compile is the authority; a failed early phase is only an early result.
                System.out.println("[reload] early phase failed: " + failed);
            }
        }
    }

    private void apply(WatchBatch batch, long receivedNanos, long receivedWall) {
        System.out.println("[reload] batch: changed=" + sorted(batch.changed())
                + " deleted=" + sorted(batch.deleted()));

        Set<Path> mainFiles = new LinkedHashSet<>();
        Set<Path> testFiles = new LinkedHashSet<>();
        Set<String> changedNames = new TreeSet<>();
        Set<String> deletedNames = new TreeSet<>();

        if (batch.overflow()) {
            // The daemon refused a replay, so the change set may be incomplete: the only safe answer is a full
            // pass. Every existing class is then treated as changed and re-read from the fresh output.
            System.out.println("[reload] overflow: the daemon refused the replay, recompiling every source");
            mainFiles.addAll(javaSources(mainRoot));
            testFiles.addAll(javaSources(testRoot));
        } else {
            collect(batch, mainFiles, testFiles, changedNames, deletedNames);
            if (changedNames.isEmpty() && deletedNames.isEmpty()) {
                System.out.println("[reload] no project source changed in this batch, nothing to do");
                return;
            }
        }

        long saveWall = earliestSourceMtime(mainFiles, testFiles, receivedWall);
        awaitFastCompilation();

        // ── Compile exactly the changed files ─────────────────────────────────────────────────────────────
        // The opt-in early phase has already parsed and bound the first changed unit. A one-file batch can finish
        // that same ECJ compiler here (resolution/cross-check + codegen). A batch with several changed files has no
        // way to append units to the already-started ECJ compiler, so it falls back to the normal changed-set
        // compile; that is also where ECJ resolves types that changed in the same batch.
        boolean singleEarly = earlyCompile
                && changedNames.size() + deletedNames.size() == 1
                && deletedNames.isEmpty()
                && !earlyBlocked.get()
                && earlyHandle.get() != null
                && earlyOwner.get() != null;
        long compileStartedNanos = System.nanoTime();
        boolean mainOk = true;
        boolean testOk = true;
        if (singleEarly) {
            EcjIncrementalCompiler owner = earlyOwner.get();
            mainOk = owner.completeEarly(earlyHandle.get());
            if (!mainOk) {
                System.out.println("[reload] early completion failed; falling back to the changed-set compile");
                singleEarly = false;
            } else {
                firstClassNanos.set(System.nanoTime() - batchStartNanos.get());
            }
        }
        if (!singleEarly) {
            synchronized (compilerLock) {
                mainOk = mainFiles.isEmpty() || mainCompiler.compile(mainFiles);
                testOk = testFiles.isEmpty() || testCompiler.compile(testFiles);
            }
        }
        long compileNanos = System.nanoTime() - compileStartedNanos;
        if (!mainOk || !testOk) {
            // A compile error is not a reload: the previous class files belong to the last good compile, and the
            // loader that is running still describes them. Report and wait for the next batch.
            System.out.printf(Locale.ROOT,
                    "[reload] compile FAILED in %dms, keeping the current loader (%d classes)%n",
                    millis(compileNanos), loader.classCount());
            printCompileErrors("main", mainCompiler, mainOk);
            printCompileErrors("test", testCompiler, testOk);
            return;
        }
        if (batch.overflow()) {
            changedNames.addAll(ProjectClassLoader.classNamesIn(classesDir));
        }
        if (firstClassNanos.get() > 0) {
            System.out.printf(Locale.ROOT, "[reload] first class from batch start: %.1fms%s%n",
                    exactMillis(firstClassNanos.get()),
                    singleEarly ? " (final; opt-in internal phase)" : " (early parse; cross-check follows)");
        }

        // ── Reload: a new loader seeded with the old bytes, overwritten where the compile changed them ────
        long reloadStartedNanos = System.nanoTime();
        Map<String, byte[]> previousBytes = loader.classBytes();
        ProjectClassLoader next = new ProjectClassLoader(classesDir, previousBytes, changedNames, deletedNames);
        loader = next; // the old loader is garbage now; its classes stay valid but can no longer be refreshed
        reloads++;
        long reloadNanos = System.nanoTime() - reloadStartedNanos;
        long reloadedNanos = System.nanoTime();
        System.out.printf(Locale.ROOT,
                "[reload] project loader #%d: %d classes (%d changed, %d removed, %d seeded from the previous"
                        + " loader)%n",
                reloads + 1, next.classCount(), changedNames.size(), deletedNames.size(), previousBytes.size());

        // ── Run both, on the new classloader ─────────────────────────────────────────────────────────────
        long appNanos = runApp(loader);
        long appDoneNanos = System.nanoTime();
        long testNanos = runTest(loader);
        long testDoneNanos = System.nanoTime();

        long totalFromSaveMs = System.currentTimeMillis() - saveWall;
        System.out.printf(Locale.ROOT,
                "[reload] compile=%.1fms reload=%.1fms app=%.1fms test=%.1fms totalFromSave=%dms%n",
                exactMillis(compileNanos), exactMillis(reloadNanos), exactMillis(appNanos),
                exactMillis(testNanos), totalFromSaveMs);
        System.out.printf(Locale.ROOT,
                "[reload] fromBatch: compileStart=%.1fms reloaded=%.1fms appDone=%.1fms testDone=%.1fms%n",
                since(receivedNanos, compileStartedNanos), since(receivedNanos, reloadedNanos),
                since(receivedNanos, appDoneNanos), since(receivedNanos, testDoneNanos));
    }

    /**
     * Maps the batch's project-relative paths to source files and fully qualified names.
     *
     * <p>Paths arrive relative to the daemon's root ({@code src/main/java/demo/project/Calculation.java}), so each
     * is resolved against that root first; only the two source roots this runner compiles are kept. A changed path
     * whose file vanished between the daemon's snapshot and this read is treated as a deletion, never as a
     * compile of a missing file.</p>
     */
    private void collect(WatchBatch batch, Set<Path> mainFiles, Set<Path> testFiles,
                         Set<String> changedNames, Set<String> deletedNames) {
        for (String name : batch.changed()) {
            Path file = daemonFile(name);
            if (file.startsWith(mainRoot)) {
                collectOne(mainRoot, file, mainFiles, changedNames, deletedNames);
            } else if (file.startsWith(testRoot)) {
                collectOne(testRoot, file, testFiles, changedNames, deletedNames);
            }
        }
        for (String name : batch.deleted()) {
            Path file = daemonFile(name);
            if (file.startsWith(mainRoot)) {
                forget(mainRoot, file, deletedNames);
            } else if (file.startsWith(testRoot)) {
                forget(testRoot, file, deletedNames);
            }
        }
    }

    private void collectOne(Path sourceRoot, Path file, Set<Path> files,
                            Set<String> changedNames, Set<String> deletedNames) {
        if (Files.isRegularFile(file)) {
            files.add(file);
            changedNames.add(fullyQualifiedName(sourceRoot, file));
        } else {
            forget(sourceRoot, file, deletedNames);
        }
    }

    /** Removes a deleted source's compiled classes and excludes it from the next loader's seed. */
    private void forget(Path sourceRoot, Path file, Set<String> deletedNames) {
        String name = fullyQualifiedName(sourceRoot, file);
        removeCompiledClasses(name);
        deletedNames.add(name);
        System.out.println("[reload] deleted " + name + " (compiled classes removed)");
    }

    // ── Running the project ───────────────────────────────────────────────────

    /**
     * Loads {@code demo.project.App} through the project loader and calls its {@code main(String[])}.
     *
     * <p>The call crosses loaders: the app class, its {@code demo.project} types and Jackson come from different
     * places. An exception thrown inside the app arrives wrapped in {@link InvocationTargetException}, so the
     * cause is what gets reported — a failing app is part of the demo, not an error in the runner.</p>
     */
    private long runApp(ClassLoader projectLoader) {
        System.out.println("[app] " + APP_CLASS + ".main(String[])");
        long startedNanos = System.nanoTime();
        boolean ok = true;
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(projectLoader);
        try {
            Class<?> app = projectLoader.loadClass(APP_CLASS);
            Method main = app.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failed) {
            ok = false;
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            System.out.println("[app] FAILED: " + cause);
            cause.printStackTrace(System.out);
        } catch (ReflectiveOperationException | LinkageError failed) {
            ok = false;
            System.out.println("[app] FAILED: " + failed);
            failed.printStackTrace(System.out);
        } finally {
            thread.setContextClassLoader(previous);
        }
        long nanos = System.nanoTime() - startedNanos;
        System.out.printf(Locale.ROOT, "[app] %s in %dms%n", ok ? "ok" : "failed", millis(nanos));
        return nanos;
    }

    /**
     * Runs the JUnit 5 test on the project loader through the Platform launcher.
     *
     * <p>The test class is loaded by the project loader and handed to
     * {@link DiscoverySelectors#selectClass(Class)} as a {@link Class}, so discovery has no reason to guess a
     * loader. The thread context classloader is set to the project loader for the launch, because the Platform
     * finds engines and extensions through the JDK's {@link java.util.ServiceLoader}; the project loader delegates
     * that lookup to the parent, where JUnit lives.</p>
     */
    private long runTest(ClassLoader projectLoader) {
        System.out.println("[test] " + TEST_CLASS);
        long startedNanos = System.nanoTime();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        TestExecutionSummary summary = null;
        String problem = null;
        try {
            thread.setContextClassLoader(projectLoader);
            Class<?> testClass = projectLoader.loadClass(TEST_CLASS);
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(testClass))
                    .build();
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(request);
            summary = listener.getSummary();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            problem = failed.toString();
        } finally {
            thread.setContextClassLoader(previous);
        }
        long nanos = System.nanoTime() - startedNanos;

        if (summary == null) {
            System.out.printf(Locale.ROOT, "[test] the launcher failed in %dms: %s%n", millis(nanos), problem);
            return nanos;
        }
        for (TestExecutionSummary.Failure failure : summary.getFailures()) {
            System.out.println("[test] FAILED: " + failure.getTestIdentifier().getDisplayName());
            System.out.println("[test]         " + failure.getException());
            failure.getException().printStackTrace(System.out);
        }
        boolean ok = summary.getTestsFailedCount() == 0 && summary.getContainersFailedCount() == 0;
        System.out.printf(Locale.ROOT, "[test] %d test(s), %d failed, %d skipped in %dms -> %s%n",
                summary.getTestsStartedCount(), summary.getTestsFailedCount(),
                summary.getTestsSkippedCount(), millis(nanos), ok ? "PASSED" : "FAILED");
        return nanos;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * The classpath the project sources are compiled against: the classes directory first, then everything this
     * runner can see.
     *
     * <p>{@code EcjIncrementalCompiler.buildClasspath} takes the extra entries as a list, so this method supplies
     * them: {@code java.class.path} plus every file URL on the runner's loader chain. The second part matters
     * under {@code exec:java}, which runs inside Maven's JVM — there {@code java.class.path} is Maven's own
     * launcher path and the module's dependencies (Jackson, JUnit, jcodebuddy-compile/watch) arrive as URLs on the
     * exec classloader. Launched as {@code java -cp ... demo.runner.ReloadRunner}, {@code java.class.path} alone
     * is complete and the URL walk simply adds nothing.</p>
     */
    private static List<String> runtimeClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        for (ClassLoader current = ReloadRunner.class.getClassLoader(); current != null;
                current = current.getParent()) {
            if (current instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    if (!"file".equals(url.getProtocol())) {
                        continue;
                    }
                    try {
                        entries.add(Path.of(url.toURI()).toString());
                    } catch (URISyntaxException | IllegalArgumentException notAFile) {
                        // A classpath entry that is not a usable file path cannot be compiled against.
                    }
                }
            }
        }
        return List.copyOf(entries);
    }

    private Path daemonFile(String daemonRelativeName) {
        return daemonRoot.resolve(daemonRelativeName.replace('/', File.separatorChar)).normalize();
    }

    private static String fullyQualifiedName(Path sourceRoot, Path file) {
        String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
    }

    /** Deletes {@code Foo.class} and {@code Foo$Nested.class} from the output directory. */
    private void removeCompiledClasses(String name) {
        int lastDot = name.lastIndexOf('.');
        String packagePath = lastDot < 0 ? "" : name.substring(0, lastDot).replace('.', '/');
        String simpleName = lastDot < 0 ? name : name.substring(lastDot + 1);
        Path directory = packagePath.isEmpty() ? classesDir : classesDir.resolve(packagePath);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".class")) {
                    continue;
                }
                String stem = fileName.substring(0, fileName.length() - ".class".length());
                if (stem.equals(simpleName) || stem.startsWith(simpleName + "$")) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException failed) {
            System.out.println("[reload] could not remove the compiled classes of " + name + ": " + failed);
        }
    }

    private static Set<Path> javaSources(Path sourceRoot) {
        Set<Path> sources = new LinkedHashSet<>();
        if (!Files.isDirectory(sourceRoot)) {
            return sources;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(sources::add);
        } catch (IOException failed) {
            System.out.println("[init] cannot walk " + sourceRoot + ": " + failed);
        }
        return sources;
    }

    /** The earliest mtime of the batch's source files: the closest measurable "the save happened" point. */
    private static long earliestSourceMtime(Collection<Path> files, Collection<Path> more, long fallback) {
        long earliest = Long.MAX_VALUE;
        for (Collection<Path> collection : List.of(files, more)) {
            for (Path file : collection) {
                try {
                    earliest = Math.min(earliest, Files.getLastModifiedTime(file).toMillis());
                } catch (IOException unreadable) {
                    // A file deleted since the batch arrived cannot date the save; the others still can.
                }
            }
        }
        return earliest == Long.MAX_VALUE ? fallback : earliest;
    }

    private static String relativePrefix(Path ancestor, Path path) {
        try {
            String relative = ancestor.relativize(path).toString().replace('\\', '/');
            return relative.equals(".") ? "" : relative;
        } catch (IllegalArgumentException outside) {
            return "";
        }
    }

    private static void printCompileErrors(String root, EcjIncrementalCompiler compiler, boolean ok) {
        if (ok) {
            return;
        }
        for (String error : compiler.lastErrors()) {
            System.out.println("[reload] " + root + ": " + error);
        }
        if (compiler.lastErrors().isEmpty()) {
            System.out.println("[reload] " + root + ": the compiler reported an error without a diagnostic");
        }
    }

    private static String sorted(Set<String> names) {
        return new TreeSet<>(names).toString();
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    /** Milliseconds with a fraction, for phases that are often below one millisecond. */
    private static double exactMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double since(long fromNanos, long toNanos) {
        return exactMillis(toNanos - fromNanos);
    }
}
