// {@link com.codebuddy.merge.BlockComposition} The line-level positions of a conflict block, in three coordinates.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.merge.MergeRange;
import com.codebuddy.merge.jetbrains.merge.MergeRangeBuilder;
import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import com.codebuddy.merge.jetbrains.text.TextLines;

import java.util.ArrayList;
import java.util.List;

/**
 * One conflict block's lines, placed in three coordinate systems at once — base, ours and theirs — as an
 * ordered list of {@link Segment}s that account for every line exactly once (unified plan step 4.21).
 *
 * <h2>Why this exists before anything composes a block</h2>
 *
 * <p>Composing "the settled part applied and the rest left marked" means <b>rewriting a person's file</b>.
 * The three sides do not have the same lines, the settled answer is a resolver's own rendering rather than a
 * range of anyone's input, and a wrong offset does not fail loudly — it silently deletes a line of their
 * code. So the positions come first, as a value with a test, and the splice comes after: this class does no
 * text manipulation at all, and {@link #audit} is the property the splice will be judged by.
 *
 * <h2>The coordinates, stated once</h2>
 *
 * <p>{@link MergeRangeBuilder} returns {@link MergeRange}s whose triples are
 * {@code (left, base, right)} — the constructor argument order, which is not the field order a reader
 * guesses — each half-open and 0-based, with the lines both sides kept appearing in <b>no range at all</b>:
 * they are the gaps. Those gaps are the trap, and they are why this type exists: a walk that emitted only
 * the ranges would drop every line neither branch touched, and the {@code MergeRangeBuilder} javadoc says so
 * — "a composer that guessed would resurrect deleted lines or drop kept ones".
 *
 * <p>{@link Segment} therefore carries all three sides' extents for every stretch of the block, including
 * the gaps, and {@link Audit} proves that the segments tile all three sides: each line of each side is in
 * exactly one segment.
 *
 * <h2>What it cannot do yet, and why that is a fact rather than an omission</h2>
 *
 * <p>A merge-style block has <b>no base</b> — git's default conflict carries only the two sides — and the
 * ranges are built <em>against a base</em>. With no base there are no coordinates to place a resolver's
 * rendering between, so a base-less block can be classified but not composed. That is why step 4.21 works on
 * {@code diff3}/{@code zdiff3} blocks, and why the base-less fixture that motivated the step
 * ({@code partialResolutionLeavesTheBlock}) is not the fixture that can be fixed by it.
 */
public final class BlockComposition {

    /**
     * What the two branches did to one stretch of the block.
     *
     * <p>Classified from the {@link MergeRangeBuilder.MergeChange} flags plus a text comparison, rather than
     * from {@code MergeRangeUtil.getMergeType}: that classifier answers "what is this change, and could a
     * text pass resolve it", which a resolver needs, while a composer needs only "who changed it, and do the
     * two sides agree". Asking the bigger question here would drag a conflict's fate into a position model.
     */
    public enum Kind {
        /** Neither branch changed these lines; they are the gap between two ranges. */
        UNCHANGED,
        /** Only ours changed: the answer is ours, and nothing is contested. */
        LEFT_ONLY,
        /** Only theirs changed. */
        RIGHT_ONLY,
        /** Both changed, to the same text: one change, and nothing to choose between. */
        AGREED,
        /** Both changed, differently: this is what a resolution has to answer for. */
        CONTESTED
    }

    /**
     * One stretch of the block, in all three coordinate systems.
     *
     * <p>All extents are half-open and 0-based, and an empty extent is how "this side has nothing here" is
     * said — a deleted line has an empty left extent, an insertion an empty base extent. {@code UNCHANGED}
     * segments have equal-length extents on all three sides by construction.
     */
    public record Segment(Kind kind,
                          int baseStart, int baseEnd,
                          int leftStart, int leftEnd,
                          int rightStart, int rightEnd) {

        public Segment {
            if (baseEnd < baseStart || leftEnd < leftStart || rightEnd < rightStart) {
                throw new IllegalArgumentException("a segment ends before it starts: " + baseStart + ".."
                    + baseEnd + ", " + leftStart + ".." + leftEnd + ", " + rightStart + ".." + rightEnd);
            }
        }

        /** True when a resolution has to answer for these lines. */
        public boolean isContested() {
            return kind == Kind.CONTESTED;
        }

        /** How many lines of ours this stretch covers. */
        public int leftLength() {
            return leftEnd - leftStart;
        }

        /** How many base lines this stretch covers; zero for an insertion. */
        public int baseLength() {
            return baseEnd - baseStart;
        }

        /** How many lines of theirs this stretch covers. */
        public int rightLength() {
            return rightEnd - rightStart;
        }
    }

