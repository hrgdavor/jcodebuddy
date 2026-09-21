// {@link com.codebuddy.merge.RenameConflictResolverTest} Tests for the variable rename conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the rename policy: a rename is never applied silently, the competing
 * names are offered, and the outcome is replayable so the same rename stops
 * asking on later updates.
 */
class RenameConflictResolverTest extends AbstractResolverTest {

    private final RenameConflictResolver resolver = new RenameConflictResolver();

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
        return List.of(ConflictType.IMPORT_ADD, ConflictType.COMMENT_ADD);
    }

    @Test
    @DisplayName("never renames silently, but offers the competing names")
    void offersCompetingNames() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "a rename is a preference, so a reviewer confirms it");
        assertTrue(resolution.isSticky(),
            "a rename decision must be replayable on the next update");
        assertTrue(resolution.isReplayable());
        assertTrue(resolution.getExplanation().contains("purchase"),
            "branch 1's name must be named: " + resolution.getExplanation());
        assertTrue(resolution.getExplanation().contains("invoice"),
            "branch 2's name must be named: " + resolution.getExplanation());
    }

    @Test
    @DisplayName("recommends the name that matches the base branch")
    void recommendsBaseCompatibleName() {
        // base uses 'order', branch 1 keeps it, branch 2 renames to 'invoice'.
        Conflict conflict = new Conflict(ConflictType.VARIABLE_RENAME, ConflictFixtures.FILE,
            "one side kept the base name",
            "int order = 1;", "int order = 1;", "int invoice = 1;");

        ConflictResolution resolution = resolver.resolve(conflict);
        FixPath primary = resolution.getAlternativePaths().get(0);

        assertTrue(primary.hasRecommendation(), "there is a base-compatible choice");
        assertTrue(primary.getRecommended().contains("order"),
            "the base-compatible name minimises the diff: " + primary.getRecommended());
    }

    @Test
    @DisplayName("declines when both branches use the same name")
    void declinesWhenNamesAgree() {
        Conflict conflict = new Conflict(ConflictType.VARIABLE_RENAME, ConflictFixtures.FILE,
            "same rename", "int order = 1;", "int invoice = 1;", "int invoice = 1;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind(),
            "an agreed rename is not a conflict");
    }

    @Test
    @DisplayName("declines when nothing is declared")
    void declinesWithoutDeclaration() {
        Conflict conflict = new Conflict(ConflictType.VARIABLE_RENAME, ConflictFixtures.FILE,
            "no declaration", "return;", "return;", "return;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }

    @Test
    @DisplayName("extracts the declared name from a typed declaration")
    void extractsDeclaredName() {
        assertEquals("order",
            RenameConflictResolver.firstDeclaredName("int order = 1;").orElseThrow());
        assertEquals("count",
            RenameConflictResolver.firstDeclaredName("final long count;").orElseThrow());
    }

    @Test
    @DisplayName("does not treat control-flow keywords as declarations")
    void ignoresControlFlowKeywords() {
        assertTrue(RenameConflictResolver.firstDeclaredName("if (ready) { return; }").isEmpty(),
            "an if-statement is not a declaration");
    }

    @Test
    @DisplayName("offers to remember the decision")
    void offersToRememberDecision() {
        List<FixPath> fixPaths = resolver.getFixPaths(ConflictFixtures.sample(ConflictType.VARIABLE_RENAME));

        assertTrue(fixPaths.stream().anyMatch(path -> path.getDescription().toLowerCase().contains("sticky")),
            "the reviewer must be able to make the choice permanent: " + fixPaths);
    }
}
