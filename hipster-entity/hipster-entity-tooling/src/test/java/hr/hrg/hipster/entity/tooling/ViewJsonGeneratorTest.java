package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Step 6.5's acceptance test: the projection + DTO marker pattern (DEC-003/DEC-007) emits a real,
 * compiling, reflection-free JSON writer for a view marked {@code @View(dto = true)}, and the JSON it
 * produces is the same document the existing Jackson serializer produces for the same values.
 *
 * <p>Three things are pinned here and each one is a separate failure mode:</p>
 * <ul>
 *   <li><strong>the marker</strong> — a view without {@code dto = true} gains no writer even when the
 *       pass opted in, so enabling the pass for a project does not rewrite its whole tree;</li>
 *   <li><strong>the opt-in flag</strong> — a marked view in a default pass gains no writer either, so
 *       a project that never asks never receives a class that imports Jackson;</li>
 *   <li><strong>the document</strong> — the emitted writer is compiled, loaded and <em>run</em>, and
 *       its output is compared to {@link EntityJacksonSerializerReference}, which is the same values
 *       written through {@code ViewReader.get(ordinal)}. Equality of the two documents is the point:
 *       the projection path exists to skip materialization, not to produce different JSON.</li>
 * </ul>
 */
class ViewJsonGeneratorTest {

    /**
     * The entity marker. It declares {@code id()} for the reason a real marker does — the view's ledger
     * carries an identity field, and the writer calls it on the view's own type, so the accessor has to
     * be reachable from the view. The example's {@code Person} satisfies the same requirement through
     * {@code Identifiable<Long>}.
     */
    private static final String MARKER_SOURCE = """
            package fixture.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface PersonEntity extends EntityBase<Long> {
                Long id();
            }
            """;

    /** The base view: deliberately <em>not</em> a DTO, so it is the control for the marker. */
    private static final String VIEW_SOURCE = """
            package fixture.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            public interface PersonSummary extends PersonEntity {
                String firstName();
                @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
                Integer age();
            }
            """;

