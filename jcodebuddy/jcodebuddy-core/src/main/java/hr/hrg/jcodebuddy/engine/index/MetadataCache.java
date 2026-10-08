package hr.hrg.jcodebuddy.engine.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;

/**
 * The per-file base cache: one {@link FileMetadata} entry per Java source file, under the module's derived
 * {@code .jcodebuddy/cache/} (DEC-041, plan step 3.0u; DEC-026 for where derived output lives).
 *
 * <p><strong>The decision this class exists to make is one question:</strong> may a file's stored facts be reused,
 * or must the file be read again? The answer is its content hash — an entry is reused only when the hash it
 * carries is the hash of the bytes on disk now (DEC-041 D3). Everything else here is bookkeeping around that: one
 * file per entry, named by the source file's own relative path so a human can find it, and counters so a caller can
 * <em>prove</em> that a warm rebuild parsed nothing rather than assert it.</p>
 *
 * <p><strong>Nothing here is ever fatal.</strong> A missing, unreadable, corrupt or older-format entry costs a
 * parse (DEC-041 D7) — reported to the caller's problem list when it is a real anomaly, never an exception, because
 * a cache is an optimisation and a broken one must not fail a pass. A file whose parse is not readable is
 * deliberately <em>not</em> cached: caching unreliable facts would make the next pass skip the file that the pass
 * itself is supposed to report.</p>
 *
 * <p><strong>Entries are derived and ignored.</strong> They live under the module's {@code .jcodebuddy/} (DEC-026),
 * a project does not commit them, and deleting the whole directory changes no answer — only the time taken
 * (DEC-041 acceptance 5).</p>
 */
public final class MetadataCache {

    /** The derived subtree entries live in, beside {@code index/} and {@code metadata/}. */
    public static final String CACHE_DIR_NAME = "cache";

    private final Path root;
    private int hits;
    private int statHits;
    private int misses;
    private int written;

    public MetadataCache(Path cacheRoot) {
        this.root = cacheRoot;
    }

    /** The cache beside the module's other derived output, at {@code <reportDir>/cache/}. */
    public static MetadataCache beside(Path reportDir) {
        return new MetadataCache(reportDir.resolve(CACHE_DIR_NAME));
    }

    /** Where one source file's entry lives: its own relative path plus {@code .json}. */
    public Path entryFile(String moduleRelativePath) {
        return root.resolve(moduleRelativePath + ".json");
    }

    /**
     * The entry for one file when it describes the file's <strong>current</strong> content, else {@code null}.
     *
     * <p>This is the whole reuse rule in one method, and the accounting is here too: a {@code null} is a miss —
     * whether because no entry exists, because the entry is unreadable, or because the file changed under it —
     * and a hit means the caller did not have to parse. The three cases are deliberately not distinguished by the
     * counters: what a caller needs to know is how many parses were avoided, and a rebuild that reports hits equal
     * to its file count is the proof DEC-041's second criterion asks for.</p>
     *
     * <p><strong>Cheapest first (plan step 6.6).</strong> The decision runs in tiers, because the expensive part is
     * reading and hashing the <em>source</em> and the cheap part is a `stat`: the entry is read (a few kilobytes),
     * and then</p>
     *
     * <ol>
     *   <li>its recorded size and last-modified time are compared with the file's — equal means <em>reused</em>,
     *       with no read of the source and no hash at all ({@link #statHits()});</li>
     *   <li>otherwise the source is hashed and compared with the entry's checksum — equal means the file was
     *       <em>touched, not changed</em>, and is reused (the entry's own time is refreshed on the next store);</li>
     *   <li>otherwise the file changed, so this is a miss and the caller parses it.</li>
     * </ol>
     *
     * <p>Before this, every entry lookup hashed every file on every pass, which is the one cost a warm rebuild was
     * still paying in full. An entry written before the timestamp existed answers tier 1 {@code false} and is
     * resolved by tier 2, so nothing has to be invalidated for this to take effect.</p>
     *
     * @param problems the caller's diagnostic list, or {@code null} to ignore anomalies
     */
    public FileMetadata entryFor(String moduleRelativePath, Path sourceFile, List<String> problems) {
        Path entry = entryFile(moduleRelativePath);
        if (!Files.isRegularFile(entry)) {
            misses++;
            return null;
        }
        FileMetadata stored;
        try {
            stored = FileMetadata.parse(Files.readString(entry, StandardCharsets.UTF_8), problems);
        } catch (IOException | RuntimeException unreadable) {
            misses++;
            if (problems != null) {
                problems.add("the base entry at " + entry + " could not be read (" + unreadable.getMessage()
                        + "), so " + moduleRelativePath + " will be parsed again");
            }
            return null;
        }
        if (stored == null) {
            misses++;
            return null;
        }
        // Tier 1: the filesystem says nothing moved, so the source is neither read nor hashed.
        long currentSize;
        long currentLastModified;
        try {
            currentSize = Files.size(sourceFile);
            currentLastModified = Files.getLastModifiedTime(sourceFile).toMillis();
        } catch (IOException unreadable) {
            misses++;
            if (problems != null) {
                problems.add("cannot stat " + sourceFile + " to decide whether its cached facts are current: "
                        + unreadable.getMessage());
            }
            return null;
        }
        if (stored.matchesStat(currentSize, currentLastModified)) {
            hits++;
            statHits++;
            return stored;
        }
        // Tier 2: the stat moved (a touch, or an edit). A touch is not a change, so the hash decides.
        String current;
        try {
            current = ContentHash.of(sourceFile);
        } catch (IOException unreadable) {
            // The file the entry claims to describe cannot be hashed, so nothing can be shown to be current.
            misses++;
            if (problems != null) {
                problems.add("cannot hash " + sourceFile + " to decide whether its cached facts are current: "
                        + unreadable.getMessage());
            }
            return null;
        }
        if (!stored.describes(current)) {
            misses++;
            return null;
        }
        // Tier 2 said yes: the hash is still right, so rewrite the entry with the stat just observed and the next
        // pass answers in tier 1. This is what makes a touch cost one hash rather than one hash per pass, and what
        // heals an entry written before the timestamp existed.
        refresh(stored.withStat(currentSize, currentLastModified), problems);
        hits++;
        return stored;
    }

