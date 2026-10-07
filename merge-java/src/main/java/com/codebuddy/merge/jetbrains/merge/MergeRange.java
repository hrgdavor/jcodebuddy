// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/util/MergeRange.kt).
// @derived Translated from Kotlin to Java as a record, with the three-sequence ordering stated explicitly.
// {enabled:true, blockMarker: "implicit"} One change across three sides: left, base and right.
package com.codebuddy.merge.jetbrains.merge;

/**
 * One change across the three sides of a merge, as three half-open ranges.
 *
 * <p>Where {@code DiffRange} answers "what changed between two texts", this answers "what changed across
 * three, and how do the three line up". That is why a merge cannot be built from two diffs alone: the
 * coordinates have to be carried together so the builder knows which region of the base a left change and
 * a right change both describe.
 *
 * <p>The sides are ordered <b>left, base, right</b> — the distinction the merge itself turns on: the
 * <em>base</em> is what both branches started from, and it is the second side, not the third. Getting it
 * wrong produces a merge that looks plausible and resolves edits nobody made.
 *
 * @param start1 inclusive start of the left range
 * @param end1   exclusive end of the left range
 * @param start2 inclusive start of the base range
 * @param end2   exclusive end of the base range
 * @param start3 inclusive start of the right range
 * @param end3   exclusive end of the right range
 */
public record MergeRange(int start1, int end1, int start2, int end2, int start3, int end3) {

    public MergeRange {
        if (start1 < 0 || start2 < 0 || start3 < 0) {
            throw new IllegalArgumentException("negative offset");
        }
        if (end1 < start1 || end2 < start2 || end3 < start3) {
            throw new IllegalArgumentException("a range ends before it starts");
        }
    }

    /** The left range's length. */
    public int length1() {
        return end1 - start1;
    }

    /** The base range's length. */
    public int length2() {
        return end2 - start2;
    }

    /** The right range's length. */
    public int length3() {
        return end3 - start3;
    }

    /** True when the base has no lines in this range: both branches inserted here. */
    public boolean baseIsEmpty() {
        return start2 == end2;
    }

    /** True when the left side has no lines in this range. */
    public boolean leftIsEmpty() {
        return start1 == end1;
    }

    /** True when the right side has no lines in this range. */
    public boolean rightIsEmpty() {
        return start3 == end3;
    }

    @Override
    public String toString() {
        return "MergeRange[" + start1 + "-" + end1 + ", " + start2 + "-" + end2
            + ", " + start3 + "-" + end3 + "]";
    }
}
