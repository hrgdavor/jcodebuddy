package hr.hrg.jetbrains.webview.bridge;

import hr.hrg.webview.core.EditableText;
import hr.hrg.webview.core.TextEdit;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The buffer half of the write contract: what a document's text becomes once core's edits are applied to it.
 *
 * <p>It exists as a pure function — no IDE types, no document, no command — for two reasons. The first is
 * testability: the plugin's test suite has no IDE fixture, so the part that decides the resulting text is
 * exactly the part that can be checked without one. The second is agreement: it routes through the same
 * {@link EditableText} the disk path uses, so "line 2 column 1" means the same thing whether the change lands in
 * a buffer or in a file. A second implementation of that mapping is how the two halves of one contract start to
 * disagree about where an edit goes.
 *
 * <p>{@code documentText} is the document's text as the platform reports it; the result carries the document's
 * own line endings and trailing-newline shape.
 *
 * @throws IllegalArgumentException when a position is outside the text or two edits overlap — the caller refuses
 *                                  the edit rather than applying part of it
 */
public final class DocumentEdits {

    private DocumentEdits() {
    }

    public static String newText(String documentText, List<TextEdit> edits) {
        EditableText text = EditableText.of(documentText.getBytes(StandardCharsets.UTF_8));
        return text.renderedAfter(text.apply(edits));
    }
}
