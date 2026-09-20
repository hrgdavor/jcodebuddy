package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Phase 7.1–7.3 acceptance test (plan.dsflash § 12.1): the generated positional adapter and
 * binder must be real, compiling, reflection-free source whose column list excludes every field a
 * writer must not touch.
 *
 * <p>No database, driver, or network is involved: the assertions are on the emitted source, and the
 * compile gate proves the emitted source is valid Java against the real {@code api} + {@code core}
 * classpath.</p>
 *
 * <h3>Status of the generator under test: draft / exploration</h3>
 * <p>SQL materialization is not a supported generator. It is kept behind the explicit
 * {@code --adapters} flag as an exploration of what generated JDBC access would look like, the
 * example project deliberately does not enable it, and its emitted API may change or disappear. The
 * tests below exist so the draft cannot rot silently — including
 * {@link #sqlGenerationIsOptInAndOffByDefault()}, which pins the rule that a future SQL generator
 * must also obey: nothing is emitted unless a project asks for it.</p>
 */
class ViewAdapterGeneratorTest {

    private static final String MARKER_SOURCE = """
            package fixture.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface PersonEntity extends EntityBase<Long> {}
            """;

    /**
     * The fixture view. It carries three things the generator must get right:
     * a renamed column ({@code @FieldSource(column = "last_name")}), a {@code DERIVED} field that
     * must never be written, and an unannotated {@code COLUMN} field that is writable by default.
     */
    private static final String VIEW_SOURCE = """
            package fixture.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            public interface PersonSummary extends PersonEntity {
                String firstName();
                @FieldSource(column = "last_name")
                String lastName();
                @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
                Integer age();
            }
            """;

    /** The generated tree and the hand-written tree it was generated from. */
    private record Generated(Path sourceRoot, Path outputRoot) {
    }

    private Generated generate() throws Exception {
        Path sourceRoot = Files.createTempDirectory("adapter-source");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER_SOURCE);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW_SOURCE);

        Path outputRoot = Files.createTempDirectory("adapter-output");
        EntityMetadataGenerator.setGenerateAdapters(true);
        try {
            EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        } finally {
            EntityMetadataGenerator.setGenerateAdapters(false);
        }
        return new Generated(sourceRoot, outputRoot);
    }

    @Test
    void adapterAndBinderAreGeneratedForTheView() throws Exception {
        Generated generated = generate();

        Path rowAdapter = generated.outputRoot().resolve("fixture/hr/PersonSummaryRowAdapter.java");
        Path binder = generated.outputRoot().resolve("fixture/hr/PersonSummaryBinder.java");
        Assertions.assertTrue(Files.exists(rowAdapter), "a positional row adapter is generated");
        Assertions.assertTrue(Files.exists(binder), "a positional binder is generated");
    }

    /**
     * SQL generation is <strong>opt-in, and off by default</strong> — the rule that must hold for the
     * draft today and for whatever SQL support comes later.
     *
     * <p>The default pass writes the ordinary generators only. The same tree generated twice, with and
     * without the flag, differs by exactly the adapter pair; nothing else enables it (no POM property,
     * no annotation, no profile), so a project that never asks never gets SQL classes it did not
     * choose.</p>
     */
    @Test
    void sqlGenerationIsOptInAndOffByDefault() throws Exception {
        Path sourceRoot = Files.createTempDirectory("adapter-optin-source");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER_SOURCE);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW_SOURCE);
        Path defaultOutput = Files.createTempDirectory("adapter-optin-default");
        Path optedInOutput = Files.createTempDirectory("adapter-optin-enabled");

        // (1) The default pass. The generator's static flag is false here, which is also its
        //     initial value — the assertion below is the one that would fail if a future change made
        //     SQL emission part of the default route.
        Assertions.assertFalse(EntityMetadataGenerator.isGenerateAdapters(),
                "the generator must start each process with SQL generation switched OFF");
        EntityMetadataGenerator.generate(sourceRoot, defaultOutput);

        Assertions.assertTrue(CompileHarness.javaSourcesUnder(defaultOutput).stream()
                        .noneMatch(p -> p.getFileName().toString().endsWith("RowAdapter.java")),
                "no row adapter without the opt-in flag");
        Assertions.assertTrue(CompileHarness.javaSourcesUnder(defaultOutput).stream()
                        .noneMatch(p -> p.getFileName().toString().endsWith("Binder.java")),
                "no binder without the opt-in flag");
        Assertions.assertTrue(Files.exists(defaultOutput.resolve("fixture/hr/PersonSummary_.java")),
                "…while the ordinary generators still ran, so the default pass itself is unaffected");

        // (2) The opted-in pass over the same sources does emit them.
        EntityMetadataGenerator.setGenerateAdapters(true);
        try {
            EntityMetadataGenerator.generate(sourceRoot, optedInOutput);
        } finally {
            EntityMetadataGenerator.setGenerateAdapters(false);
        }
        Assertions.assertTrue(Files.exists(optedInOutput.resolve("fixture/hr/PersonSummaryRowAdapter.java")),
                "the flag is what turns SQL generation on");
        Assertions.assertTrue(Files.exists(optedInOutput.resolve("fixture/hr/PersonSummaryBinder.java")),
                "for both halves of the pair");
    }

    /**
     * A {@code COLUMN} field answers {@code column()} from the enum itself, annotation or not
     * (notes F-12; the follow-up plan's § 3.2).
     *
     * <p>This is the property the draft adapter depends on: it drives its writable-column list from
     * {@code FieldDef.column()} rather than from a name table of its own. Before the rule, a view with
     * no {@code @FieldSource} anywhere produced {@code null} for every field, so an adapter over such
     * a view had no column names at all — the deficiency F-12 flags for § 12.1/7.2–7.4.</p>
     */
    @Test
    void everyColumnFieldAnswersColumnFromTheEnumEvenWithoutAnAnnotation() throws Exception {
        Generated generated = generate();
        String enumSource = Files.readString(generated.outputRoot().resolve("fixture/hr/PersonSummary_.java"));

        Assertions.assertTrue(enumSource.contains("return \"last_name\";"),
                "the annotated label still wins: " + enumSource);
        Assertions.assertTrue(enumSource.contains("return \"firstName\";"),
                "and an unannotated COLUMN field resolves to the accessor name: " + enumSource);
        Assertions.assertTrue(enumSource.contains("return \"id\";"),
                "including the inherited identity column: " + enumSource);
        Assertions.assertFalse(enumSource.contains("return \"age\";"),
                "while a DERIVED field is not a column and must not claim one: " + enumSource);

        // The contract on the other side: the draft adapter reads exactly this and no name table.
        String binderSource = Files.readString(generated.outputRoot().resolve("fixture/hr/PersonSummaryBinder.java"));
        Assertions.assertTrue(binderSource.contains("COLUMNS = {\"id\", \"firstName\", \"last_name\"}"),
                "the binder's column list is the resolved column names in ordinal order: " + binderSource);
    }

    @Test
    void binderUsesColumnLabelsAndExcludesDerivedFields() throws Exception {
        Generated generated = generate();
        String binderSource = Files.readString(generated.outputRoot().resolve("fixture/hr/PersonSummaryBinder.java"));

        // @FieldSource.column() wins over the accessor name.
        Assertions.assertTrue(binderSource.contains("\"last_name\""),
                "the declared column label is used: " + binderSource);
        // An unannotated field falls back to the accessor name.
        Assertions.assertTrue(binderSource.contains("\"firstName\""),
                "an unannotated column falls back to the accessor name");
        // The DERIVED field must not be bound at all.
        Assertions.assertFalse(binderSource.contains("\"age\""),
                "a DERIVED field must never appear in the binder's column list");

        // id (0), firstName (1), lastName (2) are COLUMN; age (3) is DERIVED.
        Assertions.assertTrue(binderSource.contains("COLUMNS = {\"id\", \"firstName\", \"last_name\"}"),
                "writable columns are exactly the COLUMN fields in ordinal order: " + binderSource);
        Assertions.assertTrue(binderSource.contains("ORDINALS = {0, 1, 2}"),
                "and their ordinals follow: " + binderSource);
    }

    @Test
    void binderEmitsParameterisedSqlFragments() throws Exception {
        Generated generated = generate();
        String binderSource = Files.readString(generated.outputRoot().resolve("fixture/hr/PersonSummaryBinder.java"));

        Assertions.assertTrue(binderSource.contains("public static String insertSql(String table)"),
                "an INSERT fragment builder is emitted");
        Assertions.assertTrue(binderSource.contains("public static String updateSql(String table, boolean[] changed)"),
                "an UPDATE fragment builder driven by a change set is emitted");
        Assertions.assertTrue(binderSource.contains("public static void bindChanged("),
                "a partial-update binder is emitted");
        Assertions.assertTrue(binderSource.contains("ps.setNull(index, Types.NULL)"),
                "a null value is written as SQL NULL, not as a type error");
    }

    @Test
    void rowAdapterIsDrivenByViewMetaNotBySelectStarOrder() throws Exception {
        Generated generated = generate();
        String rowAdapterSource = Files.readString(
                generated.outputRoot().resolve("fixture/hr/PersonSummaryRowAdapter.java"));

        Assertions.assertTrue(rowAdapterSource.contains("rs.getObject(meta.fieldNameAt(i))"),
                "the reader is driven by ViewMeta field names, not by SELECT * order");
        Assertions.assertTrue(rowAdapterSource.contains("new Object[meta.fieldCount()]"),
                "the reader allocates one slot per field, so the array length matches fieldCount");
        // The javadoc may mention the phrase; what must not exist is an actual `SELECT *` string
        // literal, i.e. a query the adapter could execute positionally.
        Assertions.assertFalse(rowAdapterSource.contains("\"SELECT *"),
                "the reader never emits a SELECT * statement");
        Assertions.assertFalse(rowAdapterSource.contains("getObject(i)"),
                "the reader never reads by positional index, which would depend on SELECT * order");
    }

    @Test
    void generatedAdaptersCompile() throws Exception {
        Generated generated = generate();

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "adapters",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }
}
