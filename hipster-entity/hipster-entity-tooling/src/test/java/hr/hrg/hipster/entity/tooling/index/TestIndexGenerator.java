package hr.hrg.hipster.entity.tooling.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
import hr.hrg.jcodebuddy.engine.index.ContentHash;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The generated test dependency index for one test module (plan
 * {@code plans/incremental-test-selection.md} § 4, step T1).
 *
 * <h3>What it is</h3>
 * <p>One JSON table, {@code <module>/.jcodebuddy/index/tests.json}, that answers "which tests does this
 * change affect?" from <strong>measured</strong> evidence rather than a hand-written list. For every test
 * class it records the project classes the compiled class file names, the test-support classes it names,
 * and the transitive closure over the production graph. For every project production class it records the
 * file it is declared in, the module it belongs to, and the project classes it names. The {@code files}
 * table maps a repo-relative source path to its type, which is how a changed path is resolved to a name.</p>
 *
 * <h3>Why the compiled class file, not the imports</h3>
 * <p>The constant pool is what actually ran: it includes a fully-qualified use, a static import, and a
 * nested type that an import scan can miss, and it excludes an import that is written but never used. The
 * plan's § 4 names this as the authority, and {@code java.lang.classfile} (JDK 25, final since JDK 24)
 * reads it without an extra parser or dependency. The source is read for nothing: a class file maps back to
 * its {@code src/main/java} / {@code src/test/java} sibling by path, and no file content is copied into the
 * table.</p>
 *
 * <h3>What it scans, and what it refuses</h3>
 * <p>It scans every classpath directory that is a project's compiled output — a directory named
 * {@code target/classes} or {@code target/test-classes} — so a class under
 * {@code jcodebuddy-core/target/classes} maps to
 * {@code jcodebuddy/jcodebuddy-core/src/main/java/…} and a one-class test in this module can depend on a
 * core class. A jar is an external dependency and is not an editable input, so it is skipped. A directory
 * that looks like project output but does not live under the repository root, and a class file that has no
 * source sibling, are <strong>errors</strong>: a project name the index cannot resolve is reported, never
 * dropped, because a silent drop is an under-selection and under-selection is the failure mode this
 * table exists to prevent (plan § 5.2).</p>
 *
 * <h3>Determinism</h3>
 * <p>Every map and set is sorted, arrays are sorted, no timestamp and no absolute path is written, and the
 * file's bytes are a pure function of the compiled classpath. Two generations on an unchanged tree are
 * byte-identical, which is what makes the table safe to regenerate on every {@code test-compile} and what
 * the test asserts.</p>
 *
 * <p><strong>Tests vs test support.</strong> A class under {@code target/test-classes} becomes a
 * {@code tests} row only when its class file declares something JUnit runs — a test-method annotation, or
 * {@code @Testable} on a type whose methods an implementation inherits. A fixture, a compile harness or a
 * benchmark is still in {@code files} and in the {@code testDeps} of the tests that name it, but it gets no
 * row: a row is a thing a lane can run, and the plan's § 2 inventory counts tests, not support. The
 * distinction is measured from the class file, never guessed from a class name.</p>
 *
 * <h3>What a dependency name means</h3>
 * <p>{@code productionDeps} keeps the <strong>exact</strong> FQN the constant pool names, including a
 * nested type ({@code productionClosure} — the selection surface — resolves it to the declaring top-level
 * type). {@code testDeps} keeps the exact name <em>and</em> its declaring top-level type, because a
 * test-support change is a file change and there is no separate test-side closure: the {@code files} table
 * maps that file to its primary top-level name, and a testDeps array must contain that name for selection
 * rule 4/5 to find it. Resolving to the declaring type is not a loss of precision: a reference to
 * {@code Outer$Inner} is a dependency on {@code Outer.java}.</p>
 */
public final class TestIndexGenerator {

    /** The table's own version. An unknown value means "do not trust this table" — never guess. */
    public static final int FORMAT = 1;

    /** The table's file name inside the module's {@code .jcodebuddy/index/}. */
    public static final String FILE_NAME = "tests.json";

    /** The derived subtree the table lives in, under the module's {@code .jcodebuddy/}. */
    public static final String INDEX_DIR_NAME = "index";

    /** The module's output root: derived, ignored, and regenerable (DEC-026). */
    public static final String JCODEBUDDY_DIR = ".jcodebuddy";

    /** The production source root, module-relative — recorded so the table is self-describing. */
    public static final String SOURCE_ROOT = "src/main/java";

    /** The test source root, module-relative. */
    public static final String TEST_ROOT = "src/test/java";

