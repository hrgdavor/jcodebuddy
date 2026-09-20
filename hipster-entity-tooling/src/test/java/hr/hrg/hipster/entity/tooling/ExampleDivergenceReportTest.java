package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The example tree's divergence report must be <strong>exactly</strong> a known set
 * (plan.dsflash § 8.7/3.20 and § 5.3 of the follow-up plan).
 *
 * <p>Why this test exists: the checks that produce divergences were each tested by asserting that a
 * <em>specific</em> kind appears after a specific damage, and nothing asserted the other direction.
 * F-43 records what that costs — a {@code type_mismatch} guard was written against the wrong string,
 * could never fire, and "the false {@code type_mismatch} I had reported as fixed was still being
 * emitted on every pass", invisible because no test looked at the report as a whole. The same
 * blindness hid F-44's non-deterministic {@code Object.class} and F-38's ten false positives.</p>
 *
 * <p>So this is the one test that treats the report as a value: a default pass over the real example
 * must produce the set below and <em>nothing else</em>. The set is measured, not assumed — the
 * assertion fails on an unexpected kind and on a missing one, because "some entries" is not a gate
 * and an explicitly empty expectation would hide a legitimate steady-state entry.</p>
 */
class ExampleDivergenceReportTest {

    private static final List<String> GENERATED_PACKAGES = List.of(
            "hr.hrg.hipster.entityexample.person.entity",
            "hr.hrg.hipster.entityexample.paymentMethod.entity");

    /**
     * The kinds a default (non-{@code --adapters}) pass over the committed example produces.
     *
     * <p>Each one is a steady-state fact about the tree rather than a problem:</p>
     * <ul>
     *   <li>{@code nested_record_reused} — the views that declare a matching nested record;
     *       {@code create()} targets it instead of emitting a second record (§ 8.4/3.10).</li>
     *   <li>{@code addon_field_collision} — {@code PersonDetails} inherits {@code Person} and
     *       declares {@code @View(addons = PersonAuditable.class)}; the addon's {@code firstName} and
     *       {@code lastName} are deliberately skipped because the view already has them, and § 4.5/G6
     *       requires that skip to be reported rather than silent.</li>
     * </ul>
     *
     * <p>The R1 ledger kinds ({@code enum_constant_appended}, {@code enum_constant_removed},
     * {@code field_retired}, …) are deliberately absent: every committed enum carries the marker, so
     * the ledger path preserves the order and there is nothing to append or retire. If one of them
     * appears here, the tree and the ledger have diverged — which is a real finding, not a reason to
     * widen this set.</p>
     *
     * <p>Also deliberately absent, and worth naming so its absence is not mistaken for an oversight:
     * {@code polymorphic_root_enum_preserved}. The hand-written {@code PaymentMethod_} root is
     * protected twice over — the package marker is never discovered as a view (G8 rule 0), and
     * {@code FieldBoilerplateGenerator.isPolymorphicRootEnum} is the structural backstop — so on this
     * tree the emitter is never reached for it and the kind does not fire. Its producing test is
     * {@code ExampleRegenerationTest#theHandWrittenPolymorphicRootEnumIsNeverOverwritten}, which
     * asserts the stronger property (the file is byte-identical after a pass).</p>
     */
    private static final Set<String> EXPECTED_KINDS = Set.of(
            "nested_record_reused",
            "addon_field_collision");

