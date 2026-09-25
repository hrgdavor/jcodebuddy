package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.EditorHost;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LSP adapter: what it forwards, and — the part that matters more — when it refuses to advertise a verb.
 *
 * <p>Phase 0's rule for this codebase is that a capability must not be advertised unless it can act, so most
 * of these cases are about the sidecar's health document: no answer, an answer with no capabilities, and an
 * answer that changes while the host is running.
 */
class LspHostTest {

    @Test
    void anUnreachableSidecarIsUnavailableAndAdvertisesNothing() {
        FakeSidecar sidecar = new FakeSidecar();
        sidecar.healthBody = null;

        LspHost host = LspHost.discover(sidecar);

        assertFalse(host.isAvailable());
        assertTrue(host.capabilities().isEmpty());
        assertEquals("none", host.lineNavigation());
        assertFalse(host.openFileAt("D:/wrk/A.java", 1, 1));
        assertTrue(sidecar.jumps.isEmpty(), "a host that cannot act must not send the request anyway");
    }

    @Test
    void aSidecarWithNoEditorAttachedIsHonestAndRefuses() {
        FakeSidecar sidecar = new FakeSidecar();
        sidecar.healthBody = FakeSidecar.health("");

        LspHost host = LspHost.discover(sidecar);

        assertFalse(host.isAvailable(), "the process is up but no LSP client has attached");
        assertEquals("none", host.lineNavigation());
        assertTrue(host.lineNavigationNote().contains("no LSP client has attached"), host.lineNavigationNote());
        assertFalse(host.openFileAt("D:/wrk/A.java", 12, 3));
        assertTrue(sidecar.jumps.isEmpty());
    }

    @Test
    void anAttachedEditorMakesNavigationExactAndForwardsThePosition() {
        FakeSidecar sidecar = FakeSidecar.withEditorAttached();

        LspHost host = LspHost.discover(sidecar);

        assertTrue(host.isAvailable());
        assertEquals(Set.of(EditorHost.CAP_OPEN, EditorHost.CAP_SELECT, EditorHost.CAP_EDIT),
                host.capabilities());
        assertEquals("exact", host.lineNavigation(),
                "showDocument carries a selection; Phase 0 saw the caret land on it");
        assertTrue(host.openFileAt("D:/wrk/project/src/A.java", 12, 3));
        assertEquals(List.of("D:/wrk/project/src/A.java:12:3"), sidecar.jumps);
    }

    @Test
    void aSidecarThatRefusesTheJumpReportsNoOpenRatherThanSuccess() {
        FakeSidecar sidecar = FakeSidecar.withEditorAttached();
        sidecar.acceptJump = false;

        assertFalse(LspHost.discover(sidecar).openFileAt("D:/wrk/A.java", 1, 1));
    }

    @Test
    void theHealthAnswerExpiresSoAnEditorAttachingLaterIsNoticed() {
        FakeSidecar sidecar = new FakeSidecar();
        sidecar.healthBody = FakeSidecar.health("");
        long[] now = {1_000L};
        LspHost host = new LspHost(sidecar, () -> now[0]);

        assertFalse(host.isAvailable());
        assertEquals(1, sidecar.healthCalls);

        // Zed attaches while webviewd is running. Inside the TTL the cached answer stands — otherwise every
        // page request would probe the sidecar — and after it the answer is re-read.
        sidecar.healthBody = FakeSidecar.health("\"open\"");
        assertFalse(host.isAvailable());
        assertEquals(1, sidecar.healthCalls);

        now[0] += LspHost.PROBE_TTL_MILLIS;
        assertTrue(host.isAvailable(), "an editor attached after the probe must start being honoured");
        assertEquals(2, sidecar.healthCalls);
    }

    @Test
    void capabilityParsingIgnoresKeysThisAdapterCannotRoute() {
        assertEquals(Set.of(EditorHost.CAP_OPEN),
                LspHost.parseCapabilities(FakeSidecar.health("\"open\",\"watch\"")));
        assertEquals(Set.of(), LspHost.parseCapabilities(FakeSidecar.health("")));
        assertEquals(Set.of(), LspHost.parseCapabilities(null));
        assertEquals(Set.of(), LspHost.parseCapabilities("not json at all"));
    }
}
