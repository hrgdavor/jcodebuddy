// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What the sidecar actually sends to the editor when a page asks for a position.
 *
 * <p>This is the regression the LSP navigation spike found (2026-09-25). {@code /jump} parsed
 * {@code ?line=&column=}, passed them to {@link JwaLanguageServer#jump}, and that method delegated to
 * {@code Navigator.openUrl(uri)} — which takes the line from a {@code #L42} fragment and defaults to 1. The
 * live symptom was a file opening at line 1 while the page had asked for line 7, and no test noticed because
 * the existing suite only ever asked the endpoint questions it answers with 403 or NO_HOST.
 *
 * <p>The test records the LSP traffic with a proxy rather than a mock, so it asserts the two messages that
 * really go out — the {@code mytool/jump} notification and the {@code window/showDocument} request — including
 * the zero-based selection a client applies.
 */
public class SidecarJumpPositionTest {

    /** Records every call the server makes on its client. */
    private static final class RecordingClient implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        final List<Object> arguments = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            calls.add(method.getName());
            arguments.add(args == null || args.length == 0 ? null : args[0]);
            return null;
        }

        JwaLanguageClient asClient() {
            return (JwaLanguageClient) Proxy.newProxyInstance(
                    JwaLanguageClient.class.getClassLoader(), new Class<?>[] {JwaLanguageClient.class}, this);
        }

        JumpParams jumpParams() {
            for (int i = 0; i < calls.size(); i++) {
                if ("jump".equals(calls.get(i)) && arguments.get(i) instanceof JumpParams params) {
                    return params;
                }
            }
            return null;
        }

        ShowDocumentParams showDocumentParams() {
            for (int i = 0; i < calls.size(); i++) {
                if ("showDocument".equals(calls.get(i)) && arguments.get(i) instanceof ShowDocumentParams params) {
                    return params;
                }
            }
            return null;
        }
    }

    /** A server with a client attached and initialize completed, which is the state a jump can land in. */
    private static JwaLanguageServer connected(RecordingClient recorder) throws Exception {
        JwaLanguageServer server = new JwaLanguageServer();
        server.connect(recorder.asClient());
        InitializeParams params = new InitializeParams();
        params.setRootUri("file:///D:/wrk/project");
        server.initialize(params).get();
        return server;
    }

    @Test
    public void thePositionFromTheJumpQueryReachesTheClient() throws Exception {
        RecordingClient recorder = new RecordingClient();
        JwaLanguageServer server = connected(recorder);

        server.jump("file:///D:/wrk/project/src/A.java", 7, 5);

        JumpParams notification = recorder.jumpParams();
        assertNotNull("the legacy notification must still go out for clients that predate showDocument",
                notification);
        assertEquals(7, notification.line);
        assertEquals(5, notification.column);

        ShowDocumentParams request = recorder.showDocumentParams();
        assertNotNull("the standard request is the one native clients act on", request);
        assertTrue(request.getTakeFocus());
        assertEquals("the selection is zero-based, so line 7 column 5 is 6:4",
                new org.eclipse.lsp4j.Position(6, 4), request.getSelection().getStart());
        assertEquals(new org.eclipse.lsp4j.Position(6, 4), request.getSelection().getEnd());
        assertEquals("file:///D:/wrk/project/src/A.java", request.getUri());
    }

    @Test
    public void aUriWithALineFragmentIsStillHonouredWhenNoPositionIsGiven() throws Exception {
        RecordingClient recorder = new RecordingClient();
        JwaLanguageServer server = connected(recorder);

        // The fragment is the only position information a caller may have; it must keep working now that an
        // explicit query parameter takes precedence over it.
        server.jump("file:///D:/wrk/project/src/A.java#L42", 1, 1);

        assertNotNull(recorder.showDocumentParams());
        assertEquals(new org.eclipse.lsp4j.Position(41, 0), recorder.showDocumentParams().getSelection().getStart());
    }

    @Test
    public void withNoClientAttachedNothingIsSentAndTheCallerIsToldWhy() throws Exception {
        JwaLanguageServer server = new JwaLanguageServer();
        RecordingClient recorder = new RecordingClient();

        var outcome = server.jump("file:///D:/wrk/project/src/A.java", 7, 5);

        assertEquals(hr.hrg.webview.core.NavigationOutcome.Reason.NO_HOST, outcome.reason());
        assertTrue(recorder.calls.isEmpty());
    }
}
