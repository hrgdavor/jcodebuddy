package hr.hrg.jetbrains.webview.bridge;

import hr.hrg.webview.core.TextEdit;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * The buffer half of the write contract, as text: what a document becomes after an edit.
 *
 * <p>This is the part of gate (d) that can be checked without an IDE. {@code WriteCommandAction} itself needs a
 * running platform — that is what the maintainer's observation is for — but "which characters end up where" is
 * exactly the part that used to be reimplemented per host, and it is checked here against the same
 * {@link hr.hrg.webview.core.EditableText} the disk path uses.
 */
public class DocumentEditsTest {

    @Test
    public void aWholeLineReplacementKeepsTheLineStructure() {
        String document = "class A {\n    int x = 1;\n}\n";

        String result = DocumentEdits.newText(document, List.of(TextEdit.lines(2, 2, "    int x = 42;")));

        assertEquals("class A {\n    int x = 42;\n}\n", result);
    }

    @Test
    public void anInsertionAtTheTopAppearsAtTheTop() {
        String document = "one\ntwo\n";

        String result = DocumentEdits.newText(document, List.of(TextEdit.insert(1, 1, "zero\n")));

        assertEquals("zero\none\ntwo\n", result);
    }

    @Test
    public void aDocumentsOwnLineEndingsAndTailSurvive() {
        assertEquals("one\r\nTWO\r\n", DocumentEdits.newText("one\r\ntwo\r\n", List.of(TextEdit.lines(2, 2, "TWO"))));
        assertEquals("ONE\ntwo", DocumentEdits.newText("one\ntwo", List.of(TextEdit.lines(1, 1, "ONE"))));
    }

    @Test
    public void aPositionOutsideTheDocumentIsRefusedRatherThanClamped() {
        // Clamping silently would put the reader's change somewhere they did not ask for; the caller refuses the
        // edit instead, which is what the write contract's invalid-edit answer means.
        assertThrows(IllegalArgumentException.class,
                () -> DocumentEdits.newText("one\n", List.of(TextEdit.lines(9, 9, "nine"))));
    }

    @Test
    public void overlappingEditsAreRefusedTogetherRatherThanAppliedInSomeOrder() {
        assertThrows(IllegalArgumentException.class, () -> DocumentEdits.newText("one\ntwo\n",
                List.of(TextEdit.lines(1, 2, "x"), TextEdit.lines(2, 2, "y"))));
    }
}
