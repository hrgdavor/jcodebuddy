package hr.hrg.webview.webviewd;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line: defaults that are safe, and refusals that say what to fix. */
class WebviewdConfigTest {

    @Test
    void defaultsBindAnEphemeralPortOnTheCurrentDirectoryWithNoEditorAssumed() {
        WebviewdConfig config = WebviewdConfig.parse(new String[] {});

        assertEquals(0, config.port(), "0 means 'pick a free port and publish it'");
        assertFalse(config.open());
        assertFalse(config.printManifestOnly());
        assertEquals(WebviewdConfig.HostChoice.AUTO, config.host());
        assertEquals(Path.of("").toAbsolutePath().normalize(), config.project());
        assertEquals(WebviewdConfig.DEFAULT_SIDECAR_PORT, config.sidecarPort());
        assertTrue(config.configuredOrigins().isEmpty(),
                "no origin is trusted beyond the host's own, and the host adds those itself");
    }

    @Test
    void readsEveryOption() {
        WebviewdConfig config = WebviewdConfig.parse(new String[] {
                "--project", "target", "--port", "18899", "--open", "--host", "zed-cli",
                "--allowed-origins", "https://example.test,http://localhost:3000", "--token", "s3cret",
                "--sidecar-port", "7988", "--sidecar-token", "sidecar-secret"});

        assertEquals(18899, config.port());
        assertTrue(config.open());
        assertEquals(WebviewdConfig.HostChoice.ZED_CLI, config.host());
        assertEquals("s3cret", config.token());
        assertEquals(7988, config.sidecarPort());
        assertEquals("sidecar-secret", config.sidecarToken());
        assertEquals(2, config.configuredOrigins().values().size());
        assertTrue(config.project().isAbsolute(), "a relative --project is resolved once, not per request");
    }

    @Test
    void theLspChoiceIsSpelledTheWayPeopleSpellIt() {
        for (String spelling : new String[] {"lsp", "sidecar", "lsp-sidecar"}) {
            assertEquals(WebviewdConfig.HostChoice.LSP,
                    WebviewdConfig.parse(new String[] {"--host", spelling}).host(), spelling);
        }
    }

    @Test
    void theSidecarTokenFallsBackToTheSystemPropertySoOneDashDConfiguresBothProcesses() {
        String previous = System.getProperty("jwa.sidecar.token");
        try {
            System.setProperty("jwa.sidecar.token", "from-property");
            assertEquals("from-property", WebviewdConfig.parse(new String[] {}).sidecarToken());
            assertEquals("from-flag", WebviewdConfig.parse(new String[] {"--sidecar-token", "from-flag"})
                    .sidecarToken());
        } finally {
            if (previous == null) {
                System.clearProperty("jwa.sidecar.token");
            } else {
                System.setProperty("jwa.sidecar.token", previous);
            }
        }
    }

    @Test
    void theHeadlessChoiceIsSpelledSeveralWaysBecausePeopleTypeSeveralWays() {
        for (String spelling : new String[] {"none", "null", "headless"}) {
            assertEquals(WebviewdConfig.HostChoice.NONE,
                    WebviewdConfig.parse(new String[] {"--host", spelling}).host(), spelling);
        }
    }

    /**
     * "No {@code --port}" and "{@code --port 0}" are different requests: the first defers to the project's
     * committed preference, the second means "any free port". Collapsing them would either ignore a project's
     * configuration or hand a project that asked for a specific port an ephemeral one.
     */
    @Test
    void anAbsentPortAndAnEphemeralPortAreDistinguishable() {
        WebviewdConfig absent = WebviewdConfig.parse(new String[] {});
        assertFalse(absent.portSpecified(), "no --port means 'ask the project first'");
        assertEquals(0, absent.port());

        WebviewdConfig ephemeral = WebviewdConfig.parse(new String[] {"--port", "0"});
        assertTrue(ephemeral.portSpecified(), "--port 0 is a decision, not an absence");
        assertEquals(0, ephemeral.port());

        assertTrue(WebviewdConfig.parse(new String[] {"--port", "18899"}).portSpecified());
    }

    /**
     * The pin is tri-state on the command line, and the third state is the important one: saying nothing must
     * leave a project's pin exactly as it was, not silently clear it.
     */
    @Test
    void thePinIsSetClearedOrLeftAlone() {
        assertEquals(Boolean.TRUE, WebviewdConfig.parse(new String[] {"--sticky"}).sticky());
        assertEquals(Boolean.FALSE, WebviewdConfig.parse(new String[] {"--no-sticky"}).sticky());
        assertNull(WebviewdConfig.parse(new String[] {}).sticky(),
                "no flag means 'leave the project's own pin as it is'");

        // Last one wins, so a script can append an override to a shared argument list.
        assertEquals(Boolean.FALSE,
                WebviewdConfig.parse(new String[] {"--sticky", "--no-sticky"}).sticky());
    }

    @Test
    void refusesWhatItCannotActOn() {
        assertThrows(IllegalArgumentException.class, () -> WebviewdConfig.parse(new String[] {"--port"}));
        assertThrows(IllegalArgumentException.class, () -> WebviewdConfig.parse(new String[] {"--port", "x"}));
        assertThrows(IllegalArgumentException.class, () -> WebviewdConfig.parse(new String[] {"--port", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> WebviewdConfig.parse(new String[] {"--host", "vscode"}));
        assertThrows(IllegalArgumentException.class, () -> WebviewdConfig.parse(new String[] {"--sidecar-port", "0"}));
        assertThrows(IllegalArgumentException.class, () -> WebviewdConfig.parse(new String[] {"--nope"}));
    }

    @Test
    void helpIsNotAnError() {
        assertThrows(WebviewdConfig.HelpRequested.class, () -> WebviewdConfig.parse(new String[] {"--help"}));
        assertThrows(WebviewdConfig.HelpRequested.class, () -> WebviewdConfig.parse(new String[] {"-h"}));
    }
}
