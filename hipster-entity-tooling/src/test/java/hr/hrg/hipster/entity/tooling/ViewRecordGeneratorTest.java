package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code RECORD} level of plan.dsflash § 8.4/3.10–3.11.
 *
 * <p>Three shape rules are asserted, because each of them is a way the level can be wrong while
 * still compiling:</p>
 * <ol>
 *   <li>a top-level {@code <View>Record} is emitted whose component order <strong>is</strong> the
 *       field enum order, and {@code META.create()} targets it positionally;</li>
 *   <li>a view that already declares a matching nested {@code record} gets <strong>no second
 *       record</strong> — {@code create()} targets the nested one instead (§ 8.4/3.10);</li>
 *   <li>a nested record whose components <em>disagree</em> with the field order is a divergence, not
 *       a silent mis-construction (§ 4.5/G1).</li>
 * </ol>
 */
class ViewRecordGeneratorTest {

    private static final String MARKER = """
            package rec.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** A RECORD-level view with a generic field and a DERIVED field. */
    private static final String VIEW = """
            package rec.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            import java.util.List;
            import java.util.Map;
            @View(gen = GenLevel.RECORD)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                @FieldSource(kind = FieldKind.DERIVED, expression = "x")
                Integer age();
                Map<String, List<Long>> metadata();
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot) {
    }

    private Generated generate(String viewSource) throws Exception {
        Path sourceRoot = Files.createTempDirectory("record-source");
        Path outputRoot = Files.createTempDirectory("record-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), viewSource);
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        return new Generated(sourceRoot, outputRoot);
    }

    @Test
    void topLevelRecordIsEmittedAndCompiles() throws Exception {
        Generated generated = generate(VIEW);

        Path record = generated.outputRoot().resolve("rec/hr/PersonSummaryRecord.java");
        Assertions.assertTrue(Files.exists(record), "RECORD emits a concrete top-level record");

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "RECORD",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    @Test
    void componentOrderIsTheFieldEnumOrder() throws Exception {
        Generated generated = generate(VIEW);
        String source = Files.readString(generated.outputRoot().resolve("rec/hr/PersonSummaryRecord.java"));

        // The fixture's ordinal order is id, firstName, lastName, age, metadata.
        Assertions.assertTrue(source.contains("public record PersonSummaryRecord("),
                "the record is top-level and named <View>Record: " + source);

        // Scope to the record HEADER: the file's import block also mentions `Map`, so a whole-file
        // indexOf would compare the wrong occurrences.
        int headerStart = source.indexOf("public record PersonSummaryRecord(");
        int headerEnd = source.indexOf("implements PersonSummary", headerStart);
        Assertions.assertTrue(headerEnd > headerStart, "the record header must end in `implements`");
        String header = source.substring(headerStart, headerEnd);

        int id = header.indexOf("Long id");
        int firstName = header.indexOf("String firstName");
        int lastName = header.indexOf("String lastName");
        int age = header.indexOf("Integer age");
        // The declared type is emitted as written in the view source, so the generic comma has no
        // following space here.
        int metadata = header.indexOf("metadata");
        Assertions.assertTrue(id >= 0 && firstName > id && lastName > firstName && age > lastName && metadata > age,
                "components must be declared in ordinal order, because create() maps positionally: " + header);
        Assertions.assertTrue(header.contains("Map<String,List<Long>>"),
                "and a parameterized component keeps its declared type: " + header);
        Assertions.assertTrue(source.contains("implements PersonSummary"),
                "and the record implements the view, so its accessors satisfy the interface by name");
    }

    @Test
    void createTargetsTheRecordPositionally() throws Exception {
        Generated generated = generate(VIEW);
        String meta = Files.readString(generated.outputRoot().resolve("rec/hr/PersonSummary_.java"));

        Assertions.assertTrue(meta.contains("-> new PersonSummaryRecord("),
                "META.create() constructs the concrete record: " + meta);
        Assertions.assertTrue(meta.contains("(Long) values[0]") || meta.contains("(java.lang.Long) values[0]"),
                "with one explicit positional cast per component, which is what makes the 1:1 mapping "
                        + "visible in the emitted source (DEC-019)");
        Assertions.assertTrue(meta.contains("(Map<String, List<Long>>) values[4]"),
                "including a parameterized component at its own ordinal");
        Assertions.assertFalse(meta.contains("ArrayBackedViewProxyFactory.createRead"),
                "a RECORD-level view has a concrete materialization, so no proxy is needed");
    }

    @Test
    void anExistingNestedRecordIsReusedAndNoSecondRecordIsEmitted() throws Exception {
        // § 8.4/3.10: recognize the nested record by shape and generate create() against it.
        String withNested = """
                package rec.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                @View(gen = GenLevel.RECORD)
                public interface PersonSummary extends PersonEntity {
                    String firstName();
                    String lastName();
                    record Record(Long id, String firstName, String lastName) implements PersonSummary {}
                }
                """;
        Generated generated = generate(withNested);

        Assertions.assertFalse(Files.exists(generated.outputRoot().resolve("rec/hr/PersonSummaryRecord.java")),
                "an existing nested record means NO second record is emitted");

        String meta = Files.readString(generated.outputRoot().resolve("rec/hr/PersonSummary_.java"));
        Assertions.assertTrue(meta.contains("-> new PersonSummary.Record("),
                "and create() targets the nested record: " + meta);
        Assertions.assertTrue(EntityMetadataGenerator.lastDivergences().stream()
                        .anyMatch(d -> d.startsWith("kind=nested_record_reused")),
                "the reuse is reported, not silent; got " + EntityMetadataGenerator.lastDivergences());
    }

    @Test
    void aMismatchedNestedRecordFallsBackInsteadOfMisConstructing() throws Exception {
        // The nested record's components do NOT match the field order, so it cannot be targeted.
        String stale = """
                package rec.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                @View(gen = GenLevel.RECORD)
                public interface PersonSummary extends PersonEntity {
                    String firstName();
                    String lastName();
                    record Record(String firstName, String lastName) implements PersonSummary {}
                }
                """;
        Generated generated = generate(stale);

        // The view declares `id` too (from the marker), so the nested record is short by one and the
        // generator must not emit a `create()` that would not compile.
        String meta = Files.readString(generated.outputRoot().resolve("rec/hr/PersonSummary_.java"));
        Assertions.assertTrue(meta.contains("-> new PersonSummaryRecord("),
                "a mismatched nested record is not used; a fresh top-level record is emitted: " + meta);
        Assertions.assertFalse(meta.contains("-> new PersonSummary.Record("),
                "and the stale nested record is never targeted");
    }

    @Test
    void aViewBelowTheRecordLevelStillUsesTheProxy() throws Exception {
        String metaLevel = VIEW.replace("GenLevel.RECORD", "GenLevel.META");
        Generated generated = generate(metaLevel);

        Assertions.assertFalse(Files.exists(generated.outputRoot().resolve("rec/hr/PersonSummaryRecord.java")),
                "META emits no record");
        String meta = Files.readString(generated.outputRoot().resolve("rec/hr/PersonSummary_.java"));
        Assertions.assertTrue(meta.contains("ArrayBackedViewProxyFactory.createRead"),
                "and META's create() stays on the array-backed read proxy");
    }
}
