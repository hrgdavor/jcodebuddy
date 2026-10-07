// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/MergeResolveUtil.kt).
// @derived Translated from Kotlin to Java: the SimpleHelper pass, with the policy that produced a result recorded because this module may write its answer to a file.
// {enabled:true, blockMarker: "implicit"} The simple resolve: composed text, or a refusal.
package com.codebuddy.merge.jetbrains.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import com.codebuddy.merge.jetbrains.text.TextLines;

import java.util.List;
import java.util.Optional;

/**
 * The simple resolve pass: compose the three sides, or refuse.
 *
 * <h2>The rule, in one sentence</h2>
 *
 * <p>For each region the base is divided into, take the text from the side that changed it — from the base
 * where neither did — and <b>refuse</b> when both changed the same region differently. Nothing here
 * guesses: the answer is a function of the three inputs, which is what makes the result safe to apply
 * rather than merely plausible.
 *
 * <h2>Why the text comes from {@link MergeRangeBuilder}'s ranges</h2>
 *
 * <p>An earlier cut walked the two diffs itself and used the <em>base</em> cursor to slice the branches. It
 * produced plausible-looking merges and was wrong: a branch's offsets are its own, and they only equal the
 * base's until the branch inserts or deletes. The defect showed as a merge that resolved to the branch
 * text with the newlines missing mid-text and the right text where the left should be. {@link MergeRange}s
 * carry all three coordinate sets, which is exactly the thing a merge needs and a diff does not, so this
 * pass composes from them and the walk stays in one place.
 *
 * <h2>Why it refuses, and why that is the feature</h2>
 *
 * <p>Two edits to the same lines are two intentions, and no comparison of text contains the information
 * needed to choose between them. Upstream refuses for the same reason and says so:
 *
 * <blockquote>insertion-insertion conflicts can't be possibly resolved (if inserted fragments are
 * different), because we don't know the right order of inserted chunks (and sorting them alphabetically or
 * by length makes no sense).</blockquote>
 *
 * <p>Upstream can afford to be bolder elsewhere — its own note above {@code tryGreedyResolve} says its
 * results are "explicitly verified by user and can be safely undone", which is why it trades correctness
 * for resolve-rate. <b>This module cannot make that trade</b>, because {@code MergeFileTool} writes its
 * resolutions to disk with nobody watching. So this pass is the conservative one only, and the bolder
 * behaviour is a suggestion rather than an automatic answer — {@code JETBRAINS_PORT.md} § 5.1 classifies
 * it and {@code SUGGESTIONS.md} is where it goes.
 *
 * <h2>The policy is recorded, not assumed</h2>
 *
 * <p>Under {@link ComparisonPolicy#IGNORE_WHITESPACES} a result can be policy-equal to an input without
 * being byte-equal to it. Upstream records nothing there because a person is looking at the result; here
 * the policy travels with the answer, so a caller that writes the file can say what its basis was and a
 * reviewer can see that a formatting difference was accepted. That is check C7 of
 * {@code JETBRAINS_PORT.md} § 5.3 — the one upstream does not have.
 */
public final class MergeResolve {

    private MergeResolve() {
    }

    /**
     * What a resolve attempt produced.
     *
     * @param mergedText the composed text, or {@code null} when the pass refused
     * @param policy     the policy the pass ran under
     * @param byteEqual  true when {@code mergedText} is byte-identical to the base text, which means the
     *                   pass found nothing to do
     */
    public record Result(String mergedText, ComparisonPolicy policy, boolean byteEqual) {

        /** True when the pass refused: the sides disagree and no function of the inputs decides it. */
        public boolean refused() {
            return mergedText == null;
        }

        /**
         * True when the pass produced text.
         *
         * <p>Named because "not refused" is the question most callers actually ask, and a caller that
         * wrote {@code !result.refused()} would be one negation away from writing a refusal to a file.
         */
        public boolean resolved() {
            return mergedText != null;
        }
    }

    /** Compose the three sides under {@link ComparisonPolicy#DEFAULT}. */
    public static Result resolve(String leftText, String baseText, String rightText) {
        return resolve(leftText, baseText, rightText, ComparisonPolicy.DEFAULT);
    }

    /** Compose the three sides. */
    public static Result resolve(String leftText, String baseText, String rightText,
                                 ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;
        TextLines leftLines = TextLines.of(leftText);
        TextLines baseLines = TextLines.of(baseText);
        TextLines rightLines = TextLines.of(rightText);

        List<MergeRangeBuilder.MergeChange> changes =
            MergeRangeBuilder.build(baseText, leftText, rightText, effective);

        StringBuilder out = new StringBuilder();
        int baseAt = 0;
        for (MergeRangeBuilder.MergeChange change : changes) {
            MergeRange range = change.range();
            // Everything between the previous range and this one is unchanged on both sides, so it comes
            // from the base. This is where a merge's unchanged bulk comes from, and it is why the ranges
            // alone are not the answer.
            out.append(baseLines.text(baseAt, range.start2()));

            boolean leftChanged = change.leftChanged();
            boolean rightChanged = change.rightChanged();
            if (leftChanged && rightChanged) {
                String leftPart = leftLines.text(range.start1(), range.end1());
                String rightPart = rightLines.text(range.start3(), range.end3());
                if (!leftPart.equals(rightPart)) {
                    // Before refusing: the two sides may disagree only about WHICH PART of a line changed, which a
                    // line comparison cannot see and a word comparison can. This is the SAFE half of the port's
                    // word-level machinery (JETBRAINS_PORT.md 11.2, 6.5) - the benchmark's most valuable resolve
                    // vectors are exactly this case, and refusing them was our only place below the floor.
                    //
                    // It composes or it refuses, and a refusal here is the same refusal as before: nothing about
                    // this path can turn a disagreement into an answer, because the word pass refuses whenever both
                    // sides inserted different words at one point (R6, one granularity down).
                    Optional<String> atWordLevel = WordLevelMerge.compose(
                        baseLines.text(range.start2(), range.end2()), leftPart, rightPart, effective);
                    if (atWordLevel.isEmpty()) {
                        // Two intentions, and nothing in the text says which to keep. Refused rather than
                        // answered with a marker, because a marker is output this pass invented.
                        return new Result(null, effective, false);
                    }
                    out.append(atWordLevel.get());
                } else {
                    // The same change on both sides, so either one is the answer.
                    out.append(leftPart);
                }
            } else if (leftChanged) {
                out.append(leftLines.text(range.start1(), range.end1()));
            } else if (rightChanged) {
                out.append(rightLines.text(range.start3(), range.end3()));
            } else {
                // Neither side changed these base lines. The walk reports such a range only when both
                // changes were deletions, so the base lines come back: a deletion on both sides is one
                // deletion, and the lines both branches removed are not content either wanted.
                out.append(baseLines.text(range.start2(), range.end2()));
            }

            baseAt = range.end2();
        }
        // Whatever follows the last change is unchanged and comes from the base.
        out.append(baseLines.text(baseAt, baseLines.size()));

        String merged = out.toString();
        return new Result(merged, effective, merged.equals(baseText));
    }
}
