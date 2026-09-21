// {@link com.codebuddy.merge.ConflictCompositionTest} Tests for composing conflicts and applying only the safe subset.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defect these tests pin down: a file that is mostly mechanical but contains
 * one unrelated structural change used to be reported as wholly manual, so the
 * mechanical part was silently lost. Merges are routinely heterogeneous, so the
 * tool must compose conflicts rather than let one replace the others.
 *
 * <p>The three versions are complete files, because region attribution has to
 * locate each conflict inside the base version. Passing a bare hunk is supported
 * but degrades to an unknown region, which conservatively blocks independent
 * application.
 */
class ConflictCompositionTest {

    @TempDir
    Path tempDir;

    /**
     * Branch 1 extends the body of method {@code a} and adds an import. Branch 2
     * deletes method {@code b} and adds a different import.
     *
     * <p>The deletion gives the structural conflict a measurable base region, so
     * the disjoint import merge remains independently applicable - which is the
     * whole point of composing conflicts.
     */
    private static final String BASE =
        "import java.util.List;\n"
            + "import java.util.Map;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "        audit();\n"
            + "    }\n"
            + "    void b() {\n"
            + "    }\n"
            + "}\n";

    private static final String BRANCH1 =
        "import java.util.List;\n"
            + "import java.util.Map;\n"
            + "import java.math.BigDecimal;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "        audit();\n"
            + "        charge();\n"
            + "    }\n"
            + "    void b() {\n"
            + "    }\n"
            + "}\n";

    private static final String BRANCH2 =
        "import java.util.List;\n"
            + "import java.util.Map;\n"
            + "import java.time.Instant;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "        audit();\n"
            + "    }\n"
            + "}\n";

    private MergeConflictResolver resolver() {
        return new MergeConflictResolver.Builder()
            .setBranchName("feature")
            .setHistoryPath(tempDir.resolve("feature"))
            .setTypeContext(TestTypeContexts.jdk())
            .build();
    }

    private List<Conflict> detect() {
        return new ConflictDetectionService().detect("Payment.java",
            BASE, BRANCH1, BRANCH2);
    }

    // ------------------------------------------------------- span arithmetic

    @Test
    @DisplayName("the span covers a method one branch dropped")
    void spanCoversDroppedMethod() {
        Region span = ConflictDetectionService.spanNotKeptByBoth(
            BASE.split("\n", -1), BRANCH1, BRANCH2);

        assertTrue(span.isKnown(), "a dropped method must produce a span: " + span);
        assertEquals(7, span.startLine(),
            "the span starts at the dropped method's declaration, base line 7");
        assertEquals(7, span.endLine(),
            "the closing brace survives in both branches, so the span is the declaration alone: "
                + span);
    }

    @Test
    @DisplayName("the span is unknown when nothing was dropped")
    void spanUnknownWhenNothingDropped() {
        String base = "class A {\n    void a() {\n    }\n}\n";
        String appended = "class A {\n    void a() {\n    }\n    void b() {\n    }\n}\n";

        Region span = ConflictDetectionService.spanNotKeptByBoth(
            base.split("\n", -1), appended, appended);

        assertFalse(span.isKnown(),
            "a purely additive change drops no base line, so there is no span: " + span);
    }

    @Test
    @DisplayName("blank lines are not treated as dropped")
    void blankLinesAreNotDropped() {
        Region span = ConflictDetectionService.spanNotKeptByBoth(
            BASE.split("\n", -1), BASE, BASE);

        assertFalse(span.isKnown(),
            "identical versions must not produce a span: " + span);
    }

    // ----------------------------------------------------------- composition

    @Test
    @DisplayName("reports recognised and structural conflicts together, not one instead of the other")
    void composesRecognisedAndStructuralConflicts() {
        List<ConflictType> types = detect().stream().map(Conflict::getType).toList();

        assertTrue(types.contains(ConflictType.IMPORT_ADD),
            "the mechanical import conflict must survive alongside the structural one: " + types);
        assertTrue(types.contains(ConflictType.STRUCTURAL_CHANGE),
            "a deleted method is structural: " + types);
    }

