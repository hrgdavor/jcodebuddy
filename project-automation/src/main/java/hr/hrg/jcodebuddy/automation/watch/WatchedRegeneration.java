package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.fsmeta.FsMetaDiff;
import hr.hrg.fsmeta.FsMetaEntry;
import hr.hrg.fsmeta.FsMetaFile;
import hr.hrg.fsmeta.FsMetaHashMode;
import hr.hrg.fsmeta.FsMetaLoad;
import hr.hrg.fsmeta.FsMetaPair;
import hr.hrg.fsmeta.FsMetaScanOptions;
import hr.hrg.fsmeta.FsMetaScanner;
import hr.hrg.fsmeta.FsMetaWriter;
import hr.hrg.jcodebuddy.watch.FileWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
 * <p>After every pass it records the {@code fs-meta} trio for every watched {@code .java} file, and a batch whose
 * changed files all still hash to the recorded values is ignored. A timing flag ("ignore events while generating")
 * was rejected because filesystem events arrive asynchronously and can land after the flag clears — which is exactly
 * when the loop would restart.</p>
 *
 * <p>Two consequences follow, and both are deliberate: <strong>at most one no-op pass per generated-file set</strong>
 * (files the generator wrote but that were not in the triggering batch have no entry yet, so the first batch that
 * mentions them runs a pass, that pass changes nothing because generation is idempotent, and the watcher then goes
 * quiet), and <strong>a save with unchanged content does nothing</strong> — an editor that rewrites a file with
 * identical bytes, or a touched timestamp, does not start a pass. The state half is {@code fs-meta}; the event source
 * is {@code jcodebuddy-watch}.</p>
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
    private final PathFilter filter;
    private final FsMetaScanOptions scanOptions;
    private final Path stateDirectory;
    private final Regenerator regenerator;
    private final Consumer<Pass> observer;
    private final FileWatch watcher;

    /** The shared trio/diff state as of the end of the last decision. Null means "fall back to a pass". */
    private FsMetaPair state;

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
        this.regenerator = regenerator;
        this.observer = observer == null ? pass -> { } : observer;
        this.filter = PathFilter.of(includes, excludes);
        this.scanOptions = scanOptions(excludes);
        this.stateDirectory = this.sourceRoot.resolve(".jcodebuddy").resolve("cache").resolve("watch-fs-meta");
        this.watcher = new FileWatch(this.sourceRoot, filter, debounceMs, this::onBatch);
        // Seed the state at construction, so the first batch is judged against the tree as the watcher found it.
        // Doing it here rather than in start() also means the decision logic is exercisable without a real
        // filesystem watcher, which is how the tests assert it.
        this.state = seedState();
    }

    /** Starts watching asynchronously. Returns immediately; batches are handled on the watcher's thread. */
    public void start() throws IOException {
        watcher.start();
    }

    /** Stops watching. Safe to call twice, and safe to call without {@link #start()}. */
    public void stop() {
        watcher.stop();
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
    public synchronized Pass onBatch(WatchBatch batch) {
        sequence++;
        Set<String> triggering = relativePaths(batch);

        if (!batch.overflow()) {
            triggering = contentChanges(triggering);
        }

        if (triggering.isEmpty() && !batch.overflow()) {
            Pass ignored = new Pass(sequence, false, triggering, List.of());
            observer.accept(ignored);
            return ignored;
        }

        List<String> divergences = regenerator.regenerate();
        passes++;
        // The generator writes into the tree it watches. Rescan after the pass so its own output is in the state;
        // that is what makes the next batch for those files a TOUCHED echo rather than a second pass.
        this.state = scanAndPublish();
        Pass pass = new Pass(sequence, true, triggering, divergences);
        observer.accept(pass);
        return pass;
    }

    /**
     * Applies the batch's paths to the state and returns the names whose <em>content</em> changed.
     *
     * <p>fs-meta's own diff decides this, not a timestamp: a same-content save is {@link FsMetaDiff.Kind#TOUCHED}
     * and is deliberately filtered out, while a modification, an addition and a deletion are real. The state is
     * advanced even for an ignored batch, so the echo is not seen again.</p>
     */
    private Set<String> contentChanges(Set<String> triggering) {
        if (state == null) {
            return triggering;
        }
        try {
            List<FsMetaFile> files = toFiles(state);
            for (String relative : triggering) {
                Path absolute = sourceRoot.resolve(relative);
                if (Files.isRegularFile(absolute)) {
                    Optional<FsMetaFile> scanned = FsMetaScanner.scanFile(sourceRoot, absolute, scanOptions);
                    if (scanned.isPresent() && filter.shouldInclude(relative)) {
                        replace(files, scanned.get());
                    } else {
                        remove(files, relative);
                    }
                } else {
                    remove(files, relative);
                }
            }
            FsMetaPair after = publishState(files);
            Set<String> changed = new LinkedHashSet<>();
            for (FsMetaDiff.Change change : FsMetaDiff.between(state, after)) {
                if (change.kind() != FsMetaDiff.Kind.TOUCHED && change.kind() != FsMetaDiff.Kind.UNKNOWN) {
                    changed.add(change.name());
                }
            }
            state = after;
            return changed;
        } catch (IOException unreadable) {
            // A state that cannot be read or published is not a correctness failure: the safe direction is one
            // extra idempotent pass, never a missed one.
            state = null;
            return triggering;
        }
    }

    private FsMetaPair seedState() {
        try {
            FsMetaLoad existing = FsMetaPair.loadIfPresent(stateDirectory);
            if (existing != null && existing.ok()) {
                return existing.pair();
            }
            return scanAndPublish();
        } catch (IOException unreadable) {
            return null;
        }
    }

    private FsMetaPair scanAndPublish() {
        try {
            List<FsMetaFile> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path path : walk.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".java"))
                        .filter(file -> filter.shouldInclude(sourceRoot, file))
                        .toList()) {
                    FsMetaScanner.scanFile(sourceRoot, path, scanOptions).ifPresent(files::add);
                }
            }
            return publishState(files);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private FsMetaPair publishState(List<FsMetaFile> files) throws IOException {
        FsMetaLoad load = FsMetaWriter.publish(stateDirectory, files, FsMetaHashMode.TEXT,
                "jcodebuddy-watched-regeneration/1", sourceRoot.toString());
        if (!load.ok()) {
            throw new IOException(load.describe());
        }
        return load.pair();
    }

    private static List<FsMetaFile> toFiles(FsMetaPair pair) {
        List<FsMetaFile> files = new ArrayList<>();
        for (int index = 0; index < pair.size(); index++) {
            FsMetaEntry entry = pair.entry(index);
            String name = pair.name(index);
            files.add(entry.contentKnown()
                    ? new FsMetaFile(name, entry.mtimeNs(), entry.sizeBytes(), entry.contentHash(), entry.flags())
                    : FsMetaFile.listingOnly(name));
        }
        return files;
    }

    private static void replace(List<FsMetaFile> files, FsMetaFile replacement) {
        remove(files, replacement.name());
        files.add(replacement);
        files.sort(Comparator.comparing(FsMetaFile::name));
    }

    private static void remove(List<FsMetaFile> files, String name) {
        files.removeIf(file -> file.name().equals(name));
    }

    /** The scan controls: the caller's directory excludes plus the watcher's own state directory. */
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
