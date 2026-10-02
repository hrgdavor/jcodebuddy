// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.run;

import hr.hrg.watch2.core.BatchedFileWatcher;
import hr.hrg.watch2.core.ChangeSet;
import hr.hrg.watch2.core.FileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-running watch-mode daemon.
 *
 * <p>
 * The daemon:
 * <ol>
 * <li>Performs an initial full compile+run on startup.</li>
 * <li>Uses {@link BatchedFileWatcher} (from <em>java-watch-core</em>) to
 * watch the {@code src/} directory. All changes that fall within the
 * debounce window are collected into a {@link ChangeSet} batch.</li>
 * <li>Passes the {@link ChangeSet} to {@link Reloader#reload(ChangeSet)},
 * which compiles only the changed files when possible (incremental path),
 * or falls back to a full recompile on deletes / overflow events.</li>
 * <li>All reload operations are serialised through a single-threaded
 * executor, so rapid successive saves never cause overlapping compiles.</li>
 * </ol>
 *
 * <p>
 * <strong>Lifecycle:</strong>
 * 
 * <pre>
 * HotSwapDaemon daemon = new HotSwapDaemon(srcDir, binDir, mainClass, mainArgs);
 * daemon.start(); // blocks until stop() or interrupt
 * </pre>
 *
 * <p>
 * <strong>CLI entry-point:</strong> {@link #main(String[])}
 */
public class HotSwapDaemon {
    private static final Logger log = LoggerFactory.getLogger(HotSwapDaemon.class);

    /** Default debounce window forwarded to {@link BatchedFileWatcher}. */
    public static final long DEFAULT_DEBOUNCE_MS = 300;

    // ── Configuration ─────────────────────────────────────────────────────────

    private final Path srcDir;
    private final Path binDir;
    private final String mainClass;
    private final String[] mainArgs;
    private final long debounceMs;
    private final List<String> extraClasspath;

    // ── Internal state ────────────────────────────────────────────────────────

    private final Reloader reloader;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean watch;
    private final boolean color;

    /** Single-threaded executor that serialises all reload operations. */
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hot-swap-reload");
        t.setDaemon(true);
        return t;
    });

    /** Batched watcher from java-watch-core. */
    private BatchedFileWatcher watcher;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Full constructor.
     *
     * @param srcDir         source directory to watch and compile
     * @param binDir         output directory for compiled classes
     * @param mainClass      target class to run after compilation
     * @param mainArgs       arguments forwarded to target's main method
     * @param debounceMs     debounce window in ms (see
     *                       {@link #DEFAULT_DEBOUNCE_MS})
     * @param extraClasspath additional classpath entries for ECJ
     */
    public HotSwapDaemon(Path srcDir, Path binDir, String mainClass, String[] mainArgs,
            long debounceMs, List<String> extraClasspath, boolean watch, boolean color) {
        this.srcDir = srcDir;
        this.binDir = binDir;
        this.mainClass = mainClass;
        this.mainArgs = mainArgs == null ? new String[0] : mainArgs;
        this.debounceMs = debounceMs;
        this.extraClasspath = extraClasspath == null ? List.of() : extraClasspath;
        this.reloader = new Reloader(srcDir, binDir, mainClass, this.mainArgs, this.extraClasspath, color);
        this.watch = watch;
        this.color = color;
    }

    public HotSwapDaemon(Path srcDir, Path binDir, String mainClass, String[] mainArgs) {
        this(srcDir, binDir, mainClass, mainArgs, DEFAULT_DEBOUNCE_MS, List.of(), false, true);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the daemon: full compile+run on startup, then enters the watch loop.
     * Blocks until {@link #stop()} is called or the thread is interrupted.
     *
     * @throws IOException if the {@link BatchedFileWatcher} cannot be initialised
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            log.warn("HotSwapDaemon is already running.");
            return;
        }

        log.info("HotSwapDaemon starting");
        log.info("  src  = {}", srcDir.toAbsolutePath());
        log.info("  bin  = {}", binDir.toAbsolutePath());
        log.info("  main = {}", mainClass);
        if (mainArgs.length > 0)
            log.info("  args = {}", Arrays.toString(mainArgs));

        // Startup: full compile (bin/ is empty, no ChangeSet available yet)
        if (watch) {
            triggerFullReload("startup");

            // Immediately queue a warm-up incremental compile on the same executor.
            // It will run after the startup reload completes (single-threaded executor),
            // while the app is already running, forcing ECJ's JIT paths to compile
            // so the FIRST real file-change reload is already fast.
            submitWarmUp();

            // ── Set up BatchedFileWatcher ─────────────────────────────────────────
            FileFilter filter = new FileFilter(srcDir, List.of("**.java"), List.of());

            watcher = new BatchedFileWatcher(srcDir, filter, debounceMs, changeSet -> {
                if (log.isDebugEnabled()) {
                    log.debug("Batch received: {}", changeSet);
                }
                triggerIncrementalReload(changeSet);
            });

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown hook triggered – stopping daemon...");
                stop();
            }, "hot-swap-shutdown"));

            watcher.start();
            log.info("Watching {} for .java changes (debounce {}ms, batch mode)...",
                    srcDir, debounceMs);

            // Park until stop() signals us
            synchronized (this) {
                while (running.get()) {
                    try {
                        wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } else {
            reloader.reload();
            Thread activeMain = reloader.getActiveMainThread();
            if (activeMain != null) {
                try {
                    activeMain.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            running.set(false);
        }

        log.info("HotSwapDaemon stopped.");
    }

    /**
     * Stops the daemon gracefully.
     */
    public void stop() {
        if (!running.compareAndSet(true, false))
            return;

        log.info("Stopping HotSwapDaemon...");
        StopRegistry.stopAll();

        if (watcher != null)
            watcher.stop();

        reloadExecutor.shutdown();
        try {
            if (!reloadExecutor.awaitTermination(5, TimeUnit.SECONDS))
                reloadExecutor.shutdownNow();
        } catch (InterruptedException e) {
            reloadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        synchronized (this) {
            notifyAll();
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Submits an incremental warm-up compile to the reload executor.
     * Because the executor is single-threaded, this runs only after the startup
     * full compile has finished — and crucially, after the app is already
     * running in its own daemon thread, so there is no user-visible delay.
     */
    private void submitWarmUp() {
        reloadExecutor.submit(() -> {
            Path target = findFirstJavaFile();
            if (target == null) {
                log.debug("Warm-up skipped – no .java files found in {}", srcDir);
                return;
            }
            reloader.warmUp(target);
        });
    }

    /**
     * Returns the first {@code .java} file found under {@code srcDir} (any order),
     * or {@code null} if the directory is empty or unreadable.
     */
    private Path findFirstJavaFile() {
        try (var stream = Files.walk(srcDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Could not scan srcDir for warm-up target: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Submits a full recompile task (startup or fallback).
     */
    private void triggerFullReload(String reason) {
        reloadExecutor.submit(() -> {
            log.info("--- Full reload triggered ({}) ---", reason);
            reloader.reload();
        });
    }

    /**
     * Submits an incremental reload task driven by a {@link ChangeSet}.
     * The reloader decides internally whether to do an incremental or full
     * compile based on the contents of the change set.
     */
    private void triggerIncrementalReload(ChangeSet changeSet) {
        reloadExecutor.submit(() -> {
            log.info("--- Incremental reload triggered: {} changed, {} deleted{} ---",
                    changeSet.changed().size(),
                    changeSet.deleted().size(),
                    changeSet.fullRecompile() ? " [OVERFLOW → full]" : "");
            reloader.reload(changeSet);
        });
    }

    // ── CLI entry-point ───────────────────────────────────────────────────────

    /**
     * Command-line entry point.
     *
     * <pre>
     *   Usage: java -jar java-watch-run.jar [-w|--watch] [--no-color] [--debounce=<ms>] <srcDir> <binDir> <mainClass> [mainArgs...]
     *
     *   -w, --watch     Enable watch mode (continuous reload).
     *   --no-color      Disable color output.
     *   --debounce=<ms> Quiet window after the last file-save before a reload is
     *                    triggered.  Lower values reduce perceived latency but may
     *                    cause partial-save recompiles on slow editors.
     *                    Default: 300 ms.  Recommended range: 0–500 ms.
     * </pre>
     */
    public static void main(String[] args) throws IOException {
        long debounceMs = DEFAULT_DEBOUNCE_MS;
        boolean watch = false;
        boolean color = true;
        int firstPos = 0;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--debounce=")) {
                try {
                    debounceMs = Long.parseLong(a.substring("--debounce=".length()));
                    if (debounceMs < 0)
                        throw new NumberFormatException("negative");
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: invalid --debounce value: " + a);
                    System.exit(1);
                }
                firstPos = i + 1;
            } else if (a.equals("-w") || a.equals("--watch")) {
                watch = true;
                firstPos = i + 1;
            } else if (a.equals("--no-color")) {
                color = false;
                firstPos = i + 1;
            } else {
                break; // first non-flag arg — stop scanning
            }
        }

        // ── Positional args ────────────────────────────────────────────────────
        String[] pos = Arrays.copyOfRange(args, firstPos, args.length);

        if (pos.length < 3) {
            System.err.println(
                    "Usage: java-watch-run [-w|--watch] [--no-color] [--debounce=<ms>] <srcDir> <binDir> <mainClass> [mainArgs...]");
            System.err.println("  -w, --watch  enable watch mode (continuous reload)");
            System.err.println("  --no-color   disable color output");
            System.err.println("  --debounce   quiet window after last save before reload (default: "
                    + DEFAULT_DEBOUNCE_MS + " ms)");
            System.exit(1);
        }

        Path srcDir = Paths.get(pos[0]);
        Path binDir = Paths.get(pos[1]);
        String mainCls = pos[2];
        String[] mainArgs = pos.length > 3
                ? Arrays.copyOfRange(pos, 3, pos.length)
                : new String[0];

        if (!srcDir.toFile().isDirectory()) {
            System.err.println("ERROR: srcDir does not exist or is not a directory: " + srcDir);
            System.exit(1);
        }

        log.info("Debounce window: {} ms", debounceMs);
        log.info("Watch mode: {}", watch);
        log.info("Color mode: {}", color);
        new HotSwapDaemon(srcDir, binDir, mainCls, mainArgs, debounceMs, List.of(), watch, color).start();
    }
}
