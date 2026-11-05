// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The polymorphic half of the generated patch applier — DEC-048's 2026-10-09 amendment, plan step 6.8.
 *
 * <p>A patch can address the family root rather than a concrete view. The generated {@code PaymentMethod}
 * dispatcher reads the discriminator through the root's accessor, matches it against each concrete view's
 * declared value, and calls that view's own applier — a direct call per arm, which is the property
 * {@code PaymentMethodController} hand-writes today and DEC-019 exists to keep.</p>
 */
@Tag("generator")
@Tag("compiler")
class GeneratedPatchApplierPolymorphicTest {

    @TempDir
    Path tempDir;

    private static final String ROOT = """
            package poly.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public sealed interface PaymentMethod extends EntityBase<Long>, Identifiable<Long>
                permits CardOnly, WalletOnly {
                String kind();
            }
            """;

    private static final String CARD = """
            package poly.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            import hr.hrg.hipster.entity.api.ViewWriter;
            @View(gen = GenLevel.BUILDER_ALL)
            public non-sealed interface CardOnly extends PaymentMethod {
                default String kind() { return "CARD"; }
                String maskedCardNumber();
                interface Write extends CardOnly, ViewWriter {
                    Write maskedCardNumber(String value);
                }
            }
            """;

    private static final String WALLET = """
            package poly.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            import hr.hrg.hipster.entity.api.ViewWriter;
            @View(gen = GenLevel.BUILDER_ALL)
            public non-sealed interface WalletOnly extends PaymentMethod {
                default String kind() { return "WALLET"; }
                String walletAddress();
                interface Write extends WalletOnly, ViewWriter {
                    Write walletAddress(String value);
                }
            }
            """;

    private static JsonNode document() {
        var root = JsonNodeFactory.instance.objectNode();
        root.set("maskedCardNumber", JsonNodeFactory.instance.objectNode()
                .put("op", "replace").put("current", "**** 4242"));
        return root;
    }

    @Test
    void aPatchAddressedToTheRootDispatchesToTheConcreteApplier() throws Exception {
        Path root = tempDir.resolve("poly");
        Path pkg = root.resolve("poly/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("PaymentMethod.java"), ROOT);
        Files.writeString(pkg.resolve("CardOnly.java"), CARD);
        Files.writeString(pkg.resolve("WalletOnly.java"), WALLET);

        EntityMetadataGenerator.setGeneratePatchAppliers(true);
        try {
            EntityMetadataGenerator.generate(root, root, root, new DivergenceReporter());
        } finally {
            EntityMetadataGenerator.setGeneratePatchAppliers(false);
        }

        String dispatcher = Files.readString(pkg.resolve("PaymentMethodPatchApplier.java"));
        Assertions.assertTrue(dispatcher.contains("case \"CARD\" ->"),
                "the dispatcher matches each concrete view's declared discriminator:\n" + dispatcher);
        Assertions.assertTrue(dispatcher.contains("CardOnlyPatchApplier.apply(document, concrete)"),
                "and calls the concrete view's own applier:\n" + dispatcher);
        Assertions.assertTrue(dispatcher.contains("view.kind()"),
                "the discriminator is read through the root's accessor:\n" + dispatcher);

        Path classes = CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "patch-applier-polymorphic",
                CompileHarness.javaSourcesUnder(root), List.of());

        try (var loader = new java.net.URLClassLoader(new java.net.URL[] { classes.toUri().toURL() },
                getClass().getClassLoader())) {
            Class<?> cardClass = loader.loadClass("poly.hr.CardOnly");
            Class<?> cardBuilderClass = loader.loadClass("poly.hr.CardOnlyBuilder");
            Class<?> rootClass = loader.loadClass("poly.hr.PaymentMethod");
            Class<?> dispatcherClass = loader.loadClass("poly.hr.PaymentMethodPatchApplier");

            Object card = cardBuilderClass.getConstructor().newInstance();
            cardBuilderClass.getMethod("kind", String.class).invoke(card, "CARD");
            cardBuilderClass.getMethod("maskedCardNumber", String.class).invoke(card, "**** 0000");

            Object result = dispatcherClass.getMethod("apply", JsonNode.class, rootClass)
                    .invoke(null, document(), card);
            Object patched = result.getClass().getMethod("value").invoke(result);
            Assertions.assertEquals("**** 4242", cardClass.getMethod("maskedCardNumber").invoke(patched),
                    "the concrete applier wrote the leaf through the concrete builder");
            @SuppressWarnings("unchecked")
            List<String> report = (List<String>) result.getClass().getMethod("report").invoke(result);
            Assertions.assertTrue(report.isEmpty(), "a known discriminator applies with no finding: " + report);
        }
    }
}
