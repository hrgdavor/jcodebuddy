package hr.hrg.jetbrains.webview.bridge;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * The one place that turns a path into an open editor with the caret on a line.
 *
 * <p>Both the injected {@code window.openFile} bridge and the HTTP fallback funnel through here, so
 * the rate limit and the path resolution cannot drift apart between the two entry points — the first
 * implementation limited the JCEF path inside {@code navigateToFile} and the HTTP path again in its
 * handler, which charged one click twice.
 */
@Service(Service.Level.PROJECT)
public final class NavigatorService {

    private static final Logger LOG = Logger.getInstance(NavigatorService.class);

    /** At most this many navigations per {@link #RATE_LIMIT_WINDOW_MS}. */
    private static final int RATE_LIMIT_COUNT = 20;
    private static final long RATE_LIMIT_WINDOW_MS = 20_000L;

    private final Project project;
    private final RateLimiter rateLimiter = new RateLimiter(RATE_LIMIT_COUNT, RATE_LIMIT_WINDOW_MS, Clock.SYSTEM);

    public NavigatorService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull NavigatorService getInstance(@NotNull Project project) {
        return project.getService(NavigatorService.class);
    }

    /**
     * Opens {@code filePath} at a one-based line and column.
     *
     * @return true when the file was found and an editor was opened; false when the path could not be
     *         resolved or the rate limit refused the request
     */
    public boolean open(@Nullable String filePath, int line, int column) {
        if (filePath == null || filePath.isBlank()) {
            LOG.warn("WebView bridge: refusing to open an empty path");
            return false;
        }
        if (!rateLimiter.tryAcquire()) {
            LOG.warn("WebView bridge: rate limit reached (" + RATE_LIMIT_COUNT + " per "
                    + (RATE_LIMIT_WINDOW_MS / 1000) + "s); ignoring request for " + filePath);
            return false;
        }

        String absolute = resolvePath(filePath);
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(absolute);
        if (file == null) {
            // The first implementation returned silently here, which made a wrong path look like a
            // dead link. Say so, and answer the HTTP caller with 404.
            LOG.warn("WebView bridge: no file found at '" + absolute + "' (from '" + filePath + "')");
            return false;
        }

        int lineIndex = Math.max(0, line - 1);
        int columnIndex = Math.max(0, column - 1);
        ApplicationManager.getApplication().invokeLater(() -> openInEditor(file, lineIndex, columnIndex));
        return true;
    }

    /** Opens a URL, placing the caret on the line named by a {@code #L42} fragment if there is one. */
    public boolean openUrl(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String path = url;
        int line = 1;
        int hash = url.indexOf('#');
        if (hash >= 0) {
            path = url.substring(0, hash);
            line = lineFromFragment(url.substring(hash + 1));
        }
        String localPath = UrlNormalizer.localPathOf(path);
        return open(localPath != null ? localPath : path, line, 1);
    }

    /** Parses {@code L42} / {@code 42} out of a URL fragment; 1 when it names no line. */
    static int lineFromFragment(@Nullable String fragment) {
        if (fragment == null || fragment.isEmpty()) {
            return 1;
        }
        String digits = fragment.startsWith("L") || fragment.startsWith("l") ? fragment.substring(1) : fragment;
        try {
            return Math.max(1, Integer.parseInt(digits.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Absolute, forward-slashed path for the local file system. */
    private @NotNull String resolvePath(@NotNull String filePath) {
        String normalized = filePath.replace('\\', '/');
        if (new File(normalized).isAbsolute()) {
            return normalized;
        }
        String base = project.getBasePath();
        if (base == null) {
            return normalized;
        }
        return base.replace('\\', '/') + "/" + normalized;
    }

    private void openInEditor(@NotNull VirtualFile file, int lineIndex, int columnIndex) {
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, lineIndex, columnIndex);
        Editor editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        if (editor == null) {
            return;
        }
        LogicalPosition position = new LogicalPosition(lineIndex, columnIndex);
        editor.getCaretModel().removeSecondaryCarets();
        editor.getCaretModel().moveToLogicalPosition(position);
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        editor.getSelectionModel().removeSelection();
    }
}
