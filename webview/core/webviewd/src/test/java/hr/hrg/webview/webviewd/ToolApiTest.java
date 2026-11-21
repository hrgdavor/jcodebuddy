package hr.hrg.webview.webviewd;

import hr.hrg.jcodebuddy.ipc.ToolDescriptor;
import hr.hrg.jcodebuddy.ipc.ToolEndpoint;
import hr.hrg.jcodebuddy.ipc.ToolException;
import hr.hrg.jcodebuddy.ipc.ToolMethods;
import hr.hrg.jcodebuddy.ipc.ToolProtocol;
import hr.hrg.webview.core.NullHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEC-053's proxy: the descriptor list and the call route, over a real Unix socket.
 *
 * <p>The tool here is a real {@link ToolEndpoint} from {@code jcodebuddy-ipc}, not a stub of the host's own
 * making — the point of the route is that the host speaks the published contract, so the test speaks it too.</p>
 */
class ToolApiTest {

    private static final String ORIGIN = "https://allowed.test";
    private static final String TOKEN = "secret";
    private static final String TOOL = "test-tool";

    @TempDir
    Path project;

    private WebviewServer server;
    private ToolEndpoint endpoint;
    private PushTool tool;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (endpoint != null) {
            endpoint.stop();
        }
    }

    private void start() throws IOException {
        server = WebviewServer.start(new WebviewdConfig(project, 0, true, null, false, ORIGIN, TOKEN,
                WebviewdConfig.HostChoice.NONE, WebviewdConfig.DEFAULT_SIDECAR_PORT, "", false),
                NullHost.INSTANCE);
    }

    private void startTool() throws IOException {
        tool = new PushTool();
        endpoint = new ToolEndpoint(project, TOOL, "Test tool", "JUnit", "1.0", tool);
        endpoint.start();
    }

    /** A tool with a log topic: the shape the compiler endpoint has, reduced to what the proxy needs. */
    private static final class PushTool implements ToolMethods {

        private final List<Map<String, Object>> history = new CopyOnWriteArrayList<>();
        private final List<Consumer<Map<String, Object>>> sinks = new CopyOnWriteArrayList<>();

        void push(String text) {
            Map<String, Object> event = Map.of("sequence", (long) history.size() + 1, "text", text);
            history.add(event);
            for (Consumer<Map<String, Object>> sink : sinks) {
                sink.accept(event);
            }
        }

        @Override
        public Object call(String method, Map<String, Object> params) throws ToolException {
            return switch (method) {
                case "build.status" -> Map.of("enabled", true, "watch", "watching", "batchMarker", 7);
                case "build.log" -> Map.of("lines",
                        List.of(Map.of("sequence", 7, "text", "hello from the tool")), "next", 7);
                case "build.rebuild" -> Map.of("accepted", true);
                default -> throw ToolException.methodNotFound(method);
            };
        }

        @Override
        public Collection<String> capabilities() {
            return List.of("build.status", "build.log", "build.rebuild");
        }

        @Override
        public Collection<String> topics() {
            return List.of("log");
        }

        @Override
        public AutoCloseable subscribe(String topic, Long since, Consumer<Map<String, Object>> sink)
                throws ToolException {
            if (!"log".equals(topic)) {
                throw ToolException.invalidParams("no topic '" + topic + "'");
            }
            if (since != null) {
                for (Map<String, Object> event : history) {
                    if (((Number) event.get("sequence")).longValue() > since) {
                        sink.accept(event);
                    }
                }
            }
            sinks.add(sink);
            return () -> sinks.remove(sink);
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Origin", ORIGIN).GET();
        if (token != null) {
            request.header("X-WebView-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("X-WebView-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theDescriptorListIsReadableAndNamesTheEndpoint() throws Exception {
        startTool();
        start();

        HttpResponse<String> list = get("/api/tools", null);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"tool\""), list.body());
        assertTrue(list.body().contains(TOOL), list.body());
        assertTrue(list.body().contains(ToolProtocol.VERSION), list.body());
        assertTrue(Files.exists(ToolProtocol.descriptorFile(project, TOOL)), "the tool published its descriptor");
    }

    @Test
    void aCallForwardsToTheToolAndRequiresTheToken() throws Exception {
        startTool();
        start();

        HttpResponse<String> refused = post("/api/tools/" + TOOL + "/call",
                "{\"method\":\"build.rebuild\",\"params\":{}}", null);
        assertEquals(403, refused.statusCode(), "a call can change the build, so it needs the token");

        HttpResponse<String> rebuilt = post("/api/tools/" + TOOL + "/call",
                "{\"method\":\"build.rebuild\",\"params\":{}}", TOKEN);
        assertEquals(200, rebuilt.statusCode(), rebuilt.body());
        assertTrue(rebuilt.body().contains("\"accepted\": true") || rebuilt.body().contains("\"accepted\":true"),
                rebuilt.body());

        HttpResponse<String> log = post("/api/tools/" + TOOL + "/call",
                "{\"method\":\"build.log\",\"params\":{\"since\":0}}", TOKEN);
        assertEquals(200, log.statusCode(), log.body());
        assertTrue(log.body().contains("hello from the tool"), log.body());
    }

    @Test
    void anUnknownToolIs404AndAToolRefusalIs400() throws Exception {
        startTool();
        start();

        assertEquals(404, post("/api/tools/no-such-tool/call", "{\"method\":\"build.status\"}", TOKEN)
                .statusCode());
        // The call route exists, so the wrong method is a 405 and never a 404 (the host distinguishes them).
        assertEquals(405, get("/api/tools/no-such-tool/call", null).statusCode());
        HttpResponse<String> refused = post("/api/tools/" + TOOL + "/call",
                "{\"method\":\"no.such.method\",\"params\":{}}", TOKEN);
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("tool-refused"), refused.body());
        assertEquals(400, post("/api/tools/" + TOOL + "/call", "not json", TOKEN).statusCode());
    }

    @Test
    void anUnreachableToolIs502() throws Exception {
        // A descriptor whose socket nobody answers on: the state a crashed editor leaves behind.
        ToolDescriptor stale = ToolDescriptor.of(project, "stale-tool", "Stale", "JUnit", "1.0",
                project.resolve("missing.sock"), List.of("tool.info"));
        stale.write(ToolProtocol.descriptorFile(project, "stale-tool"));
        start();

        HttpResponse<String> answer = post("/api/tools/stale-tool/call", "{\"method\":\"tool.info\"}", TOKEN);
        assertEquals(502, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains("tool-unreachable"), answer.body());
    }

    @Test
    @Timeout(20)
    void theEventRouteStreamsToolEventsAsSse() throws Exception {
        startTool();
        start();
        tool.push("pushed before the stream opened");

        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl()
                        + "/api/tools/" + TOOL + "/events?topic=log&since=0&token=" + TOKEN))
                .GET().build();
        HttpResponse<InputStream> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            StringBuilder frame = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                frame.append(line).append('\n');
                if (line.isEmpty()) {
                    break;
                }
            }
            assertTrue(frame.toString().contains("event: tool-event"), frame.toString());
            assertTrue(frame.toString().contains("pushed before the stream opened"), frame.toString());
            assertTrue(frame.toString().contains("id: 1"), frame.toString());
        }
    }

    @Test
    void theEventRouteNeedsTheTokenAndAKnownTopic() throws Exception {
        startTool();
        start();

        HttpResponse<String> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.baseUrl()
                        + "/api/tools/" + TOOL + "/events?topic=log")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, anonymous.statusCode(), "an EventSource must present the token in the query");

        assertEquals(400, get("/api/tools/" + TOOL + "/events?topic=nope&token=" + TOKEN, null).statusCode());
        assertEquals(404, get("/api/tools/no-such-tool/events?token=" + TOKEN, null).statusCode());
    }

    @Test
    void thePageIsServedFromTheAppNamespace() throws Exception {
        Path app = project.resolve(".jcodebuddy").resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("incremental-build.html"),
                "<!doctype html><title>compiler</title>\n", java.nio.charset.StandardCharsets.UTF_8);
        start();

        HttpResponse<String> page = get("/app/incremental-build.html", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("window.openFile"), "an /app page is served with the bridge");
    }
}
