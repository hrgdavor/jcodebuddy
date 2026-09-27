package hr.hrg.eclipse.webview.view;

import hr.hrg.eclipse.webview.bridge.EclipseBridge;
import hr.hrg.eclipse.webview.bridge.EclipseNavigator;
import hr.hrg.eclipse.webview.prefs.WebViewPreferences;

import org.eclipse.core.resources.IFile;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/**
 * The view: it hosts the SWT {@link Browser} and loads pages into it. A navigation that comes
 * from a page goes through the bridge to the navigator; a load that comes from the action (or
 * from code) goes through {@link #loadFile}. When the browser engine is not usable, the view
 * shows the problem instead of a dead pane, and the rest of the host keeps working.
 */
public class WebViewPart extends ViewPart {

    /** The view's id, as plugin.xml declares it. */
    public static final String ID = "hr.hrg.eclipse.webview.views.WebView";

    private Browser browser;
    private EclipseBridge bridge;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        BrowserFactory.Engine engine = BrowserFactory.create(parent);
        if (!engine.supported()) {
            new UnsupportedEnginePanel(parent, engine);
            return;
        }
        browser = engine.browser();
        browser.setJavascriptEnabled(true);
        if (WebViewPreferences.bridgeEnabled()) {
            bridge = new EclipseBridge(browser, new EclipseNavigator());
            // SWT propagates a child widget's events to listeners on its parent, so this parent
            // listener sees each page modification and re-injects the bridge script into the
            // new document, which the previous one's navigation would otherwise have cleared.
            parent.addListener(SWT.Modify, e -> bridge.inject());
        }
        browser.setUrl(SplashPage.url());
    }

    /**
     * Loads a workspace file into the browser by its on-disk location, so the page's origin is
     * the file's own URL and the bridge's rules for file pages apply to it. The platform's
     * {@code IResource.getLocationURI} gives that URL directly; this JDK's {@code URI} has no
     * {@code toExternalForm}, and for an absolute URI {@code toString} is the external form.
     */
    public void loadFile(IFile file) {
        if (browser == null || browser.isDisposed()) {
            return;
        }
        browser.setUrl(file.getLocationURI().toString());
    }

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            browser.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (bridge != null) {
            bridge.dispose();
        }
        if (browser != null && !browser.isDisposed()) {
            browser.dispose();
        }
        super.dispose();
    }
}
