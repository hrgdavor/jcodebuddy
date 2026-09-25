package hr.hrg.jetbrains.webview.bridge;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import hr.hrg.webview.core.BridgeMessage;
import hr.hrg.webview.core.InjectedBridge;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Injects {@code window.openFile(path, line, column)} into every page the tool window loads, and
 * forwards what the page sends back to {@link NavigatorService}.
 *
 * <p>The script itself is {@link InjectedBridge} in {@code webview-core}, not text written here: the two
 * ends of the wire — the script and {@link BridgeMessage} — now live in the same module as the other
 * hosts' copies of them, so a host cannot drift from the parser without failing that module's tests. This
 * class is only the JetBrains half: one {@link JBCefJSQuery} as the transport, one load handler, disposed
 * with the panel.
 *
 * <p>Compared to the first implementation this class owns exactly one query and registers exactly one
 * load handler, and parses the payload as JSON rather than with regular expressions.
 */
public final class WebViewBridge implements Disposable {

    /**
     * Bumped when the injected contract changes, so a page can tell which bridge it is talking to. The
     * number now lives with the script it describes.
     */
    public static final int BRIDGE_VERSION = InjectedBridge.VERSION;

    private static final Logger LOG = Logger.getInstance(WebViewBridge.class);

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
     *
     * <p>The transport is the only thing this host contributes; everything else is the shared contract.
     */
    static @NotNull String injectsInto(@NotNull String queryInvocation) {
        return InjectedBridge.script(queryInvocation);
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
