// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/ComparisonMergeUtil.kt).
// @derived Translated from Kotlin to Java: the walk that turns two two-way diffs into three-side merge ranges.
// {enabled:true, blockMarker: "implicit"} Builds the three-side ranges a merge is made of, from two two-way diffs.
package com.codebuddy.merge.jetbrains.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import com.codebuddy.merge.jetbrains.text.DiffRange;
import com.codebuddy.merge.jetbrains.text.TextCompare;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns "base versus left" and "base versus right" into the three-side ranges a merge is made of.
 *
 * <h2>Why two diffs are needed and neither is enough</h2>
 *
 * <p>A merge is not a diff. A diff says what changed on one side; a merge has to know, for each region of
 * the base, <em>which sides changed it and whether they agree</em> — and that is a fact about the base, so
 * both sides must be expressed in base coordinates before either question can be asked. This walks the two
 * diffs together and emits only base-aligned ranges, which is what makes
 * {@link MergeRangeUtil#getMergeType} meaningful.
 *
 * <p>When both sides change at the same base position, the range covers the <b>union</b> of their base
 * extents and the two changes become one range. That is the case the class exists for: a range holding
 * only one side's change would let the table decide about half a disagreement, and a later step would
 * resolve half of it.
 *
 * <h2>The cursors, and why they are tracked rather than recomputed</h2>
 *
 * <p>Three positions advance together: `baseAt` is the base line both sides are aligned at, and
 * `leftAt`/`rightAt` are where that base line sits in each branch. Every range's left and right extents
 * follow from the cursor plus the lengths the diffs report — no arithmetic over already-emitted ranges,
 * because that was the first cut's mistake: reconstructing an offset from the differences seen so far is
 * only correct when each change is a one-to-one replacement, and an insertion or deletion breaks it
 * silently.
 *
 * <h2>What this does not do</h2>
 *
 * <p>It decides nothing about content. Every range it emits is a fact about <em>which lines moved</em>. What
 * the change <em>means</em> is {@link MergeRangeUtil}'s table, and whether to apply it is
 * {@link MergeResolve}'s. That separation is what lets the table be tested with no texts at all.
 */
public final class MergeRangeBuilder {

    private MergeRangeBuilder() {
    }

    /**
     * A range plus which sides actually changed it.
     *
     * <p>The flags are not derivable from the range: a range whose left length is zero means the left
     * <em>deleted</em> the base lines and a range whose left length equals the base length means the left
     * <em>did not touch them</em>, and those are the same numbers from the range alone. A composer that
     * guessed would resurrect deleted lines or drop kept ones, so the walk reports what it saw.
     *
     * @param range       the three-side extent
     * @param leftChanged whether the left branch has a diff inside this base extent
     * @param rightChanged whether the right branch does
     */
    public record MergeChange(MergeRange range, boolean leftChanged, boolean rightChanged) {
    }

    /**
     * Build the ranges.
     *
     * @return the changes, in order, each base-aligned, with none adjacent and none empty
     */
    public static List<MergeChange> build(String baseText, String leftText, String rightText,
                                         ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;
        List<DiffRange> leftChanges = TextCompare.compareLines(baseText, leftText, effective);
        List<DiffRange> rightChanges = TextCompare.compareLines(baseText, rightText, effective);
        List<MergeChange> ranges = new ArrayList<>();

        int leftIndex = 0;
        int rightIndex = 0;
        int baseAt = 0;
        int leftAt = 0;
        int rightAt = 0;

        while (leftIndex < leftChanges.size() || rightIndex < rightChanges.size()) {
            DiffRange left = leftIndex < leftChanges.size() ? leftChanges.get(leftIndex) : null;
            DiffRange right = rightIndex < rightChanges.size() ? rightChanges.get(rightIndex) : null;

            // The next base line either side changes. `baseAt` is always at or before it, because every
            // earlier change has been consumed and both diffs are in base order.
            int nextBase = left == null ? right.start1()
                : right == null ? left.start1()
                : Math.min(left.start1(), right.start1());

            // The base lines between the cursor and that position are unchanged on both sides: they appear
            // in no range, and both branch cursors advance over them.
            int unchanged = nextBase - baseAt;
            baseAt += unchanged;
            leftAt += unchanged;
            rightAt += unchanged;

            // The range covers the union of whatever both sides change starting here. A change beginning
            // later is a separate range.
            int baseEnd = baseAt;
            if (left != null && left.start1() == baseAt) {
                baseEnd = Math.max(baseEnd, left.end1());
            }
            if (right != null && right.start1() == baseAt) {
                baseEnd = Math.max(baseEnd, right.end1());
            }

            // Absorb every further change on either side that begins inside the range, so its base extent
            // is maximal and each diff is consumed as far as it overlaps. A side with two adjacent changes
            // would otherwise leave the range half-described.
            int leftLength = 0;
            int rightLength = 0;
            boolean leftChanged = false;
            boolean rightChanged = false;
            while (leftIndex < leftChanges.size()
                && leftChanges.get(leftIndex).start1() <= baseEnd) {
                DiffRange change = leftChanges.get(leftIndex);
                baseEnd = Math.max(baseEnd, change.end1());
                leftLength += change.length2();
                leftChanged = true;
                leftIndex++;
            }
            while (rightIndex < rightChanges.size()
                && rightChanges.get(rightIndex).start1() <= baseEnd) {
                DiffRange change = rightChanges.get(rightIndex);
                baseEnd = Math.max(baseEnd, change.end1());
                rightLength += change.length2();
                rightChanged = true;
                rightIndex++;
            }

            int baseLength = baseEnd - baseAt;
            ranges.add(new MergeChange(new MergeRange(leftAt, leftAt + leftLength,
                baseAt, baseAt + baseLength,
                rightAt, rightAt + rightLength), leftChanged, rightChanged));
            baseAt += baseLength;
            leftAt += leftLength;
            rightAt += rightLength;
        }

        return List.copyOf(ranges);
    }
}
