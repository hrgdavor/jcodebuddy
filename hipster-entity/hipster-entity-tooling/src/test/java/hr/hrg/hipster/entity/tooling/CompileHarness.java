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

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * <p>The root is the nearest ancestor that is a checkout of the whole reactor: it holds both
     * {@code pom.xml} and {@code .git}. The earlier rule — "the nearest pom.xml whose directory starts with
     * {@code hipster-entity-}" — quietly assumed modules sit directly under the root, and the day the
     * modules moved into group folders ({@code hipster-entity/hipster-entity-tooling}) it returned the
     * *module* directory as the root, so every generated-source compile looked for its classpath in a
     * directory that cannot exist. A marker that cannot move with the modules is the fix.</p>
     */
    static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate the repository root from " + Path.of("").toAbsolutePath());
    }

    /**
     * The directory of a module, found through the root POM rather than assumed.
     *
     * <p>A module's location is recorded in exactly one place — the root {@code pom.xml}'s
     * {@code <module>} entries — and the modules now live in group folders
     * ({@code hipster-entity/hipster-entity-api}, {@code jcodebuddy/jcodebuddy-core}, {@code watch/…}), which
     * is not the last move they will make. A test that spells out {@code root.resolve("hipster-entity/hipster-entity-api")}
     * breaks on every such move; one that asks this method does not. A name that is not a module fails
     * loudly with the list that is, because "silently resolved to a directory that does not exist" is how
     * the move produced thirty failing test classes instead of four.</p>
     */
    static Path moduleDir(String artifactId) {
        Path root = findRepoRoot();
        List<String> modulePaths = new ArrayList<>();
        try {
            String pom = Files.readString(root.resolve("pom.xml"));
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("<module>([^<]+)</module>").matcher(pom);
            while (matcher.find()) {
                modulePaths.add(matcher.group(1).trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the root POM at " + root, e);
        }
        for (String path : modulePaths) {
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            if (lastSegment.equals(artifactId)) {
                return root.resolve(path);
            }
        }
        throw new IllegalStateException("'" + artifactId + "' is not a module in the root POM; it lists "
                + modulePaths);
    }

    /** The module directory when the name may not be a module (a documentation directory, an old name). */
    static Path moduleDirOrNull(String artifactId) {
        try {
            Path dir = moduleDir(artifactId);
            return Files.isDirectory(dir) ? dir : null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * The classpath the generated sources are compiled against.
     *
     * <p>{@code api} and {@code core} cover every generated materialization. The {@code jackson}
     * module and its third-party jars are added so the whole example tree — including the runnable
     * {@code PersonDemo}, which prints a change set as JSON — compiles as one unit. That is stronger
     * than compiling the generated files alone: it also proves the generated code and the
     * hand-written code that consumes it agree.</p>
     *
     * <p><strong>Each module contributes both its {@code target/classes} and its {@code target/*.jar}.</strong>
     * The class directory is what a build that just compiled the module leaves; the jar is what the Maven build
     * cache leaves when it restores a module instead of building it — measured: a restore-only run reports
     * {@code Skipping plugin execution (cached): compiler:compile} **and** {@code jar:jar}, and afterwards
     * {@code target/classes} is absent while the jar is there (23/28/12/126 class files before such a run, 0
     * after). Asking for both costs nothing — javac ignores a path that does not exist — and it is the difference
     * between a cached build and an uncached one producing the same test result, which is the property the cache
     * has to have to be worth having.</p>
     */
    static String generatedSourceClasspath(Path repoRoot) {
        String separator = System.getProperty("path.separator");
        List<String> entries = new ArrayList<>();
        for (String module : List.of("hipster-entity-api", "hipster-entity-core", "hipster-entity-jackson",
                "hipster-entity-example")) {
            Path target = repoRoot.resolve("hipster-entity/" + module + "/target");
            entries.add(target.resolve("classes").toString());
            try (var jars = Files.list(target)) {
                jars.filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> !p.toString().contains("-sources"))
                        .filter(p -> !p.toString().contains("-javadoc"))
                        .sorted()
                        .forEach(p -> entries.add(p.toString()));
            } catch (IOException | RuntimeException ignored) {
                // No target directory yet: the class-directory entry above is still on the classpath, and a
                // caller's diagnostics will name anything unresolved.
            }
        }

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
