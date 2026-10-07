// {@link com.codebuddy.merge.HierarchicalAcceptanceTest} The instruction's two examples, end to end (plan step 4.19).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.MergeFileTool.Outcome;
import com.codebuddy.merge.MergeFileTool.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maintainer's two examples, driven through the whole tool with the module's own resolvers — not through
 * stubs (unified plan step 4.19).
 *
 * <h2>Why this file exists separately from {@code TieredResolutionTest}</h2>
 *
 * <p>{@code TieredResolutionTest} asserts the <em>mechanism</em>: with a claim source it controls, a settled
 * conflict is never offered to the tier below. That is where "never asked" is checkable, because an
 * assertion on the outcome cannot tell it from "asked and overruled". This file asks the other question, and
 * it is the one the instruction actually makes:
 *
 * <blockquote>"structural that knows two branches added one or more whole methods in same location should
 * clear any conflicts they cover and do not need to be analyzed by text resolver, same goes for imports
 * resolver, if it has success, then there is no need for lower tier to touch that part of the file"</blockquote>
 *
 * <p>So it runs the real {@code MemberAddConflictResolver} and the real {@code ImportConflictResolver} over
 * real conflict files, and asserts that the text tier was <b>cleared</b> — named in the block's report as
 * never asked rather than outranked.
 */
class HierarchicalAcceptanceTest {

    @TempDir
    Path tempDir;

    /**
     * Two branches inserting a distinct whole method at the same place, as a {@code diff3} hunk.
     *
     * <p>The base section is <b>present and empty</b>, which is what makes this the instruction's example
     * rather than a guess: the base had no lines there, so both sides inserted, and
     * {@code detectMemberAddConflicts} accepts it. A file with no base section at all would be an unknown
     * base and the detector declines it by design.
     */
    private static final String TWO_METHODS_ADDED = """
            package com.example.demo;

            public class OrderService {

                public void audit() {
                }
            <<<<<<< ours
                public void charge() {
                }
            ||||||| base
            =======
                public void refund() {
                }
            >>>>>>> theirs
            }
            """;

    @Test
    @DisplayName("two methods added in the same place are kept, and the text tier is cleared")
    void twoAddedMethodsClearTheTextTier() throws IOException {
        Path file = write("OrderService.java", TWO_METHODS_ADDED);

        Result result = toolFor(file).applyFixes(true).run();

        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        String applied = read(file);
        assertTrue(applied.contains("void charge()"), applied);
        assertTrue(applied.contains("void refund()"), applied);
        assertTrue(result.outcomes().get(0).explanation().contains("never asked"),
            "the text tier must be cleared rather than overruled: "
                + result.outcomes().get(0).explanation());
    }

    @Test
    @DisplayName("the report says what became of every conflict, including the one never asked about")
    void theReportCarriesTheStateOfEveryConflict() throws IOException {
        // A settled conflict has no claim of its own, so without this key it appears nowhere in the report:
        // the reviewer sees a conflict that vanished rather than one that was cleared (DEC-046 clause 7).
        Path file = write("OrderService.java", TWO_METHODS_ADDED);
        Path report = tempDir.resolve("state-report.json");

        Result result = toolFor(file).reportPath(report).run();

        assertEquals(0, result.exitCode());
        String json = Files.readString(report, StandardCharsets.UTF_8);
        long resolved = json.split("\"state\": \"RESOLVED\"", -1).length - 1;
        assertEquals(2, resolved,
            "both conflicts are settled: one by its own answer, one by never having been asked: " + json);
    }

