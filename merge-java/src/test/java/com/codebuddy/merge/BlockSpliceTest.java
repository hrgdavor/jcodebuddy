// {@link com.codebuddy.merge.BlockSpliceTest} The composed block: settled stretches applied, open ones marked.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composing a block from settled stretches, kept ones and open ones (unified plan step 4.22).
 *
 * <h2>What is being bought, in one fixture</h2>
 *
 * <p>A {@code diff3} block whose imports are answered by a resolution and whose method body is not: the
 * imports are applied, the body keeps its markers, and <b>every line neither branch touched survives</b>. The
 * third part is the one that can go wrong silently, so it is asserted directly rather than inferred from the
 * block being applied.
 *
 * <h2>Refusal is a feature, and it is asserted too</h2>
 *
 * <p>Every way this composition can be wrong ends in {@link Optional#empty()} and today's behaviour: a block
 * with no base (no coordinates), an answer covering only part of a contested stretch, two answers for one
 * stretch, and nothing settled at all. A composer that guessed instead would write a plausible file with a line
 * missing.
 */
class BlockSpliceTest {

    private static final ComparisonPolicy POLICY = ComparisonPolicy.DEFAULT;

    /** A diff3 block: imports contested, the method body untouched by either branch. */
    private static final String BASE = """
        import java.util.List;

        class OrderService {

            void run() {
            }
        }""";

    private static final String OURS = """
        import java.util.List;
        import java.math.BigDecimal;

        class OrderService {

            void run() {
                audit();
            }
        }""";

    private static final String THEIRS = """
        import java.util.List;
        import java.time.Instant;

        class OrderService {

            void run() {
                charge();
            }
        }""";

    @Test
    @DisplayName("an answer for one stretch is applied and the open stretch keeps its markers")
    void oneSettledStretchAndOneOpen() {
        // Lines 1-3 of the base carry the imports (1-based, inclusive); the body is lines 5-7 and is answered
        // by nobody here.
        Optional<BlockSplice.Result> composed = BlockSplice.compose(BASE, OURS, THEIRS,
            "ours", "theirs", "base",
            List.of(new BlockSplice.Settled(Region.spanning(1, 3),
                "import java.util.List;\nimport java.math.BigDecimal;\nimport java.time.Instant;\n")),
            POLICY);

        assertTrue(composed.isPresent(), "a diff3 block with one settled stretch composes");
        String text = composed.get().text();
        assertTrue(text.contains("import java.time.Instant;"), text);
        assertTrue(text.contains("<<<<<<< ours"), "the unsettled body keeps its markers: " + text);
        assertTrue(text.contains("audit();") && text.contains("charge();"), text);
        assertTrue(text.contains("class OrderService {"),
            "the class declaration neither branch touched survives: " + text);
        assertFalse(composed.get().isComplete(), "and the result says something is still open");
    }

    @Test
    @DisplayName("when every contested stretch is answered the result is complete, and has no markers")
    void everyStretchSettledIsComplete() {
        Optional<BlockSplice.Result> composed = BlockSplice.compose(BASE, OURS, THEIRS,
            "ours", "theirs", "base",
            List.of(new BlockSplice.Settled(Region.spanning(1, 3),
                    "import java.util.List;\nimport java.math.BigDecimal;\nimport java.time.Instant;\n"),
                new BlockSplice.Settled(Region.spanning(5, 7),
                    "    void run() {\n        audit();\n        charge();\n    }\n")),
            POLICY);

        assertTrue(composed.isPresent());
        assertTrue(composed.get().isComplete(), composed.get().openSpans().toString());
        assertFalse(composed.get().text().contains("<<<<<<<"), composed.get().text());
        assertTrue(composed.get().text().contains("class OrderService {"), composed.get().text());
        assertTrue(composed.get().text().contains("audit();"), composed.get().text());
        assertTrue(composed.get().text().contains("charge();"), composed.get().text());
    }

    @Test
    @DisplayName("the lines neither branch touched are kept, not dropped")
    void untouchedLinesAreKept() {
        // The failure this asserts against is silent: a composition that emitted only the merge ranges would
        // drop the class declaration and the closing brace, and the result would still look like an answer.
        Optional<BlockSplice.Result> composed = BlockSplice.compose(BASE, OURS, THEIRS,
            "ours", "theirs", "base",
            List.of(new BlockSplice.Settled(Region.spanning(1, 3), "import java.util.List;\n")),
            POLICY);

        assertTrue(composed.isPresent());
        String text = composed.get().text();
        for (String untouched : List.of("class OrderService {", "    void run() {", "\n}\n")) {
            assertTrue(text.contains(untouched),
                "the untouched line '" + untouched.replace("\n", "\\n") + "' must survive: "
                    + text.replace("\n", "\\n"));
        }
        assertFalse(text.contains("\n\n\n"),
            "and the composed text is not reformatted: TextLines lines already carry their terminators, so "
                + "adding one would double every line break: " + text.replace("\n", "\\n"));
    }

    @Test
    @DisplayName("a block with no base is refused, because there are no coordinates to place an answer in")
    void aBaseLessBlockIsRefused() {
        Optional<BlockSplice.Result> composed = BlockSplice.compose("", OURS, THEIRS,
            "ours", "theirs", null,
            List.of(new BlockSplice.Settled(Region.spanning(1, 3), "import java.util.List;\n")),
            POLICY);

        assertTrue(composed.isEmpty(),
            "git's default merge style carries no base, so the ranges have nothing to be built against");
    }

    @Test
    @DisplayName("an answer covering only part of a contested stretch is refused")
    void aPartialAnswerIsRefused() {
        // Both branches replace base lines 1 and 2, so the contested stretch is two base lines wide, and the
        // claim answers only the first of them. Applying that text would drop the second, which is exactly the
        // defect this path exists to avoid.
        Optional<BlockSplice.Result> composed = BlockSplice.compose("a\nb\nc", "A1\nA2\nc", "B1\nB2\nc",
            "ours", "theirs", "base",
            List.of(new BlockSplice.Settled(Region.spanning(1, 1), "A1\n")),
            POLICY);

        assertTrue(composed.isEmpty(), "a partial answer is not an answer");
    }

    @Test
    @DisplayName("two answers for one stretch are refused rather than chosen between")
    void twoAnswersForOneStretchAreRefused() {
        Optional<BlockSplice.Result> composed = BlockSplice.compose(BASE, OURS, THEIRS,
            "ours", "theirs", "base",
            List.of(new BlockSplice.Settled(Region.spanning(1, 3), "one\n"),
                new BlockSplice.Settled(Region.spanning(1, 3), "two\n")),
            POLICY);

        assertTrue(composed.isEmpty());
    }

    @Test
    @DisplayName("nothing settled is refused, so the file is not rewritten for no reason")
    void nothingSettledIsRefused() {
        Optional<BlockSplice.Result> composed = BlockSplice.compose(BASE, OURS, THEIRS,
            "ours", "theirs", "base", List.of(), POLICY);

        assertTrue(composed.isEmpty());
    }

    @Test
    @DisplayName("the composed block parses back as a conflict block, with the labels it was given")
    void theComposedBlockParsesBack() {
        // The tool's own output becomes its input on the next run, so a composed block that still carries
        // markers must be readable by the parser that produced this one.
        Optional<BlockSplice.Result> composed = BlockSplice.compose(BASE, OURS, THEIRS,
            "feature-a", "feature-b", "base",
            List.of(new BlockSplice.Settled(Region.spanning(1, 3), "import java.util.List;\n")),
            POLICY);

        assertTrue(composed.isPresent());
        ConflictMarkerParser.ParsedFile parsed =
            ConflictMarkerParser.parse(composed.get().text());

        assertEquals(1, parsed.blocks().size(), composed.get().text());
        ConflictMarkerParser.Block block = parsed.blocks().get(0);
        assertEquals("feature-a", block.oursLabel());
        assertEquals("feature-b", block.theirsLabel());
        assertTrue(block.hasBase(), "the base section is written too, so a second run still has a base");
        assertTrue(block.ours().contains("audit();"), block.ours());
        assertTrue(block.theirs().contains("charge();"), block.theirs());
    }

    @Test
    @DisplayName("an insertion inside a settled span is answered, not left marked")
    void anInsertionInsideASettledSpanIsAnswered() {
        // An insertion has no base lines, so "is it inside the span" is a question about its position between
        // two base lines. Saying "no base lines, therefore nobody answers it" would leave it marked forever.
        String base = "a\nb\nc";
        String ours = "a\nX\nb\nc";
        String theirs = "a\nY\nb\nc";

        Optional<BlockSplice.Result> composed = BlockSplice.compose(base, ours, theirs,
            "ours", "theirs", "base",
            List.of(new BlockSplice.Settled(Region.spanning(1, 2), "a\nX\nY\n")),
            POLICY);

        assertTrue(composed.isPresent(), "an insertion at the span's edge is answered");
        assertTrue(composed.get().isComplete(), composed.get().text());
        assertTrue(composed.get().text().contains("X") && composed.get().text().contains("Y"),
            composed.get().text());
    }
}
