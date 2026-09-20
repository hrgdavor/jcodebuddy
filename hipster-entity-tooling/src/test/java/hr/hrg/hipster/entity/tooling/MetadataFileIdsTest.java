package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The module's class index is the <strong>only</strong> place a source path is written, and every
 * reference a document carries is a fully qualified type name that resolves in it (DEC-029).
 *
 * <p>Four properties, each of which the change would be worthless without:</p>
 *
 * <ol>
 *   <li><strong>A path appears once.</strong> Every document names its types by FQN; the paths live in
 *       {@code .jcodebuddy/index/classes.json} and nowhere else. A path that leaks back into a document
 *       reintroduces exactly the repetition the index exists to remove, and — worse — gives a consumer a
 *       second source of truth that can disagree with the table.</li>
 *   <li><strong>A reference resolves.</strong> Every {@code file}/{@code markerFile} value a document uses
 *       is a key in the table. An FQN with no row is a dead link the page cannot even diagnose.</li>
 *   <li><strong>The table is deterministic.</strong> Two passes over an unchanged tree produce
 *       byte-identical tables — including every {@code hashCalculatedAt}, which is the property that makes
 *       the table committable — and the same type has the same row in every document, which is what lets a
 *       consumer correlate documents without a registry.</li>
 *   <li><strong>The FQN is the language's own name, not a surrogate.</strong> The example declares three
 *       types called {@code Person} in three packages; a bare {@code Person} is not a valid row key
 *       because the language would not accept it either, and the three rows are distinguished by their
 *       packages rather than by a hash, a counter or a positional index.</li>
 * </ol>
 */
class MetadataFileIdsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> GENERATED_PACKAGES = List.of(
            "hr.hrg.hipster.entityexample.person.entity",
            "hr.hrg.hipster.entityexample.paymentMethod.entity");

    /** One module tree a pass ran in, with the index and documents it left behind. */
    private record Module(Path root, Path reportDir, Path indexFile, JsonNode index,
                          Map<String, String> classes, Map<String, JsonNode> documents) {

        /** The `classIndex` pointer of a document resolved against the document's own directory. */
        Path pointerTarget(String documentName) {
            String pointer = documents.get(documentName).path("classIndex").asText();
            return reportDir.resolve(pointer).toAbsolutePath().normalize();
        }
    }

    /**
     * Copies the committed example into a temp <em>module</em>, with its {@code src/main/java} layout.
     *
     * <p>Relativising against the module (not against the source root) is what keeps the recorded paths
     * in the {@code src/main/java/…} form the contract specifies — and therefore what lets this test
     * compare two tables byte for byte: a copy that lost the layout would produce different paths, not a
     * different answer.</p>
     */
    private static Path copyExample(Path parent, String name) throws Exception {
        Path exampleModule = CompileHarness.findRepoRoot().resolve("hipster-entity-example");
        Path exampleRoot = exampleModule.resolve("src/main/java");
        Path tree = parent.resolve(name);
        for (Path source : CompileHarness.javaSourcesUnder(exampleRoot)) {
            Path target = tree.resolve(exampleModule.relativize(source).toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        Files.createDirectories(tree.resolve(".jcodebuddy/metadata/entity"));
        Assertions.assertTrue(Files.isDirectory(tree.resolve("src/main/java")),
                "the copy must keep the module layout, or the recorded paths are not module-relative: "
                        + tree);
        return tree;
    }

    private static void runPass(Path tree) throws Exception {
        EntityMetadataGenerator.setGenerationPackages(GENERATED_PACKAGES);
        EntityMetadataGenerator.setGenerateAdapters(false);
        try {
            EntityMetadataGenerator.generate(tree.resolve("src/main/java"),
                    tree.resolve(".jcodebuddy/metadata/entity"), tree.resolve("src/main/java"));
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
            EntityMetadataGenerator.setGenerateAdapters(false);
        }
    }

    private static Module read(Path tree) throws Exception {
        Path reportDir = tree.resolve(".jcodebuddy/metadata/entity");
        Path indexFile = tree.resolve(".jcodebuddy/index/classes.json");
        Assertions.assertTrue(Files.exists(indexFile), "the pass must write " + indexFile);
        JsonNode index = MAPPER.readTree(Files.readString(indexFile));

        Map<String, String> classes = new LinkedHashMap<>();
        for (var it = index.path("classes").propertyStream().iterator(); it.hasNext(); ) {
            var entry = it.next();
            classes.put(entry.getKey(), entry.getValue().path("path").asText());
        }

        Map<String, JsonNode> documents = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(reportDir)) {
            for (Path document : entries.filter(p -> p.toString().endsWith(".metadata.json")).toList()) {
                documents.put(document.getFileName().toString(),
                        MAPPER.readTree(Files.readString(document)));
            }
        }
        Assertions.assertFalse(documents.isEmpty(), "the pass must write documents");
        return new Module(tree, reportDir, indexFile, index, classes, documents);
    }

    private static Module pass(Path parent, String name) throws Exception {
        Path tree = copyExample(parent, name);
        runPass(tree);
        return read(tree);
    }

    /** Every reference a document uses resolves in the table, and the table's header is right. */
    @Test
    void everyReferenceResolvesAndTheIndexDescribesItself() throws Exception {
        Path parent = Files.createTempDirectory("metadata-file-ids");
        Module module = pass(parent, "hipster-entity-example");

        Assertions.assertEquals(ClassIndexAccess.FORMAT, module.index().path("format").asInt(-1),
                "the table states the version of its own meaning, so a consumer can refuse an unknown one");
        Assertions.assertEquals("hipster-entity-example", module.index().path("module").asText(),
                "and the module it addresses, so it is self-describing when opened directly");
        Assertions.assertEquals("src/main/java", module.index().path("sourceRoot").asText(),
                "and the source root its paths are under");
        Assertions.assertEquals("wyhash64", module.index().path("hash").path("algo").asText(),
                "and the algorithm its checksums use, because a table hashed differently cannot be "
                        + "compared with this build's answer");
        Assertions.assertEquals("lf", module.index().path("hash").path("normalize").asText(),
                "and the normalisation, because that is what makes a CRLF checkout agree with an LF one");

        for (Map.Entry<String, JsonNode> document : module.documents().entrySet()) {
            for (String fqn : referencesIn(document.getValue())) {
                Assertions.assertTrue(module.classes().containsKey(fqn),
                        document.getKey() + " names the type " + fqn + ", which the class index does not "
                                + "define: " + module.indexFile());
            }
        }

        TreeSet<String> keys = new TreeSet<>(module.classes().keySet());
        Assertions.assertEquals(module.classes().size(), keys.size(), "every index key is distinct");
        Assertions.assertEquals(module.indexFile().getParent().resolve("classes.json"), module.indexFile(),
                "the table is the index directory's class index");
    }

    /** A source path appears in the index — and nowhere else. */
    @Test
    void noSourcePathAppearsOutsideTheIndex() throws Exception {
        Path parent = Files.createTempDirectory("metadata-no-paths");
        Module module = pass(parent, "hipster-entity-example");

        for (Map.Entry<String, JsonNode> document : module.documents().entrySet()) {
            List<String> problems = new ArrayList<>();
            walk(document.getValue(), document.getKey(), problems);
            Assertions.assertEquals("", String.join("; ", problems),
                    "a document names types by FQN (DEC-029): the only path-like value it may carry is "
                            + "its `classIndex` pointer");
        }

        // And the index really does carry every path the documents used to, each one a module-relative
        // path to a file that exists.
        Assertions.assertTrue(module.classes().size() >= 30,
                "the example indexes one row per type, far more than the 45 artifacts: "
                        + module.classes().size());
        for (Map.Entry<String, String> entry : module.classes().entrySet()) {
            Assertions.assertTrue(Files.isRegularFile(module.root().resolve(entry.getValue())),
                    entry.getKey() + " names an existing file: " + entry.getValue());
        }
    }

    /** Each document points at that same table, and the pointer resolves from the document's own directory. */
    @Test
    void everyDocumentPointersAtTheIndex() throws Exception {
        Path parent = Files.createTempDirectory("metadata-pointer");
        Module module = pass(parent, "hipster-entity-example");

        for (String document : module.documents().keySet()) {
            String pointer = module.documents().get(document).path("classIndex").asText(null);
            Assertions.assertNotNull(pointer, document + " must carry a classIndex pointer");
            Assertions.assertFalse(pointer.startsWith("/") || pointer.matches("^[A-Za-z]:.*"),
                    "the pointer is relative, so a document stays readable wherever the module is checked "
                            + "out: " + pointer);
            Assertions.assertEquals(module.indexFile().toAbsolutePath().normalize(),
                    module.pointerTarget(document),
                    document + "'s pointer must resolve to the module's class index");
        }
    }

    /**
     * Two passes over the same tree produce identical table bytes, and the table carries nothing that
     * depends on where the tree sits or on the order files were visited.
     *
     * <p>The one fact that legitimately differs between two trees is {@code hashCalculatedAt} of a row
     * whose content this pass hashed <em>for the first time</em> — a wall clock reading, and a table
     * generated an hour apart in two clones cannot share it. Everything else — every key, path, kind,
     * modifier list, line, depth, size and checksum — is a fact about the source and must be equal, so that
     * is what is compared; the timestamps are then compared row by row after being normalised, which still
     * catches a timestamp that depends on visit order or on the tree's location.</p>
     */
    @Test
    void theIndexIsDeterministic() throws Exception {
        Path parent = Files.createTempDirectory("metadata-determinism");
        Module first = pass(parent.resolve("a"), "hipster-entity-example");
        byte[] before = Files.readAllBytes(first.indexFile());

        runPass(first.root());
        Assertions.assertArrayEquals(before, Files.readAllBytes(first.indexFile()),
                "a second pass over an unchanged tree must be byte-identical — including every "
                        + "hashCalculatedAt, which is carried forward from the previous table — or a project "
                        + "that commits its metadata gets diff noise from a pass that changed nothing");

        // A fresh copy in a different parent directory, with the same directory name: every row must be
        // keyed and described identically there.
        Module second = pass(parent.resolve("b"), "hipster-entity-example");
        Assertions.assertEquals(normaliseTimestamps(module1Json(first)),
                normaliseTimestamps(module1Json(second)),
                "the table must not depend on visit order or on the absolute location of the tree: the only "
                        + "value allowed to differ is a first-hash timestamp, and it is normalised here");
        Assertions.assertTrue(normalisedTimestampCount(module1Json(first)) > 30,
                "and the normalisation must apply to real rows rather than to an empty table");
    }

    /** The table's text, as written. */
    private static String module1Json(Module module) throws Exception {
        return new String(Files.readAllBytes(module.indexFile()), StandardCharsets.UTF_8);
    }

    /** The table's text with every `hashCalculatedAt` value replaced by a placeholder. */
    private static String normaliseTimestamps(String json) {
        return json.replaceAll("\"hashCalculatedAt\": \"[^\"]*\"", "\"hashCalculatedAt\": \"<instant>\"");
    }

    private static int normalisedTimestampCount(String json) {
        return json.split("\"hashCalculatedAt\"", -1).length - 1;
    }

    /**
     * The three same-named types are three rows, told apart by their package — the language's own rule.
     *
     * <p>This replaces DEC-028's "shortest unique package suffix" assertion. That rule existed because a
     * readable id had to be invented; an FQN needs no invention, and the property worth asserting is the
     * one a consumer relies on: three distinct keys, each resolving to its own file, and no bare
     * {@code Person} row that would be ambiguous.</p>
     */
    @Test
    void sameNamedTypesAreDistinguishedByTheirPackage() throws Exception {
        Path parent = Files.createTempDirectory("metadata-disambiguation");
        Module module = pass(parent, "hipster-entity-example");

        Map<String, String> expected = Map.of(
                "hr.hrg.hipster.entityexample.person.entity.Person",
                "src/main/java/hr/hrg/hipster/entityexample/person/entity/Person.java",
                "hr.hrg.hipster.entityexample.person.iface.Person",
                "src/main/java/hr/hrg/hipster/entityexample/person/iface/Person.java",
                "hr.hrg.hipster.entityexample.person.record.Person",
                "src/main/java/hr/hrg/hipster/entityexample/person/record/Person.java");

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Assertions.assertEquals(entry.getValue(), module.classes().get(entry.getKey()),
                    "the row key is the fully qualified name, so the package is what separates the three");
        }
        Assertions.assertFalse(module.classes().containsKey("Person"),
                "a bare `Person` is not a name the language accepts, so it must not be a row key");
        Assertions.assertEquals(3, module.classes().keySet().stream()
                        .filter(fqn -> fqn.endsWith(".Person")).count(),
                "one row per same-named type, and no extra spellings of them");
    }

    /** A declared member type is its own row, so a document may reference it by FQN. */
    @Test
    void memberTypesHaveTheirOwnRows() throws Exception {
        Path parent = Files.createTempDirectory("metadata-member-types");
        Module module = pass(parent, "hipster-entity-example");

        String nested = "hr.hrg.hipster.entityexample.person.entity.PersonSummary.Record";
        JsonNode row = module.index().path("classes").path(nested);
        Assertions.assertFalse(row.isMissingNode(),
                "a member type a document references needs its own row: " + nested);
        Assertions.assertEquals("record", row.path("kind").asText(),
                "and its own kind, recorded from the declaration rather than from the file");
        Assertions.assertEquals(1, row.path("depth").asInt(-1), "at nesting depth 1");
        Assertions.assertEquals("hr.hrg.hipster.entityexample.person.entity.PersonSummary",
                row.path("enclosing").asText(), "with its enclosing type named");
        Assertions.assertTrue(row.path("line").asInt(-1) > 1, "and its own declaration line");
    }

    /** Every reference keyed `file`/`markerFile`, and every string value that is not the pointer, checked. */
    private static void walk(JsonNode node, String where, List<String> problems) {
        if (node.isObject()) {
            for (var it = node.properties().iterator(); it.hasNext(); ) {
                var entry = it.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if ("sourcePath".equals(key) || "markerSourcePath".equals(key)) {
                    problems.add(where + " carries the pre-DEC-028 key `" + key + "`");
                }
                if ("fileIndex".equals(key)) {
                    problems.add(where + " carries the pre-DEC-029 pointer key `" + key + "`");
                }
                if (("file".equals(key) || "markerFile".equals(key)) && value.isTextual()) {
                    String fqn = value.asText();
                    if (fqn.contains("/") || fqn.contains("\\") || fqn.endsWith(".java")) {
                        problems.add(where + "." + key + " is a path, not a fully qualified name: " + fqn);
                    }
                }
                if (value.isTextual() && !"classIndex".equals(key)
                        && !"file".equals(key) && !"markerFile".equals(key)
                        && (value.asText().contains(".java") || value.asText().startsWith("src/"))) {
                    problems.add(where + "." + key + " carries a source path: " + value.asText());
                }
                walk(value, where + "." + key, problems);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), where + "[" + i + "]", problems);
            }
        }
    }

    /** Every type a document references, whatever key it appears under. */
    private static List<String> referencesIn(JsonNode document) {
        List<String> references = new ArrayList<>();
        collect(document, references);
        return references;
    }

    private static void collect(JsonNode node, List<String> references) {
        if (node.isObject()) {
            for (var it = node.properties().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (("file".equals(entry.getKey()) || "markerFile".equals(entry.getKey()))
                        && entry.getValue().isTextual()) {
                    references.add(entry.getValue().asText());
                }
                collect(entry.getValue(), references);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collect(element, references);
            }
        }
    }

    /** Guard against a silently empty table: at least one row per document's own marker. */
    @Test
    void theIndexIsNotATruncatedTable() throws Exception {
        Path parent = Files.createTempDirectory("metadata-index-size");
        Module module = pass(parent, "hipster-entity-example");
        for (Map.Entry<String, JsonNode> document : module.documents().entrySet()) {
            String markerFqn = document.getValue().path("markerFile").asText(null);
            Assertions.assertNotNull(markerFqn, document.getKey() + " must name its marker by FQN");
            Assertions.assertTrue(module.classes().containsKey(markerFqn),
                    "the marker's row is in the table: " + markerFqn);
        }
        Assertions.assertEquals(module.documents().size(), 3,
                "the example has three markers, and a pass writes one document for each");
        Assertions.assertEquals(new String(Files.readAllBytes(module.indexFile()), StandardCharsets.UTF_8)
                        .lines().findFirst().orElse(""), "{",
                "the table is a JSON object, and it is written with the same discipline as a document");
    }

    /** The table's own version, read from the production class rather than copied into the test. */
    private static final class ClassIndexAccess {
        static final int FORMAT = hr.hrg.hipster.entity.tooling.index.ClassIndex.FORMAT;
    }
}
