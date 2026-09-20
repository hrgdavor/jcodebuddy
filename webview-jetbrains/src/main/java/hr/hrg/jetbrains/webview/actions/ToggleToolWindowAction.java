package hr.hrg.jetbrains.webview.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public final class ToggleToolWindowAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ToggleToolWindowAction.class);
    public static final String TOOL_WINDOW_ID = "WebView Explorer";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        ToolWindowManager manager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = manager.getToolWindow(TOOL_WINDOW_ID);
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
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
