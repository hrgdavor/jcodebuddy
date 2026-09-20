package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The Phase 4.1 acceptance test of {@code plan.dsflash.md} § 9.
 *
 * <p>Since the example's entity packages became generator output, the strongest available assertion
 * is that <strong>regenerating them is a byte-identical no-op</strong>. That single property
 * subsumes a whole class of regressions:</p>
 * <ul>
 *   <li>a generator change that alters the emitted shape is caught immediately, with the exact file
 *       named — which is what makes the example the regression artifact S2 says it should be;</li>
 *   <li>the R1 ledger is stable, i.e. a second pass neither reorders nor tombstones anything;</li>
 *   <li>the emitted source compiles, since the committed tree is compiled by
 *       {@code EntityMetadataGeneratorTest} and this test compares against exactly those files.</li>
 * </ul>
 *
 * <p>The test also pins the two intentional absences § 9/4.1 calls out: the orphan {@code Write_}
 * is gone and not regenerated ({@code PersonSummary.Write} is a framework surface, G8 rule 1), and
 * the two stale {@code example/} enums are gone (G7/DR-5).</p>
 */
class ExampleRegenerationTest {

    private static final List<String> GENERATED_PACKAGES = List.of(
            "hr.hrg.hipster.entityexample.person.entity",
            "hr.hrg.hipster.entityexample.paymentMethod.entity");

    private Path exampleSourceRoot() {
        Path repoRoot = CompileHarness.findRepoRoot();
        Path root = repoRoot.resolve("hipster-entity-example/src/main/java");
        Assertions.assertTrue(Files.exists(root), "the example source root must exist");
        return root;
    }

