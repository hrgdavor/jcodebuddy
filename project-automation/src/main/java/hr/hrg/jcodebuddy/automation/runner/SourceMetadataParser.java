package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.hipster.entity.tooling.MetadataLocations;
import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import hr.hrg.jcodebuddy.engine.index.ContentHash;
import hr.hrg.jcodebuddy.meta.MetadataProvider;
import org.openrewrite.java.tree.J;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEC-W008's reference implementation of {@link MetadataProvider#parse}: file-scoped metadata from a
 * file's own bytes, with no cache, index or daemon anywhere in the picture.
 *
 * <h3>Why it lives here and not in `metadata-server`</h3>
 *
 * <p>Because the source reader does. {@code metadata-server} owns the provider interface and has no
 * parser on its classpath by design; the repository's one Java source representation is OpenRewrite's
 * LST (DEC-030), and it lives in {@code hipster-entity-tooling}. DEC-W008 itself left the location to
 * "module dependency resolution" and named {@link InMemoryMetadataCacheProvider} as the override, which
 * is where this class is called from. Putting a second, hand-rolled Java scanner in the server module
 * would have been the one way to get this wrong twice: a second representation, in the module whose job
 * is to serve metadata, not to know how Java is spelled.</p>
 *
 * <h3>What the entry carries, and what it deliberately does not</h3>
 *
 * <p>The facts are exactly those a single file can answer: its identity (the wayhash of the
 * LF-normalised bytes — DEC-029 § 4's one content-identity rule, and the same key the watch caches
 * use), its primary type's fully qualified name, the type's kind and its declared method names. Nothing
 * that needs a second file is here — no resolved references, no annotation inventory, no relations.
 * DEC-W008 puts those in the relation store DEC-W009 describes, because a file-scoped entry that
 * silently depended on another file's content would be a cache of a fact nobody measured.</p>
 *
 * <h3>The primary type</h3>
 *
 * <p>Java's own rule: the top-level type whose name matches the file's. A file whose public type is
 * spelled differently is not valid Java, and an unreadable or type-less file gets an empty
 * {@code fullClassName} rather than an invented one — the entry still exists and still carries its
 * checksum, so "the file changed" and "the file is broken" stay distinguishable.</p>
 *
 * <h3>Purity</h3>
 *
 * <p>Every input comes in through the two parameters, and nothing is written anywhere: the same bytes
 * produce the same entry in a fresh checkout with no prior scan. That is what makes the manual-mode CLI
 * in DEC-W008 possible at all.</p>
 */
public final class SourceMetadataParser {

    private SourceMetadataParser() {
    }

    /**
     * Parses one file's bytes into a cache entry.
     *
     * @param relativePath the project-relative path, forward slashes; passed through as the entry's
     *                     identity and used only to recognise the primary type's name
     * @param sourceBytes  the file's bytes as read; line endings are the caller's, normalisation is
     *                     part of the checksum
     * @return the entry; never {@code null}, and its metadata map is never {@code null}
     */
    public static MetadataProvider.CacheEntry parse(String relativePath, byte[] sourceBytes) {
        byte[] bytes = sourceBytes == null ? new byte[0] : sourceBytes;
        // The checksum is computed over the LF-normalised bytes, so a CRLF checkout and an LF checkout of
        // the same content agree — the property that makes a checksum a fact about the code rather than
        // about someone's git configuration (ContentHash, DEC-029 § 4).
        String hash = ContentHash.of(ContentHash.normalizeLineEndings(bytes));
        String source = new String(bytes, StandardCharsets.UTF_8);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("hashAlgo", ContentHash.ALGO);
        metadata.put("normalize", ContentHash.NORMALIZE);
        metadata.put("bytes", bytes.length);

        SourceReader.Read read = SourceReader.readText(source);
        if (!read.readable()) {
            metadata.put("readable", false);
            metadata.put("problems", SourceReader.problemsIn(source));
            metadata.put("simpleName", "");
            metadata.put("kind", "");
            metadata.put("methods", List.of());
            return new MetadataProvider.CacheEntry(hash, "", relativePath, metadata);
        }

        J.CompilationUnit cu = read.unit();
        metadata.put("readable", true);
        String packageName = TreeQueries.packageName(cu);
        metadata.put("package", packageName);

        J.ClassDeclaration primary = primaryType(cu, relativePath);
        if (primary == null) {
            metadata.put("simpleName", "");
            metadata.put("kind", "");
            metadata.put("methods", List.of());
            return new MetadataProvider.CacheEntry(hash, "", relativePath, metadata);
        }

        String simpleName = primary.getSimpleName();
        metadata.put("simpleName", simpleName);
        metadata.put("kind", MetadataLocations.kindOf(primary));
        List<String> methods = new ArrayList<>();
        for (J.MethodDeclaration method : TreeQueries.methodsOf(primary)) {
            methods.add(method.getSimpleName());
        }
        // Sorted, so two revisions that declare the same methods in a different order produce the same
        // metadata: the entry describes a declaration set, and the declaration order is not part of it.
        Collections.sort(methods);
        metadata.put("methods", methods);

        String fullClassName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        return new MetadataProvider.CacheEntry(hash, fullClassName, relativePath, metadata);
    }

    /**
     * The top-level type this file declares: the one named after the file, else the first.
     *
     * <p>The fallback is for the two shapes that are legal but unnamed-after-the-file: a file holding
     * only a package-private type, and a file whose name and type disagree (which javac rejects for a
     * public type, but which a pass may still be handed). Choosing the first declaration keeps the answer
     * deterministic instead of returning nothing.</p>
     */
    private static J.ClassDeclaration primaryType(J.CompilationUnit cu, String relativePath) {
        List<J.ClassDeclaration> types = TreeQueries.topLevelTypes(cu);
        if (types.isEmpty()) {
            return null;
        }
        String fileSimpleName = fileName(relativePath);
        for (J.ClassDeclaration type : types) {
            if (type.getSimpleName().equals(fileSimpleName)) {
                return type;
            }
        }
        return types.get(0);
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
