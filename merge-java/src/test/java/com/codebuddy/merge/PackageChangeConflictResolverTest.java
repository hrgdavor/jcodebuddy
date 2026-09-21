// {@link com.codebuddy.merge.PackageChangeConflictResolverTest} Tests for the package move conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a one-sided move is adopted automatically, while two different
 * destinations are escalated and remembered.
 */
class PackageChangeConflictResolverTest extends AbstractResolverTest {

    private final PackageChangeConflictResolver resolver = new PackageChangeConflictResolver();

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
        return List.of(ConflictType.IMPORT_ADD, ConflictType.TYPE_CHANGE);
    }

    @Test
    @DisplayName("escalates two different destinations and remembers the choice")
    void escalatesTwoDifferentDestinations() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.PACKAGE_CHANGE));

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "both branches moved the class, so a human picks the home");
        assertTrue(resolution.isSticky(), "a package choice must be replayable");
        assertTrue(resolution.getExplanation().contains("com.example.billing"));
        assertTrue(resolution.getExplanation().contains("com.example.ledger"));
    }

    @Test
    @DisplayName("adopts a move when only one branch moved the class")
    void adoptsOneSidedMove() {
        Conflict conflict = new Conflict(ConflictType.PACKAGE_CHANGE, ConflictFixtures.FILE,
            "one side moved the class",
            "package com.example.payments;",
            "package com.example.billing;",
            "package com.example.payments;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "the move is the only deliberate change, so it can be adopted");
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH1,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("com.example.billing"));
    }

    @Test
    @DisplayName("declines when the package declarations agree")
    void declinesWhenPackagesAgree() {
        Conflict conflict = new Conflict(ConflictType.PACKAGE_CHANGE, ConflictFixtures.FILE,
            "same package", "package a;", "package a;", "package a;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }

    @Test
    @DisplayName("declines when no package declaration is present")
    void declinesWithoutPackageDeclaration() {
        Conflict conflict = new Conflict(ConflictType.PACKAGE_CHANGE, ConflictFixtures.FILE,
            "no package", "int x = 1;", "int x = 2;", "int x = 3;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }

    @Test
    @DisplayName("extracts the declared package")
    void extractsPackage() {
        assertEquals("com.example.billing",
            PackageChangeConflictResolver.firstPackage("package com.example.billing;").orElseThrow());
        assertTrue(PackageChangeConflictResolver.firstPackage("int x = 1;").isEmpty());
    }

    @Test
    @DisplayName("flags imports that must be re-pointed after a move")
    void flagsImportsToUpdate() {
        Conflict conflict = new Conflict(ConflictType.PACKAGE_CHANGE, ConflictFixtures.FILE,
            "move with imports",
            "package com.example.payments;",
            "package com.example.billing;\nimport com.example.billing.Ledger;",
            "package com.example.ledger;\nimport com.example.ledger.Account;");

        List<FixPath> fixPaths = resolver.getFixPaths(conflict);

        assertTrue(fixPaths.stream().anyMatch(path ->
                path.getDescription().toLowerCase().contains("import")),
            "a package move implies import updates: " + fixPaths);
    }
}
