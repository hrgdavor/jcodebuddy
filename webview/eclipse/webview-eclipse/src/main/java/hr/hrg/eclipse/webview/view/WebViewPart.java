package hr.hrg.eclipse.webview.view;

import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

import hr.hrg.eclipse.webview.WebViewPlugin;
import hr.hrg.eclipse.webview.bridge.EclipseBridge;
import hr.hrg.eclipse.webview.bridge.EclipseNavigator;
import hr.hrg.eclipse.webview.bridge.WorkspaceFiles;
import hr.hrg.eclipse.webview.http.EclipseHttpBridge;
import hr.hrg.eclipse.webview.prefs.WebViewPreferences;

/**
 * The view: it hosts the SWT {@link Browser} and loads pages into it. A navigation that comes
 * from a page goes through the bridge to the navigator; a load that comes from the action (or
 * from code) goes through {@link #loadFile}. When the browser engine is not usable, the view
 * shows the problem instead of a dead pane, and the rest of the host keeps working.
 *
 * <p>Two things are true no matter how a page got here: the navigator is built from the plugin's
 * shared editor host and shared rate budget — so the page bridge and the HTTP bridge cannot charge
 * a page twice — and the bridge script is re-injected on every page modification, whichever URL
 * produced the document.
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
            WebViewPlugin plugin = WebViewPlugin.getInstance();
            bridge = new EclipseBridge(browser, new EclipseNavigator(WorkspaceFiles::activeProjectRoot,
                    plugin.editorHost(), plugin.rateLimiter()));
            // SWT propagates a child widget's events to listeners on its parent, so this parent
            // listener sees each page modification and re-injects the bridge script into the
            // new document, which the previous one's navigation would otherwise have cleared.
            parent.addListener(SWT.Modify, e -> bridge.inject());
        }
        browser.setUrl(SplashPage.url());
    }

    /**
     * Loads a workspace file into the browser, by its project's bridge when one is up and by the
     * file's own URL when it is not.
     *
     * <p>The bridge route (E12) is the page's same-origin home: the token rides in the URL because a
     * browser's top-level navigation sends no headers, and the injected script lands in a page served
     * by the very host that registered {@code jcbBridge} in this widget. The file URL is not a
     * downgrade to fear — it is Phase 1's behaviour, and it is what a project without a named port
     * (E17) or a refused claim gets. The first load of a project asks the plugin to bring the bridge
     * up and loads the page when the answer arrives; a later {@code loadFile} for another file
     * supersedes it in the UI queue, so the page the reader last asked for is the page they get.
     */
    public void loadFile(IFile file) {
        if (browser == null || browser.isDisposed()) {
            return;
        }
        Path projectRoot = projectRootOf(file);
        WebViewPlugin plugin = projectRoot == null ? null : WebViewPlugin.getInstance();
        if (plugin == null) {
            loadFromFileUrl(file);
            return;
        }
        EclipseHttpBridge running = plugin.bridgeFor(projectRoot);
        if (running != null) {
            browser.setUrl(running.pageUrl(locationOf(file)));
            return;
        }
        plugin.ensureBridge(projectRoot, started -> {
            if (browser == null || browser.isDisposed()) {
                return;
            }
            if (started != null) {
                browser.setUrl(started.pageUrl(locationOf(file)));
            } else {
                loadFromFileUrl(file);
            }
        });
    }

    /**
     * The Phase 1 load: the file's own URL, its origin the file's. The platform's
     * {@code IResource.getLocationURI} gives that URL directly; this JDK's {@code URI} has no
     * {@code toExternalForm}, and for an absolute URI {@code toString} is the external form.
     */
    private void loadFromFileUrl(IFile file) {
        browser.setUrl(file.getLocationURI().toString());
    }

    /** The file's project on-disk root, or null when the workspace cannot name one. */
    private static Path projectRootOf(IFile file) {
        String root = WorkspaceFiles.projectLocation(file.getProject());
        return root == null || root.isBlank() ? null : Path.of(root);
    }

    private static Path locationOf(IFile file) {
        // IPath.toNIO is not in this train's equinox.common; File.toPath is universal.
        return file.getLocation().toFile().toPath();
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
