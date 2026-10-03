package hr.hrg.jcodebuddy.meta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata surface over a file-watch checksum cache: what {@code java-watch-agent}'s
 * {@code MetadataCache} knows, answered through {@link MetadataProvider} (the ".kilo"
 * metadata-server plan's step 9, and the reason the provider interface is not just a cache API).
 *
 * <h3>Why the input is a record here and not the agent's class</h3>
 *
 * <p>The plan this implements said to promote {@code java-watch-agent} from test scope to compile scope so
 * this adapter could name {@code MetadataCache} directly. That direction is backwards, and the cost is
 * concrete: {@code java-watch-agent} is an application (it shades a jar and pulls {@code jwa-builder},
 * hence OpenRewrite's LST), while this is a library that the MCP server and the automation runner depend
 * on. A library must not depend on an application. So the reusable half — this adapter and its
 * {@link WatchedFile} input — is promoted here, where it belongs, and the application converts its own
 * type into it (see {@code hr.hrg.watch2.agent.metadata.WatchCacheMetadataProvider}). The rule this
 * follows is the repository's own: a reusable part is promoted into a library, and a project-specific
 * behaviour is handed in through an interface rather than reached for (AGENTS.md § 1.1).</p>
 *
 * <h3>What the watch cache can and cannot answer</h3>
 *
 * <p>It holds three facts per file: the project-relative path, the wayhash of the content, and the last
 * modification time. That is exactly what this provider reports, and nothing is invented to fill the gaps:
 * {@link MetadataProvider.CacheEntry#fullClassName()} is empty because the checksum cache has never known
 * a class name, and {@link #listClasses()} answers with an empty list for the same reason. A caller that
 * needs the class name of a file has {@link MetadataProvider#parse} for the ones with a parser, and
 * {@link MetadataProvider#get} for a real metadata cache — this provider is the one that answers "what has
 * changed", which is the question the watch agent exists for.</p>
 *
 * <p>{@link #parse} is deliberately not overridden: the watch agent has a content reader for its own
 * purposes, not a file-scoped metadata parser, so the interface's default answers with
 * {@link MetadataParseUnsupportedException} — a named refusal instead of a fabricated entry.</p>
 *
 * <h3>Identity: this cache is keyed by path, and the interface is keyed by hash</h3>
 *
 * <p>{@link #get(String)} therefore searches by checksum. Two files with identical content share a
 * checksum, and two files with different content never do; when a checksum matches more than one path the
 * first in path order wins, and {@link #listEntries()} still lists every one of them. The alternative — a
 * second, checksum-keyed index built here — would be a copy that can disagree with the cache it was built
 * from, for a lookup the cache itself does not need.</p>
 */
public final class WatchMetadataProvider implements MetadataProvider {

    /**
     * One file as the watch cache knows it.
     *
     * @param path         the project-relative path, forward slashes
     * @param checksum     the wayhash of the content, as {@code MetadataCache} computes it
     * @param lastModified the mtime the cache recorded, in milliseconds
     */
    public record WatchedFile(String path, String checksum, long lastModified) {
    }

    /** The files, in path order: a deterministic answer for a cache that has no order of its own. */
    private final List<WatchedFile> files;

    private WatchMetadataProvider(List<WatchedFile> files) {
        this.files = List.copyOf(files);
    }

    /**
     * Wraps a snapshot of a watch cache.
     *
     * <p>A snapshot rather than a live reference, and that is the honest shape: this provider is a view of
     * what the cache held when it was asked, so a caller that wants the current answer asks again. A live
     * view would make {@code listEntries} and {@code get} disagree with each other mid-answer.</p>
     *
     * @param files the files, keyed by path; the map's order is not used
     */
    public static WatchMetadataProvider of(Map<String, WatchedFile> files) {
        return new WatchMetadataProvider(sorted(files == null ? List.of() : files.values()));
    }

    /** As {@link #of(Map)}, for a caller that has a collection rather than a map. */
    public static WatchMetadataProvider of(Collection<WatchedFile> files) {
        return new WatchMetadataProvider(sorted(files == null ? List.of() : files));
    }

    private static List<WatchedFile> sorted(Collection<WatchedFile> files) {
        List<WatchedFile> copy = new ArrayList<>();
        for (WatchedFile file : files) {
            if (file != null && file.path() != null) {
                copy.add(file);
            }
        }
        copy.sort(Comparator.comparing(WatchedFile::path));
        return copy;
    }

    /** The entry for a checksum, or {@code null}; see the class javadoc on identical content. */
    @Override
    public CacheEntry get(String hash) {
        if (hash == null) {
            return null;
        }
        for (WatchedFile file : files) {
            if (hash.equals(file.checksum())) {
                return entryOf(file);
            }
        }
        return null;
    }

    @Override
    public List<CacheEntry> listEntries() {
        List<CacheEntry> entries = new ArrayList<>(files.size());
        for (WatchedFile file : files) {
            entries.add(entryOf(file));
        }
        return entries;
    }

    @Override
    public boolean hasChanged(String relPath, String checksum) {
        if (relPath == null) {
            return true;
        }
        for (WatchedFile file : files) {
            if (relPath.equals(file.path())) {
                return !java.util.Objects.equals(file.checksum(), checksum);
            }
        }
        // Not in the cache: unchanged is not something this cache can claim about a file it has never
        // seen, and the interface's contract is "has it changed since the recorded checksum" — with no
        // recording, the answer is yes.
        return true;
    }

    /**
     * Empty, deliberately: the checksum cache holds no class names.
     *
     * <p>Answering with the paths that look like Java files would be a guess dressed as a fact, and a
     * caller of {@code listClasses} is asking which <em>types</em> are known — a question this cache
     * cannot answer until the metadata path (DEC-W007's model, {@code parse}) fills it in.</p>
     */
    @Override
    public List<String> listClasses() {
        return List.of();
    }

    private static CacheEntry entryOf(WatchedFile file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("checksum", file.checksum());
        metadata.put("lastModified", file.lastModified());
        metadata.put("path", file.path());
        metadata.put("source", "watch-cache");
        // fullClassName is empty on purpose: the watch cache tracks content identity, not declarations.
        return new CacheEntry(file.checksum(), "", file.path(), metadata);
    }
}
