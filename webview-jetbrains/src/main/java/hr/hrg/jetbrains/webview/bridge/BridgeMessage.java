package hr.hrg.jetbrains.webview.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One message sent from the JavaScript injected into a page (see {@code WebViewBridge}) to the IDE.
 *
 * <p>This replaces the three regular expressions the first implementation used to pick
 * {@code filePath} / {@code line} / {@code column} out of the raw text. A path containing
 * {@code ","line": 99} used to defeat that parser; here the payload is parsed as JSON, so it cannot.
 *
 * <p>Pure logic on purpose: no {@code Project}, no IDE services, no I/O. That is what makes it
 * testable without starting a platform (see {@code BridgeMessageTest}).
 */
public record BridgeMessage(@NotNull String kind, @NotNull String filePath, int line, int column) {

    /** The only message kind the injected page script sends today. */
    public static final String KIND_OPEN_FILE = "openFile";

    /**
     * Parses a raw bridge payload, returning {@code null} when the text is not a message this plugin
     * understands. Callers log the raw text; nothing here throws for bad input, because a page must
     * not be able to break the bridge by sending junk.
     */
    public static @Nullable BridgeMessage parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonObject object;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return null;
            }
            object = element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }

        String kind = stringOrNull(object, "kind");
        if (kind == null || kind.isBlank()) {
            return null;
        }
        // Unknown kinds are not an error: a newer page may speak a newer protocol. Only openFile is
        // actionable today, so anything else is reported as "not for us" by returning null.
        if (!KIND_OPEN_FILE.equals(kind)) {
            return null;
        }

        String filePath = stringOrNull(object, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return null;
        }

        return new BridgeMessage(
                KIND_OPEN_FILE,
                filePath,
                intOr(object, "line", 1),
                intOr(object, "column", 1));
    }

    /**
     * True when the text parses as JSON and carries a usable {@code kind} field, whether or not that
     * kind is one this plugin implements. Lets the caller tell "malformed" from "unknown kind" when
     * logging.
     */
    public static boolean looksLikeAMessage(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return false;
            }
            String kind = stringOrNull(element.getAsJsonObject(), "kind");
            return kind != null && !kind.isBlank();
        } catch (JsonParseException | IllegalStateException e) {
            return false;
        }
    }

    /** A one-based line, clamped to at least 1 so a page cannot ask for line 0 or -5. */
    public int safeLine() {
        return Math.max(1, line);
    }

    /** A one-based column, clamped to at least 1. */
    public int safeColumn() {
        return Math.max(1, column);
    }

    private static @Nullable String stringOrNull(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (ClassCastException | IllegalStateException e) {
            return null;
        }
    }

    private static int intOr(@NotNull JsonObject object, @NotNull String name, int fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | ClassCastException | IllegalStateException e) {
            return fallback;
        }
    }
}
