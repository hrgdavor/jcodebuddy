// {@link com.codebuddy.merge.ProjectClasspathResolutionTest} Pins how a project type is put on the classpath.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.JavaType;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a classpath entry has to be for the project's own types to resolve.
 *
 * <p>{@code MergeFileTool --classpath} and {@code MergeWorkflow} both promise that a
 * conflict about the project's own types can be decided instead of escalated, and that
 * promise rests on a fact about the parser that is easy to get wrong: which forms of
 * classpath entry actually attribute types. This class pins it, because the failure is
 * silent — an entry the parser does not read produces {@code Unknown}, which the resolver
 * reports as "could not be resolved" and which looks exactly like a missing classpath.
 *
 * <p>Each case is asserted against the <em>rendered</em> resolved type, so a pass means the
 * type came back fully qualified rather than merely present.
 */
class ProjectClasspathResolutionTest {

    private static final String DECLARATION = "class A { com.example.Gadget value = null; }";

    /** Compiles {@code com.example.Widget} and {@code Gadget extends Widget} into a directory. */
    private Path compileFixture(Path dir) throws IOException {
        Path sources = Files.createDirectories(dir.resolve("src/com/example"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(sources.resolve("Widget.java"),
            "package com.example;\n\npublic class Widget {\n}\n");
        Files.writeString(sources.resolve("Gadget.java"),
            "package com.example;\n\npublic class Gadget extends Widget {\n}\n");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test runs on a JDK");
        int status = compiler.run(null, null, null,
            "-d", classes.toString(),
            sources.resolve("Widget.java").toString(),
            sources.resolve("Gadget.java").toString());
        assertEquals(0, status, "the fixture types must compile");
        return classes;
    }

    /** Packs a compiled classes directory into a jar. */
    private static Path jarOf(Path classes, Path jarFile) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarFile);
             JarOutputStream jar = new JarOutputStream(out);
             var entries = Files.walk(classes)) {
            for (Path file : entries.filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                jar.putNextEntry(new JarEntry(name));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
        return jarFile;
    }

    private static Optional<JavaType> resolveWith(TypeContext context) {
        return ResolvedTypeReader.declaredType(DECLARATION, "value", "A.java", context);
    }

    private static String rendered(Optional<JavaType> type) {
        return type.map(ResolvedTypeReader::renderType).orElse("<no answer>");
    }

    /**
     * A <em>jar</em> of the project's classes: the form every build tool produces, and the
     * one a caller is most likely to hand over.
     */
    @Test
    @DisplayName("resolves a project type from a jar on the classpath")
    void resolvesFromAJar(@TempDir Path dir) throws IOException {
        Path classes = compileFixture(dir);
        Path jar = jarOf(classes, dir.resolve("project.jar"));

        TypeContext context = TypeContext.withRuntimeClasspathAnd(dir.resolve("src"),
            List.of(jar));
        Optional<JavaType> type = resolveWith(context);

        assertTrue(type.isPresent(), "the jar must be read: " + rendered(type));
        assertEquals("com.example.Gadget", rendered(type),
            "and the type resolves fully qualified, not as Unknown");
    }

    /**
     * A <em>directory</em> of classes. Measured here rather than assumed: this is the form
     * a caller reaches for during development ({@code target/classes}), so whether the
     * parser reads it decides what {@code --classpath} has to accept.
     */
    @Test
    @DisplayName("resolves a project type from a classes directory on the classpath")
    void resolvesFromAClassesDirectory(@TempDir Path dir) throws IOException {
        Path classes = compileFixture(dir);

        TypeContext context = TypeContext.withRuntimeClasspathAnd(dir.resolve("src"),
            List.of(classes));
        Optional<JavaType> type = resolveWith(context);

        assertTrue(type.isPresent(), "the directory must be read: " + rendered(type));
        assertEquals("com.example.Gadget", rendered(type));
    }

    /**
     * The platform, with an explicit entry added: asking for the project's types must not
     * cost the JDK types, which is why the context is additive.
     */
    @Test
    @DisplayName("keeps resolving JDK types when project entries are added")
    void keepsThePlatform(@TempDir Path dir) throws IOException {
        Path classes = compileFixture(dir);
        TypeContext context = TypeContext.withRuntimeClasspathAnd(dir.resolve("src"),
            List.of(classes));

        Optional<JavaType> type = ResolvedTypeReader.declaredType(
            "import java.util.*;\nclass A { Collection<String> value = null; }", "value",
            "A.java", context);

        assertTrue(type.isPresent(), "the platform is still there: " + rendered(type));
        assertTrue(rendered(type).startsWith("java.util.Collection"), rendered(type));
    }

    /**
     * A type that is genuinely absent stays absent, and stays {@code Unknown} — the state
     * the resolver escalates on. This is the control for the three cases above: without it
     * a passing test could mean "everything resolves", which would make them meaningless.
     */
    @Test
    @DisplayName("still reports Unknown for a type no entry carries")
    void absentTypeIsStillUnknown(@TempDir Path dir) throws IOException {
        Path classes = compileFixture(dir);
        TypeContext context = TypeContext.withRuntimeClasspathAnd(dir.resolve("src"),
            List.of(classes));

        Optional<JavaType> type = ResolvedTypeReader.declaredType(
            "class A { com.example.Absent value = null; }", "value", "A.java", context);

        assertTrue(type.isPresent(), "present, because that is how an unattributed type arrives");
        assertTrue(type.get() instanceof JavaType.Unknown, rendered(type));
    }

    /** Every entry the JVM reports, so a jar sent over a socket is the only thing missing. */
    @Test
    @DisplayName("the runtime classpath itself resolves JDK types")
    void runtimeClasspathResolvesThePlatform(@TempDir Path dir) {
        TypeContext context = TypeContext.withRuntimeClasspath(dir);

        Optional<JavaType> type = ResolvedTypeReader.declaredType(
            "import java.util.*;\nclass A { Map<String, String> value = null; }", "value",
            "A.java", context);

        assertTrue(type.isPresent(), rendered(type));
        assertTrue(rendered(type).startsWith("java.util.Map"), rendered(type));
    }
}