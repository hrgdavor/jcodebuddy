package hr.hrg.jcodebuddy.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The facade: the one place a caller reaches the engine, and the defect its shape prevents.
 *
 * <p>{@link #registeringOnTheFacadeIsVisibleToTheEngine()} is the test the plan's sketch needed and did not
 * have. The sketch kept a registry on the facade and a second one on the engine, so
 * {@code registerTransformation(...)} — the only registration entry point a caller was offered — wrote to
 * the one the engine never read, and no transformation registered that way could ever run. Nothing about
 * the sketch looked wrong; the bug was only visible from the other entry point, which is what this test
 * exercises.</p>
 */
class ProjectAutomationTest {

    @TempDir
    Path root;

    @Test
    void registeringOnTheFacadeIsVisibleToTheEngine() throws IOException {
        ProjectAutomation automation = new ProjectAutomation();
        automation.registerTransformation(TestTransformation.replacing("replace", "foo", "bar"));
        Path file = write("Sample.java", "foo\n");

        // Registered through the facade ...
        assertEquals(List.of("replace"), automation.getAllTransformations());
        // ... and runnable through the engine it drives: one registry, not two.
        assertTrue(automation.getEngine().getRegistry().has("replace"));
        TransformationResult throughTheEngine = automation.getEngine().apply(file, "replace");
        assertTrue(throughTheEngine.succeeded());
        assertEquals("bar\n", throughTheEngine.output());
    }

    @Test
    void aFacadeOverAnExistingEngineSharesItsRegistry() {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.identity("noop"));
        ProjectAutomation automation = new ProjectAutomation(engine);

        assertSame(engine, automation.getEngine());
        assertSame(engine.getRegistry(), automation.getRegistry());
        assertEquals(List.of("noop"), automation.getAllTransformations());
        assertSame(engine.getRegistry().get("noop"), automation.getTransformation("noop"));
    }

    @Test
    void runsOneTransformationOverOneFileWithoutWriting() throws IOException {
        ProjectAutomation automation = new ProjectAutomation();
        automation.registerTransformation(TestTransformation.replacing("replace", "foo", "bar"));
        Path file = write("Sample.java", "foo\n");

        TransformationResult result = automation.executeOnFile(file, "replace");

        assertTrue(result.changed());
        assertEquals("foo\n", Files.readString(file, StandardCharsets.UTF_8));

        assertTrue(automation.executeOnFileInPlace(file, "replace").changed());
        assertEquals("bar\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void runsASequenceOverOneFileAndLeavesTheDirectoryAlone() throws IOException {
        ProjectAutomation automation = new ProjectAutomation();
        automation.registerTransformation(TestTransformation.replacing("first", "a", "b"));
        automation.registerTransformation(TestTransformation.replacing("second", "b", "c"));
        Path file = write("Sample.java", "a\n");
        Path neighbour = write("Neighbour.java", "a\n");

        Map<String, TransformationResult> results =
                automation.executeOnFile(file, List.of("first", "second"));

        assertEquals("c\n", results.get("second").output());
        assertEquals("a\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("a\n", Files.readString(neighbour, StandardCharsets.UTF_8),
                "running on a file must not reach the files beside it");
    }

    @Test
    void runsOneTransformationOverATreeAndCanWriteIt() throws IOException {
        ProjectAutomation automation = new ProjectAutomation();
        automation.registerTransformation(TestTransformation.replacing("replace", "foo", "bar"));
        write("a/First.java", "foo\n");
        write("b/Second.java", "foo\n");

        BatchProcessor.BatchReport dry = automation.executeOnTree(root, "replace");
        assertEquals(2, dry.total());
        assertEquals(2, dry.changed());
        assertEquals(0, dry.failed());

        assertTrue(automation.executeOnTreeInPlace(root, "replace").ok());
        assertEquals("bar\n", Files.readString(root.resolve("a/First.java"), StandardCharsets.UTF_8));
    }

    @Test
    void validatesAndAnalysesThroughTheEngine() throws IOException {
        ProjectAutomation automation = new ProjectAutomation();
        Path file = write("Person.java", """
                package a.b;

                public class Person {
                }
                """);

        ValidationResult validation = automation.validate(file);
        AnalysisResult analysis = automation.analyze(file);

        assertTrue(validation.isValid(), () -> validation.render());
        assertEquals(1, analysis.count(AnalysisResult.TYPE_COUNT));
    }

    @Test
    void refusesANullEngine() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectAutomation(null));
    }

    @Test
    void runsThroughTheBatchProcessorAndShutsItDown() throws IOException {
        ProjectAutomation automation = new ProjectAutomation();
        automation.registerTransformation(TestTransformation.identity("noop"));
        write("Sample.java", "class Sample {}\n");

        assertTrue(automation.getBatchProcessor().getThreads() >= 1);
        assertSame(automation.getBatchProcessor(), automation.getBatchProcessor());
        assertTrue(automation.executeOnTree(root, "noop").ok());
        // Shutting down is how a command-line run exits rather than hanging on non-daemon workers.
        automation.shutdown();
    }

    private Path write(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
