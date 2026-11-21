package hr.hrg.jetbrains.webview.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The URL spellings a served HTML page can have.
 *
 * <p>Pure, so the rules are testable without an IDE: a page is named by its path **relative to the project
 * root**, and two hosts can serve it — this IDE's bridge (under a one-time token segment, so the page and
 * its assets are not an open file server on loopback) or an existing server the user names in the settings
 * (whose base URL is the path mapping; the project-relative path is appended).</p>
 */
public final class ServedPageUrls {

    /** The route this IDE's bridge serves a page under. */
    public static final String LOCAL_PREFIX = "/page/";

    private ServedPageUrls() {
    }

    /**
     * {@code page} relative to {@code projectRoot}, with forward slashes, or null when the page is outside
     * the project. A page outside the project cannot be served by a host whose jail is that project.
     */
    public static @Nullable String projectRelative(@NotNull Path projectRoot, @NotNull Path page) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path target = page.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            return null;
        }
        return root.relativize(target).toString().replace('\\', '/');
    }

    /**
     * A relative path as URL path segments: {@code a page.html} becomes {@code a%20page.html}. A {@code +}
     * is encoded too, so a path containing one cannot be read as a space by a decoder that follows the
     * query-string rule instead of the path rule.
     */
    public static @NotNull String encodePath(@NotNull String relative) {
        StringBuilder encoded = new StringBuilder(relative.length() + 16);
        String[] segments = relative.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segments[index], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    /** The URL this IDE's bridge serves: {@code http://127.0.0.1:<port>/page/<token>/<relative>}. */
    public static @NotNull String onLocalServer(int port, @NotNull String token, @NotNull String relative) {
        return "http://127.0.0.1:" + port + LOCAL_PREFIX + token + "/" + encodePath(relative);
    }

    /**
     * The URL an existing server serves, from its configured base and the project-relative path.
     *
     * <p>The base is the mapping, including the route prefix:
     * {@code http://127.0.0.1:18884/page/} expects the project root to be served at that prefix, exactly
     * as {@code webviewd --project <root>} does. A base that already ends in a slash and one that does not
     * produce the same URL.</p>
     */
    public static @NotNull String onExistingServer(@NotNull String base, @NotNull String relative) {
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/" + encodePath(relative);
    }

    /**
     * The default app URL for one project file:
     * {@code <host>/app/?openFile=<project-relative path>}.
     *
     * <p>The path is one query value and is encoded as one component, so {@code /} becomes {@code %2F}.
     * The app decodes it once and fetches {@code /source/<path>}.</p>
     */
    public static @NotNull String appUrl(@NotNull String base, @NotNull String relative) {
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/app/?openFile="
                + URLEncoder.encode(relative, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * The direct URL for one page that already lives in the app directory:
     * {@code <host>/app/<page>} — no {@code openFile} shell, no token segment.
     *
     * <p>This is what "Open in WebView Explorer" uses for a page under {@code .jcodebuddy/app/}; the
     * {@code ?openFile=} form remains the one for a source file, which the app shell hands to the IDE.
     * The relative path is relative to {@code .jcodebuddy/app/}, e.g. {@code incremental-build.html}.</p>
     */
    public static @NotNull String appPage(@NotNull String base, @NotNull String relative) {
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/app/" + encodePath(relative);
    }

    /**
     * The direct app page URL with the host token as its query, when one is configured.
     *
     * <p>The app route is auth-protected (an allowed {@code Origin} or the token). A page opened in the
     * IDE's own webview has the origin, but the page's {@code /api/tools/<tool>/call} requests need the
     * token, and the page reads it from its own query. Carrying it here makes the opened page fully usable
     * without a second manual step.</p>
     */
    public static @NotNull String appPage(@NotNull String base, @NotNull String relative,
                                          @Nullable String token) {
        String url = appPage(base, relative);
        if (token == null || token.isBlank()) {
            return url;
        }
        return url + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
