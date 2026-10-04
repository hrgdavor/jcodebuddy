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
    @DisplayName("carries the three sides beside the resolved code, so a reviewer can compare them (4.2)")
    void carriesTheThreeSides() throws IOException {
        Path target = MergeReportWriter.defaultTarget(tempDir);

        MergeReportWriter.write(target, batch());

        String json = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"sides\""), json);
        // The two branches' additions are what a reviewer compares, and they exist nowhere else in the
        // report: without them the page can only show the answer, never the question.
        assertTrue(json.contains("import java.math.BigDecimal;"), "branch 1's side: " + json);
        assertTrue(json.contains("import java.time.Instant;"), "branch 2's side: " + json);
        assertTrue(json.contains("\"base\""), json);
        assertTrue(json.contains("\"branch1\""), json);
        assertTrue(json.contains("\"branch2\""), json);
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

    /**
     * The review display is a jsx6 page (rule § 2 and DEC-027's 2026-10-01 amendment: a per-item
     * review workflow is an interactive page, never a vanilla one), and these two tests are its
     * contract: it produces ONE self-contained file, and it refuses a report it cannot read.
     *
     * <p>They drive the page's own build rather than a renderer script beside it, because the vanilla
     * `scripts/merge-report/render.js` this used to test is gone — the page replaced it. The build is
     * skipped, not failed, when the machine has not set the page up (`bun install` in `review/`, and a
     * jsx6 checkout): a missing local toolchain is not a defect in this module.</p>
     */
    @Test
    @DisplayName("the jsx6 review page builds one self-contained HTML file")
    void rendersSelfContainedHtml() throws Exception {
        assumeTrue(bunAvailable(), "bun is not installed; the review page is not exercised");
        Path review = Path.of("review").toAbsolutePath();
        assumeTrue(Files.isDirectory(review.resolve("node_modules").resolve("esbuild")),
            "the review package has no local esbuild; run `bun install` in " + review);
        assumeTrue(Files.isDirectory(Path.of("..", ".jsx6", "libs").toAbsolutePath().normalize()),
            "no jsx6 checkout at ../.jsx6; see root AGENTS.md § 2 (JCODEBUDDY_JSX6_DIR overrides)");

        Path jsonPath = tempDir.resolve("report.json");
        // The page is built into `target/`, NOT into the @TempDir: measured in this environment, esbuild
        // cannot write its output under %TEMP% at all ("Failed to write to output file … Access is denied",
        // with the directory created successfully first), while the module's own build directory works and
        // is gitignored and cleaned by `mvn clean`. The report itself stays in the @TempDir on purpose, so
        // the test also proves the build reads a report from wherever it is given one.
        Path outDir = Path.of("target", "review-page-test").toAbsolutePath();
        Files.createDirectories(outDir);
        MergeReportWriter.write(jsonPath, batch());

        Process process = new ProcessBuilder("bun", "run", "src_build/build.js",
                "--report", jsonPath.toString(), "--out", outDir.toString())
            .directory(review.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "the page build failed: " + output);

        String html = Files.readString(outDir.resolve("index.html"), StandardCharsets.UTF_8);

        // Self-contained means nothing is FETCHED. The check is therefore on attributes rather than on
        // the words: the jsx6 runtime legitimately contains the SVG namespace
        // `http://www.w3.org/2000/svg` to call createElementNS, which a blanket "no http://" assertion
        // would fail on while proving nothing about the page's self-containment.
        assertFalse(html.contains("<script src="), "no external script may be referenced");
        assertFalse(html.contains("<link "), "no external stylesheet may be referenced");
        assertFalse(html.matches("(?s).*\\s(src|href)\\s*=\\s*\"https?://.*"),
            "nothing may be fetched over the network");
        assertFalse(html.contains("sourceMappingURL"),
            "the inlined page must not point at the bundle's map file");

        // It presents the facts the reviewer needs.
        assertTrue(html.contains("Payment.java"), "the conflicting file must be named");
        assertTrue(html.contains("IMPORT_ADD"), "the conflict type must be shown");
        assertTrue(html.contains("AUTO"), "the outcome must be shown");
        assertTrue(html.contains("recommended"), "the recommendation must be shown");
        // And the three sides step 4.2 exists for, not just the answer.
        assertTrue(html.contains("branch 1"), "the branches' own code must be shown beside the result");
    }

    @Test
    @DisplayName("the page build refuses a report it cannot read, with a usage status")
    void buildRefusesAReportItCannotRead() throws Exception {
        assumeTrue(bunAvailable(), "bun is not installed");
        Path review = Path.of("review").toAbsolutePath();
        assumeTrue(Files.isDirectory(review.resolve("node_modules").resolve("esbuild")),
            "the review package has no local esbuild; run `bun install` in " + review);

        // No jsx6 checkout is needed for this path: a report that cannot be read fails before the build
        // reaches esbuild, and it must never fall back to the sample — that would render different data
        // than the caller asked for, which is the one failure a review display must not have.
        Process process = new ProcessBuilder("bun", "run", "src_build/build.js",
                "--report", tempDir.resolve("absent.json").toString())
            .directory(review.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(2, process.waitFor(), "misuse must fail with a usage status: " + output);
        assertTrue(output.contains("usage"), output);
    }
}
