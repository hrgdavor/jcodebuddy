package hr.hrg.eclipse.webview;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.BundleContext;

import hr.hrg.eclipse.webview.bridge.EclipseEditorHost;
import hr.hrg.eclipse.webview.http.BridgeStartup;
import hr.hrg.eclipse.webview.http.EclipseHttpBridge;
import hr.hrg.eclipse.webview.http.UiThreadHost;
import hr.hrg.eclipse.webview.prefs.WebViewPreferences;
import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.HostConfig;
import hr.hrg.webview.core.HostPortClaim;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.RateLimiter;

/**
 * The bundle's activator, and the owner of everything the host's two entry points must share:
 * one editor host — wrapped so calls from any thread arrive on the display thread (plan R19) — one
 * rate-limit budget, which is what stops the injected bridge and the HTTP transport from charging a
 * page twice for one click, and the set of live bridges, at most one per served project (E7).
 *
 * <p>The state is deliberately instance state reached through a static handle: the platform
 * constructs exactly one activator per bundle, and a second one would mean two budgets and two
 * registries of sockets in one workbench — the failure the shared core exists to prevent.
 */
public class WebViewPlugin extends Plugin {

    /** The bundle's symbolic name, which plugin.xml carries as its id. */
    public static final String PLUGIN_ID = "hr.hrg.eclipse.webview";

    private static WebViewPlugin instance;

    private final Object bridgeLock = new Object();

    /** Running bridges by served project. */
    private final Map<Path, EclipseHttpBridge> bridges = new HashMap<>();

    /** In-flight start attempts by project; a caller that finds one queues behind it (E7, first bind wins). */
    private final Map<Path, BridgeStartup> startups = new HashMap<>();

    /**
     * Bumped whenever a running bridge could have been started from stale preferences. A settled job
     * from an older generation finds its bridge unwanted and closes it itself, rather than the
     * restart racing it for the map.
     */
    private long generation;

    private EditorHost editorHost;
    private RateLimiter rateLimiter;

    /**
     * The platform's activator methods declare checked exceptions on this train, so the overrides
     * carry them as well.
     */
    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        shutDownBridges();
        instance = null;
        super.stop(context);
    }

    /** The running plugin, or null when the platform is not up. */
    public static WebViewPlugin getInstance() {
        return instance;
    }

    // --- shared editor state -------------------------------------------------------------------------

    /** The one wrapped editor host: page bridge, HTTP routes and actions all drive this instance. */
    public EditorHost editorHost() {
        synchronized (bridgeLock) {
            if (editorHost == null) {
                editorHost = new UiThreadHost(new EclipseEditorHost(), Display.getDefault());
            }
            return editorHost;
        }
    }

    /** The one rate budget: 20 navigations per 20 seconds across every transport (core Navigator policy). */
    public RateLimiter rateLimiter() {
        synchronized (bridgeLock) {
            if (rateLimiter == null) {
                rateLimiter = new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS,
                        Clock.SYSTEM);
            }
            return rateLimiter;
        }
    }

    // --- bridges ---------------------------------------------------------------------------------------

    /** The bridge running for this project right now, or null. The view's page-versus-file decision. */
    public EclipseHttpBridge bridgeFor(Path project) {
        synchronized (bridgeLock) {
            return bridges.get(HostPortClaim.comparable(project));
        }
    }

    /**
     * Brings a bridge up for this project and calls back with it — or with null, when the project
     * stays without one: no port named (E17), another host already serves it (E7), or a pinned port
     * refused to move. The callback always runs, on the UI thread; if a bridge is already running
     * for the project it runs inline, which is why this method requires a UI-thread caller.
     *
     * <p>Everything slow — the port resolution walk, the probes, the bind — happens on a
     * {@link BridgeStartup} job; the first caller schedules it, later callers for the same project
     * join it. The preferences are read here, at the request, so a changed port takes effect on the
     * next request rather than requiring the workbench to restart.
     */
    public void ensureBridge(Path project, Consumer<EclipseHttpBridge> onReady) {
        if (Display.getCurrent() == null) {
            throw new IllegalStateException("ensureBridge is a UI-thread call");
        }
        HostConfig.PortPreference portPreference = WebViewPreferences.bridgePort();
        if (!portPreference.problem().isEmpty()) {
            System.err.println("webview-eclipse: " + portPreference.problem());
        }
        Integer explicitPort = portPreference.present() ? portPreference.port().getAsInt() : null;
        String tokenOverride = WebViewPreferences.tokenOverride();
        String ideName = EclipseHttpBridge.ideName();
        Path key = HostPortClaim.comparable(project);

        BridgeStartup job;
        boolean scheduled = false;
        long captured;
        synchronized (bridgeLock) {
            EclipseHttpBridge running = bridges.get(key);
            if (running != null) {
                onReady.accept(running);
                return;
            }
            captured = generation;
            job = startups.get(key);
            if (job == null) {
                job = new BridgeStartup(key, explicitPort, tokenOverride, ideName,
                        editorHost(), rateLimiter(),
                        bridge -> settleBridge(key, captured, bridge));
                startups.put(key, job);
                scheduled = true;
            }
        }
        // Queue first, then start: scheduling hands the job to another thread, and a settle must not
        // be able to beat the caller into the queue.
        job.addWaiting(onReady);
        if (scheduled) {
            job.schedule();
        }
    }

    /**
     * Stops every bridge and forgets every in-flight attempt: a preference change to the port or the
     * token means the next request must re-claim, not reuse. Published descriptors stay in place —
     * they are the project's port, not this process's (DEC-033) — so the next start asks for the same
     * port it had, and the one-bridge-per-project rule survives the restart unchanged.
     */
    public void restartBridges() {
        List<EclipseHttpBridge> toClose = new ArrayList<>();
        List<BridgeStartup> toAbandon = new ArrayList<>();
        synchronized (bridgeLock) {
            generation++;
            toClose.addAll(bridges.values());
            bridges.clear();
            toAbandon.addAll(startups.values());
            startups.clear();
        }
        for (BridgeStartup job : toAbandon) {
            job.cancel();
            job.abandon();
        }
        for (EclipseHttpBridge bridge : toClose) {
            bridge.close();
        }
    }

    /**
     * The job's UI-thread settlement: register the bridge, or discard it — and answer every waiting
     * caller with the same verdict. A job from an older generation had its preferences superseded
     * while it ran, so its bridge is closed here rather than handed out.
     *
     * @return the bridge callers should use, or null when this attempt's answer is "no bridge"
     */
    private EclipseHttpBridge settleBridge(Path key, long capturedGeneration, EclipseHttpBridge bridge) {
        synchronized (bridgeLock) {
            startups.remove(key);
            if (bridge == null || capturedGeneration != generation) {
                if (bridge != null) {
                    bridge.close();
                }
                return null;
            }
            bridges.put(key, bridge);
            return bridge;
        }
    }

    private void shutDownBridges() {
        List<EclipseHttpBridge> running = new ArrayList<>();
        List<BridgeStartup> pending = new ArrayList<>();
        synchronized (bridgeLock) {
            generation++;
            running.addAll(bridges.values());
            bridges.clear();
            pending.addAll(startups.values());
            startups.clear();
        }
        for (BridgeStartup job : pending) {
            job.cancel();
            job.abandon();
        }
        for (EclipseHttpBridge bridge : running) {
            bridge.close();
        }
    }
}
