package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.NullHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preference order of {@code --host}, which is the spike's product decision: LSP wins because it is the
 * only route that can place a caret on Zed, the CLI is the fallback because it can at least open the file, and
 * "no editor" is a legitimate outcome rather than an error.
 */
class HostSelectionTest {

    @TempDir
    Path project;

    private WebviewdConfig config(WebviewdConfig.HostChoice choice) {
        return new WebviewdConfig(project, 0, false, "", "t", choice, 7979, "sidecar-secret", false);
    }

    @Test
    void autoTakesTheLspRouteWhenAnEditorIsAttachedToTheSidecar() {
        EditorHost host = WebviewServer.selectHost(config(WebviewdConfig.HostChoice.AUTO),
                FakeSidecar.withEditorAttached());

        assertInstanceOf(LspHost.class, host);
        assertEquals("exact", host.lineNavigation());
    }

    @Test
    void autoFallsBackWhenTheSidecarHasNoEditorAttached() {
        FakeSidecar unattached = new FakeSidecar();
        unattached.healthBody = FakeSidecar.health("");

        EditorHost host = WebviewServer.selectHost(config(WebviewdConfig.HostChoice.AUTO), unattached);

        assertFalse(host instanceof LspHost, "an unattached sidecar must not win the preference order");
        if (ZedCliHost.detect().isAvailable()) {
            assertEquals("zed-cli", host.name(), "the CLI is the next best thing: it opens the file");
            assertEquals("file-only", host.lineNavigation(), "and it says it cannot place a caret");
        } else {
            assertInstanceOf(NullHost.class, host);
            assertEquals("none", host.lineNavigation());
        }
    }

    @Test
    void anExplicitLspChoiceRefusesInsteadOfSilentlyServingNothing() {
        FakeSidecar unattached = new FakeSidecar();
        unattached.healthBody = null;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> WebviewServer.selectHost(config(WebviewdConfig.HostChoice.LSP), unattached));

        assertTrue(failure.getMessage().contains("advertises no navigation capability"), failure.getMessage());
        assertTrue(failure.getMessage().contains("/health"), "the message must say what to check");
    }

    @Test
    void headlessIsAChoiceNotAFailure() {
        EditorHost host = WebviewServer.selectHost(config(WebviewdConfig.HostChoice.NONE),
                FakeSidecar.withEditorAttached());

        assertInstanceOf(NullHost.class, host);
        assertFalse(host.isAvailable());
        assertEquals("none", host.lineNavigation());
    }
}
