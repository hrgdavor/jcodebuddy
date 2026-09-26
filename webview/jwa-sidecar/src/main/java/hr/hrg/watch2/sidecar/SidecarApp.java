// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hr.hrg.webview.core.AllowedOrigins;
import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.HostDescriptor;
import hr.hrg.webview.core.HostHealth;
import hr.hrg.webview.core.HostPortClaim;
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
 *
 * <p><b>Which port.</b> {@code jwa.sidecar.jumpPort} is a request, not an instruction: when something else
 * holds it, the next free port is taken ({@link HostPortClaim}), and the port actually served on is
 * published in the project's {@code .jcodebuddy/webview/host.json} as soon as the client's {@code initialize}
 * has said where the project is — a port belongs to a project, and this process does not know its project
 * until then.
 */
public class SidecarApp {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The route a page calls: {@code /jump?uri=<file url>&line=<n>&column=<n>}. */
    private static final String PATH_JUMP = "/jump";
    private static final String PATH_HEALTH = "/health";

    /**
     * The route {@code webviewd} calls to have an edit applied in the client's buffer instead of on disk:
     * {@code POST /applyEdit {uri, edits:[…]}}.
     *
     * <p>A write, so unlike {@code /jump} it requires the token rather than accepting an allowed origin: the
     * same reasoning as plan D8 for the host's own surface. It exists because the process holding the editor's
     * LSP connection is this one, and the two faces have not been folded together yet (plan question 4).
     */
    private static final String PATH_APPLY_EDIT = "/applyEdit";

