// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hr.hrg.webview.core.AllowedOrigins;
import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.HostHealth;
import hr.hrg.webview.core.NavigationOutcome;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.RateLimiter;
import org.eclipse.lsp4j.launch.LSPLauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Entry point for the JWA Sidecar Language Server.
 *
 * <p>Two transports, one destination. The LSP face on stdio is how an editor drives this process; the
 * jump HTTP service is how a page in a browser asks the editor to move, which is the case LSP alone
 * cannot cover (there is no language server to notify if the page is open in a separate window).
 *
 * <p><b>The HTTP surface is now authorized like every other host.</b> Before 2026-09-25 it answered
 * {@code Access-Control-Allow-Origin: *} to anyone who could reach the port, with no token, no origin
 * check, no rate limit, and no address argument at all (which binds every interface, so it was reachable
 * from the network and not only from the machine): any page in the user's browser could move their
 * editor, and the same shape would have let it write files once the edit verbs exist — see
 * {@code webview/PLAN-webview-suite.md}, decision D8. It now uses the shared {@link AllowedOrigins},
 * {@link RateLimiter} and {@link Navigator} from {@code webview-core}, binds the loopback address
 * explicitly, and applies the same closed default as the JetBrains plugin: with no token and no allowed
 * origin, every request is refused.
 */
public class SidecarApp {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The route a page calls: {@code /jump?uri=<file url>&line=<n>&column=<n>}. */
    private static final String PATH_JUMP = "/jump";
    private static final String PATH_HEALTH = "/health";

    public static void main(String[] args) {
        try {
            startServer(System.in, System.out);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public static void startServer(InputStream in, OutputStream out)
            throws InterruptedException, ExecutionException, IOException {
        JwaLanguageServer server = new JwaLanguageServer();

        // Start the Jump HTTP Service (non-fatal)
        try {
            int port = Integer.getInteger("jwa.sidecar.jumpPort", 7979);
            startJumpService(server, port, new RateLimiter(
                            Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM),
                    System.getProperty("jwa.sidecar.allowedOrigins"),
                    System.getProperty("jwa.sidecar.token", "").trim());
        } catch (Exception e) {
            System.err.println("Failed to start Jump service: " + e.getMessage());
        }

        // Use LSPLauncher.Builder to support LSP-specific types and custom client
        // interface
        var launcher = new LSPLauncher.Builder<JwaLanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(JwaLanguageClient.class)
                .setInput(in)
                .setOutput(out)
                .create();

        JwaLanguageClient client = launcher.getRemoteProxy();
        server.connect(client);

        System.err.println("JWA Sidecar starting...");

        launcher.startListening().get();
    }

    /**
     * The jump endpoint.
     *
     * <p>A caller proves itself with the token ({@code ?token=…} or the {@code X-WebView-Token} header) or
     * with an {@code Origin} the allow-list names. With neither configured nothing is authorized, which is
     * the deliberate default rather than an oversight.
     *
     * @param allowedOriginsText comma-separated origins, or null/blank for none
     * @param token              the shared secret, or blank for none
     */
    static void startJumpService(final JwaLanguageServer server, int port, final RateLimiter rateLimiter,
                                 String allowedOriginsText, String token) throws IOException {
        final AllowedOrigins allowedOrigins = AllowedOrigins.of(allowedOriginsText);
        final String configuredToken = token == null ? "" : token.trim();

        // Loopback explicitly: passing no address binds 0.0.0.0, which exposes the endpoint to the LAN.
        HttpServer httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);

        httpServer.createContext(PATH_JUMP, exchange -> {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            // CORS headers only for an origin that is actually allowed: an unauthenticated caller must
            // not receive a grant.
            if (origin != null && allowedOrigins.allows(origin)) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
            }
            try {
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());

                if (!isAuthorized(exchange, params, allowedOrigins, configuredToken)) {
                    System.err.println("Jump service: refused a request"
                            + (origin == null ? " with no Origin header" : " from origin " + origin)
                            + (configuredToken.isEmpty()
                                    ? " (no token configured)"
                                    : " (token missing or wrong)"));
                    respond(exchange, 403, MAPPER.writeValueAsBytes(Map.of(
                            "error", "Forbidden: configure jwa.sidecar.allowedOrigins or jwa.sidecar.token")));
                    return;
                }

                if (!rateLimiter.tryAcquire()) {
                    respond(exchange, 429, MAPPER.writeValueAsBytes(Map.of(
                            "error", "Too Many Requests: " + Navigator.RATE_LIMIT_COUNT
                                    + " jumps per " + (Navigator.RATE_LIMIT_WINDOW_MS / 1000) + "s")));
                    return;
                }

                String uri = params.get("uri");
                if (uri == null || uri.isBlank()) {
                    respond(exchange, 400, MAPPER.writeValueAsBytes(
                            Map.of("error", "Missing uri parameter")));
                    return;
                }
                int line = parseInt(params.get("line"), 1);
                int column = parseInt(params.get("column"), 1);

                // The outcome is reported rather than assumed: a page that asked for a file which does
                // not exist sees a 404, not a cheerful "ok".
                NavigationOutcome outcome = server.jump(uri, line, column);
                if (outcome.succeeded()) {
                    respond(exchange, 200, MAPPER.writeValueAsBytes(
                            Map.of("status", "ok", "uri", uri, "path", outcome.absolutePath())));
                    return;
                }
                System.err.println("Jump service: " + outcome.reason() + " for '" + uri + "'"
                        + (outcome.detail().isEmpty() ? "" : " (" + outcome.detail() + ")"));
                respond(exchange, outcome.reason() == NavigationOutcome.Reason.RATE_LIMITED ? 429 : 404,
                        MAPPER.writeValueAsBytes(Map.of(
                                "error", outcome.reason().name(),
                                "detail", outcome.detail())));
            } finally {
                exchange.close();
            }
        });

