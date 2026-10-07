// {@link com.codebuddy.merge.BlockSplice} Composes a conflict block from settled stretches and open ones.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import com.codebuddy.merge.jetbrains.text.TextLines;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Composes one conflict block's replacement text: the stretches a resolution settled are applied, the
 * stretches neither branch touched are kept, and only what is genuinely contested and unanswered keeps its
 * markers (unified plan step 4.22).
 *
 * <h2>Why this is a separate type from {@link BlockComposition}</h2>
 *
 * <p>That type answers <em>where</em> every line is, in three coordinate systems, and deliberately touches no
 * text. This one does the opposite: it is the text manipulation, and it reads its positions from there. The
 * split is the point — the coordinates were tested before anything spliced, because the failure this code can
 * produce is a deleted line of somebody's source, and a wrong offset does not fail loudly.
 *
 * <h2>What it composes, and when it refuses</h2>
 *
 * <p>Per {@link BlockComposition.Segment}:
 *
 * <ul>
 *   <li>{@code UNCHANGED} and {@code AGREED} — emitted once, because the sides are equal there;</li>
 *   <li>{@code LEFT_ONLY} — ours, {@code RIGHT_ONLY} — theirs;</li>
 *   <li>{@code CONTESTED} and settled — the settling claim's text, emitted <b>once</b> at the position of the
 *       first stretch it answers, because a resolver's answer is for its whole conflict rather than for each
 *       range;</li>
 *   <li>{@code CONTESTED} and unanswered — the four markers, with the block's own labels.</li>
 * </ul>
 *
 * <p>It refuses — returning empty rather than guessing — when the block has <b>no base</b> (the ranges are
 * built against a base, so a two-sided block has no coordinates to place an answer between), when a contested
 * stretch is <b>only partly</b> inside a settled span, when two claims answer the same stretch, and when
 * nothing is settled at all (in which case the composition would reproduce the block and there is no reason to
 * rewrite the file). Every refusal leaves the caller with exactly today's behaviour: markers and a fixture.
 *
 * <h2>The markers it writes are the markers that are read</h2>
 *
 * <p>A composed block still carrying markers must be readable by {@link ConflictMarkerParser} on the next run —
 * the tool's own output becomes its input — so the four tokens are written exactly as git writes them, the
 * block's own labels are reused rather than invented, and the round trip is a test.
 */
public final class BlockSplice {

    /**
     * One stretch a resolution settled.
     *
     * @param baseSpan the block-relative base region the answering conflict covers, 1-based and inclusive, in
     *                 the same coordinates {@link Conflict#getRegion()} uses for a block's slice
     * @param text     the claim's applied text for that whole region
     */
    public record Settled(Region baseSpan, String text) {

        public Settled {
            baseSpan = baseSpan == null ? Region.unknown() : baseSpan;
        }
    }

    /**
     * The composed block.
     *
     * @param text       the replacement for the block, markers included where anything is still open
     * @param openSpans  the stretches left contested, for a report or a fixture
     */
    public record Result(String text, List<Region> openSpans) {

        public Result {
            openSpans = List.copyOf(openSpans);
        }

        /** True when the block is fully settled and its replacement carries no markers. */
        public boolean isComplete() {
            return openSpans.isEmpty();
        }
    }

    private BlockSplice() {
    }

