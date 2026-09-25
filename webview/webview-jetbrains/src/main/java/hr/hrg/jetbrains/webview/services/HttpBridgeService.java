package hr.hrg.jetbrains.webview.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hr.hrg.jetbrains.webview.bridge.AllowedOrigins;
import hr.hrg.jetbrains.webview.bridge.NavigatorService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The browser fallback: an HTTP endpoint that opens a file in the IDE when the page is <b>not</b>
 * loaded by this plugin's webview, and therefore has no injected {@code window.openFile}.
 *
 * <p>Contract, which the HTML report renderer depends on (see {@code scripts/entity-html/README.md}):
 *
 * <pre>
 *   GET http://127.0.0.1:&lt;port&gt;/open?filePath=&lt;abs path&gt;&amp;line=&lt;n&gt;&amp;column=&lt;n&gt;
 *   GET http://127.0.0.1:&lt;port&gt;/health
 * </pre>
 *
 * <p>The server is off until {@code webview.explorer.port} is configured, because a plugin should not
 * open a listening socket that nobody asked for.
 *
 * <p><b>Security.</b> The endpoint can open an arbitrary file in the IDE, so it is denied unless the
 * caller proves it may. Three changes from the first implementation, each closing a real hole:
 * <ul>
 *   <li>it binds to the loopback address, not {@code 0.0.0.0}, so it is not reachable from the LAN;</li>
 *   <li>an empty allow-list denies every caller instead of allowing every caller;</li>
 *   <li>a caller may present either an allowed {@code Origin} or the configured token, so a
 *       {@code file:} page (which browsers send no usable {@code Origin} for) still has an opt-in
 *       route that does not require weakening the Origin check.</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class HttpBridgeService {

    private static final Logger LOG = Logger.getInstance(HttpBridgeService.class);

    private static final String PATH_OPEN = "/open";
    private static final String PATH_HEALTH = "/health";

    private final Project project;
    private final AtomicInteger threadSequence = new AtomicInteger();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int boundPort = -1;
    private volatile AllowedOrigins allowedOrigins = AllowedOrigins.of(null);
    private volatile String token = "";

    public HttpBridgeService(@NotNull Project project) {
        this.project = project;
        applySettingsAndStart();
    }

    public static @NotNull HttpBridgeService getInstance(@NotNull Project project) {
        return project.getService(HttpBridgeService.class);
    }

    /** Re-reads the settings and restarts the server; called when the settings are applied. */
    public synchronized void restartServer() {
        stopServer();
        applySettingsAndStart();
    }

    /** True when the server is listening. */
    public boolean isRunning() {
        return server != null;
    }

    /** The port actually bound, or -1 when the bridge is not running. */
    public int getBoundPort() {
        return boundPort;
    }

    /** A one-line description of the bridge for the settings UI. */
    public @NotNull String describeState() {
        HttpServer current = server;
        if (current == null) {
            return "Bridge: stopped";
        }
        String auth = token.isEmpty()
                ? (allowedOrigins.isEmpty() ? "no caller allowed yet" : allowedOrigins.values().size() + " allowed origin(s)")
                : "token required";
        return "Bridge: running on 127.0.0.1:" + boundPort + " (" + auth + ")";
    }

    public synchronized void stopServer() {
        HttpServer current = server;
        if (current == null) {
            return;
        }
        try {
            current.stop(0);
            LOG.info("WebView HTTP bridge stopped");
        } catch (RuntimeException e) {
            LOG.warn("WebView HTTP bridge did not stop cleanly", e);
        } finally {
            server = null;
            boundPort = -1;
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
            executor = null;
        }
    }

    private void applySettingsAndStart() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        String portText = state != null && state.port != null
                ? String.valueOf(state.port)
                : System.getProperty("webview.explorer.port");
        String originsText = state != null && state.allowedOrigins != null && !state.allowedOrigins.isBlank()
                ? state.allowedOrigins
                : System.getProperty("webview.explorer.allowedOrigins");
        String tokenText = state != null && state.token != null && !state.token.isBlank()
                ? state.token
                : System.getProperty("webview.explorer.token");

        allowedOrigins = AllowedOrigins.of(originsText);
        token = tokenText == null ? "" : tokenText.trim();

        if (portText == null || portText.isBlank()) {
            LOG.info("WebView HTTP bridge is off (webview.explorer.port is not configured)");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException e) {
            LOG.warn("WebView HTTP bridge: '" + portText + "' is not a port number; bridge stays off");
            return;
        }
        startServer(port);
    }

    private void startServer(int port) {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            created.createContext("/", this::handle);
            ExecutorService createdExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "webview-bridge-" + threadSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(createdExecutor);
            created.start();

            server = created;
            executor = createdExecutor;
            boundPort = port;
            LOG.info("WebView HTTP bridge listening on 127.0.0.1:" + port
                    + (allowedOrigins.isEmpty() && token.isEmpty()
                            ? " (denying every caller until an allowed origin or token is configured)"
                            : ""));
        } catch (IOException e) {
            LOG.warn("WebView HTTP bridge could not bind 127.0.0.1:" + port, e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path != null) {
            path = path.replaceAll("/+", "/");
        }

        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && allowedOrigins.allows(origin)) {
            // Browsers refuse a cross-origin response without these, so they are sent for an allowed
            // origin only; an unauthenticated caller gets no CORS grant.
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        }

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (PATH_HEALTH.equals(path)) {
            String body = "{\"plugin\":\"hr.hrg.jetbrains.webview\",\"port\":" + boundPort
                    + ",\"allowedOrigins\":" + allowedOrigins.values().size()
                    + ",\"tokenRequired\":" + !token.isEmpty() + "}";
            send(exchange, 200, body, "application/json");
            return;
        }

        if (!PATH_OPEN.equals(path)) {
            send(exchange, 404, "Not Found", "text/plain");
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

        if (!isAuthorized(exchange, origin)) {
            LOG.warn("WebView HTTP bridge: refused a request"
                    + (origin == null ? " with no Origin header" : " from origin " + origin)
                    + (token.isEmpty() ? " (no token configured)" : " (token missing or wrong)"));
            send(exchange, 403, "Forbidden: configure webview.explorer.allowedOrigins or webview.explorer.token",
                    "text/plain");
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        String filePath = params.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            send(exchange, 400, "Missing filePath parameter", "text/plain");
            return;
        }
        int line = parseInt(params.get("line"), 1);
        int column = parseInt(params.get("column"), 1);

        boolean opened = NavigatorService.getInstance(project).open(filePath, line, column);
        if (opened) {
            send(exchange, 200, "Opening " + filePath + ":" + line + ":" + column, "text/plain");
        } else {
            // Either the path is not in the project or the rate limit refused it; both are "not this".
            send(exchange, 404, "Could not open " + filePath, "text/plain");
        }
    }

    /**
     * A caller is authorized when it presents the configured token, or an Origin the allow-list names.
     * With neither configured, nothing is authorized.
     */
    private boolean isAuthorized(@NotNull HttpExchange exchange, @Nullable String origin) {
        if (!token.isEmpty()) {
            String supplied = exchange.getRequestHeaders().getFirst("X-WebView-Token");
            if (supplied == null) {
                Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                supplied = params.get("token");
            }
            if (token.equals(supplied)) {
                return true;
            }
        }
        return allowedOrigins.allows(origin);
    }

    private static void send(@NotNull HttpExchange exchange, int status, @NotNull String body,
                             @NotNull String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static int parseInt(@Nullable String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static @NotNull Map<String, String> parseQuery(@Nullable String query) {
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
