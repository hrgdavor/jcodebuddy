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
 * The module's central file index is the <strong>only</strong> place a source path is written, and the
 * ids it assigns are unique, deterministic and shared by every document (DEC-028).
 *
 * <p>Four properties, each of which the change would be worthless without:</p>
 *
 * <ol>
 *   <li><strong>A path appears once.</strong> Every document names its files by id; the paths live in
 *       {@code .jcodebuddy/index/files.json} and nowhere else. A path that leaks back into a document
 *       reintroduces exactly the repetition the index exists to remove, and — worse — gives a consumer a
 *       second source of truth that can disagree with the table.</li>
 *   <li><strong>An id resolves.</strong> Every {@code file}/{@code markerFile} value a document uses is a
 *       key in the table. An id with no row is a dead link the page cannot even diagnose.</li>
 *   <li><strong>Ids are deterministic and module-wide unique.</strong> Two passes over the same tree
 *       produce byte-identical tables, and the same file has the same id in every document — which is
 *       what lets a consumer correlate documents without a registry.</li>
 *   <li><strong>The disambiguation rule is exercised.</strong> The example declares three types named
 *       {@code Person} in three packages, so a bare simple name is not enough and the id must be
 *       qualified by the shortest package suffix that separates them.</li>
 * </ol>
 */
class MetadataFileIdsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> GENERATED_PACKAGES = List.of(
            "hr.hrg.hipster.entityexample.person.entity",
            "hr.hrg.hipster.entityexample.paymentMethod.entity");

    /** One module tree a pass ran in, with the index and documents it left behind. */
    private record Module(Path root, Path reportDir, Path indexFile, JsonNode index,
                          Map<String, String> files, Map<String, JsonNode> documents) {

        /** The `fileIndex` pointer of a document resolved against the document's own directory. */
        Path pointerTarget(String documentName) {
            String pointer = documents.get(documentName).path("fileIndex").asText();
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
        Path indexFile = tree.resolve(".jcodebuddy/index/files.json");
        Assertions.assertTrue(Files.exists(indexFile), "the pass must write " + indexFile);
        JsonNode index = MAPPER.readTree(Files.readString(indexFile));

        Map<String, String> files = new LinkedHashMap<>();
        for (var it = index.path("files").propertyStream().iterator(); it.hasNext(); ) {
            var entry = it.next();
            files.put(entry.getKey(), entry.getValue().asText());
        }

        Map<String, JsonNode> documents = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(reportDir)) {
            for (Path document : entries.filter(p -> p.toString().endsWith(".metadata.json")).toList()) {
                documents.put(document.getFileName().toString(),
                        MAPPER.readTree(Files.readString(document)));
            }
        }
        Assertions.assertFalse(documents.isEmpty(), "the pass must write documents");
        return new Module(tree, reportDir, indexFile, index, files, documents);
    }

    private static Module pass(Path parent, String name) throws Exception {
        Path tree = copyExample(parent, name);
        runPass(tree);
        return read(tree);
    }

    /** Every `file` value used by a document resolves in the index, and the index's header is right. */
    @Test
    void everyFileIdResolvesAndTheIndexDescribesItself() throws Exception {
        Path parent = Files.createTempDirectory("metadata-file-ids");
        Module module = pass(parent, "hipster-entity-example");

        Assertions.assertEquals(ModuleFileIndex.FORMAT, module.index().path("format").asInt(-1),
                "the table states the version of its own meaning, so a consumer can refuse an unknown one");
        Assertions.assertEquals("hipster-entity-example", module.index().path("module").asText(),
                "and the module it addresses, so it is self-describing when opened directly");
        Assertions.assertEquals("src/main/java", module.index().path("sourceRoot").asText(),
                "and the source root its paths are under");

        for (Map.Entry<String, JsonNode> document : module.documents().entrySet()) {
            for (String id : fileIdsIn(document.getValue())) {
                Assertions.assertTrue(module.files().containsKey(id),
                        document.getKey() + " uses the id " + id + ", which the index does not define: "
                                + module.indexFile());
            }
        }

        TreeSet<String> ids = new TreeSet<>(module.files().keySet());
        Assertions.assertEquals(module.files().size(), ids.size(), "every index key is a distinct id");
        Assertions.assertEquals(module.indexFile().getParent().resolve("files.json"), module.indexFile(),
                "the table is the index directory's addressing table");
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
                    "a document names files by id (DEC-028 section 3.2.2): the only path-like value it "
                            + "may carry is its `fileIndex` pointer");
        }

        // And the index really does carry every path the documents used to: at least one row per
        // referenced id, each one a module-relative path to a file that exists.
        Assertions.assertTrue(module.files().size() >= 30,
                "the example indexes far more files than it did locations: " + module.files().size());
        for (Map.Entry<String, String> entry : module.files().entrySet()) {
            Assertions.assertTrue(Files.isRegularFile(module.root().resolve(entry.getValue())),
                    entry.getKey() + " names an existing file: " + entry.getValue());
        }
    }

    /** Each document points at that same index, and the pointer resolves from the document's own directory. */
    @Test
    void everyDocumentPointersAtTheIndex() throws Exception {
        Path parent = Files.createTempDirectory("metadata-pointer");
        Module module = pass(parent, "hipster-entity-example");

        for (String document : module.documents().keySet()) {
            String pointer = module.documents().get(document).path("fileIndex").asText(null);
            Assertions.assertNotNull(pointer, document + " must carry a fileIndex pointer");
            Assertions.assertFalse(pointer.startsWith("/") || pointer.matches("^[A-Za-z]:.*"),
                    "the pointer is relative, so a document stays readable wherever the module is checked "
                            + "out: " + pointer);
            Assertions.assertEquals(module.indexFile().toAbsolutePath().normalize(),
                    module.pointerTarget(document),
                    document + "'s pointer must resolve to the module's index");
        }
    }

    /** Two passes over the same tree produce identical index bytes, and ids do not depend on the tree's location. */
    @Test
    void theIndexIsDeterministic() throws Exception {
        Path parent = Files.createTempDirectory("metadata-determinism");
        Module first = pass(parent.resolve("a"), "hipster-entity-example");
        byte[] before = Files.readAllBytes(first.indexFile());

        runPass(first.root());
        Assertions.assertArrayEquals(before, Files.readAllBytes(first.indexFile()),
                "a second pass over an unchanged tree must be byte-identical, or a project that commits "
                        + "its metadata gets diff noise from a pass that changed nothing");

        // A fresh copy in a different parent directory, with the same directory name: the ids are a
        // function of the paths and the SET of paths, so not one byte may depend on where the tree sits.
        Module second = pass(parent.resolve("b"), "hipster-entity-example");
        Assertions.assertArrayEquals(before, Files.readAllBytes(second.indexFile()),
                "the table must not depend on visit order or on the absolute location of the tree");
    }

    /** The disambiguation rule, on the three types the example happens to name `Person`. */
    @Test
    void sameNamedTypesAreQualifiedByTheShortestUniquePackageSuffix() throws Exception {
        Path parent = Files.createTempDirectory("metadata-disambiguation");
        Module module = pass(parent, "hipster-entity-example");

        Map<String, String> expected = Map.of(
                "entity.Person",
                "src/main/java/hr/hrg/hipster/entityexample/person/entity/Person.java",
                "iface.Person",
                "src/main/java/hr/hrg/hipster/entityexample/person/iface/Person.java",
                "record.Person",
                "src/main/java/hr/hrg/hipster/entityexample/person/record/Person.java");

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Assertions.assertEquals(entry.getValue(), module.files().get(entry.getKey()),
                    "three packages declare a `Person`, so the id is the shortest package suffix that "
                            + "separates them — and it is a function of the paths, never of visit order");
        }
        Assertions.assertFalse(module.files().containsKey("Person"),
                "a bare `Person` would be ambiguous, so it must not exist as an id");

        // The shortest suffix really is the shortest: nothing shorter than `entity.Person` separates the
        // three, and a fourth same-named type in another package would lengthen only that group.
        Assertions.assertEquals(3, module.files().keySet().stream()
                .filter(id -> id.endsWith(".Person") || id.equals("Person")).count(),
                "one id per same-named type, and no extra spellings of them");
    }

    /** Every key named `file`, and every string value that is not the pointer, checked for path-ness. */
    private static void walk(JsonNode node, String where, List<String> problems) {
        if (node.isObject()) {
            for (var it = node.properties().iterator(); it.hasNext(); ) {
                var entry = it.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if ("sourcePath".equals(key) || "markerSourcePath".equals(key)) {
                    problems.add(where + " carries the pre-DEC-028 key `" + key + "`");
                }
                if (("file".equals(key) || "markerFile".equals(key)) && value.isTextual()) {
                    String id = value.asText();
                    if (id.contains("/") || id.contains("\\") || id.endsWith(".java")) {
                        problems.add(where + "." + key + " is a path, not an id: " + id);
                    }
                }
                if (value.isTextual() && !"fileIndex".equals(key)
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

    /** Every id a document references, whatever key it appears under. */
    private static List<String> fileIdsIn(JsonNode document) {
        List<String> ids = new ArrayList<>();
        collect(document, ids);
        return ids;
    }

    private static void collect(JsonNode node, List<String> ids) {
        if (node.isObject()) {
            for (var it = node.properties().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (("file".equals(entry.getKey()) || "markerFile".equals(entry.getKey()))
                        && entry.getValue().isTextual()) {
                    ids.add(entry.getValue().asText());
                }
                collect(entry.getValue(), ids);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collect(element, ids);
            }
        }
    }

    /** Guard against a silently empty table: at least one row per document's own marker. */
    @Test
    void theIndexIsNotATruncatedTable() throws Exception {
        Path parent = Files.createTempDirectory("metadata-index-size");
        Module module = pass(parent, "hipster-entity-example");
        for (Map.Entry<String, JsonNode> document : module.documents().entrySet()) {
            String markerId = document.getValue().path("markerFile").asText(null);
            Assertions.assertNotNull(markerId, document.getKey() + " must name its marker by id");
            Assertions.assertTrue(module.files().containsKey(markerId),
                    "the marker's row is in the table: " + markerId);
        }
        Assertions.assertEquals(module.documents().size(), 3,
                "the example has three markers, and a pass writes one document for each");
        Assertions.assertEquals(new String(Files.readAllBytes(module.indexFile()), StandardCharsets.UTF_8)
                        .lines().findFirst().orElse(""), "{",
                "the table is a JSON object, and it is written with the same discipline as a document");
    }
}
