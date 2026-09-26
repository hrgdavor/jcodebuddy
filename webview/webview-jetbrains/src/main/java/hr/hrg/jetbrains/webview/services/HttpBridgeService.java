package hr.hrg.jetbrains.webview.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hr.hrg.jetbrains.webview.bridge.NavigatorService;
import hr.hrg.webview.core.AllowedOrigins;
import hr.hrg.webview.core.CheckpointStore;
import hr.hrg.webview.core.EditService;
import hr.hrg.webview.core.HostConfig;
import hr.hrg.webview.core.HostDescriptor;
import hr.hrg.webview.core.HostHealth;
import hr.hrg.webview.core.HostPortClaim;
import hr.hrg.webview.core.NavigationOutcome;
import hr.hrg.webview.core.WriteSurface;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><b>Which port, and whether to open one at all.</b> The configured port is a request, not an
 * instruction. Three things can be true of it, and {@link HostPortClaim} decides between them: it is free,
 * so it is used; it is held by something unrelated — another application, or a bridge for a <em>different</em>
 * project — so the next free port is taken; or it is held by a bridge that already serves <b>this</b>
 * project, in which case this IDE opens nothing. The last case is ordinary rather than exotic: two IDEs can
 * be open on one project, and a page that finds two bridges for one project has no rule for choosing, while
 * an edit that lands in the other IDE's buffer is worse than no bridge at all.
 *
 * <p>Whichever port is taken is published in {@code <project>/.jcodebuddy/webview/host.json}, beside the
 * port the other hosts publish, so a script — or a second editor — finds it without being told.
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

    /** The write contract's routes; see {@code doc/webview-edit-api.md}. */
    private static final String WRITE_PREFIX = "/api/v1/";

    private final Project project;
    private final AtomicInteger threadSequence = new AtomicInteger();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int boundPort = -1;
    private volatile AllowedOrigins allowedOrigins = AllowedOrigins.of(null);
    private volatile String token = "";
    private volatile WriteSurface writeSurface;

    /** Why the bridge is in the state it is in, for the settings UI: the claim's own sentence. */
    private volatile String stateNote = "Bridge: not started yet";

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
            return "Bridge: stopped — " + stateNote;
        }
        String auth = token.isEmpty()
                ? (allowedOrigins.isEmpty() ? "no caller allowed yet" : allowedOrigins.values().size() + " allowed origin(s)")
                : "token required";
        return "Bridge: running on 127.0.0.1:" + boundPort + " (" + auth + ") — " + stateNote;
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
        // The published record is left in place on purpose: it is the project's port, not this process's —
        // what the next start asks for, and where a `sticky` pin lives (HostDescriptor). "Is a bridge there?"
        // is answered by probing the port, so a record left by a stopped host misleads nobody.
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

        int port;
        String basePath = project.getBasePath();
        Integer explicitPort = null;
        if (portText != null && !portText.isBlank()) {
            try {
                explicitPort = Integer.parseInt(portText.trim());
            } catch (NumberFormatException e) {
                stateNote = "'" + portText + "' is not a port number; the bridge stays off";
                LOG.warn("WebView HTTP bridge: '" + portText + "' is not a port number; bridge stays off");
                return;
            }
        }
        // One resolution for every host: the setting the user made, else the port this checkout is currently
        // on (`.jcodebuddy/webview/host.json`, local and never in git), else the project's committed default
        // (`conf/webview.json` — optional, and there so a fresh clone needs no IDE configuration). The
        // fallback is -1: this host stays off until something names a port, which is its documented behaviour
        // and not an oversight.
        HostConfig.RequestedPort resolved = basePath == null
                ? new HostConfig.RequestedPort(-1, "fallback", "")
                : HostConfig.requestedPort(Path.of(basePath), explicitPort, -1);
        if (!resolved.problem().isEmpty()) {
            LOG.info("WebView HTTP bridge: " + resolved.problem());
        }
        if (!resolved.configured()) {
            stateNote = "the bridge is off (neither webview.explorer.port, nor this checkout's "
                    + HostDescriptor.FILE_NAME + ", nor " + HostConfig.DIR + "/" + HostConfig.FILE_NAME
                    + " names a port)";
            LOG.info("WebView HTTP bridge is off (webview.explorer.port is not configured)");
            return;
        }
        port = resolved.port();
        stateNote = resolved.describe(Path.of(basePath));
        if (!stateNote.isEmpty()) {
            LOG.info("WebView HTTP bridge: " + stateNote);
        }
        startServer(port);
    }

    /**
     * Takes a port for this project, or declines to open an endpoint at all.
     *
     * <p>The binding happens inside the claim's attempt, and the server it created is handed back out of it:
     * a "check whether the port is free, then bind it" design leaves a window in which another process takes
     * the port between the two, and the port this method reports as free is then not the port it serves on.
     *
     * <p>Three outcomes that are not "serving on the port you configured" are all handled here: the next free
     * port when something unrelated holds it; nothing at all when another IDE already serves this project;
     * and an <b>error</b> when the project's port is pinned ({@code sticky} in its {@code host.json}) and could
     * not be taken — a pinned port is a choice, and quietly moving would be a lie about it.
     */
    private void startServer(int requestedPort) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            stateNote = "the project has no base path, so there is nowhere to publish a port";
            LOG.info("WebView HTTP bridge is off: the project has no base path");
            return;
        }
        Path projectRoot = Path.of(basePath);

        // The pin is a local property of the port this checkout uses, so it is read from the published state
        // and never from configuration. `null` means "this project has not pinned anything".
        HostDescriptor published = HostDescriptor.read(projectRoot);
        boolean sticky = published != null && published.sticky();
        Integer stickyPort = sticky && published.port() > 0 ? published.port() : null;

        // The server created by the attempt that succeeded. A bind failure on a taken port is classified by
        // asking the occupant what it is: "the port is busy" is not "a bridge is there".
        AtomicReference<HttpServer> claimed = new AtomicReference<>();
        HostPortClaim.Attempt attempt = port -> {
            try {
                claimed.set(HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0));
                return new HostPortClaim.Try.Free();
            } catch (IOException e) {
                return new HostPortClaim.Try.Taken(HostPortClaim.occupantOf(port));
            }
        };

        HostPortClaim.Decision decision;
        try {
            decision = HostPortClaim.claim(projectRoot, requestedPort, stickyPort,
                    HostPortClaim.DEFAULT_ATTEMPTS, attempt, HostPortClaim::occupantOf);
        } catch (RuntimeException e) {
            stateNote = "could not decide which port to use: " + e.getMessage();
            LOG.warn("WebView HTTP bridge: could not decide which port to use", e);
            return;
        }

        if (decision.failed()) {
            stateNote = decision.reason();
            LOG.warn("WebView HTTP bridge is not started: " + decision.reason());
            notifyError(decision.reason());
            return;
        }
        if (decision.skipped()) {
            stateNote = decision.reason();
            LOG.info("WebView HTTP bridge is not started: " + decision.reason());
            return;
        }

        HttpServer created = claimed.get();
        if (created == null) {
            stateNote = "no server was created for the claimed port " + decision.port();
            LOG.warn("WebView HTTP bridge: " + stateNote);
            return;
        }
        stateNote = decision.reason();
        if (decision.moved() || decision.sticky()) {
            LOG.info("WebView HTTP bridge: " + decision.reason());
        }
        start(created, decision.port(), projectRoot, sticky);
    }

    /**
     * Tells the reader that a pinned port could not be taken.
     *
     * <p>A dialog is the right weight for this and only this failure: the user pinned a port on purpose, the
     * bridge they expected is therefore <em>absent</em>, and a log line in an IDE's idea.log is not something
     * anyone reads while wondering why a page's links do nothing. It is guarded so a headless or already
     * disposed project simply records the sentence instead of throwing inside a service constructor.
     */
    private void notifyError(@NotNull String message) {
        try {
            if (ApplicationManager.getApplication().isHeadlessEnvironment() || project.isDisposed()) {
                return;
            }
            Messages.showErrorDialog(project, message, "WebView Explorer");
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("WebView HTTP bridge: could not show the error dialog", e);
        }
    }

    /** Registers the routes, starts the claimed server and publishes the port. */
    private void start(@NotNull HttpServer created, int port, @NotNull Path projectRoot, boolean sticky) {
        try {
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
            publish(projectRoot, port, sticky);
            LOG.info("WebView HTTP bridge listening on 127.0.0.1:" + port
                    + (sticky ? " (pinned for this project)" : "")
                    + (allowedOrigins.isEmpty() && token.isEmpty()
                            ? " (denying every caller until an allowed origin or token is configured)"
                            : ""));
        } catch (RuntimeException e) {
            stateNote = "could not start on port " + port + ": " + e.getMessage();
            LOG.warn("WebView HTTP bridge could not start on port " + port, e);
        }
    }

    /**
     * Publishes the port in the project's {@code .jcodebuddy/webview/host.json}, beside the port the other
     * hosts publish, so a page, a script or a second editor finds it without being told.
     *
     * <p>{@code sticky} is carried through unchanged: this IDE did not decide whether the port is pinned, and
     * it must not silently unpin one that was.
     */
    private void publish(@NotNull Path projectRoot, int port, boolean sticky) {
        NavigatorService host = NavigatorService.getInstance(project);
        HostDescriptor.HostDetail detail = new HostDescriptor.HostDetail(host.name(), host.isAvailable(),
                host.lineNavigation(), host.lineNavigationNote());
        try {
            HostDescriptor.of(projectRoot, HostHealth.PLUGIN_JETBRAINS, ideName(), port, sticky, "",
                    new ArrayList<>(host.capabilities()), detail).write(projectRoot);
        } catch (IOException | RuntimeException e) {
            // A read-only checkout still serves pages: the descriptor is how other tools find this one, not a
            // precondition for it working.
            LOG.info("WebView HTTP bridge: could not publish the port in " + projectRoot + ": " + e.getMessage());
        }
    }

    /**
     * The project's own directory, as every document in this product spells it, or blank when the project has
     * none (the default project of a window with no folder open).
     */
    private @NotNull String projectPath() {
        String basePath = project.getBasePath();
        return basePath == null ? "" : basePath;
    }

    /**
     * The human name of the IDE this plugin is running in — "IntelliJ IDEA", "PyCharm", "RustRover".
     *
     * <p>Read from the platform rather than hard-coded, because the same plugin runs in all of them and a page
     * that says "which editor is serving this bridge" has to name the one the reader is actually in. The
     * fallback covers an environment that cannot answer the question, where an honest generic name beats a
     * crash in a service constructor.
     */
    private static @NotNull String ideName() {
        try {
            String name = ApplicationNamesInfo.getInstance().getFullProductName();
            return name == null || name.isBlank() ? HostHealth.IDE_JETBRAINS : name;
        } catch (RuntimeException | LinkageError e) {
            return HostHealth.IDE_JETBRAINS;
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
            // Built by the shared HostHealth rather than assembled here: the three hosts used to answer with
            // three different documents, and a page could not tell which keys it would get. `ide` and
            // `project` are what let a second host for this project recognise this one and decline to open a
            // bridge of its own.
            String body = HostHealth.of(HostHealth.PLUGIN_JETBRAINS, ideName(), projectPath(), boundPort,
                    allowedOrigins.values().size(), !token.isEmpty(),
                    NavigatorService.getInstance(project).capabilities()).toJson();
            send(exchange, 200, body, "application/json");
            return;
        }

        if (path != null && path.startsWith(WRITE_PREFIX)) {
            handleWrite(exchange, path);
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

        // Both transports go through the NavigatorService's shared funnel, so the rate limit is acquired
        // exactly once per request whichever way it arrived, and the reason a request failed is available
        // instead of being flattened into one 404.
        NavigationOutcome outcome = NavigatorService.getInstance(project).navigator()
                .open(filePath, line, column);
        if (outcome.succeeded()) {
            send(exchange, 200, "Opening " + filePath + ":" + line + ":" + column, "text/plain");
            return;
        }
        LOG.warn("WebView HTTP bridge: " + outcome.reason() + " for '" + filePath + "'"
                + (outcome.detail().isEmpty() ? "" : " (" + outcome.detail() + ")"));
        switch (outcome.reason()) {
            case RATE_LIMITED -> send(exchange, 429,
                    "Too Many Requests: " + outcome.detail(), "text/plain");
            case OUTSIDE_PROJECT -> send(exchange, 403,
                    "Forbidden: '" + filePath + "' is outside the project", "text/plain");
            case INVALID_PATH -> send(exchange, 400, "Missing filePath parameter", "text/plain");
            default -> send(exchange, 404, "Could not open " + filePath, "text/plain");
        }
    }

    /**
     * The write routes ({@code /api/v1/applyEdit|diff|undo|redo}).
     *
     * <p>Two rules differ from {@code /open} on purpose. The method is {@code POST}, because these routes change
     * something. And the caller must present the **token** — an allowed {@code Origin} is not enough — for the
     * reason plan D8 gives: any page in the reader's browser shares the origin rule, while a page served by this
     * bridge can hold the secret.
     *
     * <p>The routing itself (buffer or disk, the digest guard, the statuses) is core's {@link WriteSurface},
     * shared with the standalone host, so the two hosts cannot drift into answering differently.
     */
    private void handleWrite(@NotNull HttpExchange exchange, @NotNull String path) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        if (!isAuthorizedWrite(exchange)) {
            LOG.warn("WebView HTTP bridge: refused a write to " + path
                    + (token.isEmpty() ? " (no token configured)" : " (token missing or wrong)"));
            send(exchange, 403, "Forbidden: the token is required for state-changing routes", "text/plain");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        WriteSurface surface = writeSurface();
        WriteSurface.Answer answer = switch (path) {
            case "/api/v1/applyEdit" -> surface.applyEdit(body);
            case "/api/v1/diff" -> surface.diff(body);
            case "/api/v1/undo" -> surface.undo(body);
            case "/api/v1/redo" -> surface.redo(body);
            default -> null;
        };
        if (answer == null) {
            send(exchange, 404, "Not Found", "text/plain");
            return;
        }
        if (!answer.ok()) {
            LOG.warn("WebView HTTP bridge: " + path + " refused with " + answer.status());
        }
        send(exchange, answer.status(), answer.body(), "application/json");
    }

    /**
     * The write surface for this project, built once: core's {@link EditService} over the project root, sharing
     * the bridge's own rate limiter with navigation, and driving {@link NavigatorService} as the editor host so a
     * page's edit lands in the IDE's buffer when it can.
     */
    private @NotNull WriteSurface writeSurface() {
        WriteSurface current = writeSurface;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (writeSurface == null) {
                NavigatorService hosts = NavigatorService.getInstance(project);
                EditService edits = new EditService(project.getBasePath(),
                        new CheckpointStore(), hosts.rateLimiter());
                writeSurface = new WriteSurface(edits, hosts);
            }
            return writeSurface;
        }
    }

    /** The token-only rule for a state-changing route. */
    private boolean isAuthorizedWrite(@NotNull HttpExchange exchange) {
        if (token.isEmpty()) {
            return false;
        }
        String supplied = exchange.getRequestHeaders().getFirst("X-WebView-Token");
        if (supplied == null) {
            supplied = parseQuery(exchange.getRequestURI().getQuery()).get("token");
        }
        return token.equals(supplied);
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
