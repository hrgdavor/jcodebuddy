package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared support for tests that generate source and then prove it compiles
 * (plan.dsflash.md § 0.5).
 *
 * <p>The {@code javax.tools} harness was factored out of
 * {@code EntityMetadataGeneratorTest.compileSources}, where it already existed — task 0.5 says to
 * extract and grow it, not to write a compiler harness from scratch. Every generator level in
 * Phase 3 grows the assertions that use this class.</p>
 */
final class CompileHarness {

    private CompileHarness() {
    }

    /** Locates the repository root by walking up from the working directory to the nearest POM. */
    static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("pom.xml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate the repository root from " + Path.of("").toAbsolutePath());
        }
        // The nearest pom.xml is the module POM when the test runs inside a module directory.
        if (current.getFileName() != null && current.getFileName().toString().startsWith("hipster-entity-")
                && current.getParent() != null && Files.exists(current.getParent().resolve("pom.xml"))) {
            return current.getParent();
        }
        return current;
    }

    /**
     * The classpath the generated sources are compiled against.
     *
     * <p>{@code api} and {@code core} cover every generated materialization. The {@code jackson}
     * module and its third-party jars are added so the whole example tree — including the runnable
     * {@code PersonDemo}, which prints a change set as JSON — compiles as one unit. That is stronger
     * than compiling the generated files alone: it also proves the generated code and the
     * hand-written code that consumes it agree.</p>
     */
    static String generatedSourceClasspath(Path repoRoot) {
        String separator = System.getProperty("path.separator");
        List<String> entries = new ArrayList<>(List.of(
                repoRoot.resolve("hipster-entity-api/target/classes").toString(),
                repoRoot.resolve("hipster-entity-core/target/classes").toString(),
                repoRoot.resolve("hipster-entity-jackson/target/classes").toString(),
                repoRoot.resolve("hipster-entity-example/target/classes").toString()));

        // The Jackson jars the jackson module itself needs, taken from the local repository. They
        // are not on the tooling module's own test classpath, so they are located by path.
        // `jakarta/validation` is added for the same reason (§ 12.3/7.10): the generated record and
        // builders carry the author's constraint annotations, so proving those artifacts compile means
        // having the annotation types available to javac.
        String m2 = System.getProperty("user.home") + "/.m2/repository";
        for (String tree : List.of("tools/jackson", "jakarta/validation")) {
            try (var walk = Files.walk(Path.of(m2, tree),
                    java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
                walk.filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> !p.toString().contains("-sources"))
                        .filter(p -> !p.toString().contains("-javadoc"))
                        .sorted()
                        .forEach(p -> entries.add(p.toString()));
            } catch (IOException | RuntimeException ignored) {
                // A missing local repository is not fatal: the api/core classes are enough for the
                // generated materializations, and the caller's diagnostics will name what is unresolved.
            }
        }
        return String.join(separator, entries);
    }

    /**
     * Compiles every given source (plus any extra sources it needs) and asserts there were
     * <strong>zero diagnostics</strong>.
     *
     * @param repoRoot  repository root, used to build the classpath
     * @param label     a human-readable label for assertion messages, usually the level under test
     * @param sources   the sources that must compile
     * @param extra     further sources to compile alongside them (for example the hand-written
     *                  example sources a generated file references), may be empty
     * @return the output directory the classes were written to
     */
    static Path compileOrFail(Path repoRoot, String label, List<Path> sources, List<Path> extra) throws IOException {
        Assertions.assertFalse(sources.isEmpty(), label + ": there must be generated sources to compile");
        Assertions.assertTrue(sources.stream().allMatch(Files::exists),
                label + ": every generated source must exist on disk");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assertions.assertNotNull(compiler, label + ": a Java compiler must be available in the test runtime");

        Path outputDir = Files.createTempDirectory("generated-source-classes");
        List<Path> allSources = new ArrayList<>(sources);
        allSources.addAll(extra);

        List<String> diagnostics = new ArrayList<>();
        boolean compiled;
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                    allSources.stream().map(Path::toFile).toList());
            compiled = compiler.getTask(
                    null,
                    fileManager,
                    // Only ERROR/WARNING/MANDATORY_WARNING count as diagnostics. javac emits
                    // `NOTE: ... unchecked or unsafe operations` for any raw/generic boundary and
                    // for every use of a generic varargs factory such as
                    // EntityUpdateTrackingArray.create(...); a note is not a defect in the
                    // generated source, so asserting on it would make the gate unpassable.
                    diagnostic -> {
                        switch (diagnostic.getKind()) {
                            case ERROR, WARNING, MANDATORY_WARNING -> {
                                var position = diagnostic.getSource() == null
                                        ? null
                                        : diagnostic.getSource().getName() + ":"
                                                + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber();
                                diagnostics.add(diagnostic.getKind()
                                        + (position == null ? "" : " " + position)
                                        + ": " + diagnostic.getMessage(null));
                            }
                            default -> { /* NOTE and other informational kinds are ignored */ }
                        }
                    },
                    List.of("-d", outputDir.toString(), "-classpath", generatedSourceClasspath(repoRoot)),
                    null,
                    units).call();
        }

        Assertions.assertTrue(compiled, () -> label + ": generated source must compile, diagnostics = "
                + String.join(" | ", diagnostics));
        Assertions.assertTrue(diagnostics.isEmpty(), () -> label + ": generated source must compile with ZERO diagnostics, got "
                + String.join(" | ", diagnostics));
        return outputDir;
    }

    /** Convenience: every {@code .java} file under {@code root}. */
    static List<Path> javaSourcesUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }
}
