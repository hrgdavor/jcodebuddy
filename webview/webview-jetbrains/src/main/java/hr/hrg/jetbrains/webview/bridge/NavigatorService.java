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
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.NavigationOutcome;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.PathResolution;
import hr.hrg.webview.core.PathResolver;
import hr.hrg.webview.core.RateLimiter;
import hr.hrg.webview.core.TextEdit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import java.util.Set;

/**
 * The JetBrains host: the one place that turns a path into an open editor with the caret on a line.
 *
 * <p>Everything that is not about JetBrains has moved to {@code webview-core}, where the other hosts
 * share it: which path a page named ({@link PathResolver}), whether it may be opened at all, and how
 * often ({@link Navigator}, with the sliding window in {@link RateLimiter}). What is left here is the
 * IDE half — the VFS lookup, the rate-limit policy this host chooses, and the editor call.
 *
 * <p>This is deliberately the <b>only</b> place that does those three things. The first implementation
 * rate-limited inside the JCEF path <em>and</em> again inside the HTTP handler, so one click could be
 * charged twice; now both transports ask this service for its {@link #navigator()} and the limit is
 * acquired exactly once, in core, whichever transport the request arrived on.
 */
@Service(Service.Level.PROJECT)
public final class NavigatorService implements EditorHost {

    private static final Logger LOG = Logger.getInstance(NavigatorService.class);

    private final Project project;
    private final PathResolver pathResolver;
    private final Navigator navigator;
    private final RateLimiter rateLimiter;
    private final IdeDocumentEditor documentEditor;

    public NavigatorService(@NotNull Project project) {
        this(project, defaultRateLimiter());
    }

    /**
     * @param rateLimiter the policy this host applies, shared by both of its transports
     */
    public NavigatorService(@NotNull Project project, @NotNull RateLimiter rateLimiter) {
        this(project, rateLimiter, new WriteCommandEditor(project));
    }

    /**
     * @param documentEditor how this host applies a buffer edit; the production one is
     *                       {@link WriteCommandEditor}, and a test substitutes its own because a
     *                       {@code WriteCommandAction} needs a real IDE
     */
    public NavigatorService(@NotNull Project project, @NotNull RateLimiter rateLimiter,
                            @NotNull IdeDocumentEditor documentEditor) {
        this.project = project;
        this.rateLimiter = rateLimiter;
        this.documentEditor = documentEditor;
        this.pathResolver = PathResolver.forProject(projectBasePath(project));
        // false: a tool window drives its own IDE, and a report may legitimately link to a file the user
        // opened from outside the project. The sidecar's HTTP surface sets this true, because there the
        // caller can be any page in the user's browser.
        this.navigator = new Navigator(projectBasePath(project), this, false, rateLimiter);
    }

    public static @NotNull NavigatorService getInstance(@NotNull Project project) {
        return project.getService(NavigatorService.class);
    }

