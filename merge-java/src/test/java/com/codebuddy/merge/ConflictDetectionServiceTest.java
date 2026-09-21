// {@link com.codebuddy.merge.ConflictDetectionServiceTest} Tests for conflict detection.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that detection finds the conflicts the module can act on, and that
 * every detected conflict is actioned by a registered resolver - which is what
 * guarantees detection and resolution cannot drift apart.
 */
class ConflictDetectionServiceTest {

    private static final String FILE = ConflictFixtures.FILE;

    private final ConflictDetectionService detector = new ConflictDetectionService();

    @Test
    @DisplayName("finds nothing when all three versions agree")
    void findsNothingWhenIdentical() {
        List<Conflict> conflicts = detector.detect(FILE,
            "import java.util.List;\nint total = 0;",
            "import java.util.List;\nint total = 0;",
            "import java.util.List;\nint total = 0;");

        assertTrue(conflicts.isEmpty(), "identical versions have no conflict: " + conflicts);
    }

    @Test
    @DisplayName("finds an import conflict when both branches add imports")
    void detectsImportConflict() {
        List<Conflict> conflicts = detector.detect(FILE,
            "import java.util.List;",
            "import java.util.List;\nimport java.math.BigDecimal;",
            "import java.util.List;\nimport java.time.Instant;");

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.IMPORT_ADD, conflicts.get(0).getType());
        assertEquals(FILE, conflicts.get(0).getFilePath(),
            "a conflict must be anchored to its file so history stays per file");
        assertTrue(conflicts.get(0).getDescription().contains("imports"),
            "the description must name what was added: " + conflicts.get(0).getDescription());
    }

    @Test
    @DisplayName("reports two different import additions as one additive conflict")
    void reportsDifferentImportAdditions() {
        // The motivating case: neither branch added the same import, yet this is
        // trivially resolvable by keeping both.
        List<Conflict> conflicts = detector.detectImportConflicts(FILE,
            "import java.util.List;",
            "import java.util.List;\nimport java.math.BigDecimal;",
            "import java.util.List;\nimport java.time.Instant;");

        assertEquals(1, conflicts.size(),
            "two different additions are still one conflict worth resolving");
        assertTrue(conflicts.get(0).getDescription().contains("BigDecimal"));
        assertTrue(conflicts.get(0).getDescription().contains("Instant"));

        ConflictResolution resolution =
            ConflictResolvers.find(ConflictResolvers.defaultResolvers(), ConflictType.IMPORT_ADD)
                .orElseThrow()
                .resolve(conflicts.get(0));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "and it must resolve automatically");
        assertTrue(resolution.getResolvedCode().contains("java.math.BigDecimal"));
        assertTrue(resolution.getResolvedCode().contains("java.time.Instant"));
    }

    @Test
    @DisplayName("does not report an import conflict when only one branch adds imports")
    void ignoresOneSidedImportAddition() {
        List<Conflict> conflicts = detector.detectImportConflicts(FILE,
            "import java.util.List;",
            "import java.util.List;\nimport java.math.BigDecimal;",
            "import java.util.List;");

        assertTrue(conflicts.isEmpty(),
            "a one-sided addition is not a conflict at all: " + conflicts);
    }

    @Test
    @DisplayName("finds a comment conflict when both branches add documentation")
    void detectsCommentConflict() {
        List<Conflict> conflicts = detector.detectCommentConflicts(FILE,
            "int total = 0;",
            "// branch 1 note\nint total = 0;",
            "// branch 2 note\nint total = 0;");

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.COMMENT_ADD, conflicts.get(0).getType());
    }

    @Test
    @DisplayName("finds a constant conflict when both branches add constants")
    void detectsConstantConflict() {
        List<Conflict> conflicts = detector.detectConstantConflicts(FILE,
            "static final int MAX_RETRIES = 3;",
            "static final int MAX_RETRIES = 3;\nstatic final int TIMEOUT_MS = 500;",
            "static final int MAX_RETRIES = 3;\nstatic final int RETRY_DELAY_MS = 250;");

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.CONSTANT_ADD, conflicts.get(0).getType());
    }

    @Test
    @DisplayName("finds a package conflict when both branches move the class")
    void detectsPackageConflict() {
        List<Conflict> conflicts = detector.detectPackageConflicts(FILE,
            "package com.example.payments;",
            "package com.example.billing;",
            "package com.example.ledger;");

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.PACKAGE_CHANGE, conflicts.get(0).getType());
    }

    @Test
    @DisplayName("finds a type conflict when the declared type differs")
    void detectsTypeConflict() {
        List<Conflict> conflicts = detector.detectTypeConflicts(FILE,
            "int count = 0;", "long count = 0;", "double count = 0;");

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.TYPE_CHANGE, conflicts.get(0).getType());
    }

    @Test
    @DisplayName("finds a rename conflict when a declaration is renamed differently")
    void detectsRenameConflict() {
        List<Conflict> conflicts = detector.detectRenameConflicts(FILE,
            "int order = 1;", "int purchase = 1;", "int invoice = 1;");

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.VARIABLE_RENAME, conflicts.get(0).getType());
        assertTrue(conflicts.get(0).getDescription().contains("purchase"));
        assertTrue(conflicts.get(0).getDescription().contains("invoice"));
    }

    @Test
    @DisplayName("finds an API conflict when a public declaration differs")
    void detectsApiConflict() {
        List<Conflict> conflicts = detector.detectApiConflicts(FILE,
            "public void process() { }",
            "public int process() { }",
            "public long process() { }");

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.API_INCOMPATIBILITY, conflicts.get(0).getType());
    }

    @Test
    @DisplayName("falls back to a structural conflict for unrecognised divergence")
    void fallsBackToStructuralConflict() {
        // Real imports, so the body change is not masked by an import conflict,
        // plus a new method added differently on each side.
        List<Conflict> conflicts = detector.detect(FILE,
            "import java.util.List;\nvoid process() { audit(); }\nvoid audit() { }",
            "import java.util.List;\nvoid process() { audit(); charge(); }\nvoid audit() { }",
            "import java.util.List;\nvoid process() { audit(); refund(); }\nvoid audit() { }");

        assertFalse(conflicts.isEmpty(), "unrecognised divergence must not be silently dropped");
        assertTrue(conflicts.stream().anyMatch(conflict ->
                conflict.getType() == ConflictType.STRUCTURAL_CHANGE),
            "expected a structural fallback: " + conflicts);
    }

    /**
     * The invariant that keeps the module honest: anything detection can produce
     * has a resolver that will act on it, so no conflict is discovered and then
     * quietly abandoned.
     */
    @Test
    @DisplayName("every detected conflict is actioned by a registered resolver")
    void everyDetectedConflictIsActioned() {
        Set<ConflictType> detected = java.util.Arrays.stream(ConflictType.values())
            .filter(type -> !detector.detect(FILE,
                ConflictFixtures.baseCode(type),
                ConflictFixtures.branch1Code(type),
                ConflictFixtures.branch2Code(type)).isEmpty())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        assertFalse(detected.isEmpty(), "the fixtures must produce detectable conflicts");

        Set<ConflictType> unhandled = ConflictResolvers.unhandledTypes(
            ConflictResolvers.defaultResolvers());
        for (ConflictType type : detected) {
            assertFalse(unhandled.contains(type),
                "detection produced " + type + " for which no resolver is registered");
        }
    }

    @Test
    @DisplayName("accepts the three-version overload without a file path")
    void supportsPathslessOverload() {
        List<Conflict> conflicts = detector.detect(
            "import java.util.List;",
            "import java.util.List;\nimport java.math.BigDecimal;",
            "import java.util.List;\nimport java.time.Instant;");

        assertEquals(1, conflicts.size());
        assertEquals("<unknown>", conflicts.get(0).getFilePath(),
            "without a path the conflict is still usable, just unanchored");
    }

    @Test
    @DisplayName("tolerates null and empty versions")
    void toleratesEmptyInput() {
        assertTrue(detector.detect(FILE, null, null, null).isEmpty());
        assertTrue(detector.detect(FILE, "", "", "").isEmpty());
    }
}