    /** The sibling class-index file name the header points at (DEC-029); this table is separate. */
    public static final String CLASS_INDEX = "classes.json";

    /** JUnit's runtime-retained tag, read from the class file's {@code RuntimeVisibleAnnotations}. */
    private static final String TAG_DESCRIPTOR = "Lorg/junit/jupiter/api/Tag;";

    /** JUnit's repeatable container for {@code @Tag} ({@code @Tags({@Tag("a"), …})}). */
    private static final String TAGS_DESCRIPTOR = "Lorg/junit/jupiter/api/Tags;";

    /**
     * What makes a compiled class under {@code target/test-classes} a <em>test</em> rather than test
     * support: a method annotation JUnit runs, or {@code @Testable} on a type whose methods a test
     * interface inherits. A class in test output with none of these (a {@code CompileHarness}, a fixture,
     * a JMH benchmark) is still indexed — under {@code files}, and in the {@code testDeps} of the tests
     * that name it — but it gets no {@code tests} row: a row is a thing a lane can run, and the plan's
     * § 2 inventory (whose buckets start at one dependency) does not count support classes as tests.
     */
    private static final Set<String> TEST_DESCRIPTORS = Set.of(
            "Lorg/junit/jupiter/api/Test;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;",
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/params/ParameterizedTest;",
            "Lorg/junit/jupiter/api/Testable;",
            "Lorg/junit/Test;");

    private static final String CLASS_SUFFIX = ".class";
    private static final String TARGET = "target";
    private static final String CLASSES = "classes";
    private static final String TEST_CLASSES = "test-classes";

    private static final ClassFile PARSER = ClassFile.of();

    private TestIndexGenerator() {
    }

    /**
     * Where the table is written for {@code moduleRoot}: {@code .jcodebuddy/index/tests.json}.
     *
     * <p>The plan's § 4 layout, and a fact about the module rather than a flag: a caller that wants
     * another location for a test or a probe passes it to {@link #generate} explicitly.</p>
     */
    public static Path defaultOutput(Path moduleRoot) {
        return moduleRoot.toAbsolutePath().normalize()
                .resolve(JCODEBUDDY_DIR).resolve(INDEX_DIR_NAME).resolve(FILE_NAME);
    }

    /** What a generation found, so the wrapper can print counts without re-reading the JSON. */
    public record Result(int tests, int production, int files, Path output) {
    }

    /** One class file, resolved back to its declaring source and the top-level type of that source. */
    private record ProjectClass(String fqn, String topFqn, String file, String module, boolean test) {
    }

    /** One declaring source file's top-level type, with the facts its row(s) need. */
    private static final class Agg {
        private final String topFqn;
        private final String file;
        private final String module;
        private final boolean testOutput;
        private final Set<String> tags = new TreeSet<>();
        private final Set<String> refs = new TreeSet<>();
        private final Set<String> ancestors = new TreeSet<>();
        private boolean hasTests;
        private boolean testClass;
        private Path classFile;

        private Agg(String topFqn, String file, String module, boolean testOutput) {
            this.topFqn = topFqn;
            this.file = file;
            this.module = module;
            this.testOutput = testOutput;
        }
    }

    /** The graph a generation found, before it is rendered. */
    private record Scan(Map<String, ProjectClass> classes, Map<String, Agg> topTypes,
                        Map<String, String> primaryByFile) {
    }

    /** A test (or test-support) row: the source file, its class tags, and its measured dependencies. */
    private record TestRow(String file, Set<String> tags, Set<String> productionDeps, Set<String> testDeps,
                           Set<String> productionClosure, String checksum) {
    }

    /** A production row: the source file, the module, and the project classes it names. */
    private record ProductionRow(String file, String module, Set<String> deps) {
    }