    @Test
    @DisplayName("a purely additive change is not reported as structural")
    void additiveChangeIsNotStructural() {
        List<Conflict> conflicts = new ConflictDetectionService().detect("Payment.java",
            BASE,
            BASE.replace("import java.util.Map;",
                "import java.util.Map;\nimport java.math.BigDecimal;"),
            BASE.replace("import java.util.Map;",
                "import java.util.Map;\nimport java.time.Instant;"));

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.IMPORT_ADD, conflicts.get(0).getType());
    }

    @Test
    @DisplayName("attributes each conflict to the base lines it covers")
    void attributesRegions() {
        List<Conflict> conflicts = detect();

        Conflict imports = conflicts.stream()
            .filter(c -> c.getType() == ConflictType.IMPORT_ADD).findFirst().orElseThrow();
        Conflict structural = conflicts.stream()
            .filter(c -> c.getType() == ConflictType.STRUCTURAL_CHANGE).findFirst().orElseThrow();

        assertTrue(imports.getRegion().isKnown(),
            "the import region must be known: " + imports.getRegion());
        assertTrue(structural.getRegion().isKnown(),
            "the dropped method gives a measurable region: " + structural.getRegion());
        assertFalse(imports.conflictsWith(structural),
            "the import block and the dropped method do not overlap: "
                + imports.getRegion() + " vs " + structural.getRegion());
    }

    @Test
    @DisplayName("applies the automatic subset independently of the manual remainder")
    void appliesAutomaticSubsetIndependently() {
        MergeConflictResolver.MergeReport report =
            resolver().resolve("Payment.java", BASE, BRANCH1, BRANCH2);

        assertTrue(report.hasUnresolvedConflicts(),
            "the deleted method needs a human: " + report.summarize());
        assertFalse(report.getAutoResolutions().isEmpty(), "the import merge is automatic");

        List<ConflictResolution> applicable = report.getIndependentlyApplicable();
        assertEquals(report.getAutoResolutions().size(), applicable.size(),
            "the import merge is disjoint from the manual conflict, so it must be applicable: "
                + report.summarize());
        assertTrue(applicable.get(0).getResolvedCode().contains("java.math.BigDecimal"));
        assertTrue(applicable.get(0).getResolvedCode().contains("java.time.Instant"));
    }

    // ------------------------------------------------------- applicability

    @Test
    @DisplayName("never reports a resolution as applicable when a manual conflict shares its lines")
    void blocksApplicabilityOnOverlap() {
        MergeConflictResolver.MergeReport report = new MergeConflictResolver.MergeReport(
            "A.java", List.of(),
            List.of(auto(Region.spanning(1, 10)), manual(Region.spanning(5, 20))),
            "feature");

        assertTrue(report.getIndependentlyApplicable().isEmpty(),
            "an overlapping manual conflict must block the automatic one");
        assertEquals(1, report.getBlockedAutoResolutions().size());
    }

    @Test
    @DisplayName("never reports an unattributed resolution as applicable")
    void blocksApplicabilityWhenRegionUnknown() {
        MergeConflictResolver.MergeReport report = new MergeConflictResolver.MergeReport(
            "A.java", List.of(), List.of(auto(Region.unknown())), "feature");

        assertTrue(report.getIndependentlyApplicable().isEmpty(),
            "without a region, disjointness cannot be proven, so nothing is applicable");
    }

    @Test
    @DisplayName("carries the conflict's region onto its resolution")
    void carriesRegionOntoResolution() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD)
            .withRegion(Region.spanning(3, 5));

        ConflictResolution resolution = resolver().resolve(conflict);

        assertEquals(Region.spanning(3, 5), resolution.getRegion(),
            "a resolution must know which lines it covers");
    }

    // ------------------------------------------------------------- reporting

    @Test
    @DisplayName("reports a file as fully automatic only when nothing is left over")
    void reportsFullyAutomatic() {
        MergeConflictResolver.MergeReport automatic = resolver().resolve("Payment.java",
            BASE,
            BASE.replace("import java.util.Map;",
                "import java.util.Map;\nimport java.math.BigDecimal;"),
            BASE.replace("import java.util.Map;",
                "import java.util.Map;\nimport java.time.Instant;"));
        MergeConflictResolver.MergeReport mixed =
            resolver().resolve("Payment.java", BASE, BRANCH1, BRANCH2);

        assertTrue(automatic.isFullyAutomatic(),
            "a single disjoint automatic conflict is fully automatic: " + automatic.summarize());
        assertFalse(mixed.isFullyAutomatic(), mixed.summarize());
    }

    @Test
    @DisplayName("describes the outcome per region for a reviewer")
    void describesRegionsForReview() {
        MergeConflictResolver.MergeReport report =
            resolver().resolve("Payment.java", BASE, BRANCH1, BRANCH2);

        List<String> described = report.describeRegions();

        assertEquals(report.getConflicts().size(), described.size());
        assertTrue(described.stream().noneMatch(String::isBlank));
        assertTrue(described.stream().anyMatch(line -> line.contains("AUTO")),
            "the reviewer must see which region was handled: " + described);
        assertTrue(described.stream().anyMatch(line -> line.contains("MANUAL")),
            "and which needs them: " + described);
        assertTrue(described.stream().anyMatch(line -> line.startsWith("lines")),
            "regions must be named by line: " + described);
    }

    @Test
    @DisplayName("the summary counts applicable, not merely automatic")
    void summaryDistinguishesApplicableFromAutomatic() {
        MergeConflictResolver.MergeReport report = new MergeConflictResolver.MergeReport(
            "A.java", List.of(),
            List.of(auto(Region.spanning(1, 2)), manual(Region.spanning(1, 2))),
            "feature");

        assertNotNull(report.summarize());
        assertTrue(report.summarize().contains("1 auto (0 applicable)"),
            "the summary must not imply the overlapping automatic change can be applied: "
                + report.summarize());
    }

    @Test
    @DisplayName("counts replayed decisions separately in the summary")
    void summaryCountsReplayedDecisions() {
        ConflictResolution deferred = ConflictResolution.copyOf(auto(Region.spanning(1, 2)))
            .kind(ConflictResolution.ResolutionKind.DEFERRED)
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.STICKY_REPLAY)
            .build();
        MergeConflictResolver.MergeReport report = new MergeConflictResolver.MergeReport(
            "A.java", List.of(), List.of(deferred), "feature");

        assertTrue(report.summarize().contains("1 replayed"), report.summarize());
        assertEquals(1, report.getDeferredResolutions().size());
        assertTrue(report.getIndependentlyApplicable().isEmpty(),
            "a replayed decision is not a fresh automatic resolution");
    }

    private static ConflictResolution auto(Region region) {
        return ConflictResolution.builder()
            .filePath("A.java")
            .type(ConflictType.IMPORT_ADD)
            .resolvedCode("import java.util.List;")
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .kind(ConflictResolution.ResolutionKind.AUTO)
            .region(region)
            .build();
    }

    private static ConflictResolution manual(Region region) {
        return ConflictResolution.builder()
            .filePath("A.java")
            .type(ConflictType.STRUCTURAL_CHANGE)
            .resolvedCode(ConflictResolution.MANUAL_MARKER)
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.MANUAL)
            .kind(ConflictResolution.ResolutionKind.MANUAL)
            .region(region)
            .build();
    }
}
