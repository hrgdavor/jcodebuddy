// {@link com.codebuddy.merge.jetbrains.merge.WordLevelMergeTest} Composing inside a line, and refusing to (plan 4.11, port 11.2).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge.jetbrains.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The word-level composition, on the benchmark's own resolve vectors (unified plan step 4.11's remainder,
 * {@code JETBRAINS_PORT.md} § 11.2).
 *
 * <h2>The three vectors, and which of them this answers</h2>
 *
 * <p>§ 11.2's first two vectors are single-line word compositions and are asserted here against the benchmark's
 * exact expected content — {@code y z | x y z | x y → y} and {@code y z | x y z | x y → y} again for the second line
 * of the two-line case. The third row is <b>not</b> asserted as an answer, and deliberately: it is upstream's
 * editor-state case ("left applied by hand, then right resolved"), where the current document is the input, and this
 * module has no already-resolved state to carry — a difference the gate records as a named exception rather than one
 * hidden by a fixture that happens to look like it.
 *
 * <h2>What is asserted besides the answers</h2>
 *
 * <p>The refusals, because they are what keep a composition from being a guess: two different words inserted at one
 * point (R6, one granularity down), and any side spanning more than one line — word composition across line
 * boundaries would have to decide where the line breaks belong, and that decision is not in the text.
 */
class WordLevelMergeTest {

    private static final ComparisonPolicy POLICY = ComparisonPolicy.DEFAULT;

    @Test
    @DisplayName("the benchmark's first vector: each side deleted a different word, so the kept word is the answer")
    void bothSidesDeletedAroundY() {
        Optional<String> composed = WordLevelMerge.compose("x y z\n", "y z\n", "x y\n", POLICY);

        assertTrue(composed.isPresent(), "the words both sides kept are in no disagreement");
        assertEquals("y\n", composed.get(),
            "the benchmark's expected content for `y z | x y z | x y` is `y`");
    }

    @Test
    @DisplayName("the benchmark's second vector: the same composition on each of two lines")
    void twoIndependentWordConflicts() {
        // `y z_Y_x y | x y z_Y_x y z | x y_Y_y z` -> `y_Y_y`: line 0 and line 1 are each the first vector, and the
        // pass must compose each on its own. Composing them as one range would be the range-grouping mistake, and
        // refusing either would leave the benchmark's answer unreachable.
        Optional<String> firstLine = WordLevelMerge.compose("x y z\n", "y z\n", "x y\n", POLICY);
        Optional<String> secondLine = WordLevelMerge.compose("x y z\n", "x y\n", "y z\n", POLICY);

        assertTrue(firstLine.isPresent());
        assertTrue(secondLine.isPresent());
        assertEquals("y\n", firstLine.get());
        assertEquals("y\n", secondLine.get(), "the second line's two deletions mirror the first's");
    }

    @Test
    @DisplayName("one side editing a word takes that side's words, and the untouched words survive in order")
    void oneSidedWordEditKeepsEverythingElse() {
        Optional<String> composed = WordLevelMerge.compose(
            "public void audit(String account) {\n",
            "public void audit(String account) {\n",
            "public void audit(String ledger) {\n", POLICY);

        assertTrue(composed.isPresent());
        assertEquals("public void audit(String ledger) {\n", composed.get(),
            "only the word that changed differs, so the line is theirs with every other word in place");
    }

    @Test
    @DisplayName("two different words inserted at one point refuse: an order would be invented")
    void differingWordInsertionsRefuse() {
        // R6 one granularity down, and it is MORE common here than at line level, not less. A pass that guessed
        // would be writing text neither branch wrote.
        Optional<String> composed = WordLevelMerge.compose(
            "int total = 0;\n", "int total = computeTotal();\n", "int total = computeSurplus();\n", POLICY);

        assertTrue(composed.isEmpty(), "no order is more correct, so none is chosen");
    }

    @Test
    @DisplayName("both sides inserting the same words takes them once")
    void equalWordInsertionsCollapse() {
        Optional<String> composed = WordLevelMerge.compose(
            "int total = 0;\n", "int total = computeTotal();\n", "int total = computeTotal();\n", POLICY);

        assertTrue(composed.isPresent());
        assertEquals("int total = computeTotal();\n", composed.get());
    }

    @Test
    @DisplayName("a side spanning more than one line refuses rather than half-composing")
    void multiLineSidesRefuse() {
        Optional<String> composed = WordLevelMerge.compose("a b\nc d\n", "a\nc d\n", "a b\nc\n", POLICY);

        assertTrue(composed.isEmpty(),
            "word composition across line boundaries would have to decide where the breaks belong");
    }

    @Test
    @DisplayName("the composed line ends the way the line it replaces did")
    void theTerminatorComesFromTheBase() {
        assertEquals("y\n", WordLevelMerge.compose("x y z\n", "y z\n", "x y\n", POLICY).orElseThrow());
        assertEquals("y", WordLevelMerge.compose("x y z", "y z", "x y", POLICY).orElseThrow(),
            "a base line without a terminator composes to one without a terminator");
        assertEquals("y\r\n", WordLevelMerge.compose("x y z\r\n", "y z\r\n", "x y\r\n", POLICY).orElseThrow(),
            "and CRLF stays CRLF, because a merge that rewrote line endings would reformat the file");
    }

    @Test
    @DisplayName("the words nobody touched are neither dropped nor reordered")
    void untouchedWordsSurvive() {
        // A deletion at the front and an insertion at the back: neither side touched the middle, and a walk that
        // read a run's own empty extent as content - the mistake this project has met four times - would lose it.
        Optional<String> composed = WordLevelMerge.compose(
            "a b c d e\n", "b c d e\n", "a b c d e f\n", POLICY);

        assertTrue(composed.isPresent());
        assertEquals("b c d e f\n", composed.get(),
            "the front deletion stands, the back insertion is added, and b c d e are untouched");
    }
}
