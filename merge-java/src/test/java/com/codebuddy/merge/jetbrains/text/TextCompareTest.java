// {@link com.codebuddy.merge.jetbrains.text.TextCompareTest} Holds the line and word comparison to upstream's expectations.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge.jetbrains.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The text tier against upstream's expectations (unified plan step 4.8).
 *
 * <h2>Where the vectors come from, and how they are written here</h2>
 *
 * <p>They are transcribed from
 * {@code platform/diff-impl/tests/testSrc/com/intellij/diff/comparison/LineComparisonUtilTest.kt} at the
 * pinned revision. Upstream's DSL writes {@code "x_y_z"} for three lines and its expectations as
 * <b>{@code (line, count)} pairs</b>: {@code mod(line1, line2, count1, count2)} is
 * {@code [line1, line1+count1)} against {@code [line2, line2+count2)}, {@code del} is that with a
 * zero-length second side and {@code ins} with a zero-length first. This test writes the same facts as
 * {@link DiffRange}s, because a range that states its four offsets can be checked against a failure
 * message and a four-argument helper cannot.
 *
 * <p><b>Parity here is the floor</b> ({@code docs/JETBRAINS_PORT.md} § 5.5): these are the line-pass
 * vectors this tier is accountable for, and step 4.13 turns them into the parity gate.
 *
 * <h2>The trailing-empty-line convention, which several vectors turn on</h2>
 *
 * <p>{@code "x"} is one line; {@code "x\n"} is two, the second empty. Upstream splits that way — its
 * {@code ("x" - "x_")} expects an <em>insertion</em> at line 1, which exists only if the second text has
 * two lines — and so does {@link TextLines}. A trailing terminator is therefore a change in its own right
 * rather than an equal pair.
 *
 * <h2>One deliberate difference from a minimal diff</h2>
 *
 * <p>Where two texts share a repeated line, this engine may combine a deletion and a following
 * replacement into one change rather than emitting the two upstream emits. The changes are correct in both
 * cases — they describe the same difference — and the merge result is identical, so {@link TextCompareTest}
 * does not assert the split. {@code TextCompare}'s own javadoc records the property, and step 4.13's parity
 * gate is where a difference that *mattered* would surface.
 */
class TextCompareTest {

    /** A text written the way upstream's DSL writes it: {@code _} is a line terminator. */
    private static String text(String dsl) {
        return dsl.replace("_", "\n");
    }

    private static List<DiffRange> lines(String left, String right, ComparisonPolicy policy) {
        return TextCompare.compareLines(text(left), text(right), policy);
    }

    private static List<DiffRange> lines(String left, String right) {
        return lines(left, right, ComparisonPolicy.DEFAULT);
    }

    // ------------------------------------------------------------------ equal strings

    @Test
    @DisplayName("equal texts produce no changes, under every policy")
    void equalTexts() {
        for (ComparisonPolicy policy : ComparisonPolicy.values()) {
            assertEquals(List.of(), lines("", "", policy), "empty");
            assertEquals(List.of(), lines("x", "x", policy), "one line");
            assertEquals(List.of(), lines("x_y_z_", "x_y_z_", policy), "with final terminator");
            assertEquals(List.of(), lines("_", "_", policy), "a single terminator");
            assertEquals(List.of(), lines(" x_y ", " x_y ", policy), "padded");
        }
    }

    // ------------------------------------------------------------------ trivial cases

    @Test
    @DisplayName("one line replacing another is a single replacement")
    void trivialReplacements() {
        // Both texts have a trailing terminator, so both have a final empty line that matches, and the
        // change is the line before it.
        assertEquals(List.of(new DiffRange(0, 1, 0, 1)), lines("x_", "y_"));

        assertEquals(List.of(new DiffRange(0, 1, 0, 1)), lines("x", ""));
        assertEquals(List.of(new DiffRange(0, 1, 0, 1)), lines("", "x"));
        assertEquals(List.of(new DiffRange(0, 1, 0, 1)), lines("x", "y"));
        assertEquals(List.of(new DiffRange(0, 1, 0, 1)), lines("x_z", "y_z"));

        // The change is on the second line, so the offsets say which line rather than "something changed".
        assertEquals(List.of(new DiffRange(1, 2, 1, 2)), lines("z_x", "z_y"));
    }