        httpServer.createContext(PATH_HEALTH, exchange -> {
            try {
                // The shared document, so this host answers /health with the same keys as the two IDE hosts
                // (it used to answer three of the four, and neither of the two newer ones). Sent as its own
                // bytes rather than through the mapper: writeValueAsBytes(String) would quote and escape the
                // document into a JSON string.
                //
                // The capabilities are the contract's verb keys, not this host's internal route names, and
                // they are withheld until a client has completed initialize: advertising "open" while /jump
                // can only answer NO_HOST is exactly the dead-link failure the link contract's section 4
                // forbids. This was corrected on 2026-09-25, when webviewd started deciding from this
                // document whether to route navigation here at all.
                Set<String> capabilities = server.isEditorAttached() ? Set.of("open", "select") : Set.of();
                byte[] body = HostHealth.of(HostHealth.PLUGIN_SIDECAR, port,
                        allowedOrigins.values().size(), !configuredToken.isEmpty(),
                        capabilities).toJson().getBytes(StandardCharsets.UTF_8);
                respond(exchange, 200, body);
            } finally {
                exchange.close();
            }
        });

        httpServer.setExecutor(null);
        httpServer.start();
        // Use err because out is for LSP
        System.err.println("Jump service started on http://127.0.0.1:" + port + PATH_JUMP
                + (allowedOrigins.isEmpty() && configuredToken.isEmpty()
                        ? " (denying every caller until jwa.sidecar.allowedOrigins or jwa.sidecar.token is set)"
                        : ""));
    }

    /** Token or allowed origin; with neither configured, nothing is authorized. */
    private static boolean isAuthorized(HttpExchange exchange, Map<String, String> params,
                                        AllowedOrigins allowedOrigins, String configuredToken) {
        if (!configuredToken.isEmpty()) {
            String supplied = exchange.getRequestHeaders().getFirst("X-WebView-Token");
            if (supplied == null) {
                supplied = params.get("token");
            }
            if (configuredToken.equals(supplied)) {
                return true;
            }
        }
        return allowedOrigins.allows(exchange.getRequestHeaders().getFirst("Origin"));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static int parseInt(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            params.put(name, value);
        }
        return params;
    }
}
