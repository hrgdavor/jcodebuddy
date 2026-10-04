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
 * <h3>What the watch cache can and cannot answer, and what it delegates</h3>
 *
 * <p>It holds three facts per file: the project-relative path, the wayhash of the content, and the last
 * modification time. That is what this provider reports about <em>change</em>, and nothing is invented to fill
 * the gaps: the checksum cache has never known a class name. The class and type questions are therefore
 * <strong>delegated</strong> to an engine-backed provider when one is supplied (the maintainer's answer,
 * 2026-10-03): {@link #listClasses()} asks it, {@link #get(String)} takes the type name from it, and
 * {@link #parse} inherits {@link MetadataProvider}'s engine-backed default. Without a delegate the answers are
 * the honest ones this cache can give — no classes, no type name — and {@link #listClasses()} says so by being
 * empty rather than by inventing something.</p>
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

    /** Where the class and type questions go — the engine's model, or {@code null} when the caller has none. */
    private final MetadataProvider delegate;

    private WatchMetadataProvider(List<WatchedFile> files, MetadataProvider delegate) {
        this.files = List.copyOf(files);
        this.delegate = delegate;
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
        return new WatchMetadataProvider(sorted(files == null ? List.of() : files.values()), null);
    }

    /**
     * The same snapshot, with the class and type questions delegated to {@code model}.
     *
     * <p>What the watcher owns is change; what the engine owns is what the code <em>is</em>. This is where the two
     * meet, and the split is visible in the results: the checksum and the mtime in an entry are the watcher's, and
     * its type name comes from the model.</p>
     *
     * @param files the files, keyed by path; the map's order is not used
     * @param model an engine-backed provider (typically {@link IndexMetadataProvider}), or {@code null} to answer
     *              only what the cache knows
     */
    public static WatchMetadataProvider of(Map<String, WatchedFile> files, MetadataProvider model) {
        return new WatchMetadataProvider(sorted(files == null ? List.of() : files.values()), model);
    }

    /** As {@link #of(Map)}, for a caller that has a collection rather than a map. */
    public static WatchMetadataProvider of(Collection<WatchedFile> files) {
        return new WatchMetadataProvider(sorted(files == null ? List.of() : files), null);
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
     * Empty when there is no delegate, deliberately: a checksum cache holds no class names.
     *
     * <p>Answering with the paths that look like Java files would be a guess dressed as a fact, and a caller of
     * {@code listClasses} is asking which <em>types</em> are known — a question only the model can answer. So with
     * a delegate this returns the engine's types, and without one it returns nothing, which is the difference
     * between "this project has no types" and "this provider was given no model" that a caller can see.</p>
     */
    @Override
    public List<String> listClasses() {
        return delegate == null ? List.of() : delegate.listClasses();
    }

    /**
     * One watched file's entry, with its type name taken from the model when there is one.
     *
     * <p>The checksum and the mtime are the watcher's; {@code fullClassName} is a fact about a declaration, so it
     * comes from the delegate — and an entry that said nothing about the type while the model knew it would be the
     * watcher pretending to be ignorant.</p>
     */
    private CacheEntry entryOf(WatchedFile file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("checksum", file.checksum());
        metadata.put("lastModified", file.lastModified());
        metadata.put("path", file.path());
        metadata.put("source", "watch-cache");
        CacheEntry known = delegate == null ? null : delegate.get(file.checksum());
        if (known == null) {
            // fullClassName is empty on purpose: the watch cache tracks content identity, not declarations.
            return new CacheEntry(file.checksum(), "", file.path(), metadata);
        }
        metadata.put("kind", known.metadata() == null ? "" : known.metadata().get("kind"));
        return new CacheEntry(file.checksum(), known.fullClassName(), file.path(), metadata);
    }
}
