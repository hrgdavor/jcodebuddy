// {@link com.codebuddy.merge.ImportConflictResolverTest} Tests for the import conflict resolver.
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
 * Verifies the behaviour specific to {@link ImportConflictResolver}: the union of
 * imports is kept, duplicates collapse, and the case that motivates the module -
 * two branches adding different imports - is resolved automatically.
 */
class ImportConflictResolverTest extends AbstractResolverTest {

    private final ImportConflictResolver resolver = new ImportConflictResolver();

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
        return List.of(ConflictType.VARIABLE_RENAME, ConflictType.PACKAGE_CHANGE,
            ConflictType.STRUCTURAL_CHANGE);
    }

    @Test
    @DisplayName("keeps the imports added by both branches instead of reporting a conflict")
    void keepsBothBranchesImports() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertEquals(ConflictResolution.ResolutionStrategy.KEEP_BOTH,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("import java.math.BigDecimal;"),
            "branch 1's import must survive");
        assertTrue(resolution.getResolvedCode().contains("import java.time.Instant;"),
            "branch 2's import must survive");
        assertTrue(resolution.getResolvedCode().contains("import java.util.List;"),
            "the shared import must survive exactly once");
    }

    @Test
    @DisplayName("does not duplicate a shared import")
    void doesNotDuplicateSharedImports() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);
        String resolved = resolver.resolve(conflict).getResolvedCode();

        int occurrences = resolved.split("import java.util.List;", -1).length - 1;
        assertEquals(1, occurrences, "a shared import must appear once, was " + resolved);
    }

    @Test
    @DisplayName("treats static imports as distinct from ordinary imports")
    void distinguishesStaticImports() {
        Conflict conflict = new Conflict(ConflictType.IMPORT_ADD, ConflictFixtures.FILE,
            "static import added",
            "import java.util.List;",
            "import java.util.List;\nimport static java.util.Objects.requireNonNull;",
            "import java.util.List;\nimport java.util.Objects;");

        String resolved = resolver.resolve(conflict).getResolvedCode();
        assertTrue(resolved.contains("static java.util.Objects.requireNonNull"),
            "the static import must be preserved with its modifier, was " + resolved);
        assertTrue(resolved.contains("java.util.Objects"), "the plain import must be preserved");
    }

    @Test
    @DisplayName("declines when neither side contributes an import")
    void declinesWhenNoImportsPresent() {
        Conflict conflict = new Conflict(ConflictType.IMPORT_ADD, ConflictFixtures.FILE,
            "no imports", "int total = 0;", "int total = 1;", "int total = 2;");

        ConflictResolution resolution = resolver.resolve(conflict);
        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "with nothing import-shaped to merge, a human must decide");
        assertFalse(resolution.getAlternativePaths().isEmpty(),
            "the manual fallback must still offer options");
    }

    @Test
    @DisplayName("extracts imports with their fully-qualified names")
    void extractsImports() {
        Set<String> imports = ImportConflictResolver.extractImports(
            "import java.util.List;\nimport static java.util.Objects.requireNonNull;\n\nint x = 1;");

        assertEquals(Set.of("java.util.List", "static java.util.Objects.requireNonNull"), imports);
    }

    @Test
    @DisplayName("extraction tolerates null and empty input")
    void extractionHandlesEmptyInput() {
        assertTrue(ImportConflictResolver.extractImports(null).isEmpty());
        assertTrue(ImportConflictResolver.extractImports("").isEmpty());
    }

    @Test
    @DisplayName("recommends keeping both imports")
    void recommendsKeepingBoth() {
        FixPath primary = resolver.getFixPaths(ConflictFixtures.sample(ConflictType.IMPORT_ADD)).get(0);
        assertTrue(primary.hasRecommendation(), "the additive case has an obvious answer");
        assertTrue(primary.getRecommended().startsWith("Keep both"),
            "recommendation should be the union, was " + primary.getRecommended());
    }
}