    /**
     * Compose the block, or return empty when it cannot be composed safely.
     *
     * @param base        the block's base side; a block without one cannot be composed here
     * @param left        ours
     * @param right       theirs
     * @param oursLabel   the label the block's {@code <<<<<<<} marker carries, reused for any marker written
     * @param theirsLabel the label its {@code >>>>>>>} carries
     * @param baseLabel   the label its {@code |||||||} carries, or {@code null}
     * @param settled     what the resolutions settled, in any order
     * @param policy      the comparison policy the positions are built under
     */
    public static Optional<Result> compose(String base, String left, String right,
                                           String oursLabel, String theirsLabel, String baseLabel,
                                           List<Settled> settled, ComparisonPolicy policy) {
        if (base == null || base.isEmpty()) {
            // No base, no coordinates: a merge-style block cannot be composed here, and saying so is better
            // than placing an answer by a guess. The caller keeps the block and prepares a fixture.
            return Optional.empty();
        }
        List<BlockComposition.Segment> segments = BlockComposition.segments(base, left, right, policy);
        List<String> baseLines = TextLines.of(base).lines();
        List<String> leftLines = TextLines.of(left == null ? "" : left).lines();
        List<String> rightLines = TextLines.of(right == null ? "" : right).lines();
        List<Settled> claims = settled == null ? List.of() : settled;

        Settled[] owners = new Settled[segments.size()];
        for (int index = 0; index < segments.size(); index++) {
            BlockComposition.Segment segment = segments.get(index);
            if (!segment.isContested()) {
                continue;
            }
            List<Settled> answering = answering(segment, claims);
            if (answering.size() > 1) {
                // Two answers for one stretch: which text to write is not a question this type may settle.
                return Optional.empty();
            }
            if (answering.isEmpty()) {
                if (partiallySettled(segment, claims)) {
                    // An answer that covers only part of a contested stretch is not an answer for it:
                    // applying it would drop the rest of the stretch, which is the defect this whole path
                    // exists to avoid.
                    return Optional.empty();
                }
                continue;
            }
            owners[index] = answering.get(0);
        }
        boolean anySettled = false;
        for (Settled owner : owners) {
            anySettled |= owner != null;
        }
        if (!anySettled) {
            // Nothing settled: the composition would reproduce the block, so rewriting the file would be a
            // change with no content.
            return Optional.empty();
        }

        StringBuilder text = new StringBuilder();
        List<Region> openSpans = new ArrayList<>();
        List<Settled> emitted = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            BlockComposition.Segment segment = segments.get(index);
            switch (segment.kind()) {
                case UNCHANGED, AGREED -> append(text, leftLines, segment.leftStart(), segment.leftEnd());
                case LEFT_ONLY -> append(text, leftLines, segment.leftStart(), segment.leftEnd());
                case RIGHT_ONLY -> append(text, rightLines, segment.rightStart(), segment.rightEnd());
                case CONTESTED -> {
                    Settled owner = owners[index];
                    if (owner == null) {
                        appendMarkers(text, segment, leftLines, rightLines, baseLines,
                            oursLabel, theirsLabel, baseLabel);
                        openSpans.add(regionOf(segment));
                    } else if (!emitted.contains(owner)) {
                        // Once per answer, not once per stretch: a resolver's text is its answer for the whole
                        // conflict, and repeating it for each range inside it would duplicate code.
                        appendRaw(text, owner.text());
                        emitted.add(owner);
                    }
                }
            }
        }
        return Optional.of(new Result(text.toString(), openSpans));
    }

    /**
     * The claims whose span answers this stretch.
     *
     * <p>A stretch with base lines is inside a span when its whole extent is; an <b>insertion</b> has no base
     * lines at all, so it is placed by its position — the point between two base lines — and belongs to a span
     * that surrounds that point. Saying "no base lines, therefore nobody answers it" would leave every
     * insertion inside a settled region marked as open.
     */
    private static List<Settled> answering(BlockComposition.Segment segment, List<Settled> claims) {
        List<Settled> answering = new ArrayList<>();
        for (Settled claim : claims) {
            if (claim.baseSpan().isKnown() && contains(claim.baseSpan(), segment)) {
                answering.add(claim);
            }
        }
        return answering;
    }

    /** True when a claim's span overlaps this stretch without containing it. */
    private static boolean partiallySettled(BlockComposition.Segment segment, List<Settled> claims) {
        for (Settled claim : claims) {
            if (claim.baseSpan().isKnown() && overlaps(claim.baseSpan(), segment)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code span} surrounds the whole of the segment's base extent. */
    private static boolean contains(Region span, BlockComposition.Segment segment) {
        int start = span.startLine() - 1;
        int end = span.endLine();
        if (segment.baseLength() == 0) {
            int point = segment.baseStart();
            return start <= point && point <= end;
        }
        return start <= segment.baseStart() && segment.baseEnd() <= end;
    }

    /** True when {@code span} touches the segment's base extent at all. */
    private static boolean overlaps(Region span, BlockComposition.Segment segment) {
        int start = span.startLine() - 1;
        int end = span.endLine();
        if (segment.baseLength() == 0) {
            int point = segment.baseStart();
            return start <= point && point <= end;
        }
        return segment.baseStart() < end && start < segment.baseEnd();
    }

    private static Region regionOf(BlockComposition.Segment segment) {
        if (segment.baseLength() == 0) {
            // An insertion has no base lines; its position is reported as the single line it sits after.
            return Region.line(Math.max(1, segment.baseStart()));
        }
        return new Region(segment.baseStart() + 1, segment.baseEnd());
    }

    /**
     * Append a stretch of one side's lines, verbatim.
     *
     * <p>{@link TextLines} lines <b>carry their terminators</b> — the type exists so that splitting and joining
     * are inverses — so appending a newline of our own would double every line break and quietly reformat the
     * whole block. The same convention has a second half: a text that **ends** with a terminator leaves an empty
     * final line so that joining reproduces it, and that empty line is the absence of a line rather than a line —
     * emitting it would add a trailing blank line the inputs never had. A real blank line is {@code "\n"} and not
     * empty, which is what makes "an empty line contributes nothing" exactly right.
     */
    private static void append(StringBuilder text, List<String> lines, int from, int to) {
        for (int line = from; line < to && line < lines.size(); line++) {
            String value = lines.get(line);
            if (value.isEmpty()) {
                continue;
            }
            text.append(value);
            if (!value.endsWith("\n")) {
                text.append('\n');
            }
        }
    }

    private static void appendRaw(StringBuilder text, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        text.append(value);
        if (!value.endsWith("\n")) {
            text.append('\n');
        }
    }

    /**
     * The four markers, exactly as git writes them and with the block's own labels, so the result parses back.
     */
    private static void appendMarkers(StringBuilder text,
                                      BlockComposition.Segment segment,
                                      List<String> leftLines, List<String> rightLines,
                                      List<String> baseLines,
                                      String oursLabel, String theirsLabel, String baseLabel) {
        text.append("<<<<<<< ").append(oursLabel == null ? "ours" : oursLabel).append('\n');
        append(text, leftLines, segment.leftStart(), segment.leftEnd());
        text.append("||||||| ").append(baseLabel == null ? "base" : baseLabel).append('\n');
        append(text, baseLines, segment.baseStart(), segment.baseEnd());
        text.append("=======\n");
        append(text, rightLines, segment.rightStart(), segment.rightEnd());
        text.append(">>>>>>> ").append(theirsLabel == null ? "theirs" : theirsLabel).append('\n');
    }
}
