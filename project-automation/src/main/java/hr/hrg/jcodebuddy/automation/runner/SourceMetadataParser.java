package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ContentHash;
import hr.hrg.jcodebuddy.engine.index.MemberRecord;
import hr.hrg.jcodebuddy.engine.index.TypeFacts;
import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.meta.MetadataProvider;

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
 * <p><strong>It reads the engine's model, not a second one (plan step 3.0j).</strong> The facts come from
 * {@link ClassIndex#factsOf} — the same {@code TypeFacts} a table row is built from — so the entry's kind,
 * its methods and its class names cannot disagree with the index the rest of the repository reads. Before
 * this, the kind came from {@code hipster-entity-tooling}'s {@code MetadataLocations} and everything else was
 * assembled here, which made this the repository's third metadata shape: thinner than the model, spelled
 * differently, and free to drift from it. Nothing needs a second source reader and nothing needs a second kind
 * map, so neither is here.</p>
 *
 * <h3>What the entry carries, and what it deliberately does not</h3>
 *
 * <p>The facts are exactly those a single file can answer: its identity (the wayhash of the LF-normalised
 * bytes — DEC-029 § 4's one content-identity rule, and the same key the watch caches use), the fully
 * qualified names of the types it declares, its primary type, the type's kind and its declared method names.
 * Nothing that needs a second file is here — no resolved references, no annotation inventory, no relations,
 * which is the same line DEC-041 draws between base and extended metadata.</p>
 *
 * <h3>The primary type</h3>
 *
 * <p>Java's own rule: the top-level type whose name matches the file's. A file whose public type is spelled
 * differently is not valid Java, and an unreadable or type-less file gets an empty {@code fullClassName}
 * rather than an invented one — the entry still exists and still carries its checksum, so "the file changed"
 * and "the file is broken" stay distinguishable.</p>
 *
 * <h3>Purity</h3>
 *
 * <p>Every input comes in through the two parameters, and nothing is written anywhere: the same bytes produce
 * the same entry in a fresh checkout with no prior scan. That is what makes the manual-mode CLI in DEC-W008
 * possible at all.</p>
 */
public final class SourceMetadataParser {

    private SourceMetadataParser() {
    }

    /**
     * Parses one file's bytes into a cache entry.
     *
     * @param relativePath the project-relative path, forward slashes; passed through as the entry's identity and
     *                     used only to recognise the primary type's name
     * @param sourceBytes  the file's bytes as read; line endings are the caller's, normalisation is part of the
     *                     checksum
     * @return the entry; never {@code null}, and its metadata map is never {@code null}
     */
    public static MetadataProvider.CacheEntry parse(String relativePath, byte[] sourceBytes) {
        byte[] bytes = sourceBytes == null ? new byte[0] : sourceBytes;
        // The checksum is computed over the LF-normalised bytes, so a CRLF checkout and an LF checkout of the
        // same content agree — the property that makes a checksum a fact about the code rather than about
        // someone's git configuration (ContentHash, DEC-029 § 4).
        String hash = ContentHash.of(ContentHash.normalizeLineEndings(bytes));
        String source = new String(bytes, StandardCharsets.UTF_8);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("hashAlgo", ContentHash.ALGO);
        metadata.put("normalize", ContentHash.NORMALIZE);
        metadata.put("bytes", bytes.length);
        // The identity again, under the name a reader looks for: one place to compare against, and the same value
        // the entry already carries as its key.
        metadata.put("checksum", hash);

        SourceReader.Read read = SourceReader.readText(source);
        if (!read.readable() || read.unit() == null) {
            metadata.put("readable", false);
            metadata.put("problems", SourceReader.problemsIn(source));
            metadata.put("simpleName", "");
            metadata.put("kind", "");
            metadata.put("methods", List.of());
            metadata.put("classes", List.of());
            return new MetadataProvider.CacheEntry(hash, "", relativePath, metadata);
        }

        List<TypeFacts> facts = ClassIndex.factsOf(read.unit(), source);
        metadata.put("readable", true);
        metadata.put("classes", facts.stream().map(TypeFacts::fqn).toList());

        TypeFacts primary = primaryType(facts, relativePath);
        if (primary == null) {
            metadata.put("simpleName", "");
            metadata.put("kind", "");
            metadata.put("methods", List.of());
            return new MetadataProvider.CacheEntry(hash, "", relativePath, metadata);
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

        return new MetadataProvider.CacheEntry(hash, primary.fqn(), relativePath, metadata);
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
