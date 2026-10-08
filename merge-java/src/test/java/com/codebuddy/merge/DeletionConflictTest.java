// {@link com.codebuddy.merge.DeletionConflictTest} Section 11.4 at the level this module has (plan 4.13).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

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
 * § 11.4 at the level <b>this</b> module has it: one branch removed the content, the other kept or changed it
 * (unified plan step 4.13, {@code JETBRAINS_PORT.md} § 11.4).
 *
 * <h2>Why this fixture and not upstream's</h2>
 *
 * <p>Upstream's two rows are their <b>file</b>-level conflict type — {@code DELETED_MODIFIED} /
 * {@code MODIFIED_DELETED}, "one branch deleted the file and the other modified it" — and this module has no
 * file-level conflict type at all. What it has is conflict <b>blocks</b>, so the rule has to be stated where a block
 * can express it: a block in which one side's content is <b>gone</b>. Three files cannot express a file that is not
 * there; a marker block can express content that is.
 *
 * <h2>The control is the whole point, again</h2>
 *
 * <p>A refusal is only evidence if the same shape of asymmetry <em>without</em> the deletion is applied.
 * {@link #theControlTheSameAsymmetryWithoutADeletionIsApplied()} is that half: ours unchanged, theirs changed — the
 * same one-sided asymmetry — and it must be applied, or the first assertion proves nothing about the deletion and
 * everything about the fixture.
 *
 * <h2>The claim being tested</h2>
 *
 * <p>{@code DESIGN_NEVER_AUTO_RESOLVED.md} § 2 splits additive from <b>substitutive</b> changes, and a deletion is
 * the substitutive case: applying it means <em>removing code a person may not have meant to remove</em>, which is
 * the invisible regression the whole design exists to prevent. So a block whose content one side deleted must not be
 * applied silently. The assertion is about the outcome, not about which class refused it.
 */
class DeletionConflictTest {

    @TempDir
    Path tempDir;

    /** A block whose OURS side removed the member the base had, while theirs kept it. */
    private static final String OURS_DELETED = """
        package com.example.demo;

        public class OrderService {

            public void run() {
        <<<<<<< ours
        ||||||| base
                total = computeTotal();
        =======
                total = computeTotal();
        >>>>>>> theirs
            }
        }
        """;

    /** The control: the same asymmetry with no deletion — ours unchanged, theirs adding a statement. */
    private static final String OURS_UNCHANGED = """
        package com.example.demo;

        public class OrderService {

            public void run() {
        <<<<<<< ours
                total = computeTotal();
        ||||||| base
                total = computeTotal();
        =======
                total = computeTotal();
                audit(total);
        >>>>>>> theirs
            }
        }
        """;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private MergeFileTool.Builder toolFor(Path file) {
        return MergeFileTool.forFile(file)
            .fixtureRoot(tempDir.resolve("fixture-root"))
            .inMemoryOnly(true);
    }

    @Test
    @DisplayName("a block one side deleted is not applied silently, and says why")
    void aDeletionIsNotAppliedSilently() throws IOException {
        Path file = write("OursDeleted.java", OURS_DELETED);

        MergeFileTool.Result result = toolFor(file).applyFixes(true).run();

        assertFalse(result.outcomes().isEmpty(), "the block must be reported at all");
        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertTrue(read(file).contains("<<<<<<<"),
            "a substitutive change must survive the run for a person to see: " + read(file));
        assertTrue(outcome.outcome() != MergeFileTool.Outcome.APPLIED_AUTO
                && outcome.outcome() != MergeFileTool.Outcome.APPLIED_PARTIAL,
            "and it must not be reported as applied: " + outcome.outcome() + " - " + outcome.explanation());
        assertEquals(1, result.exitCode(),
            "the run reports that something is unresolved rather than exiting cleanly");
    }

    @Test
    @DisplayName("the control: the same asymmetry without a deletion, and the policy that decides it")
    void theControlTheSameAsymmetryWithoutADeletionIsApplied() throws IOException {
        // Upstream's control row says the same one-sided change DOES auto-resolve once the type is not a
        // modify/delete conflict. Ours does not, and the reason is a DECLARED policy rather than a text comparison:
        // `ConflictType.STRUCTURAL_CHANGE` carries `Handling.MANUAL`, so a structural change goes to a person by the
        // taxonomy's own rule. The shape, computed from the same input, says the instance is one-sided — ours did not
        // change and theirs added a statement — which is the mechanical case steps 4.5/4.6 measured and step 4.12
        // deliberately left undecided ("the decision does not yet use the shape").
        //
        // So this test asserts the DIVERGENCE and the evidence for it, not a behaviour nobody has chosen: whether the
        // shape may override a type's declared handling is the open decision, and the moment it is taken this
        // assertion is what changes. Writing it as "must be applied" would have been asserting a decision into
        // existence, which is how a test suite starts lying about what the tool does.
        Path file = write("OursUnchanged.java", OURS_UNCHANGED);

        MergeFileTool.Result result = toolFor(file).applyFixes(true).run();

        assertFalse(result.outcomes().isEmpty());
        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertTrue(read(file).contains("<<<<<<<"),
            "today the block survives; the markers are the policy, not an accident: " + read(file));
        // MEASURED, and it is further from the benchmark than the plan records: the block is not merely escalated,
        // it is **unclassified** — no domain type claims it at all. So the gap steps 4.5/4.6 measured ("LEFT_MANUAL
        // for an edit that needs no decision") is really two gaps: nothing types an additive one-sided change inside
        // a method, and nothing resolves it. That is evidence for step 4.12's open decision being about
        // CLASSIFICATION as much as about resolution.
        assertEquals(MergeFileTool.Outcome.LEFT_UNCLASSIFIED, outcome.outcome(),
            "measured today: no type claims a block where only one side changed: " + outcome.outcome()
                + " / type " + outcome.type());
        assertTrue(outcome.type() == null || outcome.type() == ConflictType.STRUCTURAL_CHANGE
                || outcome.type() == ConflictType.UNCLASSIFIED_TEXT,
            "whatever it is typed as, it is not resolved — and since plan step 4.19's decision the unclassified case"
                + " carries its own name rather than a null: " + outcome.type());

        // The evidence, from the same input: the change is one-sided, which is what makes the divergence a decision
        // to take rather than a mystery. Both sides are read from the file's block, so the shape is computed on
        // exactly what the tool saw.
        ConflictShape shape = ConflictShape.of(baseOf(OURS_UNCHANGED), oursOf(OURS_UNCHANGED),
            theirsOf(OURS_UNCHANGED), com.codebuddy.merge.jetbrains.text.ComparisonPolicy.DEFAULT, true);
        assertEquals(ConflictShape.INSERTED, shape,
            "ours is unchanged and theirs added a line: an insertion, which nothing in the taxonomy claims");

        System.out.println("CONTROL-DIVERGENCE: the block is a one-sided " + shape
            + ", and the tool reports " + outcome.outcome() + " with type " + outcome.type()
            + " (steps 4.5/4.6 measured LEFT_MANUAL; the measured truth is weaker still, and step 4.12's decision"
            + " is about classification as well as resolution)");
    }

    /** The marker sections of a fixture, so the shape is computed from the same text the tool read. */
    private static String section(String markerFile, String start, String end) {
        int from = markerFile.indexOf(start);
        int to = markerFile.indexOf(end, from + start.length());
        return markerFile.substring(from + start.length(), to).stripIndent().strip() + "\n";
    }

    private static String oursOf(String markerFile) {
        return section(markerFile, "<<<<<<< ours\n", "||||||| base");
    }

    private static String baseOf(String markerFile) {
        return section(markerFile, "||||||| base\n", "=======");
    }

    private static String theirsOf(String markerFile) {
        return section(markerFile, "=======\n", ">>>>>>> theirs");
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
