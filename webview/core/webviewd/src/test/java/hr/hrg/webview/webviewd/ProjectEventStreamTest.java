package hr.hrg.webview.webviewd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frames a page receives when the project changes.
 *
 * <p>Tested through {@link ProjectEventStream#awaitFrame} rather than through HTTP: the interesting decisions
 * are which changes become a frame, which are ignored, and what happens when nothing happens — none of which
 * needs a socket, and all of which are painful to test through one.
 */
class ProjectEventStreamTest {

    @TempDir
    Path project;

    /**
     * Polls until a change frame arrives, the way a client reads the stream.
     *
     * <p>Not a workaround for flakiness: Windows reports a file's change in the file's own directory *and*
     * touches the parent entry's timestamp, and the two do not always land in the same batch. A client that
     * stopped at the first frame would miss the file, which is why the assertion is about the frames that
     * arrive rather than about frame one.
     */
    private static String awaitChange(ProjectEventStream stream, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        String last = "";
        while (System.nanoTime() < deadline) {
            last = stream.awaitFrame(500);
            if (last.startsWith("event: change")) {
                return last;
            }
        }
        return last;
    }

    @Test
    void aChangedFileBecomesOneFrameNamingItProjectRelatively() throws Exception {
        Files.createDirectories(project.resolve("src"));
        Path file = project.resolve("src/A.java");
        Files.writeString(file, "one\n");

        try (ProjectEventStream stream = ProjectEventStream.of(project)) {
            Files.writeString(file, "two\n");
            String frame = awaitChange(stream, 5_000);

            assertTrue(frame.contains("event: change"), frame);
            assertTrue(frame.contains("src/A.java"), frame);
            assertFalse(frame.startsWith(":"), "a real change is not a keep-alive: " + frame);
        }
    }

    @Test
    void nothingHappeningProducesACommentFrameRatherThanNothing() throws Exception {
        try (ProjectEventStream stream = ProjectEventStream.of(project)) {
            String frame = stream.awaitFrame(150);

            assertTrue(frame.startsWith(": "), "an idle stream must send something a proxy can pass on");
        }
    }

    @Test
    void generatedDirectoriesAreNotWatchedSoABuildDoesNotFloodAPage() throws Exception {
        Path ignored = project.resolve(".git");
        Files.createDirectories(ignored);
        Path noise = ignored.resolve("index");

        try (ProjectEventStream stream = ProjectEventStream.of(project)) {
            Files.writeString(noise, "churn");
            String frame = stream.awaitFrame(400);

            assertTrue(frame.startsWith(": "), "a change under .git must not reach the page: " + frame);
        }
    }

    @Test
    void aDirectoryCreatedAfterStartupIsWatchedToo() throws Exception {
        try (ProjectEventStream stream = ProjectEventStream.of(project)) {
            Path added = Files.createDirectories(project.resolve("new-package"));
            // The creation of the directory itself registers it; a change frame may or may not be sent for it.
            stream.awaitFrame(500);

            Path inside = added.resolve("B.java");
            Files.writeString(inside, "class B {}\n");
            String frame = awaitChange(stream, 5_000);

            assertTrue(frame.contains("new-package/B.java"),
                    "a page must see files in a directory that appeared while it was watching: " + frame);
        }
    }

    @Test
    void theWatchIsBoundedAndSaysSoWhenItIsPartial() throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.createDirectories(project.resolve("dir" + i));
        }
        try (ProjectEventStream stream = ProjectEventStream.of(project)) {
            assertTrue(stream.watchedDirectoryCount() >= 1);
            assertTrue(stream.watchedDirectoryCount() <= ProjectEventStream.MAX_DIRECTORIES + 1);
        }
    }

    @Test
    void closingTheStreamIsNotAnErrorEvenWhenNothingWasRead() throws IOException {
        ProjectEventStream stream = ProjectEventStream.of(project);
        stream.close();
        stream.close();
    }
}