    @Test
    @DisplayName("a conflict nothing settled is reported open rather than absent")
    void anUnsettledConflictIsReportedOpen() throws IOException {
        Path file = write("Unsettled.java", UNSETTLED_LINE);
        Path report = tempDir.resolve("open-report.json");

        Result result = toolFor(file).reportPath(report).run();

        assertEquals(1, result.exitCode(), "the block is left for a human");
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"state\": \"OPEN\""),
            "an open conflict is named as open, which is what tells a reviewer the tier was asked and did "
                + "not decide: " + json);
    }

    /**
     * A block where both branches change one statement differently, so no detector decides it.
     *
     * <p>The counterweight to the fixture above: it is what an {@code OPEN} conflict looks like in a report,
     * and it is the case a settled conflict must not be confused with.
     */
    private static final String UNSETTLED_LINE = """
            package com.example.demo;

            public class OrderService {
            <<<<<<< ours
                private int retries = 5;
            ||||||| base
                private int retries = 0;
            =======
                private int retries = 7;
            >>>>>>> theirs
            }
            """;

    /**
     * A {@code diff3} block whose import addition is answered and whose class declaration is not mentioned by
     * that answer — because neither branch touched it.
     *
     * <p>This is the shape the partial dead end was about, with the base the composition needs. The class
     * declaration is inside the block and identical on both sides, so the answer legitimately does not carry
     * it, and replacing the block with the answer would have deleted it.
     */
    private static final String ANSWER_MISSES_UNTOUCHED_LINES = """
            package com.example.demo;

            <<<<<<< ours
            import java.math.BigDecimal;
            import java.util.List;

            class OrderService {
            ||||||| base
            import java.util.List;

            class OrderService {
            =======
            import java.time.Instant;
            import java.util.List;

            class OrderService {
            >>>>>>> theirs
            }
            """;

    @Test
    @DisplayName("an answer that misses untouched lines is composed with them instead of being refused")
    void untouchedLinesAreComposedBack() throws IOException {
        Path file = write("OrderService.java", ANSWER_MISSES_UNTOUCHED_LINES);

        Result result = toolFor(file).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.APPLIED_AUTO, outcome.outcome(), outcome.explanation());
        assertEquals(0, result.exitCode(), outcome.explanation());
        String applied = read(file);
        assertFalse(applied.contains("<<<<<<<"), applied);
        assertTrue(applied.contains("import java.math.BigDecimal;"), applied);
        assertTrue(applied.contains("import java.time.Instant;"), applied);
        assertTrue(applied.contains("class OrderService {"),
            "the line neither branch touched survives the composition: " + applied);
    }

    /**
     * One block with a settled stretch and a genuinely contested one: the imports are answered, the method body
     * is not.
     */
    private static final String SETTLED_AND_OPEN_IN_ONE_BLOCK = """
            package com.example.demo;

            <<<<<<< ours
            import java.math.BigDecimal;
            import java.util.List;

            class OrderService {
                void run() {
                    audit();
                }
            ||||||| base
            import java.util.List;

            class OrderService {
                void run() {
                }
            =======
            import java.time.Instant;
            import java.util.List;

            class OrderService {
                void run() {
                    charge();
                }
            >>>>>>> theirs
            }
            """;

    @Test
    @DisplayName("the settled stretch is applied beside the markers the open one keeps")
    void theSettledStretchIsAppliedBesideMarkers() throws IOException {
        Path file = write("Mixed.java", SETTLED_AND_OPEN_IN_ONE_BLOCK);

        Result result = toolFor(file).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.APPLIED_PARTIAL, outcome.outcome(), outcome.explanation());
        assertEquals(1, result.exitCode(),
            "the file still carries a conflict, so the exit status says so: " + outcome.explanation());

        String applied = read(file);
        assertTrue(applied.contains("<<<<<<<"), "the open stretch keeps its markers: " + applied);
        assertTrue(applied.contains("import java.math.BigDecimal;")
                && applied.contains("import java.time.Instant;"),
            "and the settled stretch was applied: " + applied);
        assertTrue(applied.contains("class OrderService {"),
            "the line neither branch touched survives: " + applied);
        assertTrue(applied.indexOf("import java.time.Instant;") < applied.indexOf("<<<<<<<"),
            "the applied stretch is written outside the markers, not inside them: " + applied);
        assertTrue(applied.indexOf("<<<<<<<") < applied.indexOf("audit();"),
            "and the contested stretch is inside them: " + applied);
    }

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private MergeFileTool.Builder toolFor(Path file) {
        return MergeFileTool.forFile(file)
            .fixtureRoot(tempDir.resolve("fixture-root"))
            .inMemoryOnly(true);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
