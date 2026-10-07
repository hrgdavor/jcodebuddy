// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/ByLineRt.kt).
// @derived Rewritten in Java as a longest-common-subsequence search rather than a translation of the Myers search: see the class note for why, and docs/JETBRAINS_PORT.md section 3.2 for what upstream's classes are wired to.
// {enabled:true, blockMarker: "implicit"} A bounded diff over any two sequences a comparator can compare.
package com.codebuddy.merge.jetbrains.text;

import java.util.ArrayList;
import java.util.List;

/**
 * A bounded diff over two sequences compared element by element, by longest common subsequence.
 *
 * <h2>Why an LCS table and not Myers</h2>
 *
 * <p>Myers is the better algorithm asymptotically, and it was implemented here first. It is not here now,
 * and the reason is worth recording rather than hiding: a frontier-based Myers search needs its
 * tie-breaking to agree between the search and the walk back, and getting that wrong produces output that
 * still looks like a diff — ranges, in order, describing <em>a</em> difference — while placing a change a
 * line away from the real one. That was measured, not feared: the implementation was debugged through
 * several rounds of exactly that failure, and each fix moved the error rather than removing it.
 *
 * <p>An LCS table cannot fail that way. The table's cells are defined by a recurrence with no
 * tie-breaking to agree on, the walk back reads the same table it built, and the invariant — "every cell
 * is the length of the longest common subsequence of the two suffixes" — is checkable by reading the
 * code. For a merge tool the trade is the right way round: the input is one conflict hunk, not a whole
 * repository, the bound below keeps the memory honest, and a diff that is *provably* the difference beats
 * one that is faster and occasionally off by a line.
 *
 * <p>It is recorded in {@code docs/JETBRAINS_PORT.md} § 3.2 as a deliberate difference from upstream, and
 * step 4.13's parity gate is where a case it handles worse would surface.
 *
 * <h2>What it guarantees</h2>
 *
 * <ul>
 *   <li>The changes are in order, and no two are adjacent — a maximal run of differences is one change,
 *       not several.</li>
 *   <li>Every change is minimal in the sense that matters: two elements that can be matched inside a
 *       change are matched, so a run of equal lines is never reported as changed.</li>
 *   <li>The common prefix and suffix are trimmed first, so the changes sit at the edges of the
 *       difference, and a small change in a large file costs a table of the <em>changed</em> region.</li>
 * </ul>
 *
 * <h2>The bound</h2>
 *
 * <p>The table is {@code (inner1 + 1) * (inner2 + 1)} cells. Both dimensions are checked against
 * {@code maxCells} <b>before</b> anything is allocated, and exceeding it throws
 * {@link DiffTooBigException} rather than degrading: see that class for why a degraded diff is worse than
 * a refusal for a merge tool.
 */
public final class MyersDiff {

    /** Compares two elements by index: true when they should be treated as matching. */
    @FunctionalInterface
    public interface Matcher {
        boolean matches(int index1, int index2);
    }

    /**
     * The default cell budget for the table.
     *
     * <p>2,000 × 2,000 cells is 4 million ints, or about 16 MB — comfortable, and it covers a
     * fully-rewritten 2,000-line region, which is a rewrite rather than a merge. Past that the diff
     * refuses instead of allocating a table nobody asked for.
     */
    public static final long DEFAULT_MAX_CELLS = 4_000_000L;

    private MyersDiff() {
    }

    /** Diff two sequences, bounded by {@link #DEFAULT_MAX_CELLS}. */
    public static List<DiffRange> diff(int length1, int length2, Matcher matcher) {
        return diff(length1, length2, matcher, DEFAULT_MAX_CELLS);
    }

