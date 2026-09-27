package hr.hrg.eclipse.webview.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;

import hr.hrg.eclipse.webview.bridge.EclipseBridge;
import hr.hrg.webview.core.AllowedOrigins;
import hr.hrg.webview.core.CheckpointStore;
import hr.hrg.webview.core.EditService;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.HostConfig;
import hr.hrg.webview.core.HostDescriptor;
import hr.hrg.webview.core.HostHealth;
import hr.hrg.webview.core.HostPortClaim;
import hr.hrg.webview.core.InjectedBridge;
import hr.hrg.webview.core.NavigationOutcome;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.PageServer;
import hr.hrg.webview.core.RateLimiter;
import hr.hrg.webview.core.WriteSurface;

/**
 * The HTTP surface of the Eclipse host: loopback, the frozen contract's routes, the two page routes,
 * a manifest, and the wrapped editor host behind all of it — the same shape as the standalone
 * {@code webviewd} server, on the same core rules, so the parity test can compare them by reading
 * both.
 *
 * <p>What this class does <b>not</b> re-implement is any part of the security model: the path jail,
 * the rate limit, the origin rule, the digest and the token all come from {@code webview-core}, and
 * the editor work is done by the shared {@link hr.hrg.eclipse.webview.bridge.EclipseEditorHost}
 * through {@link UiThreadHost}, which marshals it onto the display thread. One bridge per served
 * project (E7): a project's files are confined to its own pages, and the descriptor names it.
 *
 * <p>Two choices distinguish an IDE host from the standalone one, and both are written in the plan
 * rather than improvised here. The socket is <b>created inside the port claim</b> — the attempt that
 * reports a port free is the attempt that is holding it, because "check, then bind" leaves a window
 * (plan § "the host binds its socket inside the claim, as the JetBrains service does"). And the
 * host stays <b>off until a port is named</b> (E17): an Eclipse workbench with no opinion about
 * ports opens no socket and publishes nothing.
 *
 * <p>The token is not a secret the user must invent (E16): the file
 * {@code .jcodebuddy/webview/token} beside the descriptor holds it, generated once and reused by
 * every later start, and the explicit preference override is the only token setting there is.
 */
public final class EclipseHttpBridge implements AutoCloseable {

    /** Pages served under this prefix get the bridge injected; files under the page server's own prefix do not. */
    public static final String PAGE_ROUTE_PREFIX = "/page/";

    /** Where the port, the token path and the capabilities are published for a caller that cannot guess. */
    public static final String MANIFEST_ROUTE = "/.well-known/webview.json";

    /** The write routes' prefix; {@link #handleApi} names what each verb answers. */
    public static final String API_ROUTE_PREFIX = "/api/v1/";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The answer to one start attempt: a running bridge, or the reason there is not one. Both fields
     * can be read by the caller's log without this class knowing anything about the platform's logging.
     *
     * @param bridge the serving bridge, or null when the bridge is off, was refused a port, or failed
     * @param note   one sentence for the log: what was decided, and where the decision came from
     */
    public record Outcome(EclipseHttpBridge bridge, String note) {

