package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.EditorHost;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The adapter that moves Zed's caret: an LSP {@code window/showDocument}, ordered by a page.
 *
 * <p>This is the plan's headline case — "clicking a location link in the browser window beside Zed moves
 * Zed's caret to the line" — and it is the reason the LSP tier exists: Phase 0 measured that the CLI cannot
 * put a caret on Windows, while Zed's LSP client answers {@code showDocument} with a selection and
 * {@code {"success":true}}.
 *
 * <p>The adapter does not speak LSP itself. The process that holds the LSP connection is the sidecar Zed
 * spawned, so this adapter asks that process to send the request, through {@link SidecarClient}. What this
 * class owns is the <em>honesty policy</em>: whether navigation may be advertised at all is decided from the
 * sidecar's own {@code /health}, and that answer is re-read rather than cached forever, because Zed attaches
 * and detaches without telling anyone.
 *
 * <p>{@code lineNavigation()} is {@code exact} when an editor is attached: the request carries a selection
 * range and Zed's handler applies it, which Phase 0 confirmed on the installed build. It is {@code none}
 * when nothing is attached — an LSP request into the void must not read as an open verb.
 */
public final class LspHost implements EditorHost {

    /** The name a page sees in {@code /health} and in the manifest. */
    public static final String NAME = "lsp";

    /** How long a health answer is trusted before the sidecar is asked again. */
    static final long PROBE_TTL_MILLIS = 2_000L;

    private final SidecarClient sidecar;
    private final LongSupplier clock;

    private Set<String> capabilities = Set.of();
    /**
     * Whether {@link #probedAt} holds an answer at all. A sentinel instant cannot be used for this: comparing
     * against {@code Long.MIN_VALUE} overflows, which made the very first probe look fresh and left this host
     * permanently believing no editor was attached. The tests caught it; the flag is the fix.
     */
    private boolean probed;
    private long probedAt;
    private String lastHealth = "";

    public LspHost(SidecarClient sidecar) {
        this(sidecar, System::currentTimeMillis);
    }

    LspHost(SidecarClient sidecar, LongSupplier clock) {
        this.sidecar = sidecar;
        this.clock = clock;
    }

    /**
     * The adapter for a discovered sidecar, or an unavailable one. Discovery is the first {@code /health}
     * probe; a sidecar that answers with no capabilities yields an adapter that is present but refuses,
     * which is the honest shape for "the process is up, no editor has attached yet".
     */
    public static LspHost discover(SidecarClient sidecar) {
        LspHost host = new LspHost(sidecar);
        host.refresh();
        return host;
    }

    /** Where this adapter reaches the sidecar, for the descriptor and for logs. */
    public String sidecarDescription() {
        return sidecar.describe();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        // Refreshed here, not only on a jump: the server builds /health and the manifest from these answers,
        // and a page asking "can you navigate?" must get the current truth, not the one from startup. The TTL
        // keeps that from becoming a probe per request.
        refresh();
        return capabilities.contains(CAP_OPEN);
    }

    @Override
    public Set<String> capabilities() {
        refresh();
        return Set.copyOf(capabilities);
    }

    /** Re-reads the sidecar's health document when the last answer has expired. */
    private void refresh() {
        long now = clock.getAsLong();
        if (probed && now - probedAt < PROBE_TTL_MILLIS) {
            return;
        }
        probed = true;
        probedAt = now;
        String body = sidecar.health();
        lastHealth = body == null ? "" : body;
        capabilities = parseCapabilities(body);
    }

    /**
     * The verb keys the sidecar advertised, filtered to what this host can actually route: only the
     * contract's navigation verbs are meaningful here, and an unknown key from a newer sidecar is ignored
     * rather than passed on as a promise this adapter cannot keep.
     */
    static Set<String> parseCapabilities(String healthJson) {
        Set<String> advertised = new LinkedHashSet<>();
        if (healthJson == null) {
            return advertised;
        }
        int start = healthJson.indexOf("\"capabilities\"");
        if (start < 0) {
            return advertised;
        }
        int open = healthJson.indexOf('[', start);
        int close = open < 0 ? -1 : healthJson.indexOf(']', open);
        if (open < 0 || close < 0) {
            return advertised;
        }
        for (String part : healthJson.substring(open + 1, close).split(",")) {
            String value = part.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                advertised.add(value.substring(1, value.length() - 1));
            }
        }
        advertised.retainAll(Set.of(CAP_OPEN, CAP_SELECT, CAP_REVEAL));
        return advertised;
    }

    /** The sidecar's last health answer, for the descriptor's note. Empty when it never answered. */
    public String lastHealth() {
        return lastHealth;
    }

    @Override
    public boolean openFileAt(String absolutePath, int line, int column) {
        refresh();
        if (!isAvailable()) {
            return false;
        }
        return sidecar.jump(absolutePath, line, column);
    }

    /**
     * {@code exact} while an editor is attached, {@code none} otherwise. Never {@code file-only}: the LSP
     * request carries a selection, and Phase 0 observed the caret land on it.
     */
    @Override
    public String lineNavigation() {
        return isAvailable() ? "exact" : "none";
    }

    @Override
    public String lineNavigationNote() {
        return isAvailable()
                ? "navigation goes over LSP: the sidecar sends window/showDocument with a selection, which "
                        + "Zed 1.21.0 honoured when measured (webview/PHASE0-ZED-FINDINGS.md, section A)"
                : "no LSP client has attached to the sidecar " + sidecar.describe()
                        + ", so navigation is refused rather than sent into the void";
    }
}
