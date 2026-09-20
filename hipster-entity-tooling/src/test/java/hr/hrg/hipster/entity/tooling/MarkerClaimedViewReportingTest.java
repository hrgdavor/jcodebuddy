package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A view that two markers both claim must not be reported about twice
 * (plan.dsflash § 8.7/3.20's known remaining gap).
 *
 * <p>The emission half of this defect was fixed in F-44 ({@code alreadyFound} is hoisted above the
 * marker loop) and the predicate was then refined so an annotated view is a fallback candidate only
 * when no marker claims it by derivation. Re-measured while implementing the follow-up: with those
 * two fixes in place, the fixture below — two marker roots and a view that derives from one of them
 * while carrying {@code @View} — is <strong>already reported once</strong>. The reporting half was
 * therefore closed by the emission fix rather than by a dedup pass, and the notes' claim that it
 * remained open was stale.</p>
 *
 * <p>The test is kept as the property that made the check worthwhile in the first place: a report
 * where the same fact appears once per claiming marker is unusable, and nothing else in the suite
 * asserts that a divergence is not repeated. The reporter's own guarantee — that an identical entry
 * is collapsed <em>and that the collapse is counted</em> — is asserted in
 * {@link DivergenceReporterTest#theIdenticalEntryIsCollapsedAndCounted()}, where it can be shown
 * directly instead of depending on a fixture happening to produce a duplicate.</p>
 *
 * <p>The fixture declares a nested record, so the pass has a legitimate steady-state divergence
 * ({@code nested_record_reused}) to check. Asserting on a kind that fires <em>every</em> pass is what
 * makes the assertion meaningful rather than vacuous.</p>
 */
class MarkerClaimedViewReportingTest {

    private static final String COMPANY_MARKER = """
            package a.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface CompanyEntity extends EntityBase<Long> {}
            """;

    private static final String PERSON_MARKER = """
            package b.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface PersonEntity extends EntityBase<Long> {}
            """;

    /**
     * The view derives from {@code PersonEntity} and carries {@code @View} — so it is claimed by
     * derivation under its own marker and as an annotated fallback under the other one, and a second
     * pass reports {@code nested_record_reused} for it.
     */
    private static final String VIEW = """
            package b.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                record PersonSummaryRecord(Long id, String firstName, String lastName) {}
            }
            """;

    /**
     * Runs the pass twice: the first one writes the tree (nothing to compare against), the second one
     * is the regeneration the report is about — which is the only pass in which these checks have
     * anything to say.
     */
    private static List<String> secondPassDivergences(Path sourceRoot, Path outputRoot,
                                                     DivergenceReporter reporter) throws Exception {
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);
        return reporter.entries();
    }

    @Test
    void aViewClaimedByTwoMarkersReportsEachDivergenceOnce() throws Exception {
        Path sourceRoot = Files.createTempDirectory("claim-source");
        Path outputRoot = Files.createTempDirectory("claim-output");
        Files.writeString(sourceRoot.resolve("CompanyEntity.java"), COMPANY_MARKER);
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), PERSON_MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW);

        DivergenceReporter reporter = new DivergenceReporter();
        List<String> entries = secondPassDivergences(sourceRoot, outputRoot, reporter);

        Assertions.assertFalse(entries.isEmpty(),
                "the fixture must produce a kind that fires on every pass, or this test is vacuous");
        Assertions.assertTrue(entries.stream().anyMatch(e -> e.startsWith("kind=nested_record_reused")),
                "the steady-state divergence about the view is present: " + entries);
        Assertions.assertEquals(1, reporter.ofKind("nested_record_reused").size(),
                "the same fact about the same view is reported once, not once per claiming marker: "
                        + reporter.render());
        Assertions.assertEquals(entries.size(), entries.stream().distinct().count(),
                "no entry is repeated anywhere in the report: " + reporter.render());
    }

    @Test
    void theViewIsStillEmittedOnceAndItsReportIsAboutTheRealMarker() throws Exception {
        Path sourceRoot = Files.createTempDirectory("claim2-source");
        Path outputRoot = Files.createTempDirectory("claim2-output");
        Files.writeString(sourceRoot.resolve("CompanyEntity.java"), COMPANY_MARKER);
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), PERSON_MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW);

        DivergenceReporter reporter = new DivergenceReporter();
        secondPassDivergences(sourceRoot, outputRoot, reporter);

        // The id type comes from the view's REAL marker (`PersonEntity`, id `Long`), not from the
        // doc-sample-style generic marker the `@View` half also admits it to (F-44/F-45). The report
        // dedup must not have been achieved by reporting about the wrong marker.
        Path enumFile = outputRoot.resolve("b/hr/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(enumFile), "the view is emitted under its own package: "
                + Files.list(outputRoot.resolve("b/hr")).toList());
        String emitted = Files.readString(enumFile).replace("\r\n", "\n");
        Assertions.assertTrue(emitted.contains("id(java.lang.Long.class)"),
                "the real marker's id type wins: " + emitted);
        Assertions.assertFalse(emitted.contains("Object.class"),
                "and the generic marker's unresolved id type does not: " + emitted);
    }
}
