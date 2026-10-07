// {@link com.codebuddy.merge.TieredResolution} Resolves one block's conflicts in analysis-tier order.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves one block's conflicts <b>in tier order</b>, so that a conflict a higher tier resolves reliably
 * is never handed to a lower one (unified plan step 4.19, DEC-046 clauses 9–11).
 *
 * <h2>What this replaces, and why the difference is the whole requirement</h2>
 *
 * <p>The module used to resolve every conflict of a block and then arbitrate: the strongest answer won and
 * the others were reported as {@code outranked}. An outranked claim still <b>exists</b> — the lower tier was
 * called, spent its time, and put a claim into the arbitration and into the report. The requirement is
 * stronger: <em>"I do not want lower level resolver to even see conflict"</em>. So resolution runs tier by
 * tier over a {@link ResolutionPass}, and a conflict a reliable claim has settled has left
 * {@link ResolutionPass#live()} before the next tier is asked. <b>The call is never made</b>, which is the
 * only form of the requirement that is checkable — {@link ClaimSource} is where a test observes it.
 *
 * <h2>The order is the declaration; the removal is the record</h2>
 *
 * <p>Tiers are ordered by what each resolver <b>declares</b> it can reach
 * ({@link ConflictResolvers#inTierOrder}), because a tier can only be skipped before it runs and a recorded
 * level does not exist until the resolver has been called (DEC-046 clause 13). What a claim may
 * <b>settle</b> is then decided from what it actually recorded, by {@link Reliability} — the declaration
 * orders the pass, and it never removes anything by itself.
 *
 * <h2>A removed conflict has no claim of its own</h2>
 *
 * <p>{@link Result#claims()} is index-aligned with the conflicts and holds {@code null} exactly where a
 * conflict was settled by <em>another</em> conflict's claim: nothing was asked about it, so there is nothing
 * to record for it. The claim that settled it is at the settling conflict's own index. That is also why
 * filtering the nulls is the whole of the consumer's change: what decides the block is the claims that
 * exist, not the conflicts that were detected.
 */
public final class TieredResolution {

    /**
     * Resolves one conflict, by index.
     *
     * <p>An interface rather than a {@code Function<Conflict, …>} because the caller routinely holds two
     * lists: the conflicts as detection produced them, whose regions the {@link ResolutionPass} needs, and
     * the same conflicts carrying the block's region, which is what a recorded resolution must name. The
     * index is the one fact both share, and it is also what a test observes to prove a tier was never
     * asked.
     */
    @FunctionalInterface
    public interface ClaimSource {

        /**
         * The claim for the conflict at {@code index}, or {@code null} when the source declines to answer.
         *
         * <p>Called at most once per conflict — a conflict that a higher tier has settled is never offered
         * here, and that is the property the two acceptance cases assert.
         */
        ConflictResolution claim(int index, Conflict conflict);
    }

    /**
     * One conflict a claim settled, recorded so the report can name it.
     *
     * @param index     the position of the removed conflict
     * @param type      the removed conflict's type, which is what a reviewer recognises it by
     * @param region    the region that was settled
     * @param by        the claim that settled it, which came from another conflict
     * @param tier      the level that claim recorded — the evidence the removal rests on
     */
    public record Removal(int index, ConflictType type, Region region,
                          ConflictResolution by, AnalysisLevel tier) {
    }

    /**
     * What one tiered pass produced.
     *
     * @param pass     the working set afterwards, with every settled conflict marked
     *     {@link ConflictState#RESOLVED}
     * @param claims   index-aligned claims; {@code null} where a conflict was settled by another conflict's
     *     claim and was therefore never asked about
     * @param removals the conflicts that were taken out of the lower tiers' hands, in the order it
     *     happened, so the report can name each one
     */
    public record Result(ResolutionPass pass, List<ConflictResolution> claims, List<Removal> removals) {

        public Result {
            claims = Collections.unmodifiableList(new ArrayList<>(claims));
            removals = List.copyOf(removals);
        }

        /**
         * The claims that decide the block: every non-null claim, in conflict order.
         *
         * <p>A removed conflict contributes nothing here, which is the point — the block is decided by the
         * answers that exist rather than by the objections that would have.
         */
        public List<ConflictResolution> decidingClaims() {
            List<ConflictResolution> deciding = new ArrayList<>();
            for (ConflictResolution claim : claims) {
                if (claim != null) {
                    deciding.add(claim);
                }
            }
            return List.copyOf(deciding);
        }
    }

    private TieredResolution() {
    }

    /**
     * Resolve the conflicts of one block, strongest tier first.
     *
     * @param detected  the conflicts as detection produced them, whose regions are the ones a removal is
     *                  judged on; the pass must not be built from conflicts already stamped with the
     *                  block's region, or every conflict would appear to cover every other
     * @param block     the region of the base version the block covers, or {@link Region#unknown()}
     * @param resolvers the resolvers to run, in any order — this method tiers them
     * @param source    how one conflict is resolved; never called for a conflict an earlier tier settled
     */
    public static Result resolve(List<Conflict> detected, Region block,
                                 List<ConflictResolver> resolvers, ClaimSource source) {
        ResolutionPass pass = ResolutionPass.of(block, detected);
        List<ConflictResolution> claims = new ArrayList<>(Collections.nCopies(detected.size(), null));
        List<Removal> removals = new ArrayList<>();
        Map<ConflictType, AnalysisLevel> declaredTiers = declaredTiers(resolvers);

        for (ConflictResolver tier : ConflictResolvers.inTierOrder(resolvers)) {
            ConflictType owned = tier.supportedType();
            for (int index = 0; index < detected.size(); index++) {
                Conflict conflict = detected.get(index);
                if (conflict.getType() != owned || !pass.all().get(index).isLive()) {
                    // Not this tier's question, or already settled by a stronger one: the resolver is not
                    // asked, and that is the whole mechanism.
                    continue;
                }
                ConflictResolution claim = source.claim(index, conflict);
                claims.set(index, claim);
                if (claim == null) {
                    continue;
                }
                pass = settle(pass, detected, claims, removals, declaredTiers, index, claim);
            }
        }

        // A conflict whose type no resolver in the set owns has not been asked about at all, and the
        // fallback must still be recorded: an unhandled type is a manual answer rather than silence.
        // Only those are swept - a conflict whose owner *declined* was asked once, and asking it again
        // would double-count the call and change what declining means.
        for (int index = 0; index < detected.size(); index++) {
            Conflict conflict = detected.get(index);
            if (claims.get(index) == null && pass.all().get(index).isLive()
                && !declaredTiers.containsKey(conflict.getType())) {
                claims.set(index, source.claim(index, conflict));
            }
        }

        return new Result(pass, claims, removals);
    }

    /**
     * The strongest level each resolver in the set declares it can reach, by the type it owns.
     *
     * <p>Used only as the <b>direction</b> of the hierarchy, never as a reason to remove anything: a claim
     * may take a conflict out of a weaker tier's hands and never out of a stronger one's.
     */
    private static Map<ConflictType, AnalysisLevel> declaredTiers(List<ConflictResolver> resolvers) {
        Map<ConflictType, AnalysisLevel> declared = new EnumMap<>(ConflictType.class);
        for (ConflictResolver resolver : resolvers) {
            ConflictType type = resolver.supportedType();
            if (type != null) {
                declared.put(type, resolver.maxAnalysisLevel());
            }
        }
        return declared;
    }

    /**
     * Mark what one claim settles: its own region when it explains it, and every other still-live conflict
     * whose region it explains <em>and</em> whose tier it may speak for.
     *
     * <h2>Why reliability alone was not enough — a defect found by running the arbitration tests</h2>
     *
     * <p>The first version of this method let any reliable claim settle any conflict whose region it
     * explained. Running step 4.6's arbitration tests showed what that costs: a claim that recorded
     * {@link AnalysisLevel#TEXT_LOCAL} settled a conflict whose resolver declares
     * {@link AnalysisLevel#STRUCTURE}, and a block that had been left for a human since
     * {@code equalEvidenceOutranksNothing} became {@code APPLIED_AUTO}. That is a silent widening — the
     * block applied because of a mechanism nobody asked for, not because anyone decided the weaker claim was
     * good enough — and it is the failure this bound exists to prevent.
     *
     * <p>So the hierarchy does two jobs, and they are not the same job: the tiers decide <b>who is asked
     * first</b>, and the hierarchy decides <b>which direction a removal may run</b> — a claim speaks for the
     * conflicts at or below its own recorded level, and never for one above it. {@link Reliability} then
     * decides whether a permitted removal happens at all. Neither replaces the other: reliability without
     * the bound widens what applies, and the bound without reliability would be the licence DEC-046 clause 11
     * refuses.
     *
     * <p>A claim settling <b>its own</b> conflict is deliberately not bounded this way: a resolver whose
     * record is below its declaration — {@link MemberAddConflictResolver} without a classpath — is still the
     * one authority on the question it was asked, and its own answer needs no permission from the scale.
     */
    private static ResolutionPass settle(ResolutionPass pass, List<Conflict> detected,
                                         List<ConflictResolution> claims, List<Removal> removals,
                                         Map<ConflictType, AnalysisLevel> declaredTiers,
                                         int index, ConflictResolution claim) {
        AnalysisLevel tier = claim.getAnalysisLevel();
        if (Reliability.of(claim, detected.get(index)).isReliable()) {
            // The answer to the question this resolver was asked. It is settled regardless of the tier
            // bound below, because a resolver is the authority on its own conflict and needs no permission
            // from the scale to have answered it.
            pass = pass.withResolved(index, claim);
        }
        for (int other = 0; other < detected.size(); other++) {
            if (other == index || !pass.all().get(other).isLive()) {
                continue;
            }
            Conflict otherConflict = detected.get(other);
            AnalysisLevel otherTier = declaredTiers.get(otherConflict.getType());
            if (otherTier == null || !tier.isAtLeast(otherTier)) {
                // A type no resolver in the set owns is not removed by anything. Silence is not evidence,
                // and the module's own default for a resolver that declares nothing (TEXT_LOCAL) is a
                // statement about a resolver, not about a type nobody speaks for. A restricted resolver set
                // therefore leaves its unowned conflicts exactly where today's code leaves them.
                continue;
            }
            Region otherRegion = otherConflict.getRegion();
            if (!Reliability.of(claim, otherConflict).isReliable()) {
                continue;
            }
            pass = pass.withResolved(other, claim);
            removals.add(new Removal(other, otherConflict.getType(), otherRegion, claim, tier));
        }
        return pass;
    }
}
