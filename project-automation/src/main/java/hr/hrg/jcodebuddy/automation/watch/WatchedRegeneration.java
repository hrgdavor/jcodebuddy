package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.fsmeta.FsMetaConfig;
import hr.hrg.fsmeta.FsMetaEntry;
import hr.hrg.fsmeta.FsMetaFile;
import hr.hrg.fsmeta.FsMetaHashMode;
import hr.hrg.fsmeta.FsMetaLoad;
import hr.hrg.fsmeta.FsMetaPair;
import hr.hrg.fsmeta.FsMetaScanOptions;
import hr.hrg.fsmeta.FsMetaScanner;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The watch half of a dev-time loop, with the one hazard that matters already solved: <strong>a generator writes into
 * the tree it watches</strong>, so a naive wiring regenerates on its own output and never becomes quiescent.
 *
 * <p>This class is that mechanism, extracted so two generators can share it instead of two copies drifting apart
 * (the plan's step 3.9 says "reuse that mechanism rather than copy it", and step 3.0n is the precedent for collapsing
 * a duplicate). It decides nothing about policy: what to generate is the {@link Regenerator}'s business, and the
 * generator's own configuration is the caller's — this class only decides <em>when</em> a pass runs.</p>
 *
 * <h3>The event source is fs-meta's Zig daemon, and nothing here writes the trio</h3>
 * <p>The intended (and now the only) watcher is {@code fsmeta daemon}: one native process per served root, found
 * through its own host state ({@link FsMetaDaemonDiscovery}), subscribed over its Unix socket with
 * {@link hr.hrg.jcodebuddy.watch.FsMetaClient} ({@code FORMAT.socket.md}). Batches are the change stream; the trio
 * under {@code <root>/.fs-meta} is the state the daemon publishes, and this class only <em>reads</em> it. There is no
 * {@code FsMetaWriter.publish} call and no in-process {@code WatchService}: the engine is the only writer, so two
 * JCodeBuddy processes watching one root cannot fight over the trio.</p>
 *
 * <p>The daemon's batch is already content-classified by the engine (a same-content save is {@code TOUCHED} and
 * produces no batch at all), but that alone does not break the loop: the generator's own output arrives as a batch
 * too, and it must not start another pass. So this class keeps the trio's <em>fingerprints</em> in memory — seeded
 * from the daemon's trio at construction, advanced by each delivered batch, and re-read from the tree after every
 * pass — and a batch whose files still hash to the recorded values is ignored. That is the same content check W5a
 * had, moved to a client that never writes.</p>
 *
 * <h3>Standalone: a read-only trio fallback</h3>
 * <p>When no daemon serves the root there is no live event source. The class still seeds its state from the trio (the
 * daemon's {@code <root>/.fs-meta}, or the W5a location {@code <root>/.jcodebuddy/cache/watch-fs-meta} as a
 * <em>read-only</em> legacy baseline), and {@link #poll()} answers "did the tree change since the state?" for a
 * one-shot tool. What it cannot do without a daemon is deliver events: {@link #start()} then leaves {@link #live()}
 * false and {@link #attachProblem()} explains what to start, rather than pretending to watch.</p>
 *
 * <p>Two consequences follow, and both are deliberate: <strong>at most one no-op pass per generated-file set</strong>
 * (files the generator wrote but that were not in the triggering batch have no entry yet, so the first batch that
 * mentions them runs a pass, that pass changes nothing because generation is idempotent, and the watcher then goes
 * quiet), and <strong>a save with unchanged content does nothing</strong>.</p>
 */
public final class WatchedRegeneration implements AutoCloseable {

    /** The daemon's trio for a served root ({@code FORMAT.md}: the state directory is {@code <root>/.fs-meta}). */
    public static final String DAEMON_STATE_DIRECTORY = ".fs-meta";

    /** The W5a Java-writer location, read only as a legacy baseline and never written again. */
    public static final String LEGACY_STATE_DIRECTORY = ".jcodebuddy/cache/watch-fs-meta";

    /**
     * One decision point.
     *
     * @param sequence        1-based batch counter, so a caller or a test can assert how often the watcher acted
     * @param regenerated     whether a regeneration pass ran
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

    /** The generator's half: run one pass and return what it reported. A failure is a divergence, not an exception. */
    @FunctionalInterface
    public interface Regenerator {

        List<String> regenerate();
    }

    private final Path sourceRoot;
    private final PathFilter filter;
    private final FsMetaScanOptions baseScanOptions;
    private final Path stateDirectory;
    private final Path legacyStateDirectory;
    private final Regenerator regenerator;
    private final Consumer<Pass> observer;
    private final FsMetaDaemonDiscovery.Attach attach;
    private final FsMetaDaemonWatch watch;

    /**
     * The loop state: name → the fingerprint fs-meta last recorded for it. Seeded from the daemon's trio, advanced by
     * every delivered batch, and refreshed from the tree after a pass. Null means "not readable", which degrades to a
     * pass on the next batch — never to a missed one.
     */
    private Map<String, FsMetaFile> state;

    /** The generation the state was seeded from, or 0 when there was no usable trio. Reported, never written. */
    private final long seededGeneration;

    /** The scan controls, which adopt the trio's hash mode once the trio has been read. */
    private FsMetaScanOptions scanOptions;

    private long sequence;
    private long passes;
    private volatile boolean live;
    private volatile String attachProblem = "";

    /**
     * @param sourceRoot   the tree that is both watched and written into
     * @param includes     wildmatch patterns that decide what counts as a source worth reacting to
     * @param excludes     wildmatch patterns for build output and tool state, which must never re-enter the loop
     * @param debounceMs   retained for source compatibility; the daemon owns the batch boundary now, so this value no
     *                     longer starts a local debounce window
     * @param regenerator  what to run when the content really changed
     * @param observer     called after every decision, including the ignored ones, so a caller can log "saw a
     *                     change, nothing to do" instead of guessing why nothing happened
     */
    public WatchedRegeneration(Path sourceRoot, List<String> includes, List<String> excludes, long debounceMs,
                               Regenerator regenerator, Consumer<Pass> observer) {
        this(sourceRoot, includes, excludes, regenerator, observer, FsMetaDaemonDiscovery.discover(sourceRoot));
    }

    /** The injectable-discovery form, for a test that runs a daemon under its own runtime/config directories. */
    WatchedRegeneration(Path sourceRoot, List<String> includes, List<String> excludes, Regenerator regenerator,
                        Consumer<Pass> observer, Map<String, String> discoveryEnv, boolean windows) {
        this(sourceRoot, includes, excludes, regenerator, observer,
                FsMetaDaemonDiscovery.discover(sourceRoot, discoveryEnv, windows));
    }

    private WatchedRegeneration(Path sourceRoot, List<String> includes, List<String> excludes,
                                Regenerator regenerator, Consumer<Pass> observer,
                                FsMetaDaemonDiscovery.Attach attach) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("a source root is required");
        }
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.regenerator = regenerator;
        this.observer = observer == null ? pass -> { } : observer;
        this.filter = PathFilter.of(includes, excludes);
        this.baseScanOptions = scanOptions(excludes);
        this.scanOptions = baseScanOptions;
        this.stateDirectory = this.sourceRoot.resolve(DAEMON_STATE_DIRECTORY);
        this.legacyStateDirectory = this.sourceRoot.resolve(LEGACY_STATE_DIRECTORY);
        this.attach = attach == null ? new FsMetaDaemonDiscovery.Attach(null, "discovery did not run") : attach;
        this.attachProblem = this.attach.attached()
                ? "the fs-meta daemon has not been subscribed yet (call start())"
                : this.attach.problem();
        // Seed before subscribing: the state is what decides whether an arriving batch contains real changes, and a
        // batch that arrives before the seed would be judged against nothing.
        FsMetaPair trio = readTrio(stateDirectory);
        boolean daemonTrio = trio != null;
        if (trio == null) {
            // Read-only legacy baseline: the W5a writer's trio is still a truthful record of the tree it described.
            // It is never written again, and it is only consulted when the daemon has published no trio.
            trio = readTrio(legacyStateDirectory);
        }
        this.state = trio != null ? entriesOf(trio) : scanTree();
        this.seededGeneration = trio == null ? 0L : trio.config().generation();
        if (trio != null) {
            adoptHashMode(trio.config());
        }
        // Subscribe from the generation the state describes, not from zero: the seed already reflects it, and a
        // replay of older batches would be a no-op that only costs a socket's worth of names. A legacy trio's
        // generation belongs to a different writer and says nothing about the daemon's sequence, so that case
        // subscribes from zero and lets a replay refusal take the full-read path.
        long fromBatch = daemonTrio ? seededGeneration : 0L;
        this.watch = this.attach.attached()
                ? new FsMetaDaemonWatch(filter, this.attach.location().socket(), fromBatch, this::onBatch)
                : null;
    }

    /**
     * Attaches to the daemon serving the root and starts delivering batches asynchronously.
     *
     * <p>No daemon is not an error: the class keeps its read-only state and {@link #live()} says whether events will
     * actually arrive. A caller that wants a live loop must check it and tell its user what to start.</p>
     */
    public void start() throws IOException {
        if (watch == null) {
            live = false;
            attachProblem = attach.problem();
            return;
        }
        try {
            watch.start();
            live = watch.isRunning();
            if (!live) {
                attachProblem = "the fsmeta daemon socket " + watch.socket() + " could not be subscribed: "
                        + watch.lastError();
            }
        } catch (IOException failed) {
            live = false;
            attachProblem = "cannot attach to the fsmeta daemon at " + watch.socket() + ": " + failed.getMessage();
        }
    }

    /** Stops watching. Safe to call twice, and safe to call without {@link #start()}. */
    public void stop() {
        live = false;
        if (watch != null) {
            watch.stop();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** Whether a daemon subscription is delivering batches right now. */
    public boolean live() {
        return live && watch != null && watch.isRunning();
    }

    /** Why no live event source is running, or an empty string when {@link #live()} is true. */
    public String attachProblem() {
        return live() ? "" : attachProblem;
    }

    /** The daemon socket this watcher attached to, empty in the read-only fallback. */
    public Optional<Path> daemonSocket() {
        return watch == null ? Optional.empty() : Optional.of(watch.socket());
    }

    /** How many batches were delivered, and how many of them ran a pass. */
    public long batchCount() {
        return sequence;
    }

    public long passCount() {
        return passes;
    }

    /** How many times the daemon refused a replay and the watcher recovered with a full pass. */
    public long recoveryCount() {
        return watch == null ? 0 : watch.recoveries();
    }

    /** The root being watched and written into. */
    public Path sourceRoot() {
        return sourceRoot;
    }

    /** The daemon's trio this watcher reads; it is never written by this class. */
    public Path stateDirectory() {
        return stateDirectory;
    }

    /** The generation the loop state was seeded from, or 0 when there was no usable trio. */
    public long seededGeneration() {
        return seededGeneration;
    }

    /**
     * Handles one batch: decide whether the tree's content actually differs from what the last pass produced, and
     * regenerate if it does.
     *
     * <p>Synchronized, so a pass is never concurrent with another: the tree is one resource and a generator's
     * configuration may be process-global. The daemon's reader thread calls this; a test may call it directly, which
     * is how the decision logic is exercised without a native daemon.</p>
     */
    public synchronized Pass onBatch(WatchBatch batch) {
        sequence++;
        Set<String> triggering = relativePaths(batch);
        Set<String> changed = batch.overflow() || state == null
                ? triggering
                : contentChanges(triggering);

        if (changed.isEmpty() && !batch.overflow()) {
            Pass ignored = new Pass(sequence, false, changed, List.of());
            observer.accept(ignored);
            return ignored;
        }

        List<String> divergences = regenerator.regenerate();
        passes++;
        // The generator writes into the tree it watches. Rescan after the pass so its own output is in the state;
        // that is what makes the next batch for those files a TOUCHED echo rather than a second pass. Nothing is
        // published: the daemon owns the trio, and our state is the in-memory copy of what it last showed us.
        state = scanTree();
        Pass pass = new Pass(sequence, true, changed, divergences);
        observer.accept(pass);
        return pass;
    }

    /**
     * The one-shot, read-only check a tool uses when no daemon is running: compare the tree with the current state
     * and run a pass when the content changed. It never writes the trio, and it is safe to call in a loop a caller
     * owns — this class does not start a watcher for it.
     */
    public synchronized Pass poll() {
        sequence++;
        Map<String, FsMetaFile> current = scanTree();
        Set<String> changed = differences(state, current);
        state = current;
        if (changed.isEmpty()) {
            Pass ignored = new Pass(sequence, false, changed, List.of());
            observer.accept(ignored);
            return ignored;
        }
        List<String> divergences = regenerator.regenerate();
        passes++;
        state = scanTree();
        Pass pass = new Pass(sequence, true, changed, divergences);
        observer.accept(pass);
        return pass;
    }

    /**
     * Applies the batch's paths to the in-memory state and returns the names whose <em>content</em> changed.
     *
     * <p>The classification is fs-meta's {@code FORMAT.md} § 11, the same one {@code FsMetaDiff} applies: a
     * different {@code contentHash}/{@code sizeBytes} is a real change, equal content with a moved mtime is
     * {@code TOUCHED} and is deliberately ignored, and an entry without a usable fingerprint is unknown and is
     * treated as a change (the safe direction). The state is advanced even for an ignored batch, so the echo is not
     * seen again.</p>
     */
    private Set<String> contentChanges(Set<String> triggering) {
        Set<String> changed = new LinkedHashSet<>();
        Map<String, FsMetaFile> next = new LinkedHashMap<>(state);
        for (String relative : triggering) {
            FsMetaFile before = state.get(relative);
            Path absolute = insideRoot(relative);
            if (absolute == null || !Files.isRegularFile(absolute)) {
                next.remove(relative);
                if (before != null) {
                    changed.add(relative);
                }
                continue;
            }
            FsMetaFile after;
            try {
                after = FsMetaScanner.scanFile(sourceRoot, absolute, scanOptions).orElse(null);
            } catch (IOException unreadable) {
                // Unknown, not unchanged: one extra idempotent pass is the safe direction.
                next.remove(relative);
                changed.add(relative);
                continue;
            }
            if (after == null || !filter.shouldInclude(relative)) {
                next.remove(relative);
                if (before != null) {
                    changed.add(relative);
                }
                continue;
            }
            next.put(relative, after);
            if (before == null || !before.contentKnown() || !after.contentKnown()
                    || before.contentHash() != after.contentHash()
                    || before.sizeBytes() != after.sizeBytes()) {
                changed.add(relative);
            }
        }
        state = next;
        return changed;
    }

    /** Every name whose content differs between two in-memory states, by the same rule as {@link #contentChanges}. */
    private static Set<String> differences(Map<String, FsMetaFile> before, Map<String, FsMetaFile> after) {
        Set<String> changed = new LinkedHashSet<>();
        if (after == null) {
            if (before != null) {
                changed.addAll(before.keySet());
            }
            return changed;
        }
        if (before == null) {
            changed.addAll(after.keySet());
            return changed;
        }
        for (Map.Entry<String, FsMetaFile> entry : after.entrySet()) {
            FsMetaFile previous = before.get(entry.getKey());
            FsMetaFile current = entry.getValue();
            if (previous == null || !previous.contentKnown() || !current.contentKnown()
                    || previous.contentHash() != current.contentHash()
                    || previous.sizeBytes() != current.sizeBytes()) {
                changed.add(entry.getKey());
            }
        }
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                changed.add(name);
            }
        }
        return changed;
    }

    /** Reads the trio in {@code directory}, or null when it is absent or not usable. Never writes. */
    private static FsMetaPair readTrio(Path directory) {
        try {
            FsMetaLoad existing = FsMetaPair.loadIfPresent(directory);
            if (existing != null && existing.ok()) {
                return existing.pair();
            }
            return null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The trio's entries as this watcher's state: watched names only, no directories, fingerprints preserved. */
    private Map<String, FsMetaFile> entriesOf(FsMetaPair pair) {
        Map<String, FsMetaFile> files = new LinkedHashMap<>();
        for (int index = 0; index < pair.size(); index++) {
            FsMetaEntry entry = pair.entry(index);
            String name = pair.name(index);
            if (entry.directory() || !filter.shouldInclude(name)) {
                continue;
            }
            files.put(name, entry.contentKnown()
                    ? new FsMetaFile(name, entry.mtimeNs(), entry.sizeBytes(), entry.contentHash(), entry.flags())
                    : FsMetaFile.listingOnly(name));
        }
        return files;
    }

    /** Adopts the hash mode the trio records, so a comparison of the same bytes cannot disagree with the daemon. */
    private void adoptHashMode(FsMetaConfig config) {
        String recorded = config.hashNormalize();
        FsMetaHashMode mode = FsMetaHashMode.fromWire(recorded).orElse(null);
        if (mode != null) {
            scanOptions = baseScanOptions.hashing(mode);
        }
    }

    /** Hashes every watched file under the root, read-only — the state a pass leaves behind. Null when unreadable. */
    private Map<String, FsMetaFile> scanTree() {
        Map<String, FsMetaFile> files = new LinkedHashMap<>();
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (directory.equals(sourceRoot)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = relativeName(directory);
                    if (scanOptions.ignoredDirectoryNames().contains(directory.getFileName().toString())
                            || !filter.shouldEnter(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = relativeName(file);
                    if (!filter.shouldInclude(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        FsMetaScanner.scanFile(sourceRoot, file, scanOptions)
                                .ifPresent(scanned -> files.put(scanned.name(), scanned));
                    } catch (IOException unreadable) {
                        // Leave this file out of the state: the next batch that names it is then a change, which is
                        // the safe direction. An unreadable file must not make the whole state unusable.
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
            return files;
        } catch (IOException | UncheckedIOException failure) {
            return null;
        }
    }

    /** The absolute path for a wire name, or null when the name would escape the root. */
    private Path insideRoot(String relative) {
        Path absolute = sourceRoot.resolve(relative).normalize();
        return absolute.startsWith(sourceRoot) ? absolute : null;
    }

    private String relativeName(Path path) {
        return sourceRoot.relativize(path).toString().replace('\\', '/');
    }

    /** The scan controls: the caller's directory excludes, plus tool state that must never be scanned. */
    private static FsMetaScanOptions scanOptions(List<String> excludes) {
        FsMetaScanOptions options = FsMetaScanOptions.defaults().ignoring(".jcodebuddy");
        for (String pattern : excludes) {
            String name = pattern;
            if (name.startsWith("**/")) {
                name = name.substring(3);
            }
            if (name.endsWith("/**")) {
                name = name.substring(0, name.length() - 3);
            }
            if (!name.isEmpty() && !name.contains("/") && !name.contains("*")) {
                options = options.ignoring(name);
            }
        }
        return options;
    }

    private Set<String> relativePaths(WatchBatch batch) {
        Set<String> relatives = new LinkedHashSet<>(batch.changed());
        relatives.addAll(batch.deleted());
        return relatives;
    }

    /** SHA-1 of a file's bytes, or {@code "unknown"} when it cannot be read. Kept for callers that report hashes. */
    public static String sha1(Path file) {
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
}
