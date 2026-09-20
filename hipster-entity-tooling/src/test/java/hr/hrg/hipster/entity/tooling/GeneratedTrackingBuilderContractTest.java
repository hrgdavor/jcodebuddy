package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.EEnumSetBuilder;
import hr.hrg.hipster.entity.core.FieldChange;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Proves the emitted {@code BUILDER_TRACKED} materialization actually <strong>works</strong>, not
 * merely compiles (plan.dsflash § 8.6/3.14–3.16, and the "both materializations" half of DoD #4).
 *
 * <p>This test lives in the tooling module on purpose: the generator is a dev-time tool, and
 * {@code AGENTS.md} § 2 forbids a runtime app module from depending on it, so a test that calls
 * {@link EntityMetadataGenerator} and then runs its output belongs here rather than in
 * {@code hipster-entity-test}. The same state-sharing sequence is asserted there against a
 * hand-written stand-in, so the two together cover both materializations.</p>
 *
 * <h3>How the generated class is reached</h3>
 * <p>The emitted source is compiled into a temporary directory and loaded by a child class loader.
 * Its field enum is therefore a <em>different</em> type from anything this test could declare, so
 * the emitted {@code implements} clause is asserted on the source text (which is the precise
 * claim: {@code S} is the {@code EEnumSet} interface, never the final {@code EEnumSet64}), and the
 * runtime behaviour is driven through the parent-loader {@link ViewChangeTracking} interface, which
 * the generated class genuinely implements because that interface comes from the parent loader.</p>
 */
class GeneratedTrackingBuilderContractTest {

    private static final String MARKER = """
            package gen.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** The view under test: `id` from the marker, two COLUMN fields, one DERIVED field. */
    private static final String VIEW = """
            package gen.hr;
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

    /** The emitted ordinal layout: id=0, firstName=1, lastName=2, age=3 (DERIVED). */
    private static final int ID = 0;
    private static final int FIRST_NAME = 1;
    private static final int LAST_NAME = 2;
    private static final int AGE = 3;

    /**
     * A loaded generated builder, the baseline view it was built from, and the emitted source.
     *
     * <p>The baseline is kept because the new tracking contract has no {@code previous} half: the
     * old value lives on the caller's own baseline instance, so a test that wants an
     * old&nbsp;&rarr;&nbsp;new comparison has to read it there.</p>
     */
    private record Fixture(Object builder, Object baseline, String emittedSource) {
    }

    private Fixture load() throws Exception {
        Path sourceRoot = Files.createTempDirectory("gen-contract-source");
        Path outputRoot = Files.createTempDirectory("gen-contract-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW);

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        Path builderFile = outputRoot.resolve("gen/hr/PersonSummaryBuilderTracking.java");
        Assertions.assertTrue(Files.exists(builderFile), "BUILDER_TRACKED emits a tracking builder");
        String emittedSource = Files.readString(builderFile);

        List<Path> sources = new ArrayList<>(javaSourcesUnder(outputRoot));
        sources.addAll(javaSourcesUnder(sourceRoot));
        Path classes = Files.createTempDirectory("gen-contract-classes");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assertions.assertNotNull(compiler, "a java compiler must be available");
        List<String> diagnostics = new ArrayList<>();
        boolean ok;
        try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
            ok = compiler.getTask(null, fileManager,
                    d -> {
                        switch (d.getKind()) {
                            case ERROR, WARNING, MANDATORY_WARNING ->
                                    diagnostics.add(d.getKind() + ": " + d.getMessage(null));
                            default -> { }
                        }
                    },
                    List.of("-d", classes.toString(), "-classpath", classpath()),
                    null,
                    fileManager.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList()))
                    .call();
        }
        Assertions.assertTrue(ok, "the generated materialization must compile: " + diagnostics);
        Assertions.assertTrue(diagnostics.isEmpty(), "and with zero diagnostics: " + diagnostics);

        URLClassLoader loader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());

        Class<?> viewClass = loader.loadClass("gen.hr.PersonSummary");
        Class<?> builderClass = loader.loadClass("gen.hr.PersonSummaryBuilderTracking");
        Class<?> enumClass = loader.loadClass("gen.hr.PersonSummary_");

        // The baseline: the emitted META's create(Object[]) builds a read proxy over the positional
        // array, exactly as the ordinal contract prescribes. This exercises the emitted creator
        // function as well as the generated copy constructor.
        Object[] values = {1L, "Ada", "Lovelace", 36};
        Object meta = enumClass.getField("META").get(null);
        Object baseline = meta.getClass().getMethod("create", Object[].class).invoke(meta, (Object) values);

        Constructor<?> ctor = builderClass.getDeclaredConstructor(viewClass);
        return new Fixture(ctor.newInstance(baseline), baseline, emittedSource);
    }

    /** The generated builder, viewed through the parent-loader contract. */
    private static ViewChangeTracking<?, ?> tracking(Object builder) {
        Assertions.assertTrue(builder instanceof ViewChangeTracking,
                "the generated class must implement this module's ViewChangeTracking, which it can "
                        + "only do by naming the same contract in its implements clause");
        return (ViewChangeTracking<?, ?>) builder;
    }

    /**
     * The live change set, as a raw {@code EEnumSetBuilder}.
     *
     * <p>Raw on purpose: the generated enum type is loaded by a child class loader, so it satisfies
     * {@code E extends Enum<E>} at runtime but cannot be named in this compilation unit. The
     * assertions only call the {@code has(int)}/{@code removeOrdinal(int)} half of the contract,
     * which is ordinal-based and therefore type-independent.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EEnumSetBuilder builder(ViewChangeTracking<?, ?> tracking) {
        return tracking.changesBuilder();
    }

    private static List<Path> javaSourcesUnder(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String classpath() {
        String separator = System.getProperty("path.separator");
        return String.join(separator,
                repoRoot().resolve("hipster-entity-api/target/classes").toString(),
                repoRoot().resolve("hipster-entity-core/target/classes").toString());
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("pom.xml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("cannot locate the repository root");
        }
        if (current.getFileName().toString().startsWith("hipster-entity-") && current.getParent() != null) {
            return current.getParent();
        }
        return current;
    }

    // ------------------------------------------------------------------ assertions

    @Test
    void emittedImplementsClauseNamesTheFinalContract() throws Exception {
        String source = load().emittedSource();

        Assertions.assertTrue(
                source.contains("ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>>"),
                "S must be the EEnumSet interface, the type toImmutable() actually returns: " + source);
        Assertions.assertFalse(source.contains("EEnumSet64"),
                "EEnumSet64 is final and a sibling of the cached EEnumSetEmpty singleton, so a "
                        + "changes() accessor typed on it could not compile (DR-4)");
        Assertions.assertTrue(source.contains("changesBuilder()"),
                "both accessors exist on the generated builder");
        Assertions.assertTrue(source.contains("return mf.toImmutable();"),
                "changes() snapshots the one mf field");
    }

    @Test
    void generatedBuilderSharesStateBetweenItsTwoAccessors() throws Exception {
        ViewChangeTracking<?, ?> view = tracking(load().builder());

        Assertions.assertFalse(view.isChanged(), "a fresh builder is unchanged");
        Assertions.assertTrue(view.changes().isEmpty());

        // One real change: both accessors agree.
        setInt(view, FIRST_NAME, "Grace");
        Assertions.assertTrue(builder(view).has(FIRST_NAME));
        Assertions.assertTrue(view.changes().has(FIRST_NAME));

        // A snapshot captured before the mutation keeps the old state.
        var before = view.changes();
        builder(view).removeOrdinal(FIRST_NAME);
        Assertions.assertFalse(view.changes().has(FIRST_NAME),
                "the generated builder returns the live mf, so the accessors cannot disagree");
        Assertions.assertFalse(view.isChanged());
        Assertions.assertTrue(before.has(FIRST_NAME), "and changes() is a snapshot, not a live alias");

        // A second, different writable field: `age` (ordinal 3) is DERIVED and correctly rejected,
        // so the sequence uses lastName instead.
        setInt(view, LAST_NAME, "Byron");
        Assertions.assertTrue(view.isChanged());
        view.clearChanges();
        Assertions.assertTrue(view.changes().isEmpty());
        Assertions.assertFalse(view.isChanged());
    }

    @Test
    void generatedBuilderMarksAChangedFieldAndReportsItsCurrentValue() throws Exception {
        Fixture fixture = load();
        ViewChangeTracking<?, ?> view = tracking(fixture.builder());

        setInt(view, FIRST_NAME, "Grace");

        // (a) the ordinal is marked, and (b) the tracker reports the value the field holds now. That
        // is the whole of the tracker's own state: there is no previous-value half any more.
        Assertions.assertTrue(view.changes().has(FIRST_NAME),
                "a differing write marks the ordinal");
        Assertions.assertTrue(view.isChanged());

        List<? extends FieldChange<?>> changed = view.changedValues();
        Assertions.assertEquals(1, changed.size());
        Assertions.assertEquals("firstName", changed.get(0).fieldName());
        Assertions.assertEquals("Grace", changed.get(0).current(),
                "changedValues() yields (field, current): the value the field holds now");

        // The old value is the caller's, not the tracker's: the baseline instance this mutable was
        // built from still reports it, which is exactly the comparison the new contract prescribes.
        Assertions.assertEquals("Ada", read(fixture.baseline(), "firstName"),
                "the copy constructor read the baseline once; the tracker itself kept no copy of it");
    }

    @Test
    void aRevertedWriteStaysMarkedBecauseThereIsNoBaselineInTheTracker() throws Exception {
        Fixture fixture = load();
        ViewChangeTracking<?, ?> view = tracking(fixture.builder());

        setInt(view, FIRST_NAME, "Grace");
        setInt(view, FIRST_NAME, "Ada"); // write the original value back

        Assertions.assertTrue(view.changes().has(FIRST_NAME),
                "the second write also differed from what the field held at that moment, so the "
                        + "ordinal stays marked: there is no recorded baseline to unmark against");
        Assertions.assertEquals("Ada", view.changedValues().get(0).current());

        // "Differs from my baseline" is therefore the caller's comparison, and it can still answer
        // "no net change" here because the caller holds both instances.
        Assertions.assertEquals(read(fixture.baseline(), "firstName"),
                view.changedValues().get(0).current(),
                "baseline and current agree, so the caller concludes there is no net change — the "
                        + "tracker's mark is not that answer and never claimed to be");
    }

    @Test
    void generatedBuilderHonoursTheNoOpRule() throws Exception {
        Fixture fixture = load();
        ViewChangeTracking<?, ?> view = tracking(fixture.builder());

        setInt(view, FIRST_NAME, "Ada"); // the value the field already holds

        Assertions.assertFalse(view.isChanged(), "DEC-012: an equal-value write records nothing");
        Assertions.assertTrue(view.changes().isEmpty());
        Assertions.assertTrue(view.changedValues().isEmpty(),
                "and there is no previous-value state to report either way: the tracker keeps none");
        Assertions.assertEquals("Ada", read(fixture.baseline(), "firstName"),
                "the baseline is untouched, which is the only old-value state that exists");
    }

    @Test
    void generatedBuilderReportsMinusOneForAnUnknownName() throws Exception {
        Object builder = load().builder();

        Assertions.assertEquals(-1, invokeIntSetter(builder, "noSuchField", "x"),
                "the generated builder keeps the -1 probe contract (D5)");
        Assertions.assertEquals(FIRST_NAME, invokeIntSetter(builder, "firstName", "Grace"));
    }

    @Test
    void generatedBuilderRejectsTheDerivedFieldsOrdinal() throws Exception {
        Object builder = load().builder();

        // set(int, Object) throws for a non-writable ordinal, which is how a DERIVED field stays
        // read-only on the generated path (S1).
        Method setter = builder.getClass().getMethod("set", int.class, Object.class);
        var thrown = Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> setter.invoke(builder, AGE, 37));
        Assertions.assertInstanceOf(UnsupportedOperationException.class, thrown.getCause(),
                "a DERIVED field must not be writable through the generated builder");
    }

    @Test
    void generatedBuilderKeepsAnOrdinalZeroSetter() throws Exception {
        ViewChangeTracking<?, ?> view = tracking(load().builder());

        setInt(view, ID, 2L);

        Assertions.assertTrue(view.isChanged(),
                "the immutable-id rule is array-path only (DoD #4): id() is an unannotated COLUMN "
                        + "field, so the generated tracking builder keeps its ordinal-0 setter");
        List<? extends FieldChange<?>> changed = view.changedValues();
        Assertions.assertEquals("id", changed.get(0).fieldName());
        Assertions.assertEquals(2L, changed.get(0).current(),
                "the setter marked ordinal 0 and currentValue reports the value it holds now");
    }

    /** Drives the generated fluent setter of one ordinal by its positional form. */
    private static void setInt(ViewChangeTracking<?, ?> view, int ordinal, Object value) throws Exception {
        view.getClass().getMethod("set", int.class, Object.class).invoke(view, ordinal, value);
    }

    private static int invokeIntSetter(Object builder, String field, Object value) throws Exception {
        return (int) builder.getClass().getMethod("set", String.class, Object.class).invoke(builder, field, value);
    }

    /**
     * Reads one accessor off a loaded generated object. Reflection is unavoidable here: the view is
     * loaded by a child class loader, so its type cannot be named in this compilation unit.
     */
    private static Object read(Object target, String accessor) throws Exception {
        return target.getClass().getMethod(accessor).invoke(target);
    }
}
