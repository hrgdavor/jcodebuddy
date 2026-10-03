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
     * @param problems the caller's diagnostic list, or {@code null} to ignore anomalies
     */
    public FileMetadata entryFor(String moduleRelativePath, Path sourceFile, List<String> problems) {
        Path entry = entryFile(moduleRelativePath);
        if (!Files.isRegularFile(entry)) {
            misses++;
            return null;
        }
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
        if (stored == null || !stored.describes(current)) {
            misses++;
            return null;
        }
        hits++;
        return stored;
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
                Files.size(sourceFile), generated, ClassIndex.factsOf(read.unit(), source),
                TreeQueries.importLines(read.unit()));
        store(entry, problems);
        index.absorb(entry);
        return false;
    }

    /** Files whose stored facts were reused — parses avoided, which is the number 3.0u's gate is about. */
    public int hits() {
        return hits;
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
