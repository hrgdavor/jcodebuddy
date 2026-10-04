package hr.hrg.hipster.ioc.tooling;

import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The context generator end to end: a small tree in, committed Java out, compiled by the JDK it was
 * generated for.
 *
 * <p>Compiling the result is the assertion that matters most and the one a string-matching test cannot
 * make. A generator's output is source code, so "the file exists and mentions the right bean" can be true of
 * a file no compiler accepts — and the fixture covers the two shapes most likely to produce that: a factory
 * the interface inherits from a package-private module, and a factory parameter the context itself provides.</p>
 */
class IocContextGeneratorTest {

    private static final String GADGET = """
            package ioc.fixture;

            public class Gadget {
            }
            """;

    private static final String WIDGET = """
            package ioc.fixture;

            public class Widget {
                private final Gadget gadget;

                public Widget(Gadget gadget) {
                    this.gadget = gadget;
                }

                public Gadget gadget() {
                    return gadget;
                }
            }
            """;

    /** The module interface: package-private, with the factory the context inherits as a default method. */
    private static final String MODULE = """
            package ioc.fixture;

            interface AppModule {
                default Widget buildWidget(Gadget gadget) {
                    return new Widget(gadget);
                }
            }
            """;

    private static final String CONTEXT = """
            package ioc.fixture;

            import hr.hrg.hipster.ioc.HipsterContext;

            @HipsterContext
            public interface AppContext extends AppModule {
                Gadget gadget();

                Widget widget();
            }
            """;

    /** The whole standard fixture: a bean, a bean built by a factory, and a context that declares both. */
    private Path tree(Path dir) throws IOException {
        Path root = dir.resolve("src");
        Path packageDir = Files.createDirectories(root.resolve("ioc/fixture"));
        Files.writeString(packageDir.resolve("Gadget.java"), GADGET);
        Files.writeString(packageDir.resolve("Widget.java"), WIDGET);
        Files.writeString(packageDir.resolve("AppModule.java"), MODULE);
        Files.writeString(packageDir.resolve("AppContext.java"), CONTEXT);
        return root;
    }

    /** The standard tree with the context interface replaced. */
    private Path treeWith(Path dir, String contextSource) throws IOException {
        Path root = tree(dir);
        Files.writeString(root.resolve("ioc/fixture/AppContext.java"), contextSource);
        return root;
    }

    /** The standard tree with a different module interface too. */
    private Path treeWith(Path dir, String moduleSource, String contextSource) throws IOException {
        Path root = treeWith(dir, contextSource);
        Files.writeString(root.resolve("ioc/fixture/AppModule.java"), moduleSource);
        return root;
    }

    /** A two-bean cycle: the module declares the factories, the context declares the beans. */
    private Path cycleTree(Path dir, String moduleSource) throws IOException {
        Path root = dir.resolve("src");
        Path packageDir = Files.createDirectories(root.resolve("ioc/fixture"));
        Files.writeString(packageDir.resolve("CycleModule.java"), moduleSource);
        Files.writeString(packageDir.resolve("CycleContext.java"), """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.HipsterContext;

                @HipsterContext
                public interface CycleContext extends CycleModule {
                    A a();

                    B b();
                }
                """);
        return root;
    }

    /**
     * The tree for the two-phase form: a marked cycle whose bean classes are <strong>public and in their own
     * files</strong>, so the test can load the compiled context and ask each bean for its peer.
     *
     * <p>{@link #cycleTree} deliberately keeps its fixture package-private in one file, which is enough for
     * "does it generate" and not enough for "does it run": a method on a package-private class is not
     * invocable from another package without opening it up, and a test that needs reflection tricks to prove a
     * generator's output works is a test that will be weakened later.</p>
     */
    private Path supplierCycleTree(Path dir) throws IOException {
        Path root = dir.resolve("src");
        Path packageDir = Files.createDirectories(root.resolve("ioc/fixture"));
        Files.writeString(packageDir.resolve("CycleModule.java"), """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.Circular;
                import java.util.function.Supplier;

                interface CycleModule {
                    default A buildA(@Circular Supplier<B> b) {
                        return new A(b);
                    }

                    default B buildB(A a) {
                        return new B(a);
                    }
                }
                """);
        Files.writeString(packageDir.resolve("CycleContext.java"), """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.HipsterContext;

                @HipsterContext
                public interface CycleContext extends CycleModule {
                    A a();

                    B b();
                }
                """);
        Files.writeString(packageDir.resolve("A.java"), """
                package ioc.fixture;

                import java.util.function.Supplier;

                public class A {
                    private final Supplier<B> b;

                    A(Supplier<B> b) {
                        this.b = b;
                    }

                    public B peer() {
                        return b.get();
                    }
                }
                """);
        Files.writeString(packageDir.resolve("B.java"), """
                package ioc.fixture;

                public class B {
                    private final A a;

                    B(A a) {
                        this.a = a;
                    }

                    public A peer() {
                        return a;
                    }
                }
                """);
        return root;
    }

