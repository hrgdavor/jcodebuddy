// {@link com.codebuddy.merge.MergeFileToolTest} Tests the conflict-file tool: application rules, fixture preparation and the CLI.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.ConflictResolution.ResolutionKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import com.codebuddy.merge.ConflictResolution.ResolutionStrategy;
import com.codebuddy.merge.MergeFileTool.Outcome;
import com.codebuddy.merge.MergeFileTool.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every fixture in this class is synthetic - invented {@code com.example.demo}
 * code with no resemblance to any real codebase - because the tool exists to
 * keep proprietary conflicts out of this repository, and its own tests must
 * not become the leak.
 *
 * <p>The tests walk the decision table of the tool: what gets applied, what
 * stays marked, and that everything which stays becomes a prepared fixture
 * case with the layout {@code docs/CONFLICT_FILE_TOOL.md} prescribes.
 */
class MergeFileToolTest {

    @TempDir
    Path tempDir;

    private static final String IMPORT_CONFLICT_FILE = """
            package com.example.demo;

            import java.util.List;
            <<<<<<< ours
            import java.math.BigDecimal;
            =======
            import java.time.Instant;
            >>>>>>> theirs

            public class OrderService {

                public List<String> openOrders() {
                    return List.of();
                }
            }
            """;

    private static final String STRUCTURAL_CONFLICT_FILE = """
            package com.example.demo;

            public class OrderService {

                public void run() {
            <<<<<<< ours
                    total = computeTotal();
            =======
                    surplus = computeSurplus();
            >>>>>>> theirs
                }
            }
            """;

    private static final String METHOD_BODY_CONFLICT_FILE = """
            package com.example.demo;

            public class OrderService {

            <<<<<<< ours
                public String describe() {
                    return "our description";
                }
            =======
                public String describe() {
                    return "their description";
                }
            >>>>>>> theirs
            }
            """;

    private static final String MIXED_CONFLICT_FILE = """
            package com.example.demo;

            import java.util.List;
            <<<<<<< ours
            import java.math.BigDecimal;
            =======
            import java.time.Instant;
            >>>>>>> theirs

            public class OrderService {

            <<<<<<< ours
                public String describe() {
                    return "our description";
                }
            =======
                public String describe() {
                    return "their description";
                }
            >>>>>>> theirs
            }
            """;

    private static final String IDENTICAL_SIDES_FILE = """
            package com.example.demo;

            public class OrderService {

                public void run() {
            <<<<<<< ours
                    total = computeTotal();
            =======
                    total = computeTotal();
            >>>>>>> theirs
                }
            }
            """;

    private static final String MULTIPLE_AUTO_FILE = """
            package com.example.demo;

            <<<<<<< ours
            import java.math.BigDecimal;
            // counted in the audit log
            =======
            import java.time.Instant;
            // stamped with the event time
            >>>>>>> theirs

            public class OrderService {
            }
            """;

    private static final String PARTIAL_COVERAGE_FILE = """
            package com.example.demo;

            <<<<<<< ours
            import java.math.BigDecimal;

            public class OrderService {
            =======
            import java.math.BigDecimal;
            import java.time.Instant;

            public class OrderService {
            >>>>>>> theirs
            """;

    private static final String UNCLASSIFIED_FILE = """
            package com.example.demo;

            public class OrderService {

                public void run() {
            <<<<<<< ours
                    total = computeTotal();
            =======
                    total = computeTotal();
                    surplus = computeSurplus();
            >>>>>>> theirs
                }
            }
            """;

