package hr.hrg.hipster.entity.tooling.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The generated test dependency index (plan {@code plans/incremental-test-selection.md} § 4, T1).
 *
 * <p>The index is only worth what it can be checked against, so this test states the four claims the
 * plan's T1 gate makes, against the module's real compiled test classpath rather than a fixture:</p>
 *
 * <ul>
 *   <li>every test class under {@code target/test-classes} has a row, and test support is mapped but not
 *       given one — the index cannot quietly lose a test or promote a fixture into one;</li>
 *   <li>the {@code ContentHashTest} row is exact: the cross-module production dependency and the
 *       repo-relative source path, not a {@code ..} path or a jar name;</li>
 *   <li>two generations are byte-identical, which is what lets {@code test-compile} regenerate the table
 *       on every run; and</li>
 *   <li>a project classpath directory outside the repository is refused loudly rather than dropped.</li>
 * </ul>
 *
 * <p>It also pins one tag row: {@code CompactionRoundTripTest} is a real {@code @Tag("generator")} class
 * in this module, so its row proves the class-file annotation read without a second source parse.</p>
 */
@Tag("component")
class TestIndexGeneratorTest {

    private static final String CONTENT_HASH_TEST = "hr.hrg.hipster.entity.tooling.index.ContentHashTest";
    private static final String CONTENT_HASH_TEST_FILE =
            "hipster-entity/hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/index/"
                    + "ContentHashTest.java";
    private static final String CONTENT_HASH = "hr.hrg.jcodebuddy.engine.index.ContentHash";
    private static final String CONTENT_HASH_FILE =
            "jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/index/ContentHash.java";
    private static final String TAGGED_TEST = "hr.hrg.hipster.entity.tooling.CompactionRoundTripTest";
    private static final String SUPPORT_FQN = "hr.hrg.hipster.entity.tooling.CompileHarness";
    private static final String SUPPORT_FILE =
            "hipster-entity/hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/"
                    + "CompileHarness.java";

    /** The annotations JUnit runs — the same set the generator uses to separate a test from support. */
    private static final Set<String> TEST_DESCRIPTORS = Set.of(
            "Lorg/junit/jupiter/api/Test;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;",
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/params/ParameterizedTest;",
            "Lorg/junit/jupiter/api/Testable;",
            "Lorg/junit/Test;");

    /**
     * The module root, from this class's own compile output: {@code target/test-classes} is the one
     * location a test can trust regardless of the working directory surefire or an IDE chose.
     */
    private static Path moduleDir() {
        try {
            Path location = Path.of(TestIndexGeneratorTest.class.getProtectionDomain()
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

    /** The repository root: the nearest ancestor with the reactor POM and the {@code hipster-entity} group. */
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

    private static JsonNode read(Path file) throws Exception {
        return MetadataJson.mapper().readTree(Files.readString(file));
    }

    private static Set<String> keys(JsonNode object) {
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            keys.add(entry.getKey());
        }
        return keys;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.asText());
        }
        return values;
    }

