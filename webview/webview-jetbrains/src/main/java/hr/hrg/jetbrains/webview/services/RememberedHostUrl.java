package hr.hrg.jetbrains.webview.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Rewrites the page URL a host remembered when the port it serves on moves.
 *
 * <p>A host publishes the port it actually bound in {@code .jcodebuddy/webview/host.json}, and the IDE host
 * also writes that port back into its own project setting. The remembered URL is a separate piece of state:
 * it names the old port (and the page token from the old process). Without this rewrite a restart that had to
 * take the next port restores a dead URL and the page cannot load.</p>
 *
 * <p>The rewrite is deliberately narrow: only an {@code http(s)} URL on a loopback host, under the app/page
 * routes, and only when its port is the host's previous port or the current one. A custom server on another
 * loopback port is left alone — it is not this host's URL, and changing it would point it at the wrong
 * process. A {@code file:} URL (the Markdown page) is unaffected.</p>
 */
public final class RememberedHostUrl {

    private RememberedHostUrl() {
    }

    /**
     * @param url          the remembered URL, or null/blank
     * @param previousPort the port this host published before this start, or {@code <= 0} when there is none
     * @param actualPort   the port this host actually bound
     * @param pageToken    the current page token, or null/blank to leave the token query untouched
     * @return the URL on {@code actualPort}, with the current token when the URL already carried one
     */
    public static @Nullable String rewrite(@Nullable String url, int previousPort, int actualPort,
                                           @Nullable String pageToken) {
        if (url == null || url.isBlank() || actualPort <= 0) {
            return url;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException malformed) {
            return url;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return url;
        }
        if (!isLoopback(uri.getHost())) {
            return url;
        }
        String path = uri.getPath();
        if (path == null || !(path.equals("/app") || path.startsWith("/app/")
                || path.equals("/page") || path.startsWith("/page/"))) {
            return url;
        }
        int oldPort = uri.getPort();
        boolean ours = oldPort == actualPort
                || (previousPort > 0 && oldPort == previousPort);
        if (!ours) {
            return url;
        }
        String query = withCurrentToken(uri.getRawQuery(), pageToken);
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null) {
            return url;
        }
        int colon = rawAuthority.lastIndexOf(':');
        if (colon < 0) {
            return url;
        }
        String authority = rawAuthority.substring(0, colon) + ":" + actualPort;
        StringBuilder rewritten = new StringBuilder()
                .append(scheme).append("://").append(authority).append(uri.getRawPath());
        if (query != null) {
            rewritten.append('?').append(query);
        }
        if (uri.getRawFragment() != null) {
            rewritten.append('#').append(uri.getRawFragment());
        }
        return rewritten.toString();
    }

    private static boolean isLoopback(@Nullable String host) {
        if (host == null) {
            return false;
        }
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("127.0.0.1") || value.equals("localhost") || value.equals("::1")
                || value.equals("[::1]");
    }

    private static @Nullable String withCurrentToken(@Nullable String query,
                                                     @Nullable String pageToken) {
        if (query == null || query.isBlank() || pageToken == null || pageToken.isBlank()) {
            return query;
        }
        String encoded = URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
        // Keep every other query value; only the token belongs to the host's current process.
        String replaced = query.replaceAll("(^|&)token=[^&]*", "$1token=" + encoded);
        return replaced.equals(query) ? query : replaced;
    }
}
