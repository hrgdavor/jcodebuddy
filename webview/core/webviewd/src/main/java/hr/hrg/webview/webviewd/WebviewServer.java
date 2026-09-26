package hr.hrg.webview.webviewd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import hr.hrg.webview.core.AllowedOrigins;
import hr.hrg.webview.core.CheckpointStore;
import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.EditRequest;
import hr.hrg.webview.core.EditService;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.HostDescriptor;
import hr.hrg.webview.core.HostHealth;
import hr.hrg.webview.core.HostPortClaim;
import hr.hrg.webview.core.InjectedBridge;
import hr.hrg.webview.core.NavigationOutcome;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.NullHost;
import hr.hrg.webview.core.PageServer;
import hr.hrg.webview.core.RateLimiter;
import hr.hrg.webview.core.WriteSurface;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The page host: loopback HTTP, the frozen contract's two routes, the two page routes, a manifest, and one
 * {@link EditorHost} behind all of it.
 *
 * <p>What it does <em>not</em> do is re-implement any of the security model. The origin allow-list, the
 * token, the path jail and the 20-per-20s rate limit all come from {@code webview-core}, which is the point
 * of Phase 1: three copies of those rules disagreed, and a fourth was not an option. This class only binds a
 * socket, translates a request into a {@link Navigator} call, and translates the answer back into the status
 * codes the frozen contract defines.
 *
 * <p>Two deliberate departures from what the plan expected, both from Phase 0's measurements:
 *
 * <ul>
 *   <li>the host trusts <b>its own origin</b> by default, in addition to anything {@code --allowed-origins}
 *       names, because the pages it serves are the pages it must drive; an outside page still has to be
 *       allow-listed or hold the token;</li>
 *   <li>the adapter it attaches can be one that cannot place a caret ({@link ZedCliHost}), so the manifest
 *       carries {@code host.lineNavigation} — {@code "file-only"} — rather than a page having to infer that
 *       {@code open} means "the file, not the line".</li>
 * </ul>
 */
public final class WebviewServer implements AutoCloseable {

    /** The plugin name a page sees, and the one that goes in the descriptor. */
    public static final String PLUGIN = "hr.hrg.webview.webviewd";

    /** The human name this host answers {@code /health} with, and writes into the descriptor. */
    public static final String IDE = HostHealth.IDE_WEBVIEWD;

    /** Where the capabilities, the port and the token path are published for a caller that cannot guess. */
    public static final String MANIFEST_ROUTE = "/.well-known/webview.json";

    /** Pages served under this prefix get the bridge injected; files under {@link PageServer#ROUTE_PREFIX} do not. */
    public static final String PAGE_ROUTE_PREFIX = "/page/";

    /** How long the event stream waits before sending a keep-alive comment, so a proxy does not close it. */
    static final long EVENT_KEEPALIVE_MS = 15_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final ExecutorService executor;
    private final Navigator navigator;
    private final PageServer pageServer;
    private final EditService editService;
    private final WriteSurface writeSurface;
    private final AllowedOrigins origins;
    private final String token;
    private final Path project;
    private final int port;
    private final String injectedBridge;
    private final EditorHost host;
    private final Path tokenFile;

