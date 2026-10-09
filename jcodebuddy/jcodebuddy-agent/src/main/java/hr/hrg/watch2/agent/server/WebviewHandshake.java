// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The handshake that finds the editor's jump service — plan step 8.3, and § 1.8's "Instance Discovery".
 *
 * <p><b>The problem this solves.</b> An IDE host binds the port it can, not always the one it asked for:
 * {@code jwa.sidecar.jumpPort} is a <em>request</em>, and when something else holds it the next free port is
 * taken ({@code HostPortClaim}). The host then publishes the port it actually bound, per project, in
 * {@code <project>/.jcodebuddy/webview/host.json} — "a port belongs to a project, and this process does not know
 * its project until the client's {@code initialize} has said where it is". Until this class existed the agent
 * resolved the port from the {@code jwa.sidecar.jumpPort} system property alone, so a host that took the next
 * free port was simply <em>unreachable</em> from the dashboard: the one case the handshake exists for.</p>
 *
 * <p><b>The file is a contract owned by somebody else, and it is read rather than modelled.</b> The writer is
 * {@code hr.hrg.webview.core.HostDescriptor} in {@code webview-core} — a closed record serialised by Gson, so an
 * extra field cannot be added without editing that class and its tests. This class therefore reads the two
 * things it needs out of the JSON by name ({@code port}, {@code project}; plus {@code pid} to tell a live host
 * from a dead one) instead of taking a dependency on the whole webview module for a port number. The keys are
 * asserted against the real writer's output by {@code WebviewHandshakeTest}, which is what keeps the two ends in
 * step — a reader that guessed wrong would fail there rather than in an editor.</p>
 *
 * <p><b>Precedence, and why it is this way round.</b> A <em>published</em> port that (a) names this project and
 * (b) comes from a process that is still alive is the most specific answer available and wins. Anything else —
 * no file, a different project, a dead pid, unreadable JSON — falls back to the configured
 * {@code jwa.sidecar.jumpPort} (default 7979) exactly as before. Read-only checkouts and a machine with no IDE
 * keep working, which is why nothing here fails a jump: it only chooses where to send one.</p>
 */
public final class WebviewHandshake {

    private static final Logger log = LoggerFactory.getLogger(WebviewHandshake.class);

    /** Where a host publishes, relative to the project root (DEC-026: per project, git-ignored). */
    public static final String STATE_DIR = ".jcodebuddy/webview";
    /** The descriptor's file name, as {@code HostDescriptor.FILE_NAME} spells it. */
    public static final String FILE_NAME = "host.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The port the legacy system property asks for, used only when nothing is published. */
    public static final String PORT_PROPERTY = "jwa.sidecar.jumpPort";
    /** The sidecar's own default, and the agent's when neither source says anything. */
    public static final int DEFAULT_PORT = 7979;

    private final Path projectRoot;
    private final boolean livenessCheck;

    /**
     * @param projectRoot the project this agent serves — the same root the descriptor is keyed by
     */
    public WebviewHandshake(Path projectRoot) {
        this(projectRoot, true);
    }

    /**
     * @param livenessCheck whether a published pid must still be running to be believed. Exists for the test
     *        that has to write a descriptor naming a process that is not this one; production always checks.
     */
    WebviewHandshake(Path projectRoot, boolean livenessCheck) {
        this.projectRoot = projectRoot == null ? Path.of(".") : projectRoot;
        this.livenessCheck = livenessCheck;
    }

    /** The descriptor's path for this project, whether or not it exists. */
    public Path descriptorFile() {
        return projectRoot.resolve(STATE_DIR).resolve(FILE_NAME);
    }

    /**
     * Where a jump should be sent: the published port when it is this project's and the writer is alive,
     * otherwise the configured request, otherwise {@link #DEFAULT_PORT}.
     */
    public int jumpPort() {
        Optional<Published> published = publishedHost();
        if (published.isPresent()) {
            return published.get().port();
        }
        return Integer.getInteger(PORT_PROPERTY, DEFAULT_PORT);
    }

