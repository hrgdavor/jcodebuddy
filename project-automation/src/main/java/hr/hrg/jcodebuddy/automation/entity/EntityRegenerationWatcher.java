package hr.hrg.jcodebuddy.automation.entity;

import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import hr.hrg.hipster.entity.tooling.EntityMetadataGenerator;
import hr.hrg.watch2.core.BatchedFileWatcher;
import hr.hrg.watch2.core.ChangeSet;
import hr.hrg.watch2.core.FileFilter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Regenerates the entity views when an entity source changes (plan.dsflash § 12.5/7.17, X4).
 *
 * <h3>What this is, and what it is not</h3>
 * <p>It is the watcher half of the dev-time loop: it pairs {@code java-watch-core}'s debounced,
 * batched {@link BatchedFileWatcher} with {@code hipster-entity-tooling}'s generator, so editing a
 * view interface regenerates its enum, builders, adapters and mappers without a build. It decides
 * nothing about <em>policy</em> — the packages, adapters and mappers are the same CLI-style flags the
 * generator takes, passed through unchanged, so a watched run and a build-time run cannot disagree
 * about what gets generated.</p>
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
 * the tooling's CLI and its Maven binding share one flag surface (§ 8.8/3.23). A long-lived watcher
 * therefore takes ownership of them: each pass sets them from its own {@link Config} and resets them
 * afterwards, so a watcher embedded in a larger process cannot silently configure someone else's
 * generation run. Passes are serialized, so a batch that arrives mid-pass is handled after it.</p>
 */
public final class EntityRegenerationWatcher implements AutoCloseable {

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
         * The metadata JSON goes to the module's {@code target/}, never into the source tree.
         *
         * <p>Walking up to the nearest {@code pom.xml} is the same rule the test harness uses to find
         * the repository root, and it keeps a watched run from dropping untracked
         * {@code *.metadata.json} files next to the entities.</p>
         *
         * <p>When no module root is discoverable — a source tree outside any Maven project — the
         * reports go to the system temporary directory rather than to {@code <sourceRoot>/target}.
         * The latter would be inside the watched root, which is the one place this method exists to
         * avoid; the watcher's file filter is {@code *.java} so a JSON would not actually feed the
         * loop, but "the report never lands in the source tree" is worth holding unconditionally.</p>
         */
        static Path defaultReportDir(Path sourceRoot) {
            Path current = sourceRoot;
            while (current != null && !Files.exists(current.resolve("pom.xml"))) {
                current = current.getParent();
            }
            if (current == null) {
                return Path.of(System.getProperty("java.io.tmpdir"), "jcodebuddy-entity-metadata")
                        .toAbsolutePath().normalize();
            }
            return current.resolve("target/entity-metadata");
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
    private final FileFilter fileFilter;

    /** SHA-1 of every {@code .java} file as of the end of the last pass. */
    private final Map<String, String> hashes = new LinkedHashMap<>();

    private BatchedFileWatcher watcher;
    private long sequence;
    private long passes;

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
        // Only entity sources matter, and build output must never re-enter the loop (the same
        // exclusion the R1 checker and the compaction command use).
        this.fileFilter = new FileFilter(config.sourceRoot(),
                List.of("**/*.java"),
                List.of("**/target/**", "**/tmp/**", "**/.kilo/**"));
        // Seed the hash table at construction, so the first batch is judged against the tree as the
        // watcher found it. Doing it here rather than in start() also means the decision logic is
        // exercisable without a real filesystem watcher.
        snapshotHashes();
    }

    /** Starts watching asynchronously. Returns immediately; batches are handled on the watcher's thread. */
    public void start() throws IOException {
        if (watcher != null) {
            return;
        }
        watcher = new BatchedFileWatcher(config.sourceRoot(), fileFilter, config.debounceMs(),
                this::onBatch);
        watcher.start();
    }

    /** Stops watching. Safe to call twice, and safe to call without {@link #start()}. */
    public void stop() {
        if (watcher != null) {
            watcher.stop();
            watcher = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** How many batches were delivered, and how many of them ran a pass. */
    public long batchCount() {
        return sequence;
    }

    public long passCount() {
        return passes;
    }

    /** The configuration this watcher regenerates with, so a caller can log what it is watching. */
    public Config config() {
        return config;
    }

    /**
     * Handles one batch: decide whether the tree's content actually differs from what the last pass
     * produced, and regenerate if it does.
     *
     * <p>Synchronized, so a pass is never concurrent with another: the generator's configuration is
     * process-global and the source tree is one resource.</p>
     */
    public synchronized Pass onBatch(ChangeSet changeSet) {
        sequence++;
        Set<String> triggering = relativePaths(changeSet);

        if (!changeSet.fullRecompile() && !contentDiffers(changeSet, triggering)) {
            Pass ignored = new Pass(sequence, false, triggering, List.of());
            observer.accept(ignored);
            return ignored;
        }

        List<String> divergences = regenerate();
        Pass pass = new Pass(sequence, true, triggering, divergences);
        observer.accept(pass);
        return pass;
    }

    /**
     * Runs one generation pass with this watcher's configuration and re-snapshots the hashes.
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
        passes++;
        snapshotHashes();
        return reporter.entries();
    }

    /** Whether any changed or deleted file differs from the state the last pass left behind. */
    private boolean contentDiffers(ChangeSet changeSet, Set<String> triggering) {
        for (String relative : triggering) {
            Path absolute = config.sourceRoot().resolve(relative);
            if (!Files.exists(absolute)) {
                // A deletion always matters: the generator may need to remove or recreate a file.
                return true;
            }
            String recorded = hashes.get(relative);
            if (recorded == null || !recorded.equals(sha1(absolute))) {
                return true;
            }
        }
        // Every changed file still hashes to what the last pass left, so this batch is the echo of
        // that pass (or a save with identical content). Nothing to do — and this is the branch that
        // keeps the watcher from regenerating forever on its own output.
        return false;
    }

    private Set<String> relativePaths(ChangeSet changeSet) {
        Set<String> relatives = new LinkedHashSet<>();
        for (Path path : changeSet.changed()) {
            relatives.add(relative(path));
        }
        for (Path path : changeSet.deleted()) {
            relatives.add(relative(path));
        }
        return relatives;
    }

    private String relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return config.sourceRoot().relativize(absolute).toString().replace('\\', '/');
    }

    private void snapshotHashes() {
        hashes.clear();
        if (!Files.isDirectory(config.sourceRoot())) {
            return;
        }
        try (Stream<Path> walk = Files.walk(config.sourceRoot())) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(fileFilter::shouldInclude)
                    .toList()) {
                hashes.put(relative(path), sha1(path));
            }
        } catch (IOException ignored) {
            // A tree that cannot be walked leaves the table empty, which makes the next batch look
            // like a change. That is the safe direction: one extra idempotent pass, never a missed one.
        }
    }

    /** SHA-1 of a file's bytes, or {@code "unknown"} when it cannot be read. */
    static String sha1(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return String.format("%040x", new BigInteger(1, digest.digest()));
        } catch (Exception unreadable) {
            return "unknown";
        }
    }

    // ── CLI ───────────────────────────────────────────────────────────────────

    /**
     * The entry point, with the same flags the generator's CLI takes so a watched run and a
     * build-time run are configured identically (§ 8.8/3.23).
     *
     * <pre>
     *   --source &lt;dir&gt;          the source root to watch and regenerate (required)
     *   --report-dir &lt;dir&gt;      where the metadata JSON goes (default: &lt;module&gt;/target/entity-metadata)
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
