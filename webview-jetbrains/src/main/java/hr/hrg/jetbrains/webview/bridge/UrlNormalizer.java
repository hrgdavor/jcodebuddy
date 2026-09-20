package hr.hrg.jetbrains.webview.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Turns whatever the user typed in the address bar into a URL the browser can load.
 *
 * <p>The first implementation inlined this logic in a key listener with four nested branches and no
 * tests. Extracting it makes the decision table explicit and testable: the file-existence probe is a
 * parameter, so the rules can be exercised without touching the file system.
 *
 * <p>Pure logic: no IDE services, no I/O of its own.
 */
public final class UrlNormalizer {

    /** Windows drive-absolute paths ({@code C:\x}, {@code C:/x}) are recognised without a probe. */
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern UNIX_ABSOLUTE = Pattern.compile("^/.*");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://.*");

    private UrlNormalizer() {
    }

    /**
     * The outcome of normalising one address-bar entry.
     *
     * @param url        the URL to load; never empty
     * @param localPath  the file system path behind {@code url} when it is a {@code file:} URL, else {@code null}
     * @param unresolved true when the entry was treated as a local path but no such file exists
     */
    public record Normalized(@NotNull String url, @Nullable String localPath, boolean unresolved) {

        public boolean isLocal() {
            return localPath != null;
        }
    }

    /** Production entry point: probes the real file system. */
    public static @Nullable Normalized normalize(@Nullable String raw, @Nullable String basePath) {
        return normalize(raw, basePath, path -> new File(path).exists());
    }

    /**
     * @param raw       what the user typed
     * @param basePath  the project base, used to resolve a relative path; may be {@code null}
     * @param fileCheck whether a candidate file system path exists
     * @return the URL to load, or {@code null} when {@code raw} is blank and there is nothing to load
     */
    public static @Nullable Normalized normalize(@Nullable String raw,
                                                 @Nullable String basePath,
                                                 @NotNull Predicate<String> fileCheck) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }

        // 1. Anything already carrying a scheme is taken at face value.
        if (SCHEME.matcher(text).matches()) {
            return new Normalized(text, localPathOf(text), false);
        }

        // 2. A path that exists on disk, absolute or relative to the project, is loaded from disk.
        String candidate = toFilePath(text, basePath);
        if (candidate != null && fileCheck.test(candidate)) {
            String fileUrl = toFileUrl(candidate);
            return new Normalized(fileUrl, localPathOf(fileUrl), false);
        }

        // 3. Looks like a path (has a separator or a drive letter) but nothing is there: still treat
        //    it as a local file, because guessing "https://C:/foo" would be worse, and say so.
        if (looksLikePath(text)) {
            String fallback = candidate != null ? candidate : text;
            String fileUrl = toFileUrl(fallback);
            return new Normalized(fileUrl, localPathOf(fileUrl), true);
        }

        // 4. Otherwise it is a host: assume https.
        return new Normalized("https://" + text, null, false);
    }

    /** The file system path behind a {@code file:} URL, or {@code null} for anything else. */
    public static @Nullable String localPathOf(@Nullable String url) {
        if (url == null) {
            return null;
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith("file:")) {
            return null;
        }
        // Decode by hand rather than through URI: "file://D:/x" is not a URI with an authority that
        // URI can round-trip, and percent-encoding is the only escaping a file: URL carries here.
        String path = url.substring("file:".length());
        if (path.startsWith("//")) {
            path = path.substring(2);
        }
        // "file:///D:/x" leaves "/D:/x"; a drive-absolute path carries no leading slash.
        if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
            path = path.substring(1);
        }
        return percentDecode(path);
    }

    /** Resolves {@code text} against {@code basePath} when it is relative, without probing disk. */
    public static @Nullable String toFilePath(@Nullable String text, @Nullable String basePath) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String path = text.replace('\\', '/');
        if (WINDOWS_ABSOLUTE.matcher(text).matches() || UNIX_ABSOLUTE.matcher(path).matches()) {
            return path;
        }
        if (basePath == null || basePath.isBlank()) {
            return path;
        }
        return basePath.replace('\\', '/') + "/" + path;
    }

    private static boolean looksLikePath(@NotNull String text) {
        if (WINDOWS_ABSOLUTE.matcher(text).matches() || UNIX_ABSOLUTE.matcher(text).matches()) {
            return true;
        }
        // "D:\x" and "C:/x" — handled above. Everything else is a path only if it names a file, i.e.
        // its last segment has a dot in it. "example.com/docs" is a host with a path, not a file, and
        // treating it as one would have made every bare-host address bar entry load the project's own
        // directory as a "missing file".
        int lastSlash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        String lastSegment = text.substring(lastSlash + 1);
        return lastSegment.contains(".");
    }

    private static @NotNull String toFileUrl(@NotNull String filePath) {
        String normalized = filePath.replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return "file://" + encodePath(normalized);
    }

    /** Percent-encodes only the characters a file: URL cannot carry literally. */
    private static @NotNull String encodePath(@NotNull String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == ' ' || c == '#' || c == '?' || c == '%') {
                out.append('%').append(String.format("%02X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static @NotNull String percentDecode(@NotNull String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                int value = Character.digit(path.charAt(i + 1), 16) * 16
                        + Character.digit(path.charAt(i + 2), 16);
                if (value >= 0) {
                    out.append((char) value);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
