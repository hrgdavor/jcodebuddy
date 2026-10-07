// {@link com.codebuddy.merge.TieredResolutionTest} The lower tier is never asked about a settled conflict (plan step 4.19).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tiered resolution: a conflict a reliable claim settles is taken out of the working set, and the resolver
 * that would have been asked about it <b>is never asked</b> (unified plan step 4.19, DEC-046 clauses 9–11).
 *
 * <h2>The acceptance criterion, in one sentence</h2>
 *
 * <p><b>The claim source records which conflicts it was asked about, and a settled conflict is absent from
 * that record.</b> That is the only form of "I do not want lower level resolver to even see conflict" that
 * can be checked: an assertion on the outcome cannot tell "never asked" from "asked and overruled", which is
 * precisely the distinction this step exists to make.
 *
 * <h2>Two bounds keep it from being a licence, and both are asserted here</h2>
 *
 * <ul>
 *   <li><b>Reliability</b> — a claim settles a region only where it may be applied and explains every line
 *       of it. A claim that keeps nothing removes nothing (the negative control).</li>
 *   <li><b>Direction</b> — a claim speaks for conflicts at or below its own recorded level and never for one
 *       above it. This bound was not in the first version, and running step 4.6's arbitration tests is what
 *       found the need for it: reliability alone let a {@code TEXT_LOCAL} claim settle a {@code STRUCTURE}
 *       conflict and turned a block that had been left for a human into an applied one.
 *       {@link #aWeakerTierNeverSettlesAStrongerConflict()} is that defect as a test.</li>
 * </ul>
 */
class TieredResolutionTest {

    private static final Region REGION = Region.spanning(10, 20);
    private static final String KEEPS_BOTH = "one\ntwo";

    /**
     * The conflicts a block carries, with distinct types so the tier of each one is unambiguous.
     */
    private static Conflict conflict(ConflictType type, Region region) {
        return new Conflict(type, "Sample.java", type.name(), "base", "one", "two", region);
    }

    /**
     * A conflict with its own two sides.
     *
     * <p>Needed wherever a test is about which conflict is settled: the kept-lines way of explaining a
     * region compares the applied text against <b>the settled conflict's</b> sides, so two conflicts that
     * share their sides are settled by each other's answers, and a test that meant to show "this claim has
     * nothing to do with that region" would show the opposite.
     */
    private static Conflict conflict(ConflictType type, Region region, String ours, String theirs) {
        return new Conflict(type, "Sample.java", type.name(), "base", ours, theirs, region);
    }

    /** A resolver that declares a tier and answers nothing itself; the source supplies the claims. */
    private static ConflictResolver tier(ConflictType type, AnalysisLevel level) {
        return new ConflictResolver() {
            @Override
            public ConflictType supportedType() {
                return type;
            }

            @Override
            public ConflictResolution resolve(Conflict conflict) {
                return null;
            }

            @Override
            public List<FixPath> getFixPaths(Conflict conflict) {
                return List.of();
            }

            @Override
            public AnalysisLevel maxAnalysisLevel() {
                return level;
            }
        };
    }

    /** Records every conflict it is asked about, and answers with whatever the test supplies. */
    private static final class Recorder implements TieredResolution.ClaimSource {

        private final List<Integer> asked = new ArrayList<>();
        private final java.util.Map<Integer, ConflictResolution> answers = new java.util.HashMap<>();

        Recorder answer(int index, ConflictResolution claim) {
            answers.put(index, claim);
            return this;
        }

        @Override
        public ConflictResolution claim(int index, Conflict conflict) {
            asked.add(index);
            return answers.get(index);
        }
    }

    private static ConflictResolution claimKeepingBoth(Conflict source, AnalysisLevel recorded) {
        return ConflictResolution.auto(source, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode(KEEPS_BOTH)
            .analysisLevel(recorded)
            .explanation("keeps both sides")
            .build();
    }

    /** The same claim, told which region its own evidence accounts for. */
    private static ConflictResolution explained(ConflictResolution claim, Region span) {
        return ConflictResolution.copyOf(claim).explainedSpan(span).build();
    }

    /** The same claim, re-kinded — used to show that a REVIEW answer settles nothing. */
    private static ConflictResolution asKind(ConflictResolution claim,
                                            ConflictResolution.ResolutionKind kind) {
        return ConflictResolution.copyOf(claim).kind(kind).build();
    }

    @Test
    @DisplayName("a settled conflict is never handed to the weaker resolver")
    void aSettledConflictIsNeverAskedAbout() {
        List<Conflict> detected = List.of(
            conflict(ConflictType.MEMBER_ADD, REGION),
            conflict(ConflictType.API_INCOMPATIBILITY, REGION));
        Recorder source = new Recorder()
            .answer(0, explained(claimKeepingBoth(detected.get(0), AnalysisLevel.STRUCTURE), REGION))
            .answer(1, claimKeepingBoth(detected.get(1), AnalysisLevel.TEXT_LOCAL));

        TieredResolution.Result result = TieredResolution.resolve(detected, REGION, List.of(
            tier(ConflictType.MEMBER_ADD, AnalysisLevel.PROJECT_TYPES),
            tier(ConflictType.API_INCOMPATIBILITY, AnalysisLevel.TEXT_LOCAL)), source);

        assertEquals(List.of(0), source.asked,
            "the text-level resolver must never be asked about a conflict a stronger claim settled");
        assertNull(result.claims().get(1), "a settled conflict has no claim of its own");
        assertEquals(ConflictState.RESOLVED, result.pass().stateOf(0),
            "the claim settles its own region too, so no tier is asked about it again");
        assertEquals(ConflictState.RESOLVED, result.pass().stateOf(1));
        assertTrue(result.pass().live().isEmpty(), "nothing is left for any tier to be asked about");
        assertEquals(1, result.decidingClaims().size(), "what decides the block is the answer that exists");
        assertEquals(1, result.removals().size());
        assertEquals(ConflictType.API_INCOMPATIBILITY, result.removals().get(0).type());
        assertEquals(AnalysisLevel.STRUCTURE, result.removals().get(0).tier());
    }

    @Test
    @DisplayName("the source is asked once per conflict, strongest tier first")
    void theTierOrderDecidesWhoIsAskedFirst() {
        List<Conflict> detected = List.of(
            conflict(ConflictType.API_INCOMPATIBILITY, Region.spanning(30, 40), "alpha", "beta"),
            conflict(ConflictType.MEMBER_ADD, Region.spanning(10, 20), "gamma", "delta"));
        // Each claim keeps its own sides and nothing else, and the regions are disjoint and far apart, so
        // neither settles the other. That is what makes this a test of the order rather than of removal.
        Recorder source = new Recorder()
            .answer(0, claimKeepingBoth(detected.get(0), AnalysisLevel.TEXT_LOCAL))
            .answer(1, claimKeepingBoth(detected.get(1), AnalysisLevel.STRUCTURE));

        TieredResolution.resolve(detected, REGION, List.of(
            tier(ConflictType.API_INCOMPATIBILITY, AnalysisLevel.TEXT_LOCAL),
            tier(ConflictType.MEMBER_ADD, AnalysisLevel.PROJECT_TYPES)), source);

        assertEquals(List.of(1, 0), source.asked,
            "tier order decides who is asked first, not the order the resolvers were listed in");
    }

    @Test
    @DisplayName("negative control: a claim that keeps nothing settles nothing, and both tiers are asked")
    void aClaimThatExplainsNothingSettlesNothing() {
        List<Conflict> detected = List.of(
            conflict(ConflictType.MEMBER_ADD, REGION),
            conflict(ConflictType.API_INCOMPATIBILITY, REGION));
        ConflictResolution keepsOnlyOneSide = ConflictResolution.auto(detected.get(0),
                ConflictResolution.ResolutionStrategy.PREFER_BRANCH1)
            .resolvedCode("one")
            .analysisLevel(AnalysisLevel.PROJECT_TYPES)
            .build();
        Recorder source = new Recorder()
            .answer(0, keepsOnlyOneSide)
            .answer(1, claimKeepingBoth(detected.get(1), AnalysisLevel.TEXT_LOCAL));

        TieredResolution.Result result = TieredResolution.resolve(detected, REGION, List.of(
            tier(ConflictType.MEMBER_ADD, AnalysisLevel.PROJECT_TYPES),
            tier(ConflictType.API_INCOMPATIBILITY, AnalysisLevel.TEXT_LOCAL)), source);

        assertEquals(List.of(0, 1), source.asked, "nothing was settled, so nothing may be skipped");
        assertTrue(result.removals().isEmpty());
        assertEquals(ConflictState.OPEN, result.pass().stateOf(0),
            "the claim that explains nothing does not even settle its own conflict");
        assertEquals(2, result.decidingClaims().size(),
            "both claims still decide the block, exactly as they did before this step");
    }

    @Test
    @DisplayName("a REVIEW answer settles nothing, however well it explains the region")
    void aReviewAnswerSettlesNothing() {
        List<Conflict> detected = List.of(
            conflict(ConflictType.MEMBER_ADD, REGION),
            conflict(ConflictType.API_INCOMPATIBILITY, REGION));
        Recorder source = new Recorder()
            .answer(0, asKind(explained(claimKeepingBoth(detected.get(0), AnalysisLevel.PROJECT_TYPES), REGION), ConflictResolution.ResolutionKind.REVIEW))
            .answer(1, claimKeepingBoth(detected.get(1), AnalysisLevel.TEXT_LOCAL));

        TieredResolution.Result result = TieredResolution.resolve(detected, REGION, List.of(
            tier(ConflictType.MEMBER_ADD, AnalysisLevel.PROJECT_TYPES),
            tier(ConflictType.API_INCOMPATIBILITY, AnalysisLevel.TEXT_LOCAL)), source);

        assertEquals(List.of(0, 1), source.asked);
        assertTrue(result.removals().isEmpty());
    }

    @Test
    @DisplayName("a weaker tier never settles a stronger conflict - the defect the arbitration tests found")
    void aWeakerTierNeverSettlesAStrongerConflict() {
        // The first version of the tiered pass did settle this: a TEXT_LOCAL claim whose text happened to
        // keep both sides took a STRUCTURE conflict out of the working set, and a block that had been left
        // for a human since step 4.6 came out APPLIED_AUTO. A removal that widens what applies, without
        // anyone deciding it should, is the failure this bound exists to prevent.
        List<Conflict> detected = List.of(
            conflict(ConflictType.TYPE_CHANGE, REGION),
            conflict(ConflictType.STRUCTURAL_CHANGE, REGION));
        Recorder source = new Recorder()
            .answer(0, explained(claimKeepingBoth(detected.get(0), AnalysisLevel.TEXT_LOCAL), REGION))
            .answer(1, claimKeepingBoth(detected.get(1), AnalysisLevel.STRUCTURE));

        TieredResolution.Result result = TieredResolution.resolve(detected, REGION, List.of(
            tier(ConflictType.TYPE_CHANGE, AnalysisLevel.PROJECT_TYPES),
            tier(ConflictType.STRUCTURAL_CHANGE, AnalysisLevel.STRUCTURE)), source);

        assertEquals(List.of(0, 1), source.asked,
            "the weaker claim runs second, and the STRUCTURE conflict is asked rather than settled away");
        assertTrue(result.removals().isEmpty(),
            "with the tier bound absent, index 0 would have settled index 1 and asked would have been [0]");
    }

    @Test
    @DisplayName("a claim still settles its own conflict when its record is below its declaration")
    void aClaimSettlesItsOwnConflictRegardlessOfTheBound() {
        // MemberAddConflictResolver records STRUCTURE without a classpath while declaring PROJECT_TYPES.
        // The bound governs what a claim may say about *other* conflicts; the answer to the question a
        // resolver was actually asked needs no permission from the scale.
        List<Conflict> detected = List.of(
            conflict(ConflictType.MEMBER_ADD, Region.spanning(10, 20)),
            conflict(ConflictType.STRUCTURAL_CHANGE, Region.spanning(30, 40), "alpha", "beta"));
        Recorder source = new Recorder()
            .answer(0, claimKeepingBoth(detected.get(0), AnalysisLevel.STRUCTURE))
            .answer(1, claimKeepingBoth(detected.get(1), AnalysisLevel.STRUCTURE));

        TieredResolution.Result result = TieredResolution.resolve(detected, REGION, List.of(
            tier(ConflictType.MEMBER_ADD, AnalysisLevel.PROJECT_TYPES),
            tier(ConflictType.STRUCTURAL_CHANGE, AnalysisLevel.STRUCTURE)), source);

        assertEquals(ConflictState.RESOLVED, result.pass().stateOf(0),
            "its own region is settled by its own answer");
        assertEquals(ConflictState.OPEN, result.pass().stateOf(1),
            "and the STRUCTURE conflict is still open, because STRUCTURE is not below STRUCTURE's owner");
    }

    @Test
    @DisplayName("a conflict no resolver in the set owns is not settled away, and is still asked about")
    void aTypeNobodyOwnsIsNeverSettled() {
        // A restricted resolver set must leave the conflicts it does not cover exactly where today's code
        // leaves them: silence is not evidence, and a type nobody speaks for is not a weaker tier.
        List<Conflict> detected = List.of(
            conflict(ConflictType.MEMBER_ADD, REGION),
            conflict(ConflictType.STRUCTURAL_CHANGE, REGION));
        Recorder source = new Recorder()
            .answer(0, explained(claimKeepingBoth(detected.get(0), AnalysisLevel.PROJECT_TYPES), REGION))
            .answer(1, ConflictResolution.manual(detected.get(1)).build());

        TieredResolution.Result result = TieredResolution.resolve(detected, REGION,
            List.of(tier(ConflictType.MEMBER_ADD, AnalysisLevel.PROJECT_TYPES)), source);

        assertTrue(result.removals().isEmpty(),
            "a type no resolver owns is not taken out of anyone's hands");
        assertTrue(source.asked.contains(1),
            "and it is still asked about, so its manual fallback is recorded rather than lost");
        assertEquals(2, result.decidingClaims().size());
    }

    @Test
    @DisplayName("a null answer is recorded as such, and does not stop a later tier being asked")
    void aDecliningTierLeavesTheConflictLive() {
        List<Conflict> detected = List.of(
            conflict(ConflictType.MEMBER_ADD, REGION),
            conflict(ConflictType.API_INCOMPATIBILITY, REGION));
        Recorder source = new Recorder()
            .answer(0, null)
            .answer(1, claimKeepingBoth(detected.get(1), AnalysisLevel.TEXT_LOCAL));

        TieredResolution.Result result = TieredResolution.resolve(detected, REGION, List.of(
            tier(ConflictType.MEMBER_ADD, AnalysisLevel.PROJECT_TYPES),
            tier(ConflictType.API_INCOMPATIBILITY, AnalysisLevel.TEXT_LOCAL)), source);

        assertEquals(List.of(0, 1), source.asked);
        assertNull(result.claims().get(0));
        assertEquals(1, result.pass().live().size(), "the declining conflict stays live for the next tier");
        assertSame(detected.get(0), result.pass().live().get(0).conflict(),
            "and it is the conflict that was declined, not the one that answered");
    }
}
