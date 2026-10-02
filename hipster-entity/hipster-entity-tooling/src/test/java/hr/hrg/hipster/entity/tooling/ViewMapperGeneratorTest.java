package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generated view-to-view mappers (plan.dsflash § 12.2/7.5–7.8).
 *
 * <p>The plan's rules are asserted one at a time, because "generate a mapper" is easy to fake and
 * hard to get right:</p>
 * <ul>
 *   <li><strong>7.5</strong> direct field access — the emitted body is {@code src.field()} calls in a
 *       record constructor, never a reflective copy;</li>
 *   <li><strong>7.6</strong> same type and provably-safe widening map; anything else is a DEC-022
 *       diagnostic and a literal {@code null}, <em>never</em> a lossy cast;</li>
 *   <li><strong>7.7</strong> a field present in one view and absent from the other is reported in both
 *       directions, because a silent omission in a mapper is a data-loss bug no compiler catches;</li>
 *   <li><strong>7.8</strong> every case still compiles — a mapper that refuses to compile is not a
 *       diagnostic, it is a broken build.</li>
 * </ul>
 */
class ViewMapperGeneratorTest {

    private static final String MARKER = """
            package map.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** The source: RECORD level with a nested record, so the target has a real constructor to call. */
    private static final String SUMMARY = """
            package map.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.View;
            import java.util.List;
            import java.util.Map;
            @View
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                Integer age();
                Map<String, List<Long>> metadata();
                record Record(Long id, String firstName, String lastName, Integer age,
                              Map<String, List<Long>> metadata) implements PersonSummary {}
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot, Path packageDir,
                             List<String> divergences) {
    }

    /**
     * Re-runs the pass over an <strong>existing</strong> tree, keeping the report.
     *
     * <p>{@code generate(...)} builds a fresh output root each time, so a re-emission assertion written
     * against it is vacuous: a clean output directory has no previous revision to reconcile against.
     * The validator's preservation test was written that way first and reported nothing.</p>
     */
    private static List<String> regenerateInPlace(Generated generated, List<String> mapperRequests)
            throws Exception {
        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.setMapperRequests(mapperRequests);
        try {
            EntityMetadataGenerator.generate(generated.sourceRoot(), generated.outputRoot(),
                    generated.outputRoot(), reporter);
        } finally {
            EntityMetadataGenerator.setMapperRequests(List.of());
        }
        return reporter.entries();
    }

    private Generated generate(String targetSource, List<String> mapperRequests) throws Exception {
        Path sourceRoot = Files.createTempDirectory("mapper-source");
        Path outputRoot = Files.createTempDirectory("mapper-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), SUMMARY);
        Files.writeString(sourceRoot.resolve("PersonDto.java"), targetSource);

        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.setMapperRequests(mapperRequests);
        try {
            EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);
        } finally {
            EntityMetadataGenerator.setMapperRequests(List.of());
        }
        return new Generated(sourceRoot, outputRoot, outputRoot.resolve("map/hr"), reporter.entries());
    }

    private static boolean hasKind(List<String> entries, String kind) {
        return entries.stream().anyMatch(entry -> entry.startsWith("kind=" + kind));
    }

    private static String entryOf(List<String> entries, String kind) {
        return entries.stream().filter(entry -> entry.startsWith("kind=" + kind))
                .findFirst().orElse("(no " + kind + " entry in " + entries + ")");
    }

    private static String read(Generated generated, String fileName) throws Exception {
        return Files.readString(generated.packageDir().resolve(fileName)).replace("\r\n", "\n");
    }

    private void assertCompiles(Generated generated, String label) throws Exception {
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), label,
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    /**
     * A user-added member inside a generated <strong>mapper</strong> survives regeneration
     * (follow-up plan § 1.1, completed in round 4).
     *
     * <p>The mapper is a whole-file emission driven by a requested pair rather than by a view, which is
     * why it and the validator were the two that still overwrote outright: each had its own entry point
     * and neither went through the reconciliation the other emitters use. A helper added to a generated
     * mapper was therefore deleted on the next pass, silently — F-25's loss again, in the last two files
     * it did not reach.</p>
     */
    @Test
    void aUserMemberInsideTheGeneratedMapperSurvives() throws Exception {
        String target = """
                package map.hr;
                import hr.hrg.hipster.entity.api.View;
                @View
                public interface PersonDto extends PersonEntity {
                    String firstName();
                    String lastName();
                }
                """;
        Generated generated = generate(target, List.of("PersonSummary:PersonDto"));
        Path mapper = generated.packageDir().resolve("PersonSummaryToPersonDtoMapper.java");
        Assertions.assertTrue(Files.exists(mapper), "the requested mapper is emitted: " + mapper);

        String method = """
                /**
                 * USER-METHOD in the mapper - never generated, always preserved.
                 */
                public static String describe() {
                    return "mapper";
                }""";
        String text = Files.readString(mapper);
        int lastBrace = text.lastIndexOf('}');
        Files.writeString(mapper, text.substring(0, lastBrace) + "\n" + method + "\n" + text.substring(lastBrace));

        regenerateInPlace(generated, List.of("PersonSummary:PersonDto"));
        regenerateInPlace(generated, List.of("PersonSummary:PersonDto"));
        String after = Files.readString(mapper);
        Assertions.assertTrue(after.contains("describe()"),
                "a user's helper inside the mapper must survive: " + after);
        Assertions.assertTrue(after.contains("USER-METHOD in the mapper"), after);

        regenerateInPlace(generated, List.of("PersonSummary:PersonDto"));
        Assertions.assertEquals(after, Files.readString(mapper),
                "and the carry-over must be a fixed point, not a one-pass copy");
    }

    @Test
    void aSameShapePairMapsEveryFieldByDirectAccess() throws Exception {
        Generated generated = generate("""
                package map.hr;
                import hr.hrg.hipster.entity.api.View;
                import java.util.List;
                import java.util.Map;
                @View
                public interface PersonDto extends PersonEntity {
                    String firstName();
                    String lastName();
                    Integer age();
                    Map<String, List<Long>> metadata();
                    record Record(Long id, String firstName, String lastName, Integer age,
                                  Map<String, List<Long>> metadata) implements PersonDto {}
                }
                """, List.of("PersonSummary:PersonDto"));

        String mapper = read(generated, "PersonSummaryToPersonDtoMapper.java");

        Assertions.assertTrue(mapper.contains("public static PersonDto toPersonDto(PersonSummary src)"),
                "the signature names both views, so the IDE can navigate either way: " + mapper);
        Assertions.assertTrue(
                mapper.contains("new PersonDto.Record(src.id(), src.firstName(), src.lastName(), "
                        + "src.age(), src.metadata())"),
                "every field is a direct accessor call, in the target's component order: " + mapper);
        Assertions.assertFalse(
                mapper.contains("java.lang.reflect") || mapper.contains("Method.invoke")
                        || mapper.contains("getDeclaredMethod") || mapper.contains("Class.forName"),
                "no reflective dispatch is involved (AGENTS.md section 1): " + mapper);
        Assertions.assertEquals(List.of("id", "firstName", "lastName", "age", "metadata"),
                mappedFields(mapper), "and nothing was left unmapped");

        assertCompiles(generated, "same-shape mapper");
    }

    @Test
    void aSafeWideningIsEmittedWithANullGuard() throws Exception {
        Generated generated = generate("""
                package map.hr;
                import hr.hrg.hipster.entity.api.View;
                import java.util.List;
                import java.util.Map;
                @View
                public interface PersonDto extends PersonEntity {
                    String firstName();
                    String lastName();
                    Long age();
                    Map<String, List<Long>> metadata();
                    record Record(Long id, String firstName, String lastName, Long age,
                                  Map<String, List<Long>> metadata) implements PersonDto {}
                }
                """, List.of("PersonSummary:PersonDto"));

        String mapper = read(generated, "PersonSummaryToPersonDtoMapper.java");

        Assertions.assertTrue(mapper.contains("src.age() == null ? null : src.age().longValue()"),
                "Integer to Long is a widening, and the null guard keeps S4's absent value absent "
                        + "instead of turning it into a boxed zero: " + mapper);
        Assertions.assertFalse(hasKind(generated.divergences(), "mapper_type_incompatible"),
                "a widening is safe, so it is not reported: " + generated.divergences());
        assertCompiles(generated, "widening mapper");
    }

    @Test
    void aNarrowingIsRefusedRatherThanCast() throws Exception {
        // Long -> Integer would compile as a cast and silently truncate on overflow, which is exactly
        // what § 12.2/7.6 forbids.
        Generated generated = generate("""
                package map.hr;
                import hr.hrg.hipster.entity.api.View;
                import java.util.List;
                import java.util.Map;
                @View
                public interface PersonDto extends PersonEntity {
                    String firstName();
                    String lastName();
                    Long age();
                    Map<String, List<Long>> metadata();
                    record Record(Long id, String firstName, String lastName, Long age,
                                  Map<String, List<Long>> metadata) implements PersonDto {}
                }
                """, List.of("PersonDto:PersonSummary"));

        String mapper = read(generated, "PersonDtoToPersonSummaryMapper.java");

        Assertions.assertTrue(hasKind(generated.divergences(), "mapper_type_incompatible"),
                "the narrowing is reported: " + generated.divergences());
        Assertions.assertTrue(entryOf(generated.divergences(), "mapper_type_incompatible").contains("age"),
                entryOf(generated.divergences(), "mapper_type_incompatible"));
        Assertions.assertFalse(mapper.contains("(Integer)"),
                "and no cast is emitted for it: " + mapper);
        Assertions.assertTrue(mapper.contains("null"),
                "the position is a literal null instead: " + mapper);
        assertCompiles(generated, "narrowing mapper");
    }

    @Test
    void fieldsMissingOnEitherSideAreReportedInBothDirections() throws Exception {
        // The target has `email` (absent from the source) and the source has `metadata` (absent from
        // the target). Neither is an error; both are data-loss candidates.
        Generated generated = generate("""
                package map.hr;
                import hr.hrg.hipster.entity.api.View;
                @View
                public interface PersonDto extends PersonEntity {
                    String firstName();
                    String lastName();
                    Integer age();
                    String email();
                    record Record(Long id, String firstName, String lastName, Integer age,
                                  String email) implements PersonDto {}
                }
                """, List.of("PersonSummary:PersonDto"));

        Assertions.assertTrue(hasKind(generated.divergences(), "mapper_field_missing_in_source"),
                "the target's email has no source: " + generated.divergences());
        Assertions.assertTrue(entryOf(generated.divergences(), "mapper_field_missing_in_source").contains("email"));
        Assertions.assertTrue(hasKind(generated.divergences(), "mapper_field_missing_in_target"),
                "and the source's metadata is discarded: " + generated.divergences());
        Assertions.assertTrue(entryOf(generated.divergences(), "mapper_field_missing_in_target").contains("metadata"));

        String mapper = read(generated, "PersonSummaryToPersonDtoMapper.java");
        Assertions.assertTrue(
                mapper.contains("new PersonDto.Record(src.id(), src.firstName(), src.lastName(), "
                        + "src.age(), null)"),
                "the unmappable position carries a literal null: " + mapper);
        assertCompiles(generated, "missing-field mapper");
    }

    @Test
    void aTargetWithNoRecordIsBuiltThroughItsOwnMetadata() throws Exception {
        // META level: no record exists, so the mapper goes through the view's own positional contract
        // rather than assuming a materialization the target does not have.
        Generated generated = generate("""
                package map.hr;
                import hr.hrg.hipster.entity.api.View;
                import java.util.List;
                import java.util.Map;
                @View
                public interface PersonDto extends PersonEntity {
                    String firstName();
                    String lastName();
                    Integer age();
                    Map<String, List<Long>> metadata();
                }
                """, List.of("PersonSummary:PersonDto"));

        String mapper = read(generated, "PersonSummaryToPersonDtoMapper.java");

        Assertions.assertTrue(mapper.contains("import hr.hrg.hipster.entity.api.ViewMeta;"),
                "the META route needs ViewMeta in scope: " + mapper);
        Assertions.assertTrue(mapper.contains("PersonDto_.META.create(new Object[] {"),
                "and builds positionally through the view's own metadata: " + mapper);
        assertCompiles(generated, "meta-route mapper");
    }

    @Test
    void aRequestNamingAnUnknownViewIsReportedRatherThanHalfGenerated() throws Exception {
        Generated generated = generate("""
                package map.hr;
                import hr.hrg.hipster.entity.api.View;
                @View
                public interface PersonDto extends PersonEntity {
                    String firstName();
                }
                """, List.of("PersonSummary:NoSuchView", "not-a-request"));

        Assertions.assertTrue(hasKind(generated.divergences(), "mapper_view_not_found"),
                generated.divergences().toString());
        Assertions.assertTrue(entryOf(generated.divergences(), "mapper_view_not_found").contains("NoSuchView"));
        Assertions.assertTrue(hasKind(generated.divergences(), "mapper_request_malformed"),
                "a malformed request is named too, not silently dropped: " + generated.divergences());
        Assertions.assertFalse(Files.exists(
                        generated.packageDir().resolve("PersonSummaryToNoSuchViewMapper.java")),
                "no mapper is emitted for an unresolvable pair");
    }

    @Test
    void theWideningTableIsWideningOnly() {
        Assertions.assertEquals("$", ViewMapperGenerator.conversion("Long", "Long"));
        Assertions.assertEquals("$", ViewMapperGenerator.conversion("java.lang.Long", "Long"));
        Assertions.assertEquals("$", ViewMapperGenerator.conversion("String", "Object"));
        Assertions.assertEquals("$ == null ? null : $.longValue()",
                ViewMapperGenerator.conversion("Integer", "Long"));
        Assertions.assertEquals("$ == null ? null : $.doubleValue()",
                ViewMapperGenerator.conversion("Float", "Double"));
        Assertions.assertEquals("$ == null ? null : $.intValue()",
                ViewMapperGenerator.conversion("Byte", "Integer"));

        Assertions.assertNull(ViewMapperGenerator.conversion("Long", "Integer"),
                "a narrowing truncates on overflow, so it is refused");
        Assertions.assertNull(ViewMapperGenerator.conversion("Integer", "int"),
                "a nullable source into a primitive target would be an NPE the compiler cannot flag");
        Assertions.assertNull(ViewMapperGenerator.conversion("Integer", "String"),
                "an unrelated type is refused, never guessed at");
        Assertions.assertNull(ViewMapperGenerator.conversion("Double", "Float"));
    }

    @Test
    void theClassNameAndMethodNameComeFromTheViewsUnlessOverridden() {
        Assertions.assertEquals("PersonSummaryToPersonDtoMapper",
                ViewMapperGenerator.defaultClassName("PersonSummary", "PersonDto"));
        Assertions.assertEquals("toPersonDto", ViewMapperGenerator.defaultMethodName("PersonDto"));
    }

    /** The accessor names appearing as {@code src.<name>()} in the emitted body, in order. */
    private static List<String> mappedFields(String mapper) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("src\\.([A-Za-z_][A-Za-z0-9_]*)\\(\\)")
                .matcher(mapper.substring(mapper.indexOf("return new ")));
        List<String> names = new java.util.ArrayList<>();
        while (matcher.find()) {
            if (!names.contains(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }
}