    /**
     * Generates the table.
     *
     * @param repoRoot  the repository root every recorded path is relative to
     * @param moduleDir the test module the table is about (its {@code .jcodebuddy/} receives it)
     * @param classpath the test classpath entries to scan; jars and ordinary directories are ignored
     * @param output    the file to write
     * @throws IOException           when a class file cannot be read or the table cannot be written
     * @throws IllegalStateException when a project directory, a duplicate FQN, or a class file's source
     *                               cannot be resolved — reported, never dropped (plan § 5.2)
     */
    public static Result generate(Path repoRoot, Path moduleDir, List<Path> classpath, Path output)
            throws IOException {
        Path repo = repoRoot.toAbsolutePath().normalize();
        Path module = moduleDir.toAbsolutePath().normalize();
        Scan scan = scan(repo, classpath);

        Map<String, String> files = new TreeMap<>(scan.primaryByFile());

        Map<String, Set<String>> productionEdges = new TreeMap<>();
        Map<String, Set<String>> productionDeps = new TreeMap<>();
        for (Agg agg : scan.topTypes().values()) {
            if (agg.testOutput) {
                continue;
            }
            Set<String> deps = new TreeSet<>();
            Set<String> edges = new TreeSet<>();
            for (String ref : agg.refs) {
                ProjectClass target = scan.classes().get(ref);
                if (target == null || target.test()) {
                    continue;
                }
                deps.add(ref);
                String primary = scan.primaryByFile().get(target.file());
                if (primary != null && !primary.equals(agg.topFqn)) {
                    edges.add(primary);
                }
            }
            productionDeps.put(agg.topFqn, deps);
            productionEdges.put(agg.topFqn, edges);
        }

        Map<String, TestRow> tests = new TreeMap<>();
        for (Agg agg : scan.topTypes().values()) {
            if (!agg.testOutput || !agg.testClass) {
                continue;
            }
            Set<String> deps = new TreeSet<>();
            Set<String> testDeps = new TreeSet<>();
            Set<String> closure = new TreeSet<>();
            Deque<String> queue = new ArrayDeque<>();
            for (String ref : agg.refs) {
                ProjectClass target = scan.classes().get(ref);
                if (target == null) {
                    continue;
                }
                if (target.test()) {
                    testDeps.add(ref);
                    // A test-support change is a FILE change, and `files` maps that file to its primary
                    // top-level type. There is no test-side closure field, so a reference to a nested
                    // support type must also name its declaring type or the changed file would resolve to
                    // a name no testDeps array contains (plan § 5.2 rules 4-5).
                    String support = scan.primaryByFile().get(target.file());
                    if (support != null) {
                        testDeps.add(support);
                    }
                    continue;
                }
                deps.add(ref);
                String primary = scan.primaryByFile().get(target.file());
                if (primary != null && closure.add(primary)) {
                    queue.add(primary);
                }
            }
            while (!queue.isEmpty()) {
                for (String next : productionEdges.getOrDefault(queue.poll(), Set.of())) {
                    if (closure.add(next)) {
                        queue.add(next);
                    }
                }
            }
            if (agg.classFile == null) {
                // Every top-level type has a class file; reaching here would mean the family was never
                // completed, and a row without its checksum would be a guess.
                throw new IllegalStateException("no top-level class file for " + agg.topFqn
                        + " (" + agg.file + ")");
            }
            tests.put(agg.topFqn, new TestRow(agg.file, agg.tags, deps, testDeps, closure,
                    checksum(agg.classFile)));
        }

        Map<String, ProductionRow> production = new TreeMap<>();
        for (Agg agg : scan.topTypes().values()) {
            if (agg.testOutput) {
                continue;
            }
            production.put(agg.topFqn, new ProductionRow(agg.file, agg.module,
                    productionDeps.getOrDefault(agg.topFqn, Set.of())));
        }

        write(output, module.getFileName() == null ? "" : module.getFileName().toString(),
                files, tests, production);
        return new Result(tests.size(), production.size(), files.size(), output.toAbsolutePath().normalize());
    }
    // ── scanning ───────────────────────────────────────────────────────────────────────────────────

