package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Polymorphic view generation — plan.dsflash § 9/4.9 and the {@code discriminatorField} wiring of
 * § 4.5/G9 rule 5.
 *
 * <p>The family's shape is deliberately the example's: a <strong>hand-written root enum</strong>
 * that declares the discriminator field constant and the permitted subtypes, plus generated concrete
 * subclass enums whose {@code META} must carry the other half — the discriminator <em>value</em> each
 * subclass declares. § 9/4.9 states this explicitly: generation supplies each subclass's
 * {@code discriminatorValue} and {@code META}, while the base enum is the documented hand-off.</p>
 *
 * <p><strong>Why a subclass passes {@code null} for {@code discriminatorField}.</strong>
 * {@code DefaultViewMeta<V, F>}'s fifth parameter is typed {@code F} — the <em>view's own</em> field
 * enum — so a subclass could only name its root's constant by writing {@code PaymentMethod_.kind},
 * which is a {@code PaymentMethod_}, not a {@code CardOnly_}: a type error, and the reason an earlier
 * attempt to widen the parameter to {@code FieldDef} broke every hand-written call site. The field
 * is therefore owned solely by the root's hand-written enum (the one artifact that binds the family
 * together), and generation contributes only the value, read from the subclass's own
 * {@code default kind()} accessor. That is exactly the contract the committed example uses.</p>
 */
class PolymorphicGenerationTest {

    /**
     * The sealed root, mirroring the example's {@code PaymentMethod} <strong>exactly</strong>: it
     * extends {@code EntityBase<Long>} directly, so it is the marker, and it is the family's shared
     * field set.
     *
     * <p>An earlier version of this fixture inserted an extra {@code PaymentMethodEntity} layer
     * between the root and {@code EntityBase} — a shape the example does not have. It exposed a real
     * question (is a marker that reaches {@code EntityBase} through another interface a root?) but
     * changed the answer to "which interface owns the hand-written enum", so it tested the fixture
     * rather than the plan. The example's one-layer shape is what § 9/4.9 describes.</p>
     */
    private static final String ROOT = """
            package poly.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public sealed interface PaymentMethod extends EntityBase<Long>, Identifiable<Long>
                permits CardOnly, WalletOnly {
                String kind();
            }
            """;

    /** A concrete non-sealed view, with the discriminator value the generator must pick up. */
    private static final String CARD = """
            package poly.hr;
            import hr.hrg.hipster.entity.api.View;
            @View()
            public non-sealed interface CardOnly extends PaymentMethod {
                default String kind() { return "CARD"; }
                String maskedCardNumber();
            }
            """;

    private static final String WALLET = """
            package poly.hr;
            import hr.hrg.hipster.entity.api.View;
            @View()
            public non-sealed interface WalletOnly extends PaymentMethod {
                default String kind() { return "WALLET"; }
                String walletAddress();
            }
            """;