        public boolean serving() {
            return bridge != null;
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Navigator navigator;
    private final PageServer pageServer;
    private final EditService editService;
    private final WriteSurface writeSurface;
    private final AllowedOrigins origins;
    private final String token;
    private final Path tokenFile;
    private final Path projectRoot;
    private final int port;
    private final String ideName;
    private final EditorHost editorHost;
    private final String injectedBridge;

    private EclipseHttpBridge(HttpServer server, ExecutorService executor, Path projectRoot, int port,
                              EditorHost editorHost, RateLimiter limiter, String token, Path tokenFile,
                              String ideName) {
        this.server = server;
        this.executor = executor;
        this.projectRoot = projectRoot;
        this.port = port;
        this.editorHost = editorHost;
        this.tokenFile = tokenFile;
        this.ideName = ideName;
        // The token is always present for this host — generated when nothing named one — so writes and
        // page loads can rely on it, and /health reports tokenRequired honestly rather than hopefully.
        this.token = token;
        // A fixed-root, project-confined navigator (E7): this bridge serves one project, and a page it
        // hosts may not reach outside it. The rate limiter is the plugin's shared one, so the injected
        // bridge and this transport draw on one budget rather than charging a page twice.
        this.navigator = new Navigator(projectRoot.toString(), editorHost, true, limiter);
        this.pageServer = new PageServer(projectRoot.toString(), true);
        // The disk half of the write contract (E8), sharing the ONE budget with navigation — a page
        // that spins on writes cannot spend a different allowance from a page that spins on clicks.
        // The checkpoints are persistent and live with the descriptor, so an undo survives a restart
        // of the workbench: the part the JetBrains host's in-memory store does not claim.
        this.editService = new EditService(projectRoot.toString(),
                CheckpointStore.persistent(
                        HostDescriptor.directoryOf(projectRoot).resolve("checkpoints"),
                        CheckpointStore.DEFAULT_LIMIT),
                limiter);
        // The conversation half: parse, digest-guard, route buffer-versus-disk, map to the frozen
        // statuses. Shared with webviewd and the JetBrains host, so this host cannot drift into
        // answering a write differently (E8: "WriteSurface decides, not the plugin").
        this.writeSurface = new WriteSurface(editService, editorHost);
        // The transport a served page uses is the BrowserFunction the view registered: the page is
        // rendered inside the same SWT Browser (E12), so the injected script calls straight into the
        // editor host with no HTTP hop, no CORS grant and no visible navigation.
        this.injectedBridge = InjectedBridge.script(EclipseBridge.TRANSPORT);
        // Self origins only: the pages this host serves are its own; every other caller needs the token.
        this.origins = AllowedOrigins.of("http://127.0.0.1:" + port + ",http://localhost:" + port);
    }

    // --- starting ------------------------------------------------------------------------------------

    /**
     * Resolve the port, claim it for this project, bind inside the claim, register the routes, start,
     * and publish the descriptor — or explain in one sentence why none of that happened.
     *
     * @param explicitPort  the Eclipse preference, or null when the user named no port; the rest of the
     *                      resolution order (this checkout's current port, then the project's committed
     *                      default) is {@link HostConfig}'s, and the fallback of {@code -1} keeps the
     *                      bridge off until something names a port (E17)
     * @param editorHost    the marshalled, shared editor host; every route funnels into it
     * @param limiter       the shared rate budget — the same instance the injected bridge spends
     * @param explicitToken the token preference, or an empty string to generate-and-keep the file token
     * @param ideName       {@link #ideName()}, resolved once by the caller
     */
    public static Outcome start(Path projectRoot, Integer explicitPort, EditorHost editorHost,
                                RateLimiter limiter, String explicitToken, String ideName)
            throws IOException {
        HostConfig.RequestedPort requested =
                HostConfig.requestedPort(projectRoot, explicitPort, -1);
        if (!requested.configured()) {
            String problem = requested.problem();
            return new Outcome(null, (problem.isEmpty() ? "" : problem + "; ")
                    + "the bridge stays off until a port is named (the Eclipse preference, this checkout's "
                    + HostDescriptor.FILE_NAME + ", or " + HostConfig.DIR + "/" + HostConfig.FILE_NAME + ")");
        }

        // The pin is a local property of the port this checkout uses, read from the published state and
        // never from configuration. null means "this project has not pinned anything".
        HostDescriptor published = HostDescriptor.read(projectRoot);
        boolean sticky = published != null && published.sticky();
        Integer stickyPort = sticky && published.port() > 0 ? published.port() : null;

        // The server created by the attempt that succeeded. Binding happens here, inside the claim, so
        // the port reported free is the port this process is already holding; a bind failure is
        // classified by asking the occupant what it is — "busy" is not "a bridge is there".
        AtomicReference<HttpServer> claimed = new AtomicReference<>();
        HostPortClaim.Attempt attempt = candidate -> {
            try {
                claimed.set(HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 0));
                return new HostPortClaim.Try.Free();
            } catch (IOException e) {
                return new HostPortClaim.Try.Taken(HostPortClaim.occupantOf(candidate));
            }
        };

        HostPortClaim.Decision decision = HostPortClaim.claim(projectRoot, requested.port(), stickyPort,
                HostPortClaim.DEFAULT_ATTEMPTS, attempt, HostPortClaim::occupantOf);
        if (decision.failed() || decision.skipped()) {
            return new Outcome(null, decision.reason());
        }
        HttpServer created = claimed.get();
        if (created == null) {
            return new Outcome(null, "no server was created for the claimed port " + decision.port());
        }

        int port = created.getAddress().getPort();
        Path tokenFile = HostDescriptor.directoryOf(projectRoot).resolve("token");
        String token = resolveToken(tokenFile, explicitToken);
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "webview-eclipse-http");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        EclipseHttpBridge bridge = new EclipseHttpBridge(created, executor, projectRoot, port, editorHost,
                limiter, token, explicitToken == null || explicitToken.isBlank() ? tokenFile : null,
                ideName);
        created.createContext("/health", bridge::handleHealth);
        created.createContext("/open", bridge::handleOpen);
        created.createContext(PageServer.ROUTE_PREFIX, bridge::handleFile);
        created.createContext(PAGE_ROUTE_PREFIX, bridge::handlePage);
        created.createContext(MANIFEST_ROUTE, bridge::handleManifest);
        created.createContext(API_ROUTE_PREFIX, bridge::handleApi);
        created.createContext("/", bridge::handleIndex);
        created.start();

