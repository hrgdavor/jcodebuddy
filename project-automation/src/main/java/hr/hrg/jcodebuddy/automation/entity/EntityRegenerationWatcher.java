package hr.hrg.jcodebuddy.automation.entity;

import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import hr.hrg.hipster.entity.tooling.EntityMetadataGenerator;
import hr.hrg.jcodebuddy.automation.watch.WatchedRegeneration;
import hr.hrg.jcodebuddy.engine.JcodebuddyDirectory;
import hr.hrg.watch2.core.ChangeSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Regenerates the entity views when an entity source changes (plan.dsflash § 12.5/7.17, X4).
 *
 * <h3>What this is, and what it is not</h3>
 * <p>It is the watcher half of the dev-time loop: it pairs {@code java-watch-core}'s debounced,
 * batched {@link BatchedFileWatcher} with {@code hipster-entity-tooling}'s generator, so editing a
 * view interface regenerates its enum, builders, adapters and mappers without a build. It decides
 * nothing about <em>policy</em> — the packages, adapters and mappers are the same CLI-style flags the
 * generator takes, passed through unchanged, so a watched run and a manually invoked run cannot
 * disagree about what gets generated.</p>
 *
 * <h3>The one hazard that matters: the generator writes into the tree it watches</h3>
 * <p>Generation is in place (the committed source is the source of truth — AGENTS.md § 1), so every
 * pass rewrites files under the watched root. A naive wiring therefore regenerates on its own output
 * and never becomes quiescent. This class breaks the loop with a <strong>content check</strong> rather
 * than a timing heuristic: after every pass it records the SHA-1 of every {@code .java} file under the
 * root, and a batch whose changed files all still hash to the recorded values is ignored. A timing
 * flag ("ignore events while generating") was rejected because filesystem events arrive
 * asynchronously and can land after the flag clears, which is exactly when the loop would restart.</p>
 *
 * <p>Two consequences follow from that choice, and both are deliberate:</p>
 * <ul>
 *   <li><strong>At most one no-op pass per generated-file set.</strong> Files the generator wrote but
 *       that were not in the triggering batch have no recorded hash yet, so the first batch that
 *       mentions them runs a pass. That pass changes nothing (generation is idempotent —
 *       {@code ExampleRegenerationTest} asserts it byte-for-byte), and after it every file has a hash,
 *       so the watcher then goes quiet. {@code EntityRegenerationWatcherTest} asserts exactly this
 *       bound rather than assuming it.</li>
 *   <li><strong>A save with unchanged content does nothing.</strong> That is a feature: an editor that
 *       rewrites a file with identical bytes, or a touched timestamp, does not start a pass.</li>
 * </ul>
 *
 * <h3>Ownership of the generator's configuration</h3>
 * <p>{@code EntityMetadataGenerator}'s packages/adapters/mappers are process-global statics, because
 * every entry point — the CLI, {@code scripts/gen.cmd}, the module POM's {@code exec:java} goals and
 * this watcher — shares one flag surface (§ 8.8/3.23). A long-lived watcher
 * therefore takes ownership of them: each pass sets them from its own {@link Config} and resets them
 * afterwards, so a watcher embedded in a larger process cannot silently configure someone else's
 * generation run. Passes are serialized, so a batch that arrives mid-pass is handled after it.</p>
 */
public final class EntityRegenerationWatcher implements AutoCloseable {

    /**
     * The marker directory a JCodeBuddy-enabled module owns for its generated output.
     *
     * <p>It appears only in modules that apply {@code project-automation}; a module without one has
     * no JCodeBuddy output and must not be given a directory by a tool. The repo root gets one only
     * in the rare case of a repo-wide generator (README.md in the directory states the layout).</p>
     */
    public static final String JCODEBUDDY_DIR = ".jcodebuddy";

    /** The metadata report subtree inside {@link #JCODEBUDDY_DIR}. */
    public static final String METADATA_ENTITY_DIR = "metadata/entity";

