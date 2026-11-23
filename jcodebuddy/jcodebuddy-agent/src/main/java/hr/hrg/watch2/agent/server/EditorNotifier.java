// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells the editor host that a file the agent wrote on disk changed.
 *
 * <p>The agent writes through {@link hr.hrg.jcodebuddy.tool.WriteCoordinator}, not through the host's buffer
 * edit route, so an editor with the file open can otherwise keep a stale document until its own VFS poll
 * catches up. The next keystroke then looks like a conflicting edit. The host exposes a token-protected
 * refresh route for exactly this case; this notifier posts the written path to it after the write succeeds.</p>
 *
 * <p>Notification is best-effort and asynchronous: a host that is absent, too old to expose the route, or
 * slow must never make an apply fail. The host accepts this refresh from loopback without a token, because
 * the agent may have no host token; the route changes no bytes.</p>
 */
final class EditorNotifier {

    private static final Logger log = LoggerFactory.getLogger(EditorNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofMillis(800);

    private final Path projectRoot;
    private final Integer fixedPort;
    private final String fixedToken;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    EditorNotifier(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.fixedPort = null;
        this.fixedToken = null;
    }

    /** Test seam: notify a fixed endpoint without discovering a host descriptor. */
    EditorNotifier(int port, String token) {
        this.projectRoot = null;
        this.fixedPort = port;
        this.fixedToken = token == null ? "" : token;
    }

    void changed(Path path) {
        if (path == null) return;
        Path absolute = path.toAbsolutePath().normalize();
        executor.submit(() -> send(absolute));
    }

    private void send(Path path) {
        String token = fixedToken;
        int port;
        if (fixedPort != null) {
            port = fixedPort;
        } else {
            WebviewHandshake handshake = new WebviewHandshake(projectRoot);
            port = handshake.jumpPort();
            token = handshake.token();
        }
        if (token == null) token = "";
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "paths", List.of(path.toString().replace('\\', '/'))));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/refresh"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!token.isBlank()) builder.header("X-WebView-Token", token);
            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                log.debug("editor refresh for {} answered {}", path, response.statusCode());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // A host that is not there, or a host without the route, is a normal standalone deployment.
            log.debug("editor refresh for {} failed: {}", path, ignored.getMessage());
        }
    }
}
