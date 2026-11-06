package hr.hrg.hipster.entity.tooling.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
import hr.hrg.jcodebuddy.engine.index.ContentHash;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The affected-test selector (plan {@code plans/incremental-test-selection.md} § 5.2, step T3).
 *
 * <p>The selector is only worth what its reasons are worth, so this class states the plan's selection
 * rules against a <strong>synthetic index in a temp directory</strong> — one where every row is chosen so
 * that a rule's failure is visible — and then once more against the module's <strong>real generated
 * index</strong>: changing {@code ContentHash} must select {@code ContentHashTest} and no generator or
 * parser test.</p>
 *
 * <p>The synthetic tree is small on purpose. {@code a.UnitTest} is the unit test every rule reaches;
 * {@code a.OtherTest} is a second unit test so "all unit" is distinguishable from "all tests";
 * {@code a.ComponentTest} and {@code a.GeneratorTest} prove the lane filter; {@code a.Direct} depends on
 * {@code a.Helper}, which depends on {@code a.Base}, so the indirect chain is a real two-hop path rather
 * than a closure entry with no graph behind it; {@code a.Support} is test support that only
 * {@code a.UnitTest} names.</p>
 *
 * <p>Tagged {@code component}: the selector test reads and writes files under a temp directory, which the
 * lane rule puts outside the unit lane (plan § 3, and the T2 probe measures it that way).</p>
 */
@Tag("component")
class TestSelectorTest {

    // ── the synthetic tree ────────────────────────────────────────────────────────────────────────

    private static final String DIRECT = "a.Direct";
    private static final String HELPER = "a.Helper";
    private static final String BASE = "a.Base";
    private static final String SUPPORT = "a.Support";
    private static final String UNIT_TEST = "a.UnitTest";
    private static final String OTHER_TEST = "a.OtherTest";
    private static final String COMPONENT_TEST = "a.ComponentTest";
    private static final String GENERATOR_TEST = "a.GeneratorTest";

    private static final String DIRECT_FILE = "mod/src/main/java/a/Direct.java";
    private static final String HELPER_FILE = "mod/src/main/java/a/Helper.java";
    private static final String BASE_FILE = "mod/src/main/java/a/Base.java";
    private static final String SUPPORT_FILE = "mod/src/test/java/a/Support.java";
    private static final String UNIT_FILE = "mod/src/test/java/a/UnitTest.java";
    private static final String OTHER_FILE = "mod/src/test/java/a/OtherTest.java";
    private static final String COMPONENT_FILE = "mod/src/test/java/a/ComponentTest.java";
    private static final String GENERATOR_FILE = "mod/src/test/java/a/GeneratorTest.java";

    // ── the real module's rows (T1's generated table) ───────────────────────────────────────────

    private static final String CONTENT_HASH_TEST = "hr.hrg.hipster.entity.tooling.index.ContentHashTest";
    private static final String CONTENT_HASH = "hr.hrg.jcodebuddy.engine.index.ContentHash";
    private static final String CONTENT_HASH_FILE =
            "jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/index/ContentHash.java";

    private record Fixture(Path root, Path moduleDir, Path indexFile) {
    }