    /**
     * Diff two sequences, refusing a comparison whose table would exceed a cell budget.
     *
     * @param maxCells the largest table to build, in cells
     * @throws DiffTooBigException when the comparison would need more
     */
    public static List<DiffRange> diff(int length1, int length2, Matcher matcher, long maxCells) {
        if (length1 < 0 || length2 < 0) {
            throw new IllegalArgumentException("negative length: " + length1 + ", " + length2);
        }
        if (length1 == 0 && length2 == 0) {
            // Nothing on either side is not a change. Answered before the one-sided case below, which
            // would otherwise report a range with both sides empty.
            return List.of();
        }
        if (length1 == 0 || length2 == 0) {
            return List.of(length1 == 0
                ? new DiffRange(0, 0, 0, length2)
                : new DiffRange(0, length1, 0, 0));
        }

        // Trim the common edges: what is left is the only part a table has to describe.
        int prefix = 0;
        while (prefix < length1 && prefix < length2 && matcher.matches(prefix, prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < length1 - prefix && suffix < length2 - prefix
            && matcher.matches(length1 - 1 - suffix, length2 - 1 - suffix)) {
            suffix++;
        }
        int length = length1 - prefix - suffix;
        int otherLength = length2 - prefix - suffix;

        if (length == 0 && otherLength == 0) {
            return List.of();
        }
        if (length == 0 || otherLength == 0) {
            // One side of the trimmed region is empty, so the whole of the other is one insertion or
            // deletion of that side.
            //
            // **Both offsets are `prefix`.** The tempting alternative — placing an insertion at the end of
            // the line it follows when the *first* text is the short side — is wrong here, because the
            // vectors compare both texts at the same terminator-aware position: an insertion of a final
            // empty line belongs at `prefix`, not after the line that matched. Trying to be cleverer than
            // that moved two vectors and broke two others, which is how this comment came to be written.
            return List.of(length == 0
                ? new DiffRange(prefix, prefix, prefix, prefix + otherLength)
                : new DiffRange(prefix, prefix + length, prefix, prefix));
        }

        long cells = (long) (length + 1) * (otherLength + 1);
        if (cells > maxCells) {
            throw new DiffTooBigException(maxCells, cells, "a " + length + " by " + otherLength
                + " region (its longest common subsequence table)");
        }

        int shift = prefix;
        Matcher inner = (index1, index2) -> matcher.matches(shift + index1, shift + index2);
        return commonSubsequenceDiff(length, otherLength, inner, shift);
    }

    /**
     * The changes in a region with no common edges left, from its longest-common-subsequence table.
     *
     * <p>The table is built forward and the path read backward, which is the one direction this problem
     * forces: the length of the answer is a function of the suffixes, so it is computed from the end, and
     * reconstructing the answer walks from the beginning.
     */
    private static List<DiffRange> commonSubsequenceDiff(int length, int otherLength, Matcher matcher,
                                                         int shift) {
        // table[i][j] is the length of the longest common subsequence of this[i..] and other[j..].
        int[][] table = new int[length + 1][otherLength + 1];
        for (int i = length - 1; i >= 0; i--) {
            int[] row = table[i];
            int[] below = table[i + 1];
            for (int j = otherLength - 1; j >= 0; j--) {
                row[j] = matcher.matches(i, j)
                    ? below[j + 1] + 1
                    : Math.max(below[j], row[j + 1]);
            }
        }

        List<DiffRange> changes = new ArrayList<>();
        int i = 0;
        int j = 0;
        int runStartI = 0;
        int runStartJ = 0;

        while (i < length && j < otherLength) {
            if (matcher.matches(i, j)) {
                // A match closes whatever run was open before it.
                if (runStartI != i || runStartJ != j) {
                    changes.add(new DiffRange(shift + runStartI, shift + i,
                        shift + runStartJ, shift + j));
                }
                i++;
                j++;
                runStartI = i;
                runStartJ = j;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }

        if (runStartI != length || runStartJ != otherLength) {
            changes.add(new DiffRange(shift + runStartI, shift + length,
                shift + runStartJ, shift + otherLength));
        }
        return List.copyOf(changes);
    }
}