    /**
     * The hand-written root enum. Its {@code META} carries the discriminator FIELD constant and the
     * permitted subtypes — the one piece the generator must not derive. Its name follows the example's
     * convention: the marker's own name plus {@code _}.
     */
    private static final String ROOT_ENUM = """
            package poly.hr;
            import hr.hrg.hipster.entity.api.DefaultViewMeta;
            import hr.hrg.hipster.entity.api.FieldDef;
            import hr.hrg.hipster.entity.api.FieldNameMapper;
            import hr.hrg.hipster.entity.api.ViewMeta;
            import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
            import hr.hrg.hipster.entity.core.EntityReadArray;
            public enum PaymentMethod_ implements FieldDef {
                id(Long.class),
                kind(String.class);
                private final Class<?> javaType;
                PaymentMethod_(Class<?> javaType) { this.javaType = javaType; }
                @Override public Class<?> javaType() { return javaType; }
                public static PaymentMethod_ forName(String name) {
                    if (name == null) return null;
                    return switch (name) {
                        case "id" -> id;
                        case "kind" -> kind;
                        default -> null;
                    };
                }
                private static final FieldNameMapper<PaymentMethod_> NAME_MAPPER = PaymentMethod_::forName;
                public static final ViewMeta<PaymentMethod, PaymentMethod_> META =
                        new DefaultViewMeta<PaymentMethod, PaymentMethod_>(
                                PaymentMethod.class,
                                PaymentMethod_.class,
                                NAME_MAPPER,
                                values -> ArrayBackedViewProxyFactory.createRead(
                                        PaymentMethod.class,
                                        new EntityReadArray<PaymentMethod, PaymentMethod_>(
                                                PaymentMethod_.class, values),
                                        NAME_MAPPER),
                                PaymentMethod_.kind,
                                "",
                                new Class<?>[] { CardOnly.class, WalletOnly.class });
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot) {
    }

    /**
     * Generates <strong>in place</strong>, over a copy of the fixture tree.
     *
     * <p>In-place is the real configuration (§ 4.1/S2: generated source is committed into
     * {@code src/main/java}), and it is what makes the hand-written root enum visible to the
     * emitter's polymorphic-root guard. Generating into a separate directory would instead create a
     * fresh {@code PaymentMethod_}, because the guard recognises the root from the file it must not
     * overwrite.</p>
     */
    private Generated generate() throws Exception {
        Path tree = Files.createTempDirectory("poly-tree");
        // The source root must hold the package directory, or the generator writes its output to
        // `<root>/poly/hr/…` while the hand-written files sit at `<root>` — which splits the family
        // across two trees and makes the compile see each enum once from each.
        Path packageDir = tree.resolve("poly/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PaymentMethod.java"), ROOT);
        Files.writeString(packageDir.resolve("PaymentMethod_.java"), ROOT_ENUM);
        Files.writeString(packageDir.resolve("CardOnly.java"), CARD);
        Files.writeString(packageDir.resolve("WalletOnly.java"), WALLET);
        EntityMetadataGenerator.generate(tree, tree);
        return new Generated(tree, packageDir);
    }

    /** The `_` enums the generator wrote, as simple names. */
    private static java.util.Set<String> generatedEnums(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith("_.java"))
                    .map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void eachConcreteViewGetsItsDiscriminatorValueAndNoField() throws Exception {
        Generated generated = generate();
        Path packageDir = generated.outputRoot();

        String card = Files.readString(packageDir.resolve("CardOnly_.java"));
        Assertions.assertTrue(card.contains("\"CARD\""),
                "the VALUE is read from the view's own default kind() accessor: " + card);
        Assertions.assertFalse(card.contains("PaymentMethod_.kind"),
                "the FIELD constant must NOT be emitted: DefaultViewMeta's parameter is the view's own "
                        + "field enum, so a subclass cannot name its root's constant. META line was: "
                        + card.lines().filter(l -> l.contains("META = new")).findFirst().orElse("(none)"));
        Assertions.assertTrue(card.contains("null, \"CARD\""),
                "the field slot stays a literal null and the value sits in the next slot: " + card);

        String wallet = Files.readString(packageDir.resolve("WalletOnly_.java"));
        Assertions.assertTrue(wallet.contains("\"WALLET\""));
        Assertions.assertFalse(wallet.contains("PaymentMethod_.kind"));

        Assertions.assertEquals(java.util.Set.of("PaymentMethod_.java", "CardOnly_.java", "WalletOnly_.java"),
                generatedEnums(generated.sourceRoot()),
                "exactly three enums: the two concrete views and the preserved hand-written root");
    }

    /**
     * The root's own {@code META} is the family's single source for the field constant, and it keeps
     * the permitted-subtype list. A generated subclass carries the value and an empty array.
     */
    @Test
    void theRootKeepsTheFieldAndThePermittedSubtypes() throws Exception {
        Generated generated = generate();
        String root = Files.readString(generated.outputRoot().resolve("PaymentMethod_.java"));

        Assertions.assertTrue(root.contains("PaymentMethod_.kind"),
                "the hand-written root names the discriminator field constant");
        Assertions.assertTrue(root.contains("CardOnly.class, WalletOnly.class"),
                "and it is the only member that lists the permitted subtypes");
    }

    @Test
    void theRootEnumIsPreservedVerbatim() throws Exception {
        Generated generated = generate();

        Assertions.assertEquals(ROOT_ENUM,
                Files.readString(generated.outputRoot().resolve("PaymentMethod_.java")),
                "the root is the marker, so it is not a discovered view: the hand-written enum must "
                        + "survive in-place regeneration untouched (§ 9/4.9)");
    }

    @Test
    void anOrdinaryNonPolymorphicViewGetsNoDiscriminator() throws Exception {
        // The gate that matters: emitting a discriminator unconditionally produced a reference to
        // `<Marker>_.<field>` for every view, i.e. a class that does not exist.
        Path sourceRoot = Files.createTempDirectory("poly-plain-source");
        Path outputRoot = Files.createTempDirectory("poly-plain-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), """
                package poly2.hr;
                import hr.hrg.hipster.entity.api.EntityBase;
                public interface PersonEntity extends EntityBase<Long> {}
                """);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), """
                package poly2.hr;
                import hr.hrg.hipster.entity.api.View;
                @View
                public interface PersonSummary extends PersonEntity {
                    String firstName();
                }
                """);
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        String emitted = Files.readString(outputRoot.resolve("poly2/hr/PersonSummary_.java"));
        Assertions.assertFalse(emitted.contains("PersonEntity_"),
                "a view whose root enum declares no permitted subtypes is not polymorphic, so no "
                        + "discriminator may be emitted: " + emitted);

        // And the emitted source must compile, which it would not if the reference were invented.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "non-polymorphic",
                CompileHarness.javaSourcesUnder(outputRoot),
                CompileHarness.javaSourcesUnder(sourceRoot));
    }

    @Test
    void thePolymorphicFamilyCompiles() throws Exception {
        Generated generated = generate();

        // Generation was in place, so the single tree already holds the hand-written views, the
        // preserved root enum and the generated subclass enums.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "polymorphic",
                CompileHarness.javaSourcesUnder(generated.outputRoot()), List.of());
    }
}
