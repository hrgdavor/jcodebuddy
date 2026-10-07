// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * How the connected client wants generated members indented (plan step 7.1).
 *
 * <p>The sidecar used to construct its engine with a hard-coded {@code "    "}, so a project that indents with two
 * spaces — or with tabs — got a builder that did not match the file it was inserted into. The client's own settings
 * are the authority: LSP clients publish {@code tabSize} and {@code insertSpaces}, and this type is the one place
 * that knows how to read them and what the defaults are.
 *
 * <p><b>Where the values come from, and why not from the request.</b> The LSP verb that carries
 * {@code FormattingOptions} is a *formatting* request, and a code action is not one — it is a command the user picks,
 * so there is no {@code FormattingOptions} on it to read. The settings therefore arrive through
 * {@code workspace/didChangeConfiguration}, are remembered here, and are read by every code action; a client that
 * never sends them gets {@link #defaults()}, which is the four spaces this sidecar used before.
 *
 * <p><b>What is accepted, stated rather than guessed.</b> The settings object a client sends is either flat
 * ({@code {"tabSize":2,"insertSpaces":true}}) or wrapped in a section ({@code {"jwa":{"tabSize":2}}}), and both
 * shapes are in the wild, so both are read: the root object first, then its direct children. Anything else — a value
 * nested two sections deep, or a number sent as a string — is not guessed at, because a guess is how a page's text
 * gets reformatted by a setting the user never wrote.
 */
public record ClientFormatting(int tabSize, boolean insertSpaces) {

    /** The indent step a one-level indentation adds, as the engine wants it: spaces, or one tab. */
    public String indent() {
        return insertSpaces ? " ".repeat(Math.max(1, tabSize)) : "\t";
    }

    /** Four spaces: what this sidecar generated before it read the client's settings, kept as the default. */
    public static ClientFormatting defaults() {
        return new ClientFormatting(4, true);
    }

    /**
     * Read {@code tabSize}/{@code insertSpaces} from a client's settings object, or return the defaults.
     *
     * <p>Takes {@code Object} because that is what LSP4J declares for {@code DidChangeConfigurationParams.getSettings()}
     * even when the connection is Gson-typed, so the runtime value is a {@code JsonElement} and the compile-time one is
     * not. Anything that is not a JSON object — a client that sends a string, or nothing at all — gets the defaults
     * rather than an exception: a settings shape we do not recognise must not take the language server down.
     *
     * <p>A missing field keeps the default for that field rather than resetting the pair: a client that sends only
     * {@code insertSpaces} has said something about tabs, and nothing about width.
     */
    public static ClientFormatting fromSettings(Object settings) {
        if (!(settings instanceof JsonElement element)) {
            return defaults();
        }
        return fromSettings(element);
    }

    /** The typed form, which is what the tests and any Gson-typed caller use. */
    private static ClientFormatting fromSettings(JsonElement settings) {
        ClientFormatting defaults = defaults();
        JsonObject object = findSettingsObject(settings);
        if (object == null) {
            return defaults;
        }
        int tabSize = readInt(object, "tabSize", defaults.tabSize());
        boolean insertSpaces = readBoolean(object, "insertSpaces", defaults.insertSpaces());
        return new ClientFormatting(tabSize, insertSpaces);
    }

    /** The object that carries the settings: the argument itself, or one of its direct children. */
    private static JsonObject findSettingsObject(JsonElement settings) {
        if (settings == null || !settings.isJsonObject()) {
            return null;
        }
        JsonObject root = settings.getAsJsonObject();
        if (root.has("tabSize") || root.has("insertSpaces")) {
            return root;
        }
        for (String name : root.keySet()) {
            JsonElement child = root.get(name);
            if (child != null && child.isJsonObject()
                    && (child.getAsJsonObject().has("tabSize") || child.getAsJsonObject().has("insertSpaces"))) {
                return child.getAsJsonObject();
            }
        }
        return null;
    }

    private static int readInt(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        int read = value.getAsInt();
        return read < 1 ? fallback : read;
    }

    private static boolean readBoolean(JsonObject object, String name, boolean fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return value.getAsBoolean();
    }
}
