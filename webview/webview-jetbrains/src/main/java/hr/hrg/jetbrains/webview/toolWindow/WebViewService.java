package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Owns the live {@link WebViewPanel} for a project, and the ordering rule for its first page.
 *
 * <p>This exists so nothing else has to find the browser by walking the tool window's Swing tree or by
 * assuming a content index — which is what the first implementation did from a static
 * {@code reloadWithFile} method.
 */
@Service(Service.Level.PROJECT)
public final class WebViewService {

    private final Project project;
    private final PendingLoad pendingLoad = new PendingLoad();

    private volatile WebViewPanel panel;

    public WebViewService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull WebViewService getInstance(@NotNull Project project) {
        return project.getService(WebViewService.class);
    }

    /** The panel if the tool window has been created and not since disposed. */
    public @Nullable WebViewPanel getPanel() {
        WebViewPanel current = panel;
        return current != null && !current.isDisposed() ? current : null;
    }

    /**
     * Adopts a freshly created panel and decides its first page: the URL that was requested before the
     * tool window existed if there is one, otherwise {@code fallbackUrl}.
     *
     * <p>The decision lives here, in one place, because splitting it between this method and the
     * factory is what produced the bug where a parked file URL was immediately overwritten by the
     * splash page.
     *
     * @return true when a parked URL won over the fallback
     */
    boolean register(@NotNull WebViewPanel created, @NotNull String fallbackUrl) {
        this.panel = created;
        if (created.deliverPending()) {
            return true;
        }
        created.load(fallbackUrl);
        return false;
    }

    void unregister(@NotNull WebViewPanel disposed) {
        if (this.panel == disposed) {
            this.panel = null;
        }
    }

    /**
     * Loads a URL or path in the tool window, showing the tool window and creating its content when
     * needed.
     *
     * @return false only when the entry is blank or the tool window is not registered at all
     */
    public boolean openInPanel(@Nullable String urlOrPath) {
        if (urlOrPath == null || urlOrPath.isBlank()) {
            return false;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(WebViewToolWindow.ID);
        if (toolWindow == null) {
            return false;
        }

        WebViewPanel current = getPanel();
        if (current != null) {
            return current.load(urlOrPath);
        }

        // No panel yet: park the URL and let the panel deliver it as it is constructed, then show the
        // tool window. Loading it here instead would race with the factory's own first-page choice.
        ready().request(urlOrPath);
        toolWindow.activate(null);
        return true;
    }

    /** The ordering rule for this project's first page. */
    @NotNull PendingLoad ready() {
        return pendingLoad;
    }
}
