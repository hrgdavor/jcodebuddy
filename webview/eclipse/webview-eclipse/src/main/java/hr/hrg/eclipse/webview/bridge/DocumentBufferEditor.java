package hr.hrg.eclipse.webview.bridge;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import hr.hrg.webview.core.DocumentEdits;
import hr.hrg.webview.core.TextEdit;

/**
 * The Eclipse implementation of the {@link EclipseDocumentEditor} seam (E8): it finds the document
 * the platform <em>already holds</em> for the path, computes the new text with core's
 * {@link DocumentEdits}, and sets it inside one {@link IRewriteTarget} compound change — the
 * platform's own route to "one {@code Ctrl+Z} takes it back" (R16) — without ever saving, because
 * the reader decides when the file on disk changes (R17).
 *
 * <p>An editor that is not open is <b>not</b> opened here: a write that surprises the reader with a
 * tab they never asked for is worse than the honest refusal. A path with no open text editor
 * therefore answers false, and core's {@code WriteSurface} — which owns the statuses, not this
 * class — takes its documented next step (for this host, the disk half of the write contract).
 *
 * <p>Every call arrives on the UI thread: the HTTP transport marshals through
 * {@link hr.hrg.eclipse.webview.http.UiThreadHost}, and the page bridge is delivered there by the
 * browser itself. The empty-edit guard runs before any platform type is touched, which is the one
 * branch of this class a plain JUnit run can reach (E10); the rest is the observed half of the
 * Phase 3 gate.
 */
public final class DocumentBufferEditor implements EclipseDocumentEditor {

    @Override
    public boolean apply(String absolutePath, List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            // Nothing to change; "applying" nothing anyway would add an empty step to the undo stack.
            return false;
        }
        IWorkbenchPage page = EclipseEditorHost.activePage();
        if (page == null) {
            return false;
        }
        IFile file = WorkspaceFiles.find(absolutePath);
        if (file == null) {
            return false;
        }
        IEditorPart part = page.findEditor(new FileEditorInput(file));
        if (!(part instanceof ITextEditor textEditor)) {
            // Not open, or open in something that is not a text editor: there is no buffer to carry
            // the change, and this class does not open one as a side effect of a write.
            return false;
        }
        IRewriteTarget target = textEditor.getAdapter(IRewriteTarget.class);
        IDocument document = target != null ? target.getDocument()
                : EclipseEditorHost.documentOf(textEditor);
        if (document == null) {
            return false;
        }
        String newText;
        try {
            newText = DocumentEdits.newText(document.get(), edits);
        } catch (IllegalArgumentException e) {
            // The edits do not address this document's content (a bad position, an overlap):
            // refused, never guessed at.
            return false;
        }
        if (newText.equals(document.get())) {
            // Already there: applied, with nothing added to the undo stack.
            return true;
        }
        if (target == null) {
            // Without the rewrite target there is no compound change, and a bare document.set
            // could land as several undo steps. The promise is ONE Ctrl+Z (R16), so this declines
            // rather than half-keep it; WriteSurface's documented fallback takes over.
            return false;
        }
        target.setRedraw(false);
        try {
            target.beginCompoundChange();
            try {
                document.set(newText);
            } finally {
                target.endCompoundChange();
            }
        } finally {
            target.setRedraw(true);
        }
        return true;
    }
}
