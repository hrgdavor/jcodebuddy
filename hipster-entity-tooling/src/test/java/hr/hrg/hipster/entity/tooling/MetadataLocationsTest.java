package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.ArtifactMeta;
import hr.hrg.hipster.entity.tooling.meta.EntityMeta;
import hr.hrg.hipster.entity.tooling.meta.ViewFieldMeta;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Every field of every view carries <strong>all</strong> its locations, and every location is true
 * (DEC-028).
 *
 * <p>The metadata used to record one location per field — the declaration start in the declaring
 * interface, annotations included — and nothing else. The constant in the generated field enum, the
 * record component, the field and setter in each builder and the two switch arms were not in the
 * metadata at all, so the only consumer that could answer "where is this field" was the Bun page, by
 * scanning the committed source and recognising members by line patterns. This test is what makes the
 * recorded answer a contract rather than a claim: it runs a real pass over a copy of the example and
 * asserts the roles that must be there, the ones that must not (a {@code DERIVED} field has no setter
 * anywhere), and that each recorded line really contains the member it names.</p>
 *
 * <p>The pass runs over a <strong>copy</strong> of the example with {@code --java-out} inside it, which
 * is the configuration the real pass uses: the generated artifacts must be under the module root for
 * the index to be able to name them, and a copy keeps the committed tree untouched.</p>
 */
class MetadataLocationsTest {

    private static final List<String> GENERATED_PACKAGES = List.of(
            "hr.hrg.hipster.entityexample.person.entity",
            "hr.hrg.hipster.entityexample.paymentMethod.entity");

    /** One pass's output: the module it ran in, the index it wrote, and the documents it produced. */
    private record Pass(Path moduleRoot, Map<String, String> files, Map<String, EntityMeta> markers) {

        ViewMeta view(String marker, String view) {
            EntityMeta meta = markers.get(marker);
            Assertions.assertNotNull(meta, "no document for marker " + marker);
            return meta.views().stream()
                    .filter(candidate -> candidate.name().equals(view))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no view " + view + " under " + marker));
        }

        ViewFieldMeta field(String marker, String view, String field) {
            return view(marker, view).fields().stream()
                    .filter(candidate -> candidate.name().equals(field))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no field " + view + "." + field));
        }

        ArtifactMeta artifact(String marker, String view, String name) {
            return view(marker, view).artifacts().stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no artifact " + name + " in " + view));
        }

        String read(String moduleRelativePath) throws Exception {
            return new String(Files.readAllBytes(moduleRoot.resolve(moduleRelativePath)),
                    StandardCharsets.UTF_8);
        }

        /** The line {@code lineNumber} of a module-relative file, or null when it is outside it. */
        String line(String moduleRelativePath, int lineNumber) throws Exception {
            List<String> lines = List.of(read(moduleRelativePath).split("\\R", -1));
            if (lineNumber < 1 || lineNumber > lines.size()) {
                return null;
            }
            return lines.get(lineNumber - 1);
        }
    }

