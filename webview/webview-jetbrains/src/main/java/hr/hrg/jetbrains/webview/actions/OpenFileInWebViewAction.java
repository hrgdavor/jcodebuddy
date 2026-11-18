package hr.hrg.jetbrains.webview.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import hr.hrg.jetbrains.webview.services.HttpBridgeService;
import hr.hrg.jetbrains.webview.toolWindow.MarkdownView;
import hr.hrg.jetbrains.webview.toolWindow.WebViewService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

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
        Path page = Path.of(file.getPath());
        boolean app = appExists(project);
        if (!app && MarkdownView.isMarkdown(file.getName())
                && MarkdownView.getInstance(project).show(file.getPath())) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String appUrl = HttpBridgeService.getInstance(project).appUrl(page);
            if (appUrl != null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        WebViewService.getInstance(project).openInPanel(appUrl);
                    }
                });
                return;
            }
            // An HTML page is served, not opened as a file: a page that fetches its own JSON (the entity
            // browser does) cannot do that from file://. The bridge serves it under a one-time token path,
            // or an existing host named in the settings serves it; if neither is possible, the file URL is
            // still a usable fallback for a page that needs no fetch.
            String served = HttpBridgeService.getInstance(project).pageUrl(page);
            String url = served != null ? served : urlFor(file);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    WebViewService.getInstance(project).openInPanel(url);
                }
            });
        });
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        VirtualFile file = getFileFromContext(event);
        // A Markdown file is a page this tool window renders itself (2026-10-04): the action offers itself for
        // both, and actionPerformed decides which job it is. When the project has a built app, every file is
        // an app input instead.
        boolean app = file != null && appExists(event.getProject());
        boolean isViewable = file != null
                && (app || "html".equalsIgnoreCase(file.getExtension()) || MarkdownView.isMarkdown(file.getName()));
        event.getPresentation().setEnabledAndVisible(isViewable);
        event.getPresentation().setText("Open in WebView Explorer");
    }

    private static boolean appExists(@Nullable Project project) {
        if (project == null || project.getBasePath() == null) {
            return false;
        }
        if (HttpBridgeService.getInstance(project).isCustomServerConfigured()) {
            return true;
        }
        return java.nio.file.Files.isRegularFile(Path.of(project.getBasePath())
                .resolve(".jcodebuddy").resolve("app").resolve("index.html"));
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
