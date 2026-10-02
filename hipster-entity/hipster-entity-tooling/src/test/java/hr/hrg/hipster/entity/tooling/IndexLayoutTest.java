package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The layout of a module's {@code .jcodebuddy/index/}: two files, and the README is the human's.
 *
 * <p>A new {@code .jcodebuddy/} subfolder is a DEC-026 layout change rather than just code, and DEC-026
 * requires a README in every subfolder. So this test asserts three things a paragraph would not keep
 * true:</p>
 *
 * <ol>
 *   <li>the directory holds {@code classes.json} and {@code README.md} and <strong>nothing else</strong> —
 *       which is also the assertion that the reserved tables (the dependency edges, the
 *       {@code sourceMappingURL}-style artifact pointer) were <em>not</em> built in this change, and that
 *       the DEC-028 {@code files.json} is gone rather than left beside the new table;</li>
 *   <li>the README states what the table is and what is reserved, so a reader who opens the directory
 *       finds the contract rather than a bare JSON file;</li>
 *   <li>the pass <strong>never overwrites</strong> an existing README. It is tracked and human-owned, and
 *       rewriting it on every pass would silently revert a human's edit — the exact failure DEC-020
 *       forbids for generated blocks.</li>
 * </ol>
 *
 * <p>The module is synthetic and in a temp directory, so the test states the layout contract instead of
 * depending on the committed example having been generated recently.</p>
 */
class IndexLayoutTest {

    /** A minimal but real entity package: a marker and one view derived from it. */
    private static Path writeModule(Path tree) throws Exception {
        Path sourceRoot = tree.resolve("src/main/java");
        Path pkg = sourceRoot.resolve("layout/entity");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("ThingEntity.java"),
                "package layout.entity;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface ThingEntity extends EntityBase<Long> {}\n");
        Files.writeString(pkg.resolve("ThingSummary.java"),
                "package layout.entity;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface ThingSummary extends ThingEntity {\n"
                        + "  String name();\n"
                        + "}\n");
        Path reportDir = tree.resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(reportDir);
        return reportDir;
    }

    private static void runPass(Path tree, Path reportDir) throws Exception {
        EntityMetadataGenerator.generate(tree.resolve("src/main/java"), reportDir,
                tree.resolve("src/main/java"));
    }

    private static TreeSet<String> entriesOf(Path directory) throws Exception {
        try (Stream<Path> entries = Files.list(directory)) {
            TreeSet<String> names = new TreeSet<>();
            entries.forEach(entry -> names.add(entry.getFileName().toString()));
            return names;
        }
    }

    /** The directory is the index: one addressing table, and the README DEC-026 requires. */
    @Test
    void theIndexDirectoryHoldsTheTableAndTheReadmeAndNothingElse() throws Exception {
        Path tree = Files.createTempDirectory("index-layout");
        Path reportDir = writeModule(tree);
        runPass(tree, reportDir);

        Path indexDir = tree.resolve(".jcodebuddy/index");
        Assertions.assertTrue(Files.isDirectory(indexDir), "the pass writes the index beside metadata/: "
                + indexDir);
        Assertions.assertEquals(new TreeSet<>(List.of("classes.json", "mtimes.json", "README.md")),
                entriesOf(indexDir),
                "the index directory holds the class index, the working-tree mtime sidecar and its README; "
                        + "the DEC-028 files.json is gone (one table states a path once), and the reserved "
                        + "work (the dependency edges, the artifact pointer) is deliberately NOT built in "
                        + "this change");

        String readme = Files.readString(indexDir.resolve("README.md"));
        Assertions.assertTrue(readme.contains("classes.json"),
                "the README must say what the table is, or a reader who opens the directory has to guess");
        Assertions.assertTrue(readme.contains("hash") && readme.contains("checksum"),
                "and state the content-identity contract the checksums depend on");
        Assertions.assertTrue(readme.contains("fully qualified"),
                "and name the key space, so a reader knows what a document's `file` value is");
        Assertions.assertTrue(readme.contains("module-relative"),
                "and state the currency of the paths it holds");
        Assertions.assertTrue(readme.contains("never\noverwrites") || readme.contains("never overwrites")
                        || readme.contains("tracked and human-owned"),
                "and say that the pass does not own the README");
    }

    /** A pass after the first one leaves the README exactly as it found it. */
    @Test
    void anExistingReadmeIsNeverOverwritten() throws Exception {
        Path tree = Files.createTempDirectory("index-readme");
        Path reportDir = writeModule(tree);
        runPass(tree, reportDir);

        Path readme = tree.resolve(".jcodebuddy/index/README.md");
        String sentinel = "# edited by a human\n\n" + Files.readString(readme) + "\nSENTINEL\n";
        Files.writeString(readme, sentinel, StandardCharsets.UTF_8);

        runPass(tree, reportDir);

        Assertions.assertEquals(sentinel, Files.readString(readme),
                "the pass must not touch an existing README: rewriting a tracked, human-owned file on "
                        + "every pass silently reverts the human's edit (DEC-020)");
        // The table it DOES own is rewritten, and stays valid.
        Assertions.assertTrue(Files.readString(tree.resolve(".jcodebuddy/index/classes.json"))
                .startsWith("{"), "while the table the pass owns is rewritten as usual");
    }

    /**
     * The README is written only inside a module's {@code .jcodebuddy/}.
     *
     * <p>The fallback layout — a report directory that is not inside a {@code .jcodebuddy} at all, which
     * is what a test or a one-off pass uses — is not a DEC-026 subfolder, so it gets the table and no
     * README. That is also what keeps {@code GeneratorGuardTest}'s "a report directory holds JSON only"
     * true, and it is why the document's {@code classIndex} pointer is emitted rather than assumed: the
     * distance to the table differs between the two layouts.</p>
     */
    @Test
    void aFallbackIndexOutsideAModuleGetsTheTableAndNoReadme() throws Exception {
        Path tree = Files.createTempDirectory("index-fallback");
        writeModule(tree);
        Path reportDir = Files.createTempDirectory("index-fallback-report");
        runPass(tree, reportDir);

        Path indexDir = reportDir.resolve("index");
        Assertions.assertEquals(new TreeSet<>(List.of("classes.json", "mtimes.json")), entriesOf(indexDir),
                "a report directory that is not a module holds JSON metadata only");
    }
}
