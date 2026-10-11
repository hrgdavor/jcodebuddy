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

import java.nio.file.Files;
import java.nio.file.Path;

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
        // A remembered URL is a convenience, not a promise: the file it named may have moved (this repository's own
        // kit examples did, out from under a URL the tool window kept restoring), and the window then showed an
        // error page on every open. Validate it, forget it, and say so - a stale convenience that explains itself
        // is not a bug report.
        if (LocalFileUrls.missing(lastUrl)) {
            LOG.info("WebView Explorer: forgetting the remembered URL, its file is gone: " + lastUrl);
            PluginStateService.getInstance(project).setLastUrl("");
            lastUrl = "";
        }

        // A restart brings the window back on the page URL - and that page is a snapshot of a Markdown document with
        // nothing behind it: one page file serves every document, so the URL cannot say which file it holds, and the
        // save refresh matches an editor path. The window looked right and stopped refreshing until the file was
        // opened again (reported 2026-10-11). So when the remembered URL is our Markdown page, the document it was
        // showing is rendered again instead of loading the stale snapshot: current content, and the link back.
        MarkdownView view = MarkdownView.getInstance(project);
        String base = project.getBasePath();
        String remembered = view.lastDocument();
        if (remembered.isBlank()) {
            // The first restart after this change remembers no document: an older build recorded only the URL. The
            // page on disk was rendered from one, so it is asked which (and the answer is remembered from then on).
            remembered = view.documentInCurrentPage();
        }
        boolean rememberedExists = base != null && !remembered.isBlank()
                && Files.isRegularFile(Path.of(remembered));
        String document = base == null ? null
                : MarkdownView.documentToRestore(lastUrl, remembered, base, rememberedExists);
        MarkdownView.Rendered restored = document == null ? null : view.render(document);
        if (document != null && restored == null) {
            LOG.info("WebView Explorer: the remembered Markdown document could not be rendered: " + document);
        }
        String fallback = restored != null ? restored.pageUrl()
                : lastUrl == null || lastUrl.isBlank() ? SplashPage.URL : lastUrl;
        WebViewService.getInstance(project).register(panel, fallback);
        panel.applyIdeStyling();
    }

    private static void addFallbackContent(@NotNull ToolWindow toolWindow, @NotNull UnsupportedBrowserPanel panel) {
        Content content = ContentFactory.getInstance().createContent(panel.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
