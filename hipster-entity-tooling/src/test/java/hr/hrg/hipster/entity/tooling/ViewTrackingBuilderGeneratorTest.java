package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code BUILDER_TRACKED} level of plan.dsflash § 8.6/3.14–3.16.
 *
 * <p>The emitted builder must implement the <em>final</em> Phase 1 contract, so the primary
 * assertion is that it compiles against the real {@code core} classpath with zero diagnostics. The
 * textual assertions pin the four shape rules that make it correct rather than merely compilable:
 * {@code S} is the {@code EEnumSet} interface, both accessors derive from the one {@code mf} field,
 * setters use ordinal <em>literals</em>, and only {@code COLUMN} fields get setters.</p>
 */
class ViewTrackingBuilderGeneratorTest {

    private static final String MARKER = """
            package tracked.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** A view at BUILDER_TRACKED with a DERIVED field that must NOT get a setter (S1). */
    private static final String VIEW = """
            package tracked.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_TRACKED)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
                Integer age();
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot, String builderSource) {
    }

    private Generated generate(String viewSource) throws Exception {
        Path sourceRoot = Files.createTempDirectory("tracked-source");
        Path outputRoot = Files.createTempDirectory("tracked-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), viewSource);

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        Path builder = outputRoot.resolve("tracked/hr/PersonSummaryBuilderTracking.java");
        Assertions.assertTrue(Files.exists(builder), "BUILDER_TRACKED emits a tracking builder");
        return new Generated(sourceRoot, outputRoot, Files.readString(builder));
    }

    @Test
    void builderIsEmittedAndCompiles() throws Exception {
        Generated generated = generate(VIEW);

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "BUILDER_TRACKED",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    @Test
    void sIsTheEEnumSetInterfaceNeverTheFinalClass() throws Exception {
        String source = generate(VIEW).builderSource();

        Assertions.assertTrue(
                source.contains("ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>>"),
                "S must be EEnumSet, the type toImmutable() actually returns: " + source);
        Assertions.assertFalse(source.contains("EEnumSet64"),
                "EEnumSet64 is final and a sibling of the cached EEnumSetEmpty singleton, so it "
                        + "cannot type a changes() accessor (DR-4)");
        Assertions.assertTrue(source.contains("return mf.toImmutable();"),
                "changes() returns the snapshot from the one mf field");
        Assertions.assertTrue(source.contains("return mf;"),
                "changesBuilder() returns the live builder itself");
    }

    @Test
    void bothAccessorsDeriveFromTheSingleMfField() throws Exception {
        String source = generate(VIEW).builderSource();

        int mfDeclarations = source.split("EEnumSetBuilder64<PersonSummary_> mf", -1).length - 1;
        Assertions.assertEquals(1, mfDeclarations,
                "exactly one tracking-state field, never a second field or a snapshot cache (S5)");
        Assertions.assertFalse(source.contains("snapshotCache") || source.contains("changesCache"),
                "no snapshot cache may exist, or the two accessors could disagree");
    }

    @Test
    void settersCompareAtTheWriteSiteAndMarkOrdinalLiterals() throws Exception {
        String source = generate(VIEW).builderSource();

        // id=0, firstName=1, lastName=2, age=3 (DERIVED). The literal must be a literal so the JIT
        // can constant-fold the 1L << ordinal mask.
        String firstNameSetter = "public PersonSummaryBuilderTracking firstName(String value)";
        Assertions.assertTrue(source.contains(firstNameSetter)
                        && source.contains("if (!java.util.Objects.equals(this.firstName, value)) {"),
                "the setter compares the incoming value with the one the field holds at that moment, "
                        + "at the write site: the tracker keeps no baseline of its own: " + source);

        int setterStart = source.indexOf(firstNameSetter);
        String setter = source.substring(setterStart, source.indexOf("return this;", setterStart));
        int compare = setter.indexOf("java.util.Objects.equals(this.firstName, value)");
        int assign = setter.indexOf("this.firstName = value;");
        int mark = setter.indexOf("mf.addOrdinal(1);");
        Assertions.assertTrue(compare >= 0 && assign > compare && mark > assign,
                "compare first, then assign, then mark ordinal 1 (the verified DEC-012 order): "
                        + setter);
        Assertions.assertFalse(source.contains("mf.addOrdinal(ordinal)"),
                "the ordinal must be a compile-time literal, not a variable");
        Assertions.assertFalse(source.contains("addOrdinalChange"),
                "the removed previous-value writer must not be emitted anywhere: " + source);
        // Generated setters touch mf directly; changes() allocates and is a read-side API only.
        Assertions.assertFalse(source.contains("changes().addOrdinal") || source.contains("changes().removeOrdinal"),
                "no generated setter may route through changes() (§ 8.6/3.15)");
    }

    @Test
    void onlyColumnFieldsGetSetters() throws Exception {
        String source = generate(VIEW).builderSource();

        Assertions.assertTrue(source.contains("public PersonSummaryBuilderTracking firstName(String value)"),
                "a COLUMN field gets a fluent setter");
        Assertions.assertFalse(source.contains("public PersonSummaryBuilderTracking age(Integer value)"),
                "a DERIVED field gets NO setter (S1)");
        // The fixture's ordinals are id=0, firstName=1, lastName=2, age=3 (DERIVED). `get(int)` has
        // arms for every field, so the assertion must be scoped to the `set(int, Object)` body — the
        // mutator is the one that decides writability.
        String setterBlock = source.substring(
                source.indexOf("public void set(int fieldOrdinal, Object value)"),
                source.indexOf("public int set(String field, Object value)"));
        Assertions.assertTrue(setterBlock.contains("case 1 ->"),
                "a set(int) arm exists for a COLUMN field: " + setterBlock);
        Assertions.assertFalse(setterBlock.contains("case 3 ->"),
                "and no set(int) arm exists for the DERIVED field's ordinal (S1): " + setterBlock);

        // The getter, by contrast, must still expose the derived field for reading.
        Assertions.assertTrue(source.contains("case 3 -> age;"),
                "a DERIVED field stays readable through the positional getter");
    }

    @Test
    void nameMutatorReturnsMinusOneForAnUnknownField() throws Exception {
        String source = generate(VIEW).builderSource();

        Assertions.assertTrue(source.contains("public int set(String field, Object value)"),
                "the builder exposes the name-based mutator");
        Assertions.assertTrue(source.contains("return -1;"),
                "which returns -1 for an unknown name, matching the array contract (D5)");
    }

    @Test
    void builderHasABaselineCopyConstructor() throws Exception {
        String source = generate(VIEW).builderSource();

        Assertions.assertTrue(source.contains("public PersonSummaryBuilderTracking(PersonSummary source)"),
                "the copy constructor from the source view is mandatory (§ 8.6/3.16): it is the only "
                        + "baseline there is, because the tracker itself keeps none");
        Assertions.assertTrue(source.contains("this.firstName = source.firstName();"),
                "and it copies every field, which is what the write-site comparison reads back");
    }

    @Test
    void aViewBelowTheTrackingLevelEmitsNoBuilder() throws Exception {
        String metaLevel = VIEW.replace("GenLevel.BUILDER_TRACKED", "GenLevel.META");

        Path sourceRoot = Files.createTempDirectory("tracked-meta-source");
        Path outputRoot = Files.createTempDirectory("tracked-meta-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), metaLevel);
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        Assertions.assertFalse(
                Files.exists(outputRoot.resolve("tracked/hr/PersonSummaryBuilderTracking.java")),
                "the generator picks the materialization from GenLevel; META does not emit a builder");
    }

    @Test
    void largeViewsSelectTheLargeBuilderAtGenerationTime() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            body.append("    String f").append(i).append("();\n");
        }
        String wide = "package tracked.hr;\n"
                + "import hr.hrg.hipster.entity.api.GenLevel;\n"
                + "import hr.hrg.hipster.entity.api.View;\n"
                + "@View(gen = GenLevel.BUILDER_TRACKED)\n"
                + "public interface PersonSummary extends PersonEntity {\n"
                + body
                + "}\n";

        String source = generate(wide).builderSource();

        Assertions.assertTrue(source.contains("EEnumSetBuilderLarge<PersonSummary_>"),
                "the concrete builder is chosen at GENERATION time from the known field count, "
                        + "not at runtime (§ 8.6/3.14)");
        Assertions.assertFalse(source.contains("EEnumSetBuilder64<PersonSummary_>"),
                "and the 64-bit variant is not used for a 71-field view");
    }
}
