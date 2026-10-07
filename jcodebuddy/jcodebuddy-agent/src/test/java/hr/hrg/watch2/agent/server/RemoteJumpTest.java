// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan step 7.2's gate: the dashboard's remote jump works, and the page never holds the sidecar's token.
 *
 * <p>The sidecar is <b>stubbed rather than started</b>, and deliberately so: what this test has to prove is the part
 * that can rot quietly — that the agent's server presents {@code X-WebView-Token}, that it relays the sidecar's
 * answer instead of inventing one, and that a sidecar which is not listening is reported as such. Starting the real
 * sidecar would prove those same three things while adding a JDK-version dependency, a port-claim race and an LSP
 * client to the fixture; the end-to-end run against the live one is the scripted check beside this test.
 *
 * <p>The stub asserts the token it received, so a regression that "works" by dropping the header fails here rather
 * than at a customer's refusal.
 *
 * <p><b>JUnit 5, and that is this module's generation rather than a preference.</b> The first version of this test
 * was written with JUnit 4 imports and surefire's JUnit Platform provider ignored it completely: the build was green
 * and the test never ran, which is only visible by reading the surefire reports rather than the exit code. The
 * assertions below are in Jupiter's argument order — {@code condition, message}, the opposite of JUnit 4's.
 */
public class RemoteJumpTest {

    /** The token the sidecar requires, and the one this test proves is presented. */
    private static final String TOKEN = "dash-test-token";

    private HttpServer stub;
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    public void startStubSidecar() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/jump", exchange -> {
            seenToken.set(exchange.getRequestHeaders().getFirst("X-WebView-Token"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // What the real sidecar answers for a completed navigation: an OUTCOME, not a boolean.
            byte[] body = ("{\"status\":\"ok\",\"uri\":\"file:///src/People.java\",\"path\":\"/src/People.java\","
                    + "\"reason\":\"exact\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        stub.start();
        System.setProperty("jwa.sidecar.jumpPort", String.valueOf(stub.getAddress().getPort()));
        System.setProperty("jwa.sidecar.token", TOKEN);
    }

    @AfterEach
    public void stopStubSidecar() {
        if (stub != null) {
            stub.stop(0);
        }
        System.clearProperty("jwa.sidecar.jumpPort");
        System.clearProperty("jwa.sidecar.token");
    }

    /** The whole chain, over HTTP: page → agent server → sidecar, with the token on the hop that needs it. */
    @Test
    public void theDashboardJumpReachesTheSidecarWithTheTokenAndRelaysItsOutcome() throws Exception {
        CommandServer server = new CommandServer(0, Path.of("."), List.of(), null, "", "", false);
        server.start();
        try {
            HttpResponse<String> answer = jump(server.port(), "{\"uri\":\"file:///src/People.java\",\"line\":12}");

            assertEquals(200, answer.statusCode(), "the agent's own route answers with the sidecar's status");
            assertEquals(TOKEN, seenToken.get(),
                    "the sidecar's token must be presented by the SERVER, not by the page");
            assertNotNull(seenBody.get(), "the request's body must reach the sidecar");
            assertTrue(seenBody.get().contains("file:///src/People.java") && seenBody.get().contains("12"),
                    "the location the page asked for must be what is forwarded: " + seenBody.get());
            // Relayed verbatim: the sidecar's OUTCOME is what the page shows, so a second translation here would be
            // the place the two disagree.
            assertTrue(answer.body().contains("\"reason\":\"exact\""),
                    "the outcome must be relayed, not replaced by an assumed success: " + answer.body());
        } finally {
            server.stop();
        }
    }

    /**
     * A sidecar that is not listening is <b>reported</b>, not hidden.
     *
     * <p>This is the half that makes the feature honest: a dashboard that showed "Jumped" whenever its own request
     * completed would look identical whether the editor moved or nothing was listening at all.
     */
    @Test
    public void anUnreachableSidecarIsReportedRatherThanAssumedToHaveWorked() throws Exception {
        // A port nothing listens on: the stub is stopped first, so the number is real but closed.
        stub.stop(0);
        CommandServer server = new CommandServer(0, Path.of("."), List.of(), null, "", "", false);
        server.start();
        try {
            HttpResponse<String> answer = jump(server.port(), "{\"uri\":\"file:///src/People.java\",\"line\":1}");

            assertEquals(502, answer.statusCode(), "a jump nobody could perform is a 502, not a 200");
            assertTrue(answer.body().contains("unreachable"),
                    "and the reason must name what happened: " + answer.body());
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> jump(int agentPort, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + agentPort + "/jump"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
