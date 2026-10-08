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
 * <p>Every entry point is covered here, because the step's long hunt was about <em>which</em> one ran: the API
 * ({@code generate}), the API meeting its own previous output (a second pass), and the CLI's own {@code main} with the
 * flags the repository's launcher uses. {@link FieldAnnotationExampleShapeTest} covers the shapes the example has.
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

    /**
     * The example's exact scenario, which is what step 6.1's example half actually needs: an enum that <b>already
     * exists</b> without the override, and a pass that runs after a constraint was added to the accessor.
     *
     * <p>A committed generated file is never a blank slate — the pass meets its own previous output, and DEC-020's
     * cooperative reconciliation decides what it may change. If the override is not added here, annotating the example
     * cannot work however fresh the inputs are, which is why this test exists rather than another round of reading.</p>
     */
    @Test
    void aSecondPassAddsTheOverrideToAnEnumThatAlreadyExists() throws Exception {
        Path root = tempDir.resolve("tree");
        Path pkg = root.resolve("annot/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("PersonEntity.java"), ENTITY);
        // Pass 1: no constraint anywhere, so the enum is written the way the committed example is.
        Files.writeString(pkg.resolve("PersonSummary.java"), VIEW.replaceAll("\\s*@jakarta\\.validation[^\\n]*\\n", "\n"));
        DivergenceReporter first = new DivergenceReporter();
        EntityMetadataGenerator.generate(root, root, root, first);

        Path enumFile = root.resolve("annot/hr/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(enumFile), "the first pass must write the enum: " + first.entries());
        String before = Files.readString(enumFile);
        Assertions.assertFalse(before.contains("annotations()"), "the fixture must start without the override");

        // Pass 2: the constraint is added to the accessor, exactly as it would be in the example's source.
        Files.writeString(pkg.resolve("PersonSummary.java"), VIEW);
        DivergenceReporter second = new DivergenceReporter();
        EntityMetadataGenerator.generate(root, root, root, second);

        String after = Files.readString(enumFile);
        Assertions.assertTrue(after.contains("FieldAnnotation(\"jakarta.validation.constraints.NotNull\", \"\")"),
                "a pass that meets its own enum must ADD the override the source now determines (DEC-020 reconciliation; "
                        + "the source is the authority for a view-declared accessor, maintainer, 2026-10-08). "
                        + "divergences: " + second.entries() + "\n" + after);
    }

    /**
     * The CLI's own entry point, with the flags the repository's launcher uses — the one difference left between every
     * passing test and the example.
     *
     * <p>Reading the code showed `main` calls the same `generate` overload the tests do, so the flight recorder for
     * this step's mystery is not the pipeline but the <b>flags</b>: `--java-out`, `--packages` and `--validate`, the
     * last of which runs the entity rules through {@code runValidation} before any write. A test is the right
     * instrument because it already has a correct classpath — the hand-run attempt died on a cache-restored module
     * with no {@code target/classes} and on the Java 8 on {@code PATH} (both now in {@code doc/AGENTS.md}).</p>
     */
    @Test
    void theCliEntryPointAddsTheOverrideToo() throws Exception {
        Path root = tempDir.resolve("cli");
        Path src = root.resolve("src");
        Path pkg = src.resolve("annot/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("PersonEntity.java"), ENTITY);
        Files.writeString(pkg.resolve("PersonSummary.java"), VIEW);
        Path meta = root.resolve("meta");
        Files.createDirectories(meta);

        EntityMetadataGenerator.main(new String[] {
                src.toString(), meta.toString(),
                "--java-out", src.toString(),
                "--packages", "annot.hr",
                "--validate",
        });

        Path enumFile = pkg.resolve("PersonSummary_.java");
        Assertions.assertTrue(Files.exists(enumFile), "the CLI must write the field enum: " + Files.walk(root).toList());
        String emitted = Files.readString(enumFile);
        Assertions.assertTrue(emitted.contains("FieldAnnotation(\"jakarta.validation.constraints.NotNull\", \"\")"),
                "the CLI's own entry point must emit the override, since the example is generated through it:\n" + emitted);
    }
}