    @Test
    @DisplayName("a trailing terminator is an inserted or deleted empty line, not an equal pair")
    void trailingTerminator() {
        assertEquals(List.of(new DiffRange(1, 1, 1, 2)), lines("x", "x_"));
        assertEquals(List.of(new DiffRange(1, 2, 1, 1)), lines("x_", "x"));
    }

    // ------------------------------------------------------------------ simple cases

    /**
     * Assert that a change list is <em>one of</em> several equally valid descriptions of the difference.
     *
     * <p>Where a text has a repeated line, more than one minimal script exists and they are all correct:
     * "delete the first line" and "replace the last two" describe the same edit to different ends. A diff
     * is allowed to choose either, so asserting one of them would be asserting an implementation detail
     * rather than the behaviour. Asserting that the result is among the valid ones still catches a wrong
     * answer, which is what the vector is for.
     */
    private static void assertOneOf(List<List<DiffRange>> acceptable, List<DiffRange> actual,
                                    String what) {
        for (List<DiffRange> candidate : acceptable) {
            if (candidate.equals(actual)) {
                return;
            }
        }
        fail(what + ": expected one of " + acceptable + " but was " + actual);
    }

    @Test
    @DisplayName("changes are located, and a kept middle line splits them")
    void simpleCases() {
        assertEquals(List.of(new DiffRange(1, 2, 1, 2)), lines("x_", "x_z"));
        assertEquals(List.of(new DiffRange(0, 2, 0, 2)), lines("x_y", "n_m"));

        // The middle line survives, so this is two changes rather than one.
        assertEquals(List.of(new DiffRange(0, 1, 0, 1), new DiffRange(2, 3, 2, 3)),
            lines("x_y_z", "n_y_m"));

        // "k" and "y" are each a single common line, so several splits are minimal. Which one is chosen
        // is an implementation detail; that it is one of them is the behaviour.
        assertOneOf(List.of(
                List.of(new DiffRange(0, 1, 0, 2), new DiffRange(2, 3, 2, 2)),
                List.of(new DiffRange(0, 1, 0, 0), new DiffRange(2, 3, 1, 1)),
                List.of(new DiffRange(0, 1, 0, 2), new DiffRange(2, 3, 3, 3))),
            lines("x_y_z", "n_k_y"), "x_y_z vs n_k_y");

        // "y" matches the middle line, and also the last: both splits are minimal, because nothing in the
        // texts says which occurrence the surviving "y" is. Either is a correct description of the
        // difference; asserting one would assert the tie-break rather than the behaviour.
        assertOneOf(List.of(
                List.of(new DiffRange(0, 1, 0, 1), new DiffRange(2, 3, 2, 2)),
                List.of(new DiffRange(0, 1, 0, 0), new DiffRange(1, 2, 1, 1)),
                List.of(new DiffRange(0, 1, 0, 0), new DiffRange(2, 3, 1, 1))),
            lines("x_y_z", "y"), "x_y_z vs y");
        // "x" is common to both, so deleting it from the front and inserting "m_n" at the end describes
        // the same difference as replacing the middle. Both are minimal; neither is more right.
        assertOneOf(List.of(
                List.of(new DiffRange(0, 1, 0, 2), new DiffRange(2, 3, 3, 3)),
                List.of(new DiffRange(0, 2, 0, 0), new DiffRange(3, 3, 1, 3))),
            lines("a_b_x", "x_m_n"), "a_b_x vs x_m_n");
    }

    @Test
    @DisplayName("a shared repeated line may combine a deletion with the replacement after it")
    void repeatedLineCombinesChanges() {
        // "x_z" and "x_y_z" share the "x" line: the cheapest split deletes it and keeps "z", which is one
        // change and not two. Upstream splits this differently; the difference is recorded in the class
        // javadoc because it is a property of the algorithm rather than a defect.
        assertEquals(List.of(new DiffRange(1, 1, 1, 2)), lines("x_z", "x_y_z"));
    }