    /** The repository root, from this class's own compile output — the T1 test's method, reused. */
    private static Path moduleDir() {
        try {
            Path location = Path.of(TestSelectorTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Assertions.assertEquals("test-classes", location.getFileName().toString(),
                    "this test must run from the module's target/test-classes: " + location);
            Path target = location.getParent();
            Assertions.assertEquals("target", target.getFileName().toString(),
                    "target/test-classes must be under a target directory: " + location);
            return target.getParent();
        } catch (URISyntaxException | RuntimeException failure) {
            throw new AssertionError("cannot resolve this test's module root", failure);
        }
    }

    private static Path repoRoot(Path moduleDir) {
        for (Path candidate = moduleDir.toAbsolutePath().normalize(); candidate != null;
             candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("hipster-entity"))) {
                return candidate;
            }
        }
        throw new AssertionError("no repository root above " + moduleDir
                + " (looked for pom.xml beside hipster-entity/)");
    }

    private static Fixture fixture(Path temp) throws IOException {
        Path root = temp.resolve("repo");
        Path module = root.resolve("mod");
        Path indexFile = module.resolve(".jcodebuddy/index/tests.json");
        Files.createDirectories(indexFile.getParent());
        Files.writeString(indexFile, MetadataJson.mapper().writeValueAsString(index()),
                StandardCharsets.UTF_8);
        return new Fixture(root, module, indexFile);
    }

    /** The synthetic table: three production classes, one support class, four tests with distinct lanes. */
    private static ObjectNode index() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("format", TestSelector.FORMAT);
        root.put("module", "mod");
        root.put("sourceRoot", "src/main/java");
        root.put("testRoot", "src/test/java");
        root.put("classIndex", "classes.json");

        ObjectNode files = root.putObject("files");
        file(files, DIRECT_FILE, DIRECT);
        file(files, HELPER_FILE, HELPER);
        file(files, BASE_FILE, BASE);
        file(files, SUPPORT_FILE, SUPPORT);
        file(files, UNIT_FILE, UNIT_TEST);
        file(files, OTHER_FILE, OTHER_TEST);
        file(files, COMPONENT_FILE, COMPONENT_TEST);
        file(files, GENERATOR_FILE, GENERATOR_TEST);

        ObjectNode production = root.putObject("production");
        production(production, DIRECT, DIRECT_FILE, List.of(HELPER));
        production(production, HELPER, HELPER_FILE, List.of(BASE));
        production(production, BASE, BASE_FILE, List.of());

        ObjectNode tests = root.putObject("tests");
        test(tests, UNIT_TEST, UNIT_FILE, List.of("unit"), List.of(DIRECT), List.of(SUPPORT),
                List.of(DIRECT, HELPER, BASE), "unit-checksum");
        test(tests, OTHER_TEST, OTHER_FILE, List.of("unit"), List.of(), List.of(), List.of(),
                "other-checksum");
        test(tests, COMPONENT_TEST, COMPONENT_FILE, List.of("component"), List.of(DIRECT), List.of(),
                List.of(DIRECT, HELPER, BASE), "component-checksum");
        test(tests, GENERATOR_TEST, GENERATOR_FILE, List.of("generator"), List.of(DIRECT), List.of(),
                List.of(DIRECT, HELPER, BASE), "generator-checksum");
        return root;
    }

    private static void file(ObjectNode files, String path, String fqn) {
        files.put(path, fqn);
    }

    private static void production(ObjectNode production, String fqn, String file, List<String> deps) {
        ObjectNode row = production.putObject(fqn);
        row.put("file", file);
        row.put("module", "mod");
        array(row, "deps", deps);
    }

    private static void test(ObjectNode tests, String fqn, String file, List<String> tags,
                             List<String> productionDeps, List<String> testDeps,
                             List<String> productionClosure, String checksum) {
        ObjectNode row = tests.putObject(fqn);
        row.put("file", file);
        array(row, "tags", tags);
        array(row, "productionDeps", productionDeps);
        array(row, "testDeps", testDeps);
        array(row, "productionClosure", productionClosure);
        row.put("checksum", checksum);
    }

    private static void array(ObjectNode row, String field, List<String> values) {
        ArrayNode array = row.putArray(field);
        values.forEach(array::add);
    }

    // ── selection helpers ────────────────────────────────────────────────────────────────────────

    private static TestSelector.Selection select(Fixture fixture, String... changed) throws IOException {
        return TestSelector.select(new TestSelector.Options(fixture.root(), fixture.indexFile(), null,
                List.of(changed), false, false));
    }

    private static TestSelector.Reason reason(TestSelector.Selection selection, String test, String kind) {
        for (TestSelector.Reason reason : selection.reasons()) {
            if (test.equals(reason.test()) && kind.equals(reason.kind())) {
                return reason;
            }
        }
        Assertions.fail("no '" + kind + "' reason for " + test + "; actual reasons: "
                + selection.reasons());
        return null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.asString());
        }
        return values;
    }

    /** Every file under {@code root} with its size and content hash — the no-write assertion. */
    private static String tree(Path root) throws IOException {
        StringBuilder text = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    text.append(relative).append("/\n");
                } else {
                    text.append(relative).append(':').append(Files.size(path)).append(':')
                            .append(ContentHash.of(Files.readAllBytes(path))).append('\n');
                }
            }
        }
        return text.toString();
    }

    /** Runs the CLI with captured stdout; the caller gets the text and the exit code is asserted 0. */
    private static String run(Fixture fixture, String... args) {
        List<String> argv = new ArrayList<>(List.of(
                "--index", fixture.indexFile().toString(),
                "--root", fixture.root().toString()));
        argv.addAll(List.of(args));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        int code;
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            code = TestSelector.run(argv.toArray(String[]::new));
        } finally {
            System.setOut(previous);
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        Assertions.assertEquals(0, code, "the CLI must succeed; output: " + output);
        return output;
    }

    private static void writeUnitSources(Fixture fixture) throws IOException {
        Path testRoot = fixture.moduleDir().resolve("src/test/java/a");
        Files.createDirectories(testRoot);
        Files.writeString(testRoot.resolve("UnitTest.java"), """
                package a;

                import org.junit.jupiter.api.Tag;

                @Tag("unit")
                class UnitTest {
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(testRoot.resolve("ComponentTest.java"), """
                package a;

                import org.junit.jupiter.api.Tag;

                @Tag("component")
                class ComponentTest {
                }
                """, StandardCharsets.UTF_8);
    }

    // ── the plan's rules over a synthetic index ─────────────────────────────────────────────────

    @Test
    void aDirectProductionHitSelectsTheUnitTest(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = select(fixture, DIRECT_FILE);

        Assertions.assertEquals(List.of(UNIT_TEST), selection.selectedFqns(),
                "a changed direct production class selects the unit test that names it, and nothing else");
        Assertions.assertFalse(selection.allUnit(), "one direct hit is not the all-unit fallback");
        Assertions.assertNull(selection.fallback(), "the synthetic index is usable");

        TestSelector.Reason reason = reason(selection, UNIT_TEST, "direct-production");
        Assertions.assertEquals(List.of(DIRECT), reason.chain());
        Assertions.assertEquals("unit", reason.lane());
        Assertions.assertTrue(reason.runnable());
        Assertions.assertTrue(selection.explain().contains(DIRECT + " -> " + UNIT_TEST),
                "explain must print the changed FQN -> test chain: " + selection.explain());
    }

    @Test
    void anIndirectProductionHitSelectsThroughTheClosure(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = select(fixture, BASE_FILE);

        Assertions.assertEquals(List.of(UNIT_TEST), selection.selectedFqns(),
                "the closure must select a test that depends on the changed class indirectly");
        TestSelector.Reason reason = reason(selection, UNIT_TEST, "indirect-production");
        Assertions.assertEquals(List.of(BASE, HELPER, DIRECT), reason.chain(),
                "the chain is the recorded production graph: Base -> Helper -> Direct -> UnitTest");
        Assertions.assertTrue(selection.explain()
                        .contains(BASE + " -> " + HELPER + " -> " + DIRECT + " -> " + UNIT_TEST),
                "explain must name the closure path, not just the endpoints: " + selection.explain());
    }

    @Test
    void aTestSupportHitSelectsItsDependents(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = select(fixture, SUPPORT_FILE);

        Assertions.assertEquals(List.of(UNIT_TEST), selection.selectedFqns(),
                "a changed test-support class selects the tests that name it in testDeps");
        TestSelector.Reason reason = reason(selection, UNIT_TEST, "test-support");
        Assertions.assertEquals(List.of(SUPPORT), reason.chain());
        Assertions.assertTrue(selection.explain().contains(SUPPORT + " -> " + UNIT_TEST));
    }

    @Test
    void anUnrelatedChangedFileSelectsNothing(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = select(fixture, "docs/notes.txt");

        Assertions.assertTrue(selection.selectedFqns().isEmpty(),
                "a file outside every source/resource root cannot affect a test");
        Assertions.assertTrue(selection.notRunnableFqns().isEmpty());
        Assertions.assertFalse(selection.allUnit(), "an unrelated file must not trigger the fallback");
        Assertions.assertTrue(
                selection.explain().contains("outside the module's source/resource roots"),
                "explain must say why the file selected nothing: " + selection.explain());
    }

    @Test
    void aChangedTestFileSelectsItself(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = select(fixture, UNIT_FILE);

        Assertions.assertEquals(List.of(UNIT_TEST), selection.selectedFqns(),
                "a changed unit test selects itself so the edit is actually run");
        TestSelector.Reason reason = reason(selection, UNIT_TEST, "changed-test");
        Assertions.assertEquals(List.of(UNIT_TEST), reason.chain());
    }

    @Test
    void aChangedNonUnitTestIsReportedButNotRunnable(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = select(fixture, GENERATOR_FILE);

        Assertions.assertTrue(selection.selectedFqns().isEmpty(),
                "the affected lane must never run a generator test");
        Assertions.assertEquals(List.of(GENERATOR_TEST), selection.notRunnableFqns(),
                "but a changed generator test must be reported, not silently dropped (plan § 5.2 rule 8)");
        TestSelector.Reason reason = reason(selection, GENERATOR_TEST, "changed-test");
        Assertions.assertFalse(reason.runnable());
        Assertions.assertEquals("generator", reason.lane());
        Assertions.assertTrue(reason.detail().contains("cannot run"),
                "the reason must say the affected lane cannot run it: " + reason.detail());
        Assertions.assertTrue(selection.explain().contains("NOT RUN BY THE AFFECTED LANE"));
    }

    @Test
    void includeComponentAddsTheComponentLaneOnly(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = TestSelector.select(new TestSelector.Options(
                fixture.root(), fixture.indexFile(), null, List.of(DIRECT_FILE), false, true));

        Assertions.assertEquals(List.of(COMPONENT_TEST, UNIT_TEST), selection.selectedFqns(),
                "--include-component adds affected component tests but still never a generator test");
        Assertions.assertTrue(selection.reasons().stream().noneMatch(
                        reason -> "generator".equals(reason.lane()) && reason.runnable()),
                "a heavy lane is never runnable through this selector");
    }

    @Test
    void aPomChangeSelectsEveryUnitTest(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = select(fixture, "mod/pom.xml");

        Assertions.assertEquals(List.of(OTHER_TEST, UNIT_TEST), selection.selectedFqns());
        Assertions.assertTrue(selection.allUnit());
        Assertions.assertNull(selection.fallback(), "a POM change is a rule, not a broken index");
        Assertions.assertTrue(selection.reasons().stream()
                        .filter(reason -> reason.test() != null)
                        .allMatch(reason -> "pom".equals(reason.kind())));
    }

    @Test
    void anUnmappedSourceFileSelectsEveryUnitTest(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = select(fixture, "mod/src/main/java/a/NotIndexed.java");

        Assertions.assertEquals(List.of(OTHER_TEST, UNIT_TEST), selection.selectedFqns(),
                "a source path with no type in the index is the conservative fallback (plan § 5.2 rule 6)");
        Assertions.assertTrue(selection.allUnit());
        Assertions.assertTrue(selection.reasons().stream()
                        .filter(reason -> reason.test() != null)
                        .allMatch(reason -> "unmapped".equals(reason.kind())));
        Assertions.assertTrue(selection.explain().contains("NotIndexed"),
                "explain must name the file the index could not map: " + selection.explain());
    }

    @Test
    void allUnitSelectsEveryUnitTest(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        TestSelector.Selection selection = TestSelector.select(new TestSelector.Options(
                fixture.root(), fixture.indexFile(), null, List.of(), true, false));

        Assertions.assertEquals(List.of(OTHER_TEST, UNIT_TEST), selection.selectedFqns());
        Assertions.assertTrue(selection.allUnit());
        Assertions.assertTrue(selection.reasons().stream()
                        .filter(reason -> reason.test() != null)
                        .allMatch(reason -> "all-unit".equals(reason.kind())));
    }

    // ── the index fallbacks ──────────────────────────────────────────────────────────────────────

    @Test
    void aStaleIndexFallsBackToAllUnitTests(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        Path classFile = fixture.moduleDir().resolve("target/test-classes/a/UnitTest.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "a newer class file than the index recorded", StandardCharsets.UTF_8);

        TestSelector.Selection selection = select(fixture, DIRECT_FILE);
        Assertions.assertTrue(selection.allUnit());
        Assertions.assertNotNull(selection.fallback());
        Assertions.assertEquals("stale-index", selection.fallback().kind());
        Assertions.assertTrue(selection.fallback().detail().contains(UNIT_TEST),
                "the stale reason must name the row whose checksum disagrees: "
                        + selection.fallback().detail());
        Assertions.assertEquals(List.of(OTHER_TEST, UNIT_TEST), selection.selectedFqns(),
                "a stale index is not trusted for per-test selection: all unit tests run");
        Assertions.assertTrue(selection.explain().contains("stale-index"));
    }

    @Test
    void aCorruptIndexFallsBackToTheSourceTaggedUnitTests(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        writeUnitSources(fixture);
        Files.writeString(fixture.indexFile(), "{ this is not JSON", StandardCharsets.UTF_8);

        TestSelector.Selection selection = select(fixture, DIRECT_FILE);
        Assertions.assertTrue(selection.allUnit());
        Assertions.assertNotNull(selection.fallback());
        Assertions.assertEquals("corrupt-index", selection.fallback().kind());
        Assertions.assertEquals(List.of(UNIT_TEST), selection.selectedFqns(),
                "with no usable index the unit tests come from the source @Tag(\"unit\") claim");
    }

    @Test
    void aMissingIndexFallsBackToTheSourceTaggedUnitTests(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        writeUnitSources(fixture);
        Files.delete(fixture.indexFile());

        TestSelector.Selection selection = select(fixture, DIRECT_FILE);
        Assertions.assertTrue(selection.allUnit());
        Assertions.assertNotNull(selection.fallback());
        Assertions.assertEquals("missing-index", selection.fallback().kind());
        Assertions.assertEquals(List.of(UNIT_TEST), selection.selectedFqns());
        Assertions.assertFalse(selection.fallback().detail().isBlank());
    }

    // ── no writes, and the real generated index ─────────────────────────────────────────────────

    @Test
    void explainAndJsonWriteNothing(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        String before = tree(fixture.root());

        String explained = run(fixture, "--explain", "--changed", DIRECT_FILE);
        Assertions.assertTrue(explained.contains(DIRECT + " -> " + UNIT_TEST),
                "the CLI explain must print the chain: " + explained);
        Assertions.assertEquals(before, tree(fixture.root()),
                "--explain is a read: it must not create, delete or modify any file (plan T3 gate)");

        String json = run(fixture, "--json", "--changed", DIRECT_FILE);
        JsonNode parsed = MetadataJson.mapper().readTree(json);
        Assertions.assertEquals(List.of(UNIT_TEST), strings(parsed.path("selected")));
        Assertions.assertEquals(before, tree(fixture.root()), "--json must write no file either");
    }

    @Test
    void theRealIndexSelectsOnlyContentHashTest(@TempDir Path temp) throws Exception {
        Path moduleDir = moduleDir();
        Path repo = repoRoot(moduleDir);
        Path index = moduleDir.resolve(".jcodebuddy/index/tests.json");
        if (!Files.isRegularFile(index)) {
            // A clean checkout has no generated table; build one over the real classpath so this test
            // still asserts the selector against real rows rather than a fixture.
            index = temp.resolve("real-tests.json");
            TestIndexGenerator.generate(repo, moduleDir, TestIndexGenerator.resolveClasspath(), index);
        }

        TestSelector.Selection selection = TestSelector.select(new TestSelector.Options(
                repo, index, moduleDir, List.of(CONTENT_HASH_FILE), false, false));

        Assertions.assertNull(selection.fallback(),
                "the real index must be current: " + selection.fallback());
        Assertions.assertEquals(List.of(CONTENT_HASH_TEST), selection.selectedFqns(),
                "changing ContentHash must select ContentHashTest and nothing else");
        Assertions.assertTrue(selection.notRunnableFqns().isEmpty());
        Assertions.assertTrue(selection.reasons().stream()
                        .filter(reason -> reason.test() != null)
                        .allMatch(reason -> "unit".equals(reason.lane())),
                "a production hit may only select the unit lane");
        Assertions.assertFalse(selection.selectedFqns().contains(
                        "hr.hrg.hipster.entity.tooling.EntityMetadataGeneratorTest"),
                "no generator or parser test may be selected by a ContentHash change");
        Assertions.assertTrue(selection.explain().contains(CONTENT_HASH + " -> " + CONTENT_HASH_TEST),
                "explain must print the real chain: " + selection.explain());

        JsonNode json = MetadataJson.mapper().readTree(selection.toJson());
        Assertions.assertEquals(List.of(CONTENT_HASH_TEST), strings(json.path("selected")));
        Assertions.assertEquals("direct-production",
                json.path("reasons").get(0).path("kind").asString());
    }
}
