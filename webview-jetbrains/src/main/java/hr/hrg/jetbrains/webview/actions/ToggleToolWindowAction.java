package hr.hrg.jetbrains.webview.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public class ToggleToolWindowAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null)
            return;

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("WebView Explorer");
        if (toolWindow == null)
            return;

        if (toolWindow.isVisible()) {
            toolWindow.hide();
        } else {
            toolWindow.activate(null);
        }
    }
}
