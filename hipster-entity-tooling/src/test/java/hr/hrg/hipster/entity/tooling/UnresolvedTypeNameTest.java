package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A type parameter used where a class literal is required (plan.dsflash § 2.1 of the follow-up).
 *
 * <p>Notes F-38 found that {@code classLiteral}'s default branch passed a bare identifier straight
 * through, so an accessor returning a type variable produced {@code T.class} — source that does not
 * compile — with no diagnostic. It survived because the only type variable in the tree was
 * {@code ID}, which one of the two copies special-cased <em>by name</em>, and a hand-maintained list of
 * one is precisely the shape that produced F-43.</p>
 *
 * <p>What replaces it is a property of the source set: every type parameter declared in the tree is
 * indexed (from interfaces, records and methods), and a bare name among them resolves to
 * {@code java.lang.Object} and is reported as {@code type_unresolved}. The other direction matters just
 * as much and is asserted first — a type genuinely <em>named</em> {@code Object} produces the same
 * literal and must NOT be reported, which is why {@link TypeLiterals.Resolution} exists rather than a
 * comparison against the literal.</p>
 */
class UnresolvedTypeNameTest {

    private static final String MARKER = """
            package unresolved.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /**
     * An accessor whose return type is the enclosing method's own type parameter.
     *
     * <p>The fixture asks for {@code META} on purpose, and the reason is a real limit worth stating: an
     * accessor whose type is an unbound type parameter is <strong>not a materializable field</strong>.
     * The generator drops it from the field list and reports it, but a record or builder claiming to
     * implement the view would then be abstract (it cannot implement {@code <T> T value()}), so a view
     * that declares one can only carry those methods itself. Asking for META keeps this test about the
     * class literal and the report, which is what § 2.1 is; the materialization limit is recorded in
     * {@code dropUnresolvedTypeParameters}' javadoc.</p>
     */
    private static final String GENERIC_METHOD_VIEW = """
            package unresolved.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.META)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                <T> T value();
            }
            """;

    /** The id type a generic marker reports is its own type parameter — the live {@code ID} case. */
    private static final String GENERIC_MARKER = """
            package unresolved.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface Auditable<ID> extends EntityBase<ID> {}
            """;

    private static final String MARKERLESS_VIEW = """
            package unresolved.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.Identifiable;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonForm extends Identifiable<Long> {
                String firstName();
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot, Path packageDir, DivergenceReporter reporter) {
    }

    /** Generates a tree twice, so the second pass is the one with a previous revision to compare. */
    private static Generated generate(String... sources) throws Exception {
        Path sourceRoot = Files.createTempDirectory("unresolved-source");
        Path outputRoot = Files.createTempDirectory("unresolved-output");
        for (int i = 0; i < sources.length; i += 2) {
            Files.writeString(sourceRoot.resolve(sources[i]), sources[i + 1]);
        }
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);
        return new Generated(sourceRoot, outputRoot, outputRoot.resolve("unresolved/hr"), reporter);
    }

    private static String read(Path file) throws Exception {
        Assertions.assertTrue(Files.exists(file), "expected the generated file: " + file);
        return Files.readString(file).replace("\r\n", "\n");
    }

    @Test
    void aTypeParameterUsedAsAClassLiteralIsResolvedToObjectAndReported() throws Exception {
        Generated generated = generate("PersonEntity.java", MARKER, "PersonSummary.java", GENERIC_METHOD_VIEW);

        String enumSource = read(generated.packageDir().resolve("PersonSummary_.java"));

        Assertions.assertFalse(enumSource.contains("T.class"),
                "the enum must never carry a class literal that cannot compile: " + enumSource);
        Assertions.assertFalse(enumSource.contains("value("),
                "and the accessor is not a field, because a type parameter has no declarable type either: "
                        + enumSource);
        Assertions.assertTrue(enumSource.contains("firstName(java.lang.String.class)"),
                "while the ordinary accessors are unaffected: " + enumSource);

        List<String> reported = generated.reporter().ofKind("type_unresolved");
        Assertions.assertEquals(1, reported.size(),
                "the unresolved type is reported once: " + generated.reporter().render());
        Assertions.assertTrue(reported.get(0).contains("PersonSummary.value"),
                "and the line names the accessor a reader has to fix: " + reported);
    }

    @Test
    void aGenericMarkersIdTypeParameterIsHandledTheSameWay() throws Exception {
        // The live case: `Auditable<ID>` reports its own type parameter as the id type. A markerless view
        // must not borrow it — the id comes from its own `Identifiable<Long>`, so the generated record
        // declares `Long id` rather than `ID id`.
        Generated generated = generate("Auditable.java", GENERIC_MARKER, "PersonForm.java", MARKERLESS_VIEW);

        String recordSource = read(generated.packageDir().resolve("PersonFormRecord.java"));
        Assertions.assertTrue(recordSource.contains("Long id"),
                "the view's own Identifiable<Long> is the id type: " + recordSource);
        Assertions.assertFalse(recordSource.contains("ID id"),
                "a type parameter must never reach a declaration: " + recordSource);
    }

    @Test
    void theTypeActuallyNamedObjectIsNotReportedAsUnresolved() throws Exception {
        // The regression the Resolution enum exists for: `Object` and an unresolved type parameter both
        // produce `java.lang.Object.class`, so a check that compared literals would report every view
        // whose id is Object. (It did: three views of the example, on the first run of this check.)
        Generated generated = generate("PersonEntity.java", MARKER, "PersonSummary.java", GENERIC_METHOD_VIEW);

        String enumSource = read(generated.packageDir().resolve("PersonSummary_.java"));
        Assertions.assertTrue(enumSource.contains("firstName(java.lang.String.class)"),
                "the ordinary fields are untouched: " + enumSource);
        Assertions.assertFalse(generated.reporter().ofKind("type_unresolved").stream()
                        .anyMatch(entry -> entry.contains(".String")
                                || entry.contains(".Long") || entry.contains(".Integer")),
                "a resolved type is never reported: " + generated.reporter().render());
    }

    @Test
    void theGeneratedEnumWithAnUnresolvedTypeStillCompiles() throws Exception {
        // The point of the whole item: emit something that compiles and say what was resolved, rather
        // than emit `T.class` and let the compiler discover it (or not, if the file is never compiled).
        Generated generated = generate("PersonEntity.java", MARKER, "PersonSummary.java", GENERIC_METHOD_VIEW);

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(),
                "a view with an unresolved type parameter",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    /**
     * The resolution contract of the shared helper, asserted directly.
     *
     * <p>These four cases are the whole rule, and two of them are the ones that produced real bugs:
     * {@code Object} must be KNOWN (or every view with an {@code Object} id is reported as unresolved),
     * and an author-declared name must stay DECLARED (F-51's resolved decision — the spelling plus its
     * import is what makes the generated source navigable, per DEC-019).</p>
     */
    @Test
    void theTypeLiteralContractIsThreeWayNotBoolean() {
        Set<String> typeParameters = Set.of("T", "ID");

        Assertions.assertEquals("java.lang.String", TypeLiterals.classLiteral("String", typeParameters));
        Assertions.assertEquals("java.lang.Object", TypeLiterals.classLiteral("Object", typeParameters));
        Assertions.assertEquals(TypeLiterals.Resolution.KNOWN,
                TypeLiterals.resolutionOf("Object", typeParameters),
                "a name actually called Object is known, not unresolved");
        Assertions.assertFalse(TypeLiterals.isUnresolved("Object", typeParameters));

        Assertions.assertEquals(TypeLiterals.Resolution.UNRESOLVED,
                TypeLiterals.resolutionOf("T", typeParameters));
        Assertions.assertEquals("java.lang.Object", TypeLiterals.classLiteral("T", typeParameters));
        Assertions.assertEquals("java.lang.Object", TypeLiterals.classLiteral("ID", typeParameters),
                "the live case the hand-written special case used to cover");

        Assertions.assertEquals(TypeLiterals.Resolution.DECLARED,
                TypeLiterals.resolutionOf("LocalThing", typeParameters));
        Assertions.assertEquals("LocalThing", TypeLiterals.classLiteral("LocalThing", typeParameters),
                "an author-declared name keeps its spelling and its import (F-51/DEC-019)");

        Assertions.assertEquals("a.b.Thing", TypeLiterals.classLiteral("a.b.Thing", typeParameters));
        Assertions.assertEquals(TypeLiterals.Resolution.KNOWN,
                TypeLiterals.resolutionOf("a.b.Thing", typeParameters));
    }
}
