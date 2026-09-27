package hr.hrg.eclipse.webview.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import hr.hrg.webview.core.AllowedOrigins;
import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.HostHealth;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.RateLimiter;

/**
 * The Eclipse host checked against the shared decision tables in
 * {@code webview/conformance/bridge-decisions.json} — the same file the core's Java test and the VS
 * Code Node test read, and the reason "a host that disagrees is wrong, not different" is enforceable
 * rather than aspirational.
 *
 * <p>Each vector is asserted against <b>this host's own seam</b>, not against a reimplementation of
 * it: the CORS table against the decision function the routes call, the rate table against a live
 * socket answering twenty-one navigations, the key table against the document this bridge serves.
 * A host that passes its own unit tests but disagrees here has a bug this test exists to find.
 */
class EclipseConformanceVectorsTest {

    /** Walks up from the working directory until it finds the vector file. */
    private static Path vectorsFile() {
        Path directory = Paths.get("").toAbsolutePath();
        List<Path> candidates = new ArrayList<>();
        for (Path current = directory; current != null; current = current.getParent()) {
            candidates.add(current.resolve("webview/conformance/bridge-decisions.json"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("webview/conformance/bridge-decisions.json not found above " + directory
                + "; this test must run from inside the repository");
    }

    private static JsonObject vectors() throws IOException {
        return JsonParser.parseString(Files.readString(vectorsFile(), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static String textOrNull(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    // --- origins and CORS, against this host's own decision ----------------------------------------------------------------

    @Test
    void theOriginsTableDecidesTheSameHereAsEverywhere() throws IOException {
        List<String> failures = new ArrayList<>();
        for (JsonElement value : vectors().getAsJsonArray("allowedOrigins")) {
            JsonObject vector = value.getAsJsonObject();
            String allowed = vector.get("allowed").getAsString();
            String origin = textOrNull(vector, "origin");
            boolean expected = vector.get("expected").getAsBoolean();
            boolean actual = AllowedOrigins.of(allowed).allows(origin);
            if (actual != expected) {
                failures.add("allowed=\"" + allowed + "\" origin=" + origin
                        + " expected=" + expected + " actual=" + actual);
            }
        }
        if (!failures.isEmpty()) {
            fail("this host disagrees with the shared origin table:\n  " + String.join("\n  ", failures));
        }
    }

    @Test
    void theCorsTableMatchesTheDecisionTheRoutesUse() throws IOException {
        List<String> failures = new ArrayList<>();
        for (JsonElement value : vectors().getAsJsonArray("cors")) {
            JsonObject vector = value.getAsJsonObject();
            AllowedOrigins origins = AllowedOrigins.of(vector.get("allowed").getAsString());
            String origin = textOrNull(vector, "origin");
            boolean sends = vector.get("sends").getAsBoolean();
            String echo = textOrNull(vector, "echoes");
            String actual = EclipseHttpBridge.corsEchoFor(origins, origin);
            if (sends != (actual != null)) {
                failures.add("sends disagrees for allowed=\"" + vector.get("allowed").getAsString()
                        + "\" origin=" + origin + " actual=" + actual);
            }
            if (sends && !origin.equals(actual)) {
                failures.add("the allowed origin must be echoed verbatim, got " + actual);
            }
            if (!sends && actual != null) {
                failures.add("a refused origin must get no header, got " + actual);
            }
            if (echo != null && !echo.equals(actual)) {
                failures.add("expected echo " + echo + ", got " + actual);
            }
        }
        if (!failures.isEmpty()) {
            fail("this host disagrees with the shared CORS table:\n  " + String.join("\n  ", failures));
        }
    }

    // --- the health document, as this bridge actually answers it -------------------------------------------------------------

    @Test
    void theServedHealthDocumentCarriesTheSharedKeyListInOrder() throws Exception {
        JsonArray keys = vectors().getAsJsonObject("healthKeys").getAsJsonArray("keys");
        List<String> expected = new ArrayList<>();
        for (JsonElement key : keys) {
            expected.add(key.getAsString());
        }
        assertEquals(HostHealth.REQUIRED_KEYS, expected,
                "the shared list and the core's REQUIRED_KEYS must agree before the host can");

        BridgeFixture fixture = BridgeFixture.open();
        try {
            HttpResponse<String> health = fixture.get("/health");
            assertEquals(200, health.statusCode());
            assertEquals(expected, HostHealth.keysOf(health.body()),
                    "the /health document this host serves must have the shared keys in the shared order");
        } finally {
            fixture.close();
        }
    }

    // --- the production rate limit, spent through a live socket -------------------------------------------------------------

    @Test
    void theTwentyFirstNavigationInTwentySecondsIsRefusedByThisHost() throws IOException {
        JsonObject production = null;
        for (JsonElement value : vectors().getAsJsonArray("rateLimit")) {
            JsonObject vector = value.getAsJsonObject();
            if (vector.get("limit").getAsInt() == Navigator.RATE_LIMIT_COUNT
                    && vector.get("windowMillis").getAsLong() == Navigator.RATE_LIMIT_WINDOW_MS) {
                production = vector;
                break;
            }
        }
        assertTrue(production != null,
                "the shared table must carry the production policy, or this host can silently drift from it");

        BridgeFixture fixture = BridgeFixture.open();
        try {
            List<Boolean> actual = new ArrayList<>();
            for (JsonElement ignored : production.getAsJsonArray("tryAt")) {
                int status = fixture.get("/open?filePath=index.html&line=1"
                        + "&token=" + fixture.tokenValue()).statusCode();
                actual.add(status == 200);
            }
            List<Boolean> expected = new ArrayList<>();
            for (JsonElement value : production.getAsJsonArray("expected")) {
                expected.add(value.getAsBoolean());
            }
            assertEquals(expected, actual,
                    "this host disagrees with the shared production rate table (429 is refusal)");
        } finally {
            fixture.close();
        }
    }

    // --- the fixture: one real bridge, one budget, no UI ------------------------------------------------------------------

    /**
     * A live {@link EclipseHttpBridge} on an ephemeral-but-named port, with an editor host that
     * records. The rate limiter is built from the same constants the plugin's shared one uses, so
     * the burst test spends a real budget through real HTTP.
     */
    private static final class BridgeFixture implements AutoCloseable {

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

        private final EclipseHttpBridge bridge;
        private final RecordingHost host;
        private final Path project;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        private BridgeFixture(EclipseHttpBridge bridge, RecordingHost host, Path project) {
            this.bridge = bridge;
            this.host = host;
            this.project = project;
        }

        static BridgeFixture open() throws IOException {
            Path project = Files.createTempDirectory("jcb-eclipse-conformance");
            Files.writeString(project.resolve("index.html"), "<html><body>hi</body></html>");
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            RecordingHost host = new RecordingHost();
            EclipseHttpBridge.Outcome outcome = EclipseHttpBridge.start(project, port, host,
                    new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS,
                            Clock.SYSTEM),
                    "", "Eclipse-Test");
            if (!outcome.serving()) {
                throw new IOException("the fixture bridge refused to start: " + outcome.note());
            }
            return new BridgeFixture(outcome.bridge(), host, project);
        }

        HttpResponse<String> get(String pathAndQuery) {
            try {
                return client.send(HttpRequest.newBuilder(URI.create(bridge.baseUrl() + pathAndQuery))
                        .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while reading from the bridge", e);
            } catch (IOException e) {
                throw new IllegalStateException("the bridge did not answer " + pathAndQuery, e);
            }
        }

        String tokenValue() {
            return URLEncoder.encode(bridge.token(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            bridge.close();
        }
    }
}
