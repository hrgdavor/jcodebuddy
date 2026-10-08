// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * DEC-006's second acceptance criterion, which is the one this repository did not already meet — plan step 6.4.
 *
 * <p>Its wording is *"missing required converters MUST fail with actionable diagnostics"*, and the measurement behind the
 * decision's acceptance found that a missing converter was reported as a **divergence** and a divergence does not fail a
 * pass: `scripts/gen.js` prints them and continues, which is why the example's committed run reports several and still
 * succeeds. The decision's amendment classifies it as a **contract**-class finding, so it fails the same way the entity
 * rules do — through `STRICT` and the policy machinery step 6.3 built, not through a second mechanism.</p>
 */
class MapperConverterContractTest {

    @TempDir
    Path tempDir;

    private static final String MARKER = """
            package conv.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface Thing extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** `amount` is an `Integer` here and a `String` in the target: a pair no implicit conversion reaches. */
    private static final String SOURCE = """
            package conv.hr;
            import hr.hrg.hipster.entity.api.View;
            @View()
            public interface SourceView extends Thing {
                Integer amount();
            }
            """;

    private static final String TARGET = """
            package conv.hr;
            import hr.hrg.hipster.entity.api.View;
            @View()
            public interface TargetView extends Thing {
                String amount();
            }
            """;

    private Path tree(String name) throws Exception {
        Path root = tempDir.resolve(name);
        Path pkg = root.resolve("conv/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        Files.writeString(pkg.resolve("SourceView.java"), SOURCE);
        Files.writeString(pkg.resolve("TargetView.java"), TARGET);
        return root;
    }

    private static void map() {
        EntityMetadataGenerator.setMapperRequests(List.of("conv.hr.SourceView:conv.hr.TargetView"));
    }

    private static void reset() {
        EntityMetadataGenerator.setMapperRequests(List.of());
        EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.OFF);
    }

    @Test
    void theDivergenceIsReportedAndAPassContinuesInReportMode() throws Exception {
        Path root = tree("report");
        Path out = root.resolve("out");
        Files.createDirectories(out);
        DivergenceReporter divergences = new DivergenceReporter();

        map();
        EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.REPORT);
        try {
            Assertions.assertDoesNotThrow(() -> EntityMetadataGenerator.generate(root, out, out, divergences),
                    "REPORT mode reports and continues, which is why this criterion was not met before");
        } finally {
            reset();
        }
        Assertions.assertFalse(divergences.ofKind("mapper_type_incompatible").isEmpty(),
                "and the pair IS discovered, which is DEC-006's first criterion — already met before this step: "
                        + divergences.entries());
    }

    @Test
    void aMissingConverterFailsAStrictPass() throws Exception {
        Path root = tree("strict");
        Path out = root.resolve("out");
        Files.createDirectories(out);

        map();
        EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.STRICT);
        try {
            EntityMetadataGenerator.ValidationFailedException failure = Assertions.assertThrows(
                    EntityMetadataGenerator.ValidationFailedException.class,
                    () -> EntityMetadataGenerator.generate(root, out, out),
                    "STRICT must fail on a missing converter: DEC-006's second criterion");
            Assertions.assertTrue(failure.issues().stream()
                            .anyMatch(issue -> issue.message.contains("mapper_type_incompatible")),
                    "and the failure names the finding, so it is actionable — the criterion's own wording: "
                            + failure.issues());
        } finally {
            reset();
        }
    }

    @Test
    void theResolvedPairsAreRecordedAsDataWithBothOutcomes() throws Exception {
        // An incompatible pair: Integer -> String, the fixture above.
        Path incompatible = tree("typed-bad");
        Path outBad = incompatible.resolve("out");
        Files.createDirectories(outBad);
        map();
        try {
            EntityMetadataGenerator.generate(incompatible, outBad, outBad, new DivergenceReporter());
            java.util.List<hr.hrg.hipster.entity.tooling.meta.TypeDivergence> recorded =
                    EntityMetadataGenerator.typeDivergences();
            var amount = recorded.stream().filter(pair -> pair.location().endsWith(".amount")).findFirst()
                    .orElseThrow(() -> new AssertionError("no pair for `amount`: " + recorded));
            Assertions.assertEquals("Integer -> String", amount.pair(),
                    "named as the pair, not only as a message: " + recorded);
            Assertions.assertTrue(amount.converterRequired(),
                    "and flagged as needing a converter, which is what the manifest is rendered from: " + recorded);
            Assertions.assertTrue(amount.render().contains("converter required"),
                    "with a rendering a manifest can carry: " + amount.render());
            // The inherited `id` is a pair too, and an identical type needs nothing: coverage is both answers.
            Assertions.assertTrue(recorded.stream().anyMatch(pair -> !pair.converterRequired()),
                    "a pair that needs no converter is recorded as well: " + recorded);
        } finally {
            reset();
        }

        // A pair that needs no converter: a primitive that widens. `[converted as-is]` is coverage too, which is why
        // both outcomes are recorded rather than only the failures.
        Path widening = tempDir.resolve("typed-good");
        Path pkg = widening.resolve("conv/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        Files.writeString(pkg.resolve("SourceView.java"), SOURCE.replace("Integer amount()", "int amount()"));
        Files.writeString(pkg.resolve("TargetView.java"), TARGET.replace("String amount()", "long amount()"));
        Path outGood = widening.resolve("out");
        Files.createDirectories(outGood);
        map();
        try {
            EntityMetadataGenerator.generate(widening, outGood, outGood, new DivergenceReporter());
            java.util.List<hr.hrg.hipster.entity.tooling.meta.TypeDivergence> recorded =
                    EntityMetadataGenerator.typeDivergences();
            var amount = recorded.stream().filter(pair -> pair.location().endsWith(".amount")).findFirst()
                    .orElseThrow(() -> new AssertionError("no pair for `amount`: " + recorded));
            Assertions.assertEquals("int -> long", amount.pair(),
                    "a widening primitive is a pair too: " + recorded);
            Assertions.assertFalse(amount.converterRequired(),
                    "and it needs no converter — DEC-006's third criterion, recorded as data: " + recorded);
        } finally {
            reset();
        }
    }
}
