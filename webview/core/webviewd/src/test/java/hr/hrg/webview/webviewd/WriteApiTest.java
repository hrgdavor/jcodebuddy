package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.NullHost;
import hr.hrg.webview.core.SourceDigest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write surface over a real socket: the frozen-status habits of the navigation route, applied to edits.
 *
 * <p>The three cases that matter are the plan's gate items that need no editor — a stale digest is refused with
 * no bytes changed, an applied edit is undone byte-for-byte, and an edit outside the project is refused — plus
 * the one this phase adds on top: a state-changing route is not reachable without the token (D8).
 */
class WriteApiTest {

    private static final String TOKEN = "write-token";

    @TempDir
    Path project;

    private WebviewServer server;

    private WebviewServer start() throws IOException {
        WebviewdConfig config = new WebviewdConfig(project, 0, false, "", TOKEN,
                WebviewdConfig.HostChoice.NONE, WebviewdConfig.DEFAULT_SIDECAR_PORT, "", false);
        server = WebviewServer.start(config, NullHost.INSTANCE);
        return server;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent() == null ? project : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            request.header("X-WebView-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET();
        if (token != null) {
            request.header("X-WebView-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String editBody(String filePath, String digest, boolean dryRun) {
        return "{\"filePath\":\"" + filePath + "\",\"expectedDigest\":\"" + digest + "\",\"dryRun\":" + dryRun
                + ",\"edits\":[{\"startLine\":2,\"startColumn\":1,\"endLine\":2,\"endColumn\":4,"
                + "\"newText\":\"TWO\"}]}";
    }

    @Test
    void theDefaultFlowIsProposeThenAcceptAndOnlyAcceptWrites() throws Exception {
        Path file = write("src/A.java", "one\ntwo\n");
        start();
        String digest = SourceDigest.of(Files.readAllBytes(file));

        // 1. the proposal: a diff, and no write, even though the body said nothing about dryRun
        HttpResponse<String> proposal = post("/api/v1/diff", editBody("src/A.java", digest, true), TOKEN);
        assertEquals(200, proposal.statusCode());
        assertTrue(proposal.body().contains("\"applied\": false"), proposal.body());
        assertTrue(proposal.body().contains("-two"), proposal.body());
        assertTrue(proposal.body().contains("+TWO"), proposal.body());
        assertEquals("one\ntwo\n", read(file), "a proposal is not a write");

        // 2. absent dryRun means propose: a page that forgets the flag must not be why a file changed
        HttpResponse<String> implicit = post("/api/v1/applyEdit",
                editBody("src/A.java", digest, true).replace(",\"dryRun\":true", ""), TOKEN);
        assertEquals(200, implicit.statusCode());
        assertTrue(implicit.body().contains("\"applied\": false"), implicit.body());
        assertEquals("one\ntwo\n", read(file));

        // 3. the reader accepts
        HttpResponse<String> applied = post("/api/v1/applyEdit", editBody("src/A.java", digest, false), TOKEN);
        assertEquals(200, applied.statusCode());
        assertTrue(applied.body().contains("\"applied\": true"), applied.body());
        assertEquals("one\nTWO\n", read(file), "the terminator and the rest of the file are untouched");
    }

    @Test
    void aStaleDigestIsRefusedWith409AndTheFileIsNotTouched() throws Exception {
        Path file = write("src/A.java", "one\ntwo\n");
        start();
        String staleDigest = SourceDigest.of(Files.readAllBytes(file));
        Files.writeString(file, "one\nTWO-already-different\n", StandardCharsets.UTF_8);
        byte[] bySomeoneElse = Files.readAllBytes(file);

        HttpResponse<String> response = post("/api/v1/applyEdit", editBody("src/A.java", staleDigest, false), TOKEN);

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("\"reason\": \"stale\""), response.body());
        assertTrue(response.body().contains(SourceDigest.of(bySomeoneElse)),
                "the refusal carries the current digest so the page can re-read and propose again");
        assertArrayEquals(bySomeoneElse, Files.readAllBytes(file));
    }

    @Test
    void undoRestoresTheExactBytesAndRedoBringsTheEditBack() throws Exception {
        Path file = write("src/A.java", "one\ntwo\n");
        byte[] original = Files.readAllBytes(file);
        start();

        post("/api/v1/applyEdit", editBody("src/A.java", SourceDigest.of(original), false), TOKEN);
        assertEquals("one\nTWO\n", read(file));

        HttpResponse<String> undone = post("/api/v1/undo", "{\"filePath\":\"src/A.java\"}", TOKEN);
        assertEquals(200, undone.statusCode());
        assertArrayEquals(original, Files.readAllBytes(file), "byte-for-byte, as the gate demands");

        HttpResponse<String> redone = post("/api/v1/redo", "{\"filePath\":\"src/A.java\"}", TOKEN);
        assertEquals(200, redone.statusCode());
        assertEquals("one\nTWO\n", read(file));

        // After a redo, an undo is available again: the redo put the post-edit state back on the undo stack,
        // which is what makes undo/redo a history rather than a toggle.
        assertEquals(200, post("/api/v1/undo", "{\"filePath\":\"src/A.java\"}", TOKEN).statusCode());
        assertEquals(404, post("/api/v1/undo", "{\"filePath\":\"src/missing.java\"}", TOKEN).statusCode());
    }

    @Test
    void aStateChangingRouteIsNotReachableWithoutTheToken() throws Exception {
        Path file = write("src/A.java", "one\ntwo\n");
        start();
        String digest = SourceDigest.of(Files.readAllBytes(file));

        for (String path : new String[] {"/api/v1/applyEdit", "/api/v1/diff", "/api/v1/undo", "/api/v1/redo"}) {
            HttpResponse<String> response = post(path, editBody("src/A.java", digest, false), null);
            assertEquals(403, response.statusCode(), path);
            assertTrue(response.body().contains("token is required"), response.body());
        }
        assertEquals(403, get("/api/v1/events", null).statusCode());
        assertEquals("one\ntwo\n", read(file), "a refused write changes nothing");
    }

    @Test
    void aWriteOutsideTheProjectIsRefusedAndGetOnAWriteRouteIsMethodNotAllowed() throws Exception {
        Path outside = Files.createTempFile("webview-outside", ".txt");
        Files.writeString(outside, "not yours\n", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(outside);
        start();

        HttpResponse<String> escaped = post("/api/v1/applyEdit",
                editBody(outside.toString().replace('\\', '/'), SourceDigest.of(before), false), TOKEN);

        assertEquals(403, escaped.statusCode());
        assertArrayEquals(before, Files.readAllBytes(outside));

        assertEquals(405, get("/api/v1/applyEdit", TOKEN).statusCode());
        Files.deleteIfExists(outside);
    }

    @Test
    void aMalformedBodyIsABadRequestThatNamesTheProblem() throws Exception {
        write("src/A.java", "one\ntwo\n");
        start();

        HttpResponse<String> notJson = post("/api/v1/applyEdit", "{ not json", TOKEN);
        assertEquals(400, notJson.statusCode());
        assertTrue(notJson.body().contains("invalid-edit"), notJson.body());

        HttpResponse<String> noDigest = post("/api/v1/applyEdit",
                "{\"filePath\":\"src/A.java\",\"edits\":[]}", TOKEN);
        assertEquals(400, noDigest.statusCode());
        assertTrue(noDigest.body().contains("expectedDigest"), noDigest.body());
    }

    @Test
    void theEventStreamAnswersWithSseHeadersAndStaysOpen() throws Exception {
        write("src/A.java", "one\n");
        start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/v1/events"))
                        .header("X-WebView-Token", TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream",
                response.headers().firstValue("Content-Type").orElse(""));
        response.body().close();
    }
}
