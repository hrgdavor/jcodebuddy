// {@link com.codebuddy.merge.ReliabilityTest} What a claim must explain before it may settle a region (plan step 4.19).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reliability predicate: what a claim must explain before it may take a conflict out of the lower
 * tiers' hands (unified plan step 4.19, DEC-046 clauses 4, 10 and 11).
 *
 * <h2>The claim this predicate exists for</h2>
 *
 * <p><b>A conflict a higher tier resolves reliably is removed, not outranked</b> — the lower resolver is
 * never called about it. That is a much stronger statement than "the higher answer usually wins", and it
 * is the reason this is a predicate over one claim and one region rather than a property of a resolver or
 * of a level.
 *
 * <h2>The tests that matter most are the negative ones</h2>
 *
 * <p>A predicate nobody can fail is not a safety property. The four ways to fail are asserted here
 * explicitly: an answer that may not be applied, a region with no location, an answer that explains only
 * part of the region, and — the one worth stating on its own — an answer resting on a <b>high level</b>
 * with no evidence for the lines in front of it. {@link #aHighLevelBuysNoAuthority()} is DEC-046 clause
 * 11 in one assertion: a resolver can buy being asked early, and can never buy authority.
 */
class ReliabilityTest {

    private static final Region REGION = Region.spanning(10, 20);

    private static Conflict conflict(String base, String ours, String theirs) {
        return new Conflict(ConflictType.MEMBER_ADD, "Sample.java", "sample", base, ours, theirs, REGION);
    }

    private static Conflict threeSides() {
        return conflict("a", "a\nx", "a\ny");
    }

    @Test
    @DisplayName("a claim whose explained span covers the region is reliable")
    void anExplainedSpanIsReliable() {
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode("a\nx\ny")
            .explainedSpan(REGION)
            .build();

        Reliability.Result result = Reliability.of(claim, REGION);

        assertTrue(result.isReliable(), result.reason());
        assertEquals(Reliability.Verdict.RELIABLE, result.verdict());
    }

    @Test
    @DisplayName("a claim that keeps every line of both sides needs no declaration")
    void keepingBothSidesIsReliable() {
        // The second of DEC-046 clause 4's three ways, and the one checked rather than declared: an answer
        // that really does include both branches cannot be dropping what the region was about.
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode("a\nx\ny")
            .build();

        assertTrue(Reliability.of(claim, REGION).isReliable());
        assertEquals(Region.unknown(), claim.getExplainedSpan(), "nothing was declared, and that is fine");
    }

    @Test
    @DisplayName("a claim that prefers one side and explains nothing is not reliable")
    void preferringOneSideIsNotReliable() {
        // Dropping the other side is the decision here, not an accident, so the kept-lines test fails by
        // construction - and this claim declares no explained span either. It is still a claim: it goes up
        // against the others and simply does not remove anything.
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.PREFER_BRANCH1)
            .resolvedCode("a\nx")
            .build();

        Reliability.Result result = Reliability.of(claim, REGION);

        assertFalse(result.isReliable());
        assertEquals(Reliability.Verdict.UNEXPLAINED, result.verdict());
    }

    @Test
    @DisplayName("a high level buys no authority: PROJECT_TYPES with no evidence settles nothing")
    void aHighLevelBuysNoAuthority() {
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.PREFER_BRANCH1)
            .resolvedCode("a\nx")
            .analysisLevel(AnalysisLevel.PROJECT_TYPES)
            .build();

        Reliability.Result result = Reliability.of(claim, REGION);

        assertEquals(AnalysisLevel.PROJECT_TYPES, claim.getAnalysisLevel());
        assertFalse(result.isReliable(), "the level orders the pass; it never settles a region");
        assertEquals(Reliability.Verdict.UNEXPLAINED, result.verdict());
    }

    @Test
    @DisplayName("an explained span that covers only part of the region is not enough")
    void aPartialExplanationIsNotEnough() {
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.PREFER_BRANCH1)
            .resolvedCode("a\nx")
            .explainedSpan(Region.spanning(10, 15))
            .build();

        Reliability.Result result = Reliability.of(claim, REGION);

        assertFalse(result.isReliable());
        assertEquals(Reliability.Verdict.UNEXPLAINED, result.verdict());
        assertTrue(result.reason().contains("10-15"), result.reason());
    }

    @Test
    @DisplayName("a REVIEW answer settles nothing, whatever it explains")
    void aReviewAnswerIsNeverReliable() {
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode("a\nx\ny")
            .explainedSpan(REGION)
            .kind(ConflictResolution.ResolutionKind.REVIEW)
            .build();

        Reliability.Result result = Reliability.of(claim, REGION);

        assertFalse(result.isReliable());
        assertEquals(Reliability.Verdict.NOT_APPLICABLE, result.verdict());
        assertTrue(result.reason().contains("REVIEW"), result.reason());
    }

    @Test
    @DisplayName("a MANUAL answer settles nothing, so the hierarchy cannot promote one into application")
    void aManualAnswerIsNeverReliable() {
        ConflictResolution claim = ConflictResolution.builder()
            .filePath("Sample.java")
            .type(ConflictType.MEMBER_ADD)
            .region(REGION)
            .resolvedCode("a\nx\ny")
            .explainedSpan(REGION)
            .kind(ConflictResolution.ResolutionKind.MANUAL)
            .build();

        assertEquals(Reliability.Verdict.NOT_APPLICABLE, Reliability.of(claim, REGION).verdict());
    }

    @Test
    @DisplayName("a recorded human decision may settle a region, because a decision is not weaker evidence")
    void aDeferredAnswerIsReliable() {
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.STICKY_REPLAY)
            .resolvedCode("a\nx\ny")
            .explainedSpan(REGION)
            .kind(ConflictResolution.ResolutionKind.DEFERRED)
            .build();

        Reliability.Result result = Reliability.of(claim, REGION);

        assertTrue(result.isReliable(), result.reason());
        assertEquals(Reliability.Verdict.RELIABLE, result.verdict());
    }

    @Test
    @DisplayName("a region with no location settles nothing, because there is nothing to explain")
    void anUnknownRegionIsNotReliable() {
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode("a\nx\ny")
            .explainedSpan(Region.unknown())
            .build();

        Reliability.Result result = Reliability.of(claim, Region.unknown());

        assertFalse(result.isReliable());
        assertEquals(Reliability.Verdict.UNPLACED, result.verdict());
    }

    @Test
    @DisplayName("a claim that does not exist cannot settle anything")
    void noClaimIsNotReliable() {
        assertEquals(Reliability.Verdict.UNEXPLAINED, Reliability.of(null, REGION).verdict());
    }

    @Test
    @DisplayName("the declared span defaults to nothing, so saying nothing explains nothing")
    void theDefaultExplainsNothing() {
        // The default is load-bearing rather than polite: an unset span that read as "the whole region"
        // would let every claim settle every conflict it was asked about.
        ConflictResolution bare = ConflictResolution.builder()
            .type(ConflictType.MEMBER_ADD)
            .build();

        assertEquals(Region.unknown(), bare.getExplainedSpan());
        assertFalse(bare.getExplainedSpan().isKnown());
    }

    @Test
    @DisplayName("lines are compared normalised, so a re-rendered union still counts as kept")
    void reorderedAndReindentedLinesStillCount() {
        // An import union is sorted and re-indented rather than concatenated, so a byte comparison would
        // call a faithful merge a loss. This is the same normalisation the coverage rule uses - one
        // definition, so the two answers cannot drift.
        Conflict sides = conflict("", "import a;\nimport b;", "  import b;\n  import c;");
        ConflictResolution claim = ConflictResolution.auto(sides,
                ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode("import a;\nimport b;\nimport c;")
            .build();

        assertTrue(Reliability.keepsBothSides(claim));
        assertTrue(Reliability.of(claim, REGION).isReliable());
    }

    @Test
    @DisplayName("blank applied text keeps nothing, so it settles nothing")
    void blankAppliedTextSettlesNothing() {
        ConflictResolution claim = ConflictResolution.auto(threeSides(),
                ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode("   ")
            .explainedSpan(Region.unknown())
            .build();

        assertFalse(Reliability.keepsBothSides(claim));
        assertEquals(Reliability.Verdict.UNEXPLAINED, Reliability.of(claim, REGION).verdict());
    }
}