        try {
            bridge.descriptor(sticky).write(projectRoot);
        } catch (IOException publishFailed) {
            // A bridge nothing can find is worse than no bridge: the port is released and the cause
            // travels to the log. The claim's decision said this project may serve; the record of it
            // is part of serving.
            bridge.close();
            throw publishFailed;
        }

        String note = decision.moved() || decision.sticky()
                ? "listening on 127.0.0.1:" + port + " — " + decision.reason()
                : "listening on 127.0.0.1:" + port + " (" + requested.describe(projectRoot) + ")";
        return new Outcome(bridge, note);
    }

    // --- token and identity --------------------------------------------------------------------------

    /**
     * The token this bridge answers with: the preference override when one is set, else the project's
     * token file — read if it already holds one, generated once and written if it does not. The token
     * belongs to the project, not to this process, so a restart of the IDE keeps the same secret and a
     * bookmarked {@code /page/} URL keeps working (E16).
     */
    static String resolveToken(Path tokenFile, String explicitToken) throws IOException {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        if (Files.isRegularFile(tokenFile)) {
            String existing = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        return generateToken(tokenFile);
    }

    /**
     * Generates a token and stores it where only the user can read it. On Windows the {@code 0600}
     * attempt fails (there is no POSIX permission model) and is ignored: the file sits in the project
     * the user already owns, and the descriptor deliberately names the path without carrying the secret.
     */
    static String generateToken(Path tokenFile) throws IOException {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, token + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tokenFile, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem: documented in webview-host-api.md rather than worked around.
        }
        return token;
    }

    /**
     * The human name of the running product (E13): {@code Platform.getProduct().getName()} names the
     * distribution the reader actually installed — "Eclipse IDE for Java Developers", not a guess —
     * and the constant is the fallback for the bare workbench that defines no product, or a platform
     * too young or too broken to answer.
     */
    public static String ideName() {
        try {
            IProduct product = Platform.getProduct();
            if (product != null) {
                String name = product.getName();
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // The name is presentation, not policy: fall back rather than lose the health document.
        }
        return HostHealth.IDE_ECLIPSE;
    }

    // --- accessors -----------------------------------------------------------------------------------

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public Path project() {
        return projectRoot;
    }

    public String token() {
        return token;
    }

    public AllowedOrigins origins() {
        return origins;
    }

    /** True when this bridge serves the given project — the identity the view tests before loading a page URL. */
    public boolean serves(Path project) {
        return HostPortClaim.comparable(projectRoot).equals(HostPortClaim.comparable(project));
    }

    /**
     * The URL to load into the browser for a file of this project (E12): the {@code /page/} route with
     * the project-relative path, and the token as a query parameter — a browser's top-level navigation
     * sends no headers, so the token rides on the URL that the authorization then accepts. Segments are
     * percent-encoded by hand rather than with a form encoder: {@code +} means a literal plus in a path,
     * which is exactly what {@link PageServer} decodes it as.
     */
    public String pageUrl(Path absoluteFile) {
        Path relative = projectRoot.toAbsolutePath().normalize()
                .relativize(absoluteFile.toAbsolutePath().normalize());
        StringBuilder url = new StringBuilder("http://127.0.0.1:").append(port).append(PAGE_ROUTE_PREFIX);
        for (Path segment : relative) {
            url.append(encodeSegment(segment.toString())).append('/');
        }
        url.setLength(url.length() - 1);
        return url.append("?token=").append(encodeSegment(token)).toString();
    }

    static String encodeSegment(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** The descriptor that lets a page find this bridge without being told the port. */
    public HostDescriptor descriptor(boolean sticky) {
        List<String> capabilities = new ArrayList<>(navigator.capabilities());
        HostDescriptor.HostDetail detail = new HostDescriptor.HostDetail(editorHost.name(),
                editorHost.isAvailable(), editorHost.lineNavigation(), editorHost.lineNavigationNote());
        return HostDescriptor.of(projectRoot, HostHealth.PLUGIN_ECLIPSE, ideName, port, sticky,
                tokenFile == null ? "" : tokenFile.toString(), capabilities, detail);
    }

    /** The document a page reads from {@code /health}. */
    public String healthJson() {
        return HostHealth.of(HostHealth.PLUGIN_ECLIPSE, ideName, projectRoot.toString(), port,
                origins.values().size(), true, navigator.capabilities()).toJson();
    }

    /** The manifest {@code /.well-known/webview.json} answers with. */
    public String manifestJson() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("plugin", HostHealth.PLUGIN_ECLIPSE);
        manifest.put("ide", ideName);
        manifest.put("port", port);
        manifest.put("project", HostHealth.normalizeProject(projectRoot.toString()));
        manifest.put("tokenPath", tokenFile == null ? "" : HostHealth.normalizeProject(tokenFile.toString()));
        manifest.put("tokenRequired", true);
        manifest.put("bridgeVersion", InjectedBridge.VERSION);
        manifest.put("capabilities", new ArrayList<>(navigator.capabilities()));
        Map<String, Object> hostDetail = new LinkedHashMap<>();
        hostDetail.put("name", editorHost.name());
        hostDetail.put("available", editorHost.isAvailable());
        hostDetail.put("lineNavigation", editorHost.lineNavigation());
        hostDetail.put("note", editorHost.lineNavigationNote());
        manifest.put("host", hostDetail);
        return GSON.toJson(manifest);
    }

    // --- request handling ----------------------------------------------------------------------------

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        send(exchange, 200, "application/json", healthJson().getBytes(StandardCharsets.UTF_8));
    }