    private static Scan scan(Path repo, List<Path> classpath) throws IOException {
        Map<String, ProjectClass> classes = new TreeMap<>();
        Map<String, Agg> topTypes = new TreeMap<>();
        Set<Path> scanned = new LinkedHashSet<>();

        for (Path entry : distinctDirectories(classpath)) {
            Path directory = projectDirectory(entry, repo);
            if (directory == null || !scanned.add(directory)) {
                continue;
            }
            Kind kind = kindOf(directory);
            if (kind == null) {
                continue;
            }
            Path moduleRoot = directory.getParent().getParent().toAbsolutePath().normalize();
            String moduleName = moduleRoot.getFileName() == null ? "" : moduleRoot.getFileName().toString();
            if (moduleName.isEmpty()) {
                throw new IllegalStateException("unresolved project name for classpath directory " + entry);
            }
            Path sourceRoot = moduleRoot.resolve(kind == Kind.MAIN ? SOURCE_ROOT : TEST_ROOT);
            boolean test = kind == Kind.TEST;

            for (Path classFile : classFiles(directory)) {
                String relative = relativeSlash(directory, classFile);
                String binary = relative.substring(0, relative.length() - CLASS_SUFFIX.length());
                if (isMetadataClass(binary)) {
                    continue;
                }
                int dollar = binary.indexOf('$');
                String topBinary = dollar < 0 ? binary : binary.substring(0, dollar);
                String fqn = binary.replace('/', '.');
                String topFqn = topBinary.replace('/', '.');
                Path source = sourceRoot.resolve(topBinary + ".java");
                if (!Files.isRegularFile(source)) {
                    throw new IllegalStateException("unresolved project type " + fqn + ": " + classFile
                            + " maps to " + source + ", which does not exist. A compiled project class "
                            + "must have a source sibling; dropping it would be an under-selection.");
                }
                String file = relativeSlash(repo, source);

                ProjectClass previous = classes.get(fqn);
                if (previous != null && !previous.file().equals(file)) {
                    throw new IllegalStateException("two project classes share the FQN " + fqn + ": "
                            + previous.file() + " and " + file);
                }
                classes.put(fqn, new ProjectClass(fqn, topFqn, file, moduleName, test));

                Agg agg = topTypes.get(topFqn);
                if (agg == null) {
                    agg = new Agg(topFqn, file, moduleName, test);
                    topTypes.put(topFqn, agg);
                } else if (agg.testOutput != test || !agg.file.equals(file)) {
                    throw new IllegalStateException("two project types share the FQN " + topFqn + ": "
                            + agg.file + " (test=" + agg.testOutput + ") and " + file + " (test=" + test
                            + ")");
                }

                ClassModel model = PARSER.parse(Files.readAllBytes(classFile));
                agg.tags.addAll(tags(model));
                agg.refs.addAll(references(model, fqn, topFqn));
                agg.ancestors.addAll(ancestors(model));
                agg.hasTests |= hasTests(model);
                if (binary.equals(topBinary)) {
                    agg.classFile = classFile;
                }
            }
        }

        markTestClasses(topTypes, classes);
        return new Scan(classes, topTypes, primaryByFile(topTypes));
    }

