// {@link com.codebuddy.merge.MethodBodyChangeConflictResolverTest} Tests for the method body conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that disjoint edits to the same method are combined for review, while
 * overlapping edits are reported rather than guessed at.
 */
class MethodBodyChangeConflictResolverTest extends AbstractResolverTest {

    private final MethodBodyChangeConflictResolver resolver = new MethodBodyChangeConflictResolver();

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

    //#region combines-disjoint-edits
    @Test
    @DisplayName("combines disjoint edits but still asks for confirmation")
    void combinesDisjointEdits() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "a syntactic merge of two edits still needs human confirmation");
        assertTrue(resolution.getResolvedCode().contains("total += 1;"),
            "branch 1's edit must be present: " + resolution.getResolvedCode());
        assertTrue(resolution.getResolvedCode().contains("total *= 2;"),
            "branch 2's edit must be present: " + resolution.getResolvedCode());
    }
    //#endregion

    //#region reports-overlapping-edits
    @Test
    @DisplayName("reports overlapping edits instead of combining them")
    void reportsOverlappingEdits() {
        Conflict conflict = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "same statement changed",
            "int total = 0;\nreturn total;",
            "int total = 0;\ntotal += 1;\nreturn total;",
            "int total = 0;\ntotal += 1;\nreturn total;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind());
        assertTrue(resolution.getExplanation().contains("same")
                || resolution.getExplanation().contains("both"),
            "the explanation must say the edits collide: " + resolution.getExplanation());
    }
    //#endregion

    //#region reports-overlapping-but-different-edits
    @Test
    @DisplayName("names the shared statement when the edits only partly overlap")
    void reportsOverlappingButDifferentEdits() {
        // Both branches inserted the same statement, and each inserted a
        // different one besides: the shared edit overlaps, so the bodies
        // cannot be combined mechanically.
        Conflict conflict = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "partly overlapping edits",
            "int total = 0;\nreturn total;",
            "int total = 0;\ntotal += 1;\naudit();\nreturn total;",
            "int total = 0;\ntotal += 1;\nnotify();\nreturn total;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "partly overlapping edits are not mechanically combinable");
        assertTrue(resolution.getExplanation().contains("total += 1;"),
            "the explanation must name the shared statement: " + resolution.getExplanation());
        assertFalse(resolution.getResolvedCode().contains("notify();"),
            "branch 2's extra statement must not be silently combined in: "
                + resolution.getResolvedCode());
    }
    //#endregion

    //#region recognises-identical-edits
    @Test
    @DisplayName("recognises an edit both branches made identically")
    void recognisesIdenticalEdits() {
        Conflict conflict = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "identical edit",
            "int total = 0;\nreturn total;",
            "int total = 0;\ntotal += 1;\nreturn total;",
            "int total = 0;\ntotal += 1;\nreturn total;");

        ConflictResolution resolution = resolver.resolve(conflict);
        assertTrue(resolution.getExplanation().toLowerCase().contains("same"),
            "an identical edit should be described as such: " + resolution.getExplanation());
    }
    //#endregion

    //#region declines-without-statements
    @Test
    @DisplayName("declines when a side has no statements to compare")
    void declinesWithoutStatements() {
        Conflict conflict = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "empty bodies", "int total = 0;", "", "int total = 1;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }
    //#endregion

    //#region ignores-blank-lines-and-comments
    @Test
    @DisplayName("ignores blank lines and comments when comparing statements")
    void ignoresBlankLinesAndComments() {
        List<String> statements = MethodBodyChangeConflictResolver.statementsIn(
            "int total = 0;\n\n// a comment\n  return total;  \n");

        assertEquals(List.of("int total = 0;", "return total;"), statements);
    }
    //#endregion

    //#region recommends-combining
    @Test
    @DisplayName("recommends combining disjoint edits")
    void recommendsCombining() {
        FixPath primary =
            resolver.getFixPaths(ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE)).get(0);
        assertEquals("Combine both edits", primary.getRecommended());
    }
    //#endregion
}
