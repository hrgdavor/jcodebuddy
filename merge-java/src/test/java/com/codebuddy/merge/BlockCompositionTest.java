// {@link com.codebuddy.merge.BlockCompositionTest} The three-coordinate position model, and the invariant it must satisfy.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line positions of a conflict block (unified plan step 4.21).
 *
 * <h2>The invariant, and why it is the whole test</h2>
 *
 * <p><b>Every line of every side is in exactly one segment.</b> The composition this model exists for rewrites
 * a person's file, and the failure it can produce is not a wrong answer — it is a <b>deleted line</b>, with
 * nothing in the outcome text to show for it. The lines most at risk are the ones that appear in
 * <em>no</em> merge range: everything neither branch touched. A walk that emitted only the ranges would drop
 * them all, silently, and the block would look resolved.
 *
 * <p>So the assertions below are coverage assertions, and two of them are deliberately <b>proved to fail</b>
 * ({@link #aLineDroppedFromTheWalkIsReported()} and {@link #aLineClaimedTwiceIsReported()}). An invariant
 * nobody can break proves nothing.
 */
class BlockCompositionTest {

    private static final ComparisonPolicy POLICY = ComparisonPolicy.DEFAULT;

    /** Every fixture the model must tile, including the ones with no base at all. */
    private static final List<List<String>> FIXTURES = List.of(
        // identical sides: one unchanged stretch
        List.of("a\nb\nc", "a\nb\nc", "a\nb\nc"),
        // ours only
        List.of("a\nb\nc", "a\nB\nc", "a\nb\nc"),
        // theirs only
        List.of("a\nb\nc", "a\nb\nc", "a\nB\nc"),
        // both the same change: agreed, nothing to choose
        List.of("a\nb\nc", "a\nB\nc", "a\nB\nc"),
        // both differently: contested
        List.of("a\nb\nc", "a\nLEFT\nc", "a\nRIGHT\nc"),
        // an insertion with an empty base: the instruction's own first example
        List.of("", "public void charge() {\n}", "public void refund() {\n}"),
        // no base side at all, git's default merge style
        List.of("", "import a;\n\nclass X {", "import a;\nimport b;\n\nclass X {"),
        // a deletion on one side
        List.of("a\nb\nc", "a\nc", "a\nb\nc"),
        // both delete
        List.of("a\nb\nc", "a\nc", "a\nc"),
        // an empty side
        List.of("a\nb", "", "a\nb"),
        // trailing addition with a shared tail
        List.of("a", "a\nb\nc", "a\nc"));

    @Test
    @DisplayName("every line of every side is accounted for exactly once")
    void everyFixtureTilesExactly() {
        for (List<String> fixture : FIXTURES) {
            String base = fixture.get(0);
            String left = fixture.get(1);
            String right = fixture.get(2);
            List<BlockComposition.Segment> segments =
                BlockComposition.segments(base, left, right, POLICY);

            BlockComposition.Audit audit = BlockComposition.audit(base, left, right, segments);

            assertTrue(audit.holds(), "base=[" + base + "] ours=[" + left + "] theirs=[" + right
                + "] -> " + audit.describe());
        }
    }

    @Test
    @DisplayName("the lines neither branch touched are segments of their own, not gaps")
    void unchangedLinesAreSegments() {
        // The trap the class exists for: these lines are in no merge range, so a walk that emitted only the
        // ranges would drop them - and this is the common case, not an edge case.
        List<BlockComposition.Segment> segments =
            BlockComposition.segments("a\nb\nc", "a\nLEFT\nc", "a\nRIGHT\nc", POLICY);

        assertEquals(3, segments.size(), segments.toString());
        assertEquals(BlockComposition.Kind.UNCHANGED, segments.get(0).kind());
        assertEquals(BlockComposition.Kind.CONTESTED, segments.get(1).kind());
        assertEquals(BlockComposition.Kind.UNCHANGED, segments.get(2).kind());
        assertEquals(1, segments.get(0).leftLength(), "the leading 'a' is one line of ours");
        assertEquals(1, segments.get(2).leftLength(), "and the trailing 'c' is another");
    }

    @Test
    @DisplayName("both sides making the same change is agreed, not contested")
    void identicalChangesAreAgreed() {
        List<BlockComposition.Segment> segments =
            BlockComposition.segments("a\nb\nc", "a\nB\nc", "a\nB\nc", POLICY);

        assertTrue(segments.stream().noneMatch(BlockComposition.Segment::isContested),
            "one change and nothing to choose between: " + segments);
        assertTrue(segments.stream().anyMatch(s -> s.kind() == BlockComposition.Kind.AGREED),
            segments.toString());
    }

    @Test
    @DisplayName("an insertion with an empty base is placed, not lost")
    void anInsertionAtAnEmptyBaseIsPlaced() {
        List<BlockComposition.Segment> segments = BlockComposition.segments(
            "", "public void charge() {\n}", "public void refund() {\n}", POLICY);

        BlockComposition.Audit audit = BlockComposition.audit(
            "", "public void charge() {\n}", "public void refund() {\n}", segments);
        assertTrue(audit.holds(), audit.describe());
        assertEquals(0, audit.baseLines(),
            "a block with no base side has no base lines to account for, which is a fact about the differ and "
                + "not a licence to drop lines from the sides");
    }

    @Test
    @DisplayName("a base-less block is still tiled on both sides")
    void aBaseLessBlockIsTiledOnBothSides() {
        String ours = "import a;\n\nclass X {";
        String theirs = "import a;\nimport b;\n\nclass X {";

        List<BlockComposition.Segment> segments = BlockComposition.segments("", ours, theirs, POLICY);
        BlockComposition.Audit audit = BlockComposition.audit("", ours, theirs, segments);

        assertTrue(audit.holds(), audit.describe());
        assertTrue(audit.missingLeft().isEmpty() && audit.missingRight().isEmpty());
    }

    @Test
    @DisplayName("a line dropped from the walk is reported - the failure the splice must not have")
    void aLineDroppedFromTheWalkIsReported() {
        // The proof that the invariant is not vacuous: take a correct walk, drop the middle segment, and the
        // audit names the lines that would have been deleted from the file.
        String base = "a\nb\nc";
        String ours = "a\nLEFT\nc";
        String theirs = "a\nRIGHT\nc";
        List<BlockComposition.Segment> correct = new ArrayList<>(
            BlockComposition.segments(base, ours, theirs, POLICY));

        List<BlockComposition.Segment> dropped = new ArrayList<>(correct);
        dropped.remove(1);

        BlockComposition.Audit audit = BlockComposition.audit(base, ours, theirs, dropped);

        assertFalse(audit.holds());
        assertEquals(List.of(1), audit.missingBase(), "the base line the dropped segment covered");
        assertEquals(List.of(1), audit.missingLeft());
        assertEquals(List.of(1), audit.missingRight());
        assertTrue(audit.describe().contains("no segment"), audit.describe());
    }

    @Test
    @DisplayName("a line claimed by two segments is reported")
    void aLineClaimedTwiceIsReported() {
        String base = "a\nb\nc";
        String ours = "a\nLEFT\nc";
        String theirs = "a\nRIGHT\nc";
        List<BlockComposition.Segment> correct =
            BlockComposition.segments(base, ours, theirs, POLICY);

        List<BlockComposition.Segment> overlapping = new ArrayList<>(correct);
        overlapping.add(correct.get(1));

        BlockComposition.Audit audit = BlockComposition.audit(base, ours, theirs, overlapping);

        assertFalse(audit.holds());
        assertEquals(List.of("base line 1", "ours line 1", "theirs line 1"), audit.coveredTwice());
        assertTrue(audit.describe().contains("claimed twice"), audit.describe());
    }

    @Test
    @DisplayName("a stretch one side left alone carries the lines that side kept, not an empty extent")
    void anUnchangedSideKeepsItsLinesInPlace() {
        // The defect the coverage check cannot see: with only one side changing, the other side's extent used to be
        // the empty range the change flags imply, so the lines it had kept were covered by a LATER segment instead -
        // every line still counted once, and every later slice of that side read from the wrong offset.
        List<BlockComposition.Segment> segments =
            BlockComposition.segments("a\nb\nc", "a\nB\nc", "a\nb\nc", POLICY);

        assertTrue(BlockComposition.audit("a\nb\nc", "a\nB\nc", "a\nb\nc", segments).holds(),
            BlockComposition.audit("a\nb\nc", "a\nB\nc", "a\nb\nc", segments).describe());
        for (BlockComposition.Segment segment : segments) {
            if (segment.kind() == BlockComposition.Kind.UNCHANGED) {
                assertEquals(segment.leftLength(), segment.rightLength(),
                    "an unchanged stretch is equal on both sides: " + segment);
                assertEquals(segment.baseLength(), segment.leftLength(), segment.toString());
            }
        }
        BlockComposition.Segment changed = segments.stream()
            .filter(segment -> segment.kind() == BlockComposition.Kind.LEFT_ONLY)
            .findFirst().orElseThrow();
        assertEquals(1, changed.rightLength(),
            "and the side that did not change kept its line inside the changed stretch: " + changed);
    }

    @Test
    @DisplayName("an UNCHANGED stretch with unequal extents is reported")
    void anUnequalUnchangedStretchIsReported() {
        // The assertion that was missing: coverage alone accepted a segment claiming to be unchanged while its three
        // extents had different lengths, which is the footprint of a cursor that fell behind.
        BlockComposition.Segment broken =
            new BlockComposition.Segment(BlockComposition.Kind.UNCHANGED, 1, 2, 1, 3, 1, 2);

        BlockComposition.Audit audit = BlockComposition.audit("a\nb\nc", "a\nb\nc", "a\nb\nc", List.of(broken));

        assertFalse(audit.holds());
        assertTrue(audit.coveredTwice().stream().anyMatch(entry -> entry.contains("unequal extents")),
            audit.coveredTwice().toString());
        assertTrue(audit.describe().contains("claimed twice"), audit.describe());
    }

    @Test
    @DisplayName("an empty segment list is reported rather than passing silently")
    void anEmptyWalkIsReported() {
        BlockComposition.Audit audit = BlockComposition.audit("a\nb", "a\nb", "a\nb", List.of());

        assertFalse(audit.holds(), "no segments cannot tile anything");
        assertEquals(List.of(0, 1), audit.missingLeft());
    }
}