    /**
     * Copies the committed example into a temp module and runs a real pass in place there.
     *
     * <p>In place is the configuration DEC-028 is about: the generated siblings must sit beside the view
     * so the pass can read them back and record where each field landed inside them.</p>
     */
    private static Pass run() throws Exception {
        Path exampleModule = CompileHarness.findRepoRoot().resolve("hipster-entity-example");
        Path exampleRoot = exampleModule.resolve("src/main/java");
        Path tree = Files.createTempDirectory("metadata-locations");
        for (Path source : CompileHarness.javaSourcesUnder(exampleRoot)) {
            // Relativised against the MODULE, so the copy keeps the `src/main/java/…` layout the recorded
            // paths are written in.
            Path target = tree.resolve(exampleModule.relativize(source).toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        // A module has a `.jcodebuddy/`; creating one is what makes the module root — and therefore the
        // index location — the temp tree rather than whatever directory happens to be above it.
        Path reportDir = tree.resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(reportDir);

        EntityMetadataGenerator.setGenerationPackages(GENERATED_PACKAGES);
        EntityMetadataGenerator.setGenerateAdapters(false);
        try {
            EntityMetadataGenerator.generate(tree.resolve("src/main/java"), reportDir,
                    tree.resolve("src/main/java"));
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
            EntityMetadataGenerator.setGenerateAdapters(false);
        }

        Map<String, String> files = new LinkedHashMap<>();
        // The index lands in the MODULE's `.jcodebuddy/index/`, beside `metadata/` — not inside the
        // report directory, which is the layout the real pass produces (DEC-026/DEC-028).
        Path indexFile = tree.resolve(".jcodebuddy/index/files.json");
        Assertions.assertTrue(Files.exists(indexFile), "the pass must write " + indexFile);
        tools.jackson.databind.JsonNode index = new tools.jackson.databind.ObjectMapper()
                .readTree(Files.readString(indexFile));
        for (var it = index.path("files").propertyStream().iterator(); it.hasNext(); ) {
            var entry = it.next();
            files.put(entry.getKey(), entry.getValue().asText());
        }

        Map<String, EntityMeta> markers = new LinkedHashMap<>();
        try (Stream<Path> documents = Files.list(reportDir)) {
            for (Path document : documents.filter(p -> p.toString().endsWith(".metadata.json")).toList()) {
                EntityMeta meta = EntityMetadataGenerator.fromJson(Files.readString(document), files);
                markers.put(meta.entityName(), meta);
            }
        }
        Assertions.assertFalse(markers.isEmpty(), "the pass must write at least one document");
        return new Pass(tree, files, markers);
    }

    /**
     * A {@code DERIVED} field carries every role it has, and none it does not.
     *
     * <p>{@code PersonSummary.age} is the richest case: an annotated, non-writable field of a
     * {@code BUILDER_ALL} view with a nested record. It has an accessor and a {@code @FieldSource} line in
     * the view, a record component, an enum constant and a name lookup, a stored field, a getter and an
     * ordinal arm in both builders — and <strong>no setter anywhere</strong>, because a derived value
     * cannot be written.</p>
     */
    @Test
    void aDerivedFieldCarriesEveryRoleItHasAndNoSetter() throws Exception {
        Pass pass = run();
        ViewFieldMeta age = pass.field("Person", "PersonSummary", "age");

        ArtifactMeta view = pass.artifact("Person", "PersonSummary", "PersonSummary");
        ArtifactMeta record = pass.artifact("Person", "PersonSummary", "PersonSummary.Record");
        ArtifactMeta fieldEnum = pass.artifact("Person", "PersonSummary", "PersonSummary_");
        ArtifactMeta builder = pass.artifact("Person", "PersonSummary", "PersonSummaryBuilder");
        ArtifactMeta tracking = pass.artifact("Person", "PersonSummary", "PersonSummaryBuilderTracking");

        Assertions.assertEquals(17, age.lineAt(view.id(), "accessor"),
                "the accessor's own line, not the declaration start");
        Assertions.assertEquals(16, age.lineAt(view.id(), "annotation"),
                "and the @FieldSource line above it, which is a different line");
        Assertions.assertEquals(30, age.lineAt(record.id(), "record-component"),
                "the component of the nested record");
        Assertions.assertTrue(age.lineAt(fieldEnum.id(), "enum-constant") > 0, "the ledger constant");
        Assertions.assertTrue(age.lineAt(fieldEnum.id(), "name-slot") > 0, "the forName arm");

        for (ArtifactMeta artifact : List.of(builder, tracking)) {
            Assertions.assertTrue(age.lineAt(artifact.id(), "field") > 0,
                    artifact.name() + " stores the field");
            Assertions.assertTrue(age.lineAt(artifact.id(), "accessor") > 0,
                    artifact.name() + " reads it");
            Assertions.assertTrue(age.lineAt(artifact.id(), "ordinal-slot") > 0,
                    artifact.name() + " maps its ordinal");
            Assertions.assertEquals(-1, age.lineAt(artifact.id(), "setter"),
                    "a DERIVED field has no setter in " + artifact.name()
                            + " — reporting a line for one would be a lie");
        }
    }

    /**
     * An inherited field records its accessor in the interface that <em>declares</em> it.
     *
     * <p>{@code PersonDetails} declares two accessors and inherits the rest, so its field map has to name
     * a file other than its own — which is exactly the fact no view-relative path can express.</p>
     */
    @Test
    void anInheritedFieldRecordsTheDeclaringInterface() throws Exception {
        Pass pass = run();
        ViewMeta details = pass.view("Person", "PersonDetails");
        ViewFieldMeta firstName = pass.field("Person", "PersonDetails", "firstName");

        ArtifactMeta declaring = details.artifacts().stream()
                .filter(artifact -> artifact.name().equals("Person"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the declaring interface must be listed as a foreign artifact, or the accessor "
                                + "location has nothing to key on"));
        Assertions.assertFalse(declaring.own(), "Person is not an artifact of PersonDetails");
        Assertions.assertTrue(declaring.file().endsWith("person/entity/Person.java"),
                "and it names the file that declares the accessor: " + declaring.file());
        Assertions.assertEquals(15, firstName.lineAt(declaring.id(), "accessor"),
                "firstName's accessor lives in Person, at line 15");
    }

    /**
     * The same field name in two views has different locations, because each view has its own enum.
     *
     * <p>This is the assertion that catches a location map keyed by field name only. The example makes it
     * sharp: {@code PersonDto} extends {@code PersonSummary}, and the two field enums have the same shape,
     * so {@code age}'s constant is at line <strong>43 in both</strong>. A map keyed by name alone would
     * therefore look right — the line number would even match — while pointing every consumer at the
     * wrong <em>file</em>. The location is the pair, and the pair differs.</p>
     */
    @Test
    void theSameFieldNameHasADifferentLocationPerView() throws Exception {
        Pass pass = run();
        String summaryFile = pass.artifact("Person", "PersonSummary", "PersonSummary_").file();
        String dtoFile = pass.artifact("Person", "PersonDto", "PersonDto_").file();
        Assertions.assertNotEquals(summaryFile, dtoFile,
                "each view has its own field enum file");

        Assertions.assertEquals(43, enumConstantLine(pass, "PersonSummary", "age"),
                "and in this example the two enums are the same shape, so the LINES coincide");
        Assertions.assertEquals(43, enumConstantLine(pass, "PersonDto", "age"));
        Assertions.assertNotEquals(
                summaryFile + ":" + enumConstantLine(pass, "PersonSummary", "age"),
                dtoFile + ":" + enumConstantLine(pass, "PersonDto", "age"),
                "so the recorded location is a file and a line, and the two views' locations differ");
    }

    private static int enumConstantLine(Pass pass, String viewName, String fieldName) {
        ViewMeta view = pass.view("Person", viewName);
        ViewFieldMeta field = pass.field("Person", viewName, fieldName);
        ArtifactMeta fieldEnum = pass.artifact("Person", viewName, viewName + "_");
        return field.lineAt(fieldEnum.id(), "enum-constant");
    }

    /**
     * Every recorded location is true: the file exists and the line contains the member.
     *
     * <p>A line number is the one part of this metadata no compiler checks, and a stale one is worse than
     * a missing one — it opens the wrong line and looks like it worked. The page verifies every link it
     * renders; this asserts the same property at the source, so a location that could never verify is
     * caught by the Java gate rather than only by the renderer.</p>
     */
    @Test
    void everyRecordedLocationIsTrue() throws Exception {
        Pass pass = run();
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (EntityMeta meta : pass.markers().values()) {
            for (ViewMeta view : meta.views()) {
                Map<Integer, ArtifactMeta> byId = new LinkedHashMap<>();
                for (ArtifactMeta artifact : view.artifacts()) {
                    byId.put(artifact.id(), artifact);
                    Assertions.assertTrue(Files.isRegularFile(pass.moduleRoot().resolve(artifact.file())),
                            "artifact " + artifact.name() + " names an existing file: " + artifact.file());
                }
                for (ViewFieldMeta field : view.fields()) {
                    for (Map.Entry<Integer, Map<String, Integer>> entry : field.at().entrySet()) {
                        ArtifactMeta artifact = byId.get(entry.getKey());
                        if (artifact == null) {
                            problems.add(view.name() + "." + field.name() + ": at key " + entry.getKey()
                                    + " is not an artifact of the view");
                            continue;
                        }
                        for (Map.Entry<String, Integer> role : entry.getValue().entrySet()) {
                            checked++;
                            String text = pass.line(artifact.file(), role.getValue());
                            if (text == null) {
                                problems.add(view.name() + "." + field.name() + " " + role.getKey()
                                        + ": " + artifact.file() + ":" + role.getValue()
                                        + " is outside the file");
                            } else if ("annotation".equals(role.getKey())) {
                                if (!text.contains("@FieldSource")) {
                                    problems.add(view.name() + "." + field.name() + " annotation: "
                                            + artifact.file() + ":" + role.getValue()
                                            + " is not a @FieldSource line");
                                }
                            } else if (!containsIdentifier(text, field.name())) {
                                problems.add(view.name() + "." + field.name() + " " + role.getKey() + ": "
                                        + artifact.file() + ":" + role.getValue()
                                        + " does not contain " + field.name() + ": " + text.trim());
                            }
                        }
                    }
                }
            }
        }

        Assertions.assertEquals("", String.join("; ", problems));
        Assertions.assertTrue(checked >= 200,
                "the example records hundreds of locations; checked " + checked);
    }

    /** Whether {@code text} contains {@code word} as a whole Java identifier. */
    private static boolean containsIdentifier(String text, String word) {
        return java.util.regex.Pattern.compile("(?<![\\w$])" + java.util.regex.Pattern.quote(word)
                + "(?![\\w$])").matcher(text).find();
    }
}
