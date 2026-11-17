package hr.hrg.webview.webviewd;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The read-only metadata contract over the real HTTP server, including the report-path resolution. */
class MetadataApiTest {

    private static final String ORIGIN = "https://allowed.test";
    private static final String TOKEN = "secret";

    @TempDir
    Path project;

    private WebviewServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private void start() throws IOException {
        server = WebviewServer.start(new WebviewdConfig(project, 0, true, null, false, ORIGIN, TOKEN,
                WebviewdConfig.HostChoice.NONE, WebviewdConfig.DEFAULT_SIDECAR_PORT, "", false),
                NullHost.INSTANCE);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Origin", ORIGIN).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void metadata() throws IOException {
        Path root = project.resolve(".jcodebuddy").resolve("metadata");
        Files.createDirectories(root.resolve("browser"));
        Files.writeString(root.resolve("catalog.json"), """
                {"schemaVersion":1,"reports":[{"report":"entity-browser","manifest":"browser/manifest.json",
                "index":"browser/index.json","page":"browser/index.html"}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("browser/manifest.json"), """
                {"schemaVersion":1,"report":"entity-browser","generation":"g1","index":"index.json"}
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("browser/index.json"), """
                {"schemaVersion":1,"report":"entity-browser","generation":"g1","shards":["a","b"],
                "stats":{"records":1},"types":{}}
                """, StandardCharsets.UTF_8);
    }

    @Test
    void catalogAndIndexUseTheCatalogPathRatherThanTheReportIdDirectory() throws Exception {
        metadata();
        start();

        HttpResponse<String> catalog = get("/api/v1/metadata/catalog");
        assertEquals(200, catalog.statusCode());
        assertTrue(catalog.body().contains("entity-browser"), catalog.body());

        HttpResponse<String> index = get("/api/v1/metadata/entity-browser/index");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("\"shards\""), index.body());
    }

    @Test
    void unknownOrTraversingReportsAreRefused() throws Exception {
        metadata();
        start();

        assertEquals(404, get("/api/v1/metadata/../catalog").statusCode());
        assertEquals(404, get("/api/v1/metadata/not-there/index").statusCode());
    }
}
