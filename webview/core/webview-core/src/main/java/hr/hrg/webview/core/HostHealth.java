package hr.hrg.webview.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The document every host answers {@code GET /health} with.
 *
 * <p>A page uses this endpoint for exactly one thing: to find out whether a bridge is there, on which port,
 * and whether it must present a token. That makes the <b>shape</b> part of the contract between the hosts,
 * not a detail of each one — and it was drifting: the JetBrains host reported {@code plugin},
 * {@code port}, {@code allowedOrigins} and {@code tokenRequired}, the VS Code host reported the same four
 * under a different plugin name, and the sidecar reported three of them. A page that read
 * {@code port} from one host and not from another had no way to know which it was talking to.
 *
 * <p>So the keys are defined once, here, and every host builds its answer with this class. The four
 * original keys keep their names and types, because a page may be reading them already; everything this
 * class adds is additive.
 */
public final class HostHealth {

    /** The name a page sees for the JetBrains host. */
    public static final String PLUGIN_JETBRAINS = "hr.hrg.jetbrains.webview";
    /** The name a page sees for the VS Code host. */
    public static final String PLUGIN_VSCODE = "vscode-webview-explorer";
    /** The name a page sees for the LSP sidecar. */
    public static final String PLUGIN_SIDECAR = "hr.hrg.watch2.sidecar";

    /**
     * The keys every host must answer with, in the order they appear in the document. Asserted by {@link
     * #toJson()} itself, so a host cannot build a document that is missing one.
     */
    public static final List<String> REQUIRED_KEYS =
            List.of("plugin", "port", "allowedOrigins", "tokenRequired", "bridgeVersion", "capabilities");

    private final String plugin;
    private final int port;
    private final int allowedOrigins;
    private final boolean tokenRequired;
    private final Set<String> capabilities;

    private HostHealth(String plugin, int port, int allowedOrigins, boolean tokenRequired,
                       Set<String> capabilities) {
        this.plugin = plugin;
        this.port = port;
        this.allowedOrigins = allowedOrigins;
        this.tokenRequired = tokenRequired;
        this.capabilities = capabilities;
    }

    /**
     * @param plugin          one of the {@code PLUGIN_*} constants, so a page can tell the hosts apart
     * @param allowedOrigins  how many origins are allowed; **0 means every caller is refused**
     * @param capabilities    what this host can actually do right now — see {@link EditorHost} for the
     *                        keys. Empty is the honest answer for a host with no editor attached
     */
    public static HostHealth of(String plugin, int port, int allowedOrigins, boolean tokenRequired,
                                Set<String> capabilities) {
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("a health document must name its plugin");
        }
        if (port < 0) {
            throw new IllegalArgumentException("a bound port cannot be negative, was " + port);
        }
        if (allowedOrigins < 0) {
            throw new IllegalArgumentException("an origin count cannot be negative, was " + allowedOrigins);
        }
        return new HostHealth(plugin, port, allowedOrigins, tokenRequired,
                Set.copyOf(capabilities == null ? Set.of() : capabilities));
    }

    public String plugin() {
        return plugin;
    }

    public int port() {
        return port;
    }

    public int allowedOrigins() {
        return allowedOrigins;
    }

    public boolean tokenRequired() {
        return tokenRequired;
    }

    /** The capability keys, sorted, so two hosts with the same abilities produce identical bytes. */
    public Set<String> capabilities() {
        return new TreeSet<>(capabilities);
    }

    /**
     * The document, with the keys in {@link #REQUIRED_KEYS} order and the capabilities sorted.
     *
     * <p>Written by hand rather than with a JSON library: the response is four scalars and a short array,
     * Gson is only on the classpath for the bridge payload, and a host that already answers this endpoint
     * should not gain a serializer dependency to keep answering it.
     */
    public String toJson() {
        StringBuilder json = new StringBuilder(160);
        json.append("{\"plugin\":\"").append(escape(plugin)).append('"')
                .append(",\"port\":").append(port)
                .append(",\"allowedOrigins\":").append(allowedOrigins)
                .append(",\"tokenRequired\":").append(tokenRequired)
                // Additive since 2026-09-25: a page can now tell an old bridge from a new one, and can read
                // what the host can do instead of probing for each verb.
                .append(",\"bridgeVersion\":").append(InjectedBridge.VERSION)
                .append(",\"capabilities\":[");
        boolean first = true;
        for (String capability : capabilities()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(capability)).append('"');
        }
        return json.append("]}").toString();
    }

    /** True when this document carries every key a page may rely on. */
    public boolean isComplete() {
        String json = toJson();
        for (String key : REQUIRED_KEYS) {
            if (!json.contains("\"" + key + "\":")) {
                return false;
            }
        }
        return true;
    }

    /** The key names present in a document, for a test that compares two hosts' answers. */
    public static List<String> keysOf(String json) {
        List<String> keys = new ArrayList<>();
        if (json == null) {
            return keys;
        }
        int index = 0;
        while ((index = json.indexOf('"', index)) >= 0) {
            int end = json.indexOf('"', index + 1);
            if (end < 0) {
                break;
            }
            String candidate = json.substring(index + 1, end);
            int colon = end + 1;
            while (colon < json.length() && Character.isWhitespace(json.charAt(colon))) {
                colon++;
            }
            if (colon < json.length() && json.charAt(colon) == ':') {
                keys.add(candidate);
            }
            index = end + 1;
        }
        return keys;
    }

    /** The same keys, sorted, so two documents can be compared without caring about field order. */
    public static List<String> sortedKeysOf(String json) {
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(keysOf(json)));
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
