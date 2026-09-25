package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.NullHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole HTTP surface over a real socket, because the frozen contract is an HTTP contract: a test that
 * called the handler methods directly would not catch a wrong status line, a missing header or a route that
 * never registered.
 *
 * <p>Every case here is one the contract or the plan names, and the assertions are on the exact statuses and
 * bodies a page may see — the point of Phase 2 is that the reference host answers what the shipped hosts
 * answer, plus the capability document.
 */
class WebviewServerTest {

    private static final String TOKEN = "s3cret";
    private static final String ALLOWED_ORIGIN = "https://allowed.test";

    /** The adapter that records what it was asked to do, standing in for an editor. */
    private static final class RecordingHost implements EditorHost {
        final List<String> opened = new ArrayList<>();
        boolean answer = true;

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Set<String> capabilities() {
            return Set.of(CAP_OPEN);
        }

        @Override
        public boolean openFileAt(String absolutePath, int line, int column) {
            opened.add(absolutePath + ":" + line + ":" + column);
            return answer;
        }
    }

    @TempDir
    Path project;

    private WebviewServer server;

    private WebviewServer start(EditorHost host) throws IOException {
        WebviewdConfig config = new WebviewdConfig(project, 0, false, ALLOWED_ORIGIN, TOKEN,
                WebviewdConfig.HostChoice.NONE, WebviewdConfig.DEFAULT_SIDECAR_PORT, "", false);
        server = WebviewServer.start(config, host);
        return server;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private URI uri(String path) {
        return URI.create(server.baseUrl() + path);
    }

    private HttpResponse<String> get(String path, String origin, String tokenHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (tokenHeader != null) {
            request.header("X-WebView-Token", tokenHeader);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, null, null);
    }

    /** A project file both the file route and the page route can serve. */
    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent() == null ? project : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void healthCarriesTheSharedKeysAndTheCapabilitiesOfTheAttachedHost() throws Exception {
        start(new RecordingHost());

        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        String body = response.body();
        assertTrue(body.contains("\"plugin\":\"hr.hrg.webview.webviewd\""), body);
        assertTrue(body.contains("\"bridgeVersion\":1"), body);
        assertTrue(body.contains("\"tokenRequired\":true"), body);
        assertTrue(body.contains("\"capabilities\":[\"open\"]"), body);
        assertTrue(body.contains("\"port\":" + server.port()), body);
        assertEquals(3, server.origins().values().size(),
                "the host trusts its own two origins plus the configured one");
    }

    @Test
    void openRefusesACallerThatProvesNothing() throws Exception {
        start(new RecordingHost());

        HttpResponse<String> response = get("/open?filePath=src/A.java");

        assertEquals(403, response.statusCode());
        assertEquals("Forbidden: configure webview.explorer.allowedOrigins or webview.explorer.token",
                response.body());
    }

    @Test
    void openWithTheTokenReachesTheHostAtTheRequestedPosition() throws Exception {
        RecordingHost host = new RecordingHost();
        start(host);
        write("src/A.java", "class A {}");

        HttpResponse<String> response = get("/open?filePath=src/A.java&line=12&column=3&token=" + TOKEN);

        assertEquals(200, response.statusCode());
        assertEquals("Opening src/A.java:12:3", response.body());
        assertEquals(1, host.opened.size());
        assertTrue(host.opened.get(0).endsWith("src/A.java:12:3"), host.opened.get(0));
        assertTrue(host.opened.get(0).startsWith(project.toString().replace('\\', '/')),
                "the host is given a resolved, jailed absolute path");
    }

    @Test
    void anAllowedOriginIsEnoughAndIsTheOnlyCallerThatGetsCorsHeaders() throws Exception {
        start(new RecordingHost());

        HttpResponse<String> allowed = get("/open?filePath=src/A.java", ALLOWED_ORIGIN, null);
        assertEquals(200, allowed.statusCode());
        assertEquals(ALLOWED_ORIGIN, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

        HttpResponse<String> foreign = get("/open?filePath=src/A.java", "https://evil.test", null);
        assertEquals(403, foreign.statusCode());
        assertTrue(foreign.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
                "an unauthenticated caller must not be given a CORS grant");
    }

    @Test
    void aPathThatEscapesTheProjectIsRefusedAsSuch() throws Exception {
        start(new RecordingHost());

        HttpResponse<String> response = get("/open?filePath=" + java.net.URLEncoder.encode(
                "../../Windows/win.ini", StandardCharsets.UTF_8) + "&token=" + TOKEN);

        assertEquals(403, response.statusCode());
        assertTrue(response.body().startsWith("Forbidden: '"), response.body());
        assertTrue(response.body().endsWith("' is outside the project"), response.body());
    }

    @Test
    void aRequestThatNamesNoFileIsBadRequestAndAForeignMethodIsRefused() throws Exception {
        start(new RecordingHost());

        assertEquals(400, get("/open?token=" + TOKEN).statusCode());
        assertEquals("Missing filePath parameter", get("/open?token=" + TOKEN).body());

        HttpResponse<String> posted = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/open")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, posted.statusCode());
        assertEquals("Method Not Allowed", posted.body());
    }

    @Test
    void withNoEditorAttachedOpenIsHonestAboutIt() throws Exception {
        start(NullHost.INSTANCE);

        HttpResponse<String> health = get("/health");
        assertTrue(health.body().contains("\"capabilities\":[]"),
                "a headless host advertises nothing rather than advertising a verb it cannot run");

        HttpResponse<String> open = get("/open?filePath=src/A.java&token=" + TOKEN);
        assertEquals(404, open.statusCode());
        assertEquals("Could not open src/A.java", open.body());
    }

    @Test
    void theFileRouteServesInsideTheProjectAndRefusesOutsideIt() throws Exception {
        start(new RecordingHost());
        Path inside = write("src/A.java", "class A {}");

        HttpResponse<String> ok = get("/file/" + inside.toString().replace('\\', '/') + "?token=" + TOKEN);
        assertEquals(200, ok.statusCode());
        assertEquals("class A {}", ok.body());

        String outside = Path.of(System.getProperty("java.io.tmpdir")).resolve("outside.txt")
                .toString().replace('\\', '/');
        HttpResponse<String> forbidden = get("/file/" + outside + "?token=" + TOKEN);
        assertEquals(403, forbidden.statusCode());

        HttpResponse<String> missing = get("/file/" + project.toString().replace('\\', '/')
                + "/src/Missing.java?token=" + TOKEN);
        assertEquals(404, missing.statusCode());

        HttpResponse<String> unauthorized = get("/file/" + inside.toString().replace('\\', '/'));
        assertEquals(403, unauthorized.statusCode());
    }

    @Test
    void thePageRouteIsTheFileRoutePlusTheBridge() throws Exception {
        start(new RecordingHost());
        Path page = write("pages/report.html", "<!doctype html><html><body>report</body></html>");

        HttpResponse<String> response = get("/page/" + page.toString().replace('\\', '/') + "?token=" + TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("report"), "the page itself is still served");
        assertTrue(response.body().contains("window.openFile"), response.body());
        assertTrue(response.body().contains("window.__jcbWebViewBridge = 1"), response.body());
        assertTrue(response.body().contains("'/open?filePath='"),
                "the injected transport calls this host's own frozen route");
    }

    @Test
    void theRateLimitIsSharedAndAnswersTooManyRequests() throws Exception {
        start(new RecordingHost());
        write("src/A.java", "class A {}");

        int last = 0;
        int refused = 0;
        for (int i = 0; i < 22; i++) {
            HttpResponse<String> response = get("/open?filePath=src/A.java&token=" + TOKEN);
            last = response.statusCode();
            if (response.statusCode() == 429) {
                refused++;
                assertTrue(response.body().startsWith("Too Many Requests: "), response.body());
            }
        }
        assertEquals(429, last, "the 20-per-20s limit applies to the standalone host too");
        assertEquals(2, refused, "22 requests, 20 allowed");
    }

    @Test
    void theManifestSaysHowPreciselyThisHostCanReachALine() throws Exception {
        start(ZedCliHost.using("C:/zed/Zed.exe", command -> true));

        String zed = get(WebviewServer.MANIFEST_ROUTE).body();
        assertTrue(zed.contains("\"name\": \"zed-cli\""), zed);
        assertTrue(zed.contains("\"file-only\""), zed);
        assertTrue(zed.contains("\"tokenPath\""), zed);
        assertTrue(zed.contains("\"port\": " + server.port()), zed);

        server.close();
        start(NullHost.INSTANCE);
        String none = get(WebviewServer.MANIFEST_ROUTE).body();
        assertTrue(none.contains("\"none\""), none);
        assertFalse(none.contains("file-only"), none);
    }

    @Test
    void theDescriptorMirrorsWhatWasActuallyBound() throws Exception {
        start(new RecordingHost());

        HostDescriptor descriptor = server.descriptor();

        assertEquals(server.port(), descriptor.port());
        assertTrue(descriptor.port() > 0, "an ephemeral port is resolved before it is published");
        assertEquals(List.of("open"), descriptor.capabilities());
        assertEquals("recording", descriptor.host().name());
        assertNotNull(descriptor.tokenPathAsPath(project));
        assertFalse(descriptor.toJson().contains(TOKEN), "the descriptor never carries the secret");
    }

    @Test
    void anLspHostIsReportedAsExactAndSurvivesTheRoundTripThroughHealth() throws Exception {
        // The spike's claim in one test: when navigation goes over LSP, the host advertises open AND says the
        // caret is placed exactly, because Phase 0 measured Zed honouring the selection.
        start(LspHost.discover(FakeSidecar.withEditorAttached()));

        String health = get("/health").body();
        assertTrue(health.contains("\"capabilities\":[\"open\",\"select\"]"), health);

        String manifest = get(WebviewServer.MANIFEST_ROUTE).body();
        assertTrue(manifest.contains("\"name\": \"lsp\""), manifest);
        assertTrue(manifest.contains("\"lineNavigation\": \"exact\""), manifest);
        assertFalse(manifest.contains("file-only"), "the LSP route does not have the CLI's limitation");
    }

    @Test
    void anUnattachedLspHostIsReportedAsUnavailableRatherThanAsAnOpenVerb() throws Exception {
        FakeSidecar unattached = new FakeSidecar();
        unattached.healthBody = FakeSidecar.health("");
        start(LspHost.discover(unattached));

        assertTrue(get("/health").body().contains("\"capabilities\":[]"));
        String manifest = get(WebviewServer.MANIFEST_ROUTE).body();
        assertTrue(manifest.contains("\"lineNavigation\": \"none\""), manifest);
        assertEquals(404, get("/open?filePath=src/A.java&token=" + TOKEN).statusCode());
    }

    @Test
    void aPreflightForTheTokenHeaderIsAnswered() throws Exception {
        start(new RecordingHost());

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/open"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Headers", "X-WebView-Token")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(204, response.statusCode());
        assertEquals(ALLOWED_ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                .contains("X-WebView-Token"));
    }
}
