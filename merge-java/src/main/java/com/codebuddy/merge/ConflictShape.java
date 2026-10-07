// {@link com.codebuddy.merge.ConflictShape} What kind of change a conflict is, beside what it is about.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.merge.MergeRange;
import com.codebuddy.merge.jetbrains.merge.MergeRangeBuilder;
import com.codebuddy.merge.jetbrains.merge.MergeRangeUtil;
import com.codebuddy.merge.jetbrains.merge.MergeType;
import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import com.codebuddy.merge.jetbrains.text.TextLines;

import java.util.List;

/**
 * The <b>shape</b> of a conflict: inserted, deleted, modified or conflicting — the orthogonal half of the model,
 * beside {@link ConflictType}'s domain half (unified plan step 4.12).
 *
 * <h2>Why two taxonomies rather than one</h2>
 *
 * <p>{@link ConflictType} says what a conflict <em>is about</em> — an import addition, an overload clash, a
 * widened declaration. It cannot say "both sides inserted here", and that is a different question a reviewer
 * needs answered: "import addition, both sides inserted" is two facts, and the second one is what tells them
 * whether there is anything to choose. A shape cannot say "this is a type widening" either. Neither replaces the
 * other, and adding this one **keeps {@code ConflictType} unchanged** — a widening of the model rather than a
 * second opinion about it.
 *
 * <h2>Where the vocabulary comes from</h2>
 *
 * <p>It is upstream's {@link MergeType.Kind}, ported rather than invented: the four kinds are the cases a
 * three-way merge can be in, and they are already what {@code MergeRangeUtil.getMergeType} computes for a range.
 * This enum exists because a <em>conflict</em> may span several ranges, and because the module's own vocabulary
 * should not make every caller import the ported package to ask a question about a conflict.
 *
 * <h2>What it must never do</h2>
 *
 * <p>Inform only. The shape says what a change <em>is</em>; it never promotes a {@code REVIEW} or {@code MANUAL}
 * resolution into an applied one, which stays {@code DESIGN_NEVER_AUTO_RESOLVED.md}'s rule and
 * {@link MergeFileTool}'s decision. And a conflict whose shape cannot be computed — a merge-style block with no
 * base, where every region is unknown — records {@link #UNKNOWN} rather than guessing, because a wrong shape is
 * worse than an absent one: it would be read as evidence.
 */
public enum ConflictShape {

    /** The base had nothing here and at least one side added something. */
    INSERTED,

    /** The base had lines here and neither side kept them. */
    DELETED,

    /** The base had lines here and they were changed; which side changed them is the resolution's own record. */
    MODIFIED,

    /** Both sides changed the same region, differently. */
    CONFLICT,

    /**
     * The shape could not be computed, because the conflict has no base to compare against — git's default merge
     * style carries only two sides.
     *
     * <p>Recorded rather than guessed: with no base there is no way to tell "both sides inserted" from "one side
     * inserted and the other deleted", and those are opposite readings of the same two texts.
     */
    UNKNOWN;

    /**
     * The shape of one merge type, so a caller holding a {@link MergeType} does not map the kinds by hand — and so
     * a kind added upstream becomes a compile error here rather than a silent default.
     */
    public static ConflictShape of(MergeType.Kind kind) {
        if (kind == null) {
            return UNKNOWN;
        }
        return switch (kind) {
            case INSERTED -> INSERTED;
            case DELETED -> DELETED;
            case MODIFIED -> MODIFIED;
            case CONFLICT -> CONFLICT;
        };
    }

    /** True when the base had nothing here, so at least one side added. */
    public boolean isInsertion() {
        return this == INSERTED;
    }

    /** True when both sides changed the same region differently, so a choice is needed rather than a merge. */
    public boolean isConflict() {
        return this == CONFLICT;
    }

    /**
     * True when one side changed the region and the other left it alone — the shape the line-level detection calls
     * a structural change and this calls mechanical.
     *
     * <p>This is the general form of the defect steps 4.5 and 4.6 measured: a block where one branch inserted and
     * the other did nothing needs no decision, and it was left for a human because a line comparison could not say
     * so. The shape can, which is why it exists.
     */
    public boolean isOneSided() {
        return this == INSERTED || this == DELETED || this == MODIFIED;
    }

    /**
     * The shape of a conflict's three sides, computed by the ported classifier rather than by a second opinion.
     *
     * <p>{@code MergeRangeBuilder} finds the ranges and {@code MergeRangeUtil.getMergeType} names each one — the
     * four kinds are its vocabulary, and calling it is what keeps this model and the ported one from drifting. The
     * two callbacks it needs are answered from the range itself (which side is empty where) and from the three
     * texts (are these two sides policy-equal over this extent). It is also the ported classifier's first caller in
     * this module: it was written and tested in step 4.9 and nothing had asked it anything until now.
     *
     * @param base      the base side, which decides every reading below
     * @param baseKnown false for git's default merge style, where there is no base at all — the shape is then
     *                  {@link #UNKNOWN} rather than guessed, because with no base "both sides inserted" and "one
     *                  side inserted while the other deleted" are the same two texts
     */
    public static ConflictShape of(String base, String ours, String theirs,
                                   ComparisonPolicy policy, boolean baseKnown) {
        if (!baseKnown) {
            return UNKNOWN;
        }
        List<MergeType> types = typesOf(base, ours, theirs, policy);
        if (types.isEmpty()) {
            // The sides agree, so there is no change for a shape to describe.
            return UNKNOWN;
        }
        ConflictShape combined = null;
        for (MergeType type : types) {
            ConflictShape shape = of(type.kind());
            if (combined == null) {
                combined = shape;
            } else if (combined != shape) {
                // Several ranges of one conflict, of different kinds: the most demanding reading wins, because a
                // shape is what a reader uses to judge whether anything needs choosing.
                combined = combined == CONFLICT || shape == CONFLICT ? CONFLICT : MODIFIED;
            }
        }
        return combined == null ? UNKNOWN : combined;
    }

