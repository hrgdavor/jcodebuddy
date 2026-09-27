package hr.hrg.eclipse.webview.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.TextEdit;

/**
 * The editor host's contract, without the platform: its name, the honest answer a workbench-less
 * JVM gets, and the delegation of the buffer-edit half to the {@link EclipseDocumentEditor} seam.
 * Constructing the host touches no platform state, which is what makes these assertions runnable
 * in a plain JUnit run (E10); the workbench half of every claim here is the observed gate.
 */
class EclipseEditorHostTest {

    private final EclipseEditorHost host = new EclipseEditorHost();

    @Test
    void theHostNamesItselfEclipse() {
        assertEquals("eclipse", host.name());
        assertEquals(EclipseEditorHost.NAME, host.name());
    }

    @Test
    void aHostWithoutAWorkbenchDeclaresNothing() {
        // No workbench is running in a plain JUnit JVM, so the honest answer is the one plan § 6
        // asks for: not available, an empty capability set, and no line-navigation promise — the
        // same answer webviewd's NullHost gives. Inside a workbench the set is open+select+edit
        // and navigation is exact; that half is the observed gate, not a unit test.
        assertFalse(host.isAvailable());
        assertEquals(Set.of(), host.capabilities());
        assertEquals("none", host.lineNavigation());
    }

    @Test
    void applyEditDelegatesToTheSeam() {
        RecordingEditor editor = new RecordingEditor(true);
        EclipseEditorHost host = new EclipseEditorHost(editor);
        List<TextEdit> edits = List.of(TextEdit.lines(1, 1, "x"));

        assertTrue(host.applyEdit("/whatever/src/A.java", edits));
        assertEquals("/whatever/src/A.java", editor.path);
        assertEquals(edits, editor.edits);
    }

    @Test
    void theSeamsRefusalIsPassedThroughUntouched() {
        // The seam's false is the host's false — plan § 6's proof that "the editor seam returns
        // false for an unknown path". What core's WriteSurface makes of that false (the documented
        // disk fallback, or 409 for an explicit buffer request the capability cannot serve) is
        // asserted over a live socket in EclipseWriteApiTest, not re-decided here.
        EclipseEditorHost host = new EclipseEditorHost(new RecordingEditor(false));
        assertFalse(host.applyEdit("/whatever/src/A.java", List.of(TextEdit.lines(1, 1, "x"))));
    }

    /** The seam's fake: records the call, answers what the test needs. */
    private static final class RecordingEditor implements EclipseDocumentEditor {
        private final boolean answer;
        String path;
        List<TextEdit> edits;

        RecordingEditor(boolean answer) {
            this.answer = answer;
        }

        @Override
        public boolean apply(String absolutePath, List<TextEdit> edits) {
            this.path = absolutePath;
            this.edits = edits;
            return answer;
        }
    }
}
