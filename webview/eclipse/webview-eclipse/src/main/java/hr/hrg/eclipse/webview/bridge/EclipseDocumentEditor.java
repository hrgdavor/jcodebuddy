package hr.hrg.eclipse.webview.bridge;

import java.util.List;

import hr.hrg.webview.core.TextEdit;

/**
 * The seam for the buffer half of the write contract (E8, E10): apply one-based line-and-column
 * edits to the document the platform holds for a path, in one undo step, without saving.
 *
 * <p>An interface rather than the platform class for the reason every seam in this product exists:
 * the caller must be able to decide <em>that</em> a host can carry an edit without depending on
 * <em>how</em> it does — and a plain JUnit run must be able to substitute a fake, because there is
 * no supported headless SWT (R22). The Eclipse implementation is {@link DocumentBufferEditor}; the
 * JetBrains twin of this seam is its {@code IdeDocumentEditor}.
 *
 * <p>{@code false} means "this host cannot carry it" — never "something broke". What happens next
 * belongs to core's {@code WriteSurface}, which decides the routing and the statuses for every
 * host: a declined buffer edit falls back to the host's own disk write, the documented asymmetry
 * of the write contract. An implementation of this seam must not invent statuses of its own.
 */
public interface EclipseDocumentEditor {

    /**
     * Applies the edits to the document holding {@code absolutePath}, on the UI thread, inside one
     * compound change the reader can take back with a single undo, and without saving.
     *
     * @param absolutePath an absolute, forward-slashed path that has already passed the path jail
     * @param edits        the one-based edits core's {@code DocumentEdits} semantics describe
     * @return true only when the buffer now carries the change — or already did
     */
    boolean apply(String absolutePath, List<TextEdit> edits);
}