    private static int number(Object value) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        throw new IllegalArgumentException("line and column must be numbers");
    }

    /**
     * The token-only rule for state-changing routes: an allowed {@code Origin} is not enough, because every page
     * in the reader's browser shares that rule.
     */
    private static boolean isAuthorizedWrite(HttpExchange exchange, AllowedOrigins allowedOrigins,
                                            String configuredToken) {
        if (configuredToken.isEmpty()) {
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("X-WebView-Token");
        if (configuredToken.equals(header)) {
            return true;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        return configuredToken.equals(params.get("token"));
    }

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

        // The port this process actually serves the jump service on: the requested one when it is free, the
        // next free one when something else holds it. Published in the project's .jcodebuddy/ once the client
        // has said where the project is — a port belongs to a project, and before initialize there is none.
        java.util.concurrent.atomic.AtomicInteger jumpPort = new java.util.concurrent.atomic.AtomicInteger(-1);
        server.setProjectRootListener(root -> publishJumpPort(root, jumpPort.get(), server.capabilities()));

        // Start the Jump HTTP Service (non-fatal)
        try {
            int port = Integer.getInteger("jwa.sidecar.jumpPort", 7979);
            int bound = startJumpService(server, port, new RateLimiter(
                            Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM),
                    System.getProperty("jwa.sidecar.allowedOrigins"),
                    System.getProperty("jwa.sidecar.token", "").trim());
            jumpPort.set(bound);
            if (bound > 0 && bound != port) {
                System.err.println("Jump service: port " + port + " was taken, serving on " + bound
                        + " instead (published in the project's .jcodebuddy/webview/host.json)");
            }
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
     * <p>The port is a request: when something else holds it the next free one is taken, and when a host that
     * already serves the same project holds it this process opens nothing — the same rule the two IDE hosts
     * follow, from the same {@link HostPortClaim}.
     *
     * @param allowedOriginsText comma-separated origins, or null/blank for none
     * @param token              the shared secret, or blank for none
     * @return the port actually served on, or -1 when no endpoint was opened
     */
    static int startJumpService(final JwaLanguageServer server, int requestedPort, final RateLimiter rateLimiter,
                                String allowedOriginsText, String token) throws IOException {
        final AllowedOrigins allowedOrigins = AllowedOrigins.of(allowedOriginsText);
        final String configuredToken = token == null ? "" : token.trim();

        // The binding happens inside the claim's attempt, so the port the claim reports as free is a port this
        // process already holds. The project root is not known yet — it arrives with the LSP initialize — so
        // this is a claim about the *machine*, not about a project: a host that is already serving this
        // project cannot be recognised here, and the port loop decides.
        java.util.concurrent.atomic.AtomicReference<HttpServer> claimed = new java.util.concurrent.atomic.AtomicReference<>();
        HostPortClaim.Attempt attempt = candidate -> {
            try {
                claimed.set(HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 0));
                return new HostPortClaim.Try.Free();
            } catch (IOException e) {
                return new HostPortClaim.Try.Taken(HostPortClaim.occupantOf(candidate));
            }
        };
        HostPortClaim.Decision decision = HostPortClaim.decide(null, requestedPort,
                HostPortClaim.DEFAULT_ATTEMPTS, attempt);
        if (decision.skipped()) {
            System.err.println("Jump service: " + decision.reason());
            return -1;
        }
        HttpServer created = claimed.get();
        if (created == null) {
            System.err.println("Jump service: no server was created for the claimed port " + decision.port());
            return -1;
        }
        // Effectively final from here: the route handlers below capture it, and the port that was claimed is
        // not a value any of them may see change.
        final HttpServer httpServer = created;
        final int port = decision.port();

        httpServer.createContext(PATH_APPLY_EDIT, exchange -> {
            try {
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                if (!isAuthorizedWrite(exchange, allowedOrigins, configuredToken)) {
                    respond(exchange, 403, MAPPER.writeValueAsBytes(Map.of(
                            "error", "Forbidden: configure jwa.sidecar.allowedOrigins or jwa.sidecar.token")));
                    return;
                }
                if (!server.isEditorAttached()) {
                    respond(exchange, 404, MAPPER.writeValueAsBytes(Map.of(
                            "error", "NO_HOST", "detail", "no LSP client has completed initialize yet")));
                    return;
                }
                Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody(), Map.class);
                Object uri = request.get("uri");
                Object rawEdits = request.get("edits");
                if (!(uri instanceof String fileUri) || !(rawEdits instanceof java.util.List<?> list)) {
                    respond(exchange, 400, MAPPER.writeValueAsBytes(Map.of(
                            "error", "the body must be {uri, edits:[{startLine,startColumn,endLine,endColumn,newText}]}")));
                    return;
                }
                java.util.List<hr.hrg.webview.core.TextEdit> edits = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) {
                        respond(exchange, 400, MAPPER.writeValueAsBytes(Map.of("error", "bad edit entry")));
                        return;
                    }
                    Object newText = map.get("newText");
                    edits.add(new hr.hrg.webview.core.TextEdit(
                            number(map.get("startLine")), number(map.get("startColumn")),
                            number(map.get("endLine")), number(map.get("endColumn")),
                            newText == null ? "" : String.valueOf(newText)));
                }
                boolean applied = server.applyEdit(fileUri, edits);
                respond(exchange, applied ? 200 : 409, MAPPER.writeValueAsBytes(Map.of(
                        "applied", applied,
                        "detail", applied ? "applied in the client's buffer"
                                : "the client refused the workspace edit")));
            } catch (Exception e) {
                respond(exchange, 400, MAPPER.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
            } finally {
                exchange.close();
            }
        });

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
                //
                // The project root arrives with the same initialize, so before it does the project key is the
                // empty string — which is how every host says "not known yet" — rather than a guess. The editor
                // name is the client's, when it told us: this process serves whichever editor attached to it,
                // and the LSP initialize carries that in clientInfo.
                Set<String> capabilities = server.capabilities();
                byte[] body = HostHealth.of(HostHealth.PLUGIN_SIDECAR, server.editorName(),
                        server.getProjectRoot(), port,
                        allowedOrigins.values().size(), !configuredToken.isEmpty(),
                        capabilities).toJson().getBytes(StandardCharsets.UTF_8);
                respond(exchange, 200, body);
            } finally {
                exchange.close();
            }
        });

        httpServer.setExecutor(null);
        httpServer.start();        // Use err because out is for LSP
        System.err.println("Jump service started on http://127.0.0.1:" + port + PATH_JUMP
                + (allowedOrigins.isEmpty() && configuredToken.isEmpty()
                        ? " (denying every caller until jwa.sidecar.allowedOrigins or jwa.sidecar.token is set)"
                        : ""));
        return port;
    }

    /**
     * Publishes the jump port in the project's {@code .jcodebuddy/webview/host.json}, so a page or a script
     * finds it without being told — the same file, in the same place, as every other host writes.
     *
     * <p>Called when the client's project root becomes known, which is later than the port is claimed: a port
     * is published in the <em>project's</em> directory, and before {@code initialize} there is no project.
     *
     * <p>The {@code ide} in the descriptor is this process's own name, and the capabilities are what it can
     * honour at that moment: before {@code initialize} completes the list is empty, which is the honest answer
     * and the same one {@code /health} gives.
     */
    static void publishJumpPort(String projectRoot, int port, Set<String> capabilities) {
        if (projectRoot == null || projectRoot.isBlank() || port <= 0) {
            return;
        }
        try {
            java.nio.file.Path project = java.nio.file.Path.of(projectRoot);
            HostDescriptor.of(project, HostHealth.PLUGIN_SIDECAR, HostHealth.IDE_SIDECAR, port, "",
                    java.util.List.copyOf(capabilities),
                    new HostDescriptor.HostDetail("lsp", true, "exact",
                            "the editor's own LSP client opens the document and places the caret"))
                    .write(project);
            System.err.println("Jump service: published port " + port + " in "
                    + HostDescriptor.fileOf(project));
        } catch (IOException | RuntimeException e) {
            // A read-only checkout still serves jumps: the descriptor is how other tools find this process,
            // not a precondition for it working.
            System.err.println("Jump service: could not publish the port for " + projectRoot + ": "
                    + e.getMessage());
        }
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
