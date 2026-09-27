package hr.hrg.eclipse.webview.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The one branch of the platform implementation a plain JUnit run can reach (E10): the empty-edit
 * guard runs before any workbench type is touched, so it is a unit test rather than an
 * observation. Everything past that guard needs a page, an editor and a document — the observed
 * half of the Phase 3 gate (the replacement appears unsaved, one {@code Ctrl+Z} restores the
 * original).
 */
class DocumentBufferEditorTest {

    @Test
    void anEmptyEditListIsRefusedBeforeAnyPlatformCall() {
        assertFalse(new DocumentBufferEditor().apply("/project/src/A.java", List.of()),
                "an empty edit list is nothing to apply, and must not add an undo step");
    }

    @Test
    void aNullEditListIsRefusedToo() {
        assertFalse(new DocumentBufferEditor().apply("/project/src/A.java", null));
    }
}