    /** The production policy: {@value Navigator#RATE_LIMIT_COUNT} navigations per 20 seconds. */
    public static @NotNull RateLimiter defaultRateLimiter() {
        return new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS,
                hr.hrg.webview.core.Clock.SYSTEM);
    }

    /**
     * The funnel both transports share. Callers that want to know <em>why</em> a request failed — which
     * is what the HTTP handler needs to answer a real status — use this and read the outcome; {@link
     * #open} is the boolean convenience the injected bridge uses.
     */
    public @NotNull Navigator navigator() {
        return navigator;
    }

    /**
     * Opens {@code filePath} at a one-based line and column.
     *
     * @return true when the file was found and an editor was opened; false when the path could not be
     *         resolved, the rate limit refused the request, or the file does not exist
     */
    public boolean open(@Nullable String filePath, int line, int column) {
        NavigationOutcome outcome = navigator.open(filePath, line, column);
        if (!outcome.succeeded()) {
            logRefusal(outcome, filePath);
        }
        return outcome.succeeded();
    }

    /** Opens a URL, placing the caret on the line named by a {@code #L42} fragment if there is one. */
    public boolean openUrl(@Nullable String url) {
        NavigationOutcome outcome = navigator.openUrl(url);
        if (!outcome.succeeded()) {
            logRefusal(outcome, url);
        }
        return outcome.succeeded();
    }

    /** Parses {@code L42} / {@code 42} out of a URL fragment; 1 when it names no line. */
    static int lineFromFragment(@Nullable String fragment) {
        return Navigator.lineFromFragment(fragment);
    }

    /** The absolute, forward-slashed path a page's spelling resolves to, without opening anything. */
    public @Nullable PathResolution resolve(@Nullable String filePath) {
        return pathResolver.resolve(filePath);
    }

    @Override
    public @NotNull String name() {
        return "jetbrains";
    }

    @Override
    public boolean isAvailable() {
        return !project.isDisposed();
    }

    @Override
    public @NotNull Set<String> capabilities() {
        // CAP_REVEAL is deliberately absent: "reveal in the project view" needs a platform API that this
        // plugin has not verified against the pinned 2026.2.3 build, and advertising a capability that
        // throws at runtime would turn a page's fallback ladder into a broken link. It is added when it
        // is implemented and verified, not before.
        //
        // CAP_EDIT is present since 2026-09-25: a page may ask for an edit and have it land in this editor's
        // buffer, where the reader's own Ctrl+Z takes it back (WriteCommandEditor). It is advertised because it
        // has been implemented, which is the rule the note above is about.
        return Set.of(CAP_OPEN, CAP_SELECT, CAP_EDIT);
    }

    @Override
    public boolean applyEdit(@NotNull String absolutePath, @NotNull List<TextEdit> edits) {
        return documentEditor.apply(absolutePath, edits);
    }

    /** The rate limiter this service shares with everything else in the project's bridge. */
    public @NotNull RateLimiter rateLimiter() {
        return rateLimiter;
    }

    @Override
    public boolean openFileAt(@NotNull String absolutePath, int line, int column) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(absolutePath);
        if (file == null) {
            // Returning false here is what lets the caller answer 404, so a wrong path is diagnosable
            // instead of looking like a dead link.
            LOG.warn("WebView bridge: no file found at '" + absolutePath + "'");
            return false;
        }

        int lineIndex = Math.max(0, line - 1);
        int columnIndex = Math.max(0, column - 1);
        ApplicationManager.getApplication().invokeLater(() -> openInEditor(file, lineIndex, columnIndex));
        return true;
    }

    @Override
    public boolean select(@NotNull String absolutePath, hr.hrg.webview.core.TextRange range) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(absolutePath);
        if (file == null) {
            return false;
        }
        hr.hrg.webview.core.TextRange clamped = range.clamped();
        int startLine = clamped.startLine() - 1;
        int startColumn = clamped.startColumn() - 1;
        int endLine = clamped.endLine() - 1;
        int endColumn = clamped.endColumn() - 1;
        ApplicationManager.getApplication().invokeLater(() -> {
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, startLine, startColumn);
            Editor editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
            if (editor == null) {
                return;
            }
            LogicalPosition start = new LogicalPosition(startLine, startColumn);
            LogicalPosition end = new LogicalPosition(endLine, endColumn);
            editor.getSelectionModel().setSelection(
                    editor.logicalPositionToOffset(start), editor.logicalPositionToOffset(end));
            editor.getCaretModel().moveToLogicalPosition(start);
        });
        return true;
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

    /**
     * A broken link must be diagnosable instead of silent, which is why the reason is logged with the
     * path: "no such file" and "the rate limit refused you" used to look identical to a caller.
     */
    private void logRefusal(@NotNull NavigationOutcome outcome, @Nullable String requested) {
        LOG.warn("WebView bridge: " + outcome.reason() + " for '" + requested + "'"
                + (outcome.detail().isEmpty() ? "" : " (" + outcome.detail() + ")"));
    }

    private static @Nullable String projectBasePath(@NotNull Project project) {
        String base = project.getBasePath();
        if (base != null) {
            return base;
        }
        // A project created from a directory that no longer exists has no base path; ProjectUtil is the
        // platform's own fallback and returns null when there is genuinely nothing.
        VirtualFile dir = ProjectUtil.guessProjectDir(project);
        return dir == null ? null : dir.getPath();
    }
}
