package hr.hrg.eclipse.webview.bridge;

import java.util.List;
import java.util.Set;

import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.TextEdit;
import hr.hrg.webview.core.TextRange;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * The core's {@link EditorHost} for the Eclipse platform: it opens a workspace file in the
 * platform's text editor and reveals the requested line, selects a span in an already-open
 * editor, and carries a buffer edit through the {@link EclipseDocumentEditor} seam. The editor it
 * works through is the platform's own, so the platform's undo model owns the buffer this host
 * touches — the host never saves and never writes to disk (E8: the disk half of the write contract
 * belongs to core's {@code EditService}, routed by {@code WriteSurface}, not to this class).
 *
 * <p>Availability is asked of the workbench rather than assumed: a plugin loaded into a headless
 * product has no page to act on, and then the honest capability set is the empty one — the same
 * answer {@code webviewd}'s {@code NullHost} gives (plan § 6). Every method here runs on whatever
 * thread the transport uses; the calls that touch widgets are marshalled to the display thread by
 * {@link hr.hrg.eclipse.webview.http.UiThreadHost} before they arrive.
 */
public class EclipseEditorHost implements EditorHost {

    /** The short name /health and the logs use. */
    public static final String NAME = "eclipse";

    private final EclipseDocumentEditor documentEditor;

    /** The production wiring: the platform's own buffer editor behind the seam. */
    public EclipseEditorHost() {
        this(new DocumentBufferEditor());
    }

    /**
     * A test seam: the document editor is injectable, so {@code applyEdit}'s delegation is
     * assertable in a plain JUnit run without a workbench (E10).
     */
    public EclipseEditorHost(EclipseDocumentEditor documentEditor) {
        this.documentEditor = documentEditor;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** True when a workbench page exists to act on; false in a headless product or a plain JVM. */
    @Override
    public boolean isAvailable() {
        return activePage() != null;
    }

    /**
     * {@code edit} is declared because it is implemented — the rule that keeps {@code reveal} off
     * this list until Phase 4 observes it. It is declared only while the workbench can act at all;
     * a per-path refusal (no document held) is the seam's {@code false}, which core's
     * {@code WriteSurface} turns into its own documented answer.
     */
    @Override
    public Set<String> capabilities() {
        return isAvailable() ? Set.of(CAP_OPEN, CAP_SELECT, CAP_EDIT) : Set.of();
    }

    /**
     * Opens the file in the platform's text editor and puts the caret on the one-based line and
     * column. Returns false when no workspace file answers the path, or no editor could be made.
     */
    @Override
    public boolean openFileAt(String absolutePath, int line, int column) {
        IWorkbenchPage page = activePage();
        if (page == null) {
            return false;
        }
        IFile file = WorkspaceFiles.find(absolutePath);
        if (file == null) {
            return false;
        }
        IEditorInput input = new FileEditorInput(file);
        IEditorPart editor = page.findEditor(input);
        if (editor == null) {
            try {
                editor = page.openEditor(input, null);
            } catch (PartInitException e) {
                return false;
            }
        }
        if (editor instanceof ITextEditor textEditor) {
            IDocument document = documentOf(textEditor);
            if (document != null) {
                try {
                    textEditor.selectAndReveal(offsetOf(document, line, column), 0);
                } catch (BadLocationException e) {
                    // A caret outside this document: the file still opens, without the position.
                }
            }
        }
        page.activate(editor);
        return true;
    }

    /**
     * Selects the span in an already-open editor, clamped to the document, and reveals it. A file
     * that is not open is not opened here — this is the selection rung, not the open rung.
     */
    @Override
    public boolean select(String absolutePath, TextRange range) {
        IWorkbenchPage page = activePage();
        if (page == null) {
            return false;
        }
        IFile file = WorkspaceFiles.find(absolutePath);
        if (file == null) {
            return false;
        }
        IEditorPart editor = page.findEditor(new FileEditorInput(file));
        if (!(editor instanceof ITextEditor textEditor)) {
            return false;
        }
        IDocument document = documentOf(textEditor);
        if (document == null) {
            return false;
        }
        TextRange clamped = range.clamped();
        int start;
        int end;
        try {
            start = offsetOf(document, clamped.startLine(), clamped.startColumn());
            end = offsetOf(document, clamped.endLine(), clamped.endColumn());
        } catch (BadLocationException e) {
            // A span outside this document: there is nothing to select.
            return false;
        }
        if (end < start) {
            int swap = start;
            start = end;
            end = swap;
        }
        textEditor.selectAndReveal(start, end - start);
        page.activate(editor);
        return true;
    }

    /**
     * The buffer half of the write contract (E8), delegated to the seam: one compound change on
     * the UI thread, never a save. This host only forwards — the decision about what a declined
     * edit means belongs to core's {@code WriteSurface}, the same surface every other host answers
     * through.
     */
    @Override
    public boolean applyEdit(String absolutePath, List<TextEdit> edits) {
        return documentEditor.apply(absolutePath, edits);
    }

    /**
     * The editor's document, through the document provider with the editor's own input as the
     * element. The provider is the platform's, so its element is the {@code IEditorInput} the
     * editor was created with; a provider without that element answers null, and the caller
     * degrades gracefully. Package-visible because {@link DocumentBufferEditor} asks the same
     * question the navigation methods do.
     */
    static IDocument documentOf(ITextEditor textEditor) {
        IDocumentProvider provider = textEditor.getDocumentProvider();
        return provider == null ? null : provider.getDocument(textEditor.getEditorInput());
    }

    /**
     * The document offset for a one-based line and column, clamped to the document. The platform
     * has no TextPosition in this train, so the offset is computed from the line offsets directly.
     * A line index outside the document is the platform's {@code BadLocationException}; the
     * callers answer their graceful-degradation result when they see it.
     */
    static int offsetOf(IDocument document, int line, int column) throws BadLocationException {
        int lineIndex = Math.min(Math.max(line - 1, 0), document.getNumberOfLines() - 1);
        long offset = document.getLineOffset(lineIndex) + Math.max(column - 1, 0);
        return (int) Math.min(offset, document.getLength());
    }

    /**
     * The active workbench page, or null when there is none — which includes "there is no
     * workbench at all" (a headless product, a plain JUnit JVM): the platform throws rather than
     * answer, and the honest translation of that throw is "nothing is available". Package-visible
     * because {@link DocumentBufferEditor} refuses on exactly this answer.
     */
    static IWorkbenchPage activePage() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            return window == null ? null : window.getActivePage();
        } catch (IllegalStateException | LinkageError e) {
            return null;
        }
    }
}
