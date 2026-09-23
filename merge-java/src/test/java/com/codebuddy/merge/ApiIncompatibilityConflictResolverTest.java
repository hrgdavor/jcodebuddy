// {@link com.codebuddy.merge.ApiIncompatibilityConflictResolverTest} Tests for the public API conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that public-contract changes are never applied automatically and that
 * the report names exactly which part of the contract moved.
 */
class ApiIncompatibilityConflictResolverTest extends AbstractResolverTest {

    private final ApiIncompatibilityConflictResolver resolver =
        new ApiIncompatibilityConflictResolver();

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

    //#region never-automatically-resolves
    @Test
    @DisplayName("never changes a public contract automatically")
    void neverAutomaticallyResolves() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.API_INCOMPATIBILITY));

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        assertEquals(ConflictResolution.MANUAL_MARKER, resolution.getResolvedCode());
        assertFalse(resolution.isReplayable());
    }
    //#endregion

    //#region names-return-type-difference
    @Test
    @DisplayName("names the return type difference")
    void namesReturnTypeDifference() {
        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(
            ConflictFixtures.sample(ConflictType.API_INCOMPATIBILITY));

        assertTrue(edits.stream().anyMatch(edit -> edit.contains("return type")),
            "the return type change must be named: " + edits);
    }
    //#endregion

    //#region names-narrowed-visibility
    @Test
    @DisplayName("names a narrowed visibility")
    void namesNarrowedVisibility() {
        Conflict conflict = new Conflict(ConflictType.API_INCOMPATIBILITY, ConflictFixtures.FILE,
            "visibility narrowed",
            "public void process() { }",
            "protected void process() { }",
            "public void process() { }");

        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(conflict);

        assertTrue(edits.stream().anyMatch(edit -> edit.contains("visibility")),
            "the visibility change must be named: " + edits);
    }
    //#endregion

    //#region names-dropped-exception
    @Test
    @DisplayName("names a dropped checked exception")
    void namesDroppedException() {
        Conflict conflict = new Conflict(ConflictType.API_INCOMPATIBILITY, ConflictFixtures.FILE,
            "exception dropped",
            "public void process() throws IOException { }",
            "public void process() { }",
            "public void process() throws IOException { }");

        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(conflict);

        assertTrue(edits.stream().anyMatch(edit -> edit.contains("exception")),
            "the dropped exception must be named: " + edits);
    }
    //#endregion

    //#region names-parameter-change
    @Test
    @DisplayName("names a parameter list change")
    void namesParameterChange() {
        Conflict conflict = new Conflict(ConflictType.API_INCOMPATIBILITY, ConflictFixtures.FILE,
            "parameters changed",
            "public void process(String id) { }",
            "public void process(String id, boolean force) { }",
            "public void process(String id) { }");

        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(conflict);

        assertTrue(edits.stream().anyMatch(edit -> edit.contains("parameters")),
            "the parameter change must be named: " + edits);
    }
    //#endregion

    //#region always-reports-something
    @Test
    @DisplayName("always reports something, even for an unparsable declaration")
    void alwaysReportsSomething() {
        Conflict conflict = new Conflict(ConflictType.API_INCOMPATIBILITY, ConflictFixtures.FILE,
            "unparsable", "int x = 1;", "int x = 2;", "int x = 3;");

        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(conflict);

        assertFalse(edits.isEmpty(), "a reviewer must never be told nothing");
    }
    //#endregion

    //#region warns-about-callers
    @Test
    @DisplayName("warns that callers outside the merge may break")
    void warnsAboutCallers() {
        FixPath primary = resolver
            .getFixPaths(ConflictFixtures.sample(ConflictType.API_INCOMPATIBILITY)).get(0);

        assertTrue(primary.getImpact().contains("Callers"),
            "the impact must warn about external callers: " + primary.getImpact());
    }
    //#endregion
}
