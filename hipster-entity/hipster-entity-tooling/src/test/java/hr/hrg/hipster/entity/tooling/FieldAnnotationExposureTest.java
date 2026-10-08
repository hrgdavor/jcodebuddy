// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A field's annotations exposed as metadata on the generated view enum — DEC-047, the implementation half of plan step
 * 6.1.
 *
 * <p>Three properties, and the third matters as much as the first two:
 *
 * <ul>
 *   <li>a field whose accessor carries annotations gets an {@code annotations()} override naming them by
 *       <b>qualified</b> name with the <b>raw</b> argument text;</li>
 *   <li>the annotations are in <b>declaration order</b>, so the metadata reads like the accessor;</li>
 *   <li>a field with <b>no</b> annotations gets <b>no</b> override at all — which is what makes DEC-047 additive and
 *       is asserted by counting occurrences rather than by inspecting one constant.</li>
 * </ul>
 */
class FieldAnnotationExposureTest {

    @TempDir
    Path tempDir;

    private static final String ENTITY = """
            package annot.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** `name` carries two constraints written two ways; `age` carries none. */
    private static final String VIEW = """
            package annot.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.View;
            @View()
            public interface PersonSummary extends PersonEntity {
                @jakarta.validation.constraints.NotNull
                @jakarta.validation.constraints.Size(min = 1, max = 64)
                String name();
                @FieldSource(kind = FieldKind.COLUMN)
                Integer age();
            }
            """;

    private String generate() throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path outputRoot = tempDir.resolve("out");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(outputRoot);
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), ENTITY);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW);

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, divergences);

        Path enumFile = outputRoot.resolve("annot/hr/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(enumFile),
                "the view's field enum must be generated: " + Files.walk(outputRoot).toList() + " — " + divergences.entries());
        return Files.readString(enumFile);
    }

    @Test
    void anAnnotatedFieldCarriesItsAnnotationsAsMetadata() throws Exception {
        String generated = generate();

        Assertions.assertTrue(generated.contains("public java.util.List<hr.hrg.hipster.entity.api.FieldAnnotation> annotations()"),
                "the constant must expose annotations(): " + generated);
        Assertions.assertTrue(generated.contains(
                        "new hr.hrg.hipster.entity.api.FieldAnnotation(\"jakarta.validation.constraints.NotNull\", \"\")"),
                "a marker annotation carries an empty argument string: " + generated);
        Assertions.assertTrue(generated.contains(
                        "new hr.hrg.hipster.entity.api.FieldAnnotation(\"jakarta.validation.constraints.Size\", \"min = 1, max = 64\")"),
                "the arguments are the source text, and the type is qualified: " + generated);
    }

    @Test
    void theAnnotationsAreInDeclarationOrder() throws Exception {
        String generated = generate();

        int notNull = generated.indexOf("\"jakarta.validation.constraints.NotNull\"");
        int size = generated.indexOf("\"jakarta.validation.constraints.Size\"");
        Assertions.assertTrue(notNull > 0 && size > notNull,
                "the metadata must read like the accessor, top to bottom: " + generated);
    }

    @Test
    void aFieldWithoutAnnotationsGetsNoOverrideAtAll() throws Exception {
        String generated = generate();

        // One override for `name` and none for `age` — the property that keeps this change additive, and the reason
        // `FieldDef.annotations()` defaults to an empty list rather than being abstract.
        int overrides = generated.split("List<hr.hrg.hipster.entity.api.FieldAnnotation> annotations\\(\\)", -1).length - 1;
        Assertions.assertEquals(1, overrides,
                "exactly one constant carries annotations, so exactly one override is emitted: " + generated);
    }
}