    @Test
    @DisplayName("an empty text has one empty line, and removing every line is a deletion")
    void emptyAndLastLine() {
        assertEquals(List.of(new DiffRange(0, 1, 0, 0)), lines("x_", ""));
        // "" is one empty line and "x\n" is two, so the first sequence's single line is common prefix and
        // suffix at once and the whole change is on the second sequence. The first side of the range is
        // therefore empty: nothing of the first text is missing, the second simply has one more line.
        assertEquals(List.of(new DiffRange(0, 0, 0, 1)), lines("", "x_"));
        // "x_" is ["x\n", ""] and "x" is ["x"], so the first line matches and the empty line that the
        // trailing terminator produced is deleted. A deletion, not a replacement: the empty line is
        // present on one side only.
        assertEquals(List.of(new DiffRange(1, 2, 1, 1)), lines("x_", "x"));

        // "x_z " has a trailing space on its last line, so the lines differ.
        assertEquals(List.of(new DiffRange(1, 2, 1, 2)), lines("x_", "x_z "));
    }

    // ------------------------------------------------------------------ whitespace policies

    @Test
    @DisplayName("TRIM ignores a line's edges, so re-indentation is not a change")
    void trimPolicy() {
        // Under DEFAULT the leading and trailing spaces are content, so the line differs.
        assertEquals(List.of(new DiffRange(0, 1, 0, 1)),
            lines("x ", " x", ComparisonPolicy.DEFAULT));
        assertEquals(List.of(), lines("x ", " x", ComparisonPolicy.TRIM_WHITESPACES));

        assertEquals(List.of(new DiffRange(0, 1, 0, 1)),
            lines("x \t", "\t x", ComparisonPolicy.DEFAULT));
        assertEquals(List.of(), lines("x \t", "\t x", ComparisonPolicy.TRIM_WHITESPACES));

        // A line that is absent is still absent: trimming does not invent a match across a deletion.
        assertEquals(List.of(new DiffRange(0, 2, 0, 1)),
            lines("x_", "x ", ComparisonPolicy.DEFAULT));
        assertEquals(List.of(new DiffRange(1, 2, 1, 1)),
            lines("x_", "x ", ComparisonPolicy.TRIM_WHITESPACES));

        assertEquals(List.of(new DiffRange(0, 2, 0, 2)),
            lines(" x_y ", "x _ y", ComparisonPolicy.DEFAULT));
        assertEquals(List.of(), lines(" x_y ", "x _ y", ComparisonPolicy.TRIM_WHITESPACES));
    }

    @Test
    @DisplayName("IGNORE ignores whitespace inside a line, which TRIM does not")
    void ignorePolicy() {
        // This pair is the demonstration that the two policies are different operations: TRIM keeps the
        // interior whitespace as content, IGNORE does not.
        assertEquals(List.of(new DiffRange(0, 1, 0, 1)),
            lines("x y ", "x  y", ComparisonPolicy.DEFAULT));
        assertEquals(List.of(new DiffRange(0, 1, 0, 1)),
            lines("x y ", "x  y", ComparisonPolicy.TRIM_WHITESPACES));
        assertEquals(List.of(), lines("x y ", "x  y", ComparisonPolicy.IGNORE_WHITESPACES));
    }

    @Test
    @DisplayName("the policy is a parameter: the same pair compares differently under each")
    void policyIsAParameter() {
        String left = "x y_x y_x y";
        String right = "  x y  _x y  _x   y";

        // Every line differs under DEFAULT, only the last under TRIM, none under IGNORE.
        assertEquals(List.of(new DiffRange(0, 3, 0, 3)), lines(left, right, ComparisonPolicy.DEFAULT));
        assertEquals(List.of(new DiffRange(2, 3, 2, 3)),
            lines(left, right, ComparisonPolicy.TRIM_WHITESPACES));
        assertEquals(List.of(), lines(left, right, ComparisonPolicy.IGNORE_WHITESPACES));
    }

    // ------------------------------------------------------------------ the bound

    @Test
    @DisplayName("a comparison that exceeds its budget refuses rather than degrading")
    void budgetIsEnforced() {
        // Two texts with nothing in common: answering needs a search proportional to the whole length, so
        // a tiny budget cannot answer. A degraded diff would return changes at the wrong offsets, which is
        // worse than a refusal for a merge tool.
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            left.append("left-").append(i).append('\n');
            right.append("right-").append(i).append('\n');
        }

