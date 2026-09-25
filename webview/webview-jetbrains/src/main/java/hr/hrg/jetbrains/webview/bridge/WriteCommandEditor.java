package hr.hrg.jetbrains.webview.bridge;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.diagnostic.Logger;
import hr.hrg.webview.core.TextEdit;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The IDE glue for a buffer edit: find the document, compute its new text, and set it inside the platform's own
 * undoable command.
 *
 * <p>This is what makes gate (d) true — the change appears in the IDE's undo stack and the user's own
 * {@code Ctrl+Z} takes it back — and it is deliberately the thinnest possible layer: the decision about what the
 * text becomes lives in {@link DocumentEdits}, which is tested, leaving here only the two platform calls that
 * cannot be tested without an IDE (which document to ask for, and which command to run in).
 *
 * <p>Two platform details that are easy to get wrong and are therefore explicit:
 *
 * <ul>
 *   <li><b>Writing must happen on the EDT</b>, because the HTTP bridge calls this from its own thread.
 *       {@code invokeAndWait} marshals it, and the dispatch-thread check avoids deadlocking a caller that is
 *       already there.</li>
 *   <li><b>The change is not saved.</b> Setting the document's text marks the file modified, which is the point:
 *       the reader sees the edit, reviews it, and decides when to save — exactly the asymmetry the write contract
 *       describes for a host that declares {@code edit}.</li>
 * </ul>
 */
public final class WriteCommandEditor implements IdeDocumentEditor {

    private static final Logger LOG = Logger.getInstance(WriteCommandEditor.class);

    private final Project project;

    public WriteCommandEditor(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public boolean apply(@NotNull String absolutePath, @NotNull List<TextEdit> edits) {
        if (edits.isEmpty() || project.isDisposed()) {
            return false;
        }
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(absolutePath);
        if (file == null || !file.isValid()) {
            LOG.warn("WebView edit: no file at '" + absolutePath + "'");
            return false;
        }
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            // A binary file has no document, so there is nothing a text edit could mean.
            LOG.warn("WebView edit: no document for '" + absolutePath + "'");
            return false;
        }
        String newText;
        try {
            newText = DocumentEdits.newText(document.getText(), edits);
        } catch (IllegalArgumentException e) {
            LOG.warn("WebView edit refused for '" + absolutePath + "': " + e.getMessage());
            return false;
        }
        if (newText.equals(document.getText())) {
            // Nothing to do, and doing it anyway would add an empty step to the reader's undo stack.
            return true;
        }

        AtomicBoolean applied = new AtomicBoolean(false);
        Runnable command = () -> WriteCommandAction.runWriteCommandAction(project, "webview edit", null, () -> {
            document.setText(newText);
            applied.set(true);
        });
        if (ApplicationManager.getApplication().isDispatchThread()) {
            command.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(command);
        }
        return applied.get();
    }
}
