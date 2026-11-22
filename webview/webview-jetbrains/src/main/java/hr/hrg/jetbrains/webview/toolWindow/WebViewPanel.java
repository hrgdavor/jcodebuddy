package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefScrollbarsHelper;
import com.intellij.ui.jcef.JBCefBrowserBase;
import hr.hrg.jetbrains.webview.bridge.WebViewBridge;
import hr.hrg.jetbrains.webview.services.HttpBridgeService;
import hr.hrg.jetbrains.webview.services.PluginStateService;
import hr.hrg.webview.core.UrlNormalizer;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The tool window's contents: one JCEF browser, an address bar, a toolbar and an error card.
 *
 * <p>This class <b>owns</b> the browser. The first implementation reached back into the Swing
 * component tree from a static method to find it ({@code panel.getProperty("JBCefBrowser")} on content
 * index 0), which made every other class depend on the panel's internal layout. Callers now go
 * through {@link WebViewService}.
 */
public final class WebViewPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(WebViewPanel.class);
    private static final String CARD_BROWSER = "browser";
    private static final String CARD_ERROR = "error";

    private final Project project;
    private JBCefBrowser browser;
    private final PendingLoad pendingLoad;
    private final JBTextField addressBar = new JBTextField();
    private final SimpleToolWindowPanel root;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JBLabel errorLabel = new JBLabel();
    private WebViewBridge bridge;

    /** The one load handler for this browser, so nothing has to guess the registration order. */
    private final CefLoadHandlerAdapter loadHandler;

    private volatile boolean disposed;
    /** Home requested but not yet confirmed; used only to decide whether a fallback is still needed. */
    private volatile String homeTarget;
    private volatile boolean homeLoadStarted;

    public WebViewPanel(@NotNull Project project, @NotNull PendingLoad pendingLoad) {
        this.project = project;
        this.pendingLoad = pendingLoad;
        this.browser = JBCefBrowser.createBuilder()
                .setEnableOpenDevToolsMenuItem(true)
                .build();
        this.bridge = new WebViewBridge(project, browser, this);

        this.loadHandler = new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser cefBrowser, CefFrame frame, CefRequest.TransitionType type) {
                if (cefBrowser != browser.getCefBrowser()) {
                    return;
                }
                if (frame != null && !frame.isMain()) {
                    return;
                }
                if (homeTarget != null && homeTarget.equals(frame == null ? null : frame.getURL())) {
                    homeLoadStarted = true;
                }
            }

            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (cefBrowser != browser.getCefBrowser()) {
                    return;
                }
                if (frame != null && !frame.isMain()) {
                    return;
                }
                String url = cefBrowser.getURL();
                if (homeTarget != null && homeTarget.equals(url)) {
                    homeTarget = null;
                }
                SwingUtilities.invokeLater(() -> onMainFrameLoaded(url));
                bridge.install(cefBrowser);
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, ErrorCode errorCode,
                                    String errorText, String failedUrl) {
                if (cefBrowser != browser.getCefBrowser()) {
                    return;
                }
                if (frame != null && !frame.isMain()) {
                    return;
                }
                if (isInternalBrowserUrl(failedUrl)) {
                    // JCEF reports a failed load of its own initial about:blank page on every browser
                    // creation. Showing an error card for it would greet every first open with a
                    // failure that is not one.
                    return;
                }
                if (homeTarget != null && homeTarget.equals(failedUrl)) {
                    homeTarget = null;
                }
                LOG.info("WebView Explorer could not load " + failedUrl + ": " + errorCode + " " + errorText);
                SwingUtilities.invokeLater(() -> showBrowserError(
                        "Could not load " + failedUrl + "\n" + errorCode + ": " + errorText));
            }
        };
        browser.getJBCefClient().addLoadHandler(loadHandler, browser.getCefBrowser());

        this.root = new SimpleToolWindowPanel(true, true);
        root.setToolbar(createToolbar());
        cardPanel.add(browser.getComponent(), CARD_BROWSER);
        cardPanel.add(createErrorComponent(), CARD_ERROR);
        root.setContent(cardPanel);

        addressBar.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                    loadFromAddressBar();
                }
            }
        });
    }

    public @NotNull JComponent getComponent() {
        return root;
    }

    @Nullable JBCefBrowser getBrowser() {
        return browser;
    }

    /** True once the panel has been disposed, so late callbacks do not touch dead components. */
    public boolean isDisposed() {
        return disposed;
    }

    /** Loads a URL or path; used by the address bar, the toolbar and the service. */
    public boolean load(@Nullable String urlOrPath) {
        if (disposed || urlOrPath == null) {
            return false;
        }
        // When the app is already showing, navigation is an app action, not a document load: the app may
        // intercept it (window.jcodebuddyNavIntercept) and render in place. The script handles the
        // allowed case and falls back to location.assign, so Java never needs the result.
        if (isAppNavigationUrl(urlOrPath) && isCurrentAppPage()) {
            String file = openFileOf(urlOrPath);
            navigateApp(urlOrPath, file == null ? "page" : "file", file);
            return true;
        }
        if (!pendingLoad.request(urlOrPath)) {
            return false;
        }
        return pendingLoad.deliverTo(this::loadNow);
    }

    /** URL path is under the served app. */
    static boolean isAppNavigationUrl(@Nullable String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String path = uri.getPath();
            return path != null && (path.equals("/app") || path.startsWith("/app/"));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private boolean isCurrentAppPage() {
        return isAppNavigationUrl(currentUrl());
    }

    /** The project-relative openFile from either the query (default) or the fragment (hash routing). */
    static @Nullable String openFileOf(@Nullable String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String value = param(uri.getRawQuery(), "openFile");
            if (value == null) {
                value = param(uri.getRawFragment(), "openFile");
            }
            return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static @Nullable String param(@Nullable String query, @NotNull String name) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).equals(name)) {
                return part.substring(equals + 1);
            }
        }
        return null;
    }

    /**
     * Navigates an already-loaded app. The app may declare
     * {@code window.jcodebuddyNavIntercept(target)}; returning {@code true}, {@code "intercept"} or a URL
     * string means it rendered the target and the host only updates the URL. Anything else falls back to a
     * normal document navigation.
     */
    private void navigateApp(@NotNull String url, @NotNull String kind, @Nullable String path) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", kind);
        if (path != null) {
            target.addProperty("path", path);
        }
        target.addProperty("url", url);
        target.addProperty("line", 1);
        target.addProperty("column", 1);
        addressBar.setText(url);
        PluginStateService.getInstance(project).setLastUrl(url);
        String script = "(function () {"
                + "  var target = " + target + ";"
                + "  var hook = window.jcodebuddyNavIntercept;"
                + "  if (typeof hook === 'function') {"
                + "    var result;"
                + "    try { result = hook(target); }"
                + "    catch (error) { console.error('jcodebuddyNavIntercept failed', error); }"
                + "    if (result === true || result === 'intercept'"
                + "        || (typeof result === 'string' && result.length > 0)) {"
                + "      var next = typeof result === 'string'"
                + "          ? new URL(result, location.href).href : target.url;"
                + "      history.pushState({}, '', next);"
                + "      return;"
                + "    }"
                + "  }"
                + "  location.assign(target.url);"
                + "})();";
        browser.getCefBrowser().executeJavaScript(script, browser.getCefBrowser().getURL(), 0);
    }

    /**
     * The URL the browser is showing, or null.
     *
     * Exposed because a browser will not reload a URL it already has - so a caller that re-generated a page
     * under the same URL (the Markdown view does exactly that) must push into the live page instead of asking
     * for a load, and it can only know which it needs by asking this.
     */
    public @Nullable String currentUrl() {
        try {
            return browser.getCefBrowser().getURL();
        } catch (RuntimeException notReady) {
            return null;
        }
    }

    /**
     * Push fresh view data into the live page, for a save.
     *
     * No reload on purpose: the page exposes {@code window.__renderMarkdown()}, so a save re-renders in place
     * and the reader keeps their scroll position, instead of the browser fetching everything again to show a
     * sentence that changed. The page's own URL is passed as the script's origin, which is what JCEF wants.
     */
    public void pushMarkdownView(@NotNull String viewJson) {
        if (disposed) {
            return;
        }
        String script = "window.__view = " + viewJson + "; window.__renderMarkdown();";
        browser.getCefBrowser().executeJavaScript(script, browser.getCefBrowser().getURL(), 0);
    }

    /**
     * Delivers a URL that was requested before this panel existed, if there is one.
     *
     * @return true when a parked URL was delivered
     */
    boolean deliverPending() {
        return !disposed && pendingLoad.deliverTo(this::loadNow);
    }

    /** True once this panel has been given a URL. */
    public boolean isLoaded() {
        return pendingLoad.isLoaded();
    }

    private boolean loadNow(@NotNull String urlOrPath) {
        if (disposed) {
            return false;
        }
        UrlNormalizer.Normalized normalized = UrlNormalizer.normalize(urlOrPath, project.getBasePath());
        if (normalized == null) {
            return false;
        }
        if (normalized.unresolved()) {
            LOG.info("WebView Explorer: no file at " + normalized.localPath() + ", loading the path anyway");
        }
        pendingLoad.markLoaded();
        // The error card is a CardLayout sibling of the browser: a later load has to bring the browser
        // back to the front, or Home/Reload appear to do nothing while the old card stays visible.
        cards.show(cardPanel, CARD_BROWSER);
        browser.loadURL(normalized.url());
        return true;
    }

    /** Reloads whatever the browser is currently showing. */
    public void reload() {
        browser.getCefBrowser().reload();
    }

    /**
     * Opens the project's app home: {@code /app/index.html} on the host the panel is already using, or on
     * this project's bridge when the current page is not a host page. The default home is the dashboard;
     * a project that has not built an app gets the host's 404, which is the honest answer.
     */
    public void goHome() {
        if (disposed) {
            return;
        }
        String home = homeUrl(currentUrl(), HttpBridgeService.getInstance(project).getBoundPort());
        if (home == null) {
            LOG.info("WebView Explorer: home unavailable (no current origin and no bound port)");
            return;
        }
        LOG.info("WebView Explorer: home -> " + home);
        if (isCurrentAppPage()) {
            // An app may handle Home in place; an app without the hook falls through to location.assign
            // inside the same script. This avoids a second Swing-driven navigation path for the common
            // case of being in the app already.
            navigateApp(home, "home", null);
            return;
        }
        // Go straight to the browser: Home is a navigation command, not a request that has to pass
        // through the first-page/address-bar normalizer. Do not stopLoad here: on a large report page
        // the stop is asynchronous and delayed the useful load by seconds without helping it.
        homeTarget = home;
        homeLoadStarted = false;
        cards.show(cardPanel, CARD_BROWSER);
        addressBar.setText(home);
        pendingLoad.markLoaded();
        browser.loadURL(home);
        // A load start is enough to stop the fast fallbacks, but not enough to prove the page arrived.
        // This watchdog only fires when the home load never completed; it is dead on the normal path.
        new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this)
                .addRequest(() -> {
                    if (disposed || home.equals(currentUrl()) || homeTarget == null) {
                        return;
                    }
                    LOG.warn("WebView Explorer: Home load did not finish; recreating the browser");
                    resetBrowser(home);
                }, 2500);
        // Only wait long enough to notice that CEF ignored the load. A normal local page emits
        // onLoadStart immediately; the fallbacks are deliberately out of the normal path.
        new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this)
                .addRequest(() -> {
                    if (disposed || home.equals(currentUrl()) || homeLoadStarted) {
                        return;
                    }
                    LOG.warn("WebView Explorer: Home load did not start; forcing in-page navigation to " + home);
                    String quoted = home.replace("\\", "\\\\").replace("'", "\\'");
                    browser.getCefBrowser().executeJavaScript(
                            "window.location.replace('" + quoted + "');",
                            browser.getCefBrowser().getURL(), 0);
                    new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this)
                            .addRequest(() -> {
                                if (disposed || home.equals(currentUrl()) || homeLoadStarted) {
                                    return;
                                }
                                LOG.warn("WebView Explorer: Home still did not start; recreating the browser");
                                resetBrowser(home);
                            }, 500);
                }, 350);
    }

    /**
     * Replaces the JCEF browser with a fresh one on the same panel. This is the recovery of last resort:
     * CEF can keep a page that refuses top-level and in-page navigation, and only a new browser is
     * guaranteed to leave that state.
     */
    private void resetBrowser(@NotNull String url) {
        if (disposed) {
            return;
        }
        JBCefBrowser old = browser;
        WebViewBridge oldBridge = bridge;
        try {
            old.getJBCefClient().removeLoadHandler(loadHandler, old.getCefBrowser());
        } catch (RuntimeException ignored) {
            // The old browser may already be torn down; the replacement still has to happen.
        }
        cardPanel.remove(old.getComponent());
        Disposer.dispose(oldBridge);
        Disposer.dispose(old);

        browser = JBCefBrowser.createBuilder()
                .setEnableOpenDevToolsMenuItem(true)
                .build();
        bridge = new WebViewBridge(project, browser, this);
        browser.getJBCefClient().addLoadHandler(loadHandler, browser.getCefBrowser());
        cardPanel.add(browser.getComponent(), CARD_BROWSER);
        cards.show(cardPanel, CARD_BROWSER);
        addressBar.setText(url);
        pendingLoad.markLoaded();
        browser.loadURL(url);
    }

    /**
     * The Home URL. The bridge's actual port wins when it has one: the page being shown may be a page on
     * an old port, an asset, or a {@code file:} page, and deriving Home from that URL is why the button
     * only appeared to work on some pages. A custom/current origin is the fallback when this IDE has no
     * local bridge.
     */
    static @Nullable String homeUrl(@Nullable String currentUrl, int boundPort) {
        if (boundPort > 0) {
            return "http://127.0.0.1:" + boundPort + "/app/index.html";
        }
        String origin = originOf(currentUrl);
        return origin == null ? null : origin + "/app/index.html";
    }

    /** The scheme/host/port origin of an http(s) URL, or null for a data:/file: page. */
    private static @Nullable String originOf(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                return null;
            }
            return scheme + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public boolean canGoBack() {
        return !disposed && browser.getCefBrowser().canGoBack();
    }

    public boolean canGoForward() {
        return !disposed && browser.getCefBrowser().canGoForward();
    }

    public void goBack() {
        if (canGoBack()) {
            browser.getCefBrowser().goBack();
        }
    }

    public void goForward() {
        if (canGoForward()) {
            browser.getCefBrowser().goForward();
        }
    }

    @NotNull String getAddressBarText() {
        return addressBar.getText();
    }

    /** Loads whatever is in the address bar; used by the panel and by the tests' entry point. */
    public void loadFromAddressBar() {
        String text = addressBar.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        // A Markdown file is rendered by the IDE rather than loaded as a page, and the address bar is where a
        // person pastes the path to one. LocalFileUrls is the helper the remembered-URL fix already uses for the
        // file: form, so a path copied out of a browser works here too (2026-10-04).
        java.nio.file.Path pasted = LocalFileUrls.pathOf(text);
        String markdownPath = pasted != null ? pasted.toString() : text;
        if (MarkdownView.isMarkdown(markdownPath)) {
            // Rendered here or not at all: a .md that fell through would be handed to the page loader as a URL,
            // which is a confusing way to say "outside the project" or "unreadable".
            if (!MarkdownView.getInstance(project).show(markdownPath)) {
                showBrowserError("Could not open " + markdownPath + " as a Markdown view. It may be outside the project, "
                        + "missing, or unreadable.");
            }
            return;
        }
        UrlNormalizer.Normalized normalized =
                UrlNormalizer.normalize(text, project.getBasePath());
        if (normalized == null) {
            return;
        }
        if (normalized.unresolved()) {
            showBrowserError("No file at " + normalized.localPath());
            return;
        }
        browser.loadURL(normalized.url());
    }

    private void onMainFrameLoaded(@Nullable String url) {
        if (disposed || url == null) {
            return;
        }
        addressBar.setText(url);
        cards.show(cardPanel, CARD_BROWSER);
        // A raw /source/ response is not a page this panel can restore: JCEF has already aborted it once,
        // and remembering it makes every later restart open on the same error card. The app's source links
        // open the IDE; anything that still reaches here (middle click, address bar) is view-only.
        if (!isSourceUrl(url)) {
            PluginStateService.getInstance(project).setLastUrl(url);
        }
    }

    /** True for {@code /source/...}, the raw file route the app uses for source links. */
    static boolean isSourceUrl(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String path = java.net.URI.create(url).getPath();
            return path != null && (path.equals("/source") || path.startsWith("/source/"));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * True for the URLs JCEF itself uses, which are not pages the developer asked for and whose failing
     * to load is not an error worth reporting.
     */
    static boolean isInternalBrowserUrl(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("about:") || lower.startsWith("file:///jbcefbrowser/");
    }

    private void showBrowserError(@NotNull String message) {
        if (disposed) {
            return;
        }
        errorLabel.setText("<html><body style='width:420px;text-align:center'>"
                + message.replace("\n", "<br>") + "</body></html>");
        cards.show(cardPanel, CARD_ERROR);
    }

    private @NotNull JComponent createErrorComponent() {
        errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
        return errorLabel;
    }

    private @NotNull JComponent createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new WebViewActions.Back(this));
        group.add(new WebViewActions.Forward(this));
        group.add(new WebViewActions.Home(this));
        group.add(new WebViewActions.Refresh(this));
        group.add(new WebViewActions.Settings(project));

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("JcefToolWindowToolbar", group, true);
        toolbar.setTargetComponent(addressBar);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(toolbar.getComponent(), BorderLayout.WEST);
        panel.add(addressBar, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Installs the parts of the bridge that only make sense in a live browser: IDE-styled scrollbars
     * for the page. Kept separate from the bridge itself because it is presentation, not the contract.
     */
    public void applyIdeStyling() {
        if (disposed) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) {
                return;
            }
            browser.getCefBrowser().executeJavaScript(
                    JBCefScrollbarsHelper.buildScrollbarsStyle(), browser.getCefBrowser().getURL(), 0);
        }, ModalityState.any());
    }

    @Override
    public void dispose() {
        disposed = true;
        try {
            browser.getJBCefClient().removeLoadHandler(loadHandler, browser.getCefBrowser());
        } catch (RuntimeException e) {
            // The browser may already be gone; disposal must not fail because of it.
            LOG.debug("WebView Explorer: could not remove the load handler on dispose", e);
        }
        Disposer.dispose(browser);
    }

    /** Exposed so the factory can put the right object in the content's client properties. */
    @NotNull JBCefBrowserBase browserBase() {
        return browser;
    }
}
