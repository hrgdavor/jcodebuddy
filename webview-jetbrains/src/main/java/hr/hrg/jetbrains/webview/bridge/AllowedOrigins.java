package hr.hrg.jetbrains.webview.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The set of {@code Origin} values allowed to drive the HTTP bridge, parsed from the comma-separated
 * {@code webview.explorer.allowedOrigins} setting or system property.
 *
 * <p>The important property is that an <b>empty set denies</b>. The first implementation compared an
 * origin against a possibly-empty set with {@code !isAllowed -> 403}, which is fail-open in the wrong
 * direction for the empty case only if the caller forgets the emptiness test; here the emptiness test
 * is the class, so a caller cannot forget it.
 *
 * <p>Pure logic: no IDE services, no HTTP, no I/O.
 */
public final class AllowedOrigins {

    private final Set<String> origins;

    private AllowedOrigins(@NotNull Set<String> origins) {
        this.origins = origins;
    }

    public static @NotNull AllowedOrigins of(@Nullable String commaSeparated) {
        Set<String> parsed = new LinkedHashSet<>();
        if (commaSeparated != null) {
            for (String part : commaSeparated.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(normalize(trimmed));
                }
            }
        }
        return new AllowedOrigins(Set.copyOf(parsed));
    }

    /** The origins as configured, for display in the settings UI. */
    public @NotNull Set<String> values() {
        return origins;
    }

    /**
     * True when {@code origin} is explicitly allowed. An absent or empty allow-list denies everything:
     * the browser fallback is opt-in, and a page must not be able to open files in the IDE just
     * because nobody configured the bridge.
     */
    public boolean allows(@Nullable String origin) {
        if (origins.isEmpty() || origin == null || origin.isBlank()) {
            return false;
        }
        String candidate = normalize(origin);
        if (origins.contains(candidate)) {
            return true;
        }
        // A page served from a directory has the opaque origin "null"; only an explicit "file://"
        // entry is meant to cover local pages, so "null" is matched against the literal entry too.
        return origins.contains(origin.trim().toLowerCase(Locale.ROOT));
    }

    /** True when no origin is allowed, i.e. {@code /open} must answer 403 for every caller. */
    public boolean isEmpty() {
        return origins.isEmpty();
    }

    /**
     * Normalises one origin: trimmed, lower-cased, and with a trailing slash removed so
     * {@code http://localhost:3000/} and {@code http://localhost:3000} are the same entry.
     * A scheme-only entry such as {@code file://} keeps both of its slashes.
     */
    private static @NotNull String normalize(@NotNull String origin) {
        String value = origin.trim().toLowerCase(Locale.ROOT);
        while (value.length() > 1 && value.endsWith("/") && !value.endsWith("://")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
