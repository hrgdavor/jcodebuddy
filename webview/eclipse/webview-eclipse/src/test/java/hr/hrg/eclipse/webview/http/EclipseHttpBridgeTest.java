package hr.hrg.eclipse.webview.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.HostConfig;
import hr.hrg.webview.core.HostDescriptor;
import hr.hrg.webview.core.HostHealth;
import hr.hrg.webview.core.RateLimiter;
import hr.hrg.webview.core.SourceDigest;

/**
 * The HTTP bridge as a running thing — no workbench needed, because the socket, the routes and the
 * claim live in {@code EclipseHttpBridge} while the editor work is handed to a recording host. This
 * is the observed half of the Phase 2 gates that can be observed without a human: what the port
 * serves, what it refuses, and what it publishes.
 *
 * <p>The UI-thread marshalling ({@code UiThreadHost}) is deliberately out of scope here: it needs a
 * display, and a fake display would be asserting nothing. The bridge takes the marshalled host as a
 * given, exactly as it takes {@code ideName} as a given.
 */
class EclipseHttpBridgeTest {

    /** The editor-host stand-in: it records what the navigator asked it to open, and says yes. */
    private static final class RecordingHost implements EditorHost {
        final List<String> opened = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "fake";
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
            return true;
        }
    }

    private final List<EclipseHttpBridge> started = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @AfterEach
    void stopEverything() {
        for (EclipseHttpBridge bridge : started) {
            bridge.close();
        }
        started.clear();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path project() throws IOException {
        Path dir = Files.createTempDirectory("jcb-eclipse-bridge");
        Files.writeString(dir.resolve("index.html"), "<html><body>hi</body></html>");
        return dir;
    }

    private EclipseHttpBridge.Outcome start(Path project, Integer port) throws IOException {
        return start(project, port, new RecordingHost());
    }

    private EclipseHttpBridge.Outcome start(Path project, Integer port, RecordingHost host)
            throws IOException {
        EclipseHttpBridge.Outcome outcome = EclipseHttpBridge.start(project, port, host,
                new RateLimiter(20, 20_000L, Clock.SYSTEM), "", "Eclipse-Test");
        if (outcome.serving()) {
            started.add(outcome.bridge());
        }
        return outcome;
    }

    private HttpResponse<String> get(EclipseHttpBridge bridge, String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(bridge.baseUrl() + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String withToken(EclipseHttpBridge bridge, String pathAndQuery) {
        char join = pathAndQuery.contains("?") ? '&' : '?';
        return pathAndQuery + join + "token="
                + URLEncoder.encode(bridge.token(), StandardCharsets.UTF_8);
    }

    private static String forwardSlashed(Path path) {
        return path.toString().replace('\\', '/');
    }

    // --- the serving case ------------------------------------------------------------------------------

    @Test
    void aNamedPortClaimsBindsAndPublishes() throws Exception {
        Path project = project();
        int port = freePort();
        EclipseHttpBridge.Outcome outcome = start(project, port);
        assertTrue(outcome.serving(), outcome.note());
        assertTrue(outcome.note().contains("listening on 127.0.0.1:" + port), outcome.note());
        EclipseHttpBridge bridge = outcome.bridge();
        assertEquals(port, bridge.port());

        HostDescriptor published = HostDescriptor.read(project);
        assertNotNull(published, "a serving bridge must publish the port it took");
        assertEquals(HostHealth.PLUGIN_ECLIPSE, published.plugin());
        assertEquals("Eclipse-Test", published.ide());
        assertEquals(port, published.port());
        assertFalse(published.sticky());
        assertEquals(List.of(EditorHost.CAP_OPEN), published.capabilities());
        assertTrue(published.tokenPath().endsWith("token"), published.tokenPath());
        assertNotNull(published.host());
        assertEquals("fake", published.host().name());

        HttpResponse<String> health = get(bridge, "/health");
        assertEquals(200, health.statusCode());
        assertEquals(HostHealth.REQUIRED_KEYS, HostHealth.keysOf(health.body()));
        JsonObject json = JsonParser.parseString(health.body()).getAsJsonObject();
        assertEquals(HostHealth.PLUGIN_ECLIPSE, json.get("plugin").getAsString());
        assertEquals(port, json.get("port").getAsInt());
        assertTrue(json.get("tokenRequired").getAsBoolean());
        assertEquals(HostHealth.normalizeProject(project.toString()),
                json.get("project").getAsString());
    }

    @Test
    void openNeedsTheTokenAndTheEditorMovesOnlyAfterOne() throws Exception {
        Path project = project();
        RecordingHost host = new RecordingHost();
        EclipseHttpBridge bridge = start(project, freePort(), host).bridge();

        HttpResponse<String> refused = get(bridge, "/open?filePath=index.html");
        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("Forbidden"), refused.body());
        assertTrue(host.opened.isEmpty(), "an uninvited caller must not move an editor");

        HttpResponse<String> allowed = get(bridge, withToken(bridge, "/open?filePath=index.html&line=7"));
        assertEquals(200, allowed.statusCode());
        assertEquals(List.of(forwardSlashed(project.resolve("index.html")) + ":7:1"), host.opened);
    }

    @Test
    void aPathEscapingTheProjectIsRefusedWith403() throws Exception {
        RecordingHost host = new RecordingHost();
        EclipseHttpBridge bridge = start(project(), freePort(), host).bridge();
        HttpResponse<String> escaped = get(bridge,
                withToken(bridge, "/open?filePath=../outside.html&line=1"));
        assertEquals(403, escaped.statusCode());
        assertTrue(escaped.body().contains("outside the project"), escaped.body());
        assertTrue(host.opened.isEmpty(), "the jail refuses before the host is asked");
    }

    @Test
    void pagesGetTheBridgeAndTheDigestOfTheBytesBeforeIt() throws Exception {
        Path project = project();
        EclipseHttpBridge bridge = start(project, freePort()).bridge();
        byte[] original = Files.readAllBytes(project.resolve("index.html"));
        String digest = SourceDigest.of(original);

        HttpResponse<String> file = get(bridge, withToken(bridge, "/file/index.html"));
        assertEquals(200, file.statusCode());
        assertEquals(new String(original, StandardCharsets.UTF_8), file.body());
        assertEquals(digest, file.headers().firstValue("X-WebView-Digest").orElse(""));
        assertEquals("\"" + digest + "\"", file.headers().firstValue("ETag").orElse(""));

        HttpResponse<String> page = get(bridge, withToken(bridge, "/page/index.html"));
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains(new String(original, StandardCharsets.UTF_8)), page.body());
        // The same script the view's injected bridge evaluates — one contract, one spelling (E12).
        assertTrue(page.body().contains("window.__jcbWebViewBridge"), page.body());
        assertTrue(page.body().contains("jcbBridge(msg)"), page.body());
        // And the digest names the file on disk, not the file plus what this host appended.
        assertEquals(digest, page.headers().firstValue("X-WebView-Digest").orElse(""));
    }

    @Test
    void unauthenticatedPageAccessIsRefused() throws Exception {
        EclipseHttpBridge bridge = start(project(), freePort()).bridge();
        assertEquals(403, get(bridge, "/page/index.html").statusCode());
        assertEquals(403, get(bridge, "/file/index.html").statusCode());
    }

    @Test
    void theManifestAndIndexSayWhatServes() throws Exception {
        EclipseHttpBridge bridge = start(project(), freePort()).bridge();
        HttpResponse<String> manifest = get(bridge, "/.well-known/webview.json");
        assertEquals(200, manifest.statusCode());
        assertTrue(manifest.body().contains(HostHealth.PLUGIN_ECLIPSE), manifest.body());
        assertTrue(manifest.body().contains("tokenPath"), manifest.body());

        HttpResponse<String> index = get(bridge, "/");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("Eclipse webview bridge"), index.body());
    }

    @Test
    void theWriteRoutesExistAndRefuseUntilPhaseThree() throws Exception {
        EclipseHttpBridge bridge = start(project(), freePort()).bridge();
        HttpResponse<String> apply = get(bridge, "/api/v1/applyEdit");
        assertEquals(404, apply.statusCode());
        assertTrue(apply.body().contains("Phase 3"), apply.body());
        assertEquals(404, get(bridge, "/api/v1/nonsense").statusCode());
    }

    @Test
    void aCorsPreflightEchoesTheSelfOriginOnly() throws Exception {
        EclipseHttpBridge bridge = start(project(), freePort()).bridge();
        HttpResponse<String> own = client.send(HttpRequest.newBuilder(URI.create(bridge.baseUrl() + "/open"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", bridge.baseUrl()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, own.statusCode());
        assertEquals(bridge.baseUrl(),
                own.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

        HttpResponse<String> foreign = client.send(HttpRequest.newBuilder(URI.create(bridge.baseUrl() + "/open"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://evil.example").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, foreign.statusCode());
    }

    @Test
    void pageUrlEncodesSegmentsAndCarriesTheToken() throws Exception {
        Path project = project();
        Files.createDirectories(project.resolve("docs in progress"));
        Files.writeString(project.resolve("docs in progress").resolve("a+b.html"), "<html></html>");
        EclipseHttpBridge bridge = start(project, freePort()).bridge();

        String url = bridge.pageUrl(project.resolve("docs in progress").resolve("a+b.html"));
        assertTrue(url.startsWith(bridge.baseUrl() + "/page/docs%20in%20progress/a%2Bb.html"), url);
        // '+' in a path means a literal plus to the page server; the token rides as a query value.
        assertTrue(url.endsWith("?token=" + URLEncoder.encode(bridge.token(), StandardCharsets.UTF_8)),
                url);
        assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // --- the declining case ----------------------------------------------------------------------------

    @Test
    void itStaysOffUntilAPortIsNamed() throws Exception {
        Path project = project();
        EclipseHttpBridge.Outcome outcome = start(project, null);
        assertFalse(outcome.serving(), "no port named, no socket opened");
        assertTrue(outcome.note().contains("stays off"), outcome.note());
        assertNull(HostDescriptor.read(project), "an off host publishes nothing");
        assertFalse(Files.exists(project.resolve(".jcodebuddy/webview/token")));
    }

    @Test
    void theCommittedDefaultTurnsTheEndpointOn() throws Exception {
        // The two halves of E17, measured (Phase 2's gate (f)): a fresh workspace with no preference
        // and no committed default is off; writing the project's own conf/webview.json turns it on.
        Path project = project();
        assertFalse(start(project, null).serving(), "fresh: off");

        int defaultPort = freePort();
        Files.createDirectories(project.resolve(HostConfig.DIR));
        Files.writeString(project.resolve(HostConfig.DIR).resolve(HostConfig.FILE_NAME),
                "{ \"port\": " + defaultPort + " }", StandardCharsets.UTF_8);

        EclipseHttpBridge.Outcome outcome = start(project, null);
        assertTrue(outcome.serving(),
                "the committed default names a port, so the bridge turns on: " + outcome.note());
        assertEquals(defaultPort, outcome.bridge().port());
    }

    @Test
    void closeLeavesTheDescriptorAndTheTokenForTheNextStart() throws Exception {
        Path project = project();
        EclipseHttpBridge bridge = start(project, freePort()).bridge();
        String tokenFromDisk = Files.readString(
                project.resolve(".jcodebuddy/webview/token"), StandardCharsets.UTF_8).trim();
        assertEquals(tokenFromDisk, bridge.token(), "the token file is the token's home (E16)");
        int port = bridge.port();
        bridge.close();
        started.clear();

        assertNotNull(HostDescriptor.read(project), "the record outlives the host (DEC-033)");

        EclipseHttpBridge.Outcome again = start(project, null);
        assertTrue(again.serving(),
                "the published current port is what a restart asks for: " + again.note());
        assertEquals(port, again.bridge().port(), "and it keeps it");
        assertEquals(tokenFromDisk, again.bridge().token(), "the token survives the restart, unchanged");
    }

    @Test
    void aPinnedPortIsHonouredAndReported() throws Exception {
        Path project = project();
        int pinned = freePort();
        HostDescriptor.of(project, HostHealth.PLUGIN_ECLIPSE, "Eclipse-Test", pinned, true,
                "", List.of(), new HostDescriptor.HostDetail("fake", true, "exact", ""))
                .write(project);

        EclipseHttpBridge.Outcome outcome = start(project, null);
        assertTrue(outcome.serving(),
                "the pinned port was free, so the pin is simply kept: " + outcome.note());
        assertTrue(outcome.note().contains("pinned"), outcome.note());
        assertEquals(pinned, outcome.bridge().port());
        assertTrue(HostDescriptor.read(project).sticky());
    }
}
