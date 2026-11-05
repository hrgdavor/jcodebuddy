package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.EntityMeta;
import org.junit.jupiter.api.Assertions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One shared generator pass over a temporary copy of the committed
 * {@code hipster-entity-example} module, for the tests that each used to copy the example and run
 * a full pass once <em>per test method</em>.
 *
 * <p>Sharing the pass is what makes those classes affordable: the copy and a full
 * {@link EntityMetadataGenerator#generate(Path, Path, Path, DivergenceReporter) pass} cost seconds,
 * while the assertions over the result cost milliseconds. A class asks for the fixture once in
 * {@code @BeforeAll} and every test reads the same output.</p>
 *
 * <h2>The copy</h2>
 *
 * <p>The module is copied with its module-relative layout — {@code src/main/java/…} and a
 * {@code .jcodebuddy/metadata/entity} report directory — because that is what the recorded paths and
 * the class-index location are written in: a copy that lost the layout would produce different
 * metadata, not a different answer. Generated source is emitted <strong>in place</strong> under
 * {@code src/main/java}, which is the configuration the plan specifies (§ 4.1/S2) and the only one in
 * which cooperative codegen can recognise the previous revision of the file it is about to replace.
 * The module directory is named {@value #MODULE_NAME}, so the class index is self-describing exactly
 * as the committed one is.</p>
 *
 * <h2>Read-only by contract</h2>
 *
 * <p>A fixture returned by {@link #create()} is <strong>read-only</strong>: its tree is produced once
 * and no test may write to it. The two escape hatches return a new, independent fixture:</p>
 * <ul>
 *   <li>{@link #freshCopy()} — a brand-new copy of the committed example with a first pass run, for a
 *       test that mutates the tree (a rename, a deliberate edit) and regenerates afterwards. The shared
 *       tree is never touched, so one class cannot contaminate another or itself.</li>
 *   <li>{@link #secondPass()} — a copy of <em>this fixture's post-pass tree</em> that is passed over a
 *       second time, for the determinism and idempotence tests. The copy carries the first pass's
 *       {@code .jcodebuddy/index} with it, so a row whose content did not change keeps its
 *       {@code hashCalculatedAt}; that is the property those tests assert.</li>
 * </ul>
 *
 * <h2>Generator configuration</h2>
 *
 * <p>The pass is the one the five converted classes share: the example's two entity packages, the
 * default (non-{@code --adapters}) SQL setting, no mapper/merge/DTO/patch requests, validation off and
 * cooperative-codegen force mode off. Every one of those is a process-global static, so each pass
 * <strong>sets and restores the whole surface</strong> in a {@code finally}: a preceding test class can
 * leave any of them changed, and a fixture that reset only the two flags its own class used would
 * silently run a different pass depending on test order.</p>
 */
final class ExamplePassFixture {

    /** The copied module's directory name; the class index records it as the module it addresses. */
    static final String MODULE_NAME = "hipster-entity-example";

    /** The packages the example's pass generates, as {@code scripts/gen.js} and the tests pass them. */
    private static final List<String> GENERATED_PACKAGES = List.of(
            "hr.hrg.hipster.entityexample.person.entity",
            "hr.hrg.hipster.entityexample.paymentMethod.entity");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path parent;
    private final Path moduleRoot;
    private final Path sourceRoot;
    private final Path reportDir;
    private final Path indexFile;

    private DivergenceReporter divergences;
    private JsonNode index;
    private Map<String, String> classes = Map.of();
    private Map<String, JsonNode> documents = Map.of();
    private Map<String, EntityMeta> markers = Map.of();

    private ExamplePassFixture(Path parent, Path moduleRoot) {
        this.parent = parent;
        this.moduleRoot = moduleRoot;
        this.sourceRoot = moduleRoot.resolve("src/main/java");
        this.reportDir = moduleRoot.resolve(".jcodebuddy/metadata/entity");
        this.indexFile = moduleRoot.resolve(".jcodebuddy/index/classes.json");
    }

    /** The committed example module the fixtures copy from. */
    static Path committedModule() {
        return CompileHarness.findRepoRoot().resolve("hipster-entity/hipster-entity-example");
    }

    /** A new read-only fixture: a fresh copy of the committed example with one full pass run. */
    static ExamplePassFixture create() throws IOException {
        return copyCommittedExample();
    }

    /**
     * The JVM-wide read-only fixture, created once and shared by every class that only reads it.
     *
     * <p>Five classes used to copy and pass the example independently; each copy costs a full pass, and the
     * assertions read the result. A single lazy fixture per test JVM removes that repeated work without
     * changing isolation: the tree is read-only, and the two tests that need another pass or a mutation call
     * {@link #secondPass()} or {@link #freshCopy()}, which are independent copies.</p>
     *
     * <p>Surefire's fork lane gives each fork its own statics, so two forks do not share a tree.</p>
     */
    static synchronized ExamplePassFixture shared() throws IOException {
        if (SHARED == null) {
            SHARED = copyCommittedExample();
        }
        return SHARED;
    }

    private static ExamplePassFixture SHARED;

    /**
     * A new, independent fixture for a test that mutates the tree: the committed example is copied
     * again and one pass is run, so {@link #runPass()} may later be called over the mutated tree
     * without the shared fixture ever being written.
     */
    ExamplePassFixture freshCopy() throws IOException {
        return copyCommittedExample();
    }

    /**
     * An independent copy of this fixture's post-pass tree, <strong>without</strong> running another pass.
     *
     * <p>For a test that needs to mutate the tree and then regenerate: the copied report/index is the
     * {@code before} state, so a second pass is the only one needed. {@link #freshCopy()} is the form for a
     * test that needs a pass before it starts mutating.</p>
     */
    ExamplePassFixture copyOfShared() throws IOException {
        Path parent = Files.createTempDirectory("example-pass-copy");
        Path module = parent.resolve(MODULE_NAME);
        copyTree(moduleRoot, module);
        return new ExamplePassFixture(parent, module);
    }

    /**
     * A second pass over an independent copy of this fixture's post-pass tree.
     *
     * <p>The tree — source, generated output and the first pass's {@code .jcodebuddy/index} — is
     * copied as it stands, then passed over once more. This is the second-pass form the determinism
     * and idempotence tests need, and it never writes the fixture it is asked from.</p>
     */
    ExamplePassFixture secondPass() throws IOException {
        Path secondParent = Files.createTempDirectory("example-pass-second");
        Path secondModule = secondParent.resolve(MODULE_NAME);
        copyTree(moduleRoot, secondModule);
        ExamplePassFixture second = new ExamplePassFixture(secondParent, secondModule);
        second.runPass();
        return second;
    }

    private static ExamplePassFixture copyCommittedExample() throws IOException {
        Path committed = committedModule();
        Path committedSource = committed.resolve("src/main/java");
        Assertions.assertTrue(Files.isDirectory(committedSource),
                "the committed example source root must exist: " + committedSource);

        Path tempParent = Files.createTempDirectory("example-pass");
        Path module = tempParent.resolve(MODULE_NAME);
        for (Path source : CompileHarness.javaSourcesUnder(committedSource)) {
            // Relativised against the MODULE, not the source root: the `src/main/java/…` layout is what
            // the recorded paths are written in, and the index's sourceRoot is read back from it.
            Path target = module.resolve(committed.relativize(source).toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        // A module has a `.jcodebuddy/`; creating the report directory is what makes the module root —
        // and therefore the index location and the recorded paths' base — the temp tree.
        Files.createDirectories(module.resolve(".jcodebuddy/metadata/entity"));

        ExamplePassFixture fixture = new ExamplePassFixture(tempParent, module);
        fixture.runPass();
        return fixture;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }

    /**
     * Runs the shared pass again over this fixture's tree and re-reads everything it wrote.
     *
     * <p>Only a fixture obtained from {@link #freshCopy()} may call this after a test has mutated the
     * tree; on a shared fixture the second pass is {@link #secondPass()}, which works on a copy.</p>
     */
    void runPass() throws IOException {
        DivergenceReporter passDivergences = new DivergenceReporter();
        EntityMetadataGenerator.setGenerationPackages(GENERATED_PACKAGES);
        // The example deliberately does NOT enable SQL generation: the generated adapter pair is an
        // opt-in draft, and the committed example contains no such class.
        EntityMetadataGenerator.setGenerateAdapters(false);
        // The rest of the process-global surface is forced to the same known state. A preceding class
        // could have left any of these on, and the shared pass must not depend on test order.
        EntityMetadataGenerator.setGenerateDtoProjections(false);
        EntityMetadataGenerator.setGeneratePatchAppliers(false);
        EntityMetadataGenerator.setMapperRequests(List.of());
        EntityMetadataGenerator.setMergeRequests(List.of());
        EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.OFF);
        CooperativeCodegen.setForce(false);
        try {
            EntityMetadataGenerator.generate(sourceRoot, reportDir, sourceRoot, passDivergences);
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
            EntityMetadataGenerator.setGenerateAdapters(false);
            EntityMetadataGenerator.setGenerateDtoProjections(false);
            EntityMetadataGenerator.setGeneratePatchAppliers(false);
            EntityMetadataGenerator.setMapperRequests(List.of());
            EntityMetadataGenerator.setMergeRequests(List.of());
            EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.OFF);
            CooperativeCodegen.setForce(false);
        }
        this.divergences = passDivergences;
        read();
    }

    /** The table, the documents and the parsed markers the pass left behind. */
    private void read() throws IOException {
        Assertions.assertTrue(Files.exists(indexFile), "the pass must write " + indexFile);
        this.index = MAPPER.readTree(Files.readString(indexFile));

        Map<String, String> classTable = new LinkedHashMap<>();
        for (var it = index.path("classes").propertyStream().iterator(); it.hasNext(); ) {
            var entry = it.next();
            classTable.put(entry.getKey(), entry.getValue().path("path").asText());
        }
        this.classes = classTable;

        Map<String, JsonNode> documentTable = new LinkedHashMap<>();
        Map<String, EntityMeta> markerTable = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(reportDir)) {
            for (Path document : entries.filter(p -> p.toString().endsWith(".metadata.json")).toList()) {
                String text = Files.readString(document);
                String name = document.getFileName().toString();
                documentTable.put(name, MAPPER.readTree(text));
                EntityMeta meta = EntityMetadataGenerator.fromJson(text, classTable);
                markerTable.put(meta.entityName(), meta);
            }
        }
        Assertions.assertFalse(documentTable.isEmpty(), "the pass must write documents");
        this.documents = documentTable;
        this.markers = markerTable;
    }

    /** The temp directory that holds the copied module. */
    Path parent() {
        return parent;
    }

    /** The copied module root (named {@value #MODULE_NAME}). */
    Path moduleRoot() {
        return moduleRoot;
    }

    /** The module's {@code src/main/java}; generated source is emitted in place here. */
    Path sourceRoot() {
        return sourceRoot;
    }

    /** The module's report directory, {@code .jcodebuddy/metadata/entity}. */
    Path reportDir() {
        return reportDir;
    }

    /** The module's class index, {@code .jcodebuddy/index/classes.json}. */
    Path indexFile() {
        return indexFile;
    }

    /** The report the shared pass produced, for the divergence-report assertions. */
    DivergenceReporter divergences() {
        return divergences;
    }

    /** The parsed class index. */
    JsonNode index() {
        return index;
    }

    /** The class index's text, as written. */
    String indexText() throws IOException {
        return Files.readString(indexFile);
    }

    /** The class index's {@code classes} table: fully qualified name to module-relative path. */
    Map<String, String> classes() {
        return classes;
    }

    /** The documents the pass wrote, keyed by file name (for example {@code Person.metadata.json}). */
    Map<String, JsonNode> documents() {
        return documents;
    }

    /** The documents the pass wrote, parsed into {@link EntityMeta}, keyed by marker name. */
    Map<String, EntityMeta> markers() {
        return markers;
    }

    /** A document's text, by marker name, for the assertions that match on the JSON as written. */
    String documentText(String marker) throws IOException {
        Path file = reportDir.resolve(marker + ".metadata.json");
        Assertions.assertTrue(Files.exists(file), "the pass must write " + file);
        return Files.readString(file);
    }

    /** Every {@code .java} file under the source root, as a sorted forward-slash relative name. */
    List<String> javaFileNames() throws IOException {
        return CompileHarness.javaSourcesUnder(sourceRoot).stream()
                .map(path -> sourceRoot.relativize(path).toString().replace('\\', '/'))
                .sorted()
                .collect(Collectors.toList());
    }

    /** A <strong>source-root-relative</strong> file's text, matching {@link #javaFileNames()}. */
    String readSource(String sourceRelativePath) throws IOException {
        return Files.readString(sourceRoot.resolve(sourceRelativePath));
    }
}
