package hr.hrg.eclipse.phase0;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Phase 0 probe for the Eclipse host plan
 * ([{@code webview/PLAN-eclipse-host.md}](../PLAN-eclipse-host.md), § 7 Phase 0; experiments A–F in
 * {@code webview/eclipse/PHASE0-ECLIPSE-FINDINGS.md}).
 *
 * <p>Runs in a plain process — no Eclipse workbench, no OSGi — and prints one line per experiment. It
 * opens short-lived windows, so it needs a desktop session. Nothing here is production code: it writes
 * only into the {@code target/} directory that {@code probe.mjs} gives it on the classpath.
 */
public final class SwtProbe {

    /** The arguments the bridge functions received, in arrival order. */
    static final ConcurrentLinkedQueue<String> BRIDGE_SEEN = new ConcurrentLinkedQueue<>();
    /** The name of the thread that ran each bridge function, in arrival order. */
    static final ConcurrentLinkedQueue<String> BRIDGE_THREADS = new ConcurrentLinkedQueue<>();
    /** The {@code Origin} header the loopback server saw, in arrival order. */
    static final ConcurrentLinkedQueue<String> ORIGINS_SEEN = new ConcurrentLinkedQueue<>();

    static final String PAGE =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head>"
                    + "<body><p>probe loopback page</p></body></html>";

    static final String FILE_PAGE_TEMPLATE =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head>"
                    + "<body><p>probe file page</p><script>\n"
                    + "(async () => { try { const r = await fetch(\"http://127.0.0.1:PORT/origin\", {method: \"GET\"}); "
                    + "window.__df = \"status=\" + r.status + \" origin=\" + (await r.text()); "
                    + "} catch (e) { window.__df = \"error:\" + e; } })();\n"
                    + "</script></body></html>";

    public static void main(String[] args) {
        line("PROBE host java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name")
                + " arch=" + System.getProperty("os.arch"));

        Display display = null;
        Shell shell = null;
        Browser browser = null;
        BrowserFunction echo = null;
        BrowserFunction thrower = null;
        HttpServer server = null;

