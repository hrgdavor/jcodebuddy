// {@link com.codebuddy.merge.ResolutionPassTest} The working set and the partition invariant (plan step 4.18).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The working set of one block: the state each conflict holds, and the invariant that no line is lost
 * between tiers (unified plan step 4.18, DEC-046 clauses 8–9).
 *
 * <h2>The claim this step makes, and the one it does not</h2>
 *
 * <p><b>The pass holds live conflicts, and a conflict a higher tier resolved is gone from what the next
 * tier is handed.</b> That is the whole of the mechanism: the requirement "I do not want lower level
 * resolver to even see conflict" is satisfied by absence from {@link ResolutionPass#live()}, not by an
 * arbitration the lower claim lost.
 *
 * <p>What this step does <b>not</b> do is decide <em>when</em> a resolution may settle a span — the
 * reliability predicate is step 4.19, and it is why the tests below build states by hand. The value of
 * landing the invariant first is the reason step 4.9 gives: a plumbing defect found by an invariant
 * costs a test, and the same defect found through a fixture costs the step.
 *
 * <h2>Why the partition has teeth</h2>
 *
 * <p>"Every line is accounted for exactly once" is trivial to <em>state</em> and easy to state in a way
 * that cannot fail: if a line with no settled span is silently called "open", nothing can be dropped and
 * the check proves nothing. So the invariant is checked against the region a conflict <b>started</b>
 * with — the settled spans plus the open remainder must be exactly that region — which makes a line that
 * neither half carries an expressible, detectable defect. {@link #aDroppedLineIsReported()} and
 * {@link #aLineSettledTwiceIsReported()} are the two ways it fails, and both are asserted to fail rather
 * than described as failing.
 */
class ResolutionPassTest {

    private static final Region BLOCK = Region.spanning(10, 40);

    /**
     * A conflict whose region is known, which is what every line-level check needs.
     */
    private static Conflict conflict(ConflictType type, Region region) {
        return new Conflict(type, "Sample.java", type.name(), "", "", "", region);
    }

    private static ConflictResolution claim(ConflictType type) {
        return ConflictResolution.auto(conflict(type, BLOCK), ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode("kept")
            .explanation("test claim")
            .analysisLevel(AnalysisLevel.STRUCTURE)
            .build();
    }

    @Test
    @DisplayName("a fresh pass has every conflict open, and nothing is lost yet")
    void aFreshPassIsEntirelyOpen() {
        ResolutionPass pass = ResolutionPass.of(BLOCK, List.of(
            conflict(ConflictType.MEMBER_ADD, Region.spanning(10, 20)),
            conflict(ConflictType.METHOD_BODY_CHANGE, Region.spanning(25, 30))));

        assertEquals(ConflictState.OPEN, pass.stateOf(0));
        assertEquals(ConflictState.OPEN, pass.stateOf(1));
        assertEquals(2, pass.live().size());
        assertTrue(pass.all().stream().allMatch(ResolutionPass.LiveConflict::isOpen));
        assertTrue(pass.partition().holds(), pass.partition().describe());
        assertTrue(pass.partition().checkable());
    }

    @Test
    @DisplayName("conflicts need not cover the whole block, and that is not a defect")
    void theUnionOfConflictRegionsNeedNotCoverTheBlock() {
        // Detection emits one conflict per shape it recognises, so a block is routinely larger than the
        // union of the regions its conflicts place. Lines 21-39 below belong to no conflict and are open
        // by definition; a check that called them "dropped" would fail on every real block.
        ResolutionPass pass = ResolutionPass.of(BLOCK, List.of(
            conflict(ConflictType.MEMBER_ADD, Region.spanning(10, 20))));

        assertTrue(pass.partition().holds(), pass.partition().describe());
    }

    @Test
    @DisplayName("a resolved conflict leaves the working set, and the next tier is never handed it")
    void aResolvedConflictIsRemovedFromTheWorkingSet() {
        ResolutionPass pass = ResolutionPass.of(BLOCK, List.of(
            conflict(ConflictType.MEMBER_ADD, Region.spanning(10, 20)),
            conflict(ConflictType.API_INCOMPATIBILITY, Region.spanning(10, 20))));

        ResolutionPass after = pass.withResolved(0, claim(ConflictType.MEMBER_ADD));

        assertEquals(ConflictState.RESOLVED, after.stateOf(0));
        assertEquals(1, after.live().size(), "the resolved conflict must not be offered to a lower tier");
        assertSame(after.all().get(1).conflict(), after.live().get(0).conflict());
        assertTrue(after.partition().holds(), after.partition().describe());
    }

    @Test
    @DisplayName("a partly resolved conflict stays live, offering only the open remainder")
    void aPartialConflictOffersOnlyTheRemainder() {
        ResolutionPass pass = ResolutionPass.of(BLOCK, List.of(
            conflict(ConflictType.MEMBER_ADD, Region.spanning(10, 20))));

        ResolutionPass after = pass.withPartial(0, claim(ConflictType.MEMBER_ADD),
            List.of(Region.spanning(10, 15)), Region.spanning(16, 20));

        assertEquals(ConflictState.PARTIAL, after.stateOf(0));
        assertEquals(Region.spanning(16, 20), after.live().get(0).region());
        assertEquals(1, after.live().size(), "a partial answer does not remove the conflict");
        assertTrue(after.partition().holds(), after.partition().describe());
    }

    @Test
    @DisplayName("a line neither settled nor left open is reported as dropped")
    void aDroppedLineIsReported() {
        // Lines 13 and 14 are in the conflict's own region, in neither half of a partial answer: they
        // would be lost between two tiers that each believed the other had them.
        ResolutionPass broken = ResolutionPass.of(BLOCK, List.of(
                conflict(ConflictType.MEMBER_ADD, Region.spanning(10, 20))))
            .withPartial(0, claim(ConflictType.MEMBER_ADD),
                List.of(Region.spanning(10, 12)), Region.spanning(15, 20));

        ResolutionPass.PartitionReport report = broken.partition();

        assertFalse(report.holds());
        assertEquals(List.of(13, 14), report.dropped());
        assertTrue(report.describe().contains("dropped lines"), report.describe());
        assertFalse(broken.all().get(0).accountsForRegion());
    }

    @Test
    @DisplayName("a line settled by two claims is reported, because two answers cannot both apply")
    void aLineSettledTwiceIsReported() {
        ResolutionPass broken = ResolutionPass.of(BLOCK, List.of(
                conflict(ConflictType.MEMBER_ADD, Region.spanning(10, 20)),
                conflict(ConflictType.METHOD_BODY_CHANGE, Region.spanning(15, 25))))
            .withResolved(0, claim(ConflictType.MEMBER_ADD))
            .withResolved(1, claim(ConflictType.METHOD_BODY_CHANGE));

        ResolutionPass.PartitionReport report = broken.partition();

        assertFalse(report.holds());
        assertEquals(List.of(15, 16, 17, 18, 19, 20), report.multiplyResolved());
        assertTrue(report.describe().contains("settled twice"), report.describe());
    }

    @Test
    @DisplayName("a span settled outside the block is reported, because the block does not own that text")
    void aSpanOutsideTheBlockIsReported() {
        ResolutionPass broken = ResolutionPass.of(BLOCK, List.of(
                conflict(ConflictType.MEMBER_ADD, Region.spanning(1, 8))))
            .withResolved(0, claim(ConflictType.MEMBER_ADD));

        ResolutionPass.PartitionReport report = broken.partition();

        assertFalse(report.holds());
        assertEquals(List.of(Region.spanning(1, 8)), report.outsideBlock());
        assertTrue(report.describe().contains("outside the block"), report.describe());
    }

    @Test
    @DisplayName("a conflict with no location can settle nothing, and saying otherwise is reported")
    void anUnknownRegionCannotBeSettled() {
        // Region.unknown() spans no lines, so an "applied" answer over it is a claim about nothing. The
        // defect is reported rather than refused, because a state that could not represent the mistake
        // could not detect it either.
        ResolutionPass broken = ResolutionPass.of(BLOCK, List.of(
                conflict(ConflictType.STRUCTURAL_CHANGE, Region.unknown())))
            .withResolved(0, claim(ConflictType.STRUCTURAL_CHANGE));

        ResolutionPass.PartitionReport report = broken.partition();

        assertFalse(report.holds());
        assertFalse(report.unplaced().isEmpty());
        assertTrue(broken.partition().checkable(), "the block is known; the conflict is the unplaced half");
        assertTrue(report.describe().contains("without a location"), report.describe());
    }

    @Test
    @DisplayName("an unplaced conflict that is merely open is not reported as a dropped line")
    void anOpenConflictWithoutLocationIsNotADroppedLine() {
        // The merge-conflict-style file has no base side, so every region is unknown. That is the normal
        // case for a caller without a diff3 block, and it must not read as data loss.
        ResolutionPass pass = ResolutionPass.of(Region.unknown(), List.of(
            conflict(ConflictType.STRUCTURAL_CHANGE, Region.unknown())));

        assertTrue(pass.partition().holds(), pass.partition().describe());
        assertFalse(pass.partition().checkable(), "an unknown block cannot be checked for lost lines");
        assertFalse(pass.block().isKnown());
    }

    @Test
    @DisplayName("the run order is by declared level, and it ignores priority")
    void theRunOrderIsByDeclaredLevelNotPriority() {
        // The two are deliberately antagonistic here: if the pass ran in priority order, the weakest
        // resolver would be asked first, which is the opposite of the design.
        ConflictResolver strongest = stubResolver(ConflictType.TYPE_CHANGE, AnalysisLevel.PROJECT_TYPES, 0);
        ConflictResolver weakest = stubResolver(ConflictType.API_INCOMPATIBILITY, AnalysisLevel.TEXT_LOCAL, 100);

        List<ConflictResolver> ordered = ConflictResolvers.inTierOrder(List.of(weakest, strongest));

        assertEquals(List.of(strongest, weakest), ordered);
    }

    @Test
    @DisplayName("within one tier the given order is kept, so registration order still decides")
    void withinATierTheGivenOrderIsKept() {
        ConflictResolver first = stubResolver(ConflictType.MEMBER_ADD, AnalysisLevel.STRUCTURE, 0);
        ConflictResolver second = stubResolver(ConflictType.STRUCTURAL_CHANGE, AnalysisLevel.STRUCTURE, 0);

        assertEquals(List.of(first, second),
            ConflictResolvers.inTierOrder(List.of(first, second)));
        assertEquals(List.of(second, first),
            ConflictResolvers.inTierOrder(List.of(second, first)));
    }

    @Test
    @DisplayName("the shipped resolvers are ordered from the strongest declaration to the weakest")
    void theShippedResolversAreTierOrdered() {
        List<ConflictResolver> ordered = ConflictResolvers.inTierOrder(ConflictResolvers.defaultResolvers());

        assertEquals(ConflictResolvers.defaultResolvers().size(), ordered.size(),
            "tier ordering must not drop a resolver");
        for (int index = 1; index < ordered.size(); index++) {
            AnalysisLevel previous = ordered.get(index - 1).maxAnalysisLevel();
            AnalysisLevel current = ordered.get(index).maxAnalysisLevel();
            assertTrue(previous.strength() >= current.strength(),
                previous + " must not be asked after " + current);
        }
        assertEquals(AnalysisLevel.PROJECT_TYPES, ordered.get(0).maxAnalysisLevel(),
            "the strongest declaration runs first");
        assertEquals(AnalysisLevel.TEXT_LOCAL, ordered.get(ordered.size() - 1).maxAnalysisLevel());
    }

    @Test
    @DisplayName("a declaration orders the pass; the recorded level is a different fact")
    void theDeclarationIsNotTheRecordedLevel() {
        // MemberAddConflictResolver declares what it can reach and records what it used, and the two
        // differ whenever it had no classpath to compare resolved signatures with. That is why the run
        // order reads the declaration (the record does not exist until the resolver has been called)
        // while what a claim may settle reads the record.
        ConflictResolver memberAdd = new MemberAddConflictResolver();
        assertEquals(AnalysisLevel.PROJECT_TYPES, memberAdd.maxAnalysisLevel());

        ConflictResolution recorded = ConflictResolution.auto(
                conflict(ConflictType.MEMBER_ADD, BLOCK), ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .analysisLevel(AnalysisLevel.STRUCTURE)
            .build();
        assertEquals(AnalysisLevel.STRUCTURE, recorded.getAnalysisLevel());
        assertFalse(recorded.getAnalysisLevel().isAtLeast(memberAdd.maxAnalysisLevel()),
            "a record below the declaration is the honest direction and must stay expressible");
    }

    /**
     * A resolver that declares a level and a priority and does nothing else, so a test can assert on the
     * ordering without a conflict to resolve.
     */
    private static ConflictResolver stubResolver(ConflictType type, AnalysisLevel level, int priority) {
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

            @Override
            public int priority() {
                return priority;
            }
        };
    }
}
