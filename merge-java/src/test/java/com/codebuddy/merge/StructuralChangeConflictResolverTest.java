// {@link com.codebuddy.merge.StructuralChangeConflictResolverTest} Tests for the structural change resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that structural change is never guessed at, and that the refusal is
 * still useful: it names what each side does that the other does not.
 */
class StructuralChangeConflictResolverTest extends AbstractResolverTest {

    private final StructuralChangeConflictResolver resolver = new StructuralChangeConflictResolver();

    @Override
    protected ConflictResolver resolverUnderTest() {
        return resolver;
    }

    @Override
    protected Conflict conflictFor(ConflictType type) {
        return ConflictFixtures.sample(type);
    }

    @Override
    protected List<ConflictType> unsupportedTypes() {
        return List.of(ConflictType.IMPORT_ADD, ConflictType.COMMENT_ADD,
            ConflictType.CONSTANT_ADD);
    }

    //#region always-requires-human-decision
    @Test
    @DisplayName("always hands structural change to a human")
    void alwaysRequiresHumanDecision() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        assertEquals(ConflictResolution.ResolutionStrategy.MANUAL,
            resolution.getResolutionStrategy());
        assertTrue(resolution.requiresHumanDecision());
        assertFalse(resolution.getAlternativePaths().isEmpty(),
            "a refusal must still tell the reviewer what the options are");
    }
    //#endregion

    //#region emits-manual-marker
    @Test
    @DisplayName("emits the manual marker as the resolved code")
    void emitsManualMarker() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertEquals(ConflictResolution.MANUAL_MARKER, resolution.getResolvedCode(),
            "the caller must be able to detect that nothing was applied");
    }
    //#endregion

    //#region describes-both-sides
    @Test
    @DisplayName("describes what each branch does that the other does not")
    void describesBothSides() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        FixPath primary = resolution.getAlternativePaths().get(0);
        assertTrue(primary.getOptions().contains("Apply branch 1"),
            "both sides must be offered: " + primary.getOptions());
        assertTrue(primary.getOptions().contains("Apply branch 2"),
            "both sides must be offered: " + primary.getOptions());
        assertTrue(primary.getImpact().contains("unique"),
            "the impact must quantify what would be discarded: " + primary.getImpact());
    }
    //#endregion

    //#region never-sticky
    @Test
    @DisplayName("never marks a structural resolution as replayable")
    void neverSticky() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertFalse(resolution.isSticky(),
            "a one-off structural decision must not be replayed blindly");
        assertFalse(resolution.isReplayable());
    }
    //#endregion

    //#region offers-manual-escape-hatch
    @Test
    @DisplayName("always offers the manual escape hatch")
    void offersManualEscapeHatch() {
        List<FixPath> fixPaths =
            resolver.getFixPaths(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertTrue(fixPaths.stream().anyMatch(path ->
                path.getDescription().contains("by hand")),
            "the reviewer must always be able to take it over: " + fixPaths);
    }
    //#endregion

    @Test
    @DisplayName("handles identical branches without incident")
    void handlesIdenticalBranches() {
        Conflict conflict = new Conflict(ConflictType.STRUCTURAL_CHANGE, ConflictFixtures.FILE,
            "identical", "void a() { }", "void a() { }", "void a() { }");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }
}