        try {
            try {
                display = new Display();
            } catch (Throwable t) {
                line("E fail — new Display() threw: " + t);
                line("A B C D not-observed — no SWT display in this environment");
                line("F " + findEclipse());
                return;
            }
            line("E ok — new Display() succeeded in a workbench-less process");

            shell = new Shell(display);
            shell.setLayout(new FillLayout());
            shell.setSize(960, 600);
            Composite parent = new Composite(shell, SWT.NONE);
            parent.setLayout(new FillLayout());
            ProgressRecorder progress = new ProgressRecorder();

            try {
                browser = new Browser(parent, SWT.EDGE);
            } catch (Throwable t) {
                line("A1 fail — new Browser(parent, SWT.EDGE) threw: " + t);
                line("B C D not-observed — no browser");
                line("F " + findEclipse());
                return;
            }
            browser.addProgressListener(progress.listener);

            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException t) {
                line("B C D fail — loopback server could not start: " + t);
                line("F " + findEclipse());
                return;
            }
            server.createContext("/", ex -> serve(ex, PAGE, 200));
            server.createContext("/origin", ex -> {
                String origin = ex.getRequestHeaders().getFirst("Origin");
                ORIGINS_SEEN.add(origin == null ? "(none)" : origin);
                byte[] body = (origin == null ? "(none)" : origin).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().set("Content-Type", "text/plain");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            int port = server.getAddress().getPort();
            line("loopback server on 127.0.0.1:" + port);

            shell.setVisible(true);

            // --- C, part 1: a data: splash page ---
            String splash = "data:text/html," + URLEncoder.encode("<html><body>splash</body></html>", StandardCharsets.UTF_8);
            progress.set(splash);
            browser.setUrl(splash);
            boolean splashReady = waitUntil(display, () -> progress.ready, 15000);
            line("C splash data: -> " + (splashReady ? "complete" : "timeout") + " events=" + progress.events);
            line("C splash content = " + evalSafe(browser, "return document.body ? document.body.innerText : '(no body)'"));
            progress.events.clear();

            // --- A1: the engine report ---
            line("A1 getBrowserType() = " + browser.getBrowserType());
            for (String v : edgeVersionInfo()) {
                line("A1 " + v);
            }
            Object ua = evalSafe(browser, "return navigator.userAgent");
            line("A1 userAgent = " + ua);
            line("A1 edge-obtained = " + (ua instanceof String s && s.toLowerCase().contains("edg")));

            // --- B: the BrowserFunction contract ---
            echo = registerEcho(browser);
            thrower = registerThrower(browser);
            String loopback = "http://127.0.0.1:" + port + "/";
            progress.set(loopback);
            browser.setUrl(loopback);
            boolean bReady = waitUntil(display, () -> progress.ready, 15000);
            line("C loopback -> " + (bReady ? "complete" : "timeout") + " events=" + progress.events);
            progress.events.clear();

            BRIDGE_SEEN.clear();
            BRIDGE_THREADS.clear();
            Object sync = evalSafe(browser, "return jcbProbe('B1-echo')");
            line("B1 sync-return via evaluate = " + sync + " | java saw arg=" + BRIDGE_SEEN.peek() + " thread=" + BRIDGE_THREADS);

            Object thrown = evalSafe(browser, "return (function() { try { jcbThrow('x'); return 'returned'; }"
                    + " catch (e) { return 'threw:' + e; } })()");
            line("B2 throw = " + thrown);

            // --- D, part 1: the same-origin (loopback-served) page ---
            evalSafe(browser, "(async () => { try { const r = await fetch(location.origin + '/origin'); "
                    + "window.__d = (await r.text()); } catch (e) { window.__d = 'error:' + e; } })();");
            String servedOrigin = pollString(display, browser, "return window.__d", 10000);
            line("D loopback-served page: server-saw-origin=" + servedOrigin + " originsSeen=" + ORIGINS_SEEN);

            // --- B, part 2: dispose, then re-register ---
            echo.dispose();
            thrower.dispose();
            echo = null;
            thrower = null;
            String afterDispose = loopback + "after-dispose";
            progress.set(afterDispose);
            browser.setUrl(afterDispose);
            boolean dReady = waitUntil(display, () -> progress.ready, 15000);
            Object after = evalSafe(browser, "return typeof jcbProbe");
            line("B3 after dispose: page=" + (dReady ? "complete" : "timeout") + " typeof jcbProbe = " + after);

            echo = registerEcho(browser);
            thrower = registerThrower(browser);
            String reregistered = loopback + "reregistered";
            progress.set(reregistered);
            browser.setUrl(reregistered);
            boolean rReady = waitUntil(display, () -> progress.ready, 15000);
            BRIDGE_SEEN.clear();
            Object again = evalSafe(browser, "return jcbProbe('B4-echo')");
            line("B4 after re-registration: page=" + (rReady ? "complete" : "timeout") + " sync-return = " + again
                    + " | java saw arg=" + BRIDGE_SEEN.peek());

            // --- D, part 2: the file:// page ---
            // The probe's class directory is target/classes; the page goes next to it in target/.
            String firstClassPathEntry = System.getProperty("java.class.path").split(java.io.File.pathSeparator)[0];
            Path filePage = Path.of(firstClassPathEntry).getParent().resolve("file-page.html");
            boolean fileWritten = true;
            try {
                Files.write(filePage, FILE_PAGE_TEMPLATE.replace("PORT", String.valueOf(port)).getBytes(StandardCharsets.UTF_8));
            } catch (IOException t) {
                line("D file:// not-observed — page write failed: " + t);
                fileWritten = false;
            }
            if (!fileWritten) {
                line("A2 not-run — there is no non-invasive way to hide the installed WebView2 runtime without "
                        + "modifying the system (registry/COM state), and the probe forbids system modification");
                line("F " + findEclipse());
                return;
            }
            String fileUrl = "file:///" + filePage.toAbsolutePath().toString().replace('\\', '/');
            progress.set(fileUrl);
            browser.setUrl(fileUrl);
            boolean fReady = waitUntil(display, () -> progress.ready, 15000);
            line("C file:// -> " + (fReady ? "complete" : "timeout") + " events=" + progress.events);
            String fileResult = pollString(display, browser, "return window.__df", 10000);
            line("D file:// page fetch = " + fileResult + " originsSeen=" + ORIGINS_SEEN);
            Object fileOriginHeader = evalSafe(browser, "return window.origin");
            line("D file:// page window.origin = " + fileOriginHeader);

            // --- A2: hiding the WebView2 runtime ---
            line("A2 not-run — there is no non-invasive way to hide the installed WebView2 runtime without "
                    + "modifying the system (registry/COM state), and the probe forbids system modification");

            // --- F: an installed Eclipse ---
            line("F " + findEclipse());

            line("PROBE done");
        } finally {
            try { if (echo != null) echo.dispose(); } catch (Throwable ignored) { }
            try { if (thrower != null) thrower.dispose(); } catch (Throwable ignored) { }
            try { if (browser != null) browser.dispose(); } catch (Throwable ignored) { }
            try { if (shell != null) shell.dispose(); } catch (Throwable ignored) { }
            try { if (display != null) display.dispose(); } catch (Throwable ignored) { }
            try { if (server != null) server.stop(0); } catch (Throwable ignored) { }
        }
    }

    static BrowserFunction registerEcho(Browser browser) {
        return new BrowserFunction(browser, "jcbProbe") {
            @Override
            public Object function(Object[] arguments) {
                BRIDGE_THREADS.add(Thread.currentThread().getName());
                String arg = arguments != null && arguments.length > 0 && arguments[0] != null
                        ? String.valueOf(arguments[0])
                        : "(none)";
                BRIDGE_SEEN.add(arg);
                return "echo:" + arg;
            }
        };
    }

