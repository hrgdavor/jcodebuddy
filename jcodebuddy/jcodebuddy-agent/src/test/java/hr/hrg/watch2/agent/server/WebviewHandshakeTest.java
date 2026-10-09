// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan step 8.3's gate, the half that can be tested without an editor: the agent finds the port the host
 * <em>actually bound</em> instead of assuming the one it asked for.
 *
 * <p>The fixture writes the descriptor by hand rather than by calling the writer, and that is deliberate: the
 * keys below are the contract between this module and {@code webview-core}'s {@code HostDescriptor}, and a test
 * that built the file with the same library on both sides would pass even if this reader had the wrong key
 * names. The keys are Gson's rendering of the record's declared components, spelled out with the source of that
 * shape named — see {@link #extraAndNullFieldsInTheDescriptorChangeNothing()} for why a reflection check is not
 * available (this module does not depend on the webview stack, on purpose).</p>
 *
 * <p>JUnit 5, this module's generation: a JUnit 4 test was once ignored by surefire here while the build stayed
 * green (see {@code RemoteJumpTest}'s header).</p>
 */
public class WebviewHandshakeTest {

    @TempDir
    Path project;

    @AfterEach
    public void clearProperties() {
        System.clearProperty(WebviewHandshake.PORT_PROPERTY);
        System.clearProperty("jwa.sidecar.token");
    }

    /**
     * The descriptor the host writes, as {@code HostDescriptor.of(...).write(project)} emits it.
     *
     * <p>Field order and names are the record's: {@code plugin, ide, pid, port, sticky, project, tokenPath,
     * capabilities, startedAt, host}, with {@code host} a nested {@code {name, available, lineNavigation,
     * note}}. Gson writes nulls out by default, which is why a sidecar's {@code tokenPath} appears as a key with
     * a null value.</p>
     */
    private void publish(int port, long pid, String publishedProject, String tokenPath) throws IOException {
        Path dir = project.resolve(WebviewHandshake.STATE_DIR);
        Files.createDirectories(dir);
        String json = "{\n"
                + "  \"plugin\": \"jwa-sidecar\",\n"
                + "  \"ide\": \"zed\",\n"
                + "  \"pid\": " + pid + ",\n"
                + "  \"port\": " + port + ",\n"
                + "  \"sticky\": false,\n"
                + "  \"project\": \"" + publishedProject.replace("\\", "/") + "\",\n"
                + "  \"tokenPath\": " + (tokenPath == null ? "null" : "\"" + tokenPath.replace("\\", "\\\\") + "\"") + ",\n"
                + "  \"capabilities\": [\"open\"],\n"
                + "  \"startedAt\": \"2026-10-10T00:00:00Z\",\n"
                + "  \"host\": { \"name\": \"lsp\", \"available\": true, \"lineNavigation\": \"exact\", \"note\": \"\" }\n"
                + "}\n";
        Files.writeString(dir.resolve(WebviewHandshake.FILE_NAME), json, StandardCharsets.UTF_8);
    }

    /** A port the host took instead of the one it was asked for is the whole reason this class exists. */
    @Test
    public void aPublishedPortForThisProjectIsUsed() throws IOException {
        System.setProperty(WebviewHandshake.PORT_PROPERTY, "7979");
        publish(8123, ProcessHandle.current().pid(), project.toString(), null);

        assertEquals(8123, new WebviewHandshake(project).jumpPort(),
                "the published port wins over the requested one, or a host on the next free port is unreachable");
    }

    /** Another checkout's host is not ours: jumping into it would open a file in the wrong project. */
    @Test
    public void aDescriptorForAnotherProjectIsIgnored() throws IOException {
        System.setProperty(WebviewHandshake.PORT_PROPERTY, "7979");
        publish(8123, ProcessHandle.current().pid(), project.resolve("somewhere-else").toString(), null);

        assertEquals(7979, new WebviewHandshake(project).jumpPort(),
                "a descriptor naming a different project must fall back, not be followed");
        assertTrue(new WebviewHandshake(project).publishedHost().isEmpty());
    }

    /** A descriptor left behind by a host that has exited names a port nothing listens on. */
    @Test
    public void aDescriptorFromAProcessThatIsGoneIsIgnored() throws IOException {
        System.setProperty(WebviewHandshake.PORT_PROPERTY, "7979");
        // A pid that cannot be running: the process id space on every supported platform is far below this.
        publish(8123, Integer.MAX_VALUE, project.toString(), null);

        WebviewHandshake handshake = new WebviewHandshake(project);
        assertEquals(7979, handshake.jumpPort(), "a stale descriptor must not send a jump to a dead port");
        assertTrue(handshake.describe().contains("nothing published") || handshake.describe().contains("no descriptor"),
                "and the reason must be sayable: " + handshake.describe());
    }

    /** Nothing published at all is the normal state for a machine with no editor host running. */
    @Test
    public void withNothingPublishedTheConfiguredPortIsUsed() {
        System.setProperty(WebviewHandshake.PORT_PROPERTY, "1234");
        assertEquals(1234, new WebviewHandshake(project).jumpPort());

        System.clearProperty(WebviewHandshake.PORT_PROPERTY);
        assertEquals(WebviewHandshake.DEFAULT_PORT, new WebviewHandshake(project).jumpPort(),
                "and with no configuration the sidecar's own default is the only sensible answer");
    }

    /** A half-written descriptor is what a killed host leaves; it must not make the jump impossible. */
    @Test
    public void aTruncatedDescriptorIsIgnoredRatherThanFatal() throws IOException {
        System.setProperty(WebviewHandshake.PORT_PROPERTY, "7979");
        Path dir = project.resolve(WebviewHandshake.STATE_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(WebviewHandshake.FILE_NAME), "{\"port\": 812", StandardCharsets.UTF_8);

        assertEquals(7979, new WebviewHandshake(project).jumpPort());
        assertTrue(new WebviewHandshake(project).publishedHost().isEmpty());
    }

    /** The token a host wrote is presented by the agent; one with no token file yields no token. */
    @Test
    public void theTokenComesFromThePathTheHostPublished() throws IOException {
        Path tokenFile = project.resolve("token.txt");
        Files.writeString(tokenFile, "  secret-token\n", StandardCharsets.UTF_8);
        publish(8123, ProcessHandle.current().pid(), project.toString(), tokenFile.toString());

        assertEquals("secret-token", new WebviewHandshake(project).token(),
                "the file wins, trimmed: the host writes a line ending, not part of the token");
    }

    @Test
    public void aMissingTokenFileFallsBackToTheConfiguredOneAndThenToNothing() throws IOException {
        publish(8123, ProcessHandle.current().pid(), project.toString(),
                project.resolve("not-written.txt").toString());

        System.setProperty("jwa.sidecar.token", "from-property");
        assertEquals("from-property", new WebviewHandshake(project).token());

        System.clearProperty("jwa.sidecar.token");
        assertEquals("", new WebviewHandshake(project).token(),
                "no token is the honest answer: an invented one turns the host's refusal into a bug report here");
    }

    /**
     * The reader is not fooled by the fields the descriptor carries that it does not use.
     *
     * <p>This exists in place of a reflection test against {@code HostDescriptor}, which was the first idea and
     * is not available: that record lives in {@code webview-core}, and this module deliberately does not depend
     * on the whole webview stack to read a port number. The format is therefore pinned by
     * {@link #publish(int, long, String, String)}'s literal, which is Gson's rendering of the record's declared
     * components — {@code plugin, ide, pid, port, sticky, project, tokenPath, capabilities, startedAt, host}.
     * A rename in the record would need the same edit here, and the honest statement of that cost is this
     * comment rather than a test that cannot compile.</p>
     *
     * <p>What this test does check is the part a hand-written fixture can get wrong on its own: unknown fields,
     * a nested object, a null the sidecar writes for {@code tokenPath}, and a timestamp are all present and must
     * change nothing.</p>
     */
    @Test
    public void extraAndNullFieldsInTheDescriptorChangeNothing() throws IOException {
        System.setProperty(WebviewHandshake.PORT_PROPERTY, "7979");
        Path dir = project.resolve(WebviewHandshake.STATE_DIR);
        Files.createDirectories(dir);
        // The full record, plus a field no version of it has: a newer host must not break an older reader.
        String json = "{\"plugin\":\"jwa-sidecar\",\"ide\":\"zed\",\"pid\":" + ProcessHandle.current().pid()
                + ",\"port\":8123,\"sticky\":false,\"project\":\"" + project.toString().replace("\\", "/")
                + "\",\"tokenPath\":null,\"capabilities\":[\"open\"],\"startedAt\":\"2026-10-10T00:00:00Z\","
                + "\"host\":{\"name\":\"lsp\",\"available\":true,\"lineNavigation\":\"exact\",\"note\":\"\"},"
                + "\"somethingAddedLater\":{\"nested\":[1,2,3]}}";
        Files.writeString(dir.resolve(WebviewHandshake.FILE_NAME), json, StandardCharsets.UTF_8);

        WebviewHandshake handshake = new WebviewHandshake(project);
        assertEquals(8123, handshake.jumpPort());
        assertEquals("", handshake.token(), "a null tokenPath is not an error: the sidecar writes exactly that");
        assertTrue(handshake.describe().contains("8123"), "the description names the port it chose: " + handshake.describe());
    }

    /** A published project that is a relative path, or nonsense, is not this project. */
    @Test
    public void anUnusablePublishedProjectIsNotTreatedAsAMatch() throws IOException {
        System.setProperty(WebviewHandshake.PORT_PROPERTY, "7979");
        publish(8123, ProcessHandle.current().pid(), "  ", null);
        assertEquals(7979, new WebviewHandshake(project).jumpPort());

        publish(8123, ProcessHandle.current().pid(), "relative/place", null);
        assertTrue(new WebviewHandshake(project).publishedHost().isEmpty(),
                "a relative published path resolves against this process's cwd, which is not a match");
        assertFalse(Files.isRegularFile(project.resolve("relative/place")));
    }
}
