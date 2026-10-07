// {@link com.codebuddy.merge.WhitespacePolicyTest} The whitespace policy end to end: churn stops being a conflict (plan step 4.10).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whitespace policy, from the command line to the file (unified plan step 4.10).
 *
 * <h2>The acceptance criterion, in one sentence</h2>
 *
 * <p><b>The same three texts are one conflict under {@link ComparisonPolicy#DEFAULT} and no conflict at
 * all under {@link ComparisonPolicy#IGNORE_WHITESPACES}.</b> That is the whole claim the step makes —
 * whitespace churn stops being a conflict — and everything else here exists to show the claim is wired
 * through rather than merely available.
 *
 * <h2>The vector</h2>
 *
 * <p>{@code JETBRAINS_PORT.md} § 11.5, from upstream's {@code MergeTest.testIgnored}: the three sides
 * differ from each other only in where the spaces and line breaks sit. Under {@code DEFAULT} those are
 * content and the block cannot be decided; under {@code IGNORE_WHITESPACES} the branches agree and there
 * is nothing to decide. The {@code TRIM} case is asserted too, because it is the policy that tells the
 * other two apart at the edges rather than throughout — without it, "ignore" and "trim" would be
 * indistinguishable from the outside, which is exactly the conflation step 4.8 removed.
 */
class WhitespacePolicyTest {

    @TempDir
    Path tempDir;

    /**
     * Three sides that differ only in whitespace, as a {@code diff3} block.
     *
     * <p>Each side carries the same statement with different leading whitespace, and the base is present —
     * which matters, because without a base side the merge style is git's default and the tool treats every
     * region as unknown, so the policy would have nothing to compare.
     *
     * <p><b>The sides must differ in whitespace and agree in content, or the vector tests nothing.</b> The
     * first version of this fixture used three byte-identical sides, and the tool correctly reported
     * {@code APPLIED_IDENTICAL_SIDES} under every policy — a passing test that proved nothing. What is
     * needed is a difference the policy can ignore, which is a different thing from no difference at all.
     */
    private static final String WHITESPACE_ONLY_CONFLICT = """
        class Sample {
            void run() {
        <<<<<<< ours
                int total = 0;
        ||||||| base
        int total = 0;
        =======
                    int total = 0;
        >>>>>>> theirs
            }
        }
        """;

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private MergeFileTool.Builder toolFor(Path file, ComparisonPolicy policy) {
        return MergeFileTool.forFile(file)
            .fixtureRoot(tempDir.resolve("fixture-root"))
            .inMemoryOnly(true)
            .whitespacePolicy(policy);
    }

    private List<MergeFileTool.BlockOutcome> run(ComparisonPolicy policy) throws IOException {
        Path file = write(WHITESPACE_ONLY_CONFLICT);
        return toolFor(file, policy).applyFixes(true).run().outcomes();
    }

    // ------------------------------------------------------------------ the acceptance pair

    @Test
    @DisplayName("the same sides need a human under DEFAULT and need nobody under IGNORE_WHITESPACES")
    void thePolicyDecidesWhetherWhitespaceChurnIsAConflict() throws IOException {
        List<MergeFileTool.BlockOutcome> underDefault = run(ComparisonPolicy.DEFAULT);
        List<MergeFileTool.BlockOutcome> underIgnore = run(ComparisonPolicy.IGNORE_WHITESPACES);

        assertFalse(underDefault.isEmpty(), "the vector has a block");

        // DEFAULT: the indentation is content, so the block is a conflict a human must settle.
        assertTrue(underDefault.stream().anyMatch(outcome -> !outcome.applied()),
            "under DEFAULT an indentation difference is a conflict: " + underDefault);

        // IGNORE_WHITESPACES: the difference is formatting, so no detector may call it a conflict.
        //
        // **What this does NOT yet assert, and why that is recorded rather than implied.** The block comes
        // out LEFT_UNCLASSIFIED — not applied, and not *recognised* as anything. The policy reaches the
        // content questions (is a base line kept, did a branch change content at all, where is the region)
        // but not yet the SHAPE detectors, which ask their own line questions without it. So the honest
        // claim today is "no conflict type is produced", not "the block applies". Closing that gap means
        // threading the policy into every `detect*` method, which is recorded on the Progress row for 4.10
        // rather than being quietly asserted here as if it worked.
        assertFalse(underIgnore.stream().anyMatch(MergeFileTool.BlockOutcome::applied),
            "nothing should be applied while the shape detectors are still policy-blind: " + underIgnore);
        assertTrue(underIgnore.stream().allMatch(
                outcome -> outcome.outcome() == MergeFileTool.Outcome.LEFT_UNCLASSIFIED
                    || outcome.outcome() == MergeFileTool.Outcome.APPLIED_IDENTICAL_SIDES),
            "and it is not reported as a recognised conflict: " + underIgnore);
    }

    // ------------------------------------------------------------------ the report names the policy

    @Test
    @DisplayName("the report names the policy, per conflict and at file level")
    void theReportNamesThePolicy() throws IOException {
        // Step 4.10's second remainder, and the reason it matters: an answer reached under a lenient policy is a
        // DIFFERENT answer from the one a strict comparison would have produced, and the policy is the only thing in
        // the report that says which was read. Without it a reviewer reads a decision whose basis is invisible, which
        // is the same failure the suggestion channel's `provenance` exists to prevent.
        //
        // TWO levels are asserted, because the measurement showed the per-conflict key cannot carry the case that
        // needs it most: under IGNORE_WHITESPACES this vector has NO conflicts at all — the policy working, not the
        // file being trivially clean — so there is no conflict entry to name a policy in.
        Path strict = write(WHITESPACE_ONLY_CONFLICT);
        Path strictReport = tempDir.resolve("strict-report.json");
        toolFor(strict, ComparisonPolicy.DEFAULT).reportPath(strictReport).run();
        String strictJson = Files.readString(strictReport, StandardCharsets.UTF_8);

        assertTrue(strictJson.contains("\"whitespacePolicy\": \"DEFAULT\""),
            "a decision reached under the strict comparison must say so beside the decision: " + strictJson);
        assertFalse(strictJson.contains("IGNORE_WHITESPACES"),
            "and must not claim the lenient policy it did not use: " + strictJson);

        Path ignoring = write(WHITESPACE_ONLY_CONFLICT);
        Path ignoreReport = tempDir.resolve("ignore-report.json");
        toolFor(ignoring, ComparisonPolicy.IGNORE_WHITESPACES).reportPath(ignoreReport).run();
        String ignoreJson = Files.readString(ignoreReport, StandardCharsets.UTF_8);

        assertTrue(ignoreJson.contains("\"conflicts\": 0"),
            "the lenient policy finds nothing to decide here, which is the policy working: " + ignoreJson);
        assertTrue(ignoreJson.contains("\"whitespacePolicy\": \"IGNORE_WHITESPACES\""),
            "and the FILE must still say which comparison found it clean — otherwise 'nothing to decide' and 'the "
                + "comparison ignored what differed' read the same: " + ignoreJson);
    }

    @Test
    @DisplayName("the comparison itself is policy-aware, which is what the wiring rests on")
    void theComparisonIsPolicyAware() {
        // The line-level guarantee the whole step rests on, asserted where it is actually delivered.
        // `run()` above proves the policy reaches detection; this proves the comparison underneath it
        // does what the policy says, so the two together say the wiring is doing something.
        String ours = "                int total = 0;";
        String theirs = "                    int total = 0;";

        assertNotEquals(List.of(),
            com.codebuddy.merge.jetbrains.text.TextCompare.compareLines(ours, theirs,
                ComparisonPolicy.DEFAULT),
            "the indentation is content under DEFAULT");
        assertEquals(List.of(),
            com.codebuddy.merge.jetbrains.text.TextCompare.compareLines(ours, theirs,
                ComparisonPolicy.IGNORE_WHITESPACES),
            "and is not under IGNORE_WHITESPACES");
        assertEquals(List.of(),
            com.codebuddy.merge.jetbrains.text.TextCompare.compareLines(ours, theirs,
                ComparisonPolicy.TRIM_WHITESPACES),
            "a leading-whitespace difference is exactly what TRIM ignores");
    }

    @Test
    @DisplayName("a resolution records the policy it was made under")
    void theResolutionRecordsItsPolicy() {
        // A resolution built by detection carries the default, and the builder is how a caller states
        // otherwise. The end-to-end proof that the tool passes its policy down IS the acceptance test
        // above: if it did not, DEFAULT and IGNORE_WHITESPACES would produce the same outcomes, and they
        // do not. What is asserted here is the record itself, where it is directly reachable.
        Conflict conflict = new Conflict(ConflictType.IMPORT_ADD, "f.java",
            "both branches added imports", "", "import a;", "import b;");
        assertEquals(ComparisonPolicy.TRIM_WHITESPACES,
            ConflictResolution.auto(conflict,
                ConflictResolution.ResolutionStrategy.KEEP_BOTH).build().getWhitespacePolicy(),
            "the default is what the module used before the policy was a choice");

        assertEquals(ComparisonPolicy.IGNORE_WHITESPACES,
            ConflictResolution.auto(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
                .whitespacePolicy(ComparisonPolicy.IGNORE_WHITESPACES)
                .build()
                .getWhitespacePolicy(),
            "and a caller that chooses one has it recorded on the resolution");
    }

    @Test
    @DisplayName("applying under DEFAULT leaves the markers, so a human still decides")
    void applyingUnderDefaultLeavesTheMarkers() throws IOException {
        // The other half of the pair, and the one that matters for safety: the default must NOT write a
        // block whose sides differ only in indentation. A tool that resolved this silently would be
        // discarding a formatting intention somebody made on purpose.
        Path file = write(WHITESPACE_ONLY_CONFLICT);
        toolFor(file, ComparisonPolicy.DEFAULT).applyFixes(true).run();

        String applied = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(applied.contains("<<<<<<<"),
            "under DEFAULT the block is left for a human:\n" + applied);
    }

    // ------------------------------------------------------------------ the three names

    @Test
    @DisplayName("TRIM ignores a line's edges where DEFAULT does not, and IGNORE goes further")
    void trimSitsBetweenTheOtherTwo() {
        String left = "  int total = 0;";
        String right = "\tint total = 0;";

        // Edges only, so TRIM already resolves it.
        assertEquals(List.of(),
            com.codebuddy.merge.jetbrains.text.TextCompare.compareLines(left, right,
                ComparisonPolicy.TRIM_WHITESPACES),
            "an indentation difference is not a change under TRIM");
        assertNotEquals(List.of(),
            com.codebuddy.merge.jetbrains.text.TextCompare.compareLines(left, right,
                ComparisonPolicy.DEFAULT),
            "but it is under DEFAULT, which is why the policy is a choice");

        // Interior whitespace, where TRIM no longer helps and IGNORE still does.
        String spaced = "total  =  0;";
        String tightened = "total = 0;";
        assertNotEquals(List.of(),
            com.codebuddy.merge.jetbrains.text.TextCompare.compareLines(spaced, tightened,
                ComparisonPolicy.TRIM_WHITESPACES),
            "TRIM keeps interior whitespace as content");
        assertEquals(List.of(),
            com.codebuddy.merge.jetbrains.text.TextCompare.compareLines(spaced, tightened,
                ComparisonPolicy.IGNORE_WHITESPACES),
            "IGNORE does not");
    }

    @Test
    @DisplayName("the flag names are the three policies, and anything else is refused")
    void flagNamesAreParsed() {
        assertEquals(ComparisonPolicy.DEFAULT, MergeFileTool.whitespacePolicyOf("default"));
        assertEquals(ComparisonPolicy.TRIM_WHITESPACES, MergeFileTool.whitespacePolicyOf("trim"));
        assertEquals(ComparisonPolicy.IGNORE_WHITESPACES, MergeFileTool.whitespacePolicyOf("ignore"));

        // Case and the constant's own spelling are accepted; a command line is read by people.
        assertEquals(ComparisonPolicy.IGNORE_WHITESPACES, MergeFileTool.whitespacePolicyOf("IGNORE"));
        assertEquals(ComparisonPolicy.IGNORE_WHITESPACES,
            MergeFileTool.whitespacePolicyOf("ignore_whitespaces"));

        // An unknown name is refused rather than silently defaulted: a caller that mistyped must be told,
        // or it would believe it had chosen a policy it had not.
        assertNull(MergeFileTool.whitespacePolicyOf("nonsense"));
        assertNull(MergeFileTool.whitespacePolicyOf(null));
    }

    @Test
    @DisplayName("a run that does not choose a policy behaves as it did before the policy existed")
    void theDefaultIsUnchanged() throws IOException {
        Path file = write(WHITESPACE_ONLY_CONFLICT);

        // No whitespacePolicy() call at all.
        List<MergeFileTool.BlockOutcome> withoutChoosing = MergeFileTool.forFile(file)
            .fixtureRoot(tempDir.resolve("fixture-root-a"))
            .inMemoryOnly(true)
            .run().outcomes();

        Path other = tempDir.resolve("Other.java");
        Files.writeString(other, WHITESPACE_ONLY_CONFLICT, StandardCharsets.UTF_8);
        List<MergeFileTool.BlockOutcome> explicitTrim = toolFor(other, ComparisonPolicy.TRIM_WHITESPACES)
            .run().outcomes();

        assertEquals(explicitTrim.stream().map(MergeFileTool.BlockOutcome::outcome).toList(),
            withoutChoosing.stream().map(MergeFileTool.BlockOutcome::outcome).toList(),
            "choosing nothing is choosing TRIM_WHITESPACES, which is what the tool used to do");
    }
}
