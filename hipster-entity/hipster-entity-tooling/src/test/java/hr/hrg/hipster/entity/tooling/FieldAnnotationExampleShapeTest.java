// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which of the example's shape differences makes the DEC-047 override disappear — plan step 6.1's last open question.
 *
 * <p>Every code path is already cleared: the source path, the emitter, the second pass over an existing enum, the
 * CLI's own {@code main}, and the agent's cache all emit (or correctly reuse) the override. What is left is the
 * <b>example's own shape</b>, and it differs from every passing fixture in exactly three ways that this class turns
 * into one test each — plus a fourth that combines them — so a failure names its own cause instead of leaving another
 * round of reading:</p>
 *
 * <ul>
 *   <li>{@code @View(gen = GenLevel.BUILDER_ALL)} rather than the default level;</li>
 *   <li>the view <b>extends another view</b> rather than a plain marker;</li>
 *   <li>the view carries a <b>nested record</b> and a <b>nested interface</b> (the example's {@code Record}/{@code Write}).</li>
 * </ul>
 */
class FieldAnnotationExampleShapeTest {

    @TempDir
    Path tempDir;

    private static final String MARKER = """
            package shape.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW_IMPORTS = """
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            """;

    /** The constrained accessor, the thing whose metadata must survive. */
    private static final String CONSTRAINED = """
                @jakarta.validation.constraints.NotNull
                String metadata();
            """;

    /** Generates one tree and returns the view enum's text. The fixtures are parsed, not compiled. */
    private String enumFor(String viewSource, String... extraFiles) throws Exception {
        Path root = tempDir.resolve("tree" + Math.abs(viewSource.hashCode()));
        Path pkg = root.resolve("shape/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("PersonEntity.java"), MARKER);
        Files.writeString(pkg.resolve("PersonSummary.java"), viewSource);
        for (String extra : extraFiles) {
            String name = extra.substring(extra.indexOf("interface ") + 10, extra.indexOf(" {", extra.indexOf("interface ")));
            Files.writeString(pkg.resolve(name + ".java"), extra);
        }
        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.generate(root, root, root, divergences);
        Path enumFile = pkg.resolve("PersonSummary_.java");
        Assertions.assertTrue(Files.exists(enumFile),
                "the enum must be generated for: " + viewSource + "\ndivergences: " + divergences.entries());
        return Files.readString(enumFile);
    }

    private static void assertCarriesTheOverride(String emitted, String variant) {
        Assertions.assertTrue(emitted.contains("FieldAnnotation(\"jakarta.validation.constraints.NotNull\", \"\")"),
                "[" + variant + "] the constrained accessor's metadata must reach the enum:\n" + emitted);
    }

    /** The control: the fixture every passing test uses — default level, plain marker, no nested types. */
    @Test
    void controlDefaultLevelPlainMarker() throws Exception {
        String view = "package shape.hr;\n" + VIEW_IMPORTS + "@View()\n"
                + "public interface PersonSummary extends PersonEntity {\n" + CONSTRAINED + "}\n";
        assertCarriesTheOverride(enumFor(view), "control");
    }

    @Test
    void variantGenLevelBuilderAll() throws Exception {
        String view = "package shape.hr;\n" + VIEW_IMPORTS + "@View(gen = GenLevel.BUILDER_ALL)\n"
                + "public interface PersonSummary extends PersonEntity {\n" + CONSTRAINED + "}\n";
        assertCarriesTheOverride(enumFor(view), "gen=BUILDER_ALL");
    }

    @Test
    void variantExtendsAnotherView() throws Exception {
        String parent = "package shape.hr;\n" + VIEW_IMPORTS + "@View()\n"
                + "public interface PersonBase extends PersonEntity {\n"
                + "    @FieldSource(kind = FieldKind.COLUMN)\n    String name();\n}\n";
        String view = "package shape.hr;\n" + VIEW_IMPORTS + "@View()\n"
                + "public interface PersonSummary extends PersonBase {\n" + CONSTRAINED + "}\n";
        assertCarriesTheOverride(enumFor(view, parent), "extends another view");
    }

    @Test
    void variantNestedRecordAndInterface() throws Exception {
        String view = "package shape.hr;\n" + VIEW_IMPORTS + "@View()\n"
                + "public interface PersonSummary extends PersonEntity {\n" + CONSTRAINED
                + "    record Record(String metadata) implements PersonSummary {}\n"
                + "    interface Write extends PersonSummary {}\n}\n";
        assertCarriesTheOverride(enumFor(view), "nested record + interface");
    }

    /** All three together: the example's shape, which is the one that failed in the tree. */
    @Test
    void variantTheExamplesWholeShape() throws Exception {
        String parent = "package shape.hr;\n" + VIEW_IMPORTS + "@View()\n"
                + "public interface PersonBase extends PersonEntity {\n"
                + "    @FieldSource(kind = FieldKind.COLUMN)\n    String name();\n}\n";
        String view = "package shape.hr;\n" + VIEW_IMPORTS + "@View(gen = GenLevel.BUILDER_ALL)\n"
                + "public interface PersonSummary extends PersonBase {\n" + CONSTRAINED
                + "    record Record(String metadata) implements PersonSummary {}\n"
                + "    interface Write extends PersonSummary {}\n}\n";
        assertCarriesTheOverride(enumFor(view, parent), "the example's whole shape");
    }
}