    /**
     * Whether a list of segments accounts for every line of every side exactly once.
     *
     * @param baseLines   how many base lines the differ sees (zero when the block carries no base, since the
     *                    base is then absent rather than empty — see the class note)
     * @param missingBase base lines no segment covers
     * @param missingLeft lines of ours no segment covers
     * @param missingRight lines of theirs no segment covers
     * @param coveredTwice lines more than one segment claims, on any side, as a human-readable list
     */
    public record Audit(int baseLines,
                        List<Integer> missingBase,
                        List<Integer> missingLeft,
                        List<Integer> missingRight,
                        List<String> coveredTwice) {

        public Audit {
            missingBase = List.copyOf(missingBase);
            missingLeft = List.copyOf(missingLeft);
            missingRight = List.copyOf(missingRight);
            coveredTwice = List.copyOf(coveredTwice);
        }

        /** True when every line of every side is accounted for exactly once. */
        public boolean holds() {
            return missingBase.isEmpty() && missingLeft.isEmpty()
                && missingRight.isEmpty() && coveredTwice.isEmpty();
        }

        /** The defects in one line, for an assertion message. */
        public String describe() {
            if (holds()) {
                return "every line accounted for exactly once (" + baseLines + " base lines)";
            }
            StringBuilder text = new StringBuilder("composition does not tile the block:");
            if (!missingBase.isEmpty()) {
                text.append(" base lines with no segment ").append(missingBase);
            }
            if (!missingLeft.isEmpty()) {
                text.append(" our lines with no segment ").append(missingLeft);
            }
            if (!missingRight.isEmpty()) {
                text.append(" their lines with no segment ").append(missingRight);
            }
            if (!coveredTwice.isEmpty()) {
                text.append(" lines claimed twice ").append(coveredTwice);
            }
            return text.toString();
        }
    }

    private BlockComposition() {
    }

    /**
     * Place one block's lines in three coordinates, in order, with nothing lost.
     *
     * @param base   the block's base side, or {@code ""} when the block carries none
     * @param left   ours
     * @param right  theirs
     * @param policy the comparison policy the ranges are built under — the same one the rest of the run uses,
     *               because a range is a statement about which lines differ
     */
    public static List<Segment> segments(String base, String left, String right, ComparisonPolicy policy) {
        String baseText = base == null ? "" : base;
        String leftText = left == null ? "" : left;
        String rightText = right == null ? "" : right;
        List<String> baseLines = TextLines.of(baseText).lines();
        List<String> leftLines = TextLines.of(leftText).lines();
        List<String> rightLines = TextLines.of(rightText).lines();

        List<Segment> segments = new ArrayList<>();
        int baseAt = 0;
        int leftAt = 0;
        int rightAt = 0;

        for (MergeRangeBuilder.MergeChange change
            : MergeRangeBuilder.build(baseText, leftText, rightText,
                policy == null ? ComparisonPolicy.DEFAULT : policy)) {
            MergeRange range = change.range();

            // The lines between the cursor and this range are the ones neither branch touched. They are in no
            // range, and a walk that skipped them would drop them from the composed block.
            int unchanged = range.start2() - baseAt;
            if (unchanged > 0) {
                segments.add(new Segment(Kind.UNCHANGED,
                    baseAt, range.start2(),
                    leftAt, leftAt + unchanged,
                    rightAt, rightAt + unchanged));
                baseAt += unchanged;
                leftAt += unchanged;
                rightAt += unchanged;
            }

            int baseLength = range.end2() - range.start2();
            // A side that **changed** in this range has its own extent in it; a side that did not change **kept the
            // range's base lines**, so its extent is the range's base length and not the empty range the change
            // flags would otherwise leave. Getting this wrong is invisible to a coverage check - the lines are
            // still covered, just by the wrong segment - and it is what made every later slice of that side be
            // read from the wrong offset.
            int leftStart = leftAt;
            int leftEnd = change.leftChanged() ? range.end1() : leftAt + baseLength;
            int rightStart = rightAt;
            int rightEnd = change.rightChanged() ? range.end3() : rightAt + baseLength;

            segments.add(new Segment(classify(change, leftLines, rightLines),
                range.start2(), range.end2(),
                leftStart, leftEnd,
                rightStart, rightEnd));
            baseAt = range.end2();
            leftAt = leftEnd;
            rightAt = rightEnd;
        }

        int baseTail = baseLines.size() - baseAt;
        int leftTail = leftLines.size() - leftAt;
        int rightTail = rightLines.size() - rightAt;
        if (leftTail > 0 || rightTail > 0 || baseTail > 0) {
            // Whatever is left after the last range is unchanged on both sides: the builder stops when both
            // change lists are consumed, and anything after the last change is a change on neither side. Each
            // side still gets its own extent, so a base-less block (whose phantom base line makes baseTail 0
            // while the sides have a tail) is covered rather than assumed.
            segments.add(new Segment(Kind.UNCHANGED,
                baseAt, baseAt + Math.max(baseTail, 0),
                leftAt, leftAt + Math.max(leftTail, 0),
                rightAt, rightAt + Math.max(rightTail, 0)));
        }
        return List.copyOf(segments);
    }