    private void handleManifest(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        send(exchange, 200, "application/json", manifestJson().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The contract's authorization rule, in one place: an allowed {@code Origin} <b>or</b> the token,
     * and this host's allow-list is its own origins — so in practice a caller needs the token unless
     * it is a page this bridge served.
     *
     * <p>It is defined <em>above</em> the routes that call it deliberately: the cross-host parity test
     * ({@code HostHealthParityTest}) reads this file and requires the first {@code 403} to precede the
     * first act-on-the-request, so that "authorizes first" is true of the program text and not just of
     * the thread's order of execution.
     */
    private boolean authorize(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (origins.allows(exchange.getRequestHeaders().getFirst("Origin"))) {
                applyCors(exchange);
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "X-WebView-Token");
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(403, -1);
            }
            exchange.close();
            return false;
        }
        if (!requireGet(exchange)) {
            return false;
        }
        String presented = exchange.getRequestHeaders().getFirst("X-WebView-Token");
        if (presented == null) {
            presented = query(exchange.getRequestURI()).get("token");
        }
        if (token.equals(presented)) {
            return true;
        }
        if (origins.allows(exchange.getRequestHeaders().getFirst("Origin"))) {
            return true;
        }
        note("refused " + exchange.getRequestURI().getPath() + ": no allowed Origin and no valid token");
        send(exchange, 403, "text/plain",
                bytes("Forbidden: the Eclipse bridge accepts its own origin or a valid token"));
        return false;
    }

    private void handleOpen(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        String filePath = query.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            send(exchange, 400, "text/plain", bytes("Missing filePath parameter"));
            return;
        }
        int line = positiveInt(query.get("line"));
        int column = positiveInt(query.get("column"));

