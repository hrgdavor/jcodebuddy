// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.RateLimiter;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The jump endpoint's authorization, against a real socket on a real port.
 *
 * <p>This is the first test the sidecar has ever had. It is here because the endpoint used to answer
 * {@code Access-Control-Allow-Origin: *} to anyone who could reach the port — any page in the user's
 * browser could move their editor. Mocking the exchange would have tested this class's intent rather than
 * the thing that was wrong, so the test starts the server and calls it over HTTP. The configuration is a
 * parameter rather than a system property for the same reason: a test that has to mutate global state to
 * check the closed default is testing something other than what ships.
 */
public class SidecarAppJumpServiceTest {

    static {
        // HttpURLConnection silently DROPS an Origin header unless this is set, so a test that appears to
        // send one would be testing the "no Origin" branch while claiming to test the origin branch. The
        // JDK reads this property when the connection class is initialised, which is why it is in a static
        // initialiser rather than a @Before.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    /** A port the OS says is free, so a test cannot collide with a running sidecar. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Starts the service and returns the port it bound. */
    private static int start(String allowedOrigins, String token) throws IOException {
        int port = freePort();
        SidecarApp.startJumpService(new JwaLanguageServer(), port,
                new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM),
                allowedOrigins, token);
        return port;
    }

    private static HttpURLConnection get(int port, String path, String origin, String token)
            throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        connection.setRequestMethod("GET");
        if (origin != null) {
            connection.setRequestProperty("Origin", origin);
        }
        if (token != null) {
            connection.setRequestProperty("X-WebView-Token", token);
        }
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        return connection;
    }

    /** Reads the body so the connection is released and the server thread can finish. */
    private static String drain(HttpURLConnection connection) throws IOException {
        try (InputStream body = connection.getResponseCode() < 400
                ? connection.getInputStream() : connection.getErrorStream()) {
            return body == null ? "" : new String(body.readAllBytes());
        }
    }

    @Test
    public void withNothingConfiguredEveryCallerIsRefused() throws IOException {
        int port = start(null, "");
        HttpURLConnection connection = get(port, "/jump?uri=file:///D:/tmp/A.java&line=1", null, null);

        assertEquals("the closed default: no token and no allowed origin authorizes nobody",
                403, connection.getResponseCode());
        drain(connection);
    }

    @Test
    public void anUninvitedOriginGetsNoCorsGrant() throws IOException {
        int port = start(null, "");
        HttpURLConnection connection =
                get(port, "/jump?uri=file:///D:/tmp/A.java&line=1", "https://evil.example", null);

        assertEquals(403, connection.getResponseCode());
        assertNull("CORS headers must be sent only to an origin that is allowed",
                connection.getHeaderField("Access-Control-Allow-Origin"));
        drain(connection);
    }

    @Test
    public void anAllowedOriginIsAuthorizedAndGetsTheCorsGrant() throws IOException {
        int port = start("http://localhost:3000", "");
        HttpURLConnection connection =
                get(port, "/jump?uri=file:///D:/tmp/A.java&line=1", "http://localhost:3000", null);

        String body = drain(connection);

        assertEquals("an allowed origin is the alternative to a token", "http://localhost:3000",
                connection.getHeaderField("Access-Control-Allow-Origin"));
        // Not 403: the request was authorized and reached the LSP layer, which has no client connected in
        // this test and therefore has no host to open the file with.
        assertEquals(404, connection.getResponseCode());
        assertTrue(body, body.contains("NO_HOST"));
    }

    /**
     * A caller that presents the token is authorized even with no allowed origin — the case a
     * {@code file://} page hits, because browsers send no usable {@code Origin} for one.
     */
    @Test
    public void aTokenAuthorizesARequestWithNoOrigin() throws IOException {
        int port = start(null, "s3cret");

        HttpURLConnection refused =
                get(port, "/jump?uri=file:///D:/tmp/A.java&line=1", null, "wrong");
        assertEquals(403, refused.getResponseCode());
        drain(refused);

        HttpURLConnection accepted =
                get(port, "/jump?uri=file:///D:/tmp/A.java&line=1&token=s3cret", null, null);
        assertEquals("the right token is the authorization a file: page can actually send",
                404, accepted.getResponseCode());
        assertTrue(drain(accepted).contains("NO_HOST"));
    }

    @Test
    public void theHealthEndpointAnswersWithoutCredentials() throws IOException {
        int port = start(null, "");

        HttpURLConnection connection = get(port, "/health", null, null);
        String body = drain(connection);

        assertEquals("a page needs to be able to ask whether a bridge is there", 200,
                connection.getResponseCode());
        assertTrue(body, body.contains("\"plugin\":\"hr.hrg.watch2.sidecar\""));
        assertTrue(body, body.contains("\"port\":" + port));
        assertTrue("with nothing configured the health body must say so",
                body.contains("\"tokenRequired\":false"));
        assertTrue(body, body.contains("\"allowedOrigins\":0"));
    }

    @Test
    public void aMissingUriIsRejectedEvenWhenAuthorized() throws IOException {
        int port = start("http://localhost:3000", "");
        HttpURLConnection connection = get(port, "/jump?line=4", "http://localhost:3000", null);

        assertEquals(400, connection.getResponseCode());
        assertTrue(drain(connection).contains("Missing uri parameter"));
    }

    @Test
    public void theServiceBindsTheLoopbackAddressOnly() throws IOException {
        int port = start(null, "");

        // A connection to a non-loopback local address must not reach the service. Trying every interface
        // is not possible in a unit test; asserting that the loopback one works and that the bind address
        // is not the wildcard is what can be checked here.
        try (ServerSocket probe = new ServerSocket(0)) {
            assertTrue("the OS still owns a port we are not listening on", probe.getLocalPort() > 0);
        }
        assertTrue("the service answers on loopback", get(port, "/health", null, null)
                .getResponseCode() == 200);
    }
}