    /**
     * Copies the committed example source tree into a temp directory and generates
     * <strong>in place</strong> there, returning the tree.
     *
     * <p>In-place is the configuration the plan actually specifies (§ 4.1/S2: generated source is
     * committed into {@code src/main/java}), and it is the only configuration in which cooperative
     * codegen can work at all: recognition reads the previous revision of the very file it is about
     * to replace. Generating into a clean output directory instead makes every generated file look
     * brand new, so a user-added member inside one of them — the example's hand-written
     * {@code TrackingStrict}, plan § 4.5/G3 — cannot be carried through and the no-op assertion
     * would fail for a reason that has nothing to do with the generator being wrong.</p>
     *
     * <p>Working on a copy rather than on the committed tree keeps the test free of side effects
     * while still exercising the real path; {@link #exampleSourceRoot()} stays the comparison
     * baseline.</p>
     */
    private Path regenerate() throws Exception {
        Path tree = Files.createTempDirectory("example-regen");
        for (Path source : CompileHarness.javaSourcesUnder(exampleSourceRoot())) {
            Path target = tree.resolve(exampleSourceRoot().relativize(source).toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        EntityMetadataGenerator.setGenerationPackages(GENERATED_PACKAGES);
        // The example deliberately does NOT enable SQL generation: the generated `<View>RowAdapter` /
        // `<View>Binder` pair is a draft/exploration in the tooling and is strictly opt-in, so the
        // committed example contains no such class and the comparison below must not create one. The
        // opt-in rule itself is pinned by ViewAdapterGeneratorTest.
        EntityMetadataGenerator.setGenerateAdapters(false);
        try {
            EntityMetadataGenerator.generate(tree, tree);
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
            EntityMetadataGenerator.setGenerateAdapters(false);
        }
        return tree;
    }

    @Test
    void regeneratingTheExampleIsAByteIdenticalNoOp() throws Exception {
        Path outputRoot = regenerate();

        List<String> differences = new ArrayList<>();
        for (Path generated : CompileHarness.javaSourcesUnder(outputRoot)) {
            String relative = outputRoot.relativize(generated).toString();
            Path committed = exampleSourceRoot().resolve(relative);
            if (!Files.exists(committed)) {
                differences.add("MISSING from the committed tree: " + relative);
            } else if (!Files.readString(generated).equals(Files.readString(committed))) {
                differences.add("DIFFERS: " + relative);
            }
        }

        Assertions.assertTrue(differences.isEmpty(),
                "the committed example must be exactly what the generator emits today; the diff is a "
                        + "generator change that Phase 4.1 would have to commit, or a regression:\n  "
                        + String.join("\n  ", differences));
    }

    @Test
    void regenerationIsAlsoIdempotentOnASecondPass() throws Exception {
        Path first = regenerate();
        Path second = regenerate();

        List<String> firstFiles = relativeNames(first);
        List<String> secondFiles = relativeNames(second);
        Assertions.assertEquals(firstFiles, secondFiles, "two passes must emit the same file set");
        for (String relative : firstFiles) {
            Assertions.assertEquals(Files.readString(first.resolve(relative)),
                    Files.readString(second.resolve(relative)),
                    "generation must be byte-identical across passes: " + relative);
        }
    }

    private static List<String> relativeNames(Path root) throws Exception {
        return CompileHarness.javaSourcesUnder(root).stream()
                .map(p -> root.relativize(p).toString())
                .sorted()
                .collect(Collectors.toList());
    }

    @Test
    void theOrphanWriteEnumIsDeletedAndNotRegenerated() throws Exception {
        Path outputRoot = regenerate();

        Assertions.assertFalse(
                Files.exists(outputRoot.resolve(
                        "hr/hrg/hipster/entityexample/person/entity/Write_.java")),
                "PersonSummary.Write is a framework surface (G8 rule 1), so no `Write_` may be "
                        + "generated — the orphan is deleted outright, not tombstoned (§ 9/4.1)");
        Assertions.assertFalse(
                Files.exists(exampleSourceRoot().resolve(
                        "hr/hrg/hipster/entityexample/person/entity/Write_.java")),
                "and the committed orphan is gone");
    }

    @Test
    void theStaleExamplePackageEnumsAreDeleted() throws Exception {
        Path examplePackage = exampleSourceRoot().resolve("hr/hrg/hipster/entityexample/example");

        Assertions.assertFalse(Files.exists(examplePackage.resolve("PersonAuditable_.java")),
                "superseded by the generated person.entity.PersonAuditable_ (§ 4.7/DR-5)");
        Assertions.assertFalse(Files.exists(examplePackage.resolve("PaymentMethodAuditable_.java")),
                "wrong-shaped and unreferenced (§ 4.7/DR-5)");
        Assertions.assertTrue(Files.exists(examplePackage.resolve("Auditable.java")),
                "while the addon's own interface stays hand-written");
    }

    @Test
    void thePaymentMethodHierarchyIsGenerated() throws Exception {
        Path outputRoot = regenerate();
        Path packageDir = outputRoot.resolve("hr/hrg/hipster/entityexample/paymentMethod/entity");

        for (String subclass : List.of("BankTransferPaymentMethod", "PayPalPaymentMethod",
                "CreditCardPaymentMethod", "CryptoPaymentMethod")) {
            Assertions.assertTrue(Files.exists(packageDir.resolve(subclass + "_.java")),
                    "the sealed hierarchy's concrete views are generated: " + subclass);
        }
    }

    /**
     * The base marker's enum is hand-written and must survive a pass <em>in place</em>.
     *
     * <p>A generation into a clean tree legitimately creates the file, so asserting its absence
     * would be wrong. The invariant that matters is the one § 9/4.9 states: the root's
     * discriminator wiring — its permitted-subtype list — is never derived or overwritten. This
     * test therefore regenerates over a copy of the committed tree and asserts the file is
     * byte-identical afterwards, and separately asserts the committed file still carries the
     * wiring.</p>
     */
    @Test
    void theHandWrittenPolymorphicRootEnumIsNeverOverwritten() throws Exception {
        Path committed = exampleSourceRoot().resolve(
                "hr/hrg/hipster/entityexample/paymentMethod/entity/PaymentMethod_.java");
        Assertions.assertTrue(Files.exists(committed), "the hand-written root enum must be present");
        String before = Files.readString(committed);
        Assertions.assertTrue(before.contains("BankTransferPaymentMethod.class"),
                "and it must carry the permitted-subtype wiring the generator cannot derive (§ 9/4.9)");
        Assertions.assertTrue(before.contains("PaymentMethod_.type"),
                "including the discriminator field constant");

        // Regenerate over a copy of the committed tree, which is the real in-place situation.
        Path tree = Files.createTempDirectory("example-inplace");
        for (Path source : CompileHarness.javaSourcesUnder(exampleSourceRoot())) {
            Path target = tree.resolve(exampleSourceRoot().relativize(source).toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        EntityMetadataGenerator.setGenerationPackages(GENERATED_PACKAGES);
        // No SQL generation: see regenerate() — the adapters are opt-in and the example does not opt in.
        EntityMetadataGenerator.setGenerateAdapters(false);
        try {
            EntityMetadataGenerator.generate(tree, tree);
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
            EntityMetadataGenerator.setGenerateAdapters(false);
        }

        Path regenerated = tree.resolve(
                "hr/hrg/hipster/entityexample/paymentMethod/entity/PaymentMethod_.java");
        Assertions.assertEquals(before, Files.readString(regenerated),
                "in-place regeneration must leave the polymorphic root completely untouched");

        // The protection is the marker exclusion of § 4.5/G8 rule 0: `PaymentMethod` IS the package
        // marker, so it is never discovered as a view and no enum is generated for it in the first
        // place. `FieldBoilerplateGenerator.isPolymorphicRootEnum` is a second, structural line of
        // defence for the case where a root enum does reach the emitter.
        Assertions.assertFalse(Files.exists(
                        tree.resolve("hr/hrg/hipster/entityexample/paymentMethod/entity/PaymentMethod_.java.bak")),
                "nothing is written beside the root enum");
    }

    @Test
    void personCreateFormIsEmittedBecauseOfTheViewSeed() throws Exception {
        Path outputRoot = regenerate();

        Assertions.assertTrue(
                Files.exists(outputRoot.resolve(
                        "hr/hrg/hipster/entityexample/person/entity/PersonCreateForm_.java")),
                "PersonCreateForm extends NOTHING, so only the @View half of the discovery predicate "
                        + "finds it (§ 4.5/G8 rule 0, task 3.2a)");
    }

    @Test
    void theFieldEnumsCarryTheR1MarkerAndAreAppendOnlyLedgers() throws Exception {
        Path outputRoot = regenerate();

        Path summaryEnum = outputRoot.resolve(
                "hr/hrg/hipster/entityexample/person/entity/PersonSummary_.java");
        String source = Files.readString(summaryEnum);

        Assertions.assertTrue(source.contains("entityFieldEnum:true"),
                "every generated field enum carries the R1 marker, so opt-out has to be deliberate");
        Assertions.assertTrue(hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker
                        .readLedgers(source).values().iterator().next().guarded(),
                "and the checker must recognise it as a guardable ledger");

        // The leaked pre-R1 constants are gone: G7's bootstrap path dropped them because the
        // hand-written enum carried no marker.
        Assertions.assertFalse(source.contains("toBuilder"),
                "the leaked default-method constant is gone, not tombstoned (G7): " + source);
    }

    @Test
    void theTrackingBuilderIsGeneratedForTheBuilderAllView() throws Exception {
        Path outputRoot = regenerate();
        Path packageDir = outputRoot.resolve("hr/hrg/hipster/entityexample/person/entity");

        Assertions.assertTrue(Files.exists(packageDir.resolve("PersonSummaryBuilder.java")),
                "PersonSummary is @View(gen = BUILDER_ALL), so both builders are emitted");
        Assertions.assertTrue(Files.exists(packageDir.resolve("PersonSummaryBuilderTracking.java")),
                "including the tracking one (§ 8.6/3.17)");

        String tracking = Files.readString(packageDir.resolve("PersonSummaryBuilderTracking.java"));
        Assertions.assertTrue(tracking.contains("EEnumSet<PersonSummary_>"),
                "and the tracking builder implements the final S5 contract (DR-4)");
    }
}
