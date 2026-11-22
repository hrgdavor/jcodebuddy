// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Publishes the agent's dashboard into the project's app root so the webview host can serve it.
 *
 * <p>The agent is a tool, not a second web server: the page belongs at
 * {@code <project>/.jcodebuddy/app/jcodebuddy-agent.html}, and its calls go through the host's
 * {@code /api/tools/jcodebuddy-agent/call} proxy to the socket this agent publishes. The same built page is
 * still served by {@link CommandServer} at its own origin for standalone use; that is why the resources are
 * copied, not generated twice.</p>
 */
public final class AgentPageInstaller {

    private static final Logger log = LoggerFactory.getLogger(AgentPageInstaller.class);

    private AgentPageInstaller() {
    }

    /** Copies the built page and its two assets; a missing build is logged, never fatal. */
    public static void install(Path projectRoot) {
        if (projectRoot == null) {
            return;
        }
        Path app = projectRoot.resolve(".jcodebuddy").resolve("app");
        try {
            Files.createDirectories(app);
            copy("/web/index.html", app.resolve("jcodebuddy-agent.html"));
            copy("/web/jcodebuddy-agent.js", app.resolve("jcodebuddy-agent.js"));
            copy("/web/jcodebuddy-agent.css", app.resolve("jcodebuddy-agent.css"));
            copy("/web/builder.html", app.resolve("builder.html"));
            copy("/web/builder.js", app.resolve("builder.js"));
            copy("/web/microlighter.js", app.resolve("microlighter.js"));
            log.info("Agent dashboard installed at {}/jcodebuddy-agent.html", app);
        } catch (IOException | RuntimeException failed) {
            log.warn("Agent dashboard was not installed into {}: {}", app, failed.getMessage());
        }
    }

    private static void copy(String resource, Path target) throws IOException {
        try (InputStream input = AgentPageInstaller.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing resource " + resource + " (run the web-dashboard build)");
            }
            Files.write(target, input.readAllBytes());
        }
    }
}
