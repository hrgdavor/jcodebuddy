// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/util/Range.kt).
// @derived Translated from Kotlin to Java as a record, with the two-sequence ordering made explicit in the javadoc.
// {enabled:true, blockMarker: "implicit"} One change between two sequences, as two half-open ranges.
package com.codebuddy.merge.jetbrains.text;

/**
 * One change between two sequences: {@code [start1, end1)} in the first, {@code [start2, end2)} in the
 * second.
 *
 * <p>A half-open range rather than a (start, length) pair, because the two are used interchangeably in
 * this package and an off-by-one between them is the classic way a diff goes subtly wrong. The invariant
 * is stated once, here:
 *
 * <ul>
 *   <li>an <b>insertion</b> has {@code start1 == end1} and {@code start2 < end2};</li>
 *   <li>a <b>deletion</b> has {@code start1 < end1} and {@code start2 == end2};</li>
 *   <li>a <b>replacement</b> has both non-empty.</li>
 * </ul>
 *
 * <p>There is no "unchanged" range: a diff is a list of the changes, and the unchanged parts are what is
 * between them. A range with both sides empty is therefore meaningless, and the factory methods cannot
 * produce one.
 *
 * @param start1 inclusive start in the first sequence
 * @param end1   exclusive end in the first sequence
 * @param start2 inclusive start in the second sequence
 * @param end2   exclusive end in the second sequence
 */
public record DiffRange(int start1, int end1, int start2, int end2) {

    public DiffRange {
        if (start1 < 0 || start2 < 0) {
            throw new IllegalArgumentException(
                "negative offset in " + start1 + ", " + end1 + ", " + start2 + ", " + end2);
        }
        if (end1 < start1 || end2 < start2) {
            throw new IllegalArgumentException(
                "range ends before it starts: " + start1 + ", " + end1 + ", " + start2 + ", " + end2);
        }
        if (start1 == end1 && start2 == end2) {
            throw new IllegalArgumentException("a diff range with both sides empty is not a change");
        }
    }

    /** True when the first sequence lost lines and the second gained none. */
    public boolean isDeletion() {
        return start1 < end1 && start2 == end2;
    }

    /** True when the second sequence gained lines and the first lost none. */
    public boolean isInsertion() {
        return start1 == end1 && start2 < end2;
    }

    /** True when both sides are non-empty: content was replaced, not added or removed. */
    public boolean isReplacement() {
        return start1 < end1 && start2 < end2;
    }

    /** How many elements the first sequence contributes to this change. */
    public int length1() {
        return end1 - start1;
    }

    /** How many elements the second sequence contributes to this change. */
    public int length2() {
        return end2 - start2;
    }
}
