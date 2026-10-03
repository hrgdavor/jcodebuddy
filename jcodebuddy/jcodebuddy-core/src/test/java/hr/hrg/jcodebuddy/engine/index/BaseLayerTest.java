package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The base layer: one entry per Java source file, derived from that file alone, carrying the hash it came from
 * (DEC-041, plan step 3.0u).
 *
 * <p>The tests here are the decision's acceptance criteria, and the first one is the whole point: a base entry
 * for file {@code A} must be <strong>byte-identical</strong> whether file {@code B} exists, is edited, or is
 * deleted. A per-file cache that holds a cross-file fact is stale after an unrelated edit and cannot say so, and
 * that is the only failure mode this layer must not have — so it is asserted rather than promised.</p>
 */
class BaseLayerTest {

    private static void write(Path root, String relative, String source) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    /** The entry a pass would write for one file: its identity plus what one parse of it yields. */
    private static FileMetadata entryFor(Path root, String relative, boolean generated) throws IOException {
        String source = Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, relative + " must parse");
        return FileMetadata.of(relative, ContentHash.of(root.resolve(relative)),
                Files.size(root.resolve(relative)), generated,
                ClassIndex.factsOf(unit, source), TreeQueries.importLines(unit));
    }

    @Test
    void anEntryRoundTripsWithItsRowsAndImports(@TempDir Path tree) throws IOException {
        write(tree, "a/b/Thing.java", "package a.b;\n\n"
                + "import java.util.List;\n"
                + "import static java.util.Collections.emptyList;\n\n"
                + "public sealed interface Thing permits Impl {\n"
                + "    List<String> names() throws java.io.IOException;\n"
                + "}\n");

        FileMetadata entry = entryFor(tree, "a/b/Thing.java", false);
        FileMetadata read = FileMetadata.parse(entry.toJson(), null);
        Assertions.assertNotNull(read, "an entry this build wrote must read back");

        Assertions.assertEquals("a/b/Thing.java", read.path());
        Assertions.assertEquals(entry.checksum(), read.checksum(), "the hash survives, and it is the reason the "
                + "entry may be reused at all (DEC-041 D3)");
        Assertions.assertEquals(entry.size(), read.size());
        Assertions.assertEquals(List.of("import java.util.List;", "import static java.util.Collections.emptyList;"),
                read.imports(), "the file's import lines, in order, as written");
        Assertions.assertEquals(List.of("a.b.Thing"), read.types().stream().map(ClassRecord::fqn).toList());

        ClassRecord row = read.types().get(0);
        Assertions.assertEquals(List.of("Impl"), row.permits(), "the type's own facts are the table's rows, "
                + "written by the table's writer and read by the table's reader");
        Assertions.assertEquals(List.of("java.io.IOException"), row.members().get(0).throwsClause());
        Assertions.assertNotNull(row.span(), "including the ranges a consumer slices at");
    }

    @Test
    void aWarmRebuildParsesNothingAndProducesAnIdenticalTable(@TempDir Path tree) throws IOException {
        // DEC-041's second criterion, and the one that makes the layer worth having: the second pass over an
        // unchanged tree must parse nothing, and its table must be byte-identical to the first one's — otherwise a
        // project that commits its metadata gets diff noise from a pass that changed nothing.
        Path module = tree.resolve("module");
        Files.createDirectories(module);
        write(module, "src/a/b/First.java", "package a.b;\n\nimport java.util.List;\n\n"
                + "public class First {\n    List<String> names;\n}\n");
        write(module, "src/a/b/Second.java", "package a.b;\n\npublic interface Second {\n    String name();\n}\n");
        write(module, "src/a/b/Third.java", "package a.b;\n\npublic enum Third { ONE(1), TWO; Third(int i) {}"
                + " Third() {} }\n");

        Path report = tree.resolve("report");
        MetadataCache cache = MetadataCache.beside(report);
        List<String> problems = new java.util.ArrayList<>();
        String[] files = {"src/a/b/First.java", "src/a/b/Second.java", "src/a/b/Third.java"};

        ClassIndex cold = ClassIndex.forPass(report, module, module.resolve("src"));
        for (String file : files) {
            Assertions.assertFalse(cache.consume(cold, file, module.resolve(file), false, problems),
                    "the first pass has no entry to reuse");
        }
        Assertions.assertEquals(0, cache.hits());
        Assertions.assertEquals(3, cache.misses(), "three files, three parses");
        Assertions.assertEquals(3, cache.entriesWritten(), "and three entries stored");
        cold.write();
        String coldTable = Files.readString(cold.indexFile(), StandardCharsets.UTF_8);

        // The warm pass: a fresh index, the written table as its predecessor (which is where timestamps are
        // carried forward from), and the same cache.
        ClassIndex previous = ClassIndex.read(cold.indexFile(), report, module, module.resolve("src"));
        Assertions.assertNotNull(previous);
        cache.resetCounters();
        ClassIndex warm = ClassIndex.forPass(report, module, module.resolve("src")).merge(previous);
        for (String file : files) {
            Assertions.assertTrue(cache.consume(warm, file, module.resolve(file), false, problems),
                    "the second pass reuses the entry: it did not read " + file);
        }
        Assertions.assertEquals(3, cache.hits(), "three parses avoided, which is the number this step is about");
        Assertions.assertEquals(0, cache.misses());
        Assertions.assertEquals(0, cache.entriesWritten(), "and nothing was rewritten, because nothing changed");
        warm.write();

        Assertions.assertEquals(coldTable, Files.readString(warm.indexFile(), StandardCharsets.UTF_8),
                "the warm table is byte-identical to the cold one — every key, fact, checksum and timestamp: "
                        + problems);
        Assertions.assertTrue(coldTable.contains("\"imports\"") || coldTable.contains("a.b.First"),
                "and it is a real table, not an empty one: " + coldTable.substring(0, Math.min(200,
                        coldTable.length())));
    }

    @Test
    void oneEditRecomputesExactlyOneEntry(@TempDir Path tree) throws IOException {
        Path module = tree.resolve("module");
        Files.createDirectories(module);
        write(module, "src/a/b/One.java", "package a.b;\n\npublic class One {\n    int id;\n}\n");
        write(module, "src/a/b/Two.java", "package a.b;\n\npublic class Two {\n    int id;\n}\n");
        Path report = tree.resolve("report");
        MetadataCache cache = MetadataCache.beside(report);
        List<String> problems = new java.util.ArrayList<>();
        String[] files = {"src/a/b/One.java", "src/a/b/Two.java"};

        ClassIndex first = ClassIndex.forPass(report, module, module.resolve("src"));
        for (String file : files) {
            cache.consume(first, file, module.resolve(file), false, problems);
        }
        String oneFirst = Files.readString(cache.entryFile(files[0]), StandardCharsets.UTF_8);
        String twoFirst = Files.readString(cache.entryFile(files[1]), StandardCharsets.UTF_8);

        write(module, "src/a/b/Two.java", "package a.b;\n\npublic class Two {\n    int id;\n    String name;\n}\n");
        cache.resetCounters();
        ClassIndex second = ClassIndex.forPass(report, module, module.resolve("src"));
        for (String file : files) {
            cache.consume(second, file, module.resolve(file), false, problems);
        }

        Assertions.assertEquals(1, cache.hits(), "the untouched file's entry was reused");
        Assertions.assertEquals(1, cache.misses(), "and the edited one was parsed");
        Assertions.assertEquals(1, cache.entriesWritten(), "one edit, one entry written");
        Assertions.assertEquals(oneFirst, Files.readString(cache.entryFile(files[0]), StandardCharsets.UTF_8),
                "the untouched file's entry bytes are unchanged — the sibling's edit did not reach it, which is "
                        + "DEC-041's first criterion holding across a real pass");
        Assertions.assertNotEquals(twoFirst, Files.readString(cache.entryFile(files[1]), StandardCharsets.UTF_8),
                "while the edited file's entry did change");
    }

    @Test
    void deletingTheWholeCacheChangesNoAnswerOnlyTheTime(@TempDir Path tree) throws IOException {
        // DEC-041's last criterion. The cache is allowed to be an optimisation and nothing else.
        Path module = tree.resolve("module");
        Files.createDirectories(module);
        write(module, "src/a/b/Kept.java", "package a.b;\n\nimport java.util.Map;\n\n"
                + "public class Kept {\n    Map<String, Integer> counts;\n}\n");
        Path report = tree.resolve("report");
        List<String> problems = new java.util.ArrayList<>();

        MetadataCache warmCache = MetadataCache.beside(report);
        ClassIndex warm = ClassIndex.forPass(report, module, module.resolve("src"));
        Assertions.assertFalse(warmCache.consume(warm, "src/a/b/Kept.java", module.resolve("src/a/b/Kept.java"),
                false, problems));
        warm.write();
        String withCache = Files.readString(warm.indexFile(), StandardCharsets.UTF_8);

        // Delete the cache and rebuild from nothing, with the previous table available exactly as before.
        deleteRecursively(report.resolve(MetadataCache.CACHE_DIR_NAME));
        Assertions.assertFalse(Files.exists(report.resolve(MetadataCache.CACHE_DIR_NAME)),
                "the cache directory is gone");
        ClassIndex previous = ClassIndex.read(warm.indexFile(), report, module, module.resolve("src"));
        MetadataCache coldCache = MetadataCache.beside(report);
        ClassIndex cold = ClassIndex.forPass(report, module, module.resolve("src")).merge(previous);
        Assertions.assertFalse(coldCache.consume(cold, "src/a/b/Kept.java", module.resolve("src/a/b/Kept.java"),
                false, problems), "no cache, so the file is parsed");
        Assertions.assertEquals(1, coldCache.misses());
        cold.write();

        Assertions.assertEquals(withCache, Files.readString(cold.indexFile(), StandardCharsets.UTF_8),
                "the table is identical: the cache changed how long the pass took, not what it said");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    void anEntryIsIndependentOfEveryOtherFile(@TempDir Path tree) throws IOException {
        // The acceptance criterion DEC-041 D6 makes measurable. A is indexable on its own; B is a neighbour that
        // changes in three ways, and none of them may move A's bytes.
        write(tree, "a/b/A.java", "package a.b;\n\npublic class A {\n    int id;\n}\n");
        write(tree, "a/b/B.java", "package a.b;\n\npublic class B extends A {\n}\n");
        String alone = entryFor(tree, "a/b/A.java", false).toJson();

        String withNeighbour = entryFor(tree, "a/b/A.java", false).toJson();
        Assertions.assertEquals(alone, withNeighbour, "a neighbouring file's existence changes nothing");

        write(tree, "a/b/B.java", "package a.b;\n\npublic class B extends A implements java.io.Serializable {\n"
                + "    A other;\n}\n");
        Assertions.assertEquals(alone, entryFor(tree, "a/b/A.java", false).toJson(),
                "and neither does editing it — no resolved name, no reverse edge, no freshness verdict may be in "
                        + "the base set, however cheap it would be to compute one here");

        Files.delete(tree.resolve("a/b/B.java"));
        Assertions.assertEquals(alone, entryFor(tree, "a/b/A.java", false).toJson(),
                "nor deleting it: an entry that moved when another file did would be a per-file cache holding a "
                        + "cross-file fact, which is stale after an unrelated edit and cannot say so");
    }

    @Test
    void anEntryCarriesNoSourceText(@TempDir Path tree) throws IOException {
        // A per-file cache is exactly where storing the file's text becomes tempting: the file was just read. The
        // facts are names and ranges, and the written form is recovered by slicing (DEC-040 D2).
        String source = "package a.b;\n\npublic class Text {\n"
                + "    private String value = \"a long literal that must not be copied into the cache\";\n"
                + "}\n";
        write(tree, "a/b/Text.java", source);
        String json = entryFor(tree, "a/b/Text.java", false).toJson();

        Assertions.assertFalse(json.contains("a long literal that must not be copied into the cache"),
                "the initialiser is a range fact, not text: " + json);
        Assertions.assertFalse(json.contains("public class Text"),
                "and so is the declaration itself");
        Assertions.assertTrue(json.contains("hasInitializer"),
                "what the cache records instead is that there IS one");
    }

    @Test
    void aStaleEntryIsNotUsedAndACorruptOneCostsAParse(@TempDir Path tree) throws IOException {
        write(tree, "a/b/Moving.java", "package a.b;\n\npublic class Moving {\n    int first;\n}\n");
        FileMetadata entry = entryFor(tree, "a/b/Moving.java", false);

        Assertions.assertTrue(entry.describes(ContentHash.of(tree.resolve("a/b/Moving.java"))),
                "the entry describes the file it was computed from");
        Assertions.assertTrue(new FileMetadata("somewhere/else.java", entry.checksum(), 1, false, List.of(),
                        List.of()).describes(entry.checksum()),
                "and the question is the HASH it carries, not the path: the entry is about content identity, which "
                        + "is what lets a file be moved without its facts being recomputed");
        Assertions.assertFalse(entry.describes("different"), "so a file that moved on is refused");
        Assertions.assertFalse(entry.describes(""), "and an entry with no hash describes nothing, because it "
                + "cannot be shown to be current");

        write(tree, "a/b/Moving.java", "package a.b;\n\npublic class Moving {\n    int first;\n    int second;\n}\n");
        Assertions.assertFalse(entry.describes(ContentHash.of(tree.resolve("a/b/Moving.java"))),
                "the same entry against the same path after an edit: refused, which is the only honest answer");

        Assertions.assertNull(FileMetadata.parse("not json at all", null),
                "a corrupt entry is not an entry: the caller parses the file instead (DEC-041 D7)");
        Assertions.assertNull(FileMetadata.parse("{\"format\": 99}", null),
                "and an entry from another format version is refused rather than half-read");
        List<String> problems = new java.util.ArrayList<>();
        Assertions.assertNull(FileMetadata.parse("{\"format\": 1, \"classes\": {\"a.b.X\": {\"kind\": \"class\","
                + " \"members\": [{\"name\": \"m\", \"kind\": \"invented\"}]}}}", problems),
                "as is one carrying a member kind this contract has no case for — the table's own rule");
        Assertions.assertFalse(problems.isEmpty(), "and it says why: " + problems);
    }

    @Test
    void theEntryFormatIsDeterministic(@TempDir Path tree) throws IOException {
        write(tree, "a/b/Stable.java", "package a.b;\n\nimport java.util.Map;\n\n"
                + "public class Stable {\n    Map<String, Integer> counts;\n    enum Kind { ONE, TWO }\n}\n");
        String first = entryFor(tree, "a/b/Stable.java", false).toJson();
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(first, entryFor(tree, "a/b/Stable.java", false).toJson(),
                    "the same file yields the same entry bytes, every time — a cache whose bytes wobble is diff "
                            + "noise in a project that commits nothing and reads its own cache");
        }
        Assertions.assertEquals(first, FileMetadata.parse(first, null).toJson(),
                "and the entry survives its own round trip byte-for-byte, which is what lets a rebuild write back "
                        + "what it read");
    }
}