    /** What to generate and where, using the same flags the generator's CLI takes. */
    public record Config(Path sourceRoot, Path reportDir, List<String> packages, boolean adapters,
                         List<String> mappers, long debounceMs) {

        public Config {
            if (sourceRoot == null) {
                throw new IllegalArgumentException("a source root is required");
            }
            sourceRoot = sourceRoot.toAbsolutePath().normalize();
            reportDir = reportDir == null
                    ? defaultReportDir(sourceRoot)
                    : reportDir.toAbsolutePath().normalize();
            packages = packages == null ? List.of() : List.copyOf(packages);
            mappers = mappers == null ? List.of() : List.copyOf(mappers);
            if (debounceMs <= 0) {
                debounceMs = 300;
            }
        }

        /** The common case: generate everything, in place, with the default debounce. */
        public static Config of(Path sourceRoot) {
            return new Config(sourceRoot, null, List.of(), false, List.of(), 300);
        }

        /**
         * The metadata JSON goes to the module's {@code .jcodebuddy/metadata/entity}, never into the
         * source tree.
         *
         * <p>The marker is the {@code .jcodebuddy} directory itself: it says "this module uses
         * JCodeBuddy", and it is what distinguishes a converted module from one that merely happens to
         * be a Maven module. The search therefore looks for the nearest marker <em>anywhere</em> up the
         * tree, and only falls back to the nearest {@code pom.xml} when no marker exists — a marker
         * further up must not be shadowed by a plain {@code pom.xml} closer to the sources, which is
         * the layout a freshly converted submodule of an aggregator has.</p>
         *
         * <p>A directory whose whole content is <b>tool state</b> is not a marker: a page host publishes
         * its port in {@code <project>/.jcodebuddy/webview/} (DEC-032, DEC-033), and a project that
         * gained one because a browser was pointed at it must not capture the reports of a module below
         * it. {@link JcodebuddyDirectory#nearestMarker} owns that rule.</p>
         *
         * <p>Either way the report lands beside {@code src/main/java}, not inside it, so a watched run
         * never drops untracked {@code *.metadata.json} files next to the entities.</p>
         *
         * <p>When no module root is discoverable — a source tree outside any Maven project — the
         * reports go to the system temporary directory rather than to a directory created next to the
         * sources. The latter would be inside the watched root, which is the one place this method
         * exists to avoid; the watcher's file filter is {@code *.java} so a JSON would not actually
         * feed the loop, but "the report never lands in the source tree" is worth holding
         * unconditionally.</p>
         */
        static Path defaultReportDir(Path sourceRoot) {
            // A *marker*, not merely a directory of that name: a page host publishes its port in
            // `<project>/.jcodebuddy/webview/` (DEC-032, DEC-033), and a project that gained one because a
            // browser was pointed at it must not capture a module's reports. See JcodebuddyDirectory.
            Path marker = JcodebuddyDirectory.nearestMarker(sourceRoot);
            if (marker != null) {
                return marker.resolve(METADATA_ENTITY_DIR).toAbsolutePath().normalize();
            }
            Path current = sourceRoot;
            while (current != null && !Files.exists(current.resolve("pom.xml"))) {
                current = current.getParent();
            }
            if (current == null) {
                return Path.of(System.getProperty("java.io.tmpdir"), "jcodebuddy-entity-metadata")
                        .toAbsolutePath().normalize();
            }
            return current.resolve(JCODEBUDDY_DIR).resolve(METADATA_ENTITY_DIR).toAbsolutePath().normalize();
        }
    }

    /**
     * One decision point.
     *
     * @param sequence        1-based batch counter, so a test can assert how often the watcher acted
     * @param regenerated     whether a generation pass ran
     * @param triggeringFiles the changed paths that caused the decision, relative to the source root
     * @param divergences     what the pass reported, empty when none ran
     */
    public record Pass(long sequence, boolean regenerated, Set<String> triggeringFiles,
                       List<String> divergences) {

        public Pass {
            triggeringFiles = Set.copyOf(triggeringFiles);
            divergences = List.copyOf(divergences);
        }
    }

    private final Config config;
    private final java.util.function.Consumer<Pass> observer;

    /**
     * The shared loop: the debounced batch watcher plus the content-hash check that stops a generator reacting to
     * its own output. Everything entity-specific is the {@link WatchedRegeneration.Regenerator} below — the
     * mechanism moved out in step 3.9 so the hipster-ioc watcher could use it rather than copy it.
     */
    private final WatchedRegeneration watch;

    public EntityRegenerationWatcher(Config config) {
        this(config, pass -> { });
    }

    /**
     * @param observer called after every decision, including the ignored ones, so a caller can log
     *                 "saw a change, nothing to do" instead of guessing why nothing happened
     */
    public EntityRegenerationWatcher(Config config, java.util.function.Consumer<Pass> observer) {
        this.config = config;
        this.observer = observer == null ? pass -> { } : observer;
        // Only entity sources matter, and build output must never re-enter the loop (the same exclusion the R1
        // checker and the compaction command use). `.jcodebuddy` is in the list because the generator's own
        // metadata report lands there, inside the module it watches.
        this.watch = new WatchedRegeneration(config.sourceRoot(),
                List.of("**/*.java"),
                List.of("**/target/**", "**/tmp/**", "**/.kilo/**", "**/" + JCODEBUDDY_DIR + "/**"),
                config.debounceMs(), this::regenerate,
                shared -> observer.accept(new Pass(shared.sequence(), shared.regenerated(),
                        shared.triggeringFiles(), shared.divergences())));
    }

    /** Starts watching asynchronously. Returns immediately; batches are handled on the watcher's thread. */
    public void start() throws IOException {
        watch.start();
    }

    /** Stops watching. Safe to call twice, and safe to call without {@link #start()}. */
    public void stop() {
        watch.stop();
    }

    @Override
    public void close() {
        stop();
    }