    static BrowserFunction registerThrower(Browser browser) {
        return new BrowserFunction(browser, "jcbThrow") {
            @Override
            public Object function(Object[] arguments) {
                throw new IllegalStateException("probe throw");
            }
        };
    }

    /**
     * Pump the display until {@code check} is true or the timeout passes. SWT events are delivered on
     * the UI thread, so a bare sleep would starve the listeners; the pump is the whole trick.
     */
    static boolean waitUntil(Display display, BooleanSupplier check, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.getAsBoolean()) return true;
            if (!display.readAndDispatch()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    return check.getAsBoolean();
                }
            }
        }
        return check.getAsBoolean();
    }

    static String pollString(Display display, Browser browser, String js, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Object v = evalSafe(browser, js);
            if (!"null".equals(v)) return String.valueOf(v);
            if (!display.readAndDispatch()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    return "timeout";
                }
            }
        }
        return "timeout";
    }

    static String evalSafe(Browser browser, String js) {
        try {
            Object v = browser.evaluate(js);
            return v == null ? "null" : String.valueOf(v);
        } catch (Throwable t) {
            return "eval-threw:" + t;
        }
    }

    /**
     * What SWT publishes about the engine actually in use. There is no {@code EdgeVersion} class in
     * SWT 3.135.0: the version string is a system property that SWT itself sets —
     * {@code System.setProperty("org.eclipse.swt.browser.EdgeVersion", ...)} from
     * {@code ICoreWebView2Environment.get_BrowserVersionString()} — after the first Edge browser is
     * created, so it is readable only after that.
     */
    static List<String> edgeVersionInfo() {
        List<String> out = new ArrayList<>();
        out.add("no EdgeVersion class in SWT 3.135.0 — the version is a system property SWT sets itself");
        out.add("org.eclipse.swt.browser.EdgeVersion = "
                + System.getProperty("org.eclipse.swt.browser.EdgeVersion"));
        return out;
    }

    static void serve(HttpExchange ex, String body, int code) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /**
     * F: look for an installed Eclipse under the usual roots. Finds it by {@code eclipse.exe} and reads
     * the platform version from {@code plugins/org.eclipse.core.runtime_<version>.jar}. Does not launch
     * anything.
     */
    static String findEclipse() {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("C:\\Program Files"));
        roots.add(Path.of("C:\\Program Files (x86)"));
        String home = System.getProperty("user.home");
        if (home != null) roots.add(Path.of(home, "AppData", "Local", "Programs"));
        roots.add(Path.of("D:\\programs"));

        List<String> scanned = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            scanned.add(root.toString());
            try (Stream<Path> children = Files.list(root)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    if (!Files.isDirectory(child)) continue;
                    if (!child.getFileName().toString().toLowerCase().contains("eclipse")) continue;
                    Path exe = null;
                    try (Stream<Path> inner = Files.list(child)) {
                        for (Path f : (Iterable<Path>) inner::iterator) {
                            if (f.getFileName().toString().equalsIgnoreCase("eclipse.exe")) exe = f;
                        }
                    } catch (IOException ignored) { }
                    if (exe != null) return "found " + exe + " version=" + eclipseVersion(child);
                }
            } catch (IOException ignored) { }
        }
        return "not-found — scanned " + scanned;
    }

    static String eclipseVersion(Path eclipseDir) {
        Path plugins = eclipseDir.resolve("plugins");
        if (!Files.isDirectory(plugins)) return "unknown";
        try (Stream<Path> files = Files.list(plugins)) {
            List<String> versions = new ArrayList<>();
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (n.startsWith("org.eclipse.core.runtime_") && n.endsWith(".jar")) {
                    versions.add(n.substring("org.eclipse.core.runtime_".length(), n.length() - 4));
                }
            }
            if (!versions.isEmpty()) return String.join(",", versions);
        } catch (IOException ignored) { }
        return "unknown";
    }

    static final class ProgressRecorder {
        final List<String> events = new ArrayList<>();
        volatile boolean ready;
        String current;

        void set(String url) {
            current = url;
            ready = false;
        }

        // SWT 3.135's ProgressListener has changed(ProgressEvent)/completed(ProgressEvent); the
        // event carries no url or type of its own, so the URL is read off the browser on the UI
        // thread, and the ready latch matches against the URL main set before setUrl.
        final ProgressListener listener =
                ProgressListener.completedAdapter(e -> {
                    String url = e.widget instanceof Browser b ? b.getUrl() : String.valueOf(e.widget);
                    events.add("complete url=" + url);
                    // For data: URLs getUrl() reports empty at completion time, so an empty URL
                    // counts as ready when the navigation that was issued was a data: URL.
                    if (url.equals(current) || (url.isEmpty() && current.startsWith("data:"))) ready = true;
                });
    }

    static void line(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