        NavigationOutcome outcome = navigator.open(filePath, line, column);
        Answer answer = switch (outcome.reason()) {
            case OK -> new Answer(200, "Opening " + filePath + ":" + line + ":" + column);
            case INVALID_PATH -> new Answer(400, "Missing filePath parameter");
            case OUTSIDE_PROJECT ->
                    new Answer(403, "Forbidden: '" + outcome.absolutePath() + "' is outside the project");
            case RATE_LIMITED -> new Answer(429, "Too Many Requests: " + Navigator.RATE_LIMIT_COUNT
                    + " per " + (Navigator.RATE_LIMIT_WINDOW_MS / 1000) + "s");
            case NO_HOST, HOST_REFUSED -> new Answer(404, "Could not open " + filePath);
        };
        if (answer.status() != 200) {
            note("refused " + filePath + ": " + answer.status() + " " + answer.body());
        }
        send(exchange, answer.status(), "text/plain", bytes(answer.body()));
    }

    private void handleFile(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        PageServer.Response response = pageServer.serve(exchange.getRequestURI().getPath(), null);
        announceDigest(exchange, response);
        send(exchange, response.httpStatus(), response.contentType(), response.body());
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PAGE_ROUTE_PREFIX)) {
            send(exchange, 400, "text/plain", bytes("Invalid page path"));
            return;
        }
        // The page server decides and reads; the only difference between /file/ and /page/ is that a
        // page gets the bridge appended, so it can call window.openFile without shipping the script.
        String translated = PageServer.ROUTE_PREFIX + path.substring(PAGE_ROUTE_PREFIX.length());
        PageServer.Response response = pageServer.serve(translated, injectedBridge);
        announceDigest(exchange, response);
        send(exchange, response.httpStatus(), response.contentType(), response.body());
    }

    /**
     * The write routes, dispatched by a {@code switch} with one direct call per case so an IDE can
     * navigate a route to its handler (DEC-019, E9) — never a scanned registry. The path decides
     * first: an unknown verb is a 404 ("there is no such thing") and only a route that exists can
     * answer 405 ("not with that method") — the ordering webviewd learned the hard way. The routing,
     * the digest guard and every status belong to core's {@link WriteSurface}; this class only moves
     * bytes between the socket and it (E8: "WriteSurface decides, not the plugin").
     *
     * <p>{@code /api/v1/events} answers 404 with the reason instead of streaming: this host declares
     * no {@code watch} capability, and a page asking for one must be refused rather than left
     * waiting (plan § 6).
     */
    private void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/api/v1/applyEdit" -> handleApplyEdit(exchange);
            case "/api/v1/diff" -> handleDiff(exchange);
            case "/api/v1/undo" -> handleUndo(exchange);
            case "/api/v1/redo" -> handleRedo(exchange);
            case "/api/v1/events" -> send(exchange, 404, "text/plain", bytes(
                    "Not Found: " + path + " — this host declares no watch capability"
                            + " and serves no event stream"));
            default -> send(exchange, 404, "text/plain", bytes("Not Found: " + path));
        }
    }

    /**
     * {@code POST /api/v1/applyEdit}: the write contract's one entry point. Two rules distinguish it
     * from {@code /open}: the method is POST, and the caller must present the <b>token</b> — an
     * allowed Origin is not enough (D8).
     */
    private void handleApplyEdit(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange) || !authorizeWrite(exchange)) {
            return;
        }
        WriteSurface.Answer answer = writeSurface.applyEdit(readBody(exchange));
        noteIfRefused("applyEdit", answer);
        sendJson(exchange, answer.status(), answer.body());
    }

    /** {@code POST /api/v1/diff}: the proposal step, which by definition writes nothing. */
    private void handleDiff(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange) || !authorizeWrite(exchange)) {
            return;
        }
        WriteSurface.Answer answer = writeSurface.diff(readBody(exchange));
        noteIfRefused("diff", answer);
        sendJson(exchange, answer.status(), answer.body());
    }

    private void handleUndo(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange) || !authorizeWrite(exchange)) {
            return;
        }
        WriteSurface.Answer answer = writeSurface.undo(readBody(exchange));
        noteIfRefused("undo", answer);
        sendJson(exchange, answer.status(), answer.body());
    }

    private void handleRedo(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange) || !authorizeWrite(exchange)) {
            return;
        }
        WriteSurface.Answer answer = writeSurface.redo(readBody(exchange));
        noteIfRefused("redo", answer);
        sendJson(exchange, answer.status(), answer.body());
    }

    /** One stderr line for anything a page was refused, so the host's log explains the page's error. */
    private void noteIfRefused(String route, WriteSurface.Answer answer) {
        if (!answer.ok()) {
            note(route + " refused with " + answer.status() + ": " + answer.body().replaceAll("\\s+", " "));
        }
    }

    /**
     * The authorization for a <b>state-changing</b> route: the token, and nothing else. Deliberately
     * stricter than {@link #authorize} — any page in the reader's browser can share an origin rule,
     * and plan D8 says no state-changing route may be reachable without proving possession of the
     * secret. This host always has a token (E16 generates one when nothing named one), so the null
     * guard is the belt to that brace.
     */
    private boolean authorizeWrite(HttpExchange exchange) throws IOException {
        if (token == null) {
            send(exchange, 403, "text/plain", bytes(
                    "Forbidden: this host has no token configured, so it refuses every state-changing route"));
            return false;
        }
        String presented = exchange.getRequestHeaders().getFirst("X-WebView-Token");
        if (presented == null) {
            presented = query(exchange.getRequestURI()).get("token");
        }
        if (token.equals(presented)) {
            return true;
        }
        note("refused a write to " + exchange.getRequestURI().getPath() + ": token missing or wrong");
        send(exchange, 403, "text/plain",
                bytes("Forbidden: the token is required for state-changing routes"));
        return false;
    }

    private boolean requirePost(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", "POST");
        send(exchange, 405, "text/plain", bytes("Method Not Allowed: use POST"));
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json", bytes(json));
    }

    /**
     * Tells the page which bytes it just read, so it never has to recompute a digest — and so the
     * digest it proposes an edit with is the host's own idea of the file. Both spellings are sent:
     * {@code ETag} (quoted, per HTTP) and {@code X-WebView-Digest} (exactly the string the edit API
     * wants). The digest was taken before the bridge script was appended, by the page server, so the
     * page hashes what is on disk and not what this host appended to it.
     */
    private void announceDigest(HttpExchange exchange, PageServer.Response response) {
        if (response.fileDigest() == null || response.fileDigest().isEmpty()) {
            return;
        }
        exchange.getResponseHeaders().set("ETag", "\"" + response.fileDigest() + "\"");
        exchange.getResponseHeaders().set("X-WebView-Digest", response.fileDigest());
    }

    /**
     * The index page: what this bridge is and which project it serves. Unauthenticated by design, as
     * in {@code webviewd} — it says nothing a port probe does not, and a browser typed at a port
     * should learn what it found.
     */
    private void handleIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(exchange, 404, "text/plain", bytes("Not found: " + path));
            return;
        }
        if (!requireGet(exchange)) {
            return;
        }
        String html = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Eclipse webview bridge</title>
                <style>body{font:14px/1.5 system-ui,sans-serif;margin:2rem;max-width:52rem}
                code{background:#eee;padding:.1rem .3rem;border-radius:3px}</style></head>
                <body>
                <h1>Eclipse webview bridge</h1>
                <p>Serving <code>%s</code> on port <code>%d</code>.</p>
                <ul>
                  <li><a href="/health">/health</a> — the document a page reads to decide which rung
                      of its ladder to use.</li>
                  <li><a href="%s">/.well-known/webview.json</a> — port, token path, capabilities.</li>
                  <li><code>/page/&lt;path&gt;</code> — a project page with the bridge injected.</li>
                  <li><code>/file/&lt;path&gt;</code> — a project file as bytes.</li>
                  <li><code>/open?filePath=…&amp;line=…&amp;column=…</code> — the frozen navigation route.</li>
                </ul>
                <p>Editor adapter: <code>%s</code> (%s).</p>
                </body></html>
                """.formatted(HostHealth.normalizeProject(projectRoot.toString()), port, MANIFEST_ROUTE,
                editorHost.name(), editorHost.lineNavigation());
        send(exchange, 200, "text/html; charset=utf-8", bytes(html));
    }

    // --- shared plumbing ------------------------------------------------------------------------------

    /**
     * GET only; an OPTIONS preflight is answered rather than refused — a page that sends the token in
     * a header needs the preflight to succeed, and a preflight carries no credentials of its own.
     */
    private boolean requireGet(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method)) {
            send(exchange, 405, "text/plain", bytes("Method Not Allowed"));
            return false;
        }
        return true;
    }

    /** CORS headers go to an allowed origin only, and are echoed rather than wildcarded. */
    private void applyCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (corsEchoFor(origins, origin) != null) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Vary", "Origin");
        }
    }

    /**
     * The CORS decision as a pure function, so the conformance vectors can be asserted against the
     * very rule the routes use: an allowed origin is echoed verbatim, anything else gets no header.
     */
    static String corsEchoFor(AllowedOrigins origins, String origin) {
        return origins.allows(origin) ? origin : null;
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        applyCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** A query string as a map, decoded as UTF-8; later values win, which is what a browser does too. */
    static Map<String, String> query(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            params.put(decode(name), decode(value));
        }
        return params;
    }

    private static String decode(String text) {
        // For a query string, "+" does mean a space (unlike in a path, where PageServer keeps it literal).
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    private static int positiveInt(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** One status and one body, so the switch that maps a reason stays readable. */
    private record Answer(int status, String body) {
    }

    private static void note(String message) {
        System.err.println("webview-eclipse: " + message);
    }

    /**
     * Stops serving. The published descriptor is left in place deliberately: it is the project's port,
     * not this process's (DEC-033) — what the next start asks for, and where the {@code sticky} pin
     * lives. "Is a host there?" is answered by probing the port, never by the file.
     */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
