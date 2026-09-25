package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
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
import hr.hrg.jetbrains.webview.bridge.UrlNormalizer;
import hr.hrg.jetbrains.webview.bridge.WebViewBridge;
import hr.hrg.jetbrains.webview.services.PluginStateService;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
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
    private final JBCefBrowser browser;
    private final PendingLoad pendingLoad;
    private final JBTextField addressBar = new JBTextField();
    private final SimpleToolWindowPanel root;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JBLabel errorLabel = new JBLabel();
    private final WebViewBridge bridge;

    /** The one load handler for this browser, so nothing has to guess the registration order. */
    private final CefLoadHandlerAdapter loadHandler;

    private volatile boolean disposed;

    public WebViewPanel(@NotNull Project project, @NotNull PendingLoad pendingLoad) {
        this.project = project;
        this.pendingLoad = pendingLoad;
        this.browser = JBCefBrowser.createBuilder()
                .setEnableOpenDevToolsMenuItem(true)
                .build();
        this.bridge = new WebViewBridge(project, browser, this);

        this.loadHandler = new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (frame != null && !frame.isMain()) {
                    return;
                }
                String url = cefBrowser.getURL();
                SwingUtilities.invokeLater(() -> onMainFrameLoaded(url));
                bridge.install(cefBrowser);
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, ErrorCode errorCode,
                                    String errorText, String failedUrl) {
                if (frame != null && !frame.isMain()) {
                    return;
                }
                if (isInternalBrowserUrl(failedUrl)) {
                    // JCEF reports a failed load of its own initial about:blank page on every browser
                    // creation. Showing an error card for it would greet every first open with a
                    // failure that is not one.
                    return;
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
        if (disposed || !pendingLoad.request(urlOrPath)) {
            return false;
        }
        return pendingLoad.deliverTo(this::loadNow);
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
        browser.loadURL(normalized.url());
        return true;
    }

    /** Reloads whatever the browser is currently showing. */
    public void reload() {
        browser.getCefBrowser().reload();
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
        PluginStateService.getInstance(project).setLastUrl(url);
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
