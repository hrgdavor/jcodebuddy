package hr.hrg.jetbrains.webview.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import hr.hrg.webview.core.PageServer;
import hr.hrg.webview.core.WriteSurface;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /** The write contract's routes; see {@code doc/edit-api.md}. */
    private static final String WRITE_PREFIX = "/api/v1/";

    private final Project project;
    private final AtomicInteger threadSequence = new AtomicInteger();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int boundPort = -1;
    private volatile AllowedOrigins allowedOrigins = AllowedOrigins.of(null);
    private volatile String configuredOriginsText = "";
    private volatile String token = "";
    private volatile WriteSurface writeSurface;
    /** The project-jailed file decision used by {@code /page/}; built when a server starts. */
    private volatile PageServer pageServer;
    /** The one-time path segment that makes a served page and its assets a capability URL. */
    private volatile String pageToken = "";
    /** Project-automation server to proxy /app, /source, /lib, /reports and metadata reads to, or null. */
    private volatile String customServerBase;

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

    /**
     * The URL a generated page should be opened at, or null when it cannot be served.
     *
     * <p>A page is served by an **existing** host when the project setting {@code pageBaseUrl} names one —
     * the base is the path mapping, and the page's project-relative path is appended. Otherwise this IDE
     * starts its own server on demand (an ephemeral port when no bridge port is configured) and serves the
     * page under {@code /page/<one-time-token>/…}, so the page and its sibling assets load over HTTP and
     * the JSON it fetches is same-origin. The token makes that URL a capability rather than an open file
     * server; {@link PageServer} still confines every read to the project.</p>
     *
     * <p>Called from the Open action, which may be on a background thread: starting the server binds a
     * loopback socket, so it must not run on the EDT.</p>
     */
    public synchronized @Nullable String pageUrl(@NotNull Path page) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        String relative = ServedPageUrls.projectRelative(Path.of(basePath), page);
        if (relative == null) {
            return null;
        }
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        String existing = state == null ? "" : state.pageBaseUrl;
        if (existing != null && !existing.isBlank()) {
            return ServedPageUrls.onExistingServer(existing, relative);
        }
        if (server == null) {
            // A page being opened is the request that turns the server on; without a configured port the
            // operating system picks one, and `start` publishes the port it actually bound.
            startServer(0);
        }
        if (server == null || boundPort <= 0 || pageToken.isEmpty()) {
            stateNote = "the page server could not start; open the file:// page or configure pageBaseUrl";
            return null;
        }
        return ServedPageUrls.onLocalServer(boundPort, pageToken, relative);
    }

    /**
     * The default app URL for a project file, or null when this project has no built app.
     *
     * <p>Unlike {@link #pageUrl(Path)}, the app URL has no token segment: it is the stable
     * {@code /app/?openFile=<relative>} contract. The local server accepts it only from an allowed
     * origin (or with the token query), and confines both the app and {@code /source} to the project.</p>
     */
    public synchronized @Nullable String appUrl(@NotNull Path file) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        Path root = Path.of(basePath);
        boolean custom = customServerBase != null;
        if (!custom && !Files.isRegularFile(root.resolve(".jcodebuddy").resolve("app").resolve("index.html"))) {
            return null;
        }
        String relative = ServedPageUrls.projectRelative(root, file);
        if (relative == null) {
            return null;
        }
        if (server == null) {
            startServer(0);
        }
        if (server == null || boundPort <= 0) {
            return null;
        }
        return ServedPageUrls.appUrl("http://127.0.0.1:" + boundPort, relative);
    }

    /** True when a project-automation server is configured to own the page/data namespaces. */
    public boolean isCustomServerConfigured() {
        return customServerBase != null;
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
            pageServer = null;
            pageToken = "";
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
        configuredOriginsText = originsText == null ? "" : originsText.trim();
        token = tokenText == null ? "" : tokenText.trim();

        int port;
        String basePath = project.getBasePath();
        HostConfig.CustomServer custom = basePath == null
                ? HostConfig.customServer(Path.of("."))
                : HostConfig.customServer(Path.of(basePath));
        customServerBase = custom.present() ? custom.baseUrl() : null;
        if (!custom.problem().isEmpty()) {
            LOG.info("WebView HTTP bridge: " + custom.problem());
        }
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
        if (!resolved.configured() && customServerBase == null) {
            stateNote = "the bridge is off (neither webview.explorer.port, nor this checkout's "
                    + HostDescriptor.FILE_NAME + ", nor " + HostConfig.DIR + "/" + HostConfig.FILE_NAME
                    + " names a port)";
            LOG.info("WebView HTTP bridge is off (webview.explorer.port is not configured)");
            return;
        }
        port = resolved.configured() ? resolved.port() : 0;
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
            PageServer createdPageServer = new PageServer(projectRoot.toString());
            String createdPageToken = UUID.randomUUID().toString().replace("-", "");
            created.createContext("/", this::handle);
            ExecutorService createdExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "webview-bridge-" + threadSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(createdExecutor);
            created.start();

            // `port` is 0 when the caller asked the operating system to choose. The actual port is what
            // must be published and used in URLs; using the request (0) made an on-demand server
            // unreachable and published "port 0".
            int actualPort = created.getAddress() != null ? created.getAddress().getPort() : port;
            server = created;
            executor = createdExecutor;
            pageServer = createdPageServer;
            pageToken = createdPageToken;
            boundPort = actualPort;
            // The pages this host serves are the pages it must drive: trust its own origin, as webviewd
            // does, in addition to whatever the settings name.
            String self = "http://127.0.0.1:" + actualPort + ",http://localhost:" + actualPort;
            allowedOrigins = AllowedOrigins.of(configuredOriginsText.isEmpty()
                    ? self : self + "," + configuredOriginsText);
            publish(projectRoot, actualPort, sticky);
            LOG.info("WebView HTTP bridge listening on 127.0.0.1:" + actualPort
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

        if (path != null && path.startsWith(ServedPageUrls.LOCAL_PREFIX)) {
            handlePage(exchange, path);
            return;
        }

        if (customServerBase != null && isProxied(path)) {
            proxyToCustomServer(exchange, path);
            return;
        }

        if (path != null && (path.equals("/app") || path.startsWith("/app/"))) {
            handleApp(exchange, path, origin);
            return;
        }

        if (path != null && path.startsWith("/source/")) {
            handleSource(exchange, path, origin);
            return;
        }

        if (path != null && path.startsWith("/lib/")) {
            handleLib(exchange, path, origin);
            return;
        }

        if (path != null && path.startsWith("/reports/")) {
            handleReports(exchange, path, origin);
            return;
        }

        if (path != null && path.startsWith("/api/v1/metadata/")) {
            handleMetadata(exchange, path, origin);
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

    /** {@code GET /lib/...} — the project's shared metadata lib, read-only. */
    private void handleLib(@NotNull HttpExchange exchange, @NotNull String path, String origin)
            throws IOException {
        if (!isAuthorized(exchange, origin) || !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, isAuthorized(exchange, origin) ? 405 : 403, "Forbidden", "text/plain");
            return;
        }
        serveMetadataFile(exchange, metadataDirectory().resolve("lib"), path.substring("/lib/".length()));
    }

    /** {@code GET /reports/<id>/...} — generated report pages and their metadata, read-only. */
    private void handleReports(@NotNull HttpExchange exchange, @NotNull String path, String origin)
            throws IOException {
        if (!isAuthorized(exchange, origin)) {
            send(exchange, 403, "Forbidden", "text/plain");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String rest = path.substring("/reports/".length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        Path dir = reportDirectory(id);
        if (dir == null) {
            send(exchange, 404, "Unknown report", "text/plain");
            return;
        }
        String relative = slash < 0 ? "index.html" : rest.substring(slash + 1);
        serveMetadataFile(exchange, dir, relative);
    }

    /** The catalog/index part of the standard metadata contract. */
    private void handleMetadata(@NotNull HttpExchange exchange, @NotNull String path, String origin)
            throws IOException {
        if (!isAuthorized(exchange, origin)) {
            send(exchange, 403, "Forbidden", "text/plain");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String rest = path.substring("/api/v1/metadata/".length());
        if (rest.equals("catalog")) {
            serveMetadataFile(exchange, metadataDirectory(), "catalog.json");
            return;
        }
        if (rest.equals("events")) {
            handleMetadataEvents(exchange, exchange.getRequestURI().getQuery());
            return;
        }
        if (rest.endsWith("/index")) {
            String id = rest.substring(0, rest.length() - "/index".length());
            Path dir = reportDirectory(id);
            if (dir == null) {
                send(exchange, 404, "Unknown report", "text/plain");
                return;
            }
            serveMetadataFile(exchange, dir, "index.json");
            return;
        }
        send(exchange, 404, "Not Found", "text/plain");
    }

    private void handleMetadataEvents(@NotNull HttpExchange exchange, String query) throws IOException {
        String report = null;
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("report=")) {
                    report = URLDecoder.decode(part.substring("report=".length()), StandardCharsets.UTF_8);
                }
            }
        }
        Path dir = reportDirectory(report == null ? "" : report);
        if (dir == null) {
            send(exchange, 404, "Unknown report", "text/plain");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        String reportId = report;
        Thread.ofVirtual().start(() -> streamMetadataEvents(exchange, dir, reportId));
    }

    /**
     * Polls the local manifest and writes one event per generation. The event names only shards whose
     * content hash changed, and sets {@code indexChanged} when the non-shard index fields (stats, title,
     * link base, …) changed. This is the same diff the standalone/webviewd host computes.
     */
    private void streamMetadataEvents(HttpExchange exchange, Path dir, String report) {
        String last = "";
        JsonObject previousIndex = null;
        long keepalive = 0;
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String generation = "";
                JsonObject index = null;
                try {
                    JsonObject manifest = JsonParser.parseString(
                            Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
                    generation = manifest.has("generation") ? manifest.get("generation").getAsString() : "";
                    index = JsonParser.parseString(
                            Files.readString(dir.resolve("index.json"), StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (IOException | RuntimeException ignored) {
                    // The manifest is replaced atomically; a torn read is retried on the next tick.
                }
                if (index != null && !generation.isEmpty() && !generation.equals(last)) {
                    List<Integer> changed = changedShards(previousIndex, index);
                    boolean indexChanged = previousIndex == null
                            || !metadataWithoutShards(previousIndex).equals(metadataWithoutShards(index));
                    if (!changed.isEmpty() || indexChanged) {
                        String event = "id: " + generation + "\nevent: metadata\ndata: {\"report\":\""
                                + (report == null ? "" : report.replace("\"", "\\\""))
                                + "\",\"generation\":\"" + generation.replace("\"", "\\\"")
                                + "\",\"changedShards\":" + changed
                                + ",\"indexChanged\":" + indexChanged + "}\n\n";
                        out.write(event.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    previousIndex = index;
                    last = generation;
                }
                if (++keepalive % 60 == 0) {
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                Thread.sleep(250);
            }
        } catch (IOException | InterruptedException ignored) {
            // The browser closed the stream, or the host is stopping.
        }
    }

    /** The shard indexes whose hash changed, or every shard on the first event. */
    private static List<Integer> changedShards(@Nullable JsonObject previous, @NotNull JsonObject next) {
        JsonArray current = next.has("shards") && next.get("shards").isJsonArray()
                ? next.getAsJsonArray("shards") : new JsonArray();
        JsonArray before = previous != null && previous.has("shards") && previous.get("shards").isJsonArray()
                ? previous.getAsJsonArray("shards") : null;
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            boolean same = before != null && i < before.size()
                    && before.get(i).equals(current.get(i));
            if (!same) {
                changed.add(i);
            }
        }
        return changed;
    }

    /** The index without its shard hashes and generation, which is what "indexChanged" compares. */
    private static JsonObject metadataWithoutShards(@NotNull JsonObject index) {
        JsonObject meta = index.deepCopy();
        meta.remove("shards");
        meta.remove("generation");
        return meta;
    }

    private Path metadataDirectory() {
        String base = project.getBasePath();
        return base == null ? Path.of(".jcodebuddy/metadata") : Path.of(base, ".jcodebuddy", "metadata");
    }

    @Nullable
    private Path reportDirectory(@Nullable String id) {
        if (id == null || id.isBlank() || !id.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        try {
            Path catalog = metadataDirectory().resolve("catalog.json");
            if (Files.isRegularFile(catalog)) {
                JsonObject root = JsonParser.parseString(Files.readString(catalog, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (JsonElement element : root.getAsJsonArray("reports")) {
                    JsonObject entry = element.getAsJsonObject();
                    if (id.equals(entry.has("report") ? entry.get("report").getAsString() : "")) {
                        String manifest = entry.has("manifest") ? entry.get("manifest").getAsString() : "";
                        int slash = manifest.lastIndexOf('/');
                        if (slash > 0) {
                            return metadataDirectory().resolve(manifest.substring(0, slash));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Fall through to the direct report directory.
        }
        Path direct = metadataDirectory().resolve(id);
        return Files.isDirectory(direct) ? direct : null;
    }

    private void serveMetadataFile(@NotNull HttpExchange exchange, @NotNull Path root, @NotNull String relative)
            throws IOException {
        PageServer current = pageServer;
        if (current == null) {
            send(exchange, 404, "No page server is running", "text/plain");
            return;
        }
        String path = relative.isEmpty() ? "index.html" : relative;
        if (path.endsWith("/")) {
            path += "index.html";
        }
        String projectRelative = projectRelativeOf(root.resolve(path).normalize());
        if (projectRelative == null) {
            send(exchange, 404, "Not Found", "text/plain");
            return;
        }
        PageServer.Response response = current.serve(PageServer.ROUTE_PREFIX + projectRelative, null);
        sendBytes(exchange, response.httpStatus(), response.contentType(), response.body());
    }

    @Nullable
    private String projectRelativeOf(Path file) {
        String base = project.getBasePath();
        if (base == null) {
            return null;
        }
        Path root = Path.of(base).toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        return target.startsWith(root) ? root.relativize(target).toString().replace('\\', '/') : null;
    }

    /**
     * {@code GET /app/?openFile=<project-relative>} and its assets. The app is derived output in
     * {@code .jcodebuddy/app}; the query is read by the app, not the host.
     */
    private void handleApp(@NotNull HttpExchange exchange, @NotNull String path, String origin)
            throws IOException {
        if (!isAuthorized(exchange, origin)) {
            send(exchange, 403, "Forbidden", "text/plain");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String relative = path.startsWith("/app/") ? path.substring("/app/".length()) : "index.html";
        serveProjectRelative(exchange, ".jcodebuddy/app/" + relative);
    }

    /** {@code GET /source/<project-relative>} — the app's read-only source view. */
    private void handleSource(@NotNull HttpExchange exchange, @NotNull String path, String origin)
            throws IOException {
        if (!isAuthorized(exchange, origin)) {
            send(exchange, 403, "Forbidden", "text/plain");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        serveProjectRelative(exchange, path.substring("/source/".length()));
    }

    private void serveProjectRelative(@NotNull HttpExchange exchange, @NotNull String relative)
            throws IOException {
        PageServer current = pageServer;
        if (current == null) {
            send(exchange, 404, "No page server is running", "text/plain");
            return;
        }
        if (relative.isEmpty() || relative.endsWith("/")) {
            relative += "index.html";
        }
        PageServer.Response response = current.serve(PageServer.ROUTE_PREFIX + relative, null);
        sendBytes(exchange, response.httpStatus(), response.contentType(), response.body());
    }

    private static final HttpClient PROXY_CLIENT = HttpClient.newHttpClient();

    private static boolean isProxied(@Nullable String path) {
        if (path == null) {
            return false;
        }
        // Only the app content is the custom server's. /source, /lib, /reports and /api/v1/metadata are the
        // webview host's standard contract and stay local even when a custom server is configured.
        return path.equals("/app") || path.startsWith("/app/");
    }

    /**
     * Proxies one page/data request to the project-automation server. Editor routes are checked before this
     * method, so {@code /open}, {@code /health} and the write API always stay on this IDE's port.
     */
    private void proxyToCustomServer(@NotNull HttpExchange exchange, @NotNull String path)
            throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String rawPath = exchange.getRequestURI().getRawPath();
        String target = customServerBase + (rawPath.startsWith("/") ? rawPath.substring(1) : rawPath)
                + (query == null ? "" : "?" + query);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target));
            HttpRequest request;
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                request = builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            } else if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            } else {
                request = builder.GET().build();
            }
            HttpResponse<java.io.InputStream> response = PROXY_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            String type = response.headers().firstValue("content-type")
                    .orElse("application/octet-stream");
            exchange.getResponseHeaders().set("Content-Type", type);
            response.headers().firstValue("cache-control")
                    .ifPresent(value -> exchange.getResponseHeaders().set("Cache-Control", value));
            response.headers().firstValue("etag")
                    .ifPresent(value -> exchange.getResponseHeaders().set("ETag", value));
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (var body = response.body(); var out = exchange.getResponseBody()) {
                body.transferTo(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(exchange, 502, "Bad Gateway", "text/plain");
        } catch (IOException | RuntimeException e) {
            send(exchange, 502, "Bad Gateway: " + e.getMessage(), "text/plain");
        }
    }

    /**
     * {@code GET /page/<token>/<project-relative path>}: a page and the sibling assets it loads.
     *
     * <p>The token is a capability, not a secret the page has to send on every request: every relative
     * asset URL resolves under the same segment, so the browser carries it automatically. The file
     * decision is core's {@link PageServer}, which confines every read to the project and supplies the MIME
     * table — this handler only checks the method and the token, and writes the bytes.</p>
     */
    private void handlePage(@NotNull HttpExchange exchange, @NotNull String path) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String prefix = ServedPageUrls.LOCAL_PREFIX + pageToken + "/";
        if (!path.startsWith(prefix)) {
            send(exchange, 403, "Forbidden: this page URL is not valid in this session", "text/plain");
            return;
        }
        PageServer current = pageServer;
        if (current == null) {
            send(exchange, 404, "No page server is running", "text/plain");
            return;
        }
        String relative = path.substring(prefix.length());
        PageServer.Response response = current.serve(PageServer.ROUTE_PREFIX + relative, null);
        sendBytes(exchange, response.httpStatus(), response.contentType(), response.body());
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
        // A page this host served carries its one-time token in its own URL and passes it back on the
        // `/open` fallback, so a browser without the injected `window.openFile` can still navigate.
        if (!pageToken.isEmpty()) {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            if (pageToken.equals(params.get("token"))) {
                return true;
            }
        }
        return allowedOrigins.allows(origin);
    }

    private static void send(@NotNull HttpExchange exchange, int status, @NotNull String body,
                             @NotNull String contentType) throws IOException {
        sendBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /** A response with bytes behind it: a page, its JavaScript, its JSON model, a stylesheet. */
    private static void sendBytes(@NotNull HttpExchange exchange, int status, @NotNull String contentType,
                                  byte[] body) throws IOException {
        String type = contentType.startsWith("text/") || contentType.contains("json")
                ? contentType + "; charset=utf-8" : contentType;
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
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
