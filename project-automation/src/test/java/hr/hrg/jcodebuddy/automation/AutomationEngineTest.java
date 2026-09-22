package hr.hrg.jcodebuddy.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's contract: what it reads, when it writes, and what a failure looks like.
 *
 * <p>The read/write boundary is the reason most of these tests exist. The plan's sketch produced output
 * text and left it to the caller to guess whether anything had been written; a batch tool over a source
 * tree cannot work that way, because "show me what would change" and "change it" are different requests
 * and only one of them is safe to run by accident. Every write test therefore also asserts that the dry
 * run did <em>not</em> write.</p>
 */
class AutomationEngineTest {

    @TempDir
    Path root;

    @Test
    void applyComputesWithoutTouchingTheFile() throws IOException {
        AutomationEngine engine = engine(TestTransformation.replacing("replace", "foo", "bar"));
        Path file = write("Sample.java", "foo\n");

        TransformationResult result = engine.apply(file, "replace");

        assertTrue(result.succeeded());
        assertTrue(result.changed());
        assertEquals("bar\n", result.output());
        assertEquals("foo\n", read(file), "apply must not write the file it read");
    }

    @Test
    void applyInPlaceWritesAChangedFile() throws IOException {
        AutomationEngine engine = engine(TestTransformation.replacing("replace", "foo", "bar"));
        Path file = write("Sample.java", "foo\n");

        TransformationResult result = engine.applyInPlace(file, "replace");

        assertTrue(result.changed());
        assertEquals("bar\n", read(file));
    }

    @Test
    void aSecondPassOverItsOwnOutputIsUnchanged() throws IOException {
        AutomationEngine engine = engine(TestTransformation.replacing("replace", "foo", "bar"));
        Path file = write("Sample.java", "foo\n");

        engine.applyInPlace(file, "replace");
        TransformationResult second = engine.applyInPlace(file, "replace");

        // "ran and changed nothing" is not a failure and must not read like one: every generator in this
        // repository is idempotent on purpose.
        assertTrue(second.succeeded());
        assertFalse(second.changed());
        assertEquals("bar\n", read(file));
    }

    @Test
    void applyAllIsADryRunAndTheWriteHasToBeAskedFor() throws IOException {
        AutomationEngine engine = engine(TestTransformation.replacing("replace", "foo", "bar"));
        Path first = write("a/Sample.java", "foo\n");
        Path second = write("b/Other.java", "foo\n");

        List<TransformationResult> dry = engine.applyAll(root, "replace");
        assertEquals(2, dry.size());
        assertEquals("foo\n", read(first));
        assertEquals("foo\n", read(second));

        List<TransformationResult> written = engine.applyAll(root, "replace", true);
        assertEquals(2, written.size());
        assertTrue(written.stream().allMatch(TransformationResult::changed));
        assertEquals("bar\n", read(first));
        assertEquals("bar\n", read(second));
    }

    @Test
    void applyAllFollowsTheSortedFileOrder() throws IOException {
        AutomationEngine engine = engine(TestTransformation.identity("noop"));
        write("z/Last.java", "class Z {}\n");
        write("a/First.java", "class A {}\n");

        List<TransformationResult> results = engine.applyAll(root, "noop");

        // A report whose lines reorder between runs cannot be diffed, so the order is part of the contract.
        assertEquals(List.of("First.java", "Last.java"),
                results.stream().map(result -> result.inputFile().getFileName().toString()).toList());
    }