    /** How many batches were delivered, and how many of them ran a pass. */
    public long batchCount() {
        return watch.batchCount();
    }

    public long passCount() {
        return watch.passCount();
    }

    /** The configuration this watcher regenerates with, so a caller can log what it is watching. */
    public Config config() {
        return config;
    }

    /**
     * Handles one batch: decide whether the tree's content actually differs from what the last pass produced, and
     * regenerate if it does. The decision is {@link WatchedRegeneration}'s; this method only translates its verdict
     * into this watcher's own {@link Pass} so the API callers already use keeps working.
     */
    public Pass onBatch(ChangeSet changeSet) {
        WatchedRegeneration.Pass shared = watch.onBatch(changeSet);
        return new Pass(shared.sequence(), shared.regenerated(), shared.triggeringFiles(), shared.divergences());
    }

    /**
     * Runs one generation pass with this watcher's configuration.
     *
     * <p>The generator's statics are set and then reset, so a watcher sharing a process with another
     * generation run cannot change its behaviour. A failure is reported as a divergence rather than
     * thrown: a watcher that dies on the first malformed edit is worse than one that says what is
     * wrong and keeps watching.</p>
     */
    private List<String> regenerate() {
        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.setGenerationPackages(config.packages());
        EntityMetadataGenerator.setGenerateAdapters(config.adapters());
        EntityMetadataGenerator.setMapperRequests(config.mappers());
        try {
            // Generated .java is written back into the watched tree; only the report goes elsewhere.
            EntityMetadataGenerator.generate(config.sourceRoot(), config.reportDir(),
                    config.sourceRoot(), reporter);
        } catch (IOException | RuntimeException failure) {
            reporter.report("watcher_pass_failed", config.sourceRoot().toString(),
                    "the generation pass threw instead of reporting",
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    "a completed pass",
                    "fix the source that broke the pass; the watcher is still running");
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
            EntityMetadataGenerator.setGenerateAdapters(false);
            EntityMetadataGenerator.setMapperRequests(List.of());
        }
        return reporter.entries();
    }

    /** SHA-1 of a file's bytes, or {@code "unknown"} when it cannot be read. */
    public static String sha1(Path file) {
        return WatchedRegeneration.sha1(file);
    }

    // ── CLI ───────────────────────────────────────────────────────────────────

    /**
     * The entry point, with the same flags the generator's CLI takes so a watched run and a
     * manually invoked run are configured identically (§ 8.8/3.23).
     *
     * <pre>
     *   --source &lt;dir&gt;          the source root to watch and regenerate (required)
     *   --report-dir &lt;dir&gt;      where the metadata JSON goes (default: &lt;module&gt;/.jcodebuddy/metadata/entity)
     *   --packages a.b,c.d      restrict generation, exactly as the generator's flag does
     *   --adapters              also emit the JDBC adapters
     *   --mapper Src:Tgt        also emit a mapper (repeatable)
     *   --debounce &lt;ms&gt;         quiet window before a batch is delivered (default 300)
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        Path source = null;
        Path reportDir = null;
        List<String> packages = new ArrayList<>();
        List<String> mappers = new ArrayList<>();
        boolean adapters = false;
        long debounce = 300;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--source" -> source = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--report-dir" -> reportDir = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--packages" -> {
                    if (i + 1 < args.length) {
                        for (String pkg : args[++i].split(",")) {
                            if (!pkg.isBlank()) {
                                packages.add(pkg.trim());
                            }
                        }
                    }
                }
                case "--mapper" -> {
                    if (i + 1 < args.length) {
                        mappers.add(args[++i]);
                    }
                }
                case "--adapters" -> adapters = true;
                case "--debounce" -> debounce = i + 1 < args.length ? Long.parseLong(args[++i]) : debounce;
                default -> System.err.println("[entity-watch] unknown argument: " + args[i]);
            }
        }
        if (source == null) {
            System.err.println("Usage: entity-watch --source <dir> [--report-dir <dir>] "
                    + "[--packages a.b,c.d] [--adapters] [--mapper Src:Tgt] [--debounce <ms>]");
            System.exit(2);
            return;
        }

        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                new Config(source, reportDir, packages, adapters, mappers, debounce),
                pass -> {
                    if (pass.regenerated()) {
                        System.out.println("[entity-watch] pass " + pass.sequence() + ": regenerated for "
                                + pass.triggeringFiles());
                        pass.divergences().forEach(line -> System.out.println("  " + line));
                    } else {
                        System.out.println("[entity-watch] pass " + pass.sequence()
                                + ": no content change, nothing to do");
                    }
                });
        watcher.start();
        System.out.println("[entity-watch] watching " + watcher.config().sourceRoot()
                + "; press Ctrl+C to stop");

        Runtime.getRuntime().addShutdownHook(new Thread(watcher::stop));
        // The watcher runs on its own thread; park the main thread so the process stays alive.
        Thread.currentThread().join();
    }
}
