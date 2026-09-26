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
 *
 * <p><b>Identity is part of the document (added 2026-09-xx).</b> {@code ide} names the editor the reader is
 * sitting in and {@code project} names the directory this endpoint serves. Both are needed by the
 * port-claim protocol in {@link HostPortClaim}: a host that finds its port already taken asks the occupant
 * what it is, and only a host that names <em>the same project</em> may be left alone. A name is not a
 * secret and neither is a port: both are already published in the project's
 * {@code .jcodebuddy/webview/host.json}, so answering them here leaks nothing a caller could not read off
 * the disk it is already allowed to see.
 */
public final class HostHealth {

    /** The name a page sees for the JetBrains host. */
    public static final String PLUGIN_JETBRAINS = "hr.hrg.jetbrains.webview";
    /** The name a page sees for the VS Code host. */
    public static final String PLUGIN_VSCODE = "vscode-webview-explorer";
    /** The name a page sees for the LSP sidecar. */
    public static final String PLUGIN_SIDECAR = "hr.hrg.watch2.sidecar";
    /** The name a page sees for the standalone host. */
    public static final String PLUGIN_WEBVIEWD = "hr.hrg.webview.webviewd";

    /**
     * The human name a reader recognises for each plugin id, which is what {@code ide} carries.
     *
     * <p>Deliberately separate from {@code plugin}: a plugin id is a reverse-DNS artefact
     * ({@code hr.hrg.jetbrains.webview}) and a page that shows "which editor is serving this page" has to
     * show something a person can act on. A host whose product name is versioned or user-configurable
     * (a JetBrains IDE reports "IntelliJ IDEA", "PyCharm" or "RustRover" from the same plugin) passes its
     * own string to {@link #of}; these are the defaults for the hosts whose name is fixed.
     */
    public static final String IDE_JETBRAINS = "IntelliJ Platform";
    /** See {@link #IDE_JETBRAINS}. */
    public static final String IDE_VSCODE = "Visual Studio Code";
    /** See {@link #IDE_JETBRAINS}. */
    public static final String IDE_SIDECAR = "jwa-sidecar";
    /** See {@link #IDE_JETBRAINS}. */
    public static final String IDE_WEBVIEWD = "webviewd";

    /** The name a host uses when it has no human name to give: never blank, always non-null. */
    public static final String IDE_UNKNOWN = "unknown";

    /**
     * The keys every host must answer with, in the order they appear in the document. Asserted by {@link
     * #toJson()} itself, so a host cannot build a document that is missing one.
     *
     * <p>{@code ide} and {@code project} are appended rather than inserted: a reader that walks the keys in
     * order (the conformance test does) keeps seeing the same six it always saw, and a reader that was
     * written before they existed is unaffected by their arrival.
     */
    public static final List<String> REQUIRED_KEYS =
            List.of("plugin", "port", "allowedOrigins", "tokenRequired", "bridgeVersion", "capabilities",
                    "ide", "project");

    private final String plugin;
    private final String ide;
    private final String project;
    private final int port;
    private final int allowedOrigins;
    private final boolean tokenRequired;
    private final Set<String> capabilities;

    private HostHealth(String plugin, String ide, String project, int port, int allowedOrigins,
                       boolean tokenRequired, Set<String> capabilities) {
        this.plugin = plugin;
        this.ide = ide;
        this.project = project;
        this.port = port;
        this.allowedOrigins = allowedOrigins;
        this.tokenRequired = tokenRequired;
        this.capabilities = capabilities;
    }

    /**
     * @param plugin          one of the {@code PLUGIN_*} constants, so a page can tell the hosts apart
     * @param ide             the human name of the editor serving this endpoint; blank becomes
     *                        {@link #IDE_UNKNOWN} rather than an empty string, so the key is never
     *                        ambiguous between "no name" and "no host"
     * @param project         the absolute path of the directory this endpoint serves, or blank when the host
     *                        does not know it yet (the sidecar before its client has initialized). Written
     *                        with {@code /} separators, like every other path in this product.
     * @param allowedOrigins  how many origins are allowed; **0 means every caller is refused**
     * @param capabilities    what this host can actually do right now — see {@link EditorHost} for the
     *                        keys. Empty is the honest answer for a host with no editor attached
     */
    public static HostHealth of(String plugin, String ide, String project, int port, int allowedOrigins,
                                boolean tokenRequired, Set<String> capabilities) {
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("a health document must name its plugin");
        }
        if (port < 0) {
            throw new IllegalArgumentException("a bound port cannot be negative, was " + port);
        }
        if (allowedOrigins < 0) {
            throw new IllegalArgumentException("an origin count cannot be negative, was " + allowedOrigins);
        }
        return new HostHealth(plugin,
                ide == null || ide.isBlank() ? IDE_UNKNOWN : ide.trim(),
                normalizeProject(project),
                port, allowedOrigins, tokenRequired,
                Set.copyOf(capabilities == null ? Set.of() : capabilities));
    }

    /**
     * The same document for a host that has not had to think about its identity: the plugin's default IDE
     * name and no project path. Kept because a page-reader test, a manifest printer and a host build-up path
     * all want a document without inventing two extra arguments — but a host that serves a project **should**
     * pass both, because that is what lets a second host recognise it (see {@link HostPortClaim}).
     */
    public static HostHealth of(String plugin, int port, int allowedOrigins, boolean tokenRequired,
                                Set<String> capabilities) {
        return of(plugin, defaultIdeFor(plugin), "", port, allowedOrigins, tokenRequired, capabilities);
    }

    /** The default human name for a plugin id, or the id itself when it is not one we ship. */
    public static String defaultIdeFor(String plugin) {
        if (plugin == null) {
            return IDE_UNKNOWN;
        }
        return switch (plugin) {
            case PLUGIN_JETBRAINS -> IDE_JETBRAINS;
            case PLUGIN_VSCODE -> IDE_VSCODE;
            case PLUGIN_SIDECAR -> IDE_SIDECAR;
            case PLUGIN_WEBVIEWD -> IDE_WEBVIEWD;
            default -> plugin;
        };
    }

    /**
     * A project path as every document in this product spells it: absolute-ish, forward-slashed, and without
     * a trailing separator. Blank stays blank — that is how "not known yet" is said.
     */
    public static String normalizeProject(String project) {
        if (project == null || project.isBlank()) {
            return "";
        }
        String normalized = project.trim().replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public String plugin() {
        return plugin;
    }

    public String ide() {
        return ide;
    }

    public String project() {
        return project;
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
     * <p>Written by hand rather than with a JSON library: the response is a handful of scalars and a short
     * array, Gson is only on the classpath for the bridge payload, and a host that already answers this
     * endpoint should not gain a serializer dependency to keep answering it.
     */
    public String toJson() {
        StringBuilder json = new StringBuilder(240);
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
        // Additive since the port-claim decision: who this is and which project it serves. Both are required
        // keys, so they are always emitted — an absent project is the empty string, never a missing key.
        return json.append(']')
                .append(",\"ide\":\"").append(escape(ide)).append('"')
                .append(",\"project\":\"").append(escape(project)).append('"')
                .append('}').toString();
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
