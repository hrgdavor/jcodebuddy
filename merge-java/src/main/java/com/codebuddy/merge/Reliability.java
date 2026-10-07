// {@link com.codebuddy.merge.Reliability} Whether a claim explains a region well enough to settle it.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whether one claim explains one region well enough to <b>settle</b> it — the predicate hierarchical
 * resolution turns on (unified plan step 4.19, DEC-046 clauses 4 and 10).
 *
 * <h2>Why this is a predicate rather than a reputation</h2>
 *
 * <p>The requirement is that a lower tier must not even see a conflict a higher tier knows how to
 * resolve: the conflict leaves the working set and no objection to it is constructed
 * ({@link ConflictState#RESOLVED}). That is far stronger than "this resolver is usually good", and it
 * cannot rest on a resolver's name, its {@link AnalysisLevel} declaration, or the level it recorded. A
 * rank decides <em>who is asked first</em>; this decides <em>what leaves the working set</em>.
 *
 * <h2>The predicate</h2>
 *
 * <p>A claim is reliable over a region when all of these hold:
 *
 * <ol>
 *   <li><b>it may be applied</b> — {@link ConflictResolution.ResolutionKind#AUTO}, or
 *       {@link ConflictResolution.ResolutionKind#DEFERRED}, which is a recorded human decision and
 *       therefore a decision. A {@code REVIEW} or {@code MANUAL} answer is never reliable over anything,
 *       whatever its level, so the hierarchy can never promote an answer into application and
 *       {@code DESIGN_NEVER_AUTO_RESOLVED.md} is untouched;</li>
 *   <li><b>the region is known</b> — {@link Region#unknown()} spans no lines, so an answer over it
 *       explains nothing and settles nothing, and saying it settled something is reported by
 *       {@link ResolutionPass.PartitionReport#unplaced()};</li>
 *   <li><b>it explains every line of the region</b>, in one of the ways {@code HIERARCHICAL_RESOLUTION.md}
 *       § 3.3 names:
 *       <ul>
 *         <li>the claim's {@link ConflictResolution#getExplainedSpan() explained span} covers the region —
 *             the resolver recognised the declarations forming it, or the region is its own domain;</li>
 *         <li>or its applied text <b>keeps every line of both sides</b> in that region, which is checked
 *             here rather than declared, so a {@code KEEP_BOTH} or {@code MERGE_SAFE} answer that really
 *             does include both branches cannot lose anything by settling the region.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>A claim that fails any of them is still a claim. It goes up against the others exactly as it did
 * before — equal evidence deciding nothing — it simply does not <em>remove</em> anything.
 *
 * <h2>What the predicate deliberately does not read</h2>
 *
 * <p>Not the level. A claim recorded at {@link AnalysisLevel#PROJECT_TYPES} with no evidence for the
 * lines in front of it is {@link Verdict#UNEXPLAINED} here, which is the point of clause 11: a resolver
 * can buy being asked earlier, and can never buy authority.
 */
public final class Reliability {

    /**
     * The verdict, and the vocabulary a report uses to say why a conflict was or was not removed.
     */
    public enum Verdict {

        /** The claim explains the whole region and may be applied, so the region is settled. */
        RELIABLE,

        /**
         * The claim may not be applied at all — a {@code REVIEW} or {@code MANUAL} answer. It is not that
         * the evidence was thin; the answer itself asks for a person, so it settles nothing.
         */
        NOT_APPLICABLE,

        /**
         * The region has no location, so there is nothing for the claim to explain. An unknown region
         * spans no lines and settles nothing.
         */
        UNPLACED,

        /**
         * The claim may be applied and the region is placed, but nothing in the claim accounts for the
         * lines in it: no explained span covers them and the applied text does not keep both sides.
         */
        UNEXPLAINED
    }

    /**
     * One verdict about one claim over one region.
     *
     * @param verdict what the claim may do with this region
     * @param region  the region the verdict is about; {@link Region#unknown()} when the caller had none
     * @param reason  one sentence naming the evidence, for a report or an assertion message
     */
    public record Result(Verdict verdict, Region region, String reason) {

        public Result {
            region = region == null ? Region.unknown() : region;
        }

        /** True when the region may be settled, so the conflict leaves the working set. */
        public boolean isReliable() {
            return verdict == Verdict.RELIABLE;
        }

        @Override
        public String toString() {
            return verdict + " over " + region + ": " + reason;
        }
    }

    private Reliability() {
    }

    /**
     * Judge whether {@code claim} explains {@code region} well enough to settle it.
     */
    public static Result of(ConflictResolution claim, Region region) {
        if (claim == null) {
            return new Result(Verdict.UNEXPLAINED, region, "there is no claim to settle anything");
        }

        ConflictResolution.ResolutionKind kind = claim.getKind();
        if (kind != ConflictResolution.ResolutionKind.AUTO
            && kind != ConflictResolution.ResolutionKind.DEFERRED) {
            return new Result(Verdict.NOT_APPLICABLE, region,
                "a " + kind + " answer cannot be applied, so it settles nothing");
        }

        if (region == null || !region.isKnown()) {
            return new Result(Verdict.UNPLACED, region,
                "the region has no location, so there is nothing to explain");
        }

        Region explained = claim.getExplainedSpan();
        if (explained.covers(region)) {
            return new Result(Verdict.RELIABLE, region,
                "the claim's explained span " + explained + " accounts for the whole region");
        }

        if (keepsBothSides(claim)) {
            return new Result(Verdict.RELIABLE, region,
                "the applied text keeps every line of both sides in " + region);
        }

        return new Result(Verdict.UNEXPLAINED, region,
            "nothing in the claim accounts for " + region
                + ": its explained span is " + explained
                + " and its applied text does not keep both sides");
    }

    /**
     * True when the applied text keeps every non-blank line of both sides.
     *
     * <p>This is the second way to explain a region, and it is the one that needs no declaration from the
     * resolver: an answer that includes both branches entirely cannot be dropping what the region was
     * about. A {@code PREFER_BRANCH1/2} answer fails it by construction, because dropping the other side
     * is the decision rather than an accident — which is why such an answer must declare an explained span
     * if it is to settle anything.
     *
     * <p>An empty or blank applied text is never reliable: it keeps nothing, and the two sides are only
     * blank when there is no content to keep.
     */
    public static boolean keepsBothSides(ConflictResolution claim) {
        if (claim == null || claim.getResolvedCode().isBlank()) {
            return false;
        }
        Set<String> applied = normalisedLines(claim.getResolvedCode());
        return keepsEveryLine(applied, claim.getBranch1Code())
            && keepsEveryLine(applied, claim.getBranch2Code());
    }

    /** True when every non-blank line of {@code side} appears in {@code appliedLines}. */
    public static boolean keepsEveryLine(Set<String> appliedLines, String side) {
        for (String line : normalisedLines(side)) {
            if (!appliedLines.contains(line)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The non-blank lines of {@code text}, each stripped and with internal whitespace runs collapsed.
     *
     * <p>Normalised because resolvers re-render what they merge — an import union is sorted, not
     * concatenated — so a byte comparison would call a faithful merge a loss. The single definition of the
     * question, shared by this predicate and by {@link MergeFileTool}'s coverage rule, because two answers
     * to "did this line survive" is how the two rules would drift apart.
     */
    public static Set<String> normalisedLines(String text) {
        Set<String> lines = new LinkedHashSet<>();
        if (text == null) {
            return lines;
        }
        for (String line : text.split("\n")) {
            String collapsed = line.strip().replaceAll("\\s+", " ");
            if (!collapsed.isEmpty()) {
                lines.add(collapsed);
            }
        }
        return lines;
    }
}
