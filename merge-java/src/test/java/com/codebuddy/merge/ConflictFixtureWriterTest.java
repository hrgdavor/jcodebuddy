// {@link com.codebuddy.merge.ConflictFixtureWriterTest} Tests the private fixture workspace layout, diffs and manifests.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The writer produces the private workspace a fixture agent works in, so the
 * tests assert two things above all: the layout is complete enough to build a
 * resolver from, and the privacy guard rails (the {@code .gitignore}, the
 * copied instructions) are always present. All content is synthetic.
 */
class ConflictFixtureWriterTest {

    @TempDir
    Path tempDir;

    private static final String OURS = "package com.example.demo;\n\npublic class OrderService {\n}\n";
    private static final String THEIRS =
        "package com.example.demo;\n\npublic class OrderService {\n    int surplus = 2;\n}\n";
    private static final String BASE = "package com.example.demo;\n\npublic class OrderService {\n}\n";

    private ConflictFixtureWriter.RunManifest manifest(String baseText, Instant generatedAt) {
        return new ConflictFixtureWriter.RunManifest(
            tempDir.resolve("OrderService.java"), "src/main/java/com/example/demo/OrderService.java",
            "feature-x", baseText == null ? "none" : "diff3", false, null,
            true, false, 1, 0, 1,
            "<<<<<<< ours\n" + OURS + "=======\n" + THEIRS + ">>>>>>> theirs\n",
            OURS, THEIRS, baseText, generatedAt);
    }

    private ConflictFixtureWriter.FixtureCase fixtureCase(String description) {
        ConflictResolution resolution = ConflictResolution.builder()
            .type(ConflictType.STRUCTURAL_CHANGE)
            .filePath("src/main/java/com/example/demo/OrderService.java")
            .resolvedCode("")
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.MANUAL)
            .kind(ConflictResolution.ResolutionKind.MANUAL)
            .explanation("Both branches rewrote the same member.")
            .build();
        return new ConflictFixtureWriter.FixtureCase(
            "case-1-structural-change-0123456789abcdef",
            "src/main/java/com/example/demo/OrderService.java",
            ConflictType.STRUCTURAL_CHANGE, description, 1, 9, "LEFT_MANUAL",
            "structural-change:0123456789abcdef", List.of(resolution),
            "<<<<<<< ours\n...\n>>>>>>> theirs", null,
            "public class OrderService {\n}", "public class OrderService {\n    int surplus = 2;\n}");
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ layout

    @Test
    @DisplayName("a run without a base writes the full workspace and omits base artefacts")
    void writesTheWorkspaceWithoutABase() throws IOException {
        Path runDir = ConflictFixtureWriter.writeRun(tempDir.resolve("root"),
            manifest(null, Instant.parse("2026-02-14T10:15:30Z")),
            List.of(fixtureCase("Both branches rewrote the same member.")));

        assertTrue(runDir.getFileName().toString().matches("merge-java-\\d{8}-\\d{6}-[0-9a-f]{8}"),
            runDir.getFileName().toString());
        assertEquals("*\n", read(runDir.resolve(".gitignore")));
        assertEquals(FixtureAgentInstructions.content(),
            read(runDir.resolve(FixtureAgentInstructions.FILE_NAME)));

        assertEquals(OURS, read(runDir.resolve("source/ours.java.txt")));
        assertEquals(THEIRS, read(runDir.resolve("source/theirs.java.txt")));
        assertFalse(Files.exists(runDir.resolve("source/base.java.txt")));
        assertFalse(Files.exists(runDir.resolve("source/ours.diff")),
            "diffs are base-relative, so without a base there are none");

        Path caseDir = runDir.resolve("cases/case-1-structural-change-0123456789abcdef");
        assertTrue(Files.isDirectory(caseDir));
        assertEquals(OURS, read(caseDir.resolve("whole/ours/OrderService.java.txt")));
        assertEquals(THEIRS, read(caseDir.resolve("whole/theirs/OrderService.java.txt")));
        assertFalse(Files.exists(caseDir.resolve("whole/base")));
        assertFalse(Files.exists(caseDir.resolve("block/base.java.txt")));

        String runJson = read(runDir.resolve("run.json"));
        assertTrue(runJson.contains("\"schemaVersion\": 1"));
        assertTrue(runJson.contains("\"baseSource\": \"none\""));
        assertTrue(runJson.contains("\"cases\": 1"));
        assertTrue(runJson.contains("\"left\": 1"));

        String conflictJson = read(caseDir.resolve("conflict.json"));
        assertTrue(conflictJson.contains("\"conflictType\": \"STRUCTURAL_CHANGE\""));
        assertTrue(conflictJson.contains("\"handling\": \"MANUAL\""));
        assertTrue(conflictJson.contains("\"signature\": \"structural-change:0123456789abcdef\""));
        assertTrue(conflictJson.contains("\"kind\": \"MANUAL\""));
        assertTrue(conflictJson.contains("anonymized"));
    }

