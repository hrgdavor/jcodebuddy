package hr.hrg.jcodebuddy.automation.runner;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The dev-time scan reads the engine's model and reuses the engine's per-file cache (plan step 3.0j).
 *
 * <p>Three claims, and each one is a thing this class used to get wrong: the class names a caller asks the server
 * for are the ones the scan saw (it answered with two invented names), a second scan over an unchanged tree reads
 * nothing (it re-read every file and hashed it with SHA-1), and "has this file changed" compares the identity the
 * engine computed (it looked for a key the parser never wrote, so every file always looked changed).</p>
 */
class MetadataAnalysisTest {

    private static void write(Path root, String relative, String source) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    @Test
    void theScanPublishesWhatItReadAndTheSecondScanReadsNothing(@TempDir Path dir) throws IOException {
        write(dir, "src/a/b/First.java", "package a.b;\n\npublic class First {\n    int id;\n}\n");
        write(dir, "src/a/b/Second.java", "package a.b;\n\npublic interface Second {\n    String name();\n}\n");
        InMemoryMetadataCacheProvider provider = new InMemoryMetadataCacheProvider();
        MetadataAnalysis analysis = new MetadataAnalysis(dir, provider);

        Assertions.assertEquals(2, analysis.scan(), "the first scan reads both files");

        Assertions.assertEquals(List.of("a.b.First", "a.b.Second"), provider.listClasses(),
                "the classes are the ones the engine read, not a stub's invented names");
        Assertions.assertFalse(provider.listEntries().isEmpty(), "and the entries a caller reads exist");
        Assertions.assertTrue(provider.listEntries().stream().allMatch(entry -> entry.hash().length() == 16),
                "each entry carries the engine's content identity (DEC-029 § 4): "
                        + provider.listEntries().stream().map(entry -> entry.hash()).toList());

        // The cache's own answer, and the reason the per-file layer exists (DEC-041).
        Assertions.assertEquals(0, analysis.scan(),
                "the second scan over an unchanged tree reads NOTHING: the per-file cache answered for both files");

        // And a real edit is seen, which is the whole point of a scan that skips what did not change.
        write(dir, "src/a/b/First.java", "package a.b;\n\npublic class First {\n    int id;\n    String name;\n}\n");
        Assertions.assertEquals(1, analysis.scan(), "one edit, one file read");

        // The provider is keyed by content hash and by path, so the checksum to compare is the engine's own for
        // that file — which is also what makes this an assertion about the identity being the engine's.
        String checksum = hr.hrg.jcodebuddy.engine.index.ContentHash.of(dir.resolve("src/a/b/First.java"));
        Assertions.assertEquals(checksum, provider.get(checksum).hash(),
                "the entry is reachable by its content hash, which is what a cache lookup asks for");
        Assertions.assertEquals(2, provider.listEntries().size(),
                "and one entry per declared type, not one per content revision: a rescan replaces a file's entry "
                        + "rather than leaving the superseded content's behind");
        Assertions.assertFalse(provider.hasChanged("src/a/b/First.java", checksum),
                "the file whose checksum was just recorded has not changed");
        Assertions.assertTrue(provider.hasChanged("src/a/b/First.java", "0000000000000000"),
                "and a different checksum means it has — which the provider used to answer 'changed' to every "
                        + "time, because it looked for a key the parser never wrote");
        Assertions.assertTrue(provider.hasChanged("src/a/b/Gone.java", checksum),
                "a file the provider has no entry for counts as changed");
    }
}
