package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.watch2.core.BatchedFileWatcher;
import hr.hrg.watch2.core.ChangeSet;
import hr.hrg.watch2.core.FileFilter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The watch half of a dev-time loop, with the one hazard that matters already solved: <strong>a generator writes into
 * the tree it watches</strong>, so a naive wiring regenerates on its own output and never becomes quiescent.
 *
 * <p>This class is that mechanism, extracted so two generators can share it instead of two copies drifting apart
 * (the plan's step 3.9 says "reuse that mechanism rather than copy it", and step 3.0n is the precedent for collapsing
 * a duplicate). It decides nothing about policy: what to generate is the {@link Regenerator}'s business, and the
 * generator's own configuration is the caller's — this class only decides <em>when</em> a pass runs.</p>
 *
 * <h3>The loop breaker is a content check, not a timing heuristic</h3>
 * <p>After every pass it records the SHA-1 of every watched file, and a batch whose changed files all still hash to
 * the recorded values is ignored. A timing flag ("ignore events while generating") was rejected because filesystem
 * events arrive asynchronously and can land after the flag clears — which is exactly when the loop would restart.</p>
 *
 * <p>Two consequences follow, and both are deliberate: <strong>at most one no-op pass per generated-file set</strong>
 * (files the generator wrote but that were not in the triggering batch have no recorded hash yet, so the first batch
 * mentioning them runs a pass, that pass changes nothing because generation is idempotent, and the watcher then goes
 * quiet), and <strong>a save with unchanged content does nothing</strong> — an editor that rewrites a file with
 * identical bytes, or a touched timestamp, does not start a pass.</p>
 */
public final class WatchedRegeneration implements AutoCloseable {

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
    private final long debounceMs;
    private final FileFilter fileFilter;
    private final Regenerator regenerator;
    private final Consumer<Pass> observer;

    /** SHA-1 of every watched file as of the end of the last pass. */
    private final Map<String, String> hashes = new LinkedHashMap<>();

    private BatchedFileWatcher watcher;
    private long sequence;
    private long passes;

    /**
     * @param sourceRoot   the tree that is both watched and written into
     * @param includes     glob patterns that decide what counts as a source worth reacting to
     * @param excludes     glob patterns for build output and tool state, which must never re-enter the loop
     * @param debounceMs   quiet window before a batch is delivered; a non-positive value means the default
     * @param regenerator  what to run when the content really changed
     * @param observer     called after every decision, including the ignored ones, so a caller can log "saw a
     *                     change, nothing to do" instead of guessing why nothing happened
     */
    public WatchedRegeneration(Path sourceRoot, List<String> includes, List<String> excludes, long debounceMs,
                               Regenerator regenerator, Consumer<Pass> observer) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("a source root is required");
        }
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.debounceMs = debounceMs <= 0 ? 300 : debounceMs;
        this.regenerator = regenerator;
        this.observer = observer == null ? pass -> { } : observer;
        this.fileFilter = new FileFilter(this.sourceRoot, includes, excludes);
        // Seed the hash table at construction, so the first batch is judged against the tree as the watcher found
        // it. Doing it here rather than in start() also means the decision logic is exercisable without a real
        // filesystem watcher, which is how the tests assert it.
        snapshotHashes();
    }

    /** Starts watching asynchronously. Returns immediately; batches are handled on the watcher's thread. */
    public void start() throws IOException {
        if (watcher != null) {
            return;
        }
        watcher = new BatchedFileWatcher(sourceRoot, fileFilter, debounceMs, this::onBatch);
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

    /** The root being watched and written into. */
    public Path sourceRoot() {
        return sourceRoot;
    }

    /**
     * Handles one batch: decide whether the tree's content actually differs from what the last pass produced, and
     * regenerate if it does.
     *
     * <p>Synchronized, so a pass is never concurrent with another: the tree is one resource and a generator's
     * configuration may be process-global.</p>
     */
    public synchronized Pass onBatch(ChangeSet changeSet) {
        sequence++;
        Set<String> triggering = relativePaths(changeSet);

        if (!changeSet.fullRecompile() && !contentDiffers(triggering)) {
            Pass ignored = new Pass(sequence, false, triggering, List.of());
            observer.accept(ignored);
            return ignored;
        }

        List<String> divergences = regenerator.regenerate();
        passes++;
        snapshotHashes();
        Pass pass = new Pass(sequence, true, triggering, divergences);
        observer.accept(pass);
        return pass;
    }

    /** Whether any changed or deleted file differs from the state the last pass left behind. */
    private boolean contentDiffers(Set<String> triggering) {
        for (String relative : triggering) {
            Path absolute = sourceRoot.resolve(relative);
            if (!Files.exists(absolute)) {
                // A deletion always matters: the generator may need to remove or recreate a file.
                return true;
            }
            String recorded = hashes.get(relative);
            if (recorded == null || !recorded.equals(sha1(absolute))) {
                return true;
            }
        }
        // Every changed file still hashes to what the last pass left, so this batch is the echo of that pass (or a
        // save with identical content). Nothing to do — and this is the branch that keeps the watcher from
        // regenerating forever on its own output.
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
        return sourceRoot.relativize(absolute).toString().replace('\\', '/');
    }

    private void snapshotHashes() {
        hashes.clear();
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(fileFilter::shouldInclude)
                    .toList()) {
                hashes.put(relative(path), sha1(path));
            }
        } catch (IOException ignored) {
            // A tree that cannot be walked leaves the table empty, which makes the next batch look like a change.
            // That is the safe direction: one extra idempotent pass, never a missed one.
        }
    }

    /** SHA-1 of a file's bytes, or {@code "unknown"} when it cannot be read. */
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