    private WebviewServer(HttpServer server, ExecutorService executor, WebviewdConfig config,
                          EditorHost host, int port, String token, AllowedOrigins origins, Path tokenFile) {
        this.server = server;
        this.executor = executor;
        // One limiter for navigation and writes together: the plan makes them the same budget, so a page that
        // spins on writes cannot spend a different allowance from a page that spins on clicks.
        RateLimiter limiter = new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS,
                Clock.SYSTEM);
        this.navigator = new Navigator(config.project().toString(), host, true, limiter);
        this.pageServer = new PageServer(config.project().toString(), true);
        this.editService = new EditService(config.project().toString(),
                // Journalled, so a page that reloads — or a host that restarts — still has its undo. The files
                // live with the descriptor, under the project's .jcodebuddy/webview/, which git ignores.
                CheckpointStore.persistent(
                        HostDescriptor.directoryOf(config.project()).resolve("checkpoints"),
                        CheckpointStore.DEFAULT_LIMIT),
                limiter);
        this.writeSurface = new WriteSurface(editService, host);
        this.origins = origins;
        this.token = token;
        this.tokenFile = tokenFile;
        this.project = config.project();
        this.port = port;
        this.host = host;
        // The transport a page served by this host uses: an image beacon to our own /open, which needs no
        // CORS grant and leaves no visible navigation. Same contract, different mechanism - which is why
        // InjectedBridge takes the statement instead of hard-coding one.
        this.injectedBridge = InjectedBridge.script(
                "var beacon = new Image(); beacon.src = '/open?filePath=' + encodeURIComponent(path)"
                        + " + '&line=' + (line || 1) + '&column=' + (column || 1);");
    }

    /**
     * Binds the socket and starts serving.
     *
     * @param config the parsed command line; its project must exist
     * @param host   the adapter every verb funnels into
     */
    public static WebviewServer start(WebviewdConfig config, EditorHost host) throws IOException {
        return start(config, host, config.port());
    }

    /**
     * The same, on a port the caller has already claimed.
     *
     * <p>{@link HostPortClaim} decides which port this is; the caller passes the answer rather than
     * {@code config.port()}, because the port that was free when the claim was made is the port that must be
     * bound, and re-reading the config would silently bind the one that was taken.
     */
    public static WebviewServer start(WebviewdConfig config, EditorHost host, int claimedPort)
            throws IOException {
        if (!Files.isDirectory(config.project())) {
            throw new IOException("--project is not a directory: " + config.project());
        }
        // Loopback only, and never 0.0.0.0: the frozen contract's first security rule.
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), claimedPort);
        HttpServer server = HttpServer.create(address, 0);
        int port = server.getAddress().getPort();

        // The host's own origin is trusted, so the pages it serves can drive it without configuration; every
        // other page still has to be named or hold the token.
        String self = "http://127.0.0.1:" + port + ",http://localhost:" + port;
        String configured = config.allowedOrigins() == null || config.allowedOrigins().isBlank()
                ? self : self + "," + config.allowedOrigins();
        AllowedOrigins origins = AllowedOrigins.of(configured);

        Path tokenFile = resolveTokenFile(config);
        String token = config.token() != null && !config.token().isBlank()
                ? config.token() : generateToken(tokenFile);

        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "webviewd-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);

        WebviewServer instance = new WebviewServer(server, executor, config, host, port, token, origins, tokenFile);
        server.createContext("/health", instance::handleHealth);
        server.createContext("/open", instance::handleOpen);
        server.createContext(PageServer.ROUTE_PREFIX, instance::handleFile);
        server.createContext(PAGE_ROUTE_PREFIX, instance::handlePage);
        server.createContext(MANIFEST_ROUTE, instance::handleManifest);
        server.createContext("/api/v1/applyEdit", instance::handleApplyEdit);
        server.createContext("/api/v1/diff", instance::handleDiff);
        server.createContext("/api/v1/undo", instance::handleUndo);
        server.createContext("/api/v1/redo", instance::handleRedo);
        server.createContext("/api/v1/events", instance::handleEvents);
        server.createContext("/", instance::handleIndex);
        server.start();
        return instance;
    }

    /** The adapter the config asks for; an explicit choice refuses to fall back silently. */
    public static EditorHost selectHost(WebviewdConfig config) {
        return selectHost(config, new HttpSidecarClient(config.sidecarPort(), config.sidecarToken()));
    }

    /**
     * The same decision with the sidecar bridge supplied, which is what lets the preference order be tested
     * without a sidecar process: {@code auto} takes the LSP route when an editor is attached to it, because
     * that is the only route on Windows that can place a caret.
     */
    static EditorHost selectHost(WebviewdConfig config, SidecarClient sidecar) {
        return switch (config.host()) {
            case NONE -> NullHost.INSTANCE;
            case LSP -> {
                LspHost lsp = LspHost.discover(sidecar);
                if (!lsp.isAvailable()) {
                    throw new IllegalStateException("--host lsp was requested but the sidecar at "
                            + lsp.sidecarDescription() + " advertises no navigation capability: it is either"
                            + " not running (start it, or the Zed extension starts it) or no LSP client has"
                            + " attached yet. Its /health must list \"open\" before this host will route to it");
                }
                yield lsp;
            }
            case AUTO -> {
                // LSP first: it is the only adapter on Windows that can place a caret (Phase 0, section B).
                LspHost lsp = LspHost.discover(sidecar);
                if (lsp.isAvailable()) {
                    yield lsp;
                }
                ZedCliHost zed = ZedCliHost.detect();
                yield zed.isAvailable() ? zed : NullHost.INSTANCE;
            }
            case ZED_CLI -> {
                ZedCliHost zed = ZedCliHost.detect();
                if (!zed.isAvailable()) {
                    throw new IllegalStateException("--host zed-cli was requested but no Zed CLI was found "
                            + "on the PATH; put Zed's installation directory on the PATH or use --host auto");
                }
                yield zed;
            }
        };
    }

    /** The port actually bound, which is what a caller needs when the config asked for 0. */
    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public String token() {
        return token;
    }

    public Path tokenFile() {
        return tokenFile;
    }

    public AllowedOrigins origins() {
        return origins;
    }

    public EditorHost host() {
        return host;
    }

    public Set<String> capabilities() {
        return navigator.capabilities();
    }

    /** The document a page reads from {@code /health}; also what the descriptor and manifest carry. */
    public String healthJson() {
        // The project path is in the document because the port-claim protocol needs it: a second host that
        // finds this port taken asks this question and must be able to tell "another host for my project"
        // from "a host for somebody else's", which is the difference between not starting and taking the
        // next port.
        return HostHealth.of(PLUGIN, IDE, project.toString(), port, origins.values().size(), token != null,
                navigator.capabilities()).toJson();
    }

    /** The manifest {@code /.well-known/webview.json} answers with (and {@code --print-manifest} prints). */
    public String manifestJson() {
        return manifestJson(project, host, port, tokenFile);
    }

    /**
     * The manifest for a host that may not have bound a socket yet, which is what {@code --print-manifest}
     * needs: the point of printing it is to see what <em>would</em> be published, including on a machine with
     * no editor attached.
     */
    public static String manifestJson(Path project, EditorHost host, int port, Path tokenFile) {
        return manifestJson(project, IDE, host, port, tokenFile);
    }

    /** The same, for a host that names itself: {@code ide} is the human name {@code /health} also answers. */
    public static String manifestJson(Path project, String ide, EditorHost host, int port, Path tokenFile) {
        Set<String> capabilities = capabilitiesOf(host);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("plugin", PLUGIN);
        manifest.put("ide", ide);
        manifest.put("port", port);
        manifest.put("project", project.toString().replace('\\', '/'));
        manifest.put("tokenPath", tokenFile.toString().replace('\\', '/'));
        manifest.put("tokenRequired", true);
        manifest.put("bridgeVersion", InjectedBridge.VERSION);
        manifest.put("capabilities", new ArrayList<>(capabilities));
        Map<String, Object> hostDetail = new LinkedHashMap<>();
        hostDetail.put("name", host.name());
        hostDetail.put("available", host.isAvailable());
        hostDetail.put("lineNavigation", lineNavigationOf(host));
        hostDetail.put("note", lineNavigationNoteOf(host));
        manifest.put("host", hostDetail);
        return GSON.toJson(manifest);
    }

    /** The capabilities a page may assume, given an adapter: empty while nothing is attached. */
    static Set<String> capabilitiesOf(EditorHost host) {
        return host.isAvailable() ? host.capabilities() : Set.of();
    }

    /**
     * How precisely this host can reach a position: {@code exact}, {@code file-only} or {@code none}. A page
     * reads it instead of assuming that {@code open} implies a caret. Since 2026-09-25 the answer comes from
     * the adapter itself ({@link EditorHost#lineNavigation()}), so a new adapter cannot forget it.
     */
    public String lineNavigation() {
        return lineNavigationOf(host);
    }

    static String lineNavigationOf(EditorHost host) {
        return host.lineNavigation();
    }

    private String lineNavigationNote() {
        return lineNavigationNoteOf(host);
    }

    static String lineNavigationNoteOf(EditorHost host) {
        return host.lineNavigationNote();
    }

    /** The descriptor that lets a page find this host without being told the port. */
    public HostDescriptor descriptor() {
        return descriptor(false);
    }

    /**
     * The same, recording whether this port is pinned for the project.
     *
     * <p>{@code sticky} is passed in rather than derived here because it is the caller's decision: the
     * standalone host reads it from the project's own record and from its command line, and the file is the
     * only place a later start can learn it.
     */
    public HostDescriptor descriptor(boolean sticky) {
        List<String> capabilities = new ArrayList<>(navigator.capabilities());
        HostDescriptor.HostDetail detail = new HostDescriptor.HostDetail(host.name(), host.isAvailable(),
                lineNavigation(), lineNavigationNote());
        return HostDescriptor.of(project, PLUGIN, IDE, port, sticky, tokenFile.toString(), capabilities, detail);
    }

    // --- request handling ---------------------------------------------------------------------------

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
        // The page server decides and reads; the only difference between /file/ and /page/ is that a page
        // gets the bridge appended, so the page can call window.openFile without shipping the script itself.
        String translated = PageServer.ROUTE_PREFIX + path.substring(PAGE_ROUTE_PREFIX.length());
        PageServer.Response response = pageServer.serve(translated, injectedBridge);
        announceDigest(exchange, response);
        send(exchange, response.httpStatus(), response.contentType(), response.body());
    }

    /**
     * Tells the page which bytes it just read, so it never has to recompute a digest — and, more importantly,
     * so the digest it proposes an edit with is the host's own idea of the file rather than a second
     * implementation of the same hash.
     *
     * <p>Both spellings are sent: {@code ETag} (quoted, per HTTP) for anything that speaks HTTP, and
     * {@code X-WebView-Digest} (exactly the string the edit API wants) for the page's client.
     */
    private void announceDigest(HttpExchange exchange, PageServer.Response response) {
        if (response.fileDigest() == null || response.fileDigest().isEmpty()) {
            return;
        }
        exchange.getResponseHeaders().set("ETag", "\"" + response.fileDigest() + "\"");
        exchange.getResponseHeaders().set("X-WebView-Digest", response.fileDigest());
    }

    /**
     * {@code POST /api/v1/applyEdit}: the write contract's one entry point.
     *
     * <p>Two things distinguish it from {@code /open}. It requires the **token**, not merely an allowed origin:
     * a state-changing route must not be reachable by any page the reader happens to have open (plan D8), and a
     * page that was served by this host can present it. And the routing itself — buffer or disk, the digest
     * guard, the statuses — belongs to {@link WriteSurface}, so the JetBrains host answers the same way instead
     * of growing a second set of rules.
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

    /** One line to stderr for anything a page was refused, so the host's log explains the page's error. */
    private void noteIfRefused(String route, WriteSurface.Answer answer) {
        if (!answer.ok()) {
            note(route + " refused with " + answer.status() + ": " + answer.body().replaceAll("\\s+", " "));
        }
    }

    /**
     * {@code GET /api/v1/events}: a stream of what changed under the project.
     *
     * <p>Requires the token as well: the stream names every file a page touched, which is more than the frozen
     * contract exposes to an arbitrary origin. The loop ends when the client goes away, which is reported as an
     * {@link IOException} on write — the normal way a server learns a browser tab closed.
     */
    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!authorizeWrite(exchange)) {
            return;
        }
        if (!requireGet(exchange)) {
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody();
             ProjectEventStream events = ProjectEventStream.of(project)) {
            if (events.truncated()) {
                note("the project has more than " + ProjectEventStream.MAX_DIRECTORIES
                        + " directories; the event stream watches the first " + events.watchedDirectoryCount());
            }
            while (true) {
                String frame = events.awaitFrame(EVENT_KEEPALIVE_MS);
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGone) {
            // The page closed the stream, or the host is shutting down. Neither is an error to report.
        }
    }

    private void answerEdits(HttpExchange exchange, EditService.Outcome outcome) throws IOException {
        int status = WriteSurface.statusOf(outcome.reason());
        if (status != 200) {
            note("edit refused " + outcome.filePath() + ": " + WriteSurface.reasonName(outcome.reason())
                    + (outcome.detail().isEmpty() ? "" : " (" + outcome.detail() + ")"));
        }
        sendJson(exchange, status, WriteSurface.bodyOf(outcome));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // The path is checked before the method, and the order is the whole point: a POST to a route that does
        // not exist is a 404 ("there is no such thing"), while only a route that *does* exist can be the subject
        // of a 405 ("not with that method"). The other order made every unknown /api/v1/* answer 405, which sends
        // a reader looking for a method mistake that is not there. Found by the capability gate, which asserts
        // that 403 and 404 stay distinguishable.
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(exchange, 404, "text/plain", bytes("Not found: " + path));
            return;
        }
        if (!requireGet(exchange)) {
            return;
        }
        String html = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>webviewd</title>
                <style>body{font:14px/1.5 system-ui,sans-serif;margin:2rem;max-width:52rem}
                code{background:#eee;padding:.1rem .3rem;border-radius:3px}</style></head>
                <body>
                <h1>webviewd</h1>
                <p>Serving <code>%s</code> on port <code>%d</code>.</p>
                <ul>
                  <li><a href="/health">/health</a> — the document a page reads to decide which rung of its
                      ladder to use.</li>
                  <li><a href="%s">/.well-known/webview.json</a> — port, token path, capabilities, and how
                      precisely this host can reach a line.</li>
                  <li><code>/page/&lt;path&gt;</code> — a project page with the bridge injected.</li>
                  <li><code>/file/&lt;path&gt;</code> — a project file as bytes.</li>
                  <li><code>/open?filePath=…&amp;line=…&amp;column=…</code> — the frozen navigation route.</li>
                </ul>
                <p>Editor adapter: <code>%s</code> (%s). Capabilities: <code>%s</code>.</p>
                </body></html>
                """.formatted(project.toString().replace('\\', '/'), port, MANIFEST_ROUTE, host.name(),
                lineNavigation(), capabilities().isEmpty() ? "none" : String.join(", ", capabilities()));
        send(exchange, 200, "text/html; charset=utf-8", bytes(html));
    }

    /**
     * GET only, and an OPTIONS preflight is answered rather than refused — a page that sends the token in a
     * header needs the preflight to succeed, and a preflight carries no credentials of its own.
     */
    private boolean requireGet(HttpExchange exchange) throws IOException {
        return requireGet(exchange, false);
    }

    private boolean requireGet(HttpExchange exchange, boolean allowOptions) throws IOException {
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method) && allowOptions) {
            applyCors(exchange);
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "X-WebView-Token");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            send(exchange, 405, "text/plain", bytes("Method Not Allowed"));
            return false;
        }
        return true;
    }

    /**
     * The contract's authorization rule, in one place: an allowed {@code Origin} <b>or</b> the configured
     * token, and an empty allow-list with no token denies everyone.
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
        if (token != null && token.equals(presented)) {
            return true;
        }
        if (origins.allows(exchange.getRequestHeaders().getFirst("Origin"))) {
            return true;
        }
        note("refused " + exchange.getRequestURI().getPath() + ": no allowed Origin and no valid token");
        send(exchange, 403, "text/plain",
                bytes("Forbidden: configure webview.explorer.allowedOrigins or webview.explorer.token"));
        return false;
    }

    /** CORS headers go to an allowed origin only, and are echoed rather than wildcarded. */
    private void applyCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origins.allows(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Vary", "Origin");
        }
    }

    /**
     * POST only, for the write routes. A page that uses {@code fetch} with a token header is doing a
     * non-simple request, so the preflight has to be answered rather than refused.
     */
    private boolean requirePost(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (origins.allows(exchange.getRequestHeaders().getFirst("Origin"))) {
                applyCors(exchange);
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "X-WebView-Token, Content-Type");
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(403, -1);
            }
            exchange.close();
            return false;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", bytes("Method Not Allowed"));
            return false;
        }
        return true;
    }

    /**
     * The authorization for a <b>state-changing</b> route: the token, and nothing else.
     *
     * <p>This is deliberately stricter than {@link #authorize} (which accepts an allowed {@code Origin} as an
     * alternative). Any page in the reader's browser can share an origin rule, and plan D8 says no
     * state-changing route may be reachable without proving possession of the secret. A host with no token
     * configured therefore refuses writes outright rather than falling back to the weaker rule.
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

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
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

    // --- token and lifecycle ------------------------------------------------------------------------

    private static Path resolveTokenFile(WebviewdConfig config) {
        return HostDescriptor.directoryOf(config.project()).resolve("token");
    }

    /**
     * Generates a token and stores it where only the user can read it. On Windows the {@code 0600} attempt
     * fails (there is no POSIX permission model) and is ignored: the file sits in the user's own profile, and
     * the descriptor deliberately does not contain the secret.
     */
    private static String generateToken(Path tokenFile) throws IOException {
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

    private static void note(String message) {
        System.err.println("webviewd: " + message);
    }

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
