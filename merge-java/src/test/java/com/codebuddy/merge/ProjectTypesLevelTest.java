// {@link com.codebuddy.merge.ProjectTypesLevelTest} § 10.2 row 6 measured on a resolution, not on the parser (plan 4.13).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code JETBRAINS_PORT.md} § 10.2's <b>row 6</b>, measured where it makes a claim rather than where it is easy:
 *
 * <blockquote>Type resolution against a project classpath ({@code TypeContext}, javac) — a conflict whose
 * resolution depends on the <b>project's own</b> hierarchies. Upstream has no type model; it can only compare text.
 * <b>Measured by:</b> the resolved code compiles against the project classpath and the level is
 * {@code PROJECT_TYPES}.</blockquote>
 *
 * <h2>Why this test exists, and the measurement it corrects</h2>
 *
 * <p>{@link ProjectClasspathResolutionTest} already proves the <em>parser</em> reaches a project type from a classes
 * directory and from a jar. That is half the row. The other half is a <b>resolution recording
 * {@code PROJECT_TYPES}</b>, and until this test nothing asserted it — so the row-by-row measurement in
 * {@link PortMetricTest} reported row 6 as <em>unmet</em>, correctly for what it measured (a JDK-only corpus) and
 * wrongly as a statement about the module. The correction is the interesting part: a measurement's scope is part of
 * its claim, and a corpus that cannot contain the case is evidence of nothing.
 *
 * <h2>The control that makes it a measurement rather than a formality</h2>
 *
 * <p>The same conflict is resolved twice: once with a context carrying <b>no</b> project entries, once with one that
 * carries the compiled project type. The levels must differ — {@code PLATFORM_TYPES} and {@code PROJECT_TYPES} — and
 * that difference is the row: not "the tool can resolve types", but "the tool <em>says</em> whether it resolved the
 * project's own types".
 */
class ProjectTypesLevelTest {

    @TempDir
    Path tempDir;

    /** Two branches adding <b>distinct</b> members whose parameter lists mention a PROJECT type. */
    private static Conflict memberAddWithProjectType() {
        String base = """
            package com.example;

            public class Service {
            }
            """;
        String ours = """
            package com.example;

            public class Service {
                public void accept(Gadget gadget) {
                }
            }
            """;
        String theirs = """
            package com.example;

            public class Service {
                public void accept(Gadget gadget, int retries) {
                }
            }
            """;
        return new Conflict(ConflictType.MEMBER_ADD, "Service.java", "both branches added a distinct member",
            base, ours, theirs);
    }

    /** Compiles {@code com.example.Gadget}, returning the classes directory that carries it. */
    private Path compileGadget(Path dir) throws IOException {
        Path sources = Files.createDirectories(dir.resolve("src/com/example"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(sources.resolve("Gadget.java"),
            "package com.example;\n\npublic class Gadget {\n}\n", StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test runs on a JDK");
        int status = compiler.run(null, null, null,
            "-d", classes.toString(), sources.resolve("Gadget.java").toString());
        assertEquals(0, status, "the fixture type must compile");
        return classes;
    }

    @Test
    @DisplayName("a resolution that needed the project's own types records PROJECT_TYPES, and says so")
    void theLevelIsRecordedOnTheResolution() throws IOException {
        Path classes = compileGadget(tempDir.resolve("project"));
        TypeContext withProject = TypeContext.withRuntimeClasspathAnd(
            TestTypeContexts.SOURCE_ROOT, List.of(classes));
        TypeContext withoutProject = TypeContext.withRuntimeClasspath(TestTypeContexts.SOURCE_ROOT);

        assertTrue(withProject.hasProjectEntries(), "the context must carry the compiled project entry");
        assertTrue(!withoutProject.hasProjectEntries(), "and the control must not");

        Conflict conflict = memberAddWithProjectType();
        ConflictResolution fromProject = new MemberAddConflictResolver()
            .resolve(conflict.withTypeContext(withProject));
        ConflictResolution fromPlatform = new MemberAddConflictResolver()
            .resolve(conflict.withTypeContext(withoutProject));

        assertNotNull(fromProject, "the resolver must answer at all");
        assertNotNull(fromPlatform, "the control must answer too, or the comparison proves nothing");

        System.out.println("PROJECT-TYPES-METRIC (row 6): with project entries -> " + fromProject.getKind()
            + " at " + fromProject.getAnalysisLevel()
            + "; without -> " + fromPlatform.getKind() + " at " + fromPlatform.getAnalysisLevel());

        assertEquals(AnalysisLevel.PROJECT_TYPES, fromProject.getAnalysisLevel(),
            "the answer needed the project's own type, and the level is how a reviewer learns that");
        assertEquals(AnalysisLevel.PLATFORM_TYPES, fromPlatform.getAnalysisLevel(),
            "the same conflict without the project's entries is answered from the platform alone — a weaker reading"
                + " of the same question, and the difference is what the level is for");
        assertTrue(fromPlatform.getAnalysisLevel().isStrongerThan(AnalysisLevel.STRUCTURE),
            "both are still above the text levels: the claim is about WHICH type universe answered, not about"
                + " whether structure was read");
    }
}
