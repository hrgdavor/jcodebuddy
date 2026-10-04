package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.jcodebuddy.engine.JcodebuddyDirectory;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.index.MetadataCache;
import hr.hrg.jcodebuddy.meta.MetadataProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The scan a dev-time run does before it serves anything: read the project's Java files into the engine's model,
 * and publish what it read as {@link MetadataProvider.CacheEntry} values (plan step 3.0j).
 *
 * <p><strong>It used to be a metadata path of its own</strong>, and every part of that is gone: a SHA-1 checksum
 * (the repository has one content identity, DEC-029 § 4, and it is not SHA-1), a hand-built {@code Map} per file,
 * and a walk that parsed every file on every run. The scan now hands each file to the engine's per-file cache
 * (DEC-041), which reuses the facts of a file whose bytes have not changed, and publishes entries built from the
 * index rows the engine produced — so a caller of the server sees the same names and the same checksums the rest
 * of the repository does.</p>
 */
public class MetadataAnalysis {
    private final Path projectRoot;
    private final MetadataProvider provider;

    public MetadataAnalysis(Path projectRoot, MetadataProvider provider) {
        this.projectRoot = projectRoot;
        this.provider = provider;
    }

    /**
     * Reads the project into the model and publishes one entry per declared type.
     *
     * @return the number of files the scan had to read, so a caller can see the cache working
     */
    public int scan() throws IOException {
        Path derived = projectRoot.resolve(JcodebuddyDirectory.DIR);
        ClassIndex index = ClassIndex.forPass(derived, projectRoot, projectRoot);
        MetadataCache cache = MetadataCache.beside(derived);
        List<String> problems = new ArrayList<>();

        int filesRead = 0;
        for (Path path : javaFiles(projectRoot)) {
            String relativePath = projectRoot.relativize(path).toString().replace('\\', '/');
            // false when the entry was reused: the cache's own answer to "did this scan read the file".
            if (!cache.consume(index, relativePath, path, false, problems)) {
                filesRead++;
            }
        }
        index.write();

        if (provider instanceof InMemoryMetadataCacheProvider inMemory) {
            for (ClassRecord row : index.rows()) {
                // One entry per path, keyed by both the hash and the path: a rescan replaces the file's entry
                // rather than leaving the superseded content's behind under its old hash.
                inMemory.putForPath(entryFor(row));
            }
        }
        return filesRead;
    }

    /** One row as a provider entry: its identity, its type, and the file-scoped facts a caller reads. */
    private static MetadataProvider.CacheEntry entryFor(ClassRecord row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("checksum", row.checksum());
        metadata.put("path", row.path());
        metadata.put("kind", row.kind());
        metadata.put("simpleName", simpleNameOf(row.fqn()));
        metadata.put("classes", List.of(row.fqn()));
        return new MetadataProvider.CacheEntry(row.checksum(), row.fqn(), row.path(), metadata);
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** Every {@code .java} file under the project root, sorted so two scans see the same order. */
    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains(JcodebuddyDirectory.DIR))
                    .sorted()
                    .toList();
        }
    }
}