    /**
     * Rewrites one entry with facts the caller has just confirmed, <strong>without</strong> counting it as a stored
     * parse (plan step 6.6).
     *
     * <p>{@link #entriesWritten()} answers "how many files did this pass parse and store", which is a number tests
     * assert exactly; a refresh is bookkeeping on an entry that was <em>not</em> parsed, so counting it there would
     * make that number mean two things. Best-effort like {@link #store}: a failed refresh costs a hash next pass.</p>
     */
    private void refresh(FileMetadata entry, List<String> problems) {
        Path file = entryFile(entry.path());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.toJson(), StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            if (problems != null) {
                problems.add("the base entry for " + entry.path() + " could not be refreshed at " + file + " ("
                        + unwritable.getMessage() + "), so it will be hashed again next pass");
            }
        }
    }

    /** Writes one entry, creating its directory. Best-effort by design: a failure costs a parse next time. */
    public void store(FileMetadata entry, List<String> problems) {
        Path file = entryFile(entry.path());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.toJson(), StandardCharsets.UTF_8);
            written++;
        } catch (IOException unwritable) {
            if (problems != null) {
                problems.add("the base entry for " + entry.path() + " could not be written to " + file + " ("
                        + unwritable.getMessage() + "), so its facts will be recomputed next pass");
            }
        }
    }

    /**
     * Consume one source file into {@code index}: the cache's entry when it is current, otherwise a parse whose
     * facts are then stored for the next pass.
     *
     * <p>The convenience for a caller that only wants the file's facts in the index — a test, or a pass with no
     * other reason to read the file. A pass that must report parser diagnostics wants the two halves separately
     * ({@link #entryFor} to decide, its own read to report, {@link #store} to keep), because that is where its
     * reporting happens.</p>
     *
     * @return {@code true} when the entry was reused, so the caller can see that no parse happened
     */
    public boolean consume(ClassIndex index, String moduleRelativePath, Path sourceFile, boolean generated,
                           List<String> problems) throws IOException {
        FileMetadata cached = entryFor(moduleRelativePath, sourceFile, problems);
        if (cached != null) {
            index.absorb(cached);
            return true;
        }
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        SourceReader.Read read = SourceReader.readText(source);
        if (!read.readable() || read.unit() == null) {
            // Unreliable facts are not cached: the pass that owns the file has to report it, and a cached entry
            // would make the next pass skip exactly the file it must complain about.
            if (problems != null) {
                problems.addAll(SourceReader.problemsIn(source));
            }
            return false;
        }
        FileMetadata entry = FileMetadata.of(moduleRelativePath, ContentHash.of(sourceFile),
                Files.size(sourceFile), Files.getLastModifiedTime(sourceFile).toMillis(), generated,
                ClassIndex.factsOf(read.unit(), source), TreeQueries.importLines(read.unit()));
        store(entry, problems);
        index.absorb(entry);
        return false;
    }

    /** Files whose stored facts were reused — parses avoided, which is the number 3.0u's gate is about. */
    public int hits() {
        return hits;
    }

    /**
     * Of the {@link #hits()}, how many were decided by a `stat` alone — no read of the source and no hash.
     *
     * <p>This is the counter plan step 6.6 is measured by: a warm rebuild over unchanged files should report this
     * equal to its hit count, and a run where it is zero has hashed everything and gained nothing from the gate. It
     * is reported separately from {@link #hits()} rather than as a percentage so a test can assert the exact case it
     * means — "touched, not changed" is a hit in tier 2 and must <em>not</em> be counted here.</p>
     */
    public int statHits() {
        return statHits;
    }

    /** Files that had to be read: no entry, an unreadable one, or one the file has moved past. */
    public int misses() {
        return misses;
    }

    /** Entries written, so a test can see that one edit wrote exactly one. */
    public int entriesWritten() {
        return written;
    }

    /** Forgets the counters, not the entries — so one test can measure two passes over the same cache. */
    public void resetCounters() {
        hits = 0;
        statHits = 0;
        misses = 0;
        written = 0;
    }

    /** Every entry currently stored, for a caller that wants to compare them (a test, a sweep). */
    public List<Path> entries() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).sorted().forEach(found::add);
        }
        return List.copyOf(found);
    }
}
