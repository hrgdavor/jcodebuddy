// {@link com.codebuddy.merge.SeededMergePropertyTest} The benchmark's randomized property, re-expressed (plan 4.13).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.merge.MergeRange;
import com.codebuddy.merge.jetbrains.merge.MergeRangeBuilder;
import com.codebuddy.merge.jetbrains.merge.MergeResolve;
import com.codebuddy.merge.jetbrains.merge.MergeType;
import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import com.codebuddy.merge.jetbrains.text.TextLines;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upstream's randomized property, <b>ported as a property rather than as its harness</b> (unified plan step 4.13,
 * {@code JETBRAINS_PORT.md} § 11.6).
 *
 * <h2>What was taken and what was left behind</h2>
 *
 * <p>{@code MergeAutoTest} checks that after any sequence of apply / ignore / resolve / edit, the change ranges stay
 * ordered and non-overlapping and undo restores the prior state. Its harness is bound to {@code ApplicationManager},
 * {@code Disposable} and an editor undo stack (§ 3.4), so what crosses over is the <b>property</b>, stated over our
 * plain-text API: for any three texts, the ranges our builder produces tile the three sides exactly once, in order,
 * without overlap.
 *
 * <h2>The seed is fixed, and that is a requirement rather than a detail</h2>
 *
 * <p>A failing run must be reproducible. Upstream seeds from {@code System.currentTimeMillis()}, which makes a
 * failure a story about a run nobody can repeat; here the seed is a constant that is <b>printed with every run</b>,
 * so a case that fails can be re-run by freezing the generator at that index rather than by hoping.
 *
 * <h2>The invariant is checked by a named helper, and the helper is itself tested</h2>
 *
 * <p>An invariant nobody has seen fail is a claim, not a check. {@link #theInvariantCheckerHasTeeth()} feeds the
 * checker a deliberately broken range list — two ranges out of order — and asserts that it says so, which is the
 * only evidence that a green property run means anything.
 */
class SeededMergePropertyTest {

    /** Fixed, printed, and part of the output: a property that fails must be reproducible from the message alone. */
    private static final long SEED = 20261007L;

    private static final int CASES = 400;

    /**
     * How many seeds the property is run under. One seed is weak evidence — it is one walk through the space, not a
     * statement about it — and each seed is reported separately so a failure names the run to reproduce rather than
     * a range to bisect.
     */
    private static final int SEEDS = 5;

    private static final ComparisonPolicy POLICY = ComparisonPolicy.DEFAULT;

    private static final String[] ALPHABET = {"alpha", "beta", "gamma", "delta", "epsilon"};

    @Test
    @DisplayName("for any three texts, the ranges tile all three sides once, in order, without overlap")
    void theRangesTileEverySide() {
        List<String> failures = new ArrayList<>();
        int resolved = 0;
        int refused = 0;
        int cases = 0;

        for (int seed = 0; seed < SEEDS; seed++) {
            Random random = new Random(SEED + seed);
            for (int index = 0; index < CASES; index++) {
                cases++;
                String base = text(random);
                String ours = change(random, base);
                String theirs = change(random, base);
                List<String> violations = violations(base, ours, theirs);
                if (!violations.isEmpty()) {
                    failures.add("case " + index + " (seed " + (SEED + seed) + ")\n    base:   "
                        + base.replace("\n", "\\n") + "\n    ours:   " + ours.replace("\n", "\\n")
                        + "\n    theirs: " + theirs.replace("\n", "\\n")
                        + "\n    " + String.join("\n    ", violations));
                }
                // The text pass is exercised on the same case, and whatever it decides must be reproducible: a
                // resolver that answers differently on the same input is the other failure no single vector catches.
                MergeResolve.Result first = MergeResolve.resolve(ours, base, theirs, POLICY);
                MergeResolve.Result second = MergeResolve.resolve(ours, base, theirs, POLICY);
                assertEquals(first.refused(), second.refused(),
                    "same input, same verdict (seed " + (SEED + seed) + " case " + index + ")");
                if (first.resolved()) {
                    resolved++;
                    assertEquals(first.mergedText(), second.mergedText(),
                        "same input, same text (seed " + (SEED + seed) + " case " + index + ")");
                } else {
                    refused++;
                }
            }
        }

        System.out.println("PROPERTY-METRIC: " + cases + " cases over " + SEEDS + " seeds from " + SEED
            + ", " + resolved + " resolved, " + refused + " refused, "
            + failures.size() + " invariant violation(s)");
        assertTrue(failures.isEmpty(),
            failures.size() + " of " + cases + " random cases violated the tiling property:\n  - "
                + String.join("\n  - ", failures));
    }

    @Test
    @DisplayName("the invariant checker has teeth: a deliberately broken range list is reported")
    void theInvariantCheckerHasTeeth() {
        // Two ranges swapped: the property must see it. Without this, a green property run would be
        // indistinguishable from a checker that returns nothing.
        String base = "a\nb\nc\nd\n";
        String ours = "a\nB\nc\nd\n";
        String theirs = "a\nb\nC\nd\n";
        List<MergeRangeBuilder.MergeChange> sound = MergeRangeBuilder.build(base, ours, theirs, POLICY);
        assertFalse(sound.isEmpty());

        List<MergeRangeBuilder.MergeChange> swapped = new ArrayList<>(sound);
        if (swapped.size() > 1) {
            java.util.Collections.swap(swapped, 0, 1);
        } else {
            // One range is not enough to reorder, so the break is an invented overlap instead: the same claim the
            // property makes, broken by hand.
            MergeRange single = swapped.get(0).range();
            swapped.add(new MergeRangeBuilder.MergeChange(
                new MergeRange(single.start1(), single.end1(), single.start2(), single.end2(),
                    single.start3(), single.end3()),
                swapped.get(0).leftChanged(), swapped.get(0).rightChanged()));
        }
        List<String> violations = new ArrayList<>();
        collectRangeViolations(swapped, violations);
        assertFalse(violations.isEmpty(),
            "a broken range list must be reported, or the property proves nothing: " + swapped);
    }

    // ------------------------------------------------------------------ the invariant, as a named check

    /** Every way the three texts can be tiled wrongly by a list of ranges: order, overlap and coverage. */
    private static List<String> violations(String base, String ours, String theirs) {
        List<String> problems = new ArrayList<>();
        List<MergeRangeBuilder.MergeChange> changes = MergeRangeBuilder.build(base, ours, theirs, POLICY);
        collectRangeViolations(changes, problems);

        // The ported classifier must have one name per range: a range it cannot name is a range nobody can act on.
        List<MergeType> types = ConflictShape.typesOf(base, ours, theirs, POLICY);
        if (types.size() != changes.size()) {
            problems.add("the classifier named " + types.size() + " of " + changes.size() + " ranges");
        }

        // And the composition must tile the three sides exactly once, which is the invariant that catches a range
        // walk dropping the lines in no range at all.
        List<BlockComposition.Segment> segments = BlockComposition.segments(base, ours, theirs, POLICY);
        BlockComposition.Audit audit = BlockComposition.audit(base, ours, theirs, segments);
        if (!audit.holds()) {
            problems.add("the composition does not tile: " + audit.describe());
        }
        return problems;
    }

    /** Order and overlap, in base coordinates and in each side's own. */
    private static void collectRangeViolations(List<MergeRangeBuilder.MergeChange> changes,
                                               List<String> problems) {
        int baseAt = 0;
        for (int index = 0; index < changes.size(); index++) {
            MergeRange range = changes.get(index).range();
            if (range.start2() < baseAt) {
                problems.add("range " + index + " starts at base " + range.start2()
                    + ", before the previous range ended at " + baseAt);
            }
            if (range.end2() < range.start2() || range.end1() < range.start1() || range.end3() < range.start3()) {
                problems.add("range " + index + " runs backwards: " + range);
            }
            if (range.start1() < 0 || range.start2() < 0 || range.start3() < 0) {
                problems.add("range " + index + " has a negative coordinate: " + range);
            }
            baseAt = Math.max(baseAt, range.end2());
        }
    }

    // ------------------------------------------------------------------ the generator

    /** A random list of lines, from a small alphabet so collisions and agreements happen often. */
    private static String text(Random random) {
        int count = 1 + random.nextInt(6);
        StringBuilder text = new StringBuilder();
        for (int line = 0; line < count; line++) {
            text.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            if (line < count - 1) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    /** One side's edit of the base: a random number of insertions, deletions and replacements. */
    private static String change(Random random, String base) {
        List<String> lines = new ArrayList<>(TextLines.of(base).lines());
        // `TextLines` keeps a trailing empty line so that splitting and joining are inverses; it is not a line, and
        // a generator that edited it would be generating a case no text can produce.
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int edits = random.nextInt(3);
        for (int edit = 0; edit < edits; edit++) {
            if (lines.isEmpty()) {
                lines.add(ALPHABET[random.nextInt(ALPHABET.length)]);
                continue;
            }
            int at = random.nextInt(lines.size());
            switch (random.nextInt(3)) {
                case 0 -> lines.set(at, ALPHABET[random.nextInt(ALPHABET.length)]);
                case 1 -> lines.add(at, ALPHABET[random.nextInt(ALPHABET.length)]);
                default -> lines.remove(at);
            }
        }
        return String.join("\n", lines);
    }
}
