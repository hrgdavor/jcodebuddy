// {@link com.codebuddy.merge.jetbrains.merge.MergeResolveTest} Holds the simple resolve pass to upstream's expectations, and pins its refusals.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge.jetbrains.merge;

import com.codebuddy.merge.ConflictShape;
import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simple resolve pass against upstream's expectations (unified plan step 4.9).
 *
 * <h2>Where the vectors come from</h2>
 *
 * <p>{@code MergeTest.testResolve} in
 * {@code platform/diff-impl/tests/testSrc/com/intellij/diff/merge/MergeTest.kt}, whose DSL writes
 * {@code "y z_Y_x y"} for four lines and asserts the resolved content — not just a verdict, which is why
 * these vectors are worth having: "resolved something" is not parity.
 *
 * <h2>The refusals are the point of this step</h2>
 *
 * <p>Half of these vectors assert that the pass returns <b>nothing</b>. Two edits to the same lines are two
 * intentions, and no comparison of text contains the information needed to choose between them; a pass that
 * answered anyway would be inventing a decision. {@code DESIGN_NEVER_AUTO_RESOLVED.md} is not relaxed by
 * this step, and {@link #refusesWhenBothSidesChangeTheSameRegionDifferently()} is where that is asserted
 * rather than promised.
 */
class MergeResolveTest {

    /** A text written the way upstream's DSL writes it: {@code _} is a line terminator. */
    private static String text(String dsl) {
        return dsl.replace("_", "\n");
    }

    private static MergeResolve.Result resolve(String left, String base, String right) {
        return MergeResolve.resolve(text(left), text(base), text(right));
    }

    // ------------------------------------------------------------------ the resolving vectors

    @Test
    @DisplayName("both sides deleting the SAME lines is not a disagreement, but the pass must not guess")
    void bothSidesDeletingAroundAWordComposes() {
        // up: left "y z", base "x y z", right "x y" -> "y". Upstream's SimpleHelper resolves this because it
        // compares at the WORD level, where the surviving "y" is one word and the deletions around it are separate
        // fragments.
        //
        // **This pass resolves it now, and the test changed with the capability** — which is what the old comment
        // promised it would be: a change with a test to update rather than an accident. Step 4.11's remainder landed
        // the word-level half (WordLevelMerge, in this tier because it is ported machinery), and this vector is its
        // acceptance case. The refusal has not gone away, it has moved one granularity down: two different words
        // inserted at one point still refuse.
        MergeResolve.Result result = resolve("y z", "x y z", "x y");
        assertFalse(result.refused(), "the word-level half composes this, as the benchmark does");
        assertEquals("y", result.mergedText().strip(),
            "and to the benchmark's own answer: the word both sides kept");
    }

    @Test
    @DisplayName("two independent conflicts compose at word level, one line at a time")
    void twoIndependentConflictsComposeAtWordLevel() {
        // up: left "y z_Y_x y", base "x y z_Y_x y z", right "x y_Y_y z" -> "y_Y_y". Two lines, each the case
        // above: the answer is a word-level composition, and the pass reaches it now — for BOTH lines, which is what
        // makes this vector worth keeping as well as the one-line case. Composing only the first line would leave the
        // result one line short, and the assertion is on the whole text for that reason.
        MergeResolve.Result result = resolve("y z_Y_x y", "x y z_Y_x y z", "x y_Y_y z");
        assertFalse(result.refused(), "the word-level half composes this too");
        // Stripped, so the assertion is about the composed text rather than about which terminator the last line
        // happened to keep: the benchmark states content, and the `_` in its notation is a separator. THREE lines,
        // not two — `y_Y_y` is "y", "Y", "y", and the middle one is the line neither side touched, so it must
        // survive between the two composed ones. (The assertion that got this wrong was mine, not the pass's: it
        // expected two lines and the pass produced the untouched Y where the benchmark puts it.)
        assertEquals("y\nY\ny", result.mergedText().strip(),
            "the benchmark's own answer for this vector, both lines resolved and the untouched one kept");
    }

    @Test
    @DisplayName("one unchanged run is taken from the base when only the other side changed")
    void onlyOneSideChanges() {
        // Right is untouched, so the answer is the left text.
        MergeResolve.Result result = resolve("a_X_c", "a_b_c", "a_b_c");
        assertFalse(result.refused());
        assertEquals(text("a_X_c"), result.mergedText());

        // And symmetrically.
        MergeResolve.Result other = resolve("a_b_c", "a_b_c", "a_Y_c");
        assertFalse(other.refused());
        assertEquals(text("a_Y_c"), other.mergedText());
    }

    @Test
    @DisplayName("an untouched base resolves to itself and says so")
    void untouchedBase() {
        MergeResolve.Result result = resolve("a_b_c", "a_b_c", "a_b_c");
        assertFalse(result.refused());
        assertEquals(text("a_b_c"), result.mergedText());
        assertTrue(result.byteEqual(), "an untouched base is byte-identical to itself");
    }

    @Test
    @DisplayName("both sides making the same change resolves to that change")
    void bothSidesMakeTheSameChange() {
        MergeResolve.Result result = resolve("a_X_c", "a_b_c", "a_X_c");
        assertFalse(result.refused());
        assertEquals(text("a_X_c"), result.mergedText());
        assertFalse(result.byteEqual(), "the result is not the base");
        assertTrue(result.resolved(), "and the caller can say so without negating refused()");
    }

    // ------------------------------------------------------------------ the refusing vectors

    @Test
    @DisplayName("both sides changing the same region differently is refused")
    void refusesWhenBothSidesChangeTheSameRegionDifferently() {
        // up: ("x" - "y" - "z") is a CONFLICT, and tryResolve returns null for it.
        MergeResolve.Result result = resolve("x", "y", "z");
        assertTrue(result.refused(), "one line, two different edits: nothing decides this");
        assertNull(result.mergedText());
    }

    @Test
    @DisplayName("two different insertions at one point are refused")
    void refusesTwoDifferentInsertions() {
        // The base has nothing here and the two sides insert different content. Neither order is more
        // correct, which is upstream's own stated reason for refusing (MergeResolveUtil.kt:44).
        MergeResolve.Result result = resolve("x_Y", "Y", "z_Y");
        assertTrue(result.refused(), "two different insertions have no correct order");
    }

    @Test
    @DisplayName("a deletion competing with an edit is refused")
    void refusesDeletionAgainstEdit() {
        // "deleted-inserted conflicts can be resolved by applying both of them" upstream says of its own
        // greedy pass — but that is the pass this step classifies as a suggestion, not an automatic
        // answer. Here a side that removed the lines is a different intention from one that edited them.
        MergeResolve.Result result = resolve("", "A_B_C", "A_X_C");
        assertTrue(result.refused() || !result.mergedText().contains("B"),
            "a deletion competing with an edit must not silently resurrect or drop the line: "
                + result.mergedText());
    }

    // ------------------------------------------------------------------ the policy, recorded

    @Test
    @DisplayName("the policy that produced a result travels with it")
    void thePolicyIsRecorded() {
        MergeResolve.Result defaultPolicy = MergeResolve.resolve("a_X_c", "a_b_c", "a_b_c",
            ComparisonPolicy.DEFAULT);
        assertEquals(ComparisonPolicy.DEFAULT, defaultPolicy.policy());

        MergeResolve.Result ignoring = MergeResolve.resolve("a_X_c", "a_b_c", "a_b_c",
            ComparisonPolicy.IGNORE_WHITESPACES);
        assertEquals(ComparisonPolicy.IGNORE_WHITESPACES, ignoring.policy(),
            "a caller that writes this result must be able to say what its basis was");
    }

    @Test
    @DisplayName("a formatting-only difference is not a conflict under a whitespace policy")
    void formattingOnlyDifferenceIsNotAConflict() {
        // Under DEFAULT the indentation is content, so the two sides disagree.
        assertTrue(MergeResolve.resolve(text("  a_b"), text("a_b"), text("\ta_b"),
            ComparisonPolicy.DEFAULT).refused(), "DEFAULT sees two different edits");

        // Under IGNORE_WHITESPACES they are the same edit, so there is nothing to decide.
        MergeResolve.Result ignoring = MergeResolve.resolve(text("  a_b"), text("a_b"), text("\ta_b"),
            ComparisonPolicy.IGNORE_WHITESPACES);
        assertFalse(ignoring.refused(), "whitespace churn is not a disagreement under this policy");
        assertNotNull(ignoring.mergedText());
    }

    // ------------------------------------------------------------------ the ranges the pass walks

    @Test
    @DisplayName("a range is built for a change on one side only, and for both")
    void rangesDescribeWhichSideChanged() {
        // Only the right changed: one range - and the left's KEPT line is inside it.
        //
        // This assertion used to read `leftIsEmpty()`, and that was the defect rather than a description of one: a
        // range's side extent is the lines that side HAS over the base extent, so a side that did not change has the
        // base's line there. Recording only the side's *changed* lines made "the left kept `b`" and "the left
        // deleted `b`" the same range, which is how the benchmark's `x_Y | x_z_Y | z_Y` came to be applied as `Y`,
        // dropping the line each branch kept. The type is asserted too, because that is what the range is for.
        List<MergeRangeBuilder.MergeChange> onlyRight = MergeRangeBuilder.build(
            text("a_b_c"), text("a_b_c"), text("a_X_c"), ComparisonPolicy.DEFAULT);
        assertEquals(1, onlyRight.size(), "one change, one range: " + onlyRight);
        assertFalse(onlyRight.get(0).leftChanged(), "the left side did not change");
        assertTrue(onlyRight.get(0).rightChanged());
        assertEquals(1, onlyRight.get(0).range().length1(),
            "the left kept its line, so the range holds it: " + onlyRight.get(0));
        assertFalse(onlyRight.get(0).range().leftIsEmpty(),
            "`empty` must mean the side has nothing here, not that it changed nothing");
        assertEquals(List.of(MergeType.modified(false, true)),
            ConflictShape.typesOf(text("a_b_c"), text("a_b_c"), text("a_X_c"), ComparisonPolicy.DEFAULT),
            "the right modified the line and the left did not: not a conflict");

        // Only the left changed, symmetrically.
        List<MergeRangeBuilder.MergeChange> onlyLeft = MergeRangeBuilder.build(
            text("a_b_c"), text("a_X_c"), text("a_b_c"), ComparisonPolicy.DEFAULT);
        assertEquals(1, onlyLeft.size(), "one change, one range: " + onlyLeft);
        assertTrue(onlyLeft.get(0).leftChanged());
        assertFalse(onlyLeft.get(0).rightChanged());

        // Both changed the same region, so it is ONE range holding both sides — the case the builder
        // exists for. Two ranges here would let a later step decide about half a disagreement.
        List<MergeRangeBuilder.MergeChange> both = MergeRangeBuilder.build(
            text("a_b_c"), text("a_X_c"), text("a_Y_c"), ComparisonPolicy.DEFAULT);
        assertEquals(1, both.size(), "both sides' changes are one range: " + both);
        assertTrue(both.get(0).leftChanged() && both.get(0).rightChanged());
        assertEquals(1, both.get(0).range().length2(),
            "the base range is the line both changed: " + both.get(0));
    }

    @Test
    @DisplayName("an unchanged text produces no ranges at all")
    void noRangesWhenNothingChanged() {
        assertEquals(List.of(), MergeRangeBuilder.build(text("a_b_c"), text("a_b_c"), text("a_b_c"),
            ComparisonPolicy.DEFAULT));
    }

    @Test
    @DisplayName("the ranges are in order and none is empty")
    void rangesAreOrderedAndNonEmpty() {
        List<MergeRangeBuilder.MergeChange> ranges = MergeRangeBuilder.build(
            text("a_b_c_d_e"), text("a_X_c_d_e"), text("a_b_c_Y_e"), ComparisonPolicy.DEFAULT);
        assertFalse(ranges.isEmpty(), "there are two changes here");
        for (int i = 1; i < ranges.size(); i++) {
            MergeRange previous = ranges.get(i - 1).range();
            MergeRange current = ranges.get(i).range();
            assertTrue(previous.end1() <= current.start1(), "left ranges overlap: " + ranges);
            assertTrue(previous.end2() <= current.start2(), "base ranges overlap: " + ranges);
            assertTrue(previous.end3() <= current.start3(), "right ranges overlap: " + ranges);
        }
        for (MergeRangeBuilder.MergeChange change : ranges) {
            MergeRange range = change.range();
            assertTrue(range.length1() > 0 || range.length2() > 0 || range.length3() > 0,
                "an empty range is not a change: " + range);
        }
    }

    @Test
    @DisplayName("the modify/delete shape is named, and a one-sided insertion is not it")
    void modifyDeleteShapeIsNamed() {
        // The left removed the base's lines and the right edited them: check C2, and the same rule as
        // DESIGN_NEVER_AUTO_RESOLVED.md section 2's substitutive/additive split.
        MergeRange leftDeleted = new MergeRange(0, 0, 0, 1, 0, 1);
        assertEquals(MergeRangeUtil.DeletedSide.LEFT, MergeRangeUtil.modifyDeleteShape(leftDeleted),
            "the left has nothing where the base had a line");

        MergeRange rightDeleted = new MergeRange(0, 1, 0, 1, 0, 0);
        assertEquals(MergeRangeUtil.DeletedSide.RIGHT, MergeRangeUtil.modifyDeleteShape(rightDeleted));

        // THE CONTROL, and the reason the guard asks about the base rather than only about emptiness: an
        // insertion by one side has the same emptiness pattern as a deletion by the other. A guard that
        // could not tell them apart would resolve a deletion as an addition.
        MergeRange insertion = new MergeRange(0, 0, 0, 0, 0, 1);
        assertNull(MergeRangeUtil.modifyDeleteShape(insertion),
            "the base had nothing here, so an empty left side is an insertion, not a deletion");

        // Both sides deleted the same lines: they agree, so it is not a modify/delete conflict.
        assertNull(MergeRangeUtil.modifyDeleteShape(new MergeRange(0, 0, 0, 1, 0, 0)));

        // Neither side is empty: an ordinary modification, about which this guard has no opinion.
        assertNull(MergeRangeUtil.modifyDeleteShape(new MergeRange(0, 1, 0, 1, 0, 1)));
    }

    @Test
    @DisplayName("a deletion competing with an edit is refused by the pass as well as by the guard")
    void deletionAgainstEditIsRefused() {
        // The guard and the pass must agree. If the pass resolved this the guard would be documentation
        // rather than a rule; if the guard refused what the pass resolved, one of the two is wrong.
        MergeResolve.Result result = resolve("", "A_B_C", "A_X_C");
        assertTrue(result.refused(),
            "one branch removed the lines and the other edited them: nothing decides it");
    }
}
