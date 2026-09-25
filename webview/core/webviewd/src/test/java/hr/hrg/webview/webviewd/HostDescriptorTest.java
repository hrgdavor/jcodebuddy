package hr.hrg.webview.webviewd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The descriptor is what makes an ephemeral port usable and a second start impossible. Both of those are
 * decided here, so both are tested here rather than through a socket.
 */
class HostDescriptorTest {

    private static HostDescriptor sample(Path project) {
        return HostDescriptor.of(project, 18899, project.resolve(".jcodebuddy/webview/token").toString(),
                List.of("open"), new HostDescriptor.HostDetail("zed-cli", true, "file-only", "no caret"));
    }

    @Test
    void roundTripsThroughTheFileAndKeepsTheSecretOutOfIt(@TempDir Path project) throws IOException {
        HostDescriptor written = sample(project).write(project);

        Path file = HostDescriptor.fileOf(project);
        assertTrue(Files.isRegularFile(file));
        String json = Files.readString(file, StandardCharsets.UTF_8);
        // Asserted on the key set rather than on a substring: the token's *path* legitimately ends in "token",
        // so a substring check would either pass by accident or fail for the wrong reason.
        for (String key : List.of("plugin", "pid", "port", "project", "tokenPath", "capabilities", "startedAt")) {
            assertTrue(json.contains("\"" + key + "\":"), "missing key " + key + " in " + json);
        }
        assertFalse(json.contains("\"token\":"), "the descriptor names the token path, never the token");

        HostDescriptor read = HostDescriptor.read(project);
        assertNotNull(read);
        assertEquals(written.port(), read.port());
        assertEquals(written.pid(), read.pid());
        assertEquals(List.of("open"), read.capabilities());
        assertEquals("file-only", read.host().lineNavigation());
        assertTrue(read.project().contains("/"), "paths are stored forward-slashed so a page can use them");
    }

    @Test
    void liveMeansTheProcessExistsNotThatTheFileExists(@TempDir Path project) throws IOException {
        HostDescriptor mine = HostDescriptor.of(project, 1, "t", List.of(), null);
        assertTrue(mine.isLive(), "the test's own process is alive");

        HostDescriptor dead = new HostDescriptor("p", 999_999_999L, 1, project.toString(), "t", List.of(),
                "then", null);
        assertFalse(dead.isLive());
    }

    @Test
    void aLiveHostBlocksASecondStartAndADeadOneDoesNot(@TempDir Path project) throws IOException {
        sample(project).write(project);
        assertTrue(HostDescriptor.blocksStart(HostDescriptor.read(project)));
        assertTrue(sample(project).conflictMessage().contains("already running"));

        // A process that was killed without running its shutdown hook leaves the file behind: the next start
        // must proceed rather than tell the user about a host that is not there.
        HostDescriptor stale = new HostDescriptor("p", 999_999_999L, 18899, project.toString(), "t",
                List.of(), "then", null);
        stale.write(project);
        assertFalse(HostDescriptor.blocksStart(HostDescriptor.read(project)));
        assertEquals(999_999_999L, HostDescriptor.read(project).pid());
    }

    @Test
    void anUnreadableOrAbsentDescriptorCountsAsAbsent(@TempDir Path project) throws IOException {
        assertNull(HostDescriptor.read(project));

        Files.createDirectories(HostDescriptor.directoryOf(project));
        Files.writeString(HostDescriptor.fileOf(project), "{ truncated", StandardCharsets.UTF_8);
        assertNull(HostDescriptor.read(project), "a half-written file must not make the next start impossible");
    }

    @Test
    void deleteRemovesItAndReportsWhetherItWasThere(@TempDir Path project) throws IOException {
        sample(project).write(project);
        assertTrue(HostDescriptor.delete(project));
        assertFalse(HostDescriptor.delete(project));
        assertNull(HostDescriptor.read(project));
    }
}
