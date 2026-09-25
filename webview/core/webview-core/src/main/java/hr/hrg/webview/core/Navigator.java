package hr.hrg.webview.core;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The one place a page's request becomes a host call: rate limit → resolve → jail → host.
 *
 * <p>Both transports of every host funnel through here, which is the property the first JetBrains
 * implementation lost: it rate-limited inside its JCEF navigation path <em>and</em> again inside its HTTP
 * handler, so one click could be charged twice, and the two paths could drift apart. Here the limit is
 * acquired exactly once per request, for every entry point.
 *
 * <p>The rate limit is acquired before the path is resolved on purpose: an attacker guessing paths
 * should not get free path-validation work out of the host, and a burst of garbage must count against
 * the same budget as a burst of real clicks.
 *
 * <p>{@link #requireConfinedPaths} is the switch between the two legitimate policies. A JetBrains tool
 * window drives its own IDE and may open any absolute path the user could open by hand, so it leaves it
 * false. A host that serves pages to a browser — the sidecar — must set it true, because the caller may
 * be any page the user has open, and the frozen contract's rule for that surface is to refuse anything
 * outside the project.
 */
public final class Navigator {

    /** At most this many navigations per {@link #RATE_LIMIT_WINDOW_MS}, shared by every transport. */
    public static final int RATE_LIMIT_COUNT = 20;
    public static final long RATE_LIMIT_WINDOW_MS = 20_000L;

    private final PathResolver resolver;
    private final RateLimiter rateLimiter;
    private final boolean requireConfinedPaths;

    private EditorHost host;

    /** A navigator with the production clock and the shared 20-per-20s policy. */
    public Navigator(String projectRoot, EditorHost host, boolean requireConfinedPaths) {
        this(projectRoot, host, requireConfinedPaths,
                new RateLimiter(RATE_LIMIT_COUNT, RATE_LIMIT_WINDOW_MS, Clock.SYSTEM));
    }

    public Navigator(String projectRoot, EditorHost host, boolean requireConfinedPaths,
                     RateLimiter rateLimiter) {
        this.resolver = PathResolver.forProject(projectRoot);
        this.host = Objects.requireNonNull(host, "host");
        this.requireConfinedPaths = requireConfinedPaths;
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    }

    /**
     * Swaps the host. A sidecar starts as {@link NullHost} and gains a real host when an editor attaches
     * — by LSP handshake, or by a CLI being found on the PATH — which is why the host is mutable while
     * the policy is not.
     */
    public void setHost(EditorHost newHost) {
        this.host = Objects.requireNonNull(newHost, "host");
    }

    public EditorHost host() {
        return host;
    }

    public boolean requireConfinedPaths() {
        return requireConfinedPaths;
    }

    /** The capabilities a page may assume right now: empty while no host is attached. */
    public Set<String> capabilities() {
        EditorHost current = host;
        return current.isAvailable() ? current.capabilities() : Set.of();
    }

    /** Opens a file at a one-based line and column. */
    public NavigationOutcome open(String filePath, int line, int column) {
        Verdict verdict = check(filePath);
        if (verdict.refusal() != null) {
            return verdict.refusal();
        }
        PathResolution resolution = verdict.resolution();
        return act(resolution, EditorHost.CAP_OPEN,
                () -> host.openFileAt(resolution.absolute(), Math.max(1, line), Math.max(1, column)));
    }

    /** Reveals a file in the host's own navigation UI. */
    public NavigationOutcome reveal(String filePath) {
        Verdict verdict = check(filePath);
        if (verdict.refusal() != null) {
            return verdict.refusal();
        }
        PathResolution resolution = verdict.resolution();
        return act(resolution, EditorHost.CAP_REVEAL, () -> host.reveal(resolution.absolute()));
    }

    /** Selects a span. */
    public NavigationOutcome select(String filePath, TextRange range) {
        Verdict verdict = check(filePath);
        if (verdict.refusal() != null) {
            return verdict.refusal();
        }
        PathResolution resolution = verdict.resolution();
        TextRange clamped = range == null ? TextRange.at(1, 1) : range.clamped();
        return act(resolution, EditorHost.CAP_SELECT,
                () -> host.select(resolution.absolute(), clamped));
    }

    /**
     * Reads a {@code #L42} fragment (or a bare number) out of a URL fragment; 1 when it names no line.
     * Moved here from the JetBrains host because the sidecar needs the same rule for links it is sent.
     */
    public static int lineFromFragment(String fragment) {
        if (fragment == null || fragment.isEmpty()) {
            return 1;
        }
        String digits = fragment.startsWith("L") || fragment.startsWith("l")
                ? fragment.substring(1) : fragment;
        try {
            return Math.max(1, Integer.parseInt(digits.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Opens a URL: the line comes from a fragment when there is one, and a {@code file:} URL is turned
     * into the path behind it.
     */
    public NavigationOutcome openUrl(String url) {
        if (url == null || url.isBlank()) {
            return NavigationOutcome.refused(
                    NavigationOutcome.Reason.INVALID_PATH, url, "empty URL");
        }
        String path = url;
        int line = 1;
        int hash = url.indexOf('#');
        if (hash >= 0) {
            path = url.substring(0, hash);
            line = lineFromFragment(url.substring(hash + 1));
        }
        String localPath = UrlNormalizer.localPathOf(path);
        return open(localPath != null ? localPath : path, line, 1);
    }

    /**
     * Either the reason this request is refused outright, or the resolution it may proceed with. A
     * two-field record rather than a nullable return, because "rate limited" and "not a usable path"
     * must stay distinguishable all the way to the HTTP status the caller sees.
     */
    private record Verdict(PathResolution resolution, NavigationOutcome refusal) {

        static Verdict proceed(PathResolution resolution) {
            return new Verdict(resolution, null);
        }

        static Verdict refuse(NavigationOutcome.Reason reason, String filePath, String detail) {
            return new Verdict(null, NavigationOutcome.refused(reason, filePath, detail));
        }
    }

    /** Rate limit, then resolve, then apply whichever jail policy this navigator was built with. */
    private Verdict check(String filePath) {
        if (!rateLimiter.tryAcquire()) {
            return Verdict.refuse(NavigationOutcome.Reason.RATE_LIMITED, filePath,
                    "rate limit of " + rateLimiter.limit() + " per "
                            + (rateLimiter.windowMillis() / 1000) + "s reached");
        }
        PathResolution resolution = resolver.resolve(filePath);
        if (resolution == null) {
            return Verdict.refuse(NavigationOutcome.Reason.INVALID_PATH, filePath,
                    "not a usable path");
        }
        if (requireConfinedPaths && resolution.escaped()) {
            return Verdict.refuse(NavigationOutcome.Reason.OUTSIDE_PROJECT, resolution.absolute(),
                    "outside the project");
        }
        return Verdict.proceed(resolution);
    }

    /** Capability check, then the host call itself. */
    private NavigationOutcome act(PathResolution resolution, String capability, BooleanSupplier call) {
        EditorHost current = host;
        if (!current.isAvailable()) {
            return NavigationOutcome.refused(NavigationOutcome.Reason.NO_HOST,
                    resolution.absolute(), "no " + capability + " host is attached");
        }
        if (!current.capabilities().contains(capability)) {
            return NavigationOutcome.refused(NavigationOutcome.Reason.NO_HOST,
                    resolution.absolute(), "host '" + current.name() + "' cannot " + capability);
        }
        boolean acted = call.getAsBoolean();
        return acted
                ? NavigationOutcome.ok(resolution.absolute())
                : NavigationOutcome.refused(NavigationOutcome.Reason.HOST_REFUSED,
                        resolution.absolute(), "host '" + current.name() + "' refused");
    }
}