    /**
     * Marks the top-level types that are tests: a class with a test method (or {@code @Testable}), and any
     * class that inherits from one — a subclass with no method of its own is still run by JUnit.
     *
     * <p>The annotation is read from the class file, so a nested {@code @Nested} class's methods count for
     * its declaring top-level class through {@link Agg#hasTests} aggregation. A test interface's
     * {@code @Testable} and its default test methods reach an implementation through
     * {@link Agg#ancestors}.</p>
     */
    private static void markTestClasses(Map<String, Agg> topTypes, Map<String, ProjectClass> classes) {
        for (Agg agg : topTypes.values()) {
            agg.testClass = agg.testOutput && agg.hasTests;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Agg agg : topTypes.values()) {
                if (!agg.testOutput || agg.testClass) {
                    continue;
                }
                for (String ancestor : agg.ancestors) {
                    ProjectClass parent = classes.get(ancestor);
                    if (parent == null || !parent.test()) {
                        continue;
                    }
                    Agg parentAgg = topTypes.get(parent.topFqn());
                    if (parentAgg != null && parentAgg.testClass) {
                        agg.testClass = true;
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /** The superclass and interfaces one class file names, as FQNs. */
    private static Set<String> ancestors(ClassModel model) {
        Set<String> ancestors = new TreeSet<>();
        model.superclass().ifPresent(entry -> ancestors.add(entry.asInternalName().replace('/', '.')));
        for (ClassEntry entry : model.interfaces()) {
            ancestors.add(entry.asInternalName().replace('/', '.'));
        }
        return ancestors;
    }

    /**
     * Whether one class file declares something JUnit runs: a method carrying a test annotation, or
     * {@code @Testable} on the type. This is what separates a test from test support; it is measured from
     * the class file alongside the tags, never guessed from the class name.
     */
    private static boolean hasTests(ClassModel model) {
        Optional<RuntimeVisibleAnnotationsAttribute> typeAnnotations =
                model.findAttribute(Attributes.runtimeVisibleAnnotations());
        if (typeAnnotations.isPresent()) {
            for (Annotation annotation : typeAnnotations.get().annotations()) {
                if (TEST_DESCRIPTORS.contains(annotation.className().stringValue())) {
                    return true;
                }
            }
        }
        for (MethodModel method : model.methods()) {
            Optional<RuntimeVisibleAnnotationsAttribute> methodAnnotations =
                    method.findAttribute(Attributes.runtimeVisibleAnnotations());
            if (methodAnnotations.isEmpty()) {
                continue;
            }
            for (Annotation annotation : methodAnnotations.get().annotations()) {
                if (TEST_DESCRIPTORS.contains(annotation.className().stringValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The project compiled-output directory a classpath entry stands for, or {@code null} when the entry
     * is not a project input at all.
     *
     * <p>A directory named {@code target/classes} or {@code target/test-classes} is the plan's input. A
     * <strong>workspace jar</strong> — {@code <module>/target/<artifact>.jar} under the repository root —
     * is the same module's packaged output and is resolved to its sibling {@code target/classes}: Maven's
     * surefire classpath names reactor dependencies as jars once they have been packaged, and the plan's
     * rule is that a workspace module has a source root. A jar anywhere else (a released artifact in
     * {@code ~/.m2}) is external and has no editable input, so it is skipped.</p>
     *
     * <p>Refusals are loud: a project output directory outside the repository has no repo-relative path,
     * and a workspace jar whose {@code target/classes} is missing means the module was not compiled — both
     * would be silent under-selections if dropped (plan § 5.2).</p>
     */
    private static Path projectDirectory(Path entry, Path repo) throws IOException {
        if (Files.isDirectory(entry)) {
            Kind kind = kindOf(entry);
            if (kind == null) {
                return null;
            }
            Path moduleRoot = entry.getParent().getParent().toAbsolutePath().normalize();
            if (!moduleRoot.startsWith(repo)) {
                throw new IllegalStateException("unresolved project at " + entry + ": its module root "
                        + moduleRoot + " is not under the repository root " + repo
                        + ". A project classpath directory must be <repo>/<module>/target/classes or "
                        + "<repo>/<module>/target/test-classes; dropping it would be an under-selection, "
                        + "so the index refuses to guess.");
            }
            return entry.toAbsolutePath().normalize();
        }

        if (!Files.isRegularFile(entry) || !entry.getFileName().toString().endsWith(".jar")) {
            return null;
        }
        Path target = entry.getParent();
        if (target == null || target.getFileName() == null
                || !TARGET.equals(target.getFileName().toString())) {
            return null;
        }
        Path moduleRoot = target.getParent();
        if (moduleRoot == null || !moduleRoot.toAbsolutePath().normalize().startsWith(repo)) {
            // A released artifact: external by construction, not an editable input.
            return null;
        }
        Path classes = target.resolve(CLASSES);
        if (!Files.isDirectory(classes)) {
            return classesFromJar(entry);
        }
        return classes.toAbsolutePath().normalize();
    }

    /**
     * A cache restore can leave the module JAR without {@code target/classes} (the whole-module cache is
     * allowed to restore the packaged output and not the intermediate directory). The test index still needs
     * the class files, so extract the JAR's entries into a temporary directory rather than failing the gate
     * on a build-cache detail.
     */
    private static Path classesFromJar(Path jar) throws IOException {
        Path target = jar.getParent();
        Path classes = target.resolve(CLASSES);
        Files.createDirectories(classes);
        try (JarFile archive = new JarFile(jar.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                Path targetFile = classes.resolve(entry.getName()).normalize();
                if (!targetFile.startsWith(classes)) {
                    continue;
                }
                Files.createDirectories(targetFile.getParent());
                try (InputStream input = archive.getInputStream(entry)) {
                    Files.copy(input, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return classes.toAbsolutePath().normalize();
    }

    /** Two classpath entries with the same directory are one input, not two. */
    private static List<Path> distinctDirectories(List<Path> classpath) {
        Set<Path> seen = new LinkedHashSet<>();
        for (Path entry : classpath) {
            if (entry != null) {
                seen.add(entry.toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(seen);
    }

    private enum Kind {
        MAIN, TEST
    }

    /** The kind of project output {@code directory} is, or {@code null} for anything else (a jar, a dir). */
    private static Kind kindOf(Path directory) {
        Path target = directory.getParent();
        if (target == null || target.getFileName() == null
                || !TARGET.equals(target.getFileName().toString())) {
            return null;
        }
        String name = directory.getFileName() == null ? "" : directory.getFileName().toString();
        if (CLASSES.equals(name)) {
            return Kind.MAIN;
        }
        return TEST_CLASSES.equals(name) ? Kind.TEST : null;
    }

    /** Every {@code .class} file under {@code directory}, sorted — the walk's order never leaks out. */
    private static List<Path> classFiles(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .filter(path -> !path.getFileName().toString().equals("module-info.class"))
                    .sorted()
                    .toList();
        }
    }

    /** {@code module-info} and {@code package-info} are file metadata, not a type a document addresses. */
    private static boolean isMetadataClass(String binary) {
        return binary.equals("module-info") || binary.endsWith("/module-info")
                || binary.equals("package-info") || binary.endsWith("/package-info");
    }

    /**
     * The project FQNs one class file names — the constant pool's {@code ClassEntry} names, which is what
     * the plan means by "the compiled class file, not imports".
     *
     * <p>The class itself and its own nested types are not dependencies: a change to the file is what the
     * row is about, and a self-edge would make the closure report the test as its own dependency.</p>
     */
    private static Set<String> references(ClassModel model, String fqn, String topFqn) {
        Set<String> refs = new TreeSet<>();
        for (PoolEntry entry : model.constantPool()) {
            if (!(entry instanceof ClassEntry classEntry)) {
                continue;
            }
            String internal = classEntry.asInternalName();
            if (internal.startsWith("[")) {
                // An array descriptor names its element type elsewhere; the array is not a class row.
                continue;
            }
            String ref = internal.replace('/', '.');
            if (ref.equals(fqn) || ref.equals(topFqn) || ref.startsWith(topFqn + "$")) {
                continue;
            }
            refs.add(ref);
        }
        return refs;
    }

    /**
     * Reads {@code @Tag} from the class file's {@code RuntimeVisibleAnnotations}.
     *
     * <p>JUnit retains {@code @Tag} at runtime, and it is {@code @Repeatable(Tags.class)}: a class with two
     * tags writes one {@code Tag} annotation and one {@code Tags} container, so both spellings are read.
     * The source is not consulted. A tag is part of the lane contract (T2), and reading it from the class
     * file is what lets T2's contract test compare the index with the source without a second parse.</p>
     */
    private static Set<String> tags(ClassModel model) {
        Set<String> tags = new TreeSet<>();
        Optional<RuntimeVisibleAnnotationsAttribute> visible =
                model.findAttribute(Attributes.runtimeVisibleAnnotations());
        if (visible.isEmpty()) {
            return tags;
        }
        for (Annotation annotation : visible.get().annotations()) {
            String descriptor = annotation.className().stringValue();
            if (TAG_DESCRIPTOR.equals(descriptor)) {
                addTag(tags, annotation);
            } else if (TAGS_DESCRIPTOR.equals(descriptor)) {
                for (AnnotationElement element : annotation.elements()) {
                    if (!"value".equals(element.name().stringValue())
                            || !(element.value() instanceof AnnotationValue.OfArray array)) {
                        continue;
                    }
                    for (AnnotationValue value : array.values()) {
                        if (value instanceof AnnotationValue.OfAnnotation nested) {
                            addTag(tags, nested.annotation());
                        }
                    }
                }
            }
        }
        return tags;
    }

    private static void addTag(Set<String> tags, Annotation annotation) {
        for (AnnotationElement element : annotation.elements()) {
            if ("value".equals(element.name().stringValue())
                    && element.value() instanceof AnnotationValue.OfString string) {
                String value = string.stringValue();
                if (value != null && !value.isBlank()) {
                    tags.add(value);
                }
            }
        }
    }

    /**
     * The primary top-level type of every declaring file — the name {@code files} maps a path to.
     *
     * <p>A file names its type, so the type whose simple name matches the file is primary; a file that
     * declares a second top-level type (a package-private helper beside a public one) keeps that helper as
     * a row but maps the path to the primary, because the {@code files} table's value is one FQN per path
     * (plan § 4). Dependencies are resolved through this map, so a change to the file still selects a test
     * that names the helper: both are the same editable input.</p>
     */
    private static Map<String, String> primaryByFile(Map<String, Agg> topTypes) {
        Map<String, List<String>> byFile = new TreeMap<>();
        for (Agg agg : topTypes.values()) {
            byFile.computeIfAbsent(agg.file, key -> new ArrayList<>()).add(agg.topFqn);
        }
        Map<String, String> primary = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : byFile.entrySet()) {
            List<String> fqns = entry.getValue();
            fqns.sort(String::compareTo);
            String fileName = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            String base = fileName.endsWith(".java")
                    ? fileName.substring(0, fileName.length() - ".java".length())
                    : fileName;
            String chosen = fqns.get(0);
            for (String fqn : fqns) {
                if (fqn.substring(fqn.lastIndexOf('.') + 1).equals(base)) {
                    chosen = fqn;
                    break;
                }
            }
            primary.put(entry.getKey(), chosen);
        }
        return primary;
    }
    // ── classpath ────────────────────────────────────────────────────────────────────────────────

    /**
     * The test classpath, from the first source that names a real test classpath.
     *
     * <p>The order matters on both sides of this class. A forked JVM puts the full test classpath in
     * {@code surefire.test.class.path}; a direct {@code java -cp} invocation (the wrapper) puts it in
     * {@code java.class.path}; a booter-only {@code java.class.path} names a manifest jar instead, so the
     * context class loader is the last resort. A candidate is accepted only when it names at least one
     * {@code target/test-classes} and one {@code target/classes}, so a half-exported classpath cannot be
     * silently indexed as if it were complete.</p>
     */
    public static List<Path> resolveClasspath() {
        return resolveClasspath(null);
    }

    /** {@link #resolveClasspath()} with an explicit override, which wins when it is a test classpath. */
    public static List<Path> resolveClasspath(String override) {
        List<String> candidates = new ArrayList<>();
        if (override != null && !override.isBlank()) {
            candidates.add(override);
        }
        String surefire = System.getProperty("surefire.test.class.path");
        if (surefire != null && !surefire.isBlank()) {
            candidates.add(surefire);
        }
        String javaClasspath = System.getProperty("java.class.path");
        if (javaClasspath != null && !javaClasspath.isBlank()) {
            candidates.add(javaClasspath);
        }
        for (String candidate : candidates) {
            List<Path> entries = splitClasspath(candidate);
            if (isTestClasspath(entries)) {
                return entries;
            }
        }
        List<Path> urls = classLoaderEntries();
        if (isTestClasspath(urls)) {
            return urls;
        }
        throw new IllegalStateException("no test classpath found: tried an explicit value, "
                + "surefire.test.class.path, java.class.path and the context class loader, and none named "
                + "both a target/test-classes and a target/classes directory");
    }

    private static List<Path> splitClasspath(String value) {
        List<Path> entries = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (part.isBlank()) {
                continue;
            }
            try {
                entries.add(Path.of(part).toAbsolutePath().normalize());
            } catch (RuntimeException notAPath) {
                // A malformed entry is not a project output directory; the scan would skip it anyway.
            }
        }
        return entries;
    }

    private static boolean isTestClasspath(List<Path> entries) {
        boolean testClasses = false;
        boolean classes = false;
        for (Path entry : entries) {
            String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
            Path parent = entry.getParent();
            String parentName = parent == null || parent.getFileName() == null
                    ? "" : parent.getFileName().toString();
            if (!TARGET.equals(parentName)) {
                continue;
            }
            if (TEST_CLASSES.equals(name)) {
                testClasses = true;
            } else if (CLASSES.equals(name)) {
                classes = true;
            }
        }
        return testClasses && classes;
    }

    private static List<Path> classLoaderEntries() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (!(loader instanceof URLClassLoader urls)) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        for (URL url : urls.getURLs()) {
            if (!"file".equals(url.getProtocol())) {
                continue;
            }
            try {
                entries.add(Path.of(url.toURI()).toAbsolutePath().normalize());
            } catch (Exception notAPath) {
                // A class loader URL that is not a file path is not a project output directory.
            }
        }
        return entries;
    }
    // ── writer ──────────────────────────────────────────────────────────────────────────────────

    private static void write(Path output, String moduleName, Map<String, String> files,
                              Map<String, TestRow> tests, Map<String, ProductionRow> production)
            throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"format\": ").append(FORMAT).append(",\n");
        json.append("  \"module\": \"").append(MetadataJson.escape(moduleName)).append("\",\n");
        json.append("  \"sourceRoot\": \"").append(SOURCE_ROOT).append("\",\n");
        json.append("  \"testRoot\": \"").append(TEST_ROOT).append("\",\n");
        json.append("  \"classIndex\": \"").append(CLASS_INDEX).append("\",\n");
        appendFiles(json, files);
        json.append(",\n");
        appendTests(json, tests);
        json.append(",\n");
        appendProduction(json, production);
        json.append("\n}\n");

        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, json.toString(), StandardCharsets.UTF_8);
    }

    private static void appendFiles(StringBuilder json, Map<String, String> files) {
        json.append("  \"files\": {");
        if (files.isEmpty()) {
            json.append('}');
            return;
        }
        json.append('\n');
        int index = 0;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            json.append("    \"").append(MetadataJson.escape(entry.getKey())).append("\": \"")
                    .append(MetadataJson.escape(entry.getValue())).append('"');
            json.append(++index < files.size() ? ",\n" : "\n");
        }
        json.append("  }");
    }

    private static void appendTests(StringBuilder json, Map<String, TestRow> tests) {
        json.append("  \"tests\": {");
        if (tests.isEmpty()) {
            json.append('}');
            return;
        }
        json.append('\n');
        int index = 0;
        for (Map.Entry<String, TestRow> entry : tests.entrySet()) {
            TestRow row = entry.getValue();
            json.append("    \"").append(MetadataJson.escape(entry.getKey())).append("\": {\n");
            json.append("      \"file\": \"").append(MetadataJson.escape(row.file())).append("\",\n");
            json.append("      \"tags\": ");
            appendStrings(json, row.tags());
            json.append(",\n");
            json.append("      \"productionDeps\": ");
            appendStrings(json, row.productionDeps());
            json.append(",\n");
            json.append("      \"testDeps\": ");
            appendStrings(json, row.testDeps());
            json.append(",\n");
            json.append("      \"productionClosure\": ");
            appendStrings(json, row.productionClosure());
            json.append(",\n");
            json.append("      \"checksum\": \"").append(row.checksum()).append("\"\n");
            json.append("    }");
            json.append(++index < tests.size() ? ",\n" : "\n");
        }
        json.append("  }");
    }

    private static void appendProduction(StringBuilder json, Map<String, ProductionRow> production) {
        json.append("  \"production\": {");
        if (production.isEmpty()) {
            json.append('}');
            return;
        }
        json.append('\n');
        int index = 0;
        for (Map.Entry<String, ProductionRow> entry : production.entrySet()) {
            ProductionRow row = entry.getValue();
            json.append("    \"").append(MetadataJson.escape(entry.getKey())).append("\": {\n");
            json.append("      \"file\": \"").append(MetadataJson.escape(row.file())).append("\",\n");
            json.append("      \"module\": \"").append(MetadataJson.escape(row.module())).append("\",\n");
            json.append("      \"deps\": ");
            appendStrings(json, row.deps());
            json.append("\n    }");
            json.append(++index < production.size() ? ",\n" : "\n");
        }
        json.append("  }");
    }

    /** A JSON string array, inline; sorted because a set's iteration order is not semantic. */
    private static void appendStrings(StringBuilder json, Set<String> values) {
        json.append('[');
        int index = 0;
        for (String value : values) {
            if (index > 0) {
                json.append(", ");
            }
            json.append('"').append(MetadataJson.escape(value)).append('"');
            index++;
        }
        json.append(']');
    }

    private static String relativeSlash(Path base, Path file) {
        return base.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    /**
     * The checksum of the compiled class file's raw bytes.
     *
     * <p>Raw, not {@link ContentHash#of(Path)}: that normalises line endings for <em>text</em>, and a class
     * file is binary — a {@code \r\n} inside a string constant is content, not a checkout artefact. The
     * algorithm is still the repository's one content identity (DEC-029), so a selector compares like with
     * like.</p>
     */
    private static String checksum(Path classFile) throws IOException {
        return ContentHash.of(Files.readAllBytes(classFile));
    }
    // ── the command line (the wrapper's entry point) ────────────────────────────────────────────

    private static final String USAGE = """
            TestIndexGenerator — write <module>/.jcodebuddy/index/tests.json from compiled test classes

              --repo-root <dir>    the repository root the recorded paths are relative to (required)
              --module-dir <dir>   the test module to index (required)
              --classpath <list>   the test classpath, path-separator separated (default: the JVM's)
              --output <file>      the table to write (default: <module-dir>/.jcodebuddy/index/tests.json)
              --help               this text

            The test classpath is scanned for <module>/target/classes and <module>/target/test-classes
            directories; jars are external and are skipped. A project directory or class file the index
            cannot resolve is an error, never a silent drop.""".stripTrailing();

    public static void main(String[] args) {
        Map<String, String> options = new TreeMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println(USAGE);
                return;
            }
            if (!arg.startsWith("--")) {
                System.err.println("test-index: unexpected argument '" + arg + "'");
                System.err.println(USAGE);
                System.exit(2);
            }
            if (i + 1 >= args.length) {
                System.err.println("test-index: " + arg + " needs a value");
                System.err.println(USAGE);
                System.exit(2);
            }
            options.put(arg.substring(2), args[++i]);
        }
        String repoRoot = options.get("repo-root");
        String moduleDir = options.get("module-dir");
        if (repoRoot == null || moduleDir == null) {
            System.err.println("test-index: --repo-root and --module-dir are required");
            System.err.println(USAGE);
            System.exit(2);
        }

        try {
            Path repo = Path.of(repoRoot).toAbsolutePath().normalize();
            Path module = Path.of(moduleDir).toAbsolutePath().normalize();
            List<Path> classpath = resolveClasspath(options.get("classpath"));
            Path output = options.containsKey("output")
                    ? Path.of(options.get("output")).toAbsolutePath().normalize()
                    : defaultOutput(module);
            Result result = generate(repo, module, classpath, output);
            System.out.println("test-index: " + result.tests() + " test rows, " + result.production()
                    + " production rows, " + result.files() + " files -> "
                    + relativeSlash(repo, result.output()));
        } catch (RuntimeException | IOException failure) {
            System.err.println("test-index: " + failure.getMessage());
            System.exit(1);
        }
    }
}
