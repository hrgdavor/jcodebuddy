package hr.hrg.jetbrains.webview.bridge;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.openapi.util.Disposer;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Injects {@code window.openFile(path, line, column)} into every page the tool window loads, and
 * forwards what the page sends back to {@link NavigatorService}.
 *
 * <p>This is the contract the generated HTML reports rely on: {@code scripts/entity-html/render.js}
 * calls {@code window.openFile} when a source link is clicked, and falls back to the HTTP bridge or
 * the clipboard when the function is absent (DEC-027 § 7).
 *
 * <p>Compared to the first implementation this class owns exactly one {@link JBCefJSQuery} and
 * registers exactly one load handler, disposes the query with the panel, and parses the payload with
 * {@link BridgeMessage} rather than with regular expressions.
 */
public final class WebViewBridge implements Disposable {

    private static final Logger LOG = Logger.getInstance(WebViewBridge.class);

    /** Bumped when the injected contract changes, so a page can tell which bridge it is talking to. */
    public static final int BRIDGE_VERSION = 1;

    private final Project project;
    private final JBCefJSQuery query;

    public WebViewBridge(@NotNull Project project, @NotNull JBCefBrowserBase browser, @NotNull Disposable parent) {
        this.project = project;
        this.query = JBCefJSQuery.create(browser);
        this.query.addHandler(this::handle);
        Disposer.register(parent, this);
    }

    /**
     * The script installed after every main-frame load. Exposed for tests so the contract can be
     * asserted without starting a browser.
     */
    @NotNull String injectedScript() {
        return injectsInto(query.inject("msg"));
    }

    /**
     * The injectable half of {@link #injectedScript()}, factored out so a test can supply its own
     * {@code jsQuery.inject(...)} placeholder and assert the contract without a live JCEF browser.
     */
    static @NotNull String injectsInto(@NotNull String queryInvocation) {
        return "window.__jcbWebViewBridge = " + BRIDGE_VERSION + ";\n"
                + "window.openFile = function (path, line, column) {\n"
                + "  var msg = JSON.stringify({kind: 'openFile', filePath: path, line: line || 1, column: column || 1});\n"
                + "  " + queryInvocation + "\n"
                + "};";
    }

    /** Called from the panel's single load handler once the main frame has finished loading. */
    public void install(@NotNull CefBrowser browser) {
        browser.executeJavaScript(injectedScript(), browser.getURL(), 0);
    }

    private JBCefJSQuery.@NotNull Response handle(@Nullable String raw) {
        BridgeMessage message = BridgeMessage.parse(raw);
        if (message == null) {
            if (BridgeMessage.looksLikeAMessage(raw)) {
                LOG.info("WebView bridge: ignoring message kind this version does not handle: " + truncate(raw));
            } else {
                LOG.warn("WebView bridge: could not parse a message from the page: " + truncate(raw));
            }
            return new JBCefJSQuery.Response("ignored");
        }
        boolean opened = NavigatorService.getInstance(project)
                .open(message.filePath(), message.safeLine(), message.safeColumn());
        return opened
                ? new JBCefJSQuery.Response("OK")
                : new JBCefJSQuery.Response(null, 404, "no file at " + message.filePath());
    }

    @Override
    public void dispose() {
        query.dispose();
    }

    /** A load handler that installs the bridge, for the panel to register on its browser. */
    public @NotNull CefLoadHandlerAdapter loadHandler() {
        return new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (frame != null && !frame.isMain()) {
                    return;
                }
                install(browser);
            }
        };
    }

    private static @NotNull String truncate(@Nullable String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
