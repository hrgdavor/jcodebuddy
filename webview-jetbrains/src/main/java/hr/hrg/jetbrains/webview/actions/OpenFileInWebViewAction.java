package hr.hrg.jetbrains.webview.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import hr.hrg.jetbrains.webview.toolWindow.JcefToolWindowFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OpenFileInWebViewAction extends AnAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null)
            return;

        VirtualFile file = getFileFromContext(e);
        if (file == null)
            return;

        // Force file:// protocol and ensure slashes are correct
        String url = "file:///" + file.getPath().replace("\\", "/") + "?v=" + System.currentTimeMillis();
        JcefToolWindowFactory.reloadWithFile(project, url);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = getFileFromContext(e);
        boolean isHtml = file != null && "html".equalsIgnoreCase(file.getExtension());

        e.getPresentation().setEnabledAndVisible(isHtml);
        e.getPresentation().setText("Open in WebView Explorer");
    }

    @Nullable
    private VirtualFile getFileFromContext(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null) {
            VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
            if (files != null && files.length > 0) {
                file = files[0];
            }
        }
        if (file == null) {
            com.intellij.psi.PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
            if (psiFile != null) {
                file = psiFile.getVirtualFile();
            }
        }
        return file;
    }
}
