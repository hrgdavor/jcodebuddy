package hr.hrg.jetbrains.webview.bridge;

import hr.hrg.webview.core.TextEdit;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * How a host puts an edit into the editor the reader is looking at, instead of into the file on disk.
 *
 * <p>An interface rather than the platform class directly, for the reason every seam in this product exists: the
 * caller — {@link hr.hrg.webview.core.WriteSurface}, through {@link NavigatorService} — must be able to decide
 * <em>that</em> a host can carry an edit without depending on <em>how</em> that host does it. The JetBrains
 * implementation is {@link WriteCommandEditor}; a test substitutes its own.
 *
 * <p>{@code false} means "write it yourself": the caller then falls back to its own atomic write, which is the
 * documented asymmetry of the write contract rather than a failure.
 */
public interface IdeDocumentEditor {

    /**
     * Applies one-based, line-and-column edits to the document that holds {@code absolutePath}, inside the IDE's
     * own undoable command, without saving.
     *
     * @return true only when the document was actually changed
     */
    boolean apply(@NotNull String absolutePath, @NotNull List<TextEdit> edits);
}