    /**
     * Every top-level test class the compiler wrote, by FQN — a class file that carries a JUnit test
     * annotation, in its own methods or in a nested class of the same source file.
     *
     * <p>This is deliberately an independent read of the same class files: a "test class" is measured,
     * not inferred from a name, so a support class ({@code CompileHarness}, a fixture, a benchmark) is
     * not counted and a test cannot be silently dropped.</p>
     */
    private static Set<String> compiledTestClasses(Path moduleDir) throws Exception {
        Path testClasses = moduleDir.resolve("target/test-classes");
        Set<String> classes = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(testClasses)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .toList()) {
                String relative = testClasses.relativize(file).toString().replace('\\', '/');
                String binary = relative.substring(0, relative.length() - ".class".length());
                if (binary.equals("module-info") || binary.endsWith("/module-info")
                        || binary.equals("package-info") || binary.endsWith("/package-info")) {
                    continue;
                }
                if (!declaresTests(file)) {
                    continue;
                }
                int dollar = binary.indexOf('$');
                classes.add((dollar < 0 ? binary : binary.substring(0, dollar)).replace('/', '.'));
            }
        }
        return classes;
    }

    private static boolean declaresTests(Path classFile) throws IOException {
        ClassModel model = ClassFile.of().parse(Files.readAllBytes(classFile));
        if (declaresTests(model.findAttribute(Attributes.runtimeVisibleAnnotations()))) {
            return true;
        }
        for (MethodModel method : model.methods()) {
            if (declaresTests(method.findAttribute(Attributes.runtimeVisibleAnnotations()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresTests(Optional<RuntimeVisibleAnnotationsAttribute> visible) {
        if (visible.isEmpty()) {
            return false;
        }
        for (Annotation annotation : visible.get().annotations()) {
            if (TEST_DESCRIPTORS.contains(annotation.className().stringValue())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode generated(Path moduleDir, Path output) throws Exception {
        Path repo = repoRoot(moduleDir);
        TestIndexGenerator.Result result = TestIndexGenerator.generate(repo, moduleDir,
                TestIndexGenerator.resolveClasspath(), output);
        Assertions.assertTrue(Files.isRegularFile(output), "the generator must write " + output);
        JsonNode root = read(output);
        Assertions.assertEquals(TestIndexGenerator.FORMAT, root.path("format").asInt(),
                "the table's format is its contract; a consumer must be able to refuse an unknown one");
        Assertions.assertEquals("hipster-entity-tooling", root.path("module").asText());
        Assertions.assertEquals(TestIndexGenerator.SOURCE_ROOT, root.path("sourceRoot").asText());
        Assertions.assertEquals(TestIndexGenerator.TEST_ROOT, root.path("testRoot").asText());
        Assertions.assertEquals(TestIndexGenerator.CLASS_INDEX, root.path("classIndex").asText());
        Assertions.assertEquals(keys(root.path("tests")).size(), result.tests(),
                "the summary's test count must be the table's own");
        return root;
    }

    @Test
    void everyCompiledTestClassHasARow(@TempDir Path temp) throws Exception {
        Path moduleDir = moduleDir();
        JsonNode root = generated(moduleDir, temp.resolve("tests.json"));

        Assertions.assertEquals(compiledTestClasses(moduleDir), keys(root.path("tests")),
                "every class file under target/test-classes that declares a JUnit test must have a row: "
                        + "a test the index cannot see is a test the affected selector can never run "
                        + "(plan § 4)");
    }

    /**
     * A support class is a measured fact, not a row: it has no test method, so running it through a lane
     * is meaningless. It is still in {@code files} (a change to it must resolve to an FQN) and in the
     * {@code testDeps} of the tests that name it (the plan's test-support graph, § 4), which is what lets
     * a support change select its dependents without a phantom test.
     */
    @Test
    void testSupportClassesAreMappedButAreNotTestRows(@TempDir Path temp) throws Exception {
        JsonNode root = generated(moduleDir(), temp.resolve("tests.json"));

        Assertions.assertEquals(SUPPORT_FQN, root.path("files").path(SUPPORT_FILE).asText(),
                "a changed support path still resolves to its type");
        Assertions.assertFalse(root.path("tests").has(SUPPORT_FQN),
                "CompileHarness declares no test; it must not become a row a lane might try to run");

        JsonNode dependent = root.path("tests").path("hr.hrg.hipster.entity.tooling.AllLevelsCompileTest");
        Assertions.assertFalse(dependent.isMissingNode(), "the dependent test must be indexed");
        Assertions.assertTrue(strings(dependent.path("testDeps")).contains(SUPPORT_FQN),
                "the test-support graph must record the support class the test names; actual testDeps: "
                        + strings(dependent.path("testDeps")));
    }

    @Test
    void theContentHashTestRowIsExact(@TempDir Path temp) throws Exception {
        Path moduleDir = moduleDir();
        JsonNode root = generated(moduleDir, temp.resolve("tests.json"));

        JsonNode row = root.path("tests").path(CONTENT_HASH_TEST);
        Assertions.assertFalse(row.isMissingNode(), "the known test must have a row");
        Assertions.assertEquals(CONTENT_HASH_TEST_FILE, row.path("file").asText(),
                "the file is repo-relative, never absolute and never a ../../ path");
        Assertions.assertEquals(List.of(CONTENT_HASH), strings(row.path("productionDeps")),
                "ContentHashTest names exactly one project production class: ContentHash. The constant "
                        + "pool is the authority, so a different list is a real class-file reference, not "
                        + "an assertion to weaken.");

        JsonNode production = root.path("production").path(CONTENT_HASH);
        Assertions.assertFalse(production.isMissingNode(),
                "the production graph must carry the test's direct dependency");
        Assertions.assertEquals(CONTENT_HASH_FILE, production.path("file").asText(),
                "the cross-module path maps back to its sibling src/main/java file");
        Assertions.assertEquals("jcodebuddy-core", production.path("module").asText());
        Assertions.assertEquals(CONTENT_HASH, root.path("files").path(CONTENT_HASH_FILE).asText(),
                "the files table is how a changed path is resolved back to an FQN");
        Assertions.assertEquals(CONTENT_HASH_TEST,
                root.path("files").path(CONTENT_HASH_TEST_FILE).asText());
        Assertions.assertTrue(strings(row.path("productionClosure")).contains(CONTENT_HASH),
                "the closure includes the direct dependency");
    }

    @Test
    void twoGenerationsAreByteIdentical(@TempDir Path temp) throws Exception {
        Path moduleDir = moduleDir();
        Path first = temp.resolve("first/tests.json");
        Path second = temp.resolve("second/tests.json");
        generated(moduleDir, first);
        generated(moduleDir, second);
        Assertions.assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "no timestamp, no walk order and no absolute path may leak into the table: two passes on "
                        + "an unchanged classpath are byte-identical (plan § 4, T1 gate)");
    }

    @Test
    void aProjectDirectoryOutsideTheRepositoryIsRefused(@TempDir Path temp) throws Exception {
        Path moduleDir = moduleDir();
        Path outside = temp.resolve("outside-module");
        Files.createDirectories(outside.resolve("target/classes"));

        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
                () -> TestIndexGenerator.generate(repoRoot(moduleDir), moduleDir,
                        List.of(outside.resolve("target/classes")), temp.resolve("refused.json")),
                "a project-looking classpath directory outside the repository cannot be made "
                        + "repo-relative; dropping it would be an under-selection");
        Assertions.assertTrue(failure.getMessage().contains("unresolved project"),
                "the failure must name the unresolved project: " + failure.getMessage());
    }

    @Test
    void theClassFileTagIsRecorded(@TempDir Path temp) throws Exception {
        Path moduleDir = moduleDir();
        JsonNode root = generated(moduleDir, temp.resolve("tests.json"));

        List<String> tags = strings(root.path("tests").path(TAGGED_TEST).path("tags"));
        Assertions.assertTrue(tags.contains("generator"),
                "the row must carry the runtime-retained @Tag from the class file; other lanes (T2) read "
                        + "this field instead of parsing the source again. Actual tags: " + tags);
    }
}
