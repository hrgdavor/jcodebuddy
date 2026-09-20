package hr.hrg.jetbrains.webview.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import hr.hrg.jetbrains.webview.toolWindow.WebViewService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * "Open in WebView Explorer" for the Project view, editor and editor-tab popups.
 *
 * <p>It hands the URL to {@link WebViewService}, which owns the panel; the first implementation called
 * a static method that searched the tool window's Swing tree for the browser.
 */
public final class OpenFileInWebViewAction extends AnAction implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        VirtualFile file = getFileFromContext(event);
        if (file == null) {
            return;
        }
        WebViewService.getInstance(project).openInPanel(urlFor(file));
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        VirtualFile file = getFileFromContext(event);
        boolean isHtml = file != null && "html".equalsIgnoreCase(file.getExtension());
        event.getPresentation().setEnabledAndVisible(isHtml);
        event.getPresentation().setText("Open in WebView Explorer");
    }

    /**
     * A {@code file:} URL for the virtual file.
     *
     * <p>The cache-busting query the first implementation appended ({@code ?v=<millis>}) is gone on
     * purpose: a page whose links carry {@code data-*} and are resolved against {@code location} does
     * not need it, and a query string makes some browsers treat the page as non-local.
     */
    public static @NotNull String urlFor(@NotNull VirtualFile file) {
        return "file:///" + file.getPath().replace('\\', '/').replaceFirst("^/+", "");
    }

    @Nullable
    private VirtualFile getFileFromContext(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null) {
            VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
            if (files != null && files.length > 0) {
                file = files[0];
            }
        }
        if (file == null) {
            com.intellij.psi.PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
            if (psiFile != null) {
                file = psiFile.getVirtualFile();
            }
        }
        return file;
    }
}
