package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import hr.hrg.jetbrains.webview.services.PluginStateService;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the WebView Explorer tool window: one {@link WebViewPanel}, registered with
 * {@link WebViewService} so nothing else has to search for it.
 */
public final class JcefToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final Logger LOG = Logger.getInstance(JcefToolWindowFactory.class);

    public JcefToolWindowFactory() {
        // No-arg constructor, which the platform instantiates tool-window factories with. Note that a
        // missing runtime dependency on com.intellij.modules.jcef makes the platform report this
        // constructor as "not found" - see plan.reimplement.md finding F8.
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        if (!JBCefApp.isSupported()) {
            addFallbackContent(toolWindow, new UnsupportedBrowserPanel());
            return;
        }

        WebViewPanel panel;
        try {
            // The panel consumes any URL that was requested before this tool window existed, so that
            // "Open in WebView Explorer" on a file shows the file and not the splash page.
            panel = new WebViewPanel(project, WebViewService.getInstance(project).ready());
        } catch (RuntimeException | LinkageError e) {
            // A LinkageError is what a missing platform module looks like from here; catching it turns
            // a dead tool window into one that explains itself.
            LOG.warn("WebView Explorer could not create its browser", e);
            addFallbackContent(toolWindow, new UnsupportedBrowserPanel(e));
            return;
        }

        Content content = ContentFactory.getInstance().createContent(panel.getComponent(), "", false);
        // The content disposes the panel, which disposes the browser and the JS query with it.
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);

        // One decision, in one place: deliver the URL that was requested before this tool window
        // existed, or fall back to the last URL (or the splash page). Previously the factory delivered
        // the parked URL and then loaded the fallback on top of it, so "Open in WebView Explorer" on a
        // file showed the splash page and never rendered the file.
        String lastUrl = PluginStateService.getInstance(project).getLastUrl();
        String fallback = lastUrl == null || lastUrl.isBlank() ? SplashPage.URL : lastUrl;
        WebViewService.getInstance(project).register(panel, fallback);
        panel.applyIdeStyling();
    }

    private static void addFallbackContent(@NotNull ToolWindow toolWindow, @NotNull UnsupportedBrowserPanel panel) {
        Content content = ContentFactory.getInstance().createContent(panel.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
