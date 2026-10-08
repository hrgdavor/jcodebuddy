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
}
