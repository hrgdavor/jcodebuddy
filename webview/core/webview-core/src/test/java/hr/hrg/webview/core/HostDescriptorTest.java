package hr.hrg.webview.core;

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
 * The descriptor is what makes an ephemeral port usable and a second host visible. Both of those are decided
 * here, so both are tested here rather than through a socket.
 *
 * <p>The record lives in this module because every host publishes it (see {@link HostDescriptor}); the tests
 * came with it for the same reason, so that a change to the file's shape is caught once rather than in each
 * host's own suite.
 */
class HostDescriptorTest {

    private static HostDescriptor sample(Path project) {
        return HostDescriptor.of(project, HostHealth.PLUGIN_WEBVIEWD, HostHealth.IDE_WEBVIEWD, 18899,
                project.resolve(".jcodebuddy/webview/token").toString(), List.of("open"),
                new HostDescriptor.HostDetail("zed-cli", true, "file-only", "no caret"));
    }

    @Test
    void roundTripsThroughTheFileAndKeepsTheSecretOutOfIt(@TempDir Path project) throws IOException {
        HostDescriptor written = sample(project).write(project);

        Path file = HostDescriptor.fileOf(project);
        assertTrue(Files.isRegularFile(file));
        assertEquals(project.resolve(".jcodebuddy/webview/host.json"), file,
                "the descriptor is per project, under the project's own .jcodebuddy");
        String json = Files.readString(file, StandardCharsets.UTF_8);
        // Asserted on the key set rather than on a substring: the token's *path* legitimately ends in "token",
        // so a substring check would either pass by accident or fail for the wrong reason.
        for (String key : List.of("plugin", "ide", "pid", "port", "project", "tokenPath", "capabilities",
                "startedAt")) {
            assertTrue(json.contains("\"" + key + "\":"), "missing key " + key + " in " + json);
        }
        assertFalse(json.contains("\"token\":"), "the descriptor names the token path, never the token");

        HostDescriptor read = HostDescriptor.read(project);
        assertNotNull(read);
        assertEquals(written.port(), read.port());
        assertEquals(written.pid(), read.pid());
        assertEquals(HostHealth.IDE_WEBVIEWD, read.ide(),
                "the descriptor names the editor, so a reader need not probe the port to learn it");
        assertEquals(List.of("open"), read.capabilities());
        assertEquals("file-only", read.host().lineNavigation());
        assertTrue(read.project().contains("/"), "paths are stored forward-slashed so a page can use them");
    }

    @Test
    void liveMeansTheProcessExistsNotThatTheFileExists(@TempDir Path project) throws IOException {
        HostDescriptor mine = HostDescriptor.of(project, "p", "webviewd", 1, "t", List.of(), null);
        assertTrue(mine.isLive(), "the test's own process is alive");
        assertTrue(mine.isOurs(), "and it is this process that wrote it");

        HostDescriptor dead = new HostDescriptor("p", "webviewd", 999_999_999L, 1, false, project.toString(), "t",
                List.of(), "then", null);
        assertFalse(dead.isLive());
        assertFalse(dead.isOurs());
    }

    @Test
    void aLiveHostIsVisibleAndADeadOneIsNot(@TempDir Path project) throws IOException {
        sample(project).write(project);
        assertTrue(HostDescriptor.blocksStart(HostDescriptor.read(project)));
        assertTrue(sample(project).conflictMessage().contains("already running"));
        assertTrue(sample(project).conflictMessage().contains(HostHealth.IDE_WEBVIEWD),
                "the conflict names the editor, not only a pid");

        // A process that was killed without running its shutdown hook leaves the file behind: the next start
        // must proceed rather than tell the user about a host that is not there.
        HostDescriptor stale = new HostDescriptor("p", "webviewd", 999_999_999L, 18899, false, project.toString(), "t",
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

    @Test
    void publishingAlsoKeepsTheStateOutOfGitStatus(@TempDir Path project) throws IOException {
        sample(project).write(project);

        Path ignore = HostDescriptor.directoryOf(project).resolve(".gitignore");
        assertTrue(Files.isRegularFile(ignore),
                "a host is pointed at arbitrary directories, so it must ignore its own state: " + ignore);
        assertTrue(Files.readString(ignore).contains("*"),
                "the mechanism is the ordinary one: `*` in the state directory's own .gitignore");

        // A project that wants a narrower policy of its own writes one, and a later start must not clobber it.
        Files.writeString(ignore, "mine\n", StandardCharsets.UTF_8);
        sample(project).write(project);
        assertEquals("mine\n", Files.readString(ignore), "an existing ignore policy is never overwritten");
    }

    @Test
    void theRecordOutlivesTheHostThatWroteIt(@TempDir Path project) throws IOException {
        // A host that stops leaves the record in place, because the record is the project's port rather than
        // the process's: it is what the next start asks for, and where a `sticky` pin lives. Nothing in a
        // host's shutdown path may delete it — the only way it goes is a user deleting the file.
        sample(project).write(project);
        HostDescriptor afterStop = HostDescriptor.read(project);

        assertNotNull(afterStop, "a stopped host's record is still the checkout's current port");
        assertEquals(18899, afterStop.port());
        assertEquals(sample(project).pid(), afterStop.pid(),
                "and it still says which process wrote it, which is how a live host is recognised at all");

        assertTrue(HostDescriptor.delete(project), "deleting it is a deliberate act, and it works");
        assertNull(HostDescriptor.read(project));
    }

    @Test
    void thePinIsRecordedWithThePortAndDefaultsToOff(@TempDir Path project) throws IOException {
        HostDescriptor.of(project, HostHealth.PLUGIN_WEBVIEWD, HostHealth.IDE_WEBVIEWD, 18899, true, "t",
                List.of("open"), null).write(project);

        HostDescriptor read = HostDescriptor.read(project);
        assertNotNull(read);
        assertTrue(read.sticky(), "a pinned port is a fact about this checkout, and the file is where it lives");
        assertTrue(read.description().contains("sticky"), read.description());

        // A descriptor written before the field existed (or by a host that does not pin) reads as unpinned:
        // the default has to be the permissive one, or an upgrade would silently pin every existing project.
        Files.writeString(HostDescriptor.fileOf(project), """
                { "plugin": "p", "ide": "webviewd", "pid": 1, "port": 18899, "project": "%s",
                  "tokenPath": "t", "capabilities": [], "startedAt": "then", "host": null }
                """.formatted(project.toString().replace('\\', '/')), StandardCharsets.UTF_8);
        assertFalse(HostDescriptor.read(project).sticky());
    }

    @Test
    void aCommandCanFlipThePinWithoutPretendingToBeTheHost(@TempDir Path project) throws IOException {
        // `webviewd --sticky` on a project another host is already serving: the pin belongs to the port, so it
        // is changed in place, and the pid of the host that holds the port must survive untouched.
        HostDescriptor held = new HostDescriptor("p", "IntelliJ Platform", 999_999_999L, 18899, false,
                project.toString(), "t", List.of("open"), "then", null);
        held.write(project);

        held.withSticky(true).write(project);

        HostDescriptor read = HostDescriptor.read(project);
        assertTrue(read.sticky());
        assertEquals(999_999_999L, read.pid(), "the host that holds the port is still the one recorded");
        assertEquals(18899, read.port());
        assertEquals("then", read.startedAt());

        read.withSticky(false).write(project);
        assertFalse(HostDescriptor.read(project).sticky(), "and `--no-sticky` clears it again");
    }
}