    /**
     * Who changed this range, and do the two sides agree?
     *
     * <p>The flags say whether each side has a diff inside the base extent; the texts settle the rest. Both
     * sides changing to the same text is {@link Kind#AGREED} rather than contested — one change, and nothing
     * to choose between.
     */
    private static Kind classify(MergeRangeBuilder.MergeChange change,
                                 List<String> leftLines, List<String> rightLines) {
        if (!change.leftChanged() && !change.rightChanged()) {
            // The builder only emits a range because a side changed inside it, so this cannot happen; saying
            // so is cheaper than guessing which side to believe.
            throw new IllegalStateException("a merge range with neither side changed: " + change.range());
        }
        if (!change.leftChanged()) {
            return Kind.RIGHT_ONLY;
        }
        if (!change.rightChanged()) {
            return Kind.LEFT_ONLY;
        }
        MergeRange range = change.range();
        return sameLines(leftLines, range.start1(), range.end1(),
            rightLines, range.start3(), range.end3())
            ? Kind.AGREED
            : Kind.CONTESTED;
    }

    private static boolean sameLines(List<String> left, int leftStart, int leftEnd,
                                     List<String> right, int rightStart, int rightEnd) {
        int length = leftEnd - leftStart;
        if (length != rightEnd - rightStart) {
            return false;
        }
        for (int offset = 0; offset < length; offset++) {
            if (!left.get(leftStart + offset).equals(right.get(rightStart + offset))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code segments} tile the block: every line of ours and of theirs, and every base line when the
     * block has a base at all, in exactly one segment.
     *
     * <p><b>Coverage is necessary and not sufficient, and the difference bit this type once.</b> A segment for a
     * range in which only one side changed used to carry the empty extent the change flags imply for the other
     * side, so the lines that side had kept were covered by a <em>later</em> segment instead — every line still
     * counted exactly once, and every later slice of that side was read from the wrong offset. So {@link #audit}
     * also reports <b>an {@code UNCHANGED} segment whose extents are not the same length</b>: that is the shape a
     * mis-advanced cursor leaves behind, and a coverage check alone will never see it.
     *
     * <p>The base is exempt when {@code base} is empty, and the reason is a counting artifact rather than a
     * rule: a merge-style block has <em>no</em> base side, while {@link TextLines#of(String)} reads {@code ""}
     * as one empty line, so requiring that phantom line to be covered would fail every base-less block for a
     * reason that has nothing to do with the composition.
     */
    public static Audit audit(String base, String left, String right, List<Segment> segments) {
        List<String> baseLines = TextLines.of(base == null ? "" : base).lines();
        List<String> leftLines = TextLines.of(left == null ? "" : left).lines();
        List<String> rightLines = TextLines.of(right == null ? "" : right).lines();
        boolean baseIsPresent = base != null && !base.isEmpty();
        int baseCount = baseIsPresent ? baseLines.size() : 0;

        int[] baseCovered = new int[baseCount];
        int[] leftCovered = new int[leftLines.size()];
        int[] rightCovered = new int[rightLines.size()];
        for (Segment segment : segments == null ? List.<Segment>of() : segments) {
            for (int line = segment.baseStart(); line < segment.baseEnd(); line++) {
                if (line >= 0 && line < baseCovered.length) {
                    baseCovered[line]++;
                }
            }
            for (int line = segment.leftStart(); line < segment.leftEnd(); line++) {
                if (line >= 0 && line < leftCovered.length) {
                    leftCovered[line]++;
                }
            }
            for (int line = segment.rightStart(); line < segment.rightEnd(); line++) {
                if (line >= 0 && line < rightCovered.length) {
                    rightCovered[line]++;
                }
            }
        }

        List<Integer> missingBase = uncovered(baseCovered);
        List<Integer> missingLeft = uncovered(leftCovered);
        List<Integer> missingRight = uncovered(rightCovered);
        List<String> twice = new ArrayList<>();
        collectCoveredTwice(baseCovered, "base", twice);
        collectCoveredTwice(leftCovered, "ours", twice);
        collectCoveredTwice(rightCovered, "theirs", twice);
        // A shape check the coverage check cannot make: an UNCHANGED stretch means the sides are equal there, so
        // unequal extents are the footprint of a cursor that fell behind.
        for (Segment segment : segments == null ? List.<Segment>of() : segments) {
            if (segment.kind() == Kind.UNCHANGED
                && (segment.leftLength() != segment.rightLength()
                    || segment.baseLength() != segment.leftLength())) {
                twice.add("an UNCHANGED stretch with unequal extents: base " + segment.baseLength()
                    + ", ours " + segment.leftLength() + ", theirs " + segment.rightLength());
            }
        }
        return new Audit(baseCount, missingBase, missingLeft, missingRight, twice);
    }

    private static List<Integer> uncovered(int[] covered) {
        List<Integer> missing = new ArrayList<>();
        for (int line = 0; line < covered.length; line++) {
            if (covered[line] == 0) {
                missing.add(line);
            }
        }
        return missing;
    }

    /** A line two segments both claim, named with its side — a bare number would not say which. */
    private static void collectCoveredTwice(int[] covered, String side, List<String> target) {
        for (int line = 0; line < covered.length; line++) {
            if (covered[line] > 1) {
                target.add(side + " line " + line);
            }
        }
    }
}
