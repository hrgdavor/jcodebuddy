package hr.hrg.jetbrains.webview.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import hr.hrg.jetbrains.webview.toolWindow.WebViewToolWindow;
import org.jetbrains.annotations.NotNull;

/** Toggles the WebView Explorer tool window; bound to Ctrl+Alt+Shift+W. */
public final class ToggleToolWindowAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(ToggleToolWindowAction.class);

    /**
     * The tool window's id, kept in {@link WebViewToolWindow} because the service and the factory need
     * it too. Retained here as a deprecated alias so the name in {@code plugin.xml} and any external
     * reference keep resolving.
     *
     * @deprecated use {@link WebViewToolWindow#ID}
     */
    @Deprecated
    public static final String TOOL_WINDOW_ID = WebViewToolWindow.ID;

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(WebViewToolWindow.ID);
        if (toolWindow == null) {
            LOG.warn("WebView Explorer tool window is not registered");
            return;
        }

        if (toolWindow.isVisible()) {
            toolWindow.hide(null);
        } else {
            toolWindow.activate(null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(project != null
                && ToolWindowManager.getInstance(project).getToolWindow(WebViewToolWindow.ID) != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