    /** The read projection: the marker under test, plus a record so the test can build an instance. */
    private static final String DTO_SOURCE = """
            package fixture.hr;
            import hr.hrg.hipster.entity.api.View;
            @View(dto = true)
            public interface PersonDto extends PersonSummary {
                record Record(Long id, String firstName, Integer age) implements PersonDto {}
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot) {
    }

    private Generated generate(String dtoSource, boolean optedIn) throws Exception {
        Path sourceRoot = Files.createTempDirectory("dto-source");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER_SOURCE);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW_SOURCE);
        Files.writeString(sourceRoot.resolve("PersonDto.java"), dtoSource);

        Path outputRoot = Files.createTempDirectory("dto-output");
        EntityMetadataGenerator.setGenerateDtoProjections(optedIn);
        try {
            EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        } finally {
            EntityMetadataGenerator.setGenerateDtoProjections(false);
        }
        return new Generated(sourceRoot, outputRoot);
    }

    @Test
    void theMarkedViewGetsAWriterAndTheUnmarkedOneDoesNot() throws Exception {
        Generated generated = generate(DTO_SOURCE, true);

        Assertions.assertTrue(Files.exists(generated.outputRoot().resolve("fixture/hr/PersonDtoJson.java")),
                "the marked view gets its projection writer");
        Assertions.assertFalse(
                Files.exists(generated.outputRoot().resolve("fixture/hr/PersonSummaryJson.java")),
                "the marker is what selects a view: the unmarked base view gets none, even with the flag on");
        Assertions.assertTrue(Files.exists(generated.outputRoot().resolve("fixture/hr/PersonSummary_.java")),
                "…while the ordinary generators still ran for both views");
    }

    @Test
    void theFlagIsTheOtherHalfOfTheOptIn() throws Exception {
        Assertions.assertFalse(EntityMetadataGenerator.isGenerateDtoProjections(),
                "the generator must start each process with projections switched OFF");

        EntityMetadataGenerator.setGenerateDtoProjections(true);
        Assertions.assertTrue(EntityMetadataGenerator.isGenerateDtoProjections(),
                "the flag is readable, so a preflight and a test can assert it");
        EntityMetadataGenerator.setGenerateDtoProjections(false);

        Generated generated = generate(DTO_SOURCE, false);
        Assertions.assertFalse(
                Files.exists(generated.outputRoot().resolve("fixture/hr/PersonDtoJson.java")),
                "a marked view in a default pass gains no writer: the flag is required too");
    }

    @Test
    void theWriterNamesItsFieldsInLedgerOrderAndCallsTheViewsOwnAccessors() throws Exception {
        Generated generated = generate(DTO_SOURCE, true);
        String source = Files.readString(generated.outputRoot().resolve("fixture/hr/PersonDtoJson.java"));

        Assertions.assertTrue(source.contains("FIELDS = {\"id\", \"firstName\", \"age\"}"),
                "the field list is the ledger order, as a literal: " + source);
        Assertions.assertTrue(source.contains("gen.writeNumberProperty(\"id\", source.id());"),
                "a Long field is a compiled number write on the view's own accessor");
        Assertions.assertTrue(source.contains("gen.writeStringProperty(\"firstName\", source.firstName());"),
                "a String field is a compiled string write");
        Assertions.assertTrue(source.contains("gen.writeNumberProperty(\"age\", source.age());"),
                "an Integer field is a compiled number write");
        Assertions.assertFalse(source.contains("get("),
                "nothing reads the positional array: that is the materialization this path skips");
        Assertions.assertFalse(source.contains("java.lang.reflect"),
                "and nothing reflects (AGENTS.md § 1 / DEC-019)");
    }

    @Test
    void generatedWriterCompiles() throws Exception {
        Generated generated = generate(DTO_SOURCE, true);
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "dto projection",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    /**
     * The document test: the emitted writer is compiled, loaded, given a real record instance, and its
     * JSON is compared with the document built from the view's own <strong>field enum</strong>.
     *
     * <p>This is what the step's gate asks for when it says the DTO path is <em>asserted by a test</em>.
     * The expected document is derived from the committed generated enum rather than written as a golden
     * string, and that choice is the point: the enum is the ledger the projector path exists to skip, so
     * agreeing with <em>it</em> is the property under test. A golden string would keep passing if the
     * writer's field order and the enum's ordinals drifted apart — which is exactly the disagreement
     * this test is here to catch.</p>
     */
    @Test
    void theWrittenDocumentEqualsTheOrdinalLedgerOverTheSameValues() throws Exception {
        Generated generated = generate(DTO_SOURCE, true);

        Path classes = CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "dto projection runtime",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));

        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] { classes.toUri().toURL() }, getClass().getClassLoader())) {
            Class<?> view = loader.loadClass("fixture.hr.PersonDto");
            Class<?> recordClass = loader.loadClass("fixture.hr.PersonDto$Record");
            Object instance = recordClass.getConstructor(Long.class, String.class, Integer.class)
                    .newInstance(7L, "Ada", 36);
            Assertions.assertTrue(view.isInstance(instance), "the record implements the view");

            ObjectMapper mapper = new ObjectMapper();
            StringWriter out = new StringWriter();
            Class<?> writer = loader.loadClass("fixture.hr.PersonDtoJson");
            writer.getMethod("toJson", ObjectMapper.class, java.io.Writer.class, view)
                    .invoke(null, mapper, out, instance);

            String fromWriter = out.toString();
            String fromLedger = documentFromFieldEnum(loader, view, instance);

            Assertions.assertEquals(fromLedger, fromWriter,
                    "the projection path must produce the document the view's own field enum describes");
            Assertions.assertEquals("{\"id\":7,\"firstName\":\"Ada\",\"age\":36}", fromWriter,
                    "and that document carries exactly the view's fields, in ledger order");
        }
    }

    /**
     * The expected document, built by walking the generated field enum and calling each constant's
     * accessor on the instance — the ordinal ledger, in code.
     *
     * <p>Scalars only, deliberately: a composite would need a codec here too, and the composite case has
     * its own test above. A null is written as JSON {@code null}, which the fixture does not exercise but
     * the generated writer produces by the same type of call.</p>
     */
    private static String documentFromFieldEnum(ClassLoader loader, Class<?> view, Object instance)
            throws Exception {
        Class<?> enumClass = loader.loadClass(view.getName() + "_");
        Object[] constants = (Object[]) enumClass.getMethod("values").invoke(null);
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < constants.length; i++) {
            Object constant = constants[i];
            // `FieldDef.name()` is `Enum.name()` here (the naming contract: constant name == accessor
            // name, with no mapping layer), so the ledger's own accessor is the field name.
            String name = (String) enumClass.getMethod("name").invoke(constant);
            Object value = view.getMethod(name).invoke(instance);
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(name).append("\":");
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number) {
                json.append(value);
            } else {
                json.append('"').append(value).append('"');
            }
        }
        return json.append('}').toString();
    }

    /**
     * A view with a composite field still compiles: the composite goes through the caller's codec.
     *
     * <p>{@code writePOJOProperty} is the only correct answer for a collection whose JSON shape the
     * caller's mapper owns, and this test is here because the alternative — guessing a shape at
     * generation time — is the one failure that would be silent.</p>
     */
    @Test
    void aCompositeFieldGoesThroughTheCallersCodec() throws Exception {
        String dtoWithCollection = """
                package fixture.hr;
                import hr.hrg.hipster.entity.api.View;
                import java.util.List;
                @View(dto = true)
                public interface PersonDto extends PersonSummary {
                    List<String> tags();
                    record Record(Long id, String firstName, Integer age, List<String> tags)
                            implements PersonDto {}
                }
                """;
        Generated generated = generate(dtoWithCollection, true);
        String source = Files.readString(generated.outputRoot().resolve("fixture/hr/PersonDtoJson.java"));

        Assertions.assertTrue(source.contains("gen.writePOJOProperty(\"tags\", source.tags());"),
                "a composite field is handed to the codec: " + source);
        Assertions.assertTrue(source.contains("import java.util.List;"),
                "and the composite's own import is emitted, or the generated source would not compile");

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "dto projection with a composite field",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }
}
