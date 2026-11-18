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

/**
 * The static site namespaces and the editor API. Metadata catalog/index/SSE is the JS backend's job; a
 * webview host serves the files and the editor routes, not the metadata transport.
 */
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
        return get(path, null);
    }

    private HttpResponse<String> get(String path, String ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Origin", ORIGIN).GET();
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void metadata() throws IOException {
        Path root = project.resolve(".jcodebuddy").resolve("metadata");
        Files.createDirectories(root.resolve("browser"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve(".jcodebuddy").resolve("app"));
        Files.writeString(project.resolve(".jcodebuddy/app/index.html"), "<!doctype html><title>app</title>\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("src/A.java"), "class A {}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("lib/shared.js"), "export const x = 1\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("browser/index.html"), "<!doctype html><title>report</title>\n",
                StandardCharsets.UTF_8);
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
    void staticSiteNamespacesAreServedAndMetadataTransportIsNot() throws Exception {
        metadata();
        start();

        assertEquals(200, get("/reports/entity-browser/").statusCode());
        assertEquals(200, get("/reports/entity-browser/index.html").statusCode());
        assertEquals(200, get("/app/").statusCode());
        assertEquals(200, get("/lib/shared.js").statusCode());
        assertEquals(200, get("/source/src/A.java").statusCode());
        assertTrue(get("/source/src/A.java").body().contains("class A"), "source is served read-only");
        assertEquals(404, get("/api/v1/metadata/catalog").statusCode(),
                "metadata transport belongs to the JS project-automation server, not webviewd");
    }

    @Test
    void unknownOrTraversingReportsAreRefused() throws Exception {
        metadata();
        start();

        assertEquals(404, get("/reports/not-there/index.html").statusCode());
        assertEquals(404, get("/reports/../catalog.json").statusCode());
    }
}
