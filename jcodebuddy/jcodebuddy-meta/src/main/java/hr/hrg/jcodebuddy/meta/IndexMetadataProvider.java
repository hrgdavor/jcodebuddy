package hr.hrg.jcodebuddy.meta;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.index.ContentHash;
import hr.hrg.jcodebuddy.engine.index.MemberRecord;
import hr.hrg.jcodebuddy.engine.index.TypeFacts;
import hr.hrg.jcodebuddy.engine.source.SourceReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata surface over the engine's class index: what the engine knows, answered through
 * {@link MetadataProvider} (plan step 3.0j).
 *
 * <p>Where {@link WatchMetadataProvider} answers from a checksum cache and can therefore say nothing about
 * types, this one answers from the model — so {@link #listClasses()} returns the project's real type names, an
 * entry carries the type it describes, and {@code hasChanged} compares the identity the rest of the repository
 * uses. Together the two cover the question a caller actually has: "what changed" from the watcher, "what is
 * there" from the engine.</p>
 *
 * <p><strong>This is also where DEC-W008's original requirement is finally met.</strong> That decision asked for a
 * {@code parse} whose default "works correctly regardless of whether overriding exists", and its amendment recorded
 * why it could not: a default that parses needs the repository's one source reader, and the module that owned the
 * provider interface deliberately had no reader on its classpath. The maintainer's answer moved the reader into the
 * engine and allowed this module to depend on it, so {@link #parse} lives here and
 * {@link MetadataProvider#parse} now inherits it: no provider has to be told how Java is spelled, because the
 * engine already knows.</p>
 *
 * <p>Identity follows the interface's rule: {@link #get(String)} takes a content hash, so a lookup searches the
 * rows by checksum. Two files with identical content share a checksum and the first in FQN order wins, while
 * {@link #listEntries()} still lists every row.</p>
 */
public final class IndexMetadataProvider implements MetadataProvider {

    /** The rows, in FQN order — the index's own order, which makes every answer here deterministic. */
    private final List<ClassRecord> rows;

    private IndexMetadataProvider(List<ClassRecord> rows) {
        this.rows = List.copyOf(rows);
    }

    /** A provider over an index that already exists in memory. */
    public static IndexMetadataProvider of(ClassIndex index) {
        return new IndexMetadataProvider(index == null ? List.of() : index.rows());
    }

    /**
     * A provider over a table on disk, or an empty one when the table cannot be read.
     *
     * <p>The three paths are the table's own addressing (DEC-029): its file, the directory derived output lives
     * in, and the module and source roots its rows are relative to. A missing or unreadable table yields a provider
     * that knows nothing rather than an exception, because "no index yet" is a normal state for a project that has
     * not run a pass — and a caller that needs one can tell, because {@link #listClasses()} is empty.</p>
     */
    public static IndexMetadataProvider reading(Path indexFile, Path reportDir, Path moduleRoot, Path sourceRoot) {
        if (indexFile == null) {
            return new IndexMetadataProvider(List.of());
        }
        ClassIndex index = ClassIndex.read(indexFile, reportDir == null ? indexFile.getParent() : reportDir,
                moduleRoot, sourceRoot, null);
        return of(index);
    }

    /** The entry for a checksum, or {@code null}; see the class javadoc on identical content. */
    @Override
    public CacheEntry get(String hash) {
        if (hash == null) {
            return null;
        }
        for (ClassRecord row : rows) {
            if (hash.equals(row.checksum())) {
                return entryOf(row);
            }
        }
        return null;
    }

    @Override
    public List<CacheEntry> listEntries() {
        List<CacheEntry> entries = new ArrayList<>(rows.size());
        for (ClassRecord row : rows) {
            entries.add(entryOf(row));
        }
        return List.copyOf(entries);
    }

    /**
     * Whether {@code relPath}'s content differs from the recorded {@code checksum}.
     *
     * <p>Answered from the rows for that path — a file that declares several types appears once per type, and any
     * of them carrying the checksum means the file has not changed. A path the index has no row for has changed as
     * far as this provider can tell, which is the same answer the watcher gives and the honest one.</p>
     */
    @Override
    public boolean hasChanged(String relPath, String checksum) {
        if (relPath == null) {
            return true;
        }
        boolean known = false;
        for (ClassRecord row : rows) {
            if (!relPath.equals(row.path())) {
                continue;
            }
            known = true;
            if (java.util.Objects.equals(row.checksum(), checksum)) {
                return false;
            }
        }
        return true;
    }

    /** Every type the index holds, in FQN order. */
    @Override
    public List<String> listClasses() {
        List<String> classes = new ArrayList<>(rows.size());
        for (ClassRecord row : rows) {
            classes.add(row.fqn());
        }
        return List.copyOf(classes);
    }

    /**
     * DEC-W008's no-cache path, over the engine's own parse: a fully populated entry from the file's bytes alone.
     *
     * <p>The facts are the base set DEC-041 D2 draws and nothing more — the file's content identity over its
     * LF-normalised bytes, the fully qualified names of the types it declares, its primary type, that type's kind
     * and its declared method names. Nothing needing a second file appears, because a file-scoped entry that
     * silently depended on another file's content would be a cache of a fact nobody measured.</p>
     *
     * <p><strong>Static and pure.</strong> Every input comes in through the parameters and nothing is written
     * anywhere, which is what lets a caller use it in a fresh checkout with no daemon and no prior scan — and what
     * lets it be the default behind {@link MetadataProvider#parse} rather than a method every provider copies.</p>
     *
     * @param relativePath the project-relative path, forward slashes; passed through as the entry's identity and
     *                     used only to recognise the primary type's name
     * @param sourceBytes  the file's bytes as read; line endings are the caller's, normalisation is part of the
     *                     checksum
     */
    public static CacheEntry parseSource(String relativePath, byte[] sourceBytes) {
        byte[] bytes = sourceBytes == null ? new byte[0] : sourceBytes;
        String hash = ContentHash.of(ContentHash.normalizeLineEndings(bytes));
        String source = new String(bytes, StandardCharsets.UTF_8);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("hashAlgo", ContentHash.ALGO);
        metadata.put("normalize", ContentHash.NORMALIZE);
        metadata.put("bytes", bytes.length);
        metadata.put("checksum", hash);

        SourceReader.Read read = SourceReader.readText(source);
        if (!read.readable() || read.unit() == null) {
            metadata.put("readable", false);
            metadata.put("problems", SourceReader.problemsIn(source));
            metadata.put("simpleName", "");
            metadata.put("kind", "");
            metadata.put("methods", List.of());
            metadata.put("classes", List.of());
            return new CacheEntry(hash, "", relativePath, metadata);
        }

        List<TypeFacts> facts = ClassIndex.factsOf(read.unit(), source);
        metadata.put("readable", true);
        metadata.put("classes", facts.stream().map(TypeFacts::fqn).toList());

        TypeFacts primary = primaryType(facts, relativePath);
        if (primary == null) {
            metadata.put("simpleName", "");
            metadata.put("kind", "");
            metadata.put("methods", List.of());
            return new CacheEntry(hash, "", relativePath, metadata);
        }

        metadata.put("package", packageOf(primary.fqn()));
        metadata.put("simpleName", simpleNameOf(primary.fqn()));
        metadata.put("kind", primary.kind());
        List<String> methods = new ArrayList<>();
        for (MemberRecord member : primary.members()) {
            if (member.kind() == MemberRecord.Kind.METHOD) {
                methods.add(member.name());
            }
        }
        // Sorted, so two revisions that declare the same methods in a different order produce the same metadata:
        // the entry describes a declaration set, and the declaration order is not part of it.
        Collections.sort(methods);
        metadata.put("methods", methods);

        return new CacheEntry(hash, primary.fqn(), relativePath, metadata);
    }

    /**
     * The top-level type this file declares: the one named after the file, else the first.
     *
     * <p>The fallback is for the two shapes that are legal but unnamed-after-the-file: a file holding only a
     * package-private type, and a file whose name and type disagree (which javac rejects for a public type, but
     * which a pass may still be handed). Choosing the first declaration keeps the answer deterministic instead of
     * returning nothing. Only depth-0 facts are considered, because "the type this file is named after" is a
     * question about top-level declarations.</p>
     */
    private static TypeFacts primaryType(List<TypeFacts> facts, String relativePath) {
        TypeFacts first = null;
        String fileSimpleName = fileName(relativePath);
        for (TypeFacts fact : facts) {
            if (fact.depth() != 0) {
                continue;
            }
            if (first == null) {
                first = fact;
            }
            if (simpleNameOf(fact.fqn()).equals(fileSimpleName)) {
                return fact;
            }
        }
        return first;
    }

    /** One row as a provider entry: its identity, the type it declares, and the file-scoped facts. */
    private static CacheEntry entryOf(ClassRecord row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("checksum", row.checksum());
        metadata.put("path", row.path());
        metadata.put("kind", row.kind());
        metadata.put("simpleName", simpleNameOf(row.fqn()));
        metadata.put("classes", List.of(row.fqn()));
        metadata.put("source", "class-index");
        return new CacheEntry(row.checksum(), row.fqn(), row.path(), metadata);
    }

    /** The package of a top-level type's fully qualified name, empty for the default package. */
    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** {@code a/b/Thing.java} becomes {@code Thing}; the path is never resolved against a filesystem. */
    private static String fileName(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String normalised = relativePath.replace('\\', '/');
        int slash = normalised.lastIndexOf('/');
        String name = slash < 0 ? normalised : normalised.substring(slash + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }
}
