package hr.hrg.eclipse.webview.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import hr.hrg.webview.core.EditorHost;

import org.junit.jupiter.api.Test;

/**
 * The editor host's contract, without the platform: its name, its availability, its declared
 * capabilities and its line-navigation precision. Constructing the host touches no platform
 * state, which is what makes these assertions runnable in a plain JUnit run.
 */
class EclipseEditorHostTest {

    private final EclipseEditorHost host = new EclipseEditorHost();

    @Test
    void theHostNamesItselfEclipse() {
        assertEquals("eclipse", host.name());
        assertEquals(EclipseEditorHost.NAME, host.name());
    }

    @Test
    void theHostIsAvailable() {
        assertTrue(host.isAvailable());
    }

    @Test
    void theHostDeclaresOpenAndSelect() {
        assertEquals(Set.of(EditorHost.CAP_OPEN, EditorHost.CAP_SELECT), host.capabilities());
    }

    @Test
    void theHostNavigatesExactly() {
        assertEquals("exact", host.lineNavigation());
    }
}
