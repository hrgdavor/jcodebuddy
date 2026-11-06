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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The disposable test-run state (plan {@code plans/incremental-test-selection.md} § 5.1, step T4).
 *
 * <p>The state is a cache, so every test here is about the <em>decision</em> rather than about Maven: a missing,
 * corrupt or foreign state must cost a full all-unit run, {@code TOUCHED} must be ignored, one changed file must
 * be reported with the names the diff produced, and a POM change must force the all-unit fallback. Publishing is
 * tested from both sides — a successful run advances the trio and {@code run.json}, and a failed run (modelled by
 * simply not calling {@link TestRunState#publish}) leaves both byte-identical so the same tests are selected
 * again.</p>
 *
 * <p>The fixture is a synthetic repository with two modules: {@code mod} (the test module, with its four roots
 * and a POM) and {@code lib} (a dependency that appears only in the index's {@code files}/{@code production}
 * tables). That is enough to prove the watched-input derivation without touching the real module's tree, and it
 * makes "a dependency source edit is seen" a one-line assertion instead of a cross-module integration test.</p>
 *
 * <p>Tagged {@code component}: the test writes and scans files under a temp directory, which the lane rule puts
 * outside the unit lane (plan § 3).</p>
 */
@Tag("component")
class TestRunStateTest {

    private static final String MODULE = "mod";
    private static final String LIB = "lib";
    private static final String POM_FILE = "mod/pom.xml";
    private static final String DIRECT_FILE = "mod/src/main/java/a/Direct.java";
    private static final String UNIT_FILE = "mod/src/test/java/a/UnitTest.java";
    private static final String RESOURCE_FILE = "mod/src/test/resources/note.txt";
    private static final String HELPER_FILE = "lib/src/main/java/b/Helper.java";

    private static final String UNIT_TEST = "a.UnitTest";

    private record Fixture(Path root, Path moduleDir, Path indexFile, TestRunState state) {
    }

    // ── the fixture ───────────────────────────────────────────────────────────────────────────────

    private static Fixture fixture(Path temp) throws IOException {
        Path root = temp.resolve("repo");
        Path module = root.resolve(MODULE);
        write(root.resolve(POM_FILE), "<project/>\n");
        write(root.resolve(DIRECT_FILE), "package a;\npublic class Direct {}\n");
        write(root.resolve(UNIT_FILE), "package a;\nclass UnitTest {}\n");
        write(root.resolve(RESOURCE_FILE), "resource\n");
        write(root.resolve(HELPER_FILE), "package b;\npublic class Helper {}\n");

        Path index = module.resolve(".jcodebuddy/index/tests.json");
        write(index, indexJson());
        return new Fixture(root, module, index, new TestRunState(root, module, index));
    }

    /** The synthetic table: two production classes, one unit test, and the two source roots they name. */
    private static String indexJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("format", TestSelector.FORMAT);
        root.put("module", MODULE);
        root.put("sourceRoot", "src/main/java");
        root.put("testRoot", "src/test/java");
        root.put("classIndex", "classes.json");

        ObjectNode files = root.putObject("files");
        files.put(DIRECT_FILE, "a.Direct");
        files.put(HELPER_FILE, "b.Helper");
        files.put(UNIT_FILE, UNIT_TEST);

        ObjectNode production = root.putObject("production");
        production(production, "a.Direct", DIRECT_FILE, MODULE, List.of());
        production(production, "b.Helper", HELPER_FILE, LIB, List.of());

        ObjectNode tests = root.putObject("tests");
        ObjectNode row = tests.putObject(UNIT_TEST);
        row.put("file", UNIT_FILE);
        array(row, "tags", List.of("unit"));
        array(row, "productionDeps", List.of("a.Direct", "b.Helper"));
        array(row, "testDeps", List.of());
        array(row, "productionClosure", List.of("a.Direct", "b.Helper"));
        row.put("checksum", "unit-checksum");
        return MetadataJson.mapper().writeValueAsString(root);
    }

    private static void production(ObjectNode production, String fqn, String file, String module,
                                   List<String> deps) {
        ObjectNode row = production.putObject(fqn);
        row.put("file", file);
        row.put("module", module);
        array(row, "deps", deps);
    }

    private static void array(ObjectNode row, String field, List<String> values) {
        ArrayNode array = row.putArray(field);
        values.forEach(array::add);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Every file under {@code directory} with its content hash — the "nothing was written" assertion. */
    private static String snapshot(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted().toList()) {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    text.append(relative).append("/\n");
                } else {
                    text.append(relative).append(':')
                            .append(ContentHash.of(Files.readAllBytes(path))).append('\n');
                }
            }
        }
        return text.toString();
    }

    private static JsonNode runJson(Fixture fixture) throws IOException {
        return MetadataJson.mapper().readTree(
                Files.readString(fixture.state().directory().resolve(TestRunState.RUN_FILE)));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.asString());
        }
        return values;
    }

    private static String checksum(Fixture fixture) throws IOException {
        return ContentHash.of(fixture.indexFile());
    }

    // ── placement and the first run ──────────────────────────────────────────────────────────────

    @Test
    void theStateLivesUnderTheModuleCache(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);

        Assertions.assertEquals(fixture.moduleDir().resolve(".jcodebuddy/cache/test-affected"),
                TestRunState.stateDirectory(fixture.moduleDir()),
                "plan § 5.1 names the state directory below the module's .jcodebuddy/cache");
        Assertions.assertEquals(TestRunState.stateDirectory(fixture.moduleDir()),
                fixture.state().directory());
    }

    @Test
    void aMissingStateSelectsEveryUnitTest(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("no test-run state"), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), "there is no diff to report without a state");
        Assertions.assertEquals(checksum(fixture), decision.indexChecksum());
        Assertions.assertFalse(Files.exists(fixture.state().directory()),
                "deciding must not create the state directory");
    }

    // ── publishing and the no-op ─────────────────────────────────────────────────────────────────

    @Test
    void publishingRecordsTheTrioTheInputsAndTheSelection(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));

        Path directory = fixture.state().directory();
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("files.meta")), "the trio is published");
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("files.txt")));
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("files.json")));
        Assertions.assertTrue(Files.isRegularFile(directory.resolve(TestRunState.RUN_FILE)));

        JsonNode run = runJson(fixture);
        Assertions.assertEquals(TestRunState.RUN_FORMAT, run.path("format").asInt());
        Assertions.assertEquals(MODULE, run.path("module").asString());
        Assertions.assertEquals(checksum(fixture), run.path("indexChecksum").asString(),
                "the sidecar records the test-index checksum the selection was computed from");
        Assertions.assertEquals(List.of(UNIT_TEST), strings(run.path("selected")));

        List<String> roots = strings(run.path("inputs").path("roots"));
        Assertions.assertTrue(roots.contains(MODULE + "/src/main/java"), roots.toString());
        Assertions.assertTrue(roots.contains(MODULE + "/src/test/java"), roots.toString());
        Assertions.assertTrue(roots.contains(MODULE + "/src/test/resources"),
                "an existing module resource root is watched even when no indexed file names it: " + roots);
        Assertions.assertTrue(roots.contains(LIB + "/src/main/java"),
                "a module named by the index's production table is watched: " + roots);
        Assertions.assertEquals(List.of(POM_FILE), strings(run.path("inputs").path("files")),
                "the module POM is a watched file");
        Assertions.assertTrue(run.path("state").path("entries").asInt() > 0,
                "the sidecar records the trio it published");
        Assertions.assertEquals(TestRunState.FS_META_WRITER, run.path("state").path("writer").asString());
    }

    @Test
    void publishThenAnUnchangedTreeIsANoop(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.NOOP, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), decision.changed().toString());
        Assertions.assertTrue(decision.reason().contains("no test input changed"), decision.reason());
    }

    @Test
    void aSameContentTouchIsANoop(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        Path helper = fixture.root().resolve(HELPER_FILE);
        Files.setLastModifiedTime(helper,
                FileTime.fromMillis(Files.getLastModifiedTime(helper).toMillis() + 60_000));

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.NOOP, decision.mode(),
                "fs-meta reports TOUCHED for a same-content save, and the state ignores it: "
                        + decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), decision.changed().toString());
    }

    // ── one changed file, POM, and the full fallback ─────────────────────────────────────────────

    @Test
    void oneChangedDependencyFileIsReportedAlone(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        write(fixture.root().resolve(HELPER_FILE), "package b;\npublic class Helper { int changed(); }\n");

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.CHANGED, decision.mode(), decision.reason());
        Assertions.assertEquals(List.of(HELPER_FILE), decision.changed(),
                "the changed name is repo-relative and /-separated, as the state stores it");
        Assertions.assertFalse(decision.reason().contains("pom.xml"), decision.reason());
    }

    @Test
    void aPomChangeFallsBackToEveryUnitTest(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        write(fixture.root().resolve(POM_FILE), "<project><!-- changed --></project>\n");

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("pom.xml"), decision.reason());
        Assertions.assertEquals(List.of(POM_FILE), decision.changed());
    }

    @Test
    void aChangedIndexInvalidatesTheState(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        Files.writeString(fixture.indexFile(), Files.readString(fixture.indexFile()) + "\n",
                StandardCharsets.UTF_8);

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("index changed"), decision.reason());
    }

    @Test
    void aNewWatchedInputRootInvalidatesTheState(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        write(fixture.moduleDir().resolve("src/main/resources/new.txt"), "new root\n");

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("input set changed"), decision.reason());
        Assertions.assertTrue(decision.reason().contains(MODULE + "/src/main/resources"),
                "the reason names the root that appeared: " + decision.reason());
    }

    // ── corrupt state ────────────────────────────────────────────────────────────────────────────

    @Test
    void aCorruptTrioFallsBackToEveryUnitTest(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        write(fixture.state().directory().resolve("files.meta"), "not a trio");

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("not usable"), decision.reason());
    }

    @Test
    void aCorruptSidecarFallsBackToEveryUnitTest(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        write(fixture.state().directory().resolve(TestRunState.RUN_FILE), "{}");

        TestRunState.Decision decision = fixture.state().decide();

        Assertions.assertEquals(TestRunState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("sidecar"), decision.reason());
    }

    // ── publish only after success ───────────────────────────────────────────────────────────────

    @Test
    void aFailedRunLeavesThePreviousStateSoTheNextRunSelectsAgain(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        String published = snapshot(fixture.state().directory());

        write(fixture.root().resolve(HELPER_FILE), "package b;\npublic class Helper { int changed(); }\n");
        TestRunState.Decision first = fixture.state().decide();
        Assertions.assertEquals(TestRunState.Mode.CHANGED, first.mode(), first.reason());

        // A failed Maven run does not call publish. The decision is a read, and the state must be untouched.
        Assertions.assertEquals(published, snapshot(fixture.state().directory()),
                "deciding and failing must not advance the state");

        TestRunState.Decision second = fixture.state().decide();
        Assertions.assertEquals(TestRunState.Mode.CHANGED, second.mode(),
                "the same change is selected again after a failed run");
        Assertions.assertEquals(first.changed(), second.changed());
        Assertions.assertEquals(published, snapshot(fixture.state().directory()),
                "the second decision is still a read");
    }

    @Test
    void aSuccessfulRunAdvancesTheStateAndTheGeneration(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        long firstGeneration = runJson(fixture).path("state").path("generation").asLong();

        write(fixture.root().resolve(HELPER_FILE), "package b;\npublic class Helper { int changed(); }\n");
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));

        TestRunState.Decision decision = fixture.state().decide();
        Assertions.assertEquals(TestRunState.Mode.NOOP, decision.mode(),
                "after the successful run the new tree is the published one: " + decision.reason());
        Assertions.assertTrue(runJson(fixture).path("state").path("generation").asLong() > firstGeneration,
                "publish writes a new generation of the trio");
    }

    @Test
    void publishingRefusesAnIndexThatChangedWhileTheTestsRan(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        fixture.state().publish(checksum(fixture), List.of(UNIT_TEST));
        String expected = checksum(fixture);
        String before = snapshot(fixture.state().directory());

        Files.writeString(fixture.indexFile(), Files.readString(fixture.indexFile()) + "\n",
                StandardCharsets.UTF_8);
        Assertions.assertThrows(IOException.class,
                () -> fixture.state().publish(expected, List.of(UNIT_TEST)),
                "a selection computed from a different index must not publish a state for this one");
        Assertions.assertEquals(before, snapshot(fixture.state().directory()),
                "the refused publish leaves the previous state in place");
    }
}
