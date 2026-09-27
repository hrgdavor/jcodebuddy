package hr.hrg.eclipse.webview.bridge;

import java.util.function.Supplier;

import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.NavigationOutcome;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.RateLimiter;

/**
 * The navigator front for the Eclipse host: it asks the platform which project is open, then hands
 * each request to the core's {@link Navigator} with a shared rate limiter. A request that arrives
 * while no project is open is refused with NO_HOST <em>before</em> any rate-limit budget is spent —
 * the page may retry once a project is open without having used up its budget, which is the rule
 * the plan fixes for a navigation that precedes the project.
 *
 * <p>The root supplier is injectable because the platform lookup it wraps calls
 * {@code PlatformUI}, which a JUnit test cannot run; the tests substitute a mutable supplier.
 */
public class EclipseNavigator {

    private final Supplier<String> rootSupplier;
    private final EditorHost host;
    private final RateLimiter rateLimiter;

    public EclipseNavigator() {
        this(WorkspaceFiles::activeProjectRoot, new EclipseEditorHost(),
                new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM));
    }

    /**
     * Testable constructor: a root supplier, an editor host and a rate limiter are supplied, so the
     * whole flow — no-host refusal, confinement, rate limit — runs without the platform.
     */
    public EclipseNavigator(Supplier<String> rootSupplier, EditorHost host, RateLimiter rateLimiter) {
        this.rootSupplier = rootSupplier;
        this.host = host;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Resolves the request against the open project and hands the result to the editor host. The
     * path, line and column are the page's spelling: one-based, and the navigator's rules decide
     * the outcome — no existence check here, the resolver is lenient on purpose.
     */
    public NavigationOutcome open(String path, int line, int column) {
        String root = rootSupplier.get();
        if (root == null || root.isBlank()) {
            return NavigationOutcome.refused(NavigationOutcome.Reason.NO_HOST, path, "no project is open");
        }
        return new Navigator(root, host, false, rateLimiter).open(path, line, column);
    }
}
