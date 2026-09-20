package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code BUILDER} level of plan.dsflash § 8.5/3.13, plus {@code BUILDER_ALL}'s
 * "both builders in one pass" rule (§ 8.6/3.17).
 *
 * <p>The load-bearing assertion is that {@code build()} returns the concrete materialization from
 * the level below — the nested {@code Record} when the view declares one, the emitted
 * {@code <View>Record} otherwise — rather than falling back to an array-backed proxy.</p>
 */
class ViewBuilderGeneratorTest {

    private static final String MARKER = """
            package bld.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW = """
            package bld.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                @FieldSource(kind = FieldKind.DERIVED, expression = "x")
                Integer age();
                record Record(Long id, String firstName, String lastName, Integer age) implements PersonSummary {}
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot) {
    }

    private Generated generate(String viewSource) throws Exception {
        Path sourceRoot = Files.createTempDirectory("builder-source");
        Path outputRoot = Files.createTempDirectory("builder-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), viewSource);
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        return new Generated(sourceRoot, outputRoot);
    }

    @Test
    void builderIsEmittedAndCompiles() throws Exception {
        Generated generated = generate(VIEW);

        Path builder = generated.outputRoot().resolve("bld/hr/PersonSummaryBuilder.java");
        Assertions.assertTrue(Files.exists(builder), "BUILDER emits a concrete builder");

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "BUILDER",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    @Test
    void buildReturnsTheViewsOwnNestedRecord() throws Exception {
        Generated generated = generate(VIEW);
        String source = Files.readString(generated.outputRoot().resolve("bld/hr/PersonSummaryBuilder.java"));

        Assertions.assertTrue(source.contains("-> new PersonSummary.Record(")
                        || source.contains("return new PersonSummary.Record("),
                "build() targets the view's own nested record, not a proxy: " + source);
        Assertions.assertFalse(source.contains("ArrayBackedViewProxyFactory"),
                "a builder must not fall back to an array-backed proxy");
        Assertions.assertFalse(Files.exists(generated.outputRoot().resolve("bld/hr/PersonSummaryRecord.java")),
                "and no second record is emitted when the view declares a matching one (§ 8.4/3.10)");
    }

    @Test
    void onlyWritableFieldsGetSetters() throws Exception {
        Generated generated = generate(VIEW);
        String source = Files.readString(generated.outputRoot().resolve("bld/hr/PersonSummaryBuilder.java"));

        Assertions.assertTrue(source.contains("public PersonSummaryBuilder firstName(String value)"),
                "a COLUMN field gets a fluent setter");
        Assertions.assertFalse(source.contains("public PersonSummaryBuilder age(Integer value)"),
                "a DERIVED field gets NO setter (S1)");
        Assertions.assertFalse(source.contains("case \"age\""),
                "and no name-based write arm for it");
        Assertions.assertTrue(source.contains("public Integer age()"),
                "but it stays readable, and keeps its ordinal in get(int)");
        Assertions.assertTrue(source.contains("case 3 -> age;"),
                "including in the positional getter");
    }

    @Test
    void nameWriteProbesWithMinusOne() throws Exception {
        Generated generated = generate(VIEW);
        String source = Files.readString(generated.outputRoot().resolve("bld/hr/PersonSummaryBuilder.java"));

        Assertions.assertTrue(source.contains("public int set(String field, Object value)"),
                "the name mutator exists");
        Assertions.assertTrue(source.contains("return -1;"),
                "and reports -1 for an unknown field (D5)");
    }

    @Test
    void builderAllEmitsBothBuildersInOnePass() throws Exception {
        Generated generated = generate(VIEW.replace("GenLevel.BUILDER", "GenLevel.BUILDER_ALL"));

        Assertions.assertTrue(Files.exists(generated.outputRoot().resolve("bld/hr/PersonSummaryBuilder.java")),
                "BUILDER_ALL emits the plain builder");
        Assertions.assertTrue(
                Files.exists(generated.outputRoot().resolve("bld/hr/PersonSummaryBuilderTracking.java")),
                "and the tracking builder in the same pass (§ 8.6/3.17)");
    }

    @Test
    void aViewAboveTheBuilderLevelStillGetsTheBuilder() throws Exception {
        // BUILDER_TRACKED sits above BUILDER on the ladder, so the plain builder is emitted too.
        Generated generated = generate(VIEW.replace("GenLevel.BUILDER", "GenLevel.BUILDER_TRACKED"));

        Assertions.assertTrue(
                Files.exists(generated.outputRoot().resolve("bld/hr/PersonSummaryBuilderTracking.java")),
                "BUILDER_TRACKED emits the tracking builder");
    }

    @Test
    void withoutANestedRecordBuildTargetsTheEmittedOne() throws Exception {
        String noNested = """
                package bld.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                @View(gen = GenLevel.BUILDER)
                public interface PersonSummary extends PersonEntity {
                    String firstName();
                    String lastName();
                }
                """;
        Generated generated = generate(noNested);

        Assertions.assertTrue(Files.exists(generated.outputRoot().resolve("bld/hr/PersonSummaryRecord.java")),
                "the record level below provides a concrete record");
        String source = Files.readString(generated.outputRoot().resolve("bld/hr/PersonSummaryBuilder.java"));
        Assertions.assertTrue(source.contains("return new PersonSummaryRecord("),
                "so build() targets it: " + source);
    }
}