    /**
     * Every range of the change, with the ported classifier's own naming of it — kind and which sides changed.
     *
     * <p>Exposed because a <b>shape</b> is a summary and the benchmark's vectors are about the detail: upstream's
     * change-type vectors assert the exact kinds and sides per range (and how many there are), which a single
     * combined shape cannot answer. The parity gate grades these, so this is the ported classifier's public face
     * as well as {@link #of}'s implementation.
     *
     * @return one entry per range, in base order; empty when the sides agree
     */
    public static List<MergeType> typesOf(String base, String ours, String theirs, ComparisonPolicy policy) {
        String baseText = base == null ? "" : base;
        String leftText = ours == null ? "" : ours;
        String rightText = theirs == null ? "" : theirs;
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;

        List<MergeRangeBuilder.MergeChange> changes =
            MergeRangeBuilder.build(baseText, leftText, rightText, effective);
        if (changes.isEmpty()) {
            return List.of();
        }

        List<String> baseLines = TextLines.of(baseText).lines();
        List<String> leftLines = TextLines.of(leftText).lines();
        List<String> rightLines = TextLines.of(rightText).lines();

        List<MergeType> types = new java.util.ArrayList<>(changes.size());
        for (MergeRangeBuilder.MergeChange change : changes) {
            MergeRange range = change.range();
            boolean leftChanged = change.leftChanged();
            boolean rightChanged = change.rightChanged();
            types.add(MergeRangeUtil.getMergeType(range,
                // "Empty" here means the side has no content over this extent, and a side that did NOT change has
                // the base's content there rather than nothing: the range's own empty extent for that side is the
                // absence of a *change*, not the absence of lines. Reading it as emptiness made every one-sided
                // modification report as a conflict.
                side -> switch (side) {
                    case LEFT -> leftChanged ? range.leftIsEmpty() : range.baseIsEmpty();
                    case BASE -> range.baseIsEmpty();
                    case RIGHT -> rightChanged ? range.rightIsEmpty() : range.baseIsEmpty();
                },
                equalityOf(effective, baseLines, leftLines, rightLines, range, leftChanged, rightChanged),
                // Byte equality is not needed here: it only distinguishes *which* side really changed when both are
                // policy-equal to the base, and passing null makes that case report both sides — the honest reading
                // when the caller did not ask for the distinction.
                null,
                // Whether a text pass could resolve a conflict does not change its KIND, which is all a shape asks.
                // Claiming it could would put a resolution's fate inside a description of the change.
                () -> false));
        }
        return List.copyOf(types);
    }

    /** Policy-aware equality between two of the three sides, over one range's extent for each. */
    private static MergeRangeUtil.SideEquality equalityOf(ComparisonPolicy policy,
                                                          List<String> baseLines, List<String> leftLines,
                                                          List<String> rightLines, MergeRange range,
                                                          boolean leftChanged, boolean rightChanged) {
        return (first, second) -> {
            List<String> one = sideLines(first, baseLines, leftLines, rightLines, range,
                leftChanged, rightChanged);
            List<String> other = sideLines(second, baseLines, leftLines, rightLines, range,
                leftChanged, rightChanged);
            if (one.size() != other.size()) {
                return false;
            }
            for (int index = 0; index < one.size(); index++) {
                if (!policy.normaliseLine(one.get(index)).equals(policy.normaliseLine(other.get(index)))) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * One side's content over this range.
     *
     * <p><b>A side that did not change has the base's lines here, not an empty stretch.</b> The range carries an
     * empty extent for such a side — it records where that side *changed* — and reading the extent as the content is
     * the mistake that made every one-sided modification look like a conflict. The same rule is what the greedy pass
     * and {@link BlockComposition} each had to learn, which is a sign it is the only way to read a merge range at
     * all: <em>a side with no change kept what the base had</em>.
     */
    private static List<String> sideLines(MergeRangeUtil.Side side,
                                          List<String> baseLines, List<String> leftLines, List<String> rightLines,
                                          MergeRange range, boolean leftChanged, boolean rightChanged) {
        return switch (side) {
            case LEFT -> leftChanged
                ? slice(leftLines, range.start1(), range.end1())
                : slice(baseLines, range.start2(), range.end2());
            case BASE -> slice(baseLines, range.start2(), range.end2());
            case RIGHT -> rightChanged
                ? slice(rightLines, range.start3(), range.end3())
                : slice(baseLines, range.start2(), range.end2());
        };
    }

    private static List<String> slice(List<String> lines, int from, int to) {
        int start = Math.max(0, Math.min(from, lines.size()));
        int end = Math.max(start, Math.min(to, lines.size()));
        return lines.subList(start, end);
    }
}
