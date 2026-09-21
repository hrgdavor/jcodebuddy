// {@link com.codebuddy.merge.ConstantAddConflictResolverTest} Tests for the constant conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that independently added constants coexist, while two definitions of
 * the same name with different values are escalated.
 */
class ConstantAddConflictResolverTest extends AbstractResolverTest {

    private final ConstantAddConflictResolver resolver = new ConstantAddConflictResolver();

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
        return List.of(ConflictType.IMPORT_ADD, ConflictType.VARIABLE_RENAME);
    }

    @Test
    @DisplayName("keeps constants that were added independently on each branch")
    void keepsIndependentConstants() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.CONSTANT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertTrue(resolution.getResolvedCode().contains("TIMEOUT_MS"));
        assertTrue(resolution.getResolvedCode().contains("RETRY_DELAY_MS"));
    }

    @Test
    @DisplayName("escalates when both branches define the same constant differently")
    void escalatesOnDifferingValues() {
        Conflict conflict = new Conflict(ConflictType.CONSTANT_ADD, ConflictFixtures.FILE,
            "same constant, different value",
            "static final int MAX_RETRIES = 3;",
            "static final int MAX_RETRIES = 3;\nstatic final int LIMIT = 10;",
            "static final int MAX_RETRIES = 3;\nstatic final int LIMIT = 99;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "a differing value is a real disagreement, not an additive change");
        assertTrue(resolution.getExplanation().contains("LIMIT"),
            "the explanation must name the offending constant: " + resolution.getExplanation());
    }

    @Test
    @DisplayName("treats an identically-defined constant as a duplicate")
    void treatsIdenticalDefinitionAsDuplicate() {
        Conflict conflict = new Conflict(ConflictType.CONSTANT_ADD, ConflictFixtures.FILE,
            "same constant, same value",
            "static final int MAX = 1;",
            "static final int MAX = 1;\nstatic final int LIMIT = 10;",
            "static final int MAX = 1;\nstatic final int LIMIT = 10;");

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolver.resolve(conflict).getKind(),
            "identical additions need no human decision");
    }

    @Test
    @DisplayName("declines when neither side adds a constant")
    void declinesWhenNoConstantsPresent() {
        Conflict conflict = new Conflict(ConflictType.CONSTANT_ADD, ConflictFixtures.FILE,
            "no constants", "int total = 0;", "int total = 1;", "int total = 2;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }

    @Test
    @DisplayName("extracts constant names and their declarations")
    void extractsConstants() {
        Map<String, String> constants = ConstantAddConflictResolver.constantsIn(
            "static final int MAX_RETRIES = 3;\npublic static final String NAME = \"x\";\nint local = 1;");

        assertTrue(constants.containsKey("MAX_RETRIES"), "was " + constants.keySet());
        assertTrue(constants.containsKey("NAME"), "was " + constants.keySet());
    }

    @Test
    @DisplayName("extracts enum members")
    void extractsEnumMembers() {
        Map<String, String> constants = ConstantAddConflictResolver.constantsIn(
            "PENDING,\nACTIVE,\nCLOSED;");

        assertTrue(constants.containsKey("PENDING"), "was " + constants.keySet());
        assertTrue(constants.containsKey("ACTIVE"), "was " + constants.keySet());
    }

    @Test
    @DisplayName("recommends keeping both when nothing collides")
    void recommendsKeepingBoth() {
        FixPath primary =
            resolver.getFixPaths(ConflictFixtures.sample(ConflictType.CONSTANT_ADD)).get(0);
        assertEquals("Keep both", primary.getRecommended());
    }
}
