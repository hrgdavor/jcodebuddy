package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.jcodebuddy.automation.cli.MetadataStaleCli;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ContentHash;
import hr.hrg.jcodebuddy.engine.index.FileMetadata;
import hr.hrg.jcodebuddy.engine.index.MetadataCache;
import hr.hrg.jcodebuddy.engine.index.Staleness;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

/**
 * The watch entry of plan step 6.6, and the step's own acceptance line: <em>"the CLI verb and watch mode give
 * the same answer for the same tree"</em>.
 *
 * <p>That line is why one test here runs both and compares them field for field. Two tests that each asserted
 * their own expectation would pass while the two entries disagreed — which is the failure the one-implementation
 * rule exists to prevent, and the reason both go through {@link Staleness} rather than through a rule of their
 * own.</p>
 */
class StalenessWatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = """
            package t;

            public interface A {
            }
            """;

    private static void store(Path moduleRoot, String relative, String text) throws IOException {
        Path file = moduleRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        var unit = hr.hrg.jcodebuddy.engine.source.SourceReader.readSourceText(text);
        Assertions.assertNotNull(unit, "the fixture must parse");
        MetadataCache.beside(moduleRoot.resolve(".jcodebuddy")).store(
                FileMetadata.of(relative, ContentHash.of(file), Files.size(file),
                        Files.getLastModifiedTime(file).toMillis(), false,
                        ClassIndex.factsOf(unit, text),
                        hr.hrg.jcodebuddy.engine.source.TreeQueries.importLines(unit)), null);
    }

    private static StalenessWatch watch(Path module, StalenessWatch.Action action) {
        return new StalenessWatch(Staleness.beside(module.resolve(".jcodebuddy")), module, action, null);
    }

    @Test
    void aBatchOfUnchangedFilesRunsNothing(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        List<String> actions = new java.util.ArrayList<>();

        StalenessWatch watch = watch(module, actions::addAll);
        StalenessWatch.Batch batch = watch.onBatch(List.of(module.resolve("src/main/java/A.java")));

        Assertions.assertEquals(List.of("src/main/java/A.java"), batch.checked());
        Assertions.assertTrue(batch.stale().isEmpty(), "an unchanged file is not work");
        Assertions.assertFalse(batch.regenerated());
        Assertions.assertTrue(actions.isEmpty(), "the action must not run for a batch with nothing stale in it");
        Assertions.assertEquals(Staleness.Verdict.UNCHANGED,
                batch.results().get("src/main/java/A.java").verdict());
    }

    @Test
    void aBatchWithOneEditedFileRunsTheActionOnceWithThatFile(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        store(module, "src/main/java/B.java", SOURCE);
        Files.writeString(module.resolve("src/main/java/B.java"),
                SOURCE.replace("interface A", "interface C"), StandardCharsets.UTF_8);
        List<List<String>> actions = new java.util.ArrayList<>();

        StalenessWatch.Batch batch = watch(module, actions::add).onBatch(List.of(
                module.resolve("src/main/java/A.java"),
                module.resolve("src/main/java/B.java")));

        Assertions.assertEquals(List.of("src/main/java/B.java"), batch.stale(),
                "exactly the edited file, not the whole batch");
        Assertions.assertEquals(1, actions.size(), "one action per batch, not one per file");
        Assertions.assertEquals(List.of("src/main/java/B.java"), actions.get(0));
    }

    @Test
    void aTouchedBatchIsNotWork(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        Path file = module.resolve("src/main/java/A.java");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(120)));
        List<String> actions = new java.util.ArrayList<>();

        StalenessWatch.Batch batch = watch(module, actions::addAll).onBatch(List.of(file));

        Assertions.assertEquals(Staleness.Verdict.TOUCHED, batch.results().get("src/main/java/A.java").verdict());
        Assertions.assertTrue(batch.stale().isEmpty(), "a touch is not a change here either");
        Assertions.assertTrue(actions.isEmpty());
    }

    @Test
    void aDeletedFileIsWorkBecauseNothingCanAnswerForIt(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        Path file = module.resolve("src/main/java/A.java");
        Files.delete(file);
        List<String> actions = new java.util.ArrayList<>();

        StalenessWatch.Batch batch = watch(module, actions::addAll).onBatch(List.of(file));

        Assertions.assertEquals(List.of("src/main/java/A.java"), batch.stale(),
                "a deleted source can mean a generated file has to go, so it must reach the action");
        Assertions.assertEquals(Staleness.Verdict.UNKNOWN,
                batch.results().get("src/main/java/A.java").verdict());
    }

    @Test
    void aRepeatedPathIsCheckedOnce(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        Path file = module.resolve("src/main/java/A.java");

        StalenessWatch.Batch batch = watch(module, null).onBatch(List.of(file, file, file));

        Assertions.assertEquals(List.of("src/main/java/A.java"), batch.checked(),
                "a batch that mentions a file twice is still one file");
    }

    /**
     * The step's acceptance line, asserted rather than assumed: the watch entry and the CLI verb answer the same
     * for the same tree.
     */
    @Test
    void theWatchEntryAndTheCliAgreeForTheSameTree(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        store(module, "src/main/java/B.java", SOURCE);
        Files.writeString(module.resolve("src/main/java/B.java"),
                SOURCE.replace("interface A", "interface C"), StandardCharsets.UTF_8);
        Files.createDirectories(module.resolve(".jcodebuddy/metadata"));

        StalenessWatch watch = watch(module, null);
        for (String relative : List.of("src/main/java/A.java", "src/main/java/B.java")) {
            Path file = module.resolve(relative);

            Staleness.Result fromWatch = watch.check(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int status;
            try (PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8)) {
                status = MetadataStaleCli.run(new String[] { "metadata", "stale", file.toString() },
                        stream, new PrintStream(java.io.OutputStream.nullOutputStream()));
            }
            JsonNode fromCli = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));

            Assertions.assertEquals(fromWatch.verdict().name(), fromCli.path("verdict").asText(),
                    "the two entries must not hold different opinions about " + relative);
            Assertions.assertEquals(fromWatch.tier().name(), fromCli.path("tier").asText(), relative);
            Assertions.assertEquals(fromWatch.rebuildNeeded() ? 1 : 0, status,
                    "and the exit code must be the verdict's own consequence");
        }
    }
}
