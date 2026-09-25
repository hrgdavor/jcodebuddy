// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.RateLimiter;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The write half of the LSP channel: {@code workspace/applyEdit}, so a change from a page lands in the editor's
 * buffer and its undo stack instead of on disk (plan § 6.2 and Phase 3's gate (e)).
 *
 * <p>Tested at both boundaries — the message the sidecar sends, and the HTTP route that asks it to send it —
 * because the two failures it can have are different: sending the wrong ranges, and being reachable without the
 * token that a state-changing route requires.
 */
public class SidecarApplyEditTest {

    /** Records calls and answers the ones the server dereferences with a response. */
    private static final class RecordingClient implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        final List<Object> arguments = new ArrayList<>();
        boolean applied = true;
        boolean online = true;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            calls.add(method.getName());
            arguments.add(args == null || args.length == 0 ? null : args[0]);
            if ("applyEdit".equals(method.getName())) {
                return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(applied));
            }
            return null;
        }

        JwaLanguageClient asClient() {
            return (JwaLanguageClient) Proxy.newProxyInstance(
                    JwaLanguageClient.class.getClassLoader(), new Class<?>[] {JwaLanguageClient.class}, this);
        }

        ApplyWorkspaceEditParams applyEditParams() {
            for (int i = 0; i < calls.size(); i++) {
                if ("applyEdit".equals(calls.get(i)) && arguments.get(i) instanceof ApplyWorkspaceEditParams params) {
                    return params;
                }
            }
            return null;
        }
    }

    private static JwaLanguageServer connected(RecordingClient recorder) throws Exception {
        JwaLanguageServer server = new JwaLanguageServer();
        server.connect(recorder.asClient());
        InitializeParams params = new InitializeParams();
        params.setRootUri("file:///D:/wrk/project");
        server.initialize(params).get();
        return server;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static hr.hrg.webview.core.TextEdit edit(int line, int column, String text) {
        return new hr.hrg.webview.core.TextEdit(line, column, line, column + 3, text);
    }

    @Test
    public void theEditIsSentAsAWorkspaceEditWithZeroBasedRanges() throws Exception {
        RecordingClient recorder = new RecordingClient();
        JwaLanguageServer server = connected(recorder);

        boolean applied = server.applyEdit("file:///D:/wrk/project/src/A.java",
                List.of(edit(7, 1, "renamed")));

        assertTrue(applied);
        ApplyWorkspaceEditParams params = recorder.applyEditParams();
        assertNotNull("the client must receive workspace/applyEdit", params);
        WorkspaceEdit edit = params.getEdit();
        assertNotNull(edit.getDocumentChanges());
        assertEquals(1, edit.getDocumentChanges().size());
        TextDocumentEdit documentEdit = edit.getDocumentChanges().get(0).getLeft();
        assertEquals("file:///D:/wrk/project/src/A.java", documentEdit.getTextDocument().getUri());
        assertEquals("a version of null asks the client not to refuse on a version we cannot see",
                null, documentEdit.getTextDocument().getVersion());
        assertEquals(1, documentEdit.getEdits().size());
        assertEquals("line 7 column 1 becomes 6:0, and the end is exclusive",
                new org.eclipse.lsp4j.Position(6, 0), documentEdit.getEdits().get(0).getRange().getStart());
        assertEquals(new org.eclipse.lsp4j.Position(6, 3), documentEdit.getEdits().get(0).getRange().getEnd());
        assertEquals("renamed", documentEdit.getEdits().get(0).getNewText());
        assertEquals("webview", params.getLabel());
    }

    @Test
    public void aClientThatRefusesIsReportedAsNotApplied() throws Exception {
        RecordingClient recorder = new RecordingClient();
        recorder.applied = false;
        JwaLanguageServer server = connected(recorder);

        assertFalse("a refusal must fall through to the caller's own write",
                server.applyEdit("file:///D:/wrk/project/src/A.java", List.of(edit(1, 1, "x"))));
    }

    @Test
    public void withNoClientAttachedNothingIsSent() throws Exception {
        JwaLanguageServer server = new JwaLanguageServer();

        assertFalse(server.applyEdit("file:///D:/wrk/project/src/A.java", List.of(edit(1, 1, "x"))));
    }

    @Test
    public void theHttpRouteRequiresTheTokenAndThenApplies() throws Exception {
        RecordingClient recorder = new RecordingClient();
        JwaLanguageServer server = connected(recorder);
        int port = freePort();
        String token = "sidecar-edit-token";
        SidecarApp.startJumpService(server, port,
                new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM),
                "http://localhost:3000", token);

        String body = "{\"uri\":\"file:///D:/wrk/project/src/A.java\","
                + "\"edits\":[{\"startLine\":7,\"startColumn\":1,\"endLine\":7,\"endColumn\":4,"
                + "\"newText\":\"renamed\"}]}";

        HttpURLConnection refused = post(port, "/applyEdit", body, null);
        assertEquals("an Origin is not enough for a write; the token is", 403, refused.getResponseCode());
        drain(refused);

        HttpURLConnection accepted = post(port, "/applyEdit", body, token);
        assertEquals(200, accepted.getResponseCode());
        String answer = drain(accepted);
        assertTrue(answer, answer.contains("\"applied\":true"));
        assertNotNull("the accepted request is what reached the client", recorder.applyEditParams());
    }

    @Test
    public void theHealthDocumentAdvertisesTheEditCapabilityOnlyWhileAClientIsAttached() throws Exception {
        RecordingClient recorder = new RecordingClient();
        JwaLanguageServer server = connected(recorder);
        int port = freePort();
        SidecarApp.startJumpService(server, port,
                new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM),
                null, "");

        String body = drain(get(port, "/health"));

        assertTrue(body, body.contains("\"capabilities\":[\"edit\",\"open\",\"select\"]"));
    }

    private static HttpURLConnection post(int port, String path, String body, String token) throws IOException {
        HttpURLConnection connection = open(port, path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            connection.setRequestProperty("X-WebView-Token", token);
        }
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private static HttpURLConnection get(int port, String path) throws IOException {
        HttpURLConnection connection = open(port, path);
        connection.setRequestMethod("GET");
        return connection;
    }

    private static HttpURLConnection open(int port, String path) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        return connection;
    }

    private static String drain(HttpURLConnection connection) throws IOException {
        try (InputStream body = connection.getResponseCode() < 400
                ? connection.getInputStream() : connection.getErrorStream()) {
            return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
