package hr.hrg.jcodebuddy.compile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of {@link EcjIncrementalCompiler}: one changed file compiles, a second
 * compile reuses the same index, a compile error is a {@code false} with a diagnostic,
 * and the source path is interpreted relative to the source root (including its
 * on-disk case).
 *
 * <p>The fixtures are written into a JUnit {@link TempDir} at test time instead of
 * living under {@code src/test/java}, because they are compiler input, not test code:
 * Maven must not compile them. The classpath is deliberately empty — the fixtures use
 * only JDK types, which exercises the name environment's JDK resolution without
 * depending on the test classpath layout surefire happens to use.
 */
class EcjIncrementalCompilerTest {

    @TempDir
    Path tempDir;

    private Path sourceRoot;
    private Path binDir;

    @BeforeEach
    void setUp() throws IOException {
        sourceRoot = Files.createDirectories(tempDir.resolve("src"));
        // Not created here on purpose: ClassFileWriter must create the output tree.
        binDir = tempDir.resolve("bin");
    }

    private Path write(String relativePath, String source) throws IOException {
        Path file = sourceRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private EcjIncrementalCompiler newCompiler() {
        return new EcjIncrementalCompiler(sourceRoot, binDir, "");
    }

    private static void assertCompiles(EcjIncrementalCompiler compiler, Path... files) {
        assertTrue(compiler.compile(Set.of(files)),
                () -> "expected a clean compile, got:\n" + String.join("\n", compiler.lastErrors()));
    }

    private static int classFileMagic(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        return ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
    }

    @Test
    void doesNotTouchAByteIdenticalClassUnlessForced() throws IOException {
        Path source = write("demo/Stable.java", """
                package demo;

                public final class Stable {
                    public static int value() {
                        return 1;
                    }
                }
                """);
        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();
        assertTrue(compiler.compile(Set.of(source), false));

        Path classFile = binDir.resolve("demo").resolve("Stable.class");
        FileTime marker = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(classFile, marker);

        assertTrue(compiler.compile(Set.of(source), false));
        assertEquals(marker, Files.getLastModifiedTime(classFile),
                "an identical class must not be rewritten in incremental mode");

        assertTrue(compiler.compile(Set.of(source), true));
        assertNotEquals(marker, Files.getLastModifiedTime(classFile),
                "a forced rebuild must rewrite the class");
    }

    @Test
    void resolvesATypeFromAClasspathDirectory() throws IOException {
        Path dependencySources = Files.createDirectories(tempDir.resolve("dependency-src"));
        Path dependencyClasses = tempDir.resolve("dependency-classes");
        Path dependencyFile = dependencySources.resolve("dep/External.java");
        Files.createDirectories(dependencyFile.getParent());
        Files.writeString(dependencyFile, """
                package dep;

                public final class External {
                    public static int value() {
                        return 7;
                    }
                }
                """, StandardCharsets.UTF_8);
        EcjIncrementalCompiler dependencyCompiler = new EcjIncrementalCompiler(
                dependencySources, dependencyClasses, "");
        dependencyCompiler.index();
        assertCompiles(dependencyCompiler, dependencyFile);

        Path usesExternal = write("demo/UsesExternal.java", """
                package demo;

                public final class UsesExternal {
                    int value = dep.External.value();
                }
                """);
        EcjIncrementalCompiler compiler = new EcjIncrementalCompiler(
                sourceRoot, binDir, dependencyClasses.toString());
        compiler.index();

        assertCompiles(compiler, usesExternal);
    }

    @Test
    void resolvesATypeFromAJarOnDemand() throws Exception {
        Path ecjJar = Path.of(Class.forName("org.eclipse.jdt.internal.compiler.Compiler")
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path usesEcj = write("demo/UsesEcj.java", """
                package demo;

                import org.eclipse.jdt.internal.compiler.Compiler;

                public final class UsesEcj {
                    Compiler compiler;
                }
                """);

        EcjIncrementalCompiler compiler = new EcjIncrementalCompiler(sourceRoot, binDir, ecjJar.toString());
        compiler.index();

        assertCompiles(compiler, usesEcj);
        assertTrue(Files.isRegularFile(binDir.resolve("demo").resolve("UsesEcj.class")));
        CompilerMemory memory = compiler.memoryStats();
        assertTrue(memory.indexedTypes() > 0, "the JAR index should have types");
        assertTrue(memory.estimatedBytes() >= memory.cachedJarBytes(),
                "the estimate includes the hot cache: " + memory.summary());
    }

    @Test
    void earlyPhaseStartsBeforeCompletionAndWritesTheClassOnCompletion() throws IOException {
        Path hello = write("demo/Early.java", """
                package demo;

                public final class Early {
                    public static int answer() {
                        return 42;
                    }
                }
                """);

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();

        EcjIncrementalCompiler.EarlyCompilation early = compiler.beginEarly(hello);
        assertTrue(early != null, () -> "early phase should start: " + compiler.lastErrors());
        assertFalse(Files.exists(binDir.resolve("demo").resolve("Early.class")),
                "beginEarly parses/binds; it must not write the class file yet");
        assertTrue(compiler.completeEarly(early), "completing the early phase should succeed");
        assertTrue(Files.isRegularFile(binDir.resolve("demo").resolve("Early.class")),
                "the class file is written by the completion phase");
    }

    @Test
    void compilesOneFileForTheStartBatchFastPath() throws IOException {
        Path hello = write("demo/One.java", """
                package demo;

                public final class One {
                    public static int answer() {
                        return 42;
                    }
                }
                """);

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();

        assertTrue(compiler.compileOne(hello), "one-file compile should succeed: " + compiler.lastErrors());
        assertTrue(Files.isRegularFile(binDir.resolve("demo").resolve("One.class")),
                "compileOne writes the class file immediately");
        assertEquals(List.of(), compiler.lastErrors());
    }

    @Test
    void compilesOneChangedSourceAndWritesItsClassFile() throws IOException {
        Path hello = write("demo/Hello.java", """
                package demo;

                import java.util.List;

                public final class Hello {
                    public static String greeting() {
                        return List.of("hi").get(0);
                    }
                }
                """);

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();

        assertCompiles(compiler, hello);

        Path classFile = binDir.resolve("demo").resolve("Hello.class");
        assertTrue(Files.isRegularFile(classFile), "the class file must be written to bin/");
        assertEquals(0xCAFEBABE, classFileMagic(classFile), "the output must be a class file");
        assertEquals(List.of(), compiler.lastErrors(), "a clean compile has no diagnostics");
        assertTrue(compiler.lastCompileNanos() > 0, "the compile duration must be measured");
    }

    @Test
    void aSecondCompileReusesTheIndexAndResolvesClassesFromEarlierCompiles() throws IOException {
        Path first = write("demo/First.java", """
                package demo;

                public final class First {
                    public static int value() {
                        return 41;
                    }
                }
                """);
        Path second = write("demo/Second.java", """
                package demo;

                public final class Second {
                    public static int value() {
                        return First.value() + 1;
                    }
                }
                """);

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index(); // once, before both compiles

        assertCompiles(compiler, first);
        assertTrue(Files.isRegularFile(binDir.resolve("demo").resolve("First.class")));

        // No index() call between the two: the second compile resolves `First` from the
        // bin directory through the same name environment.
        assertCompiles(compiler, second);
        assertTrue(Files.isRegularFile(binDir.resolve("demo").resolve("Second.class")),
                "the dependent file must compile against the class from the previous compile");
    }

    @Test
    void aCompileErrorReturnsFalseWithADiagnosticAndNoException() throws IOException {
        Path broken = write("demo/Broken.java", """
                package demo;

                public final class Broken {
                    void fail() {
                        return 1;
                    }
                }
                """);

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();

        assertFalse(compiler.compile(Set.of(broken)), "a compile error must not report success");

        String diagnostics = String.join("\n", compiler.lastErrors());
        assertFalse(diagnostics.isBlank(), "a failure must carry a diagnostic");
        assertTrue(diagnostics.contains("Broken.java"), diagnostics);
        assertFalse(Files.exists(binDir.resolve("demo").resolve("Broken.class")),
                "a failed unit must not leave a class file behind");
    }

    @Test
    void aFailedCompileDoesNotPoisonTheNextOne() throws IOException {
        Path source = write("demo/Recover.java", """
                package demo;

                public final class Recover {
                    void fail() {
                        return 1;
                    }
                }
                """);

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();
        assertFalse(compiler.compile(Set.of(source)));

        Files.writeString(source, """
                package demo;

                public final class Recover {
                    public static int value() {
                        return 7;
                    }
                }
                """, StandardCharsets.UTF_8);

        assertCompiles(compiler, source);
        assertTrue(Files.isRegularFile(binDir.resolve("demo").resolve("Recover.class")));
        assertEquals(List.of(), compiler.lastErrors(), "the previous round's diagnostic must be cleared");
    }

    @Test
    void nestedPackagesLandInTheirPackageDirectory() throws IOException {
        Path deep = write("a/b/c/Deep.java", "package a.b.c; public final class Deep {}");

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();

        assertCompiles(compiler, deep);
        assertTrue(Files.isRegularFile(binDir.resolve("a").resolve("b").resolve("c").resolve("Deep.class")),
                "the package path must become the output directory path");
    }

    @Test
    void aDifferentlyCasedSourceDirectoryStillDerivesTheRealPackage() throws IOException {
        Path hello = write("demo/Hello.java", "package demo; public final class Hello {}");
        Path differentlyCased = sourceRoot.resolve("DEMO").resolve("Hello.java");
        Assumptions.assumeTrue(Files.exists(differentlyCased),
                "needs a case-insensitive filesystem (Windows) to resolve DEMO/ as demo/");

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();

        assertCompiles(compiler, differentlyCased);
        assertTrue(Files.isRegularFile(binDir.resolve("demo").resolve("Hello.class")),
                "the on-disk spelling of the package must win over the caller's spelling");
    }

    @Test
    void aSourceOutsideTheRootIsReportedNotThrown() throws IOException {
        Path outside = tempDir.resolve("elsewhere").resolve("Stray.java");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "package stray; public final class Stray {}", StandardCharsets.UTF_8);

        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();

        assertFalse(compiler.compile(Set.of(outside)));
        String diagnostics = String.join("\n", compiler.lastErrors());
        assertTrue(diagnostics.contains("not under the source root"), diagnostics);
    }

    @Test
    void anUnreadableSourceIsReportedNotThrown() {
        EcjIncrementalCompiler compiler = newCompiler();
        compiler.index();

        assertFalse(compiler.compile(Set.of(sourceRoot.resolve("demo").resolve("Missing.java"))));
        assertFalse(compiler.lastErrors().isEmpty(), "a missing file must be reported");
    }

    @Test
    void compilesMutuallyDependentSourceRootsInOneBatch() throws IOException {
        Path mainRoot = Files.createDirectories(tempDir.resolve("main"));
        Path generatedRoot = Files.createDirectories(tempDir.resolve("generated"));
        Path main = mainRoot.resolve("p/MainType.java");
        Path generated = generatedRoot.resolve("p/GeneratedType.java");
        Files.createDirectories(main.getParent());
        Files.createDirectories(generated.getParent());
        Files.writeString(main, "package p; public class MainType { GeneratedType value; }\n");
        Files.writeString(generated, "package p; public class GeneratedType { MainType value; }\n");

        EcjIncrementalCompiler compiler = new EcjIncrementalCompiler(
                List.of(mainRoot, generatedRoot), binDir, "");
        assertCompiles(compiler, main, generated);
        assertTrue(Files.isRegularFile(binDir.resolve("p/MainType.class")));
        assertTrue(Files.isRegularFile(binDir.resolve("p/GeneratedType.class")));
    }
}
