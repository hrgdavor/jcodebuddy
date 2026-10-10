package hr.hrg.webview.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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
 * <p><b>Every navigation is confined to the project root.</b> There used to be a
 * {@code requireConfinedPaths} switch, on the argument that a JetBrains tool window drives its own IDE and
 * may open any absolute path the user could open by hand. The maintainer removed that argument on
 * 2026-10-04: a webview plugin may not reach any file outside the project root, and a switch a caller can
 * set to false is a jail with an off switch. The consequence is deliberate and is written down rather than
 * discovered: the address bar is confined too, so a person cannot walk out of the project from the tool
 * window either.
 */
public final class Navigator {

    /** At most this many navigations per {@link #RATE_LIMIT_WINDOW_MS}, shared by every transport. */
    public static final int RATE_LIMIT_COUNT = 20;
    public static final long RATE_LIMIT_WINDOW_MS = 20_000L;

    private final PathResolver resolver;
    private final RateLimiter rateLimiter;

    private EditorHost host;

    /** A navigator with the production clock and the shared 20-per-20s policy. */
    /** A navigator with the production clock and the shared 20-per-20s policy. */
    public Navigator(String projectRoot, EditorHost host) {
        this(projectRoot, host, new RateLimiter(RATE_LIMIT_COUNT, RATE_LIMIT_WINDOW_MS, Clock.SYSTEM));
    }

    public Navigator(String projectRoot, EditorHost host, RateLimiter rateLimiter) {
        this.resolver = PathResolver.forProject(projectRoot);
        this.host = Objects.requireNonNull(host, "host");
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


    /** The capabilities a page may assume right now: empty while no host is attached. */
    public Set<String> capabilities() {
        EditorHost current = host;
        return current.isAvailable() ? current.capabilities() : Set.of();
    }

    /** Opens a file at a one-based line and column. */
    public NavigationOutcome open(String filePath, int line, int column) {
        return open(filePath, line, column, null);
    }

    /**
     * Opens a file at a position a page named, which may be a line or something only the file can answer: a
     * declaration, a region, a JSON key path (plan step 9.7).
     *
     * <p>The fragment wins over the line when it is a location, because it is the more specific thing the page said -
     * a page that also sends line 1 is sending the contract's own default, not an intention. A fragment that is not a
     * location (a heading anchor in a document, say) leaves the page's line standing, exactly as before.</p>
     *
     * <p><strong>A name the file does not have is refused, not aimed at line 1.</strong> A wrong line costs the
     * reader a search to discover, and looks like a link that worked. In a document, though, a bare name is the
     * viewer's own business (a heading anchor), so it falls back to the line rather than refusing.</p>
     */
    public NavigationOutcome open(String filePath, int line, int column, String fragment) {
        // A fragment may ride in the path itself: 'data-open="src/A.java#add"' is how a page spells a location, and
        // the frozen contract calls that attribute a path rather than a label. Splitting it HERE, in the one place
        // every host's request passes through, is what lets a page use the one spelling instead of learning which
        // host understands which. An explicit fragment argument wins when both are given.
        String pathOnly = filePath;
        String named = fragment;
        if (filePath != null) {
            int hash = filePath.indexOf('#');
            if (hash >= 0) {
                pathOnly = filePath.substring(0, hash);
                if (named == null || named.isEmpty()) {
                    named = filePath.substring(hash + 1);
                }
            }
        }
        Verdict verdict = check(pathOnly);
        if (verdict.refusal() != null) {
            return verdict.refusal();
        }
        PathResolution resolution = verdict.resolution();
        LocationFragment location = LocationFragment.parse(resolution.absolute(), named);
        if (location == null) {
            return act(resolution, EditorHost.CAP_OPEN,
                    () -> host.openFileAt(resolution.absolute(), Math.max(1, line), Math.max(1, column)));
        }
        String text = null;
        if (location.kind() != LocationFragment.Kind.LINE && location.kind() != LocationFragment.Kind.RANGE) {
            try {
                text = Files.readString(Path.of(resolution.absolute()), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException unreadable) {
                return NavigationOutcome.refused(NavigationOutcome.Reason.INVALID_PATH,
                        resolution.absolute(), "could not read the file: " + unreadable.getMessage());
            }
        }
        LocationResolution at = LocationResolver.resolve(location, text, resolution.absolute());
        if (at == null) {
            if (isDocument(resolution.absolute())) {
                // A bare name in a document is its heading anchor: the viewer knows better than we do.
                return act(resolution, EditorHost.CAP_OPEN,
                        () -> host.openFileAt(resolution.absolute(), Math.max(1, line), Math.max(1, column)));
            }
            return NavigationOutcome.refused(NavigationOutcome.Reason.LOCATION_NOT_FOUND,
                    resolution.absolute(), location.summary() + " is not in this file");
        }
        int target = Math.max(1, at.line());
        return act(resolution, EditorHost.CAP_OPEN,
                () -> host.openFileAt(resolution.absolute(), target, 1));
    }

    /** The extensions whose fragments are their own anchors rather than source locations. */
    private static boolean isDocument(String absolutePath) {
        String lower = absolutePath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".html")
                || lower.endsWith(".htm");
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
        String fragment = null;
        int hash = url.indexOf('#');
        if (hash >= 0) {
            path = url.substring(0, hash);
            fragment = url.substring(hash + 1);
            // The line is still read for the plain '#L42' case and as the fallback a non-location fragment leaves.
            line = lineFromFragment(fragment);
        }
        String localPath = UrlNormalizer.localPathOf(path);
        return open(localPath != null ? localPath : path, line, 1, fragment);
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
        if (resolution.escaped()) {
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