    @Test
    void aChainedRunFeedsEachStepThePreviousStepsOutput() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.replacing("first", "a", "b"));
        engine.register(TestTransformation.replacing("second", "b", "c"));
        Path file = write("Sample.java", "a\n");

        Map<String, List<TransformationResult>> results =
                engine.applyAllSequential(root, List.of("first", "second"), true);

        assertEquals(List.of("first", "second"), List.copyOf(results.keySet()));
        assertEquals("b\n", results.get("first").get(0).output());
        assertEquals("c\n", results.get("second").get(0).output());
        assertEquals("c\n", read(file));
    }

    @Test
    void aChainedDryRunStopsAtTheLastTextInMemory() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.replacing("first", "a", "b"));
        engine.register(TestTransformation.replacing("second", "b", "c"));
        Path file = write("Sample.java", "a\n");

        Map<String, List<TransformationResult>> results =
                engine.applyAllSequential(root, List.of("first", "second"));

        assertEquals("c\n", results.get("second").get(0).output());
        assertEquals("a\n", read(file), "the chained dry run must leave the file alone");
    }

    @Test
    void applySequentialChainsOverOneFileWithoutWritingIt() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.replacing("first", "a", "b"));
        engine.register(TestTransformation.replacing("second", "b", "c"));
        Path file = write("Sample.java", "a\n");

        Map<String, TransformationResult> results = engine.applySequential(file, List.of("first", "second"));

        assertEquals(List.of("first", "second"), List.copyOf(results.keySet()));
        assertEquals("b\n", results.get("first").output());
        assertEquals("c\n", results.get("second").output());
        assertEquals("a\n", read(file));
    }

    @Test
    void applySequentialWithNoNamesReadsNothing() {
        AutomationEngine engine = engine(TestTransformation.identity("noop"));
        Path missing = root.resolve("NotThere.java");

        // No names means no work: the early return must come before the read, so a path that does not
        // exist is not an error here.
        assertEquals(Map.of(), engine.applySequential(missing, List.of()));
    }

    @Test
    void aTransformationThatRefusesIsReportedAsThisFilesFailure() throws IOException {
        AutomationEngine engine = engine(TestTransformation.failing("refuse", "the tree is not readable"));
        Path file = write("Sample.java", "foo\n");

        TransformationResult result = engine.apply(file, "refuse");

        assertFalse(result.succeeded());
        assertFalse(result.changed());
        assertTrue(result.error().contains("the tree is not readable"));
        // The output is the untouched input, so a caller that writes every result blindly still writes
        // nothing new.
        assertEquals("foo\n", result.output());
        assertEquals("foo\n", read(file));
    }

    @Test
    void aNullReturnIsAFailureRatherThanANullOutput() throws IOException {
        AutomationEngine engine = engine(TestTransformation.returningNull("nulls"));
        Path file = write("Sample.java", "foo\n");

        TransformationResult result = engine.apply(file, "nulls");

        assertFalse(result.succeeded());
        assertNotNull(result.output());
        assertTrue(result.error().contains("returned null"));
    }

    @Test
    void anUncheckedThrowBecomesThisFilesFailure() throws IOException {
        AutomationEngine engine = engine(
                TestTransformation.throwing("buggy", new IllegalStateException("boom")));
        Path file = write("Sample.java", "foo\n");

        TransformationResult result = engine.apply(file, "buggy");

        assertFalse(result.succeeded());
        assertTrue(result.error().contains("IllegalStateException"));
        assertTrue(result.error().contains("boom"));
    }

    @Test
    void anUnregisteredNameIsACallerErrorAndIsNotRepeatedPerFile() throws IOException {
        AutomationEngine engine = engine(TestTransformation.identity("noop"));
        write("a/Sample.java", "class A {}\n");
        write("b/Other.java", "class B {}\n");

        // One throw with the registry's list of real names, not one failure per file in the tree.
        assertThrows(NoSuchElementException.class, () -> engine.apply(root.resolve("a/Sample.java"), "typo"));
        assertThrows(NoSuchElementException.class, () -> engine.applyAll(root, "typo"));
        assertThrows(NoSuchElementException.class, () -> engine.applyAll(root, "typo", true));
    }

    @Test
    void aRepeatedNameInAChainedRunIsRefused() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.replacing("first", "a", "b"));
        Path file = write("Sample.java", "a\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> engine.applySequential(file, List.of("first", "first")));
        assertTrue(failure.getMessage().contains("twice"));
    }

    @Test
    void applyReportsAFileThatCannotBeRead() throws IOException {
        AutomationEngine engine = engine(TestTransformation.identity("noop"));
        Files.createDirectories(root.resolve("a"));

        // A directory is not readable text, which is the per-file failure a batch over a tree has to
        // survive: the result says what happened instead of the run dying.
        TransformationResult result = engine.apply(root.resolve("a"), "noop");

        assertFalse(result.succeeded());
        assertFalse(result.changed());
        assertTrue(result.error().contains("cannot read the file"));
    }

    @Test
    void aReadFailureIsReportedForEveryStepInAChain() throws IOException {
        AutomationEngine engine = new AutomationEngine();
        engine.register(TestTransformation.replacing("first", "a", "b"));
        engine.register(TestTransformation.replacing("second", "b", "c"));
        Files.createDirectories(root.resolve("a"));

        Map<String, TransformationResult> results =
                engine.applySequential(root.resolve("a"), List.of("first", "second"));

        assertEquals(2, results.size());
        assertTrue(results.values().stream().noneMatch(TransformationResult::succeeded));
        assertTrue(results.get("first").error().contains("cannot read the file"));
    }

    @Test
    void validateReadsTheFilesFacts() throws IOException {
        Path file = write("Person.java", """
                package a.b;

                public class Person {
                }
                """);

        ValidationResult result = engine(TestTransformation.identity("noop")).validate(file);

        assertTrue(result.isValid(), () -> result.render());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void validateReportsAPublicTypeNamedAfterAnotherFile() throws IOException {
        Path file = write("Other.java", """
                package a.b;

                public class Person {
                }
                """);

        ValidationResult result = engine(TestTransformation.identity("noop")).validate(file);

        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).contains("does not match the file name Other.java"));
    }

    @Test
    void validateReportsAnUnreadableFileInsteadOfThrowing() {
        ValidationResult result = engine(TestTransformation.identity("noop")).validate(root);

        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).contains("cannot read the file"));
    }

    @Test
    void analyzeCountsStructureFromTheTree() throws IOException {
        Path file = write("Sample.java", """
                package a.b;

                import java.util.List;

                /** A type with three methods, one of them behind a comment. */
                public class Sample {
                    private final List<String> names = List.of();
                    private final String label = "sample";

                    int nameCount() {
                        // a comment the counter must not mistake for code
                        return names.size();
                    }

                    String label() {
                        return label;
                    }

                    boolean isEmpty() {
                        return names.isEmpty() && label.isEmpty();
                    }
                }
                """);

        AnalysisResult result = engine(TestTransformation.identity("noop")).analyze(file);

        assertEquals("a.b", result.get(AnalysisResult.PACKAGE));
        assertEquals(1, result.count(AnalysisResult.TYPE_COUNT));
        assertEquals(1, result.count(AnalysisResult.CLASS_COUNT));
        assertEquals(0, result.count(AnalysisResult.INTERFACE_COUNT));
        assertEquals(3, result.count(AnalysisResult.METHOD_COUNT));
        assertEquals(1, result.count(AnalysisResult.IMPORT_COUNT));
        // The line counts are exact on purpose: a regular expression over the text gets the comment inside
        // the method body wrong, which is the technique this class replaced.
        assertEquals(22, result.count(AnalysisResult.TOTAL_LINES));
        assertEquals(15, result.count(AnalysisResult.CODE_LINES));
        assertEquals(2, result.count(AnalysisResult.COMMENT_LINES));
        assertEquals(5, result.count(AnalysisResult.BLANK_LINES));
        assertTrue(result.issues().isEmpty(), () -> result.render());
    }

    @Test
    void analyzeFlagsATinyFileAndSurvivesAnUnreadableOne() throws IOException {
        Path tiny = write("Tiny.java", "package a;\n\npublic class Tiny {}\n");
        AutomationEngine engine = engine(TestTransformation.identity("noop"));

        AnalysisResult small = engine.analyze(tiny);
        assertTrue(small.issues().stream().anyMatch(issue -> issue.contains("fewer than 10 lines")));

        AnalysisResult unreadable = engine.analyze(root);
        assertEquals(Map.of(), unreadable.analysis());
        assertTrue(unreadable.issues().get(0).contains("cannot read the file"));
    }

    @Test
    void nullArgumentsAreRefused() {
        AutomationEngine engine = engine(TestTransformation.identity("noop"));

        assertThrows(IllegalArgumentException.class, () -> engine.apply(null, "noop"));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(root.resolve("x.java"), ""));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(root.resolve("x.java"), null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.applyAllSequential(root, null));
    }

    private static AutomationEngine engine(Transformation transformation) {
        AutomationEngine engine = new AutomationEngine();
        engine.register(transformation);
        return engine;
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
