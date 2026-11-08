package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Turns the in-memory compiler diagnostics into IntelliJ editor errors.
 *
 * <p>The annotator is the second half of the plugin's error integration. The build service does not write a
 * report file: it stores {@link Diagnostic}s in {@link IncrementalDiagnosticsService}, restarts highlighting
 * after a compile, and this annotator reads the current list. An empty list means the last compile succeeded,
 * so IntelliJ drops the previous annotations on the next highlighting pass.</p>
 */
public final class JcodeBuddyDiagnosticsAnnotator
        extends ExternalAnnotator<List<Diagnostic>, List<Diagnostic>> {

    @Override
    public @Nullable List<Diagnostic> collectInformation(@NotNull PsiFile file) {
        if (file.getVirtualFile() == null) {
            return List.of();
        }
        return IncrementalDiagnosticsService.getInstance(file.getProject())
                .forFile(file.getVirtualFile().getPath());
    }

    @Override
    public @Nullable List<Diagnostic> doAnnotate(List<Diagnostic> information) {
        return information;
    }

    @Override
    public void apply(@NotNull PsiFile file, List<Diagnostic> diagnostics,
                      @NotNull AnnotationHolder holder) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null || document.getLineCount() == 0) {
            return;
        }
        for (Diagnostic diagnostic : diagnostics) {
            int line = Math.max(0, Math.min(diagnostic.line() - 1, document.getLineCount() - 1));
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            int start = lineStart;
            if (diagnostic.column() > 0) {
                int columnOffset = Math.min(diagnostic.column() - 1, Math.max(0, lineEnd - lineStart));
                start = lineStart + columnOffset;
            }
            holder.newAnnotation(HighlightSeverity.ERROR, diagnostic.message())
                    .range(new TextRange(start, lineEnd))
                    .create();
        }
    }
}