    // ------------------------------------------------------------------ helpers

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * A tool builder that keeps every side effect inside the temporary
     * directory: fixtures under {@code fixture-root}, history in memory.
     */
    private MergeFileTool.Builder toolFor(Path file) {
        return MergeFileTool.forFile(file)
            .fixtureRoot(tempDir.resolve("fixture-root"))
            .inMemoryOnly(true);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * A resolver that answers one type with a fixed resolution - the seam for
     * testing the tool's handling of REVIEW and DEFERRED verdicts without
     * depending on a detection shape that happens to produce them.
     */
    private static final class StubResolver implements ConflictResolver {

        private final ConflictType type;
        private final ResolutionKind kind;
        private final ResolutionStrategy strategy;
        private final String resolvedCode;
        private final String explanation;
        private final AnalysisLevel level;

        StubResolver(ConflictType type, ResolutionKind kind, ResolutionStrategy strategy,
                     String resolvedCode, String explanation) {
            this(type, kind, strategy, resolvedCode, explanation, null);
        }

        /**
         * The level-aware form, for the tests about which claim outranks which. A {@code null}
         * level leaves the resolution at its default, so the older tests are unaffected.
         */
        StubResolver(ConflictType type, ResolutionKind kind, ResolutionStrategy strategy,
                     String resolvedCode, String explanation, AnalysisLevel level) {
            this.type = type;
            this.kind = kind;
            this.strategy = strategy;
            this.resolvedCode = resolvedCode;
            this.explanation = explanation;
            this.level = level;
        }

        @Override
        public ConflictType supportedType() {
            return type;
        }

        @Override
        public ConflictResolution resolve(Conflict conflict) {
            ConflictResolution.Builder builder = ConflictResolution.builder()
                .type(type)
                .filePath(conflict.getFilePath())
                .baseCode(conflict.getBaseCode())
                // The sides travel, because a claim is judged on what it was protecting.
                .branch1Code(conflict.getBranch1Code())
                .branch2Code(conflict.getBranch2Code())
                .resolvedCode(resolvedCode)
                .resolutionStrategy(strategy)
                .kind(kind)
                .analysisLevel(level)
                .explanation(explanation);
            return builder.build();
        }

        @Override
        public List<FixPath> getFixPaths(Conflict conflict) {
            return List.of();
        }
    }

    private static MergeConflictResolver resolverWith(ConflictResolver stub) {
        return new MergeConflictResolver.Builder()
            .setBranchName("stubbed")
            .setInMemoryOnly(true)
            .setResolvers(List.of(stub))
            .build();
    }

    // --------------------------------------------------------- applied outcomes

    @Test
    @DisplayName("an import-union block is applied when fixes are requested")
    void appliesTheImportUnionWhenAsked() throws IOException {
        Path file = write("OrderService.java", IMPORT_CONFLICT_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        assertTrue(result.hadConflicts());
        assertEquals(1, result.totalBlocks());
        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome());
        assertEquals(ConflictType.IMPORT_ADD, result.outcomes().get(0).type());
        assertTrue(result.fullyResolved());
        assertEquals(0, result.exitCode());
        assertTrue(result.fileWritten());
        assertNull(result.fixtureRunDir(), "nothing remained, so no fixture workspace");

        String fixed = read(file);
        assertFalse(fixed.contains("<<<<<<<"));
        assertFalse(fixed.contains("======="));
        assertFalse(fixed.contains(">>>>>>>"));
        assertTrue(fixed.contains("import java.util.List;"));
        assertTrue(fixed.contains("import java.math.BigDecimal;"));
        assertTrue(fixed.contains("import java.time.Instant;"));
        assertTrue(fixed.contains("public List<String> openOrders()"),
            "the clean part of the file is untouched");
        assertFalse(Files.exists(tempDir.resolve("fixture-root")),
            "a fully applied run leaves nothing on disk but the fixed file");
    }

    @Test
    @DisplayName("the default is a dry run: the report says applied, the file says otherwise")
    void dryRunIsTheDefaultAndLeavesTheFileAlone() throws IOException {
        Path file = write("OrderService.java", IMPORT_CONFLICT_FILE);

        Result result = toolFor(file).run();

        assertTrue(result.dryRun());
        assertFalse(result.fileWritten());
        assertEquals(1, result.appliedCount());
        assertTrue(result.fullyResolved());
        assertEquals(IMPORT_CONFLICT_FILE, read(file), "dry run never writes");
        assertTrue(result.describe().contains("dry run"));
        assertTrue(result.describe().contains("applyFixes(true)"));
    }

    @Test
    @DisplayName("a block whose sides are identical collapses to that side")
    void identicalSidesResolve() throws IOException {
        Path file = write("OrderService.java", IDENTICAL_SIDES_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        assertEquals(Outcome.APPLIED_IDENTICAL_SIDES, result.outcomes().get(0).outcome());
        assertTrue(result.fullyResolved());
        assertNull(result.fixtureRunDir());
        String fixed = read(file);
        assertFalse(fixed.contains("<<<<<<<"));
        assertEquals(1, fixed.split("total = computeTotal\\(\\);", -1).length - 1,
            "the duplicated statement survives exactly once");
    }

    @Test
    @DisplayName("CRLF line endings survive an applied fix")
    void preservesCrlfEndings() throws IOException {
        Path file = write("OrderService.java", IMPORT_CONFLICT_FILE.replace("\n", "\r\n"));

        Result result = toolFor(file).applyFixes(true).run();

        assertTrue(result.fullyResolved());
        String fixed = read(file);
        assertFalse(fixed.replace("\r\n", "").contains("\n"),
            "no bare LF may appear in a CRLF file");
        assertTrue(fixed.contains("import java.math.BigDecimal;\r\n"));
    }

    // ------------------------------------------------------------ left outcomes

    @Test
    @DisplayName("a structural block stays marked and becomes a complete fixture case")
    void structuralBlockStaysAndBecomesAFixture() throws IOException {
        Path file = write("OrderService.java", STRUCTURAL_CONFLICT_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.LEFT_MANUAL, outcome.outcome());
        assertEquals(ConflictType.STRUCTURAL_CHANGE, outcome.type());
        assertEquals(1, result.leftCount());
        assertEquals(1, result.exitCode());
        assertFalse(result.fileWritten(), "nothing was applicable, so nothing was written");
        assertTrue(read(file).contains("<<<<<<<"), "the markers stay");

        // The fixture workspace: private by construction, complete by layout.
        Path runDir = result.fixtureRunDir();
        assertNotNull(runDir);
        assertTrue(runDir.startsWith(tempDir.resolve("fixture-root")));
        assertEquals("*\n", read(runDir.resolve(".gitignore")),
            "the workspace must never be committable");
        String agents = read(runDir.resolve(FixtureAgentInstructions.FILE_NAME));
        assertTrue(agents.toLowerCase().contains("anonymize"));
        assertTrue(agents.toLowerCase().contains("proprietary"));

        String runJson = read(runDir.resolve("run.json"));
        assertTrue(runJson.contains("\"conflictType\": \"STRUCTURAL_CHANGE\""));
        assertTrue(runJson.contains("\"baseSource\": \"none\""));
        assertTrue(runJson.contains("\"cases\": 1"));

        assertEquals(STRUCTURAL_CONFLICT_FILE, read(runDir.resolve("source/conflicted.java.txt")));
        assertFalse(Files.exists(runDir.resolve("source/base.java.txt")),
            "no base is known, so none may be fabricated");

        Path caseDir = outcome.fixtureCase();
        assertNotNull(caseDir);
        assertTrue(Files.isDirectory(caseDir));
        assertTrue(caseDir.getFileName().toString().startsWith("case-1-"));

        String conflictJson = read(caseDir.resolve("conflict.json"));
        assertTrue(conflictJson.contains("\"conflictType\": \"STRUCTURAL_CHANGE\""));
        assertTrue(conflictJson.contains("\"handling\": \"MANUAL\""));
        assertTrue(conflictJson.contains("\"outcome\": \"LEFT_MANUAL\""));
        assertTrue(conflictJson.contains("\"blockBase\": \"none\""));
        assertTrue(conflictJson.contains("AGENTS.md"));

        assertEquals("        total = computeTotal();",
            read(caseDir.resolve("block/ours.java.txt")));
        assertEquals("        surplus = computeSurplus();",
            read(caseDir.resolve("block/theirs.java.txt")));
        assertFalse(Files.exists(caseDir.resolve("block/base.java.txt")));
        assertTrue(read(caseDir.resolve("block/conflicted.txt")).startsWith("<<<<<<< ours"));

        String wholeOurs = read(caseDir.resolve("whole/ours/OrderService.java.txt"));
        assertFalse(wholeOurs.contains("<<<<<<<"));
        assertTrue(wholeOurs.contains("total = computeTotal();"));
        assertFalse(wholeOurs.contains("surplus = computeSurplus();"));
        String wholeTheirs = read(caseDir.resolve("whole/theirs/OrderService.java.txt"));
        assertTrue(wholeTheirs.contains("surplus = computeSurplus();"));
        assertFalse(Files.exists(caseDir.resolve("whole/base")),
            "without a base the layout says so by absence");
    }

    @Test
    @DisplayName("a method-body block reads as an overload clash plus a structural residual, and stays manual")
    void methodBodyBlockCarriesBothReadings() throws IOException {
        // With no base to compare against, both sides "added" describe(): the
        // overload detector claims it, and the structural residual joins it.
        // The block stays manual, and the fixture carries both readings.
        Path file = write("OrderService.java", METHOD_BODY_CONFLICT_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.LEFT_MANUAL, outcome.outcome());
        assertEquals(ConflictType.OVERLOAD_ADD, outcome.type(),
            "the reported type is the first reading detection produced");
        assertTrue(read(file).contains("<<<<<<<"));

        String conflictJson = read(outcome.fixtureCase().resolve("conflict.json"));
        assertTrue(conflictJson.contains("\"OVERLOAD_ADD\""), conflictJson);
        assertTrue(conflictJson.contains("\"STRUCTURAL_CHANGE\""), conflictJson);
    }

    @Test
    @DisplayName("a mixed file gets the safe subset applied and the rest fixtured")
    void mixedFileAppliesTheSafeSubset() throws IOException {
        Path file = write("OrderService.java", MIXED_CONFLICT_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        assertEquals(2, result.totalBlocks());
        assertEquals(1, result.appliedCount());
        assertEquals(1, result.leftCount());
        assertEquals(1, result.exitCode());
        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome());
        assertEquals(Outcome.LEFT_MANUAL, result.outcomes().get(1).outcome());
        assertTrue(result.fileWritten(), "the applied half is written");

        String fixed = read(file);
        assertTrue(fixed.contains("import java.math.BigDecimal;"));
        assertTrue(fixed.contains("import java.time.Instant;"));
        assertTrue(fixed.contains("<<<<<<<"), "the structural block stays marked");
        assertTrue(fixed.contains("their description"));

        Path runDir = result.fixtureRunDir();
        assertNotNull(runDir);
        String runJson = read(runDir.resolve("run.json"));
        assertTrue(runJson.contains("\"applied\": 1"));
        assertTrue(runJson.contains("\"left\": 1"));
        assertTrue(runJson.contains("\"cases\": 1"));
        try (var cases = Files.list(runDir.resolve("cases"))) {
            assertEquals(1, cases.count(), "only the unresolved block is fixtured");
        }
    }

    @Test
    @DisplayName("two automatic answers for one block leave it marked - they cannot be composed")
    void multipleAutomaticResolutionsLeaveTheBlock() throws IOException {
        Path file = write("OrderService.java", MULTIPLE_AUTO_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.LEFT_MULTIPLE_AUTOMATIC, outcome.outcome());
        assertEquals(1, result.exitCode());
        assertTrue(read(file).contains("<<<<<<<"));

        assertNotNull(result.fixtureRunDir());
        String conflictJson = read(outcome.fixtureCase().resolve("conflict.json"));
        assertTrue(conflictJson.contains("IMPORT_ADD"), conflictJson);
        assertTrue(conflictJson.contains("COMMENT_ADD"), conflictJson);
    }

    @Test
    @DisplayName("an automatic answer that rewrites only part of the block is not applied")
    void partialResolutionLeavesTheBlock() throws IOException {
        // The union of the imports is a correct import answer, but the block
        // also carries the class header: replacing the block with the union
        // would silently delete it.
        Path file = write("OrderService.java", PARTIAL_COVERAGE_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.LEFT_PARTIAL_RESOLUTION, outcome.outcome());
        assertTrue(outcome.explanation().contains("part of the block"), outcome.explanation());
        // And in the terms the composition will use: how much of the block is contested at all against how
        // much neither branch touched. The class header in this fixture is the second kind, which is why
        // "rewrites only part of the block" is a statement about the answer rather than about the block.
        assertTrue(outcome.explanation().contains("neither branch touched"), outcome.explanation());
        assertTrue(read(file).contains("<<<<<<<"));
        assertNotNull(outcome.fixtureCase());
        assertTrue(Files.isDirectory(outcome.fixtureCase()));
    }

    @Test
    @DisplayName("differing sides no detector recognises stay marked and become an unclassified case")
    void unclassifiedBlockBecomesAFixture() throws IOException {
        Path file = write("OrderService.java", UNCLASSIFIED_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.LEFT_UNCLASSIFIED, outcome.outcome());
        // Since plan step 4.19's decision (2026-10-09) the case is NAMED rather than left with a null type: a null meant
        // "unknown", "not asked" and "no tier reached it" at once, so nothing could select these blocks and no report
        // could print what they were. The name is the lower tier that was reached last and declined, and the block is
        // still handed to a human — the outcome is unchanged, which is what the assertion above keeps.
        assertEquals(ConflictType.UNCLASSIFIED_TEXT, outcome.type(),
            "the unclassified case carries its own type now: " + outcome.type());
        assertTrue(read(file).contains("<<<<<<<"));

        Path caseDir = outcome.fixtureCase();
        assertNotNull(caseDir);
        assertTrue(caseDir.getFileName().toString().contains("unclassified"),
            caseDir.getFileName().toString());
        String conflictJson = read(caseDir.resolve("conflict.json"));
        // Named since plan step 4.19's decision (2026-10-09): the fixture records the type a classifier can select,
        // instead of the string "UNCLASSIFIED" that stood in for a null.
        assertTrue(conflictJson.contains("\"conflictType\": \"UNCLASSIFIED_TEXT\""), conflictJson);
        assertTrue(conflictJson.contains("\"resolutions\": []"));
    }

    @Test
    @DisplayName("an answer the resolver computed is offered as a suggestion, and the block is fixtured")
    void reviewResolutionLeavesTheBlock() throws IOException {
        Path file = write("OrderService.java", STRUCTURAL_CONFLICT_FILE);
        String computed = "        total = computeTotal();   // our wording confirmed";
        MergeConflictResolver stubbed = resolverWith(new StubResolver(
            ConflictType.STRUCTURAL_CHANGE, ResolutionKind.REVIEW, ResolutionStrategy.MERGE_SAFE,
            computed, "Kept our statement; a human should confirm."));

        Result result = toolFor(file).resolver(stubbed).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        // Step 4.15: this used to be LEFT_REVIEW and the computed code was thrown away at the surface. It is
        // the same non-decision - the markers stay - with the answer now in the reviewer's hands.
        assertEquals(Outcome.LEFT_SUGGESTION, outcome.outcome(), outcome.explanation());
        assertEquals(ConflictType.STRUCTURAL_CHANGE, outcome.type());
        assertTrue(read(file).contains("<<<<<<<"), "nothing was applied: " + read(file));
        assertNotNull(outcome.fixtureCase(), "and the block's case is still prepared for whoever picks it up");
    }

    @Test
    @DisplayName("a deferred block waits for the opt-in, then the recorded decision is written")
    void recordedDecisionIsAppliedOnlyWhenAsked() throws IOException {
        String decided = "        total = computeSurplus();   // decided earlier";
        MergeConflictResolver stubbed = resolverWith(new StubResolver(
            ConflictType.STRUCTURAL_CHANGE, ResolutionKind.DEFERRED,
            ResolutionStrategy.STICKY_REPLAY, decided,
            "Replayed a decision recorded on an earlier run."));

        Path first = write("First.java", STRUCTURAL_CONFLICT_FILE);
        Result waiting = toolFor(first).resolver(stubbed).applyFixes(true).run();
        assertEquals(Outcome.LEFT_DEFERRED, waiting.outcomes().get(0).outcome());
        assertNull(waiting.fixtureRunDir(), "an existing decision needs no new fixture");
        assertTrue(read(first).contains("<<<<<<<"));
        assertTrue(waiting.describe().contains("applyRecordedDecisions(true)"));

        Path second = write("Second.java", STRUCTURAL_CONFLICT_FILE);
        Result applying = toolFor(second).resolver(stubbed)
            .applyRecordedDecisions(true).applyFixes(true).run();
        assertEquals(Outcome.APPLIED_RECORDED_DECISION, applying.outcomes().get(0).outcome());
        assertTrue(applying.fullyResolved());
        assertEquals(0, applying.exitCode());
        String fixed = read(second);
        assertFalse(fixed.contains("<<<<<<<"));
        assertTrue(fixed.contains("decided earlier"));
    }

    // ------------------------------------------------------------ clean reports

    @Test
    @DisplayName("a file without markers is a no-op, even with fixes enabled")
    void cleanFileIsANoOp() throws IOException {
        String clean = "package com.example.demo;\n\npublic class OrderService {\n}\n";
        Path file = write("OrderService.java", clean);

        Result result = toolFor(file).applyFixes(true).run();

        assertFalse(result.hadConflicts());
        assertEquals(0, result.totalBlocks());
        assertTrue(result.fullyResolved());
        assertEquals(0, result.exitCode());
        assertFalse(result.fileWritten());
        assertNull(result.fixtureRunDir());
        assertEquals(clean, read(file));
        assertTrue(result.describe().contains("no conflict markers"));
    }

    @Test
    @DisplayName("fixtures can be declined - the report survives without a workspace")
    void fixturesCanBeDeclined() throws IOException {
        Path file = write("OrderService.java", STRUCTURAL_CONFLICT_FILE);

        Result result = toolFor(file).prepareFixtures(false).run();

        assertEquals(Outcome.LEFT_MANUAL, result.outcomes().get(0).outcome());
        assertNull(result.fixtureRunDir());
        assertNull(result.outcomes().get(0).fixtureCase());
        assertFalse(Files.exists(tempDir.resolve("fixture-root")));
    }

    // ----------------------------------------------------------------- reverify

    @Test
    @DisplayName("reverify judges a candidate resolver against the original fixture case")
    void reverifyRunsTheGateAgainstTheOriginalCase() throws IOException {
        Path file = write("OrderService.java", STRUCTURAL_CONFLICT_FILE);
        Result result = toolFor(file).run();
        Path caseDir = result.outcomes().get(0).fixtureCase();
        assertNotNull(caseDir);

        // The shipped structural resolver declines: not viable.
        MergeFileTool.Reverification declined =
            MergeFileTool.reverify(caseDir, new StructuralChangeConflictResolver());
        assertEquals(ConflictType.STRUCTURAL_CHANGE, declined.conflictType());
        assertFalse(declined.viable());
        assertEquals(ResolutionKind.MANUAL, declined.kind());

        // A candidate that answers with clean, balanced code: viable.
        MergeFileTool.Reverification answered = MergeFileTool.reverify(caseDir, new StubResolver(
            ConflictType.STRUCTURAL_CHANGE, ResolutionKind.AUTO, ResolutionStrategy.MERGE_SAFE,
            "package com.example.demo;\n\npublic class OrderService {\n\n"
                + "    public String describe() {\n        return \"our description\";\n    }\n}\n",
            "Kept our description."));
        assertTrue(answered.viable());
        assertEquals(ResolutionKind.AUTO, answered.kind());
        assertTrue(answered.resolvedCode().contains("our description"));

        // A candidate whose answer still carries markers fails the gate.
        MergeFileTool.Reverification marked = MergeFileTool.reverify(caseDir, new StubResolver(
            ConflictType.STRUCTURAL_CHANGE, ResolutionKind.AUTO, ResolutionStrategy.MERGE_SAFE,
            "<<<<<<< ours\nbroken\n>>>>>>> theirs\n",
            "Still conflicted."));
        assertFalse(marked.viable());
    }

    @Test
    @DisplayName("reverify refuses an unclassified case - there is no type to build a resolver for")
    void reverifyRejectsUnclassifiedCases() throws IOException {
        Path file = write("OrderService.java", UNCLASSIFIED_FILE);
        Result result = toolFor(file).run();
        Path caseDir = result.outcomes().get(0).fixtureCase();
        assertNotNull(caseDir);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> MergeFileTool.reverify(caseDir, new StructuralChangeConflictResolver()));
        assertTrue(failure.getMessage().contains("usable conflict type"), failure.getMessage());
    }

    // ---------------------------------------------------------------- classpath

    /**
     * A conflict about the project's own types: {@code Gadget} extends {@code Widget}, so
     * adopting the {@code Widget} declaration is a widening only a <em>compiled</em> view
     * of the project can see.
     */
    private static final String PROJECT_TYPE_CONFLICT_FILE = """
            package com.example.demo;

            public class OrderService {
            <<<<<<< ours
                com.example.Gadget value = null;
            =======
                com.example.Widget value = null;
            >>>>>>> theirs

                public String describe() {
                    return String.valueOf(value);
                }
            }
            """;

    /** The same shape on JDK types, which resolves whether or not a project classpath is given. */
    private static final String JDK_TYPE_CONFLICT_FILE = """
            package com.example.demo;

            public class IndexService {
            <<<<<<< ours
                java.util.HashMap<String, String> index = new java.util.HashMap<>();
            =======
                java.util.Map<String, String> index = new java.util.HashMap<>();
            >>>>>>> theirs
            }
            """;

    /** The compiled fixture types, built once per JVM. */
    private static Path projectTypes;

    /**
     * The project types the classpath tests resolve against, compiled <b>once</b> and shared.
     *
     * <p>javac is not cheap and this was run per test — seven times for this class, each time to
     * produce the same three classes. The directory is JVM-scoped rather than the test's
     * {@code @TempDir} (which JUnit deletes after every test), so the compile happens once for the
     * whole fork instead.
     */
    private Path compiledProjectTypes() throws IOException {
        if (projectTypes != null && Files.isDirectory(projectTypes)) {
            return projectTypes;
        }

        Path root = Files.createTempDirectory("merge-java-project-types");
        Path sources = Files.createDirectories(root.resolve("src/com/example"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Files.writeString(sources.resolve("Widget.java"),
            "package com.example;\n\npublic class Widget {\n}\n");
        Files.writeString(sources.resolve("Gadget.java"),
            "package com.example;\n\npublic class Gadget extends Widget {\n}\n");
        Files.writeString(sources.resolve("MiniGadget.java"),
            "package com.example;\n\npublic class MiniGadget extends Gadget {\n}\n");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test runs on a JDK");
        int status = compiler.run(null, null, null,
            "-d", classes.toString(),
            sources.resolve("Widget.java").toString(),
            sources.resolve("Gadget.java").toString(),
            sources.resolve("MiniGadget.java").toString());
        assertEquals(0, status, "the fixture project types must compile");

        projectTypes = classes;
        return classes;
    }

    /**
     * Compiles one file's text as {@code OrderService.java} against {@code classes}, so a
     * test can assert that what the tool wrote is real code rather than text that merely
     * looks resolved.
     */
    private boolean compiles(String code, Path classes) throws IOException {
        Path sources = Files.createDirectories(tempDir.resolve("applied-src"));
        Path source = sources.resolve("OrderService.java");
        Files.writeString(source, code, StandardCharsets.UTF_8);
        Path output = Files.createDirectories(tempDir.resolve("applied-classes"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test runs on a JDK");
        return compiler.run(null, null, null,
            "-cp", classes.toString(), "-d", output.toString(), source.toString()) == 0;
    }

    @Test
    @DisplayName("a project classpath decides the project's own types instead of escalating them")
    void classpathDecidesProjectTypes() throws IOException {
        Path classes = compiledProjectTypes();
        Path without = write("Without.java", PROJECT_TYPE_CONFLICT_FILE);
        Path with = write("With.java", PROJECT_TYPE_CONFLICT_FILE);

        Result escalated = toolFor(without).applyFixes(true).run();
        Result decided = toolFor(with).classpath(List.of(classes)).applyFixes(true).run();

        String escalatedReport = escalated.outcomes().get(0).explanation();
        String decidedReport = decided.outcomes().get(0).explanation();

        assertTrue(decidedReport.contains("is a widening of"),
            "with the classpath, Widget is a widening of Gadget: " + decidedReport);
        assertFalse(decidedReport.contains("STRUCTURAL_CHANGE"),
            "and the residual it is subsumed by no longer decides the block: " + decidedReport);
        assertFalse(escalatedReport.contains("is a widening of"),
            "without it both declarations are Unknown, so nothing is decided: " + escalatedReport);
        assertTrue(escalatedReport.contains("[TYPE_CHANGE/SUGGESTION]"),
            "the widening it computed is offered now rather than stored and dropped: " + escalatedReport);

        // The residual STRUCTURAL_CHANGE is still emitted alongside the recognised
        // conflict (by design - a residual that replaced the recognised conflicts once
        // lost a mechanical import addition), but it no longer vetoes a block it is
        // subsumed by: with the classpath the widening is a complete automatic answer,
        // so the block is written. Without one the only answer is a review, so the veto
        // stands and the classpath genuinely changes what this tool does.
        assertEquals(1, escalated.exitCode());
        assertEquals(0, decided.exitCode(),
            "a residual with nothing of its own to say must not veto the decided block");
        assertEquals(Outcome.APPLIED_AUTO, decided.outcomes().get(0).outcome(),
            decidedReport);
        assertFalse(read(with).contains("<<<<<<<"));
        assertTrue(compiles(read(with), classes),
            "the block the residual stopped vetoing must compile: " + read(with));
        assertTrue(read(without).contains("<<<<<<<"),
            "and with nothing decided the block still waits for a human");
    }

    /**
     * The same conflict carrying a base side - {@code diff3}/{@code zdiff3} style, which
     * git writes only when asked: base {@code MiniGadget}, ours {@code Widget}, theirs
     * {@code Gadget}. Every base line the residual can place is the declaration the type
     * change is already about, so the residual has nothing of its own to say.
     */
    private static final String SUBSUMED_RESIDUAL_FILE = """
            package com.example.demo;

            public class OrderService {
            <<<<<<< ours
                com.example.Widget value = null;
            ||||||| base
                com.example.MiniGadget value = null;
            =======
                com.example.Gadget value = null;
            >>>>>>> theirs

                public String describe() {
                    return String.valueOf(value);
                }
            }
            """;

    /**
     * The same block plus a line both branches changed that no recognised conflict
     * explains: {@code retries}. The residual now reaches a line of its own, so it keeps
     * the veto - the case the alongside-emission exists for.
     */
    private static final String UNEXPLAINED_RESIDUAL_FILE = """
            package com.example.demo;

            public class OrderService {
            <<<<<<< ours
                com.example.Widget value = null;
                private int retries = 5;
            ||||||| base
                com.example.MiniGadget value = null;
                private int retries = 0;
            =======
                com.example.Gadget value = null;
                private int retries = 7;
            >>>>>>> theirs

                public String describe() {
                    return String.valueOf(value);
                }
            }
            """;

    @Test
    @DisplayName("a subsumed residual stops vetoing a block with a base side, and the applied code compiles")
    void subsumedResidualDoesNotVeto() throws IOException {
        Path classes = compiledProjectTypes();
        Path file = write("Subsumed.java", SUBSUMED_RESIDUAL_FILE);
        Path report = tempDir.resolve("subsumed-report.json");

        Result result = toolFor(file).classpath(List.of(classes)).applyFixes(true)
            .reportPath(report).run();

        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        assertEquals(0, result.exitCode());
        String applied = read(file);
        assertFalse(applied.contains("<<<<<<<"), applied);
        assertTrue(applied.contains("com.example.Widget value = null;"),
            "the wider declaration is the one adopted: " + applied);
        assertTrue(compiles(applied, classes), "the applied code must compile: " + applied);

        // Dropped from the decision, never from the report: a reviewer still sees the
        // residual, which is the whole point of emitting it alongside the recognised
        // conflicts rather than instead of them.
        assertTrue(read(report).contains("STRUCTURAL_CHANGE"),
            "the subsumed residual is still reported: " + read(report));
    }

    @Test
    @DisplayName("a residual that reaches a line no other conflict explains keeps its veto")
    void unexplainedResidualStillVetoes() throws IOException {
        Path classes = compiledProjectTypes();
        Path file = write("Unexplained.java", UNEXPLAINED_RESIDUAL_FILE);

        Result result = toolFor(file).classpath(List.of(classes)).applyFixes(true).run();

        assertEquals(Outcome.LEFT_MANUAL, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        assertEquals(1, result.exitCode());
        assertTrue(read(file).contains("<<<<<<<"), "the markers stay");
        assertTrue(result.outcomes().get(0).explanation().contains("is a widening of"),
            "and the type change was still decided - the veto is the residual's, not the "
                + "type's: " + result.outcomes().get(0).explanation());
    }

    /**
     * Two branches each appending a member beside the one the base declared: the additions are
     * adjacent, so git reports a conflict, and the member both sides carry is context rather than a
     * change.
     */
    private static final String DISTINCT_MEMBERS_FILE = """
            package com.example.demo;

            public class OrderService {
            <<<<<<< ours
                public void audit() {
                }

                public int charge() {
                    return 1;
                }
            ||||||| base
                public void audit() {
                }
            =======
                public void audit() {
                }

                public int refund() {
                    return 2;
                }
            >>>>>>> theirs
            }
            """;

    @Test
    @DisplayName("a distinct member added on each side is kept - both, and the shared one once")
    void distinctMemberAdditionsAreKept() throws IOException {
        Path file = write("Members.java", DISTINCT_MEMBERS_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        assertEquals(0, result.exitCode());
        String applied = read(file);
        assertFalse(applied.contains("<<<<<<<"), applied);
        assertTrue(applied.contains("public int charge()"), applied);
        assertTrue(applied.contains("public int refund()"), applied);
        assertEquals(1, applied.split("public void audit\\(\\)", -1).length - 1,
            "the member both sides carried is context, and must appear once: " + applied);
        assertTrue(compiles(applied, tempDir),
            "the merged class must compile - a repeated member would not: " + applied);
    }

    /**
     * The shape a real merge produces for two adjacent additions: a {@code diff3} hunk whose base
     * section is present and <b>empty</b>, because the base had nothing in that region.
     */
    private static final String EMPTY_BASE_INSERTION_FILE = """
            package com.example.demo;

            public class OrderService {
            <<<<<<< ours
                public int charge() {
                    return 1;
                }
            ||||||| base
            =======
                public int refund() {
                    return 2;
                }
            >>>>>>> theirs
            }
            """;

    /** The same shape one level down: two distinct fields, each declaring its access. */
    private static final String EMPTY_BASE_FIELD_INSERTION_FILE = """
            package com.example.demo;

            public class OrderService {
            <<<<<<< ours
                private final int chargeCount = 1;
            ||||||| base
            =======
                private final int refundCount = 2;
            >>>>>>> theirs

                public int total() {
                    return 0;
                }
            }
            """;

    @Test
    @DisplayName("an insertion at an empty base is applied, and the text tier is never asked")
    void insertionAtAnEmptyBaseIsApplied() throws IOException {
        Path file = write("Inserted.java", EMPTY_BASE_INSERTION_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        assertEquals(0, result.exitCode());
        String applied = read(file);
        assertFalse(applied.contains("<<<<<<<"), applied);
        assertTrue(applied.contains("public int charge()") && applied.contains("public int refund()"),
            applied);
        assertTrue(compiles(applied, tempDir), applied);

        // The API check reads the first public declaration of each side as text and calls this a
        // changed contract; recognising the two members is what answers that.
        //
        // Step 4.19 changed what happens to that objection, and this is the instruction's own first
        // example: an insertion into an *empty* base has no base lines, so every base-line question about
        // it is unanswerable and the conflict is judged over its block instead. The text tier is then
        // **cleared** - the resolver that would have raised the objection is never called - rather than
        // asked and overruled. Before this step the same block applied, and the report said "Outranked on
        // this block": the same decision, reached by doing the weaker work first and discarding it.
        assertTrue(result.outcomes().get(0).explanation().contains("never asked"),
            result.outcomes().get(0).explanation());
        assertTrue(result.outcomes().get(0).explanation().contains("API_INCOMPATIBILITY"),
            "and the claim that was not asked is named by type: "
                + result.outcomes().get(0).explanation());
    }

    @Test
    @DisplayName("two distinct fields inserted at an empty base are both kept")
    void distinctFieldsAtAnEmptyBaseAreKept() throws IOException {
        Path file = write("Fields.java", EMPTY_BASE_FIELD_INSERTION_FILE);

        Result result = toolFor(file).applyFixes(true).run();

        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        assertEquals(0, result.exitCode());
        String applied = read(file);
        assertFalse(applied.contains("<<<<<<<"), applied);
        assertTrue(applied.contains("private final int chargeCount = 1;"), applied);
        assertTrue(applied.contains("private final int refundCount = 2;"), applied);
        assertTrue(compiles(applied, tempDir), applied);
    }

    // ------------------------------------------------- analysis level arbitration

    /** Every line of both sides, which is what a claim must keep to have dropped nothing. */
    private static final String BOTH_SIDES_KEPT =
        "com.example.Widget value = null;\nprivate int retries = 5;"
            + "\ncom.example.Gadget value = null;\nprivate int retries = 7;";

    @Test
    @DisplayName("stronger evidence outranks a weaker objection that it already accounts for")
    void strongerEvidenceOutranksWeakerObjection() throws IOException {
        Path file = write("Outranked.java", UNEXPLAINED_RESIDUAL_FILE);

        // KEEP_BOTH, so the winner contains every line of both sides: nothing the residual was
        // guarding is dropped, and the claim rests on resolved types where the residual compares
        // lines. The residual keeps its region on the second line, so this is decided by what the
        // winner accounts for and not by the regions happening to coincide.
        //
        // Step 4.19 leaves this block on the arbitration path, and that is the point: this resolver set
        // owns TYPE_CHANGE only, so STRUCTURAL_CHANGE is a type no resolver here speaks for - and a type
        // nobody owns is not settled by anything, whatever a claim explains. With the module's full
        // resolver set the same shape is *settled* rather than outranked, which is what
        // TieredResolutionTest asserts.
        Result result = toolFor(file)
            .resolver(resolverWith(new StubResolver(ConflictType.TYPE_CHANGE,
                ResolutionKind.AUTO, ResolutionStrategy.KEEP_BOTH, BOTH_SIDES_KEPT,
                "Both declarations are kept.", AnalysisLevel.PROJECT_TYPES)))
            .applyFixes(true)
            .run();

        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        assertEquals(0, result.exitCode());
        assertTrue(result.outcomes().get(0).explanation().contains("Outranked on this block"),
            "and the report names the claim that lost, so the comparison can be judged: "
                + result.outcomes().get(0).explanation());
        assertTrue(result.outcomes().get(0).explanation().contains("STRUCTURAL_CHANGE"),
            "the outranked claim is named by type: "
                + result.outcomes().get(0).explanation());
    }

    @Test
    @DisplayName("equal evidence outranks nothing - the block stays for a human")
    void equalEvidenceOutranksNothing() throws IOException {
        Path file = write("Equal.java", UNEXPLAINED_RESIDUAL_FILE);

        // Same answer, same everything - except that this claim rests on no more evidence than the
        // objection does. Two analyses of equal strength disagreeing is the case a human settles.
        Result result = toolFor(file)
            .resolver(resolverWith(new StubResolver(ConflictType.TYPE_CHANGE,
                ResolutionKind.AUTO, ResolutionStrategy.KEEP_BOTH, BOTH_SIDES_KEPT,
                "Both declarations are kept.", AnalysisLevel.TEXT_LOCAL)))
            .applyFixes(true)
            .run();

        assertEquals(Outcome.LEFT_MANUAL, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        assertEquals(1, result.exitCode());
        assertTrue(read(file).contains("<<<<<<<"), "the markers stay");
    }

    @Test
    @DisplayName("an explicit classpath still resolves JDK types")
    void explicitClasspathKeepsThePlatform() throws IOException {
        Path classes = compiledProjectTypes();
        Path file = write("IndexService.java", JDK_TYPE_CONFLICT_FILE);

        Result result = toolFor(file).classpath(List.of(classes)).applyFixes(true).run();

        assertTrue(result.outcomes().get(0).explanation().contains("is a widening of"),
            "the platform is not a classpath entry: an explicit project classpath must not "
                + "hide java.util, or asking for more resolution would lose some: "
                + result.outcomes().get(0).explanation());
    }

    @Test
    @DisplayName("the CLI takes --classpath, and refuses an entry that does not exist")
    void cliTakesClasspath() throws IOException {
        Path classes = compiledProjectTypes();
        Path file = write("CliWith.java", PROJECT_TYPE_CONFLICT_FILE);

        int status = MergeFileTool.runMain(new String[] {file.toString(),
            "--no-fixtures", "--classpath", classes.toString()});

        assertEquals(0, status,
            "the flag is accepted and the block is decided, rather than a usage error");
        assertTrue(read(file).contains("<<<<<<<"), "and the default run writes nothing");

        // A misspelled entry contributes nothing to attribution, so without this check the
        // conflict would escalate and look like a limitation of the tool rather than a typo.
        int missing = MergeFileTool.runMain(new String[] {file.toString(),
            "--classpath", tempDir.resolve("does-not-exist").toString()});
        assertEquals(2, missing);
        assertEquals(2, MergeFileTool.runMain(new String[] {"--classpath"}),
            "and a flag with no value is a usage error");
    }

    // ---------------------------------------------------------------------- CLI

    @Test
    @DisplayName("the CLI applies fixes and reports exit status 0")
    void cliAppliesAndExitsZero() throws IOException {
        Path file = write("OrderService.java", IMPORT_CONFLICT_FILE);

        int status = MergeFileTool.runMain(new String[] {
            file.toString(), "--apply", "--fixtures", tempDir.resolve("cli-root").toString()});

        assertEquals(0, status);
        assertFalse(read(file).contains("<<<<<<<"));
    }

    @Test
    @DisplayName("the CLI exits 1 while conflicts remain and points at the workspace")
    void cliExitsOneWhileConflictsRemain() throws IOException {
        Path file = write("OrderService.java", STRUCTURAL_CONFLICT_FILE);
        Path root = tempDir.resolve("cli-root");

        int status = MergeFileTool.runMain(new String[] {
            "--fixtures", root.toString(), file.toString()});

        assertEquals(1, status);
        assertTrue(read(file).contains("<<<<<<<"));
        assertTrue(Files.isDirectory(root), "the fixture workspace was prepared");
        try (var runs = Files.list(root)) {
            assertEquals(1, runs.count());
        }
    }

    @Test
    @DisplayName("the CLI rejects bad usage with exit status 2")
    void cliUsageErrors() {
        assertEquals(2, MergeFileTool.runMain(new String[0]));
        assertEquals(2, MergeFileTool.runMain(new String[] {"--bogus"}));
        assertEquals(2, MergeFileTool.runMain(new String[] {"--fixtures"}));
        assertEquals(0, MergeFileTool.runMain(new String[] {"--help"}));
    }
    @Test
    @DisplayName("writes the report the review page renders, for one file")
    void writesTheReportTheReviewPageRenders() throws Exception {
        Path file = write("OrderService.java", IMPORT_CONFLICT_FILE);
        Path report = tempDir.resolve("merge-report.json");

        MergeFileTool.Result result = toolFor(file).reportPath(report).run();

        assertTrue(Files.isRegularFile(report), "the report must be written");
        String json = read(report);
        assertTrue(json.contains("OrderService.java"), "the file must be named: " + json);
        assertTrue(json.contains("IMPORT_ADD"), "the conflict type must be there: " + json);
        assertTrue(json.contains("\"sides\""),
            "the three sides are what the page compares: " + json);
        assertTrue(json.contains("\"signature\""),
            "and the key a decision is recorded under: " + json);

        // The conflict's region is the BLOCK's, in file coordinates: the detector works on the block's slices,
        // so its own regions are block-relative and two conflicts of one block are indistinguishable. The page
        // groups by that region, so this is the invariant that makes reading per block possible.
        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        JsonNode conflict = new ObjectMapper().readTree(json).path("files").get(0).path("conflicts").get(0);
        assertEquals(outcome.markerRegion().startLine(), conflict.path("region").path("startLine").asInt(),
            "a conflict is located where its block is");
        assertEquals(outcome.markerRegion().endLine(), conflict.path("region").path("endLine").asInt(),
            "and spans the block");
    }

    /**
     * The round trip the review flow depends on, end to end and without a browser: the tool writes the report the
     * page renders, the page's export is built from that report's own facts (signature, type, filePath, sides -
     * exactly what {@code src/decisions.js} reads), and the SAME CLI records and applies it. A decision the tool
     * could not have made by itself is what makes this a test rather than a coincidence.
     */
    @Test
    @DisplayName("applies the decisions the review page exported, built from its own report")
    void appliesTheDecisionsTheReviewPageExported() throws Exception {
        Path file = write("Ledger.java", STRUCTURAL_CONFLICT_FILE);
        Path report = tempDir.resolve("merge-report.json");

        // 1. The report the page would render.
        toolFor(file).reportPath(report).run();
        String before = read(file);

        // 2. The page's export, built from that report.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode document = mapper.readTree(read(report));
        JsonNode section = document.path("files").get(0);
        // The page builds its payload from the CONFLICT's own key and sides, and pairs it with the resolution
        // by index (the report's two arrays are index-parallel). A replayed resolution would otherwise name a
        // signature the raw conflict does not have.
        JsonNode conflict = section.path("conflicts").get(0);
        JsonNode resolution = section.path("resolutions").get(0);
        JsonNode sides = conflict.path("sides");
        String resolvedCode = resolution.path("sides").path("branch1").asString("");

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("signature", conflict.path("signature").asString(""));
        decision.put("type", conflict.path("type").asString(""));
        decision.put("filePath", section.path("filePath").asString(""));
        decision.put("description", conflict.path("description").asString(""));
        decision.put("base", sides.path("base").asString(""));
        decision.put("branch1", sides.path("branch1").asString(""));
        decision.put("branch2", sides.path("branch2").asString(""));
        decision.put("resolvedCode", resolvedCode);
        decision.put("explanation", "reviewer chose branch 1 in the review page");

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("schemaVersion", 1);
        export.put("branchName", "feature-payments");
        export.put("decisions", List.of(decision));
        Path decisions = tempDir.resolve("decisions.json");
        Files.writeString(decisions, mapper.writeValueAsString(export), StandardCharsets.UTF_8);

        // 3. The same CLI records them and applies them.
        MergeFileTool.Result result = MergeFileTool.forFile(file)
            .fixtureRoot(tempDir.resolve("fixture-root"))
            .historyPath(tempDir.resolve("history"))
            .branchName("feature-payments")
            .decisionsFile(decisions)
            .applyFixes(true)
            .run();

        String after = read(file);
        assertNotEquals(before, after, "the accepted decision must change the file");
        String chosen = resolvedCode.strip();
        String firstLine = chosen.lines().findFirst().orElse("").strip();
        assertFalse(firstLine.isEmpty(), "the report must carry the branch 1 side to accept");
        assertTrue(after.contains(firstLine),
            "the code the reviewer accepted must be in the file, got: " + after);
        assertTrue(result.outcomes().size() > 0, result.describe());
    }

}
