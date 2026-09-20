package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The compile-the-output gate of {@code plan.dsflash.md} § 0.5, factored out of
 * {@code EntityMetadataGeneratorTest} into a named test.
 *
 * <p>This is the test that closes the documentation/code gap permanently: it writes a fixture
 * source root, runs {@link EntityMetadataGenerator#generate}, asserts the emitted file set, and
 * then compiles the emitted sources against the real {@code api} + {@code core} classpath with
 * <strong>zero diagnostics</strong>. Every generator level in Phase 3 grows this matrix; the
 * {@code META} row below is the level that exists today.</p>
 */
class GeneratedSourceCompilesTest {

    private static final String MARKER = """
            package example.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface PersonEntity extends EntityBase<Long> {}
            """;

    /** A view with a generic field, a derived field and a joined field, plus one default method. */
    private static final String SUMMARY = """
            package example.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.View;
            import java.util.List;
            import java.util.Map;
            @View
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
                Integer age();
                @FieldSource(kind = FieldKind.JOINED, relation = "department.name")
                String departmentName();
                Map<String, List<Long>> metadata();
                default String fullName() { return firstName() + " " + lastName(); }
            }
            """;

    private Path writeFixture() throws Exception {
        Path sourceRoot = Files.createTempDirectory("gsc-source");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), SUMMARY);
        return sourceRoot;
    }

    @Test
    void emittedMetaLevelCompilesWithZeroDiagnostics() throws Exception {
        Path sourceRoot = writeFixture();
        Path outputRoot = Files.createTempDirectory("gsc-output");

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        List<Path> generated = CompileHarness.javaSourcesUnder(outputRoot);
        Assertions.assertFalse(generated.isEmpty(),
                "the generator must emit at least the metadata enum for the fixture view");

        Path enumFile = outputRoot.resolve("example/hr/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(enumFile), "the META level emits <View>_.java; got " + generated);

        // The META output is generated source that implements the author's view, so it only
        // compiles when the view and its marker are on the compile path. The @TempDir source root
        // holds exactly that hand-written input; the generated tree holds the output.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "META",
                generated, CompileHarness.javaSourcesUnder(sourceRoot));

        String emitted = Files.readString(enumFile);
        Assertions.assertTrue(emitted.contains("enum PersonSummary_"), "the emitted file is the field enum");

        // The default method must NOT become a field constant (plan.dsflash § 8.2/3.5).
        Assertions.assertFalse(emitted.contains("fullName"),
                "a default method must not leak into the field enum: " + emitted);

        // META shape (§ 8.3/3.7): implements FieldDef, javaType(), forName, NAME_MAPPER.
        Assertions.assertTrue(emitted.contains("implements FieldDef"), "the enum implements FieldDef");
        Assertions.assertTrue(emitted.contains("public Type javaType()") || emitted.contains("Type javaType()"),
                "the enum declares javaType() returning a Type, not a Class: " + emitted);
        Assertions.assertTrue(emitted.contains("NAME_MAPPER"), "the enum publishes NAME_MAPPER");
        Assertions.assertTrue(emitted.contains("public static PersonSummary_ forName(String name)"),
                "the enum publishes a switch-based forName");

        // The DEC-021 two-line header with the R1 marker (§ 8.3/3.8).
        Assertions.assertTrue(emitted.startsWith("// {@link example.hr.PersonSummary}"),
                "the file starts with the DEC-021 {@link} header line: " + emitted.substring(0, Math.min(200, emitted.length())));
        Assertions.assertTrue(emitted.contains("// {enabled:true, entityFieldEnum:true, blockMarker: \"implicit\"}"),
                "the header carries the entityFieldEnum:true marker: " + emitted.substring(0, Math.min(300, emitted.length())));

        // @FieldSource-derived overrides (§ 8.3/3.9): only for annotated accessors.
        Assertions.assertTrue(emitted.contains("fieldKind()") && emitted.contains("FieldKind.DERIVED"),
                "the DERIVED accessor's kind is emitted: " + emitted);
        Assertions.assertTrue(emitted.contains("FieldKind.JOINED") && emitted.contains("\"department.name\""),
                "the JOINED accessor's relation is emitted: " + emitted);
        Assertions.assertTrue(emitted.contains("\"YEAR(NOW()) - YEAR(birthDate)\""),
                "the DERIVED accessor's expression is emitted: " + emitted);

        // And the R1 checker must accept the emitted enum as a guardable ledger.
        Assertions.assertTrue(EnumConstantOrderChecker.readLedgers(emitted)
                        .get("example.hr.PersonSummary_").guarded(),
                "the emitted enum is a guardable R1 ledger because it carries the marker");
    }

    @Test
    void emissionIsDeterministicAcrossTwoPasses() throws Exception {
        Path sourceRoot = writeFixture();
        Path first = Files.createTempDirectory("gsc-first");
        Path second = Files.createTempDirectory("gsc-second");

        EntityMetadataGenerator.generate(sourceRoot, first);
        EntityMetadataGenerator.generate(sourceRoot, second);

        List<Path> firstFiles = CompileHarness.javaSourcesUnder(first).stream()
                .map(first::relativize).sorted().collect(Collectors.toList());
        List<Path> secondFiles = CompileHarness.javaSourcesUnder(second).stream()
                .map(second::relativize).sorted().collect(Collectors.toList());
        Assertions.assertEquals(firstFiles, secondFiles, "two passes must emit the same file set");

        for (Path relative : firstFiles) {
            Assertions.assertEquals(
                    Files.readString(first.resolve(relative)),
                    Files.readString(second.resolve(relative)),
                    "generation must be byte-identical across passes for " + relative);
        }
    }
}