    @Test
    @DisplayName("a run with a base adds the base side and its diffs")
    void writesTheBaseSideAndDiffs() throws IOException {
        Path runDir = ConflictFixtureWriter.writeRun(tempDir.resolve("root"),
            manifest(BASE, Instant.parse("2026-02-14T10:15:31Z")),
            List.of(fixtureCase("Both branches rewrote the same member.")));

        assertEquals(BASE, read(runDir.resolve("source/base.java.txt")));
        assertTrue(Files.exists(runDir.resolve("source/theirs.diff")),
            "theirs differs from base, so its diff exists");
        assertFalse(Files.exists(runDir.resolve("source/ours.diff")),
            "ours equals base here, so there is nothing to document");

        Path caseDir = runDir.resolve("cases/case-1-structural-change-0123456789abcdef");
        assertEquals(BASE, read(caseDir.resolve("whole/base/OrderService.java.txt")));
        assertTrue(read(caseDir.resolve("whole/theirs.diff")).contains("+    int surplus = 2;"));

        assertTrue(read(runDir.resolve("run.json")).contains("\"baseSource\": \"diff3\""));
        assertTrue(read(caseDir.resolve("conflict.json")).contains("\"runBaseSource\": \"diff3\""));
    }

    @Test
    @DisplayName("manifest strings are escaped, so the JSON stays one field per line")
    void escapesManifestStrings() throws IOException {
        Path runDir = ConflictFixtureWriter.writeRun(tempDir.resolve("root"),
            manifest(null, Instant.parse("2026-02-14T10:15:32Z")),
            List.of(fixtureCase("Says \"keep both\"\nand more.")));

        String conflictJson = read(runDir.resolve(
            "cases/case-1-structural-change-0123456789abcdef/conflict.json"));
        assertTrue(conflictJson.contains("\\\"keep both\\\""), conflictJson);
        assertTrue(conflictJson.contains("and more.\""), "the newline is escaped, not literal");
        assertFalse(conflictJson.contains("Says \"keep both\"\nand more."),
            "raw newlines inside a string would corrupt the manifest");
    }

    @Test
    @DisplayName("repeated runs on the same file accumulate side by side")
    void runDirectoriesNeverCollide() {
        Instant first = Instant.parse("2026-02-14T10:15:33Z");
        String one = ConflictFixtureWriter.runDirName(manifest(null, first));
        String two = ConflictFixtureWriter.runDirName(
            manifest(null, first.plusNanos(1_000_000)));
        assertNotEquals(one, two);
        assertTrue(one.startsWith("merge-java-"));
    }

    // --------------------------------------------------------------- file names

    @Test
    @DisplayName("whole-file names keep only the last segment and gain the .txt suffix")
    void simpleFileNameTakesTheLastSegment() {
        assertEquals("OrderService.java.txt", ConflictFixtureWriter.simpleFileName(
            "src/main/java/com/example/demo/OrderService.java"));
        assertEquals("OrderService.java.txt", ConflictFixtureWriter.simpleFileName(
            "src\\main\\java\\com\\example\\demo\\OrderService.java"));
        assertEquals("OrderService.java.txt",
            ConflictFixtureWriter.simpleFileName("OrderService.java"));
        assertEquals("Conflicted.java.txt", ConflictFixtureWriter.simpleFileName(null));
    }

    // -------------------------------------------------------------------- diffs

    @Test
    @DisplayName("the unified diff shows changed lines with their context")
    void unifiedDiffRendersChanges() {
        Optional<String> diff = ConflictFixtureWriter.unifiedDiff(
            "alpha\nbeta\ngamma\ndelta\n",
            "alpha\nbeta changed\ngamma\ndelta\n",
            "a/Demo.java", "b/Demo.java");

        assertTrue(diff.isPresent());
        String text = diff.get();
        assertTrue(text.startsWith("--- a/Demo.java"));
        assertTrue(text.contains("+++ b/Demo.java"));
        assertTrue(text.contains("-beta"));
        assertTrue(text.contains("+beta changed"));
        assertTrue(text.contains(" alpha"), "context lines are kept");
        assertTrue(text.contains("@@ -1,4 +1,4 @@"), text);
    }

    @Test
    @DisplayName("equal texts and empty insertions get honest diff answers")
    void unifiedDiffEdgeCases() {
        assertEquals(Optional.empty(), ConflictFixtureWriter.unifiedDiff(
            "alpha\n", "alpha\n", "a", "b"));

        Optional<String> inserted = ConflictFixtureWriter.unifiedDiff(
            "", "one\ntwo\n", "a/Demo.java", "b/Demo.java");
        assertTrue(inserted.isPresent());
        assertTrue(inserted.get().contains("@@ -0,0 +1,2 @@"), inserted.get());
        assertTrue(inserted.get().contains("+one"));
    }
}
