package hr.hrg.jcodebuddy.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch processor: the tally, the order a parallel run reports in, and the write decision.
 *
 * <p>A parallel run is only acceptable if its report is the one a sequential run would have produced, so
 * that is asserted directly rather than assumed from the implementation. The processor collects its
 * futures in the order the files were found; a report that reordered between runs could not be diffed, and
 * this repository has already paid once for an unstable iteration order.</p>
 */
class BatchProcessorTest {

    @TempDir
    Path root;

    @Test
    void aParallelRunReportsTheSameOrderAsASequentialOne() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.replacing("replace", "foo", "bar"));
        write("a/First.java", "foo\n");
        write("b/Second.java", "foo\n");
        write("c/Third.java", "foo\n");
        BatchProcessor processor = new BatchProcessor(engine, 4);

        try {
            BatchProcessor.BatchReport parallel = processor.process(root, "replace");
            BatchProcessor.BatchReport sequential = processor.processSequential(root, "replace");

            assertEquals(files(sequential), files(parallel));
            assertEquals(3, parallel.total());
            assertEquals(3, parallel.changed());
            assertEquals(3, parallel.succeeded());
            assertEquals(0, parallel.failed());
            assertTrue(parallel.ok());
        } finally {
            processor.shutdown();
        }
    }

    @Test
    void processIsADryRunAndProcessInPlaceWrites() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.replacing("replace", "foo", "bar"));
        Path file = write("Sample.java", "foo\n");
        BatchProcessor processor = new BatchProcessor(engine, 1);

        try {
            processor.process(root, "replace");
            assertEquals("foo\n", read(file), "process must not write");

            BatchProcessor.BatchReport written = processor.processInPlace(root, "replace");
            assertEquals("bar\n", read(file));
            assertEquals(1, written.changed());
        } finally {
            processor.shutdown();
        }
    }

    @Test
    void anUnchangedFileIsNotCountedAsChanged() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.identity("noop"));
        write("Sample.java", "class Sample {}\n");
        BatchProcessor processor = new BatchProcessor(engine, 1);

        try {
            BatchProcessor.BatchReport report = processor.process(root, "noop");

            assertEquals(1, report.total());
            assertEquals(1, report.succeeded());
            assertEquals(0, report.changed());
            // "succeeded but changed nothing" is the normal result of a second idempotent pass, so the
            // summary line has to distinguish it from a failure.
            assertTrue(report.render().contains("1 file(s), 0 changed, 1 unchanged, 0 failed"));
        } finally {
            processor.shutdown();
        }
    }

    @Test
    void aFailureIsCountedAndRendered() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.failing("refuse", "not this one"));
        write("Sample.java", "class Sample {}\n");
        BatchProcessor processor = new BatchProcessor(engine, 1);

        try {
            BatchProcessor.BatchReport report = processor.process(root, "refuse");

            assertEquals(1, report.failed());
            assertFalse(report.ok());
            assertTrue(report.render().contains("failed"));
            assertTrue(report.render().contains("not this one"));
        } finally {
            processor.shutdown();
        }
    }

    @Test
    void processMatchingUsesARealGlob() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.identity("noop"));
        write("RootController.java", "class A {}\n");
        write("a/NestedController.java", "class B {}\n");
        write("a/PersonService.java", "class C {}\n");
        BatchProcessor processor = new BatchProcessor(engine, 1);

        try {
            // `*Controller.java` does not cross a directory boundary in a glob, so it is the root only —
            // the sketch's `path.toString().matches(glob)` against the absolute path matched neither.
            assertEquals(List.of("RootController.java"),
                    names(processor.processMatching(root, "*Controller.java", "noop")));
            // `**/` means "anywhere", which includes the root: Java's own glob would have missed
            // RootController.java here (SourceFiles explains the deviation). RootController.java is first
            // because files are sorted by path, and 'R' sorts before 'a'.
            assertEquals(List.of("RootController.java", "NestedController.java"),
                    names(processor.processMatching(root, "**/*Controller.java", "noop")));
            assertEquals(List.of("NestedController.java", "PersonService.java"),
                    names(processor.processMatching(root, "a/*.java", "noop")));
        } finally {
            processor.shutdown();
        }
    }

    @Test
    void anUnregisteredNameIsThrownBeforeAnyWorkerStarts() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.identity("noop"));
        write("Sample.java", "class Sample {}\n");
        BatchProcessor processor = new BatchProcessor(engine, 2);

        try {
            // Not a CompletionException from join(): the name is checked on the caller's thread.
            assertThrows(NoSuchElementException.class, () -> processor.process(root, "typo"));
            assertThrows(NoSuchElementException.class, () -> processor.processMatching(root, "*.java", "typo"));
            assertThrows(IllegalArgumentException.class, () -> processor.process(root, ""));
        } finally {
            processor.shutdown();
        }
    }

    @Test
    void refusesAnImpossibleThreadCount() {
        AutomationEngine engine = new AutomationEngine();

        assertThrows(IllegalArgumentException.class, () -> new BatchProcessor(engine, 0));
        assertThrows(IllegalArgumentException.class, () -> new BatchProcessor(engine, -1));
        assertThrows(IllegalArgumentException.class, () -> new BatchProcessor(null));
    }

    private static List<String> files(BatchProcessor.BatchReport report) {
        return report.results().stream().map(result -> result.inputFile().toString()).toList();
    }

    private static List<String> names(BatchProcessor.BatchReport report) {
        return report.results().stream().map(result -> result.inputFile().getFileName().toString()).toList();
    }

    private Path write(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