    /**
     * DEC-036 § 3's `init*` hooks: the order that creates the beans is the order that initialises them, and a
     * hook runs <strong>after</strong> its bean exists and <strong>before</strong> anything that depends on it.
     *
     * <p>The trace is what makes the claim checkable rather than plausible: the bean constructors write
     * {@code newB}/{@code newA} and the hooks write {@code initB}/{@code initA}, so the exact sequence
     * {@code [newB, initB, newA, initA]} shows both halves at once — B is built and initialised before A is
     * built, and each hook is handed a non-null bean (a hook that received {@code null} would record
     * {@code initB:NULL} instead).</p>
     */
    @Test
    void initHooksRunInCreationOrderAndAfterTheirBeanExists(@TempDir Path dir) throws Exception {
        Path root = hookTree(dir);
        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(0, result.refused(), "hooks refuse nothing: " + result.divergences());
        String source = Files.readString(impl(root, "HookContext"));
        Assertions.assertTrue(source.contains("initB(b);") && source.contains("initA(a);"),
                "the hook is called with the bean it initialises:\n" + source);
        compile(root);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> implClass = loader.loadClass("ioc.fixture.HookContextImpl");
            implClass.getDeclaredConstructor().newInstance();
            List<?> order = (List<?>) loader.loadClass("ioc.fixture.Trace").getField("ORDER").get(null);

            Assertions.assertEquals(List.of("newB", "initB", "newA", "initA"), order,
                    "each hook runs after its own bean and before that bean's dependents; the no-argument and "
                            + "non-bean-typed init* methods are left alone entirely");
        }
    }

    /**
     * Two hooks for one bean are reported, because "which initialiser runs" must not be answered by declaration
     * order — the first is used and the second is named in a {@code init_hook_ambiguous} diagnostic.
     */
    @Test
    void twoHooksForOneBeanAreReported(@TempDir Path dir) throws Exception {
        Path root = hookTree(dir, HOOK_MODULE.replace("default void initB(B b)",
                "default void initAlsoB(B b) {\n            Trace.ORDER.add(\"initAlsoB\");\n        }\n\n        default void initB(B b)"));

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertTrue(result.divergences().stream()
                        .anyMatch(e -> e.startsWith("kind=init_hook_ambiguous")),
                "the second hook is named rather than silently dropped: " + result.divergences());
    }

    /**
     * The tree for the hook tests: a two-bean chain whose factories and hooks all record into {@code Trace}, so
     * the generated context's behaviour can be read back as a sequence.
     */
    private Path hookTree(Path dir) throws IOException {
        return hookTree(dir, HOOK_MODULE);
    }

    private Path hookTree(Path dir, String moduleSource) throws IOException {
        Path root = dir.resolve("src");
        Path packageDir = Files.createDirectories(root.resolve("ioc/fixture"));
        Files.writeString(packageDir.resolve("HookModule.java"), moduleSource);
        Files.writeString(packageDir.resolve("HookContext.java"), """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.HipsterContext;

                @HipsterContext
                public interface HookContext extends HookModule {
                    A a();

                    B b();
                }
                """);
        Files.writeString(packageDir.resolve("Trace.java"), """
                package ioc.fixture;

                import java.util.ArrayList;
                import java.util.List;

                public class Trace {
                    public static final List<String> ORDER = new ArrayList<>();
                }
                """);
        Files.writeString(packageDir.resolve("A.java"), """
                package ioc.fixture;

                public class A {
                    A(B b) {
                        Trace.ORDER.add("newA");
                    }
                }
                """);
        Files.writeString(packageDir.resolve("B.java"), """
                package ioc.fixture;

                public class B {
                    B() {
                        Trace.ORDER.add("newB");
                    }
                }
                """);
        return root;
    }

    /**
     * DEC-036 § 9: a large context gets region markers around its three sections, so a reader — or a hand edit —
     * can find them. 6 beans is over all three thresholds.
     */
    @Test
    void regionMarkersAppearOnlyAboveTheirThresholds(@TempDir Path dir) throws Exception {
        Path root = manyBeansTree(dir, 6);

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(0, result.refused(), "nothing here is refused: " + result.divergences());
        String source = Files.readString(impl(root, "ManyContext"));
        for (String id : List.of("fields", "accessors", "factories")) {
            Assertions.assertTrue(source.contains("// @generated region begin " + id + " "),
                    "a 6-bean context is over the " + id + " threshold:\n" + source);
            Assertions.assertTrue(source.contains("// @generated region end " + id + "\n"),
                    "and every begin has its matching end, by id (DEC-035):\n" + source);
        }
        compile(root);
    }

    /**
     * The other side of every threshold, which is where a marker would be noise forever: 3 beans is under the
     * section thresholds and 4 is over them but still under the field one.
     */
    @Test
    void regionMarkersStayAwayBelowTheirThresholds(@TempDir Path dir) throws Exception {
        Path small = manyBeansTree(Files.createDirectories(dir.resolve("small")), 3);
        IocGeneration.Result smallResult = IocGeneration.generate(small, small, "    ");
        Assertions.assertEquals(0, smallResult.refused(), String.valueOf(smallResult.divergences()));
        String smallSource = Files.readString(impl(small, "ManyContext"));
        Assertions.assertFalse(smallSource.contains("region begin"),
                "3 beans is under every threshold, and a marker per small section is noise:\n" + smallSource);

        Path medium = manyBeansTree(Files.createDirectories(dir.resolve("medium")), 4);
        IocGeneration.Result mediumResult = IocGeneration.generate(medium, medium, "    ");
        Assertions.assertEquals(0, mediumResult.refused(), String.valueOf(mediumResult.divergences()));
        String mediumSource = Files.readString(impl(medium, "ManyContext"));
        Assertions.assertTrue(mediumSource.contains("// @generated region begin accessors "),
                "4 beans is over the section thresholds:\n" + mediumSource);
        Assertions.assertFalse(mediumSource.contains("// @generated region begin fields "),
                "but still under the field threshold, which needs more than five:\n" + mediumSource);
    }

    /**
     * A context with {@code count} independent beans of one type, which is the cheapest tree that can cross the
     * thresholds. The beans are independent on purpose: the sections are what is under test, not the ordering.
     */
    private Path manyBeansTree(Path dir, int count) throws IOException {
        Path root = dir.resolve("src");
        Path packageDir = Files.createDirectories(root.resolve("ioc/fixture"));
        StringBuilder module = new StringBuilder("package ioc.fixture;\n\ninterface ManyModule {\n");
        StringBuilder context = new StringBuilder("package ioc.fixture;\n\n")
                .append("import hr.hrg.hipster.ioc.HipsterContext;\n\n")
                .append("@HipsterContext\npublic interface ManyContext extends ManyModule {\n");
        for (int i = 1; i <= count; i++) {
            module.append("    default Leaf buildLeaf").append(i).append("() {\n")
                    .append("        return new Leaf(\"").append(i).append("\");\n    }\n\n");
            context.append("    Leaf leaf").append(i).append("();\n");
        }
        Files.writeString(packageDir.resolve("ManyModule.java"), module.append("}\n").toString());
        Files.writeString(packageDir.resolve("ManyContext.java"), context.append("}\n").toString());
        Files.writeString(packageDir.resolve("Leaf.java"), """
                package ioc.fixture;

                public class Leaf {
                    public Leaf(String id) {
                    }
                }
                """);
        return root;
    }

    private static Path impl(Path root, String simpleName) {
        return root.resolve("ioc/fixture/" + simpleName + "Impl.java");
    }

    @Test
    void generatesAnImplementationInDependencyOrderAndItCompiles(@TempDir Path dir) throws Exception {
        Path root = tree(dir);

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(1, result.contextsRead(), "one context in the tree");
        Assertions.assertEquals(1, result.filesWritten(), "and one implementation written");
        Assertions.assertEquals(List.of(), result.divergences(), "a well-formed context reports nothing");

        String text = Files.readString(impl(root, "AppContext"));
        Assertions.assertTrue(text.startsWith("// @generated file " + IocContextGenerator.class.getName()),
                "DEC-035's file marker is the first line, so a parser recognises the file: " + text);
        Assertions.assertTrue(text.contains("{enabled:true"),
                "and DEC-021's config line follows it: " + text.substring(0, Math.min(400, text.length())));
        Assertions.assertTrue(text.contains("public class AppContextImpl implements AppContext"),
                "the class implements the interface directly — no proxy, no registry (DEC-019)");

        int gadget = text.indexOf("this.gadget = new Gadget();");
        int widget = text.indexOf("this.widget = buildWidget(gadget);");
        Assertions.assertTrue(gadget > 0, "the bean with no factory is created from its constructor");
        Assertions.assertTrue(widget > gadget,
                "and the bean that needs it is created afterwards, reading the field it depends on");
        Assertions.assertTrue(text.contains("public Widget widget()"), "every bean has its accessor");
        Assertions.assertTrue(text.contains("public Gadget gadget()"));

        compile(root);

        Map<?, ?> graph = new ObjectMapper().readValue(Files.readString(result.graphFile()), Map.class);
        Assertions.assertEquals(IocContextGenerator.NAME, graph.get("generator"));
        List<?> contexts = (List<?>) graph.get("contexts");
        Assertions.assertEquals(1, contexts.size(), "the graph names the context it generated");
        Map<?, ?> entry = (Map<?, ?>) contexts.get(0);
        Assertions.assertEquals("ioc.fixture.AppContext", entry.get("context"));
        Assertions.assertEquals(2, ((List<?>) entry.get("beans")).size());
    }

    @Test
    void aFactoryParameterTheContextDoesNotProvideBecomesAConstructorParameter(@TempDir Path dir) throws Exception {
        Path root = treeWith(dir, """
                package ioc.fixture;

                interface AppModule {
                    default Widget buildWidget(Gadget gadget, java.time.Clock clock) {
                        return new Widget(gadget);
                    }
                }
                """, CONTEXT);

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(List.of(), result.divergences(),
                "a parameter the context cannot provide is not an error: the caller supplies it");
        String text = Files.readString(impl(root, "AppContext"));
        Assertions.assertTrue(text.contains("public AppContextImpl(java.time.Clock clock)"),
                "the generated constructor takes it: " + text);
        Assertions.assertTrue(text.contains("this.widget = buildWidget(gadget, clock)"),
                "and the factory receives it from the field, never a null");
        Assertions.assertFalse(text.contains("null"), "the generator never invents a null");
    }

    @Test
    void aSecondPassWritesNothingAndTheTextIsTheSame(@TempDir Path dir) throws Exception {
        Path root = tree(dir);

        IocGeneration.generate(root, root, "    ");
        String first = Files.readString(impl(root, "AppContext"));

        IocGeneration.Result second = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(0, second.filesWritten(),
                "an unchanged file is not rewritten: touching it would show a diff-free change in every "
                        + "tool that watches mtimes");
        Assertions.assertEquals(first, Files.readString(impl(root, "AppContext")));
    }

    @Test
    void aFrozenFileIsLeftExactlyAsItIs(@TempDir Path dir) throws Exception {
        Path root = tree(dir);
        IocGeneration.generate(root, root, "    ");

        Path file = impl(root, "AppContext");
        String takenOver = Files.readString(file)
                .replace("{enabled:true", "{enabled:false")
                .replaceFirst("\\}\\s*$", """
                            /** Mine: this context is hand-maintained now. */
                            public String owner() {
                                return "me";
                            }
                        }
                        """);
        Files.writeString(file, takenOver);

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(takenOver, Files.readString(file),
                "enabled:false freezes the whole file (DEC-018/DEC-021 § 6, implemented in step 1.1)");
        Assertions.assertTrue(result.divergences().stream().anyMatch(e -> e.startsWith("kind=file_frozen")),
                "and the pass says it skipped the file rather than being silent: " + result.divergences());
        Assertions.assertEquals(0, result.filesWritten());
    }

    @Test
    void anUnmarkedCycleIsRefusedWithADiagnostic(@TempDir Path dir) throws Exception {
        Path root = cycleTree(dir, CYCLE_MODULE);

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(1, result.refused(), "a cycle no parameter marks cannot be created at all");
        Assertions.assertTrue(result.divergences().stream()
                        .anyMatch(e -> e.startsWith("kind=circular_dependency_unmarked")),
                "and the diagnostic names it: " + result.divergences());
        Assertions.assertFalse(Files.exists(impl(root, "CycleContext")),
                "nothing is written: code that cannot run is worse than an absent file");
    }

    /**
     * The two-phase form (DEC-036 § 5): a marked cycle is generated, compiled, <strong>and run</strong>.
     *
     * <p>The last assertion is the one this step exists for. Compiling proves the shape is legal Java; running
     * the context and asking each bean for its peer proves the cycle is actually closed — the supplier A holds
     * resolves to the very B the context built, and B holds that same A. A string-matching test could not tell
     * the difference between a closed cycle and a lambda that returns {@code null}.</p>
     */
    @Test
    void aMarkedCircularParameterClosesTheCycleAndTheGeneratedCodeRuns(@TempDir Path dir) throws Exception {
        Path root = supplierCycleTree(dir);

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(0, result.refused(),
                "a marked cycle is created in two phases rather than refused: " + result.divergences());
        String source = Files.readString(impl(root, "CycleContext"));
        Assertions.assertTrue(source.contains("() -> b()"),
                "the marked edge renders as a call to the accessor of the bean it supplies, which is what "
                        + "makes it navigable and what defers it past construction:\n" + source);
        compile(root);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> implClass = loader.loadClass("ioc.fixture.CycleContextImpl");
            Object context = implClass.getDeclaredConstructor().newInstance();
            Object a = implClass.getMethod("a").invoke(context);
            Object b = implClass.getMethod("b").invoke(context);

            Assertions.assertSame(b, a.getClass().getMethod("peer").invoke(a),
                    "A's supplier resolves to the B the context built");
            Assertions.assertSame(a, b.getClass().getMethod("peer").invoke(b),
                    "and B holds that same A, so the cycle is closed at runtime");
        }
    }

    /**
     * A marked parameter that is not a {@code Supplier} cannot be resolved after construction, so it is refused
     * with its own diagnostic — the marked edge is a request the generator can only honour one way.
     */
    @Test
    void aMarkedCircularParameterThatIsNotASupplierIsRefused(@TempDir Path dir) throws Exception {
        Path root = cycleTree(dir, CYCLE_MODULE.replace("buildA(B b)", "buildA(@Circular B b)"));

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(1, result.refused());
        Assertions.assertTrue(result.divergences().stream()
                        .anyMatch(e -> e.startsWith("kind=circular_dependency_needs_supplier")),
                "the diagnostic names the shape it needs: " + result.divergences());
        Assertions.assertFalse(Files.exists(impl(root, "CycleContext")),
                "and nothing is written until the shape is right");
    }

    @Test
    void aLazyBeanWithoutAFactoryIsRefused(@TempDir Path dir) throws Exception {
        Path root = treeWith(dir, """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.HipsterContext;
                import java.util.function.Supplier;

                @HipsterContext
                public interface AppContext extends AppModule {
                    Supplier<Gadget> gadget();
                }
                """);

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(1, result.refused(),
                "new Supplier<Gadget>() is not even valid Java, and inventing one would capture a bean "
                        + "nobody built (DEC-036 § 8)");
        Assertions.assertTrue(result.divergences().stream()
                .anyMatch(e -> e.startsWith("kind=lazy_bean_needs_factory")),
                String.valueOf(result.divergences()));
    }

    @Test
    void aContextThatNamesItsOwnImplementationIsSkipped(@TempDir Path dir) throws Exception {
        Path root = treeWith(dir, """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.HipsterContext;

                @HipsterContext(impl = Object.class)
                public interface AppContext extends AppModule {
                    Gadget gadget();
                }
                """);

        IocGeneration.Result result = IocGeneration.generate(root, root, "    ");

        Assertions.assertEquals(1, result.refused());
        Assertions.assertTrue(result.divergences().stream()
                        .anyMatch(e -> e.startsWith("kind=context_implementation_present")),
                String.valueOf(result.divergences()));
        Assertions.assertFalse(Files.exists(impl(root, "AppContext")),
                "the generator does not compete with an implementation that already exists");
    }

    @Test
    void aContextWithAParentGeneratesTheParentAccessors(@TempDir Path dir) throws Exception {
        Path root = treeWith(dir, """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.ChildContext;
                import hr.hrg.hipster.ioc.HipsterContext;

                @HipsterContext
                public interface AppContext extends AppModule, ChildContext<AppContext> {
                    Gadget gadget();
                }
                """);

        IocGeneration.generate(root, root, "    ");

        String text = Files.readString(impl(root, "AppContext"));
        Assertions.assertTrue(text.contains("public AppContext getParent()"), text);
        Assertions.assertTrue(text.contains("public void setParent(AppContext parent)"), text);
        compile(root);
    }

    /**
     * The whole tree is compiled by the JDK running the test.
     *
     * <p>The classpath is the test JVM's own, which is what makes the fixture's {@code HipsterContext}
     * reference resolvable — the API is a dependency of this module, and a compile check that needed a
     * hand-built classpath would be testing the classpath instead of the generator.</p>
     */
    private static void compile(Path root) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assertions.assertNotNull(compiler, "the test runs on a JDK, so a compiler is available");
        List<String> sources;
        try (Stream<Path> stream = Files.walk(root)) {
            sources = stream.filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<String> args = new ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        args.addAll(sources);

        // javac's own report is captured and shown on failure: "must compile" without the compiler's
        // message leaves the next reader to re-run it by hand.
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int status = compiler.run(null, output, output, args.toArray(String[]::new));
        Assertions.assertEquals(0, status,
                "the generated tree must compile:\n" + output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The hook fixture: a factory for each bean, a hook for each bean, and two methods that merely start with
     * {@code init} — one with no parameter, one whose parameter type is not a bean this context builds. Neither
     * of those is a hook, and the trace proves the generator left them alone.
     */
    private static final String HOOK_MODULE = """
            package ioc.fixture;

            interface HookModule {
                default A buildA(B b) {
                    return new A(b);
                }

                default B buildB() {
                    return new B();
                }

                default void initA(A a) {
                    Trace.ORDER.add("initA" + (a == null ? ":NULL" : ""));
                }

                default void initB(B b) {
                    Trace.ORDER.add("initB" + (b == null ? ":NULL" : ""));
                }

                default void initNothing() {
                    Trace.ORDER.add("initNothing");
                }

                default void initNotABean(String value) {
                    Trace.ORDER.add("initNotABean");
                }
            }
            """;

    /**
     * Two beans whose factories reference each other.
     *
     * <p>One fixture serves both cycle tests: the marked variant is this text with {@code @Circular} added to
     * a parameter, so the two diagnostics differ only by the thing they are diagnosing.</p>
     */
    private static final String CYCLE_MODULE = """
            package ioc.fixture;

            import hr.hrg.hipster.ioc.Circular;

            interface CycleModule {
                default A buildA(B b) {
                    return new A(b);
                }

                default B buildB(A a) {
                    return new B(a);
                }
            }

            class A {
                A(B b) {
                }
            }

            class B {
                B(A a) {
                }
            }
            """;

    /**
     * The reading half on its own, so a failure says whether the model or the rendering is wrong.
     *
     * <p>Worth its own test for a second reason: this is where the LST quirks the generator depends on are
     * pinned — an interface's {@code extends} clause living in {@code getImplements()}, a {@code default}
     * modifier being a keyword, and a factory method being recognised by the bean it builds.</p>
     */
    @Test
    void readsTheContextModelFromTheIndex(@TempDir Path dir) throws Exception {
        Path root = tree(dir);
        DivergenceReporter divergences = new DivergenceReporter();

        ClassIndex index = indexOf(root, root);
        java.util.Optional<IocModel.Context> read =
                ContextReader.read(contextRow(index, "ioc.fixture.AppContext"), index, divergences);

        Assertions.assertTrue(read.isPresent(), "the row declares a context: " + divergences.render());
        IocModel.Context model = read.get();
        Assertions.assertEquals("ioc.fixture", model.packageName());
        Assertions.assertEquals("AppContext", model.simpleName());
        Assertions.assertEquals(List.of("gadget", "widget"),
                model.beans().stream().map(IocModel.Bean::name).toList(),
                "beans are the interface's no-arg accessors, in declaration order");
        Assertions.assertEquals(List.of("Gadget", "Widget"),
                model.beans().stream().map(IocModel.Bean::typeText).toList());
        Assertions.assertEquals(List.of("widget"), List.copyOf(model.factories().keySet()),
                "buildWidget on the module interface is the factory for the widget bean: " + model.factories());
        Assertions.assertEquals(List.of("gadget"),
                model.factories().get("widget").parameters().stream().map(IocModel.Parameter::name).toList());
        Assertions.assertFalse(model.hasImplementation());
        Assertions.assertFalse(model.hasParent());
        Assertions.assertTrue(model.importLines().stream().noneMatch(line -> line.contains("HipsterContext")),
                "the imports come from the index's sidecar, with the marker annotation's own import dropped — a"
                        + " generated class implements the interface and never names the annotation: "
                        + model.importLines());
    }

    /**
     * The point of step 3.0e part two: the generator reads the <strong>model</strong>, not the sources.
     *
     * <p>The test deletes the context interface's own file after the index is built and then generates. It still
     * produces the right implementation, which it could not do if it were reading the file it was pointed at —
     * and the file it does still read is the one it <em>writes</em>, because cooperative codegen has to recognise
     * its own previous output (DEC-020). That is a different question from where the facts come from, and this is
     * the test that keeps them apart.</p>
     */
    @Test
    void generatesFromTheModelWithTheSourceFileGone(@TempDir Path dir) throws Exception {
        Path root = tree(dir);
        ClassIndex index = indexOf(root, root);
        ClassRecord contextRow = contextRow(index, "ioc.fixture.AppContext");

        Files.delete(root.resolve("ioc/fixture/AppContext.java"));
        Assertions.assertFalse(Files.exists(root.resolve("ioc/fixture/AppContext.java")),
                "the source the facts came from is gone");

        IocContextGenerator.GeneratedContext generated =
                new IocContextGenerator().generate(contextRow, index, root, "    ");

        Assertions.assertFalse(generated.refused(), "it generated: " + generated.divergences());
        Assertions.assertTrue(generated.source().contains("class AppContextImpl"),
                "the implementation it renders names the context it only knows from the model");
        Assertions.assertTrue(generated.source().contains("Gadget gadget"),
                "and its beans, which the model recorded as the interface's accessors");
        Assertions.assertTrue(generated.source().contains("public AppContextImpl("),
                "and the factory parameter that becomes a constructor parameter");
    }

    /** Every {@code .java} file under {@code root} indexed through the engine — what a pass does first. */
    private static ClassIndex indexOf(Path root, Path moduleRoot) throws IOException {
        ClassIndex index = ClassIndex.forPass(moduleRoot.resolve(".jcodebuddy"), moduleRoot, root);
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String relative = moduleRoot.relativize(file).toString().replace('\\', '/');
                String source = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                hr.hrg.jcodebuddy.engine.source.SourceReader.Read read =
                        hr.hrg.jcodebuddy.engine.source.SourceReader.readText(source);
                Assertions.assertTrue(read.readable(), relative + " must parse");
                index.addTypes(relative, read.unit(), source, false);
            }
        }
        // A pass writes its table, which is also what stamps the file facts a relation's SLICE needs to be usable.
        index.write();
        return index;
    }

    private static ClassRecord contextRow(ClassIndex index, String fqn) {
        ClassRecord row = index.row(fqn);
        Assertions.assertNotNull(row, fqn + " must be indexed: " + index.rows().stream()
                .map(ClassRecord::fqn).toList());
        return row;
    }

    /** A guard that the kinds this generator produces are the kinds the project's vocabulary lists. */
    @Test
    void theDiagnosticKindsThisGeneratorProducesAreInTheProjectsVocabulary() {
        for (String kind : List.of("circular_dependency_unmarked", "circular_dependency_needs_supplier",
                "init_hook_ambiguous", "lazy_bean_needs_factory", "context_implementation_present")) {
            Assertions.assertTrue(DivergenceReporter.KINDS.contains(kind),
                    "a diagnostic nobody can find in the vocabulary is a private dialect: " + kind);
        }
        Assertions.assertFalse(DivergenceReporter.KINDS.contains("circular_dependency_marked_unsupported"),
                "the kind that said the two-phase form was unimplemented is retired: step 3.4 implemented it, "
                        + "and a vocabulary entry nothing can produce is a promise rather than a diagnostic");
    }
}
