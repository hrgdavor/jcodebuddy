// {@link com.codebuddy.merge.MergeFileToolTest} Tests the conflict-file tool: application rules, fixture preparation and the CLI.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.ConflictResolution.ResolutionKind;
import com.codebuddy.merge.ConflictResolution.ResolutionStrategy;
import com.codebuddy.merge.MergeFileTool.Outcome;
import com.codebuddy.merge.MergeFileTool.Result;
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

        StubResolver(ConflictType type, ResolutionKind kind, ResolutionStrategy strategy,
                     String resolvedCode, String explanation) {
            this.type = type;
            this.kind = kind;
            this.strategy = strategy;
            this.resolvedCode = resolvedCode;
            this.explanation = explanation;
        }

        @Override
        public ConflictType supportedType() {
            return type;
        }

        @Override
        public ConflictResolution resolve(Conflict conflict) {
            return ConflictResolution.builder()
                .type(type)
                .filePath(conflict.getFilePath())
                .baseCode(conflict.getBaseCode())
                .resolvedCode(resolvedCode)
                .resolutionStrategy(strategy)
                .kind(kind)
                .explanation(explanation)
                .build();
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
        assertNull(outcome.type());
        assertTrue(read(file).contains("<<<<<<<"));

        Path caseDir = outcome.fixtureCase();
        assertNotNull(caseDir);
        assertTrue(caseDir.getFileName().toString().contains("unclassified"),
            caseDir.getFileName().toString());
        String conflictJson = read(caseDir.resolve("conflict.json"));
        assertTrue(conflictJson.contains("\"conflictType\": \"UNCLASSIFIED\""));
        assertTrue(conflictJson.contains("\"resolutions\": []"));
    }

    @Test
    @DisplayName("a review resolution leaves the block marked and fixtured")
    void reviewResolutionLeavesTheBlock() throws IOException {
        Path file = write("OrderService.java", STRUCTURAL_CONFLICT_FILE);
        MergeConflictResolver stubbed = resolverWith(new StubResolver(
            ConflictType.STRUCTURAL_CHANGE, ResolutionKind.REVIEW, ResolutionStrategy.MERGE_SAFE,
            "        total = computeTotal();   // our wording confirmed",
            "Kept our statement; a human should confirm."));

        Result result = toolFor(file).resolver(stubbed).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.LEFT_REVIEW, outcome.outcome());
        assertEquals(ConflictType.STRUCTURAL_CHANGE, outcome.type());
        assertTrue(read(file).contains("<<<<<<<"));
        assertNotNull(outcome.fixtureCase());
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
}