    /** A default pass over a copy of the committed example, with the report kept. */
    private static DivergenceReporter regenerateWithReport() throws Exception {
        Path exampleRoot = CompileHarness.findRepoRoot().resolve("hipster-entity-example/src/main/java");
        Assertions.assertTrue(Files.exists(exampleRoot), "the example source root must exist");

        Path tree = Files.createTempDirectory("example-report");
        for (Path source : CompileHarness.javaSourcesUnder(exampleRoot)) {
            Path target = tree.resolve(exampleRoot.relativize(source).toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }

        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.setGenerationPackages(GENERATED_PACKAGES);
        // The example does not opt into SQL generation (D-17); a default pass is what is measured.
        EntityMetadataGenerator.setGenerateAdapters(false);
        try {
            EntityMetadataGenerator.generate(tree, tree, tree, reporter);
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
            EntityMetadataGenerator.setGenerateAdapters(false);
        }
        return reporter;
    }

    /** The kinds named by a report, deduplicated and sorted, so a diff is readable. */
    private static Set<String> kindsOf(List<String> entries) {
        Set<String> kinds = new TreeSet<>();
        for (String entry : entries) {
            Assertions.assertTrue(entry.startsWith("kind="),
                    "every entry is in the DEC-022 shape: " + entry);
            int comma = entry.indexOf(',');
            String kind = comma < 0 ? entry : entry.substring(0, comma);
            kinds.add(kind.substring("kind=".length()).trim());
        }
        return kinds;
    }

    @Test
    void aDefaultPassOverTheExampleReportsExactlyTheKnownKinds() throws Exception {
        DivergenceReporter reporter = regenerateWithReport();
        Set<String> actual = kindsOf(reporter.entries());

        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(EXPECTED_KINDS);
        Set<String> missing = new TreeSet<>(EXPECTED_KINDS);
        missing.removeAll(actual);

        Assertions.assertTrue(unexpected.isEmpty(),
                "no unplanned divergence may appear in a default pass — an unexpected kind is either a "
                        + "new fact to pin or a false positive to fix (F-38/F-43/F-44). Full report:\n"
                        + reporter.render());
        Assertions.assertTrue(missing.isEmpty(),
                "and a kind that is expected must actually fire, or the expectation is decoration. "
                        + "Full report:\n" + reporter.render());
    }

    @Test
    void noEntryInTheExampleReportIsRepeated() throws Exception {
        DivergenceReporter reporter = regenerateWithReport();

        Assertions.assertEquals(reporter.entries().size(),
                new LinkedHashSet<>(reporter.entries()).size(),
                "the report reads as a list of facts, so no fact may appear twice:\n"
                        + reporter.render());
    }

    /**
     * Every kind the generator can report is produced by a named test.
     *
     * <p>This guards the other direction of the same decay: a kind in
     * {@link DivergenceReporter#KINDS} that no code path can ever produce is a promise rather than a
     * check. The map names the test that produces each kind, and the test asserts the map covers the
     * list exactly — a kind added to the list without a producer fails here instead of quietly
     * becoming documentation.</p>
     *
     * <p>Two kinds are <strong>not</strong> in this map on purpose, and the reason is worth stating:
     * {@code addon_on_non_view} is produced by {@code ViewAnnotationRule} and
     * {@code enum_order_shuffled} by {@code EnumConstantOrderChecker}. Both are members of the
     * validator/checker subsystem with their own violation channel, not of the generation pass, so a
     * generation pass can never emit them. They are listed in {@code KINDS} because that list is the
     * project-wide vocabulary of divergence kinds; {@link #KINDS_PRODUCED_ELSEWHERE} names their
     * producers so the exception is explicit rather than an unexplained gap.</p>
     */
    private static final Map<String, String> KIND_PRODUCER = Map.ofEntries(
            Map.entry("nested_record_reused", "this class (the example's own pass)"),
            Map.entry("addon_field_collision", "this class (the example's own pass)"),
            Map.entry("polymorphic_root_enum_preserved",
                    "ExampleRegenerationTest (the root enum is never reached by the emitter here)"),
            Map.entry("source_not_parsed", "ParseGuardTest"),
            Map.entry("field_in_enum_not_in_interface", "DivergenceKindTest"),
            Map.entry("field_in_interface_not_in_enum", "DivergenceKindTest"),
            Map.entry("stale_switch", "DivergenceKindTest"),
            Map.entry("missing_setter", "DivergenceKindTest"),
            Map.entry("type_mismatch", "DivergenceKindTest"),
            Map.entry("ordinal_drift", "DivergenceKindTest"),
            Map.entry("enum_constant_removed", "DivergenceKindTest"),
            Map.entry("enum_constant_appended", "DivergenceKindTest"),
            Map.entry("enum_reorder_allowed", "DivergenceKindTest"),
            Map.entry("enum_not_parsed", "DivergenceKindTest"),
            Map.entry("field_retired", "TombstoneLedgerTest"),
            Map.entry("type_ambiguous", "AddonAndInheritanceTest"),
            Map.entry("type_unresolved", "UnresolvedTypeNameTest"),
            Map.entry("generated_member_diverged", "CooperativeCodegenTest"),
            Map.entry("mapper_field_missing_in_source", "ViewMapperGeneratorTest"),
            Map.entry("mapper_field_missing_in_target", "ViewMapperGeneratorTest"),
            Map.entry("mapper_type_incompatible", "ViewMapperGeneratorTest"),
            Map.entry("mapper_view_not_found", "ViewMapperGeneratorTest"),
            Map.entry("mapper_request_malformed", "ViewMapperGeneratorTest"),
            Map.entry("validation_constraint_unsupported", "ValidationGeneratorTest"),
            Map.entry("validation_constraint_type_mismatch", "ValidationGeneratorTest"),
            Map.entry("deep_tracking_type_not_enabled", "DeepTrackingWiringTest"));

    /** Kinds in the project vocabulary whose producer is a validator, not the generation pass. */
    private static final Map<String, String> KINDS_PRODUCED_ELSEWHERE = Map.of(
            "addon_on_non_view", "ViewAnnotationRuleTest (the annotation rule's own channel)",
            "enum_order_shuffled", "EnumConstantOrderCheckerTest (the checker's violation list)");

    @Test
    void everyRecognizedKindIsEitherProducedOrExplicitlyUnreachable() {
        List<String> unclassified = new ArrayList<>();
        for (String kind : DivergenceReporter.KINDS) {
            if (!KIND_PRODUCER.containsKey(kind) && !KINDS_PRODUCED_ELSEWHERE.containsKey(kind)) {
                unclassified.add(kind);
            }
        }
        Assertions.assertTrue(unclassified.isEmpty(),
                "a recognized kind that nothing produces is a promise rather than a check; name its "
                        + "producing test (or its other channel): " + unclassified);

        List<String> unknown = new ArrayList<>();
        for (String kind : KIND_PRODUCER.keySet()) {
            if (!DivergenceReporter.KINDS.contains(kind)) {
                unknown.add(kind);
            }
        }
        for (String kind : KINDS_PRODUCED_ELSEWHERE.keySet()) {
            if (!DivergenceReporter.KINDS.contains(kind)) {
                unknown.add(kind);
            }
        }
        Assertions.assertTrue(unknown.isEmpty(),
                "and the classification may not name a kind the reporter does not recognize: " + unknown);
    }

    @Test
    void everyKindNamedByTheMapIsActuallyProducedInTheTree() throws Exception {
        DivergenceReporter reporter = regenerateWithReport();
        Set<String> actual = kindsOf(reporter.entries());

        // The map's entries that name this class as the producer must be in the report it just
        // produced; the rest are produced by fixtures, which this test cannot run. Asserting the
        // self-produced half here keeps the map from drifting away from the report it describes.
        for (Map.Entry<String, String> producer : KIND_PRODUCER.entrySet()) {
            if (!producer.getValue().startsWith("this class")) {
                continue;
            }
            Assertions.assertTrue(actual.contains(producer.getKey()),
                    "the map claims this class produces " + producer.getKey()
                            + ", so it must be in the report:\n" + reporter.render());
        }
    }
}
