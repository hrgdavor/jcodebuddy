package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.core.FieldChange;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * E1 of {@code plans/hipster-entity-mvp.md}: a repeatable, consumer-shaped acceptance harness for
 * the MVP surface.
 *
 * <p>Everything here happens in a temporary module that is laid out the way a project outside this
 * repository would lay it out — a marker, one {@code @View} interface, a tiny hand-written consumer,
 * generated {@code .java} written back into {@code src/main/java}, and the pass's JSON/index output
 * under {@code .jcodebuddy/metadata/}. The test proves, in one pass over that tree:</p>
 *
 * <ol>
 *   <li>one full pass emits the field enum ({@code PersonSummary_}, with {@code META}), the record,
 *       the plain builder and the tracking builder into the source tree;</li>
 *   <li>the whole tree — generated source and the hand-written {@code Use} that calls
 *       {@code hr.hrg.hipster.entity.jackson.EntityJacksonMapper} — compiles with zero
 *       diagnostics against the harness classpath (that module is deliberately not a dependency of
 *       this one: the harness classpath is what makes the adapter part of the consumer-shaped
 *       compile);</li>
 *   <li>{@code META.create(Object[])}, the plain builder and the tracking builder actually work at
 *       runtime, loaded by a child class loader because the generated types cannot be named in this
 *       compilation unit;</li>
 *   <li>a second pass over the unchanged tree is byte-identical, which pins idempotence at the
 *       level a consumer sees.</li>
 * </ol>
 *
 * <p><strong>Why a child loader.</strong> The generated types are compiled into a temporary
 * directory and loaded with the harness classpath as a child loader. The shared contracts
 * ({@code ViewChangeTracking}, {@code FieldChange}) come from the parent loader because the
 * generated source names the same interfaces this module depends on, so behaviour is asserted
 * through that parent-loader contract exactly as {@link GeneratedTrackingBuilderContractTest}
 * does.</p>
 *
 * <p><strong>Why no Maven and no {@code proto/}.</strong> The generator is called directly, and the
 * classpath comes from {@link CompileHarness#generatedSourceClasspath(Path)}, which names the
 * reactor modules and the Jackson jars the adapter needs. Running the module's tests is the only
 * prerequisite; no install and no driver project is involved.</p>
 */
@Tag("generator")
@Tag("compiler")
class MvpAcceptanceTest {

    /** A consumer's entity marker: no accessors of its own, just the identity contract. */
    private static final String MARKER = """
            package gen.mvp;

            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;

            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {
            }
            """;

    /** One view at the top of the ladder, using record-style accessors. */
    private static final String VIEW = """
            package gen.mvp;

            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;

            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                Long id();
                String firstName();
                String lastName();
            }
            """;

    /**
     * The hand-written consumer. It is never run: compiling it is the assertion. It names the
     * Jackson adapter, the generated {@code META}, the generated record and the generated tracking
     * builder, so a change to any of those signatures fails the compile rather than a later
     * runtime surprise.
     */
    private static final String USE = """
            package gen.mvp;

            import hr.hrg.hipster.entity.jackson.EntityJacksonMapper;

            public final class Use {

                private Use() {
                }

                public static String changedFirstNameJson() {
                    PersonSummary view = PersonSummary_.META.create(new Object[]{1L, "Ada", "Lovelace"});
                    PersonSummaryRecord record =
                            new PersonSummaryRecord(view.id(), view.firstName(), view.lastName());
                    PersonSummaryBuilderTracking tracking = new PersonSummaryBuilderTracking(record);
                    tracking.firstName("Grace");
                    java.io.StringWriter out = new java.io.StringWriter();
                    EntityJacksonMapper.toJsonChanges(PersonSummary_.META, tracking, out);
                    return out.toString();
                }
            }
            """;

    /**
     * The artifacts a consumer's committed tree must contain after one {@code BUILDER_ALL} pass.
     * These are the names the current emitters produce: the compact ladder naming
     * ({@code <View>_}, {@code <View>Record}, {@code <View>Builder},
     * {@code <View>BuilderTracking}), discovered from the emitters rather than guessed.
     */
    private static final List<String> REQUIRED_ARTIFACTS = List.of(
            "PersonSummary_.java",             // field enum, carrying the static META
            "PersonSummaryRecord.java",        // record META.create(Object[]) constructs
            "PersonSummaryBuilder.java",       // plain builder
            "PersonSummaryBuilderTracking.java"); // tracking builder

    @Test
    void consumerShapedProjectGeneratesCompilesAndRuns() throws Exception {
        Path repoRoot = CompileHarness.findRepoRoot();

        // The consumer's module: an ordinary Maven source root and the derived .jcodebuddy/ subtree
        // (DEC-026). Nothing here is specific to this repository's own layout.
        Path moduleRoot = Files.createTempDirectory("mvp-consumer");
        Path sourceRoot = moduleRoot.resolve("src/main/java");
        Path packageRoot = sourceRoot.resolve("gen/mvp");
        Files.createDirectories(packageRoot);
        Path metadataOutputDir = moduleRoot.resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(metadataOutputDir);

        Files.writeString(packageRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(packageRoot.resolve("PersonSummary.java"), VIEW);
        Files.writeString(packageRoot.resolve("Use.java"), USE);

        // 1. One full pass, with the generated .java written into the source tree as a consumer's
        //    documented command does.
        EntityMetadataGenerator.generate(sourceRoot, metadataOutputDir, sourceRoot);

        List<Path> sources = CompileHarness.javaSourcesUnder(sourceRoot);
        List<String> sourceNames = sources.stream().map(path -> path.getFileName().toString()).toList();
        for (String artifact : REQUIRED_ARTIFACTS) {
            Assertions.assertTrue(sourceNames.contains(artifact),
                    "the pass must emit " + artifact + " into the source tree, got " + sourceNames);
        }
        Assertions.assertTrue(CompileHarness.javaSourcesUnder(metadataOutputDir).isEmpty(),
                "generated .java belongs in the source tree and never under .jcodebuddy/ (DEC-026)");

        // 2. Compile the whole tree — generated and hand-written together — which is what proves the
        //    Jackson adapter's generated-code contract through Use.java.
        Path classesDir = CompileHarness.compileOrFail(repoRoot, "mvp", sources, List.of());

        // 3. Runtime behaviour of the generated materializations. The generated types are child-loader
        //    types, so construction and setters are reflective; the change-tracking contract is read
        //    through the parent-loader interface the generated class genuinely implements.
        try (URLClassLoader loader = newGeneratedClassLoader(classesDir, repoRoot)) {
            Class<?> viewType = loader.loadClass("gen.mvp.PersonSummary");
            Class<?> fieldEnumType = loader.loadClass("gen.mvp.PersonSummary_");
            Class<?> builderType = loader.loadClass("gen.mvp.PersonSummaryBuilder");
            Class<?> trackingBuilderType = loader.loadClass("gen.mvp.PersonSummaryBuilderTracking");

            // META.create(Object[]): the positional creator, in field-enum ordinal order.
            Object meta = fieldEnumType.getField("META").get(null);
            Object view = meta.getClass()
                    .getMethod("create", Object[].class)
                    .invoke(meta, (Object) new Object[]{1L, "Ada", "Lovelace"});
            Assertions.assertEquals("Ada", access(view, "firstName"), "META.create fills the positional array");
            Assertions.assertEquals("Lovelace", access(view, "lastName"));
            Assertions.assertEquals(1L, access(view, "id"));

            // The plain builder: copy-construct from the view, change one field, build.
            Object builder = builderType.getDeclaredConstructor(viewType).newInstance(view);
            builderType.getMethod("firstName", String.class).invoke(builder, "Grace");
            Object rebuilt = builderType.getMethod("build").invoke(builder);
            Assertions.assertEquals("Grace", access(rebuilt, "firstName"), "the plain builder's change is built");
            Assertions.assertEquals("Lovelace", access(rebuilt, "lastName"), "untouched fields survive the copy");

            // The tracking builder: the same fluent setter, but it records the write.
            Object tracking = trackingBuilderType.getDeclaredConstructor(viewType).newInstance(view);
            trackingBuilderType.getMethod("firstName", String.class).invoke(tracking, "Grace");
            Assertions.assertInstanceOf(ViewChangeTracking.class, tracking,
                    "the generated tracking builder implements the module's ViewChangeTracking");
            ViewChangeTracking<?, ?> changeTracking = (ViewChangeTracking<?, ?>) tracking;
            Assertions.assertTrue(changeTracking.isChanged(), "a differing write marks the builder changed");
            List<? extends FieldChange<?>> changed = changeTracking.changedValues();
            Assertions.assertEquals(1, changed.size(), "exactly the written field is reported: " + changed);
            Assertions.assertEquals("firstName", changed.get(0).fieldName());
            Assertions.assertEquals("Grace", changed.get(0).current());
            Assertions.assertEquals("Ada", access(view, "firstName"),
                    "the baseline view the builder copied from keeps the old value");
        }

        // 4. Idempotence at the consumer level: the second pass reads its own output and must not
        //    change a byte of any .java file — the generated materializations or the hand-written
        //    view the pass augmented with builder entry points.
        Map<Path, byte[]> firstPass = readJavaTree(sourceRoot);
        EntityMetadataGenerator.generate(sourceRoot, metadataOutputDir, sourceRoot);
        Map<Path, byte[]> secondPass = readJavaTree(sourceRoot);

        Assertions.assertEquals(firstPass.keySet(), secondPass.keySet(),
                "a second pass over an unchanged tree must produce the same file set");
        for (Path file : firstPass.keySet()) {
            Assertions.assertArrayEquals(firstPass.get(file), secondPass.get(file),
                    "a second pass over an unchanged tree must be byte-identical: "
                            + moduleRoot.relativize(file));
        }
    }

    /**
     * The child loader: the compiled tree first, then the exact classpath the generated sources were
     * compiled against, so api/core/jackson types resolve as they did at compile time.
     */
    private static URLClassLoader newGeneratedClassLoader(Path classesDir, Path repoRoot) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(classesDir.toUri().toURL());
        for (String entry : CompileHarness.generatedSourceClasspath(repoRoot)
                .split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), MvpAcceptanceTest.class.getClassLoader());
    }

    /** Reads one accessor off a generated instance, which this compilation unit cannot name. */
    private static Object access(Object generated, String accessor) throws Exception {
        return generated.getClass().getMethod(accessor).invoke(generated);
    }

    /** Every {@code .java} byte under {@code root}, keyed by path so two passes compare file by file. */
    private static Map<Path, byte[]> readJavaTree(Path root) throws Exception {
        Map<Path, byte[]> tree = new TreeMap<>();
        for (Path file : CompileHarness.javaSourcesUnder(root)) {
            tree.put(file, Files.readAllBytes(file));
        }
        return tree;
    }
}
