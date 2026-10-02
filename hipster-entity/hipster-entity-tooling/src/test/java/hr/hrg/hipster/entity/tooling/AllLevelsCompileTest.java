package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.api.GenLevel;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Phase 3 exit gate of {@code plan.dsflash.md} § 8: <strong>the emitted source compiles for
 * every generation level that has an emitter</strong>, with zero diagnostics.
 *
 * <p>This is the test that makes the level ladder real. It is parameterised over {@link GenLevel} so
 * a newly added level is covered by construction rather than by remembering to add a case, and
 * {@code DEFAULT} is skipped explicitly — it is not an emitter, it is the <em>resolution</em> rule
 * whose output is one of the concrete levels (asserted in {@code ViewRecordGeneratorTest} and
 * {@code ViewAnnotationReaderTest}). {@code WRITABLE} is not skipped: § 8.5/3.13 says a view that
 * stays at {@code WRITABLE} falls back to the array-backed updatable proxy, so it must compile on
 * the {@code META} shape.</p>
 */
class AllLevelsCompileTest {

    private static final String MARKER = """
            package all.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /**
     * The fixture view. It deliberately carries every shape a level can act on: two plain COLUMN
     * fields, a {@code DERIVED} field that must never become writable, a generic field that exercises
     * import resolution, and a nested record whose components match the field order — which is what
     * makes {@code DEFAULT} resolve to {@code RECORD} and what {@code BUILDER}/{@code BUILDER_ALL}
     * must reuse rather than duplicate.
     */
    private static String view(GenLevel level) {
        return """
                package all.hr;
                import hr.hrg.hipster.entity.api.FieldKind;
                import hr.hrg.hipster.entity.api.FieldSource;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                import java.util.List;
                import java.util.Map;
                @View(gen = GenLevel.%s)
                public interface PersonSummary extends PersonEntity {
                    String firstName();
                    String lastName();
                    @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
                    Integer age();
                    Map<String, List<Long>> metadata();
                    record Record(Long id, String firstName, String lastName, Integer age,
                                  Map<String, List<Long>> metadata) implements PersonSummary {}
                }
                """.formatted(level.name());
    }

    private record Generated(Path sourceRoot, Path outputRoot, List<Path> emitted) {
    }

    private Generated generate(GenLevel level) throws Exception {
        Path sourceRoot = Files.createTempDirectory("all-levels-source");
        Path outputRoot = Files.createTempDirectory("all-levels-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), view(level));

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        List<Path> emitted = CompileHarness.javaSourcesUnder(outputRoot);
        Assertions.assertFalse(emitted.isEmpty(), level + " must emit at least the field enum");
        return new Generated(sourceRoot, outputRoot, emitted);
    }

    @ParameterizedTest(name = "{0} emits source that compiles with zero diagnostics")
    @EnumSource(value = GenLevel.class, names = "DEFAULT", mode = EnumSource.Mode.EXCLUDE)
    void emittedSourceCompiles(GenLevel level) throws Exception {
        Generated generated = generate(level);

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), level.name(),
                generated.emitted(), CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    /**
     * The ladder is cumulative: a higher level must not lose what a lower one provides. Asserted as a
     * file-set superset relation over the ordered levels, which is what makes "one independently
     * shippable step at a time" checkable rather than aspirational.
     */
    @ParameterizedTest(name = "{0} emits the field enum and a META")
    @EnumSource(value = GenLevel.class, names = "DEFAULT", mode = EnumSource.Mode.EXCLUDE)
    void everyLevelEmitsTheFieldEnumAndMeta(GenLevel level) throws Exception {
        Generated generated = generate(level);

        Path fieldEnum = generated.outputRoot().resolve("all/hr/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(fieldEnum),
                level + " must emit the field enum; got " + generated.emitted());

        String source = Files.readString(fieldEnum);
        Assertions.assertTrue(source.contains("implements FieldDef"),
                level + ": the field enum is a FieldDef enum");
        Assertions.assertTrue(source.contains("META = new DefaultViewMeta"),
                level + ": and it publishes a META");
        Assertions.assertTrue(source.contains("entityFieldEnum:true"),
                level + ": with the R1 marker in the DEC-021 header");
    }

    private static final List<GenLevel> ORDERED = List.of(
            GenLevel.META, GenLevel.RECORD, GenLevel.WRITABLE,
            GenLevel.BUILDER, GenLevel.BUILDER_TRACKED, GenLevel.BUILDER_ALL);

    @ParameterizedTest(name = "{0} is a superset of every lower level")
    @EnumSource(value = GenLevel.class, names = "DEFAULT", mode = EnumSource.Mode.EXCLUDE)
    void theLadderIsCumulative(GenLevel level) throws Exception {
        int index = ORDERED.indexOf(level);
        Assertions.assertTrue(index >= 0, level + " must appear in the ladder order");

        List<String> current = fileNames(generate(level));
        for (GenLevel lower : ORDERED.subList(0, index)) {
            List<String> lowerFiles = fileNames(generate(lower));
            Assertions.assertTrue(current.containsAll(lowerFiles),
                    level + " must emit at least what " + lower + " emits; " + lower + " emitted "
                            + lowerFiles + " but " + level + " emitted " + current);
        }
    }

    private static List<String> fileNames(Generated generated) {
        return generated.emitted().stream().map(p -> p.getFileName().toString()).sorted().toList();
    }
}
