// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/util/MergeRangeUtil.kt).
// @derived Translated from Kotlin to Java: the getMergeType decision table, with the two guards it encodes named as constants and cross-referenced to this module's own invariants.
// {enabled:true, blockMarker: "implicit"} Which kind of change a merge range is: the emptiness and equality decision table.
package com.codebuddy.merge.jetbrains.merge;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * The decision table that says what kind of change a {@link MergeRange} is.
 *
 * <h2>The whole table is emptiness and equality</h2>
 *
 * <p>Nothing here reads content beyond asking whether two sides are equal, which is why it ports cleanly
 * and why it is the piece worth having: the four shapes and the two refusals fall out of six booleans.
 * The sides are {@code L}, {@code B}, {@code R}, and {@code eq(x, y)} compares two of them under a policy.
 *
 * <table>
 *   <caption>What each combination is</caption>
 *   <tr><th>B</th><th>L</th><th>R</th><th>eq(L,R)</th><th>type</th><th>changed by</th></tr>
 *   <tr><td>empty</td><td>empty</td><td>content</td><td>—</td><td>{@code INSERTED}</td><td>right</td></tr>
 *   <tr><td>empty</td><td>content</td><td>empty</td><td>—</td><td>{@code INSERTED}</td><td>left</td></tr>
 *   <tr><td>empty</td><td>content</td><td>content</td><td>yes</td><td>{@code INSERTED}</td><td>both</td></tr>
 *   <tr><td>empty</td><td>content</td><td>content</td><td>no</td><td>{@code CONFLICT}</td><td>both, <b>unresolvable</b></td></tr>
 *   <tr><td>content</td><td>empty</td><td>empty</td><td>—</td><td>{@code DELETED}</td><td>both</td></tr>
 *   <tr><td>content</td><td>—</td><td>—</td><td>{@code eq(B,L) &amp;&amp; eq(B,R)}</td><td>{@code MODIFIED}</td><td>whichever side is not byte-equal</td></tr>
 *   <tr><td>content</td><td>—</td><td>—</td><td>{@code eq(B,L)}</td><td>{@code DELETED} if R empty else {@code MODIFIED}</td><td>right</td></tr>
 *   <tr><td>content</td><td>—</td><td>—</td><td>{@code eq(B,R)}</td><td>{@code DELETED} if L empty else {@code MODIFIED}</td><td>left</td></tr>
 *   <tr><td>content</td><td>content</td><td>content</td><td>yes</td><td>{@code MODIFIED}</td><td>both</td></tr>
 *   <tr><td>content</td><td>content</td><td>content</td><td>no</td><td>{@code CONFLICT}</td><td>both</td></tr>
 * </table>
 *
 * <h2>The two guards, both of which are checks this module already had</h2>
 *
 * <p><b>Both sides must be non-empty {@linkplain #bothSidesHaveContent for a conflict to be resolvable.}</b>
 * A deletion competing with an edit is not a disagreement about content — one branch decided the lines
 * should not exist, and text comparison cannot arbitrate that. This is the module's own
 * {@code DESIGN_NEVER_AUTO_RESOLVED.md} § 2 split between substitutive and additive change, arrived at
 * independently; upstream encodes it here and again in its model, and so do we.
 *
 * <p><b>Two different insertions at one point are never resolvable.</b> Neither order is more correct, and
 * sorting them by length or alphabetically would be inventing a decision. Equal insertions <em>are</em>
 * safe, because there is nothing to choose between — which is why the table's
 * {@code empty/content/content} row splits on {@code eq(L,R)}.
 */
public final class MergeRangeUtil {

    private MergeRangeUtil() {
    }

    /**
     * Whether a conflict may be attempted by a text pass.
     *
     * <p>True only when both sides have content. Named rather than inlined because it is the guard that
     * keeps a deletion out of the automatic path, and it should be greppable from the record that
     * explains it.
     *
     * @see <a href="../../../../../../../DESIGN_NEVER_AUTO_RESOLVED.md">DESIGN_NEVER_AUTO_RESOLVED.md</a>
     */
    public static boolean bothSidesHaveContent(MergeRange range) {
        return !range.leftIsEmpty() && !range.rightIsEmpty();
    }

    /**
     * The table.
     *
     * @param range        the change, in three-side coordinates
     * @param emptiness    whether a side has no elements in this range
     * @param equality     policy-aware equality between two sides
     * @param trueEquality byte equality, or {@code null} when the caller cannot distinguish it; used only
     *                     to say <em>which</em> sides changed when both sides are policy-equal to the base
     *                     but not to it byte-for-byte
     * @param canResolve   whether a text pass could resolve a conflict here; consulted only for a genuine
     *                     conflict, and combined with the both-sides-non-empty guard
     */
    public static MergeType getMergeType(MergeRange range,
                                         SideEmptiness emptiness,
                                         SideEquality equality,
                                         SideEquality trueEquality,
                                         java.util.function.BooleanSupplier canResolve) {
        boolean leftEmpty = emptiness.isEmpty(Side.LEFT);
        boolean baseEmpty = emptiness.isEmpty(Side.BASE);
        boolean rightEmpty = emptiness.isEmpty(Side.RIGHT);
        if (leftEmpty && baseEmpty && rightEmpty) {
            throw new IllegalArgumentException("a merge range with all three sides empty is not a change");
        }

        if (baseEmpty) {
            // The base had nothing here, so at least one side inserted. `--=`, `=--`, `=-=` in upstream's
            // shorthand.
            if (leftEmpty) {
                return MergeType.inserted(false, true);
            }
            if (rightEmpty) {
                return MergeType.inserted(true, false);
            }
            return equality.test(Side.LEFT, Side.RIGHT)
                // Both sides inserted the same thing: one insertion, and nothing to choose between.
                ? MergeType.inserted(true, true)
                // Two different insertions at one point. No order is more correct, so this is refused
                // even though both sides have content.
                : MergeType.conflict(false);
        }

        if (leftEmpty && rightEmpty) {
            // `-=-`: both sides removed the base's lines.
            return MergeType.deleted(true, true);
        }

        boolean unchangedLeft = equality.test(Side.BASE, Side.LEFT);
        boolean unchangedRight = equality.test(Side.BASE, Side.RIGHT);

        if (unchangedLeft && unchangedRight) {
            // Both sides are policy-equal to the base. They cannot be byte-equal to it as well, or this
            // range would not exist, so `trueEquality` is what says which sides actually differ — under
            // IGNORE_WHITESPACES a formatting-only change reaches here, and reporting it as "changed by
            // both" is what keeps the difference visible instead of silently normalising it.
            if (trueEquality == null) {
                return MergeType.modified(true, true);
            }
            boolean trueUnchangedLeft = trueEquality.test(Side.BASE, Side.LEFT);
            boolean trueUnchangedRight = trueEquality.test(Side.BASE, Side.RIGHT);
            if (trueUnchangedLeft && trueUnchangedRight) {
                // Byte-identical on both sides: not a change at all, and the caller should not have
                // offered it. Reported rather than guessed at.
                throw new IllegalArgumentException(
                    "a merge range whose sides are byte-equal to the base is not a change");
            }
            return MergeType.modified(!trueUnchangedLeft, !trueUnchangedRight);
        }

        if (unchangedLeft) {
            return rightEmpty
                ? MergeType.deleted(false, true)
                : MergeType.modified(false, true);
        }
        if (unchangedRight) {
            return leftEmpty
                ? MergeType.deleted(true, false)
                : MergeType.modified(true, false);
        }

        // Both sides changed the base's lines. Identical changes are not a disagreement.
        if (equality.test(Side.LEFT, Side.RIGHT)) {
            return MergeType.modified(true, true);
        }
        return MergeType.conflict(bothSidesHaveContent(range) && canResolve.getAsBoolean());
    }

    /**
     * Whether a range is a <b>modify/delete</b> shape: one side has content here and the other has
     * nothing, while the base had content.
     *
     * <p>This is check C2 of {@code JETBRAINS_PORT.md} § 5.3, and it is the same rule as
     * {@code DESIGN_NEVER_AUTO_RESOLVED.md} § 2's substitutive/additive split — arrived at
     * <b>independently</b>, which is why it is spelled out here rather than left implicit. Upstream
     * encodes it twice, in its range table and again as
     * {@code MergeConflictModel.isModifyDeleteFileConflict}; this module encodes it here and in
     * {@code MergeFileTool} — and a rule two projects derive separately is worth naming where a reader
     * meets it, so that a future change to one is visibly a change to both.
     *
     * <h2>Why it must be refused rather than resolved</h2>
     *
     * <p>One branch decided these lines should not exist and the other decided they should, edited. That
     * is not a disagreement about <em>content</em> — it is a disagreement about whether there is any — and
     * no comparison of text can arbitrate it. Textually the two sides look like "one is empty, one is
     * not", which is exactly the shape that also describes a plain insertion; the difference is whether
     * the <em>base</em> had lines here. A guard that only looked at emptiness would resolve a deletion as
     * an addition, which is the silent regression the whole design exists to prevent.
     *
     * <p>Note the contrast with {@link #bothSidesHaveContent}: that asks whether a conflict <em>may</em> be
     * attempted, this asks whether the shape is one no automatic answer may touch. A caller can use either
     * alone; a caller that uses both is asking the two questions the design actually has.
     *
     * @return which side deleted, or {@code null} when the range is not a modify/delete shape
     * @see <a href="../../../../../../../DESIGN_NEVER_AUTO_RESOLVED.md">DESIGN_NEVER_AUTO_RESOLVED.md § 2</a>
     */
    public static DeletedSide modifyDeleteShape(MergeRange range) {
        if (range.baseIsEmpty()) {
            // The base had nothing here, so an empty side means an insertion by the other, not a deletion.
            // This is the branch that makes the guard safe: without it, every one-sided insertion would
            // look like a deletion competing with an edit.
            return null;
        }
        boolean leftEmpty = range.leftIsEmpty();
        boolean rightEmpty = range.rightIsEmpty();
        if (leftEmpty && !rightEmpty) {
            return DeletedSide.LEFT;
        }
        if (rightEmpty && !leftEmpty) {
            return DeletedSide.RIGHT;
        }
        // Both empty is a deletion by both, which agrees; neither empty is an ordinary modification.
        return null;
    }

    /** Which branch removed the lines a modify/delete shape disagrees about. */
    public enum DeletedSide {
        LEFT,
        RIGHT
    }

    /**
     * The table, for callers that have already decided whether a text pass may attempt a conflict.
     *
     * <p>Use this overload when the answer to "can a text pass resolve this?" is already known — for
     * instance because the pass has run and its answer is in hand. {@link #getMergeType} is for callers
     * that need that question answered as part of the classification.
     */
    public static MergeType getMergeTypeForResult(MergeRange range,
                                                  SideEmptiness emptiness,
                                                  SideEquality equality,
                                                  SideEquality trueEquality,
                                                  boolean resolvableResult) {
        return getMergeType(range, emptiness, equality, trueEquality, () -> resolvableResult);
    }

    /** The three sides of a merge, in the order that matters: the base is the second one. */
    public enum Side {
        LEFT,
        BASE,
        RIGHT
    }
    /** Whether a side has no elements in a range. */
    @FunctionalInterface
    public interface SideEmptiness {
        boolean isEmpty(Side side);
    }

    /** Policy-aware equality between two sides of a range. */
    @FunctionalInterface
    public interface SideEquality extends BiPredicate<Side, Side> {
    }

    /**
     * The type of every range in a list, in order.
     *
     * <p>Convenience for callers that have a whole merge to classify, which is the common case.
     */
    public static List<MergeType> getMergeTypes(List<MergeRange> ranges,
                                                SideEmptiness emptiness,
                                                SideEquality equality,
                                                SideEquality trueEquality,
                                                java.util.function.BooleanSupplier canResolve) {
        return ranges.stream()
            .map(range -> getMergeType(range, emptiness, equality, trueEquality, canResolve))
            .toList();
    }
}
