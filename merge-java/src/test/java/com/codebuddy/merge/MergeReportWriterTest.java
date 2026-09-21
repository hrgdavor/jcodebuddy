// {@link com.codebuddy.merge.MergeReportWriterTest} Tests for the JSON metadata and the Bun-rendered report.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The report follows this repository's split: Java writes the facts, a Bun script
 * renders one self-contained HTML file. These tests hold both halves to their
 * contract, and skip the renderer half when Bun is not installed rather than
 * failing on a machine that cannot run it.
 */
class MergeReportWriterTest {

    @TempDir
    Path tempDir;

    private static final String BASE =
        "import java.util.List;\n"
            + "import java.util.Map;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "        audit();\n"
            + "    }\n"
            + "    void b() {\n"
            + "    }\n"
            + "}\n";

    private static final String BRANCH1 =
        "import java.util.List;\n"
            + "import java.util.Map;\n"
            + "import java.math.BigDecimal;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "        audit();\n"
            + "    }\n"
            + "    void b() {\n"
            + "    }\n"
            + "}\n";

    private static final String BRANCH2 =
        "import java.util.List;\n"
            + "import java.util.Map;\n"
            + "import java.time.Instant;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "        audit();\n"
            + "    }\n"
            + "}\n";

    private MergeBatch batch() {
        MergeConflictResolver resolver = new MergeConflictResolver.Builder()
            .setBranchName("feature")
            .setHistoryPath(tempDir.resolve("history").resolve("feature"))
            .setTypeContext(TestTypeContexts.jdk())
            .build();
        return MergeBatch.using(resolver)
            .add("Payment.java", BASE, BRANCH1, BRANCH2);
    }

    // ------------------------------------------------------------ JSON writer

    @Test
    @DisplayName("writes well-formed metadata containing the summary and every file")
    void writesMetadata() throws IOException {
        Path target = MergeReportWriter.defaultTarget(tempDir);

        MergeReportWriter.write(target, batch());

        assertTrue(Files.isRegularFile(target), "the report must be written");
        String json = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"), json);
        assertTrue(json.contains("\"summary\""), json);
        assertTrue(json.contains("\"Payment.java\""), json);
        assertTrue(json.contains("\"IMPORT_ADD\""), json);
        assertTrue(json.contains("\"fixPaths\""), json);
    }

    @Test
    @DisplayName("escapes quotes, newlines and tabs so the JSON stays parseable")
    void escapesStrings() {
        assertEquals("\"a\\\"b\"", MergeReportWriter.quote("a\"b"));
        assertEquals("\"a\\nb\"", MergeReportWriter.quote("a\nb"));
        assertEquals("\"a\\tb\"", MergeReportWriter.quote("a\tb"));
        assertEquals("\"a\\\\b\"", MergeReportWriter.quote("a\\b"));
        assertEquals("null", MergeReportWriter.quote(null));
        assertEquals("\"\\u0007\"", MergeReportWriter.quote("\u0007"));
    }

    @Test
    @DisplayName("records each resolution's kind, region and applicability")
    void recordsResolutionDetail() {
        String json = MergeReportWriter.toJson(batch().getReports(), batch().summarize());

        assertTrue(json.contains("\"kind\": \"AUTO\""), json);
        assertTrue(json.contains("\"verification\": "), json);
        assertTrue(json.contains("\"independentlyApplicable\": true"),
            "the import merge is disjoint from the structural conflict: " + json);
        assertTrue(json.contains("\"startLine\""), "a known region must be reported: " + json);
    }

    @Test
    @DisplayName("reports an unknown region as JSON null rather than a fake line")
    void reportsUnknownRegionAsNull() {
        ConflictResolution unanchored = ConflictResolution.builder()
            .filePath("A.java")
            .type(ConflictType.STRUCTURAL_CHANGE)
            .resolvedCode(ConflictResolution.MANUAL_MARKER)
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.MANUAL)
            .kind(ConflictResolution.ResolutionKind.MANUAL)
            .build();
        MergeConflictResolver.MergeReport report = new MergeConflictResolver.MergeReport(
            "A.java", List.of(), List.of(unanchored), "feature");

        String json = MergeReportWriter.toJson(List.of(report),
            MergeBatch.using(MergeConflictResolver.create(TestTypeContexts.jdk())).summarize());

        assertTrue(json.contains("\"region\": null"), json);
    }

    // -------------------------------------------------------------- renderer

    private static boolean bunAvailable() {
        try {
            Process process = new ProcessBuilder("bun", "--version")
                .redirectErrorStream(true)
                .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    @DisplayName("the Bun renderer produces one self-contained HTML file")
    void rendersSelfContainedHtml() throws Exception {
        assumeTrue(bunAvailable(), "bun is not installed; renderer contract not exercised");

        Path jsonPath = tempDir.resolve("report.json");
        Path htmlPath = tempDir.resolve("report.html");
        MergeReportWriter.write(jsonPath, batch());

        Path script = Path.of("scripts", "merge-report", "render.js").toAbsolutePath();
        assumeTrue(Files.isRegularFile(script), "renderer script not found at " + script);

        Process process = new ProcessBuilder("bun", "run", script.toString(),
                jsonPath.toString(), htmlPath.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "renderer failed: " + output);

        String html = Files.readString(htmlPath, StandardCharsets.UTF_8);

        // Self-contained: no external resources at all.
        assertFalse(html.contains("<script src="), "no external script may be referenced");
        assertFalse(html.contains("<link "), "no external stylesheet may be referenced");
        assertFalse(html.contains("http://"), "no network reference may appear");
        assertFalse(html.contains("https://"), "no network reference may appear");

        // It presents the facts the reviewer needs.
        assertTrue(html.contains("payment.java") || html.contains("Payment.java"),
            "the conflicting file must be named");
        assertTrue(html.contains("IMPORT_ADD"), "the conflict type must be shown");
        assertTrue(html.contains("AUTO"), "the outcome must be shown");
        assertTrue(html.contains("recommended"), "the recommendation must be shown");
    }

    @Test
    @DisplayName("the renderer refuses to run without both arguments")
    void rendererRequiresArguments() throws Exception {
        assumeTrue(bunAvailable(), "bun is not installed");

        Path script = Path.of("scripts", "merge-report", "render.js").toAbsolutePath();
        assumeTrue(Files.isRegularFile(script), "renderer script not found");

        Process process = new ProcessBuilder("bun", "run", script.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(2, process.waitFor(), "misuse must fail with a usage status: " + output);
        assertTrue(output.contains("usage"), output);
    }
}
