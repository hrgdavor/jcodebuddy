// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/DiffTooBigException.kt).
// @derived Translated from Kotlin to Java as an unchecked exception carrying the budget it exceeded.
// {enabled:true, blockMarker: "implicit"} Thrown instead of degrading when a comparison is too large to answer honestly.
package com.codebuddy.merge.jetbrains.text;

/**
 * Thrown when a comparison is too large to answer, instead of degrading to a worse answer.
 *
 * <h2>Why refusing beats degrading</h2>
 *
 * <p>A diff search has a cost that grows with how <em>different</em> two texts are, not with how large
 * they are. Two large but nearly identical files are cheap; two large files with nothing in common are
 * not. A bounded search therefore has exactly two options when it runs out of budget:
 *
 * <ul>
 *   <li><b>Refuse</b>, with a named failure a caller can act on — report it, hand the conflict to a
 *       human, or come back with a larger budget.</li>
 *   <li><b>Degrade</b> to a cheap approximation, and return something that looks like an answer.</li>
 * </ul>
 *
 * <p>The second is worse here, and specifically worse for a <em>merge</em> tool: a degraded diff does not
 * produce a diff that is a bit off, it produces changes at the wrong offsets, and the caller then
 * composes those offsets into a merge. The failure surfaces as corrupted output rather than as a slow
 * run. So this is an exception, and it is unchecked because a caller that has no larger budget has
 * nothing useful to do with it except report it.
 *
 * <p>Upstream's {@code DiffTooBigException} is caught at the boundary and turned into "this conflict
 * cannot be resolved automatically" — {@code MergeResolveUtil} returns {@code null} — which is the same
 * decision this module makes by other means.
 */
public class DiffTooBigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient long budget;
    private final transient long required;

    /**
     * @param budget   the cell budget the comparison was given
     * @param required how many cells the search had reached when the budget ran out
     * @param what     what was being compared, for the message
     */
    public DiffTooBigException(long budget, long required, String what) {
        super("the comparison of " + what + " needs more than " + budget
            + " cells, and reached " + required + ". Raise the budget, or decline to compare:"
            + " a degraded diff reports changes at the wrong offsets.");
        this.budget = budget;
        this.required = required;
    }

    /** The cell budget the comparison was given. */
    public long budget() {
        return budget;
    }

    /** How many cells the search had reached when the budget ran out. */
    public long required() {
        return required;
    }
}
