package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>All tests read one shared pass over a temporary copy of the committed example, except
 * {@link #regenerationIsAlsoIdempotentOnASecondPass()}, which asks the fixture for a second pass over
 * an independent copy of that output.</p>
 */
@Tag("generator")
class ExampleRegenerationTest {

    private static ExamplePassFixture fixture;

    @BeforeAll
    static void generateOnce() throws Exception {
        fixture = ExamplePassFixture.shared();
    }

    private Path exampleSourceRoot() {
        Path repoRoot = CompileHarness.findRepoRoot();
        Path root = repoRoot.resolve("hipster-entity/hipster-entity-example/src/main/java");
        Assertions.assertTrue(Files.exists(root), "the example source root must exist");
        return root;
    }

    @Test
    void regeneratingTheExampleIsAByteIdenticalNoOp() throws Exception {
        List<String> differences = new ArrayList<>();
        for (String relative : fixture.javaFileNames()) {
            Path committed = exampleSourceRoot().resolve(relative);
            if (!Files.exists(committed)) {
                differences.add("MISSING from the committed tree: " + relative);
            } else if (!fixture.readSource(relative).equals(Files.readString(committed))) {
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
        ExamplePassFixture second = fixture.secondPass();

        List<String> firstFiles = fixture.javaFileNames();
        List<String> secondFiles = second.javaFileNames();
        Assertions.assertEquals(firstFiles, secondFiles, "two passes must emit the same file set");
        for (String relative : firstFiles) {
            Assertions.assertEquals(fixture.readSource(relative), second.readSource(relative),
                    "generation must be byte-identical across passes: " + relative);
        }
    }

    @Test
    void theOrphanWriteEnumIsDeletedAndNotRegenerated() throws Exception {
        Assertions.assertFalse(
                Files.exists(fixture.sourceRoot().resolve(
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
        Path packageDir = fixture.sourceRoot()
                .resolve("hr/hrg/hipster/entityexample/paymentMethod/entity");

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
     * discriminator wiring — its permitted-subtype list — is never derived or overwritten. The shared
     * fixture regenerates over a copy of the committed tree, and this asserts the file is byte-identical
     * afterwards, and separately asserts the committed file still carries the wiring.</p>
     */
    @Test
    void theHandWrittenPolymorphicRootEnumIsNeverOverwritten() throws Exception {
        String relative = "hr/hrg/hipster/entityexample/paymentMethod/entity/PaymentMethod_.java";
        Path committed = exampleSourceRoot().resolve(relative);
        Assertions.assertTrue(Files.exists(committed), "the hand-written root enum must be present");
        String before = Files.readString(committed);
        Assertions.assertTrue(before.contains("BankTransferPaymentMethod.class"),
                "and it must carry the permitted-subtype wiring the generator cannot derive (§ 9/4.9)");
        Assertions.assertTrue(before.contains("PaymentMethod_.type"),
                "including the discriminator field constant");

        // The fixture is a copy in which the pass ran in place, which is the real in-place situation.
        Assertions.assertEquals(before, fixture.readSource(relative),
                "in-place regeneration must leave the polymorphic root completely untouched");

        // The protection is the marker exclusion of § 4.5/G8 rule 0: `PaymentMethod` IS the package
        // marker, so it is never discovered as a view and no enum is generated for it in the first
        // place. `FieldBoilerplateGenerator.isPolymorphicRootEnum` is a second, structural line of
        // defence for the case where a root enum does reach the emitter.
        Assertions.assertFalse(Files.exists(fixture.sourceRoot().resolve(relative + ".bak")),
                "nothing is written beside the root enum");
    }

    @Test
    void personCreateFormIsEmittedBecauseOfTheViewSeed() throws Exception {
        Assertions.assertTrue(
                Files.exists(fixture.sourceRoot().resolve(
                        "hr/hrg/hipster/entityexample/person/entity/PersonCreateForm_.java")),
                "PersonCreateForm extends NOTHING, so only the @View half of the discovery predicate "
                        + "finds it (§ 4.5/G8 rule 0, task 3.2a)");
    }

    @Test
    void theFieldEnumsCarryTheR1MarkerAndAreAppendOnlyLedgers() throws Exception {
        Path summaryEnum = fixture.sourceRoot().resolve(
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
        Path packageDir = fixture.sourceRoot()
                .resolve("hr/hrg/hipster/entityexample/person/entity");

        Assertions.assertTrue(Files.exists(packageDir.resolve("PersonSummaryBuilder.java")),
                "PersonSummary is @View(gen = BUILDER_ALL), so both builders are emitted");
        Assertions.assertTrue(Files.exists(packageDir.resolve("PersonSummaryBuilderTracking.java")),
                "including the tracking one (§ 8.6/3.17)");

        String tracking = Files.readString(packageDir.resolve("PersonSummaryBuilderTracking.java"));
        Assertions.assertTrue(tracking.contains("EEnumSet<PersonSummary_>"),
                "and the tracking builder implements the final S5 contract (DR-4)");
    }
}
