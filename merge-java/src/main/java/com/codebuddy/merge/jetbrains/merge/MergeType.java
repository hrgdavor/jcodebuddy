// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/util/MergeConflictType.kt).
// @derived Translated from Kotlin to Java: the four-constant Type enum, with the "change on each side" flags kept beside it.
// {enabled:true, blockMarker: "implicit"} What kind of change one merge range is, and which sides changed.
package com.codebuddy.merge.jetbrains.merge;

/**
 * What kind of change one {@link MergeRange} is, and which sides changed.
 *
 * <h2>Shape, not domain</h2>
 *
 * <p>This is deliberately <b>not</b> {@code com.codebuddy.merge.ConflictType}. That enum says what a
 * conflict <em>is about</em> — an import addition, an overload clash, a widened declaration. This says
 * what <em>shape</em> the change has, in four words. Both are needed and neither implies the other: a
 * domain type cannot say "both sides inserted here", and a shape cannot say "this is a type widening".
 * {@code JETBRAINS_PORT.md} § 9 is where the two are related.
 *
 * <h2>The four shapes</h2>
 *
 * <ul>
 *   <li>{@link #INSERTED} — the base had nothing here and at least one side added something.</li>
 *   <li>{@link #DELETED} — the base had lines here and neither side kept them.</li>
 *   <li>{@link #MODIFIED} — the base had lines here and they were changed; {@link #changeLeft()} and
 *       {@link #changeRight()} say by which sides.</li>
 *   <li>{@link #CONFLICT} — both sides changed the same region, differently. Whether it can be resolved
 *       at all is a separate question, answered by {@link #resolvable()}.</li>
 * </ul>
 *
 * <h2>Why "resolvable" is a field and not a method</h2>
 *
 * <p>Upstream's type carries a nullable resolution strategy, and the distinction it encodes is real and
 * worth keeping: a conflict whose two sides are both non-empty <em>may</em> be resolvable by a text pass,
 * while one where a side is empty is a deletion competing with an edit and never is. That is not a
 * property this class can compute — it needs the range's emptiness and the resolver's own answer — so it
 * is recorded when the type is built, and {@link MergeRangeUtil} is the one place that decides it.
 */
public record MergeType(Kind kind, boolean changeLeft, boolean changeRight, boolean resolvable) {

    /** The four shapes a change can have. */
    public enum Kind {
        INSERTED,
        DELETED,
        MODIFIED,
        CONFLICT
    }

    public MergeType {
        if (kind == null) {
            throw new IllegalArgumentException("a merge type needs a kind");
        }
    }

    /** A change the base had nothing to compare against. */
    public static MergeType inserted(boolean left, boolean right) {
        return new MergeType(Kind.INSERTED, left, right, true);
    }

    /** A change where the base's lines were removed by both sides. */
    public static MergeType deleted(boolean left, boolean right) {
        return new MergeType(Kind.DELETED, left, right, true);
    }

    /** A change to lines the base had, by the sides given. */
    public static MergeType modified(boolean left, boolean right) {
        return new MergeType(Kind.MODIFIED, left, right, true);
    }

    /**
     * Both sides changed the same region, differently.
     *
     * @param resolvable whether a text pass may attempt it, which requires both sides to be non-empty —
     *                   see {@link MergeRangeUtil}
     */
    public static MergeType conflict(boolean resolvable) {
        return new MergeType(Kind.CONFLICT, true, true, resolvable);
    }

    /** True when both sides changed this range. */
    public boolean changedBothSides() {
        return changeLeft && changeRight;
    }

    /** True when neither side changed it, which is not a change at all. */
    public boolean changedNeitherSide() {
        return !changeLeft && !changeRight;
    }
}
