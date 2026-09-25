package hr.hrg.webview.webviewd;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