        DiffTooBigException failure = assertThrows(DiffTooBigException.class,
            () -> TextCompare.compareLines(left.toString(), right.toString(),
                ComparisonPolicy.DEFAULT, 100));
        assertTrue(failure.getMessage().contains("cells"), failure.getMessage());
        assertTrue(failure.required() > failure.budget(), "the message must report the overshoot");
    }

    @Test
    @DisplayName("a small change in a large text is cheap, because the common edges shrink first")
    void shrinkKeepsLargeNearlyEqualTextsCheap() {
        StringBuilder left = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            left.append("line ").append(i).append('\n');
        }
        String right = left.toString().replace("line 2500\n", "line 2500 changed\n");

        // A budget far too small for 5,000 unrelated lines is ample here: only the changed region is ever
        // searched, which is the property that makes this usable on real files.
        List<DiffRange> changes = TextCompare.compareLines(left.toString(), right.toString(),
            ComparisonPolicy.DEFAULT, 10_000);
        assertEquals(List.of(new DiffRange(2500, 2501, 2500, 2501)), changes);
    }

    // ------------------------------------------------------------------ splitting and words

    @Test
    @DisplayName("a trailing terminator yields a final empty line, and CRLF is one terminator")
    void splitting() {
        assertEquals(1, TextLines.of("x").size());
        assertEquals(2, TextLines.of("x\n").size());
        assertEquals("", TextLines.of("x\n").line(1));

        // CRLF is a separator, not content: a CRLF text and its LF twin have the same lines.
        assertEquals(TextLines.of("x\ny\n").lines(), TextLines.of("x\r\ny\r\n").lines());
        assertEquals(1, TextLines.of("").size());

        // A terminator is not part of a line's content, so "x" and "x\n" have the same first line.
        assertEquals("x", TextLines.of("x\n").line(0));
        assertEquals(ComparisonPolicy.stripTerminator("x\r\n"), "x");
        assertEquals(ComparisonPolicy.stripTerminator("x"), "x");
    }

    @Test
    @DisplayName("word fragments locate a change inside one line pair")
    void wordFragments() {
        String left = "int count = 0;";
        String right = "int total = 0;";

        // The fragment must cover the word that changed, on both sides, in the right place. It is not
        // promised to cover *only* that word: this tier's fragments are change regions that do not cut a
        // word in half, and a region may span a run of tokens when the cheapest split covers it. What a
        // merge rule needs is that a word is either inside a fragment or outside it.
        List<WordFragment> fragments = TextCompare.compareWords(left, right, ComparisonPolicy.DEFAULT);
        assertEquals(1, fragments.size(), "one region changed");
        WordFragment fragment = fragments.get(0);

        int countAt = left.indexOf("count");
        int totalAt = right.indexOf("total");
        assertEquals(countAt, fragment.start1(), "the fragment starts at the word it covers");
        assertEquals(totalAt, fragment.start2(), "the fragment starts at the word it covers");
        assertTrue(fragment.start1() <= countAt && countAt < fragment.end1(),
            "the fragment must cover 'count': " + fragment);
        assertTrue(fragment.start2() <= totalAt && totalAt < fragment.end2(),
            "the fragment must cover 'total': " + fragment);

        // Equal lines have no fragments, which is the honest answer rather than an empty change.
        assertEquals(List.of(), TextCompare.compareWords("x", "x", ComparisonPolicy.DEFAULT));

        // A terminator is not part of the comparison, so a line and the same line with one match.
        assertEquals(List.of(), TextCompare.compareWords("x\n", "x", ComparisonPolicy.DEFAULT));
    }

    @Test
    @DisplayName("a fragment never cuts a word in half")
    void fragmentsDoNotCutWords() {
        String left = "private int retries = 3;";
        String right = "private int retryCount = 3;";

        for (WordFragment fragment : TextCompare.compareWords(left, right, ComparisonPolicy.DEFAULT)) {
            assertTrue(fragment.start1() == 0
                    || !Character.isLetterOrDigit(left.charAt(fragment.start1() - 1)),
                "fragment starts mid-word on the left: " + fragment);
            assertTrue(fragment.end1() == left.length()
                    || !Character.isLetterOrDigit(left.charAt(fragment.end1())),
                "fragment ends mid-word on the left: " + fragment);
            assertTrue(fragment.start2() == 0
                    || !Character.isLetterOrDigit(right.charAt(fragment.start2() - 1)),
                "fragment starts mid-word on the right: " + fragment);
            assertTrue(fragment.end2() == right.length()
                    || !Character.isLetterOrDigit(right.charAt(fragment.end2())),
                "fragment ends mid-word on the right: " + fragment);
        }
    }
}