    /**
     * The token to present, or {@code ""} when there is none.
     *
     * <p>Read from the descriptor's {@code tokenPath} when that names a readable file, because a host that was
     * started with a token writes it there and the agent is expected to present it. Falls back to
     * {@code jwa.sidecar.token} — the property a developer sets by hand — and to nothing, which is the honest
     * answer for a host started with no token and no allowed origin (it would refuse anyway, and an invented
     * token would only make the refusal look like a bug here).</p>
     */
    public String token() {
        Optional<Published> published = publishedHost();
        if (published.isPresent()) {
            String fromFile = published.get().token();
            if (!fromFile.isEmpty()) {
                return fromFile;
            }
        }
        return System.getProperty("jwa.sidecar.token", "").trim();
    }

    /** A short description of the decision, for a log line or a dashboard that has to explain itself. */
    public String describe() {
        Optional<Published> published = publishedHost();
        if (published.isPresent()) {
            Published host = published.get();
            return "published host.json: port " + host.port() + ", pid " + host.pid()
                    + ", ide " + (host.ide().isEmpty() ? "unknown" : host.ide());
        }
        if (Files.isRegularFile(descriptorFile())) {
            return "no descriptor for this project at " + descriptorFile()
                    + "; using " + PORT_PROPERTY + "=" + jumpPort();
        }
        return "nothing published at " + descriptorFile() + "; using " + PORT_PROPERTY + "=" + jumpPort();
    }

    /**
     * The published descriptor, when it is usable: this project's, live, and with a positive port.
     *
     * <p>Every rejection is silent by design. A descriptor written by another checkout, by a process that has
     * since exited, or half-written by one that was killed mid-write is <em>normal</em> here, and a log line per
     * jump would turn a healthy fallback into noise. {@link #describe()} is where a caller asks what happened.</p>
     */
    Optional<Published> publishedHost() {
        Path file = descriptorFile();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            // A truncated descriptor is the documented consequence of a killed host, not a fault to report loudly.
            log.debug("Ignoring an unreadable {}: {}", file, String.valueOf(unreadable.getMessage()));
            return Optional.empty();
        }
        int port = node.path("port").asInt(0);
        if (port <= 0) {
            return Optional.empty();
        }
        // The project check is the instance discovery: the same machine may hold descriptors for several
        // checkouts, and jumping into the wrong one is worse than not jumping.
        String publishedProject = node.path("project").asString("");
        if (!sameProject(publishedProject)) {
            return Optional.empty();
        }
        long pid = node.path("pid").asLong(0);
        if (livenessCheck && pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
            // Published by a process that is gone: a stale port is the case that makes a blind caller hang.
            return Optional.empty();
        }
        return Optional.of(new Published(port, pid, node.path("ide").asString(""),
                readToken(node.path("tokenPath").asString(""))));
    }

    /** Whether a descriptor's recorded project is this one, compared the way paths have to be. */
    private boolean sameProject(String publishedProject) {
        if (publishedProject == null || publishedProject.isBlank()) {
            return false;
        }
        try {
            // Both sides are normalised absolute paths: the writer publishes an absolute, forward-slashed path,
            // and this process has its own. Comparing the strings would fail on a trailing separator or a
            // different drive spelling for the same directory.
            Path their = Path.of(publishedProject).toAbsolutePath().normalize();
            Path ours = projectRoot.toAbsolutePath().normalize();
            return their.equals(ours);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** The token a host wrote to its token file, or {@code ""} when there is none to read. */
    private String readToken(String tokenPath) {
        if (tokenPath == null || tokenPath.isBlank()) {
            return "";
        }
        try {
            Path file = Path.of(tokenPath);
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException unreadable) {
            log.debug("Ignoring an unreadable token file {}: {}", tokenPath,
                    String.valueOf(unreadable.getMessage()));
            return "";
        }
    }

    /** The published host's facts, as far as this class needs them. */
    record Published(int port, long pid, String ide, String token) {
    }
}
