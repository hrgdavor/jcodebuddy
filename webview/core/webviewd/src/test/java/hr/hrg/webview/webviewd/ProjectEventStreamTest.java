package hr.hrg.webview.webviewd;

import hr.hrg.jcodebuddy.watch.WatchBatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frames a page receives when the project changes.
 *
 * <p>The production source is the fs-meta daemon; {@link ProjectEventStream#forTesting(Path)} is the test seam
 * that supplies {@link WatchBatch}es directly, so these tests assert the frame decisions without a socket or a
 * native daemon. {@link ProjectEventStream#of(Path)} is separately asserted to fail closed when no daemon
 * serves the root — there is no Java {@code WatchService} fallback.</p>
 */
class ProjectEventStreamTest {

    @TempDir
    Path project;

    @Test
    void aChangedFileBecomesOneFrameNamingItProjectRelatively() throws Exception {
        try (ProjectEventStream stream = ProjectEventStream.forTesting(project)) {
            stream.offer(new WatchBatch(Set.of("src/A.java"), Set.of(), false));

            String frame = stream.awaitFrame(500);

            assertTrue(frame.contains("event: change"), frame);
            assertTrue(frame.contains("src/A.java"), frame);
            assertFalse(frame.startsWith(":"), "a real change is not a keep-alive: " + frame);
        }
    }

    @Test
    void aDeletedFileIsNamedInTheFrame() throws Exception {
        try (ProjectEventStream stream = ProjectEventStream.forTesting(project)) {
            stream.offer(new WatchBatch(Set.of(), Set.of("src/Gone.java"), false));

            String frame = stream.awaitFrame(500);

            assertTrue(frame.contains("src/Gone.java"), frame);
        }
    }

    @Test
    void nothingHappeningProducesACommentFrameRatherThanNothing() throws Exception {
        try (ProjectEventStream stream = ProjectEventStream.forTesting(project)) {
            String frame = stream.awaitFrame(150);

            assertTrue(frame.startsWith(": "), "an idle stream must send something a proxy can pass on");
        }
    }

    @Test
    void generatedDirectoriesAreFilteredBeforeAFrameIsBuilt() throws Exception {
        try (ProjectEventStream stream = ProjectEventStream.forTesting(project)) {
            stream.offer(new WatchBatch(Set.of(".git/index", "target/classes/A.class"), Set.of(), false));

            String frame = stream.awaitFrame(300);

            assertTrue(frame.startsWith(": "), "a change under a generated directory must not reach the page: " + frame);
        }
    }

    @Test
    void anOverflowBatchAsksThePageToRenderEverything() throws Exception {
        try (ProjectEventStream stream = ProjectEventStream.forTesting(project)) {
            stream.offer(new WatchBatch(Set.of(), Set.of(), true));

            String frame = stream.awaitFrame(500);

            assertTrue(frame.contains("\"*\""), "an overflow is a full-render signal: " + frame);
        }
    }

    @Test
    void closingTheStreamIsNotAnErrorEvenWhenNothingWasRead() throws IOException {
        ProjectEventStream stream = ProjectEventStream.forTesting(project);
        stream.close();
        stream.close();
    }

    @Test
    void withoutADaemonTheStreamFailsWithAnActionableMessage() {
        IOException failure = assertThrows(IOException.class, () -> ProjectEventStream.of(project));
        assertTrue(failure.getMessage().contains("fs-meta daemon is required"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fsmeta daemon start"), failure.getMessage());
    }
}
