package hr.hrg.jcodebuddy.engine.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The tiered staleness gate as one answerable question: <strong>is this item stale, and why?</strong>
 * (plan step 6.6, DEC-041 D3).
 *
 * <p>The maintainer's design, as given on 2026-10-08, is a gate that runs cheapest first: a {@code stat}
 * (size + last-modified) decides most files, a content hash decides only the ones the stat moved, and a
 * rebuild happens only when the hash differs. {@link MetadataCache#entryFor} already implements exactly
 * that order for a pass that wants the stored facts — and this class is the <em>same decision</em> for a
 * caller that wants only the verdict, with no index to absorb and no write to perform.</p>
 *
 * <h3>Why a second entry point rather than a second rule</h3>
 * <p>A watch loop and an on-demand command must not disagree about what "stale" means, and the way that
 * goes wrong is two implementations of the ladder drifting apart — one comparing a size the other
 * forgot. The ladder lives here once; {@link MetadataCache#entryFor} keeps the pass's variant because it
 * also <em>refreshes</em> the entry it reuses (the self-healing half), which is a write this component
 * must not perform.</p>
 *
 * <h3>What this class deliberately does not do</h3>
 * <ul>
 *   <li><strong>It never writes.</strong> No entry is stored, refreshed or created: a {@code stat} only
 *       run, a hash only computed, and the verdict returned. That is what makes it safe to call from a
 *       watch loop on every batch and from a CLI command in a checkout nobody has built.</li>
 *   <li><strong>It never guesses.</strong> A file with no entry is {@link Verdict#UNKNOWN}, not "stale"
 *       and not "fresh": nothing was ever recorded about it, so there is no baseline to compare against
 *       and no way to tell "new file" from "the cache was deleted". The enum has three more values than a
 *       boolean because those are three different operational facts.</li>
 * </ul>
 */
public final class Staleness {

    /** The verdict for one file, in the order the gate reaches it. */
    public enum Verdict {
        /** No entry describes this file, so nothing can be said about it (a new file, or a deleted cache). */
        UNKNOWN,
        /** The stat matched: the entry's facts describe the file and no read of it was needed. */
        UNCHANGED,
        /** The stat moved but the content hash did not: the file was written with the same bytes. */
        TOUCHED,
        /** The content hash differs from the entry's: the file's stored facts are no longer about it. */
        STALE
    }

    /** Which tier of the gate produced a verdict — the answer a person asks next, after "is it stale?". */
    public enum Tier {
        /** No entry was found, so no tier ran. */
        NONE,
        /** Size and last-modified agreed: one {@code stat}, no read, no hash. */
        STAT,
        /** The stat moved, so the content was hashed: a whole-file read, and the tier that costs. */
        HASH
    }

    /** One file's answer. */
    public record Result(String path, Verdict verdict, Tier tier, String cause) {

        /** Whether this file's stored facts may be reused as they are. */
        public boolean reusable() {
            return verdict == Verdict.UNCHANGED || verdict == Verdict.TOUCHED;
        }

        /** Whether the caller has to do the expensive thing: rebuild, or parse for the first time. */
        public boolean rebuildNeeded() {
            return verdict == Verdict.STALE || verdict == Verdict.UNKNOWN;
        }
    }

    private final MetadataCache cache;

    public Staleness(Path cacheRoot) {
        this.cache = new MetadataCache(cacheRoot);
    }

    /** The gate for the module whose derived output sits at {@code reportDir} (e.g. {@code .jcodebuddy/metadata}). */
    public static Staleness beside(Path reportDir) {
        return new Staleness(reportDir.resolve(MetadataCache.CACHE_DIR_NAME));
    }

    /**
     * The verdict for one file.
     *
     * @param moduleRelativePath the key the entry is stored under — the file's path relative to the
     *                           module root, with {@code /} separators, exactly as the pass stores it
     * @param file               the file on disk
     */
    public Result check(String moduleRelativePath, Path file) {
        Path entryFile = cache.entryFile(moduleRelativePath);
        if (!Files.isRegularFile(entryFile)) {
            return new Result(moduleRelativePath, Verdict.UNKNOWN, Tier.NONE,
                    "no base entry at " + entryFile.getFileName());
        }
        if (!Files.isRegularFile(file)) {
            return new Result(moduleRelativePath, Verdict.UNKNOWN, Tier.NONE,
                    "the entry exists but the file does not: " + file);
        }
        FileMetadata stored;
        try {
            stored = FileMetadata.parse(Files.readString(entryFile, StandardCharsets.UTF_8), null);
        } catch (IOException | RuntimeException unreadable) {
            return new Result(moduleRelativePath, Verdict.UNKNOWN, Tier.NONE,
                    "the base entry could not be read: " + unreadable.getMessage());
        }
        if (stored == null) {
            return new Result(moduleRelativePath, Verdict.UNKNOWN, Tier.NONE,
                    "the base entry is not one this build understands");
        }
        long size;
        long lastModified;
        try {
            size = Files.size(file);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unreadable) {
            return new Result(moduleRelativePath, Verdict.UNKNOWN, Tier.NONE,
                    "the file cannot be stat'ed: " + unreadable.getMessage());
        }
        // Tier 1: the filesystem says nothing moved, so nothing is read and nothing is hashed.
        if (stored.matchesStat(size, lastModified)) {
            return new Result(moduleRelativePath, Verdict.UNCHANGED, Tier.STAT,
                    "size and modification time are the ones the entry recorded");
        }
        // Tier 2: the stat moved, so only the content can decide. A touch is not a change.
        String current;
        try {
            current = ContentHash.of(file);
        } catch (IOException unreadable) {
            return new Result(moduleRelativePath, Verdict.UNKNOWN, Tier.HASH,
                    "the content could not be hashed: " + unreadable.getMessage());
        }
        if (stored.describes(current)) {
            return new Result(moduleRelativePath, Verdict.TOUCHED, Tier.HASH,
                    "the modification time moved but the content hash did not");
        }
        return new Result(moduleRelativePath, Verdict.STALE, Tier.HASH,
                "the content hash differs from the one the entry recorded");
    }

    /**
     * The verdict for every {@code .java} file under {@code sourceRoot}, in path order.
     *
     * <p>The sweep the on-demand and watch entries share: the same {@link #check} per file, so neither can
     * grow an opinion of its own about what a stale item is.</p>
     *
     * @param sourceRoot the root to walk (the module's {@code src/main/java})
     * @param moduleRoot the root the keys are relative to — the module directory, since that is what the
     *                   pass stored
     * @param filter     an extra predicate a caller may narrow with, or {@code null} for every {@code .java}
     * @throws IOException when the tree cannot be walked at all; a single unreadable file is a verdict,
     *                     not a failure
     */
    public List<Result> scan(Path sourceRoot, Path moduleRoot, Predicate<Path> filter) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(sourceRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> filter == null || filter.test(path))
                    .sorted()
                    .forEach(files::add);
        }
        List<Result> results = new ArrayList<>(files.size());
        for (Path file : files) {
            results.add(check(moduleRelativePath(moduleRoot, file), file));
        }
        return List.copyOf(results);
    }

    /** The key a file is stored under: relative to the module root, with {@code /} separators. */
    public static String moduleRelativePath(Path moduleRoot, Path file) {
        Path root = moduleRoot.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        try {
            return root.relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException outsideModule) {
            // A file outside the module has no module-relative key; its own name is the honest fallback,
            // and it will simply have no entry rather than being keyed under a path that would collide.
            return absolute.getFileName().toString();
        }
    }
}
