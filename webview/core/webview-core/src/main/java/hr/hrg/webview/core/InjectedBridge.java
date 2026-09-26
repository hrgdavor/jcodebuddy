package hr.hrg.webview.core;

/**
 * The exact script a host installs into a page so it can navigate the host.
 *
 * <p>This is the frozen half of the contract in {@code webview/kit/doc/contract.md} § 1: the page
 * sees one function and one version probe, and the transport underneath is the host's business. A
 * JetBrains tool window passes a {@code JBCefJSQuery.inject(...)} invocation as {@code transport}; a
 * plain browser served by the sidecar passes a call to {@code POST /api/v1/open}; a host that only
 * mirrors messages passes a {@code postMessage}.
 *
 * <p>Kept here, rather than in each host, because the two ends of the wire have to stay in step: the
 * keys this script writes are the keys {@link BridgeMessage} reads, and a host that reinvented the
 * script could drift from {@link BridgeMessage#KIND_OPEN_FILE} without any test noticing.
 */
public final class InjectedBridge {

    /**
     * Bumped when the injected contract changes incompatibly, so a page can tell which bridge it is
     * talking to. {@code window.__jcbWebViewBridge} on the page and this constant are the same number.
     */
    public static final int VERSION = 1;

    /** The global a page feature-detects when it only needs to know whether navigation is possible. */
    public static final String OPEN_FILE_FUNCTION = "window.openFile";

    /**
     * The global a page feature-detects when it needs to know <em>which</em> bridge it is talking to.
     */
    public static final String VERSION_GLOBAL = "window.__jcbWebViewBridge";

    private InjectedBridge() {
    }

    /**
     * The script installed after every main-frame load.
     *
     * @param transport the one-line statement that delivers {@code msg} to the host; must end without a
     *                  newline. A host passes its own transport here, which is what keeps the contract
     *                  identical while the mechanism differs.
     */
    public static String script(String transport) {
        return VERSION_GLOBAL + " = " + VERSION + ";\n"
                + OPEN_FILE_FUNCTION + " = function (path, line, column) {\n"
                + "  var msg = JSON.stringify({kind: '" + BridgeMessage.KIND_OPEN_FILE
                + "', filePath: path, line: line || 1, column: column || 1});\n"
                + "  " + transport + "\n"
                + "};";
    }

    /**
     * The script with the transport left as {@code placeholder}, for a test that asserts the contract
     * without a live browser. Exists so the placeholder cannot leak into production by accident: {@link
     * #script(String)} takes no default.
     */
    public static String scriptWithPlaceholder(String placeholder) {
        return script(placeholder);
    }
}
