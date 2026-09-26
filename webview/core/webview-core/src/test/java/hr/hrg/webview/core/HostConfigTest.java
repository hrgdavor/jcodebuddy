package hr.hrg.webview.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's committed host preferences.
 *
 * <p>Two things are being asserted, and the second is the one a host depends on: where the file is (the
 * reserved, tracked {@code conf/} subtree — never beside the port that a running host publishes), and that no
 * way of writing it can stop a project from being served. A malformed preference must degrade to the built-in
 * default, because refusing to serve a project over a config typo is a worse failure than serving it on the
 * conventional port.
 */
class HostConfigTest {

    private static void write(Path project, String json) throws IOException {
        Files.createDirectories(HostConfig.directoryOf(project));
        Files.writeString(HostConfig.fileOf(project), json, StandardCharsets.UTF_8);
    }

    @Test
    void theFileIsCommittedConfigurationNotHostState(@TempDir Path project) {
        assertTrue(HostConfig.fileOf(project).endsWith(Path.of(".jcodebuddy", "conf", "webview.json")),
                "the preference belongs in the tracked subtree: " + HostConfig.fileOf(project));
        assertFalse(HostConfig.fileOf(project).startsWith(HostDescriptor.directoryOf(project)),
                "and never in the ignored state directory, which holds a running host's port and token");
    }

    @Test
    void noConfigurationIsTheNormalStateAndNeverAProblem(@TempDir Path project) {
        HostConfig.PortPreference preference = HostConfig.portPreference(project);

        assertFalse(preference.present());
        assertEquals("", preference.problem(), "an absent file is not a problem to report");
        assertEquals("", preference.describe(project));
    }

    @Test
    void aPortIsRead(@TempDir Path project) throws IOException {
        write(project, "{ \"port\": 18882 }\n");

        HostConfig.PortPreference preference = HostConfig.portPreference(project);

        assertTrue(preference.present());
        assertEquals(18882, preference.port().getAsInt());
        assertTrue(preference.describe(project).contains("18882"), preference.describe(project));
    }

    @Test
    void aPortBesideOtherKeysIsStillRead(@TempDir Path project) throws IOException {
        // A tool may keep more than a port here; this host reads what it needs and ignores the rest rather
        // than insisting the file is only its own.
        write(project, "{ \"port\": 18883, \"allowedOrigins\": \"http://localhost:3000\" }\n");

        assertEquals(18883, HostConfig.portPreference(project).port().getAsInt());
    }

    @Test
    void anEmptyDocumentNamesNoPortAndIsNotAProblem(@TempDir Path project) throws IOException {
        write(project, "{}\n");

        HostConfig.PortPreference preference = HostConfig.portPreference(project);

        assertFalse(preference.present());
        assertEquals("", preference.problem(), "'no preference' is a legitimate way to leave the file in git");
    }

    @Test
    void anUnusablePreferenceIsReportedAndIgnored(@TempDir Path project) throws IOException {
        for (String json : new String[] {
                "{ \"port\": 0 }",
                "{ \"port\": 70000 }",
                "{ \"port\": -1 }",
                "{ \"port\": \"18882\" }",
                "{ \"port\": 18882.5 }",
                "{ \"port\": null }",
                "not json at all",
                "[1, 2, 3]",
                "{ \"port\": { \"value\": 18882 } }" }) {
            write(project, json);
            HostConfig.PortPreference preference = HostConfig.portPreference(project);

            assertFalse(preference.present(), json + " must not yield a port");
            if (!json.contains("null")) {
                assertFalse(preference.problem().isEmpty(),
                        json + " is present but unusable, so it must be reported rather than ignored silently");
                assertTrue(preference.problem().contains("webview.json"), preference.problem());
            }
        }
    }

    @Test
    void aTornFileIsTreatedAsNoPreferenceRatherThanACrash(@TempDir Path project) throws IOException {
        write(project, "{ \"port\": 1888");

        HostConfig.PortPreference preference = HostConfig.portPreference(project);

        assertFalse(preference.present());
        assertFalse(preference.problem().isEmpty(), "a half-written file is reported, and the host starts anyway");
    }

    // --- the four sources, in order, for the port a run should ask for ---------------------------------

    private static void currentPort(Path project, int port) throws IOException {
        Files.createDirectories(HostDescriptor.directoryOf(project));
        Files.writeString(HostDescriptor.fileOf(project), """
                { "plugin": "p", "ide": "webviewd", "pid": 1, "port": %d, "sticky": false, "project": "%s",
                  "tokenPath": "t", "capabilities": [], "startedAt": "then", "host": null }
                """.formatted(port, project.toString().replace('\\', '/')), StandardCharsets.UTF_8);
    }

    @Test
    void withNothingConfiguredTheHostsOwnFallbackIsUsed(@TempDir Path project) {
        HostConfig.RequestedPort requested = HostConfig.requestedPort(project, null, 0);

        assertEquals(0, requested.port(), "the standalone host's fallback is an ephemeral port");
        assertEquals("fallback", requested.source());
        assertFalse(requested.configured(), "and it is not a configuration: 0 means 'pick one'");
        assertEquals("", requested.describe(project));
    }

    @Test
    void theProjectsDefaultIsTheBootstrapValue(@TempDir Path project) throws IOException {
        write(project, "{ \"port\": 18882 }\n");

        HostConfig.RequestedPort requested = HostConfig.requestedPort(project, null, 0);

        assertEquals(18882, requested.port());
        assertEquals("default", requested.source());
        assertTrue(requested.describe(project).contains("default"), requested.describe(project));
        assertTrue(requested.describe(project).contains("webview.json"), requested.describe(project));
    }

    /**
     * The case the two files exist for: the checkout had to take the next free port, and it must keep it
     * rather than drift back to the default and collide there again on every start.
     */
    @Test
    void theCheckoutsCurrentPortBeatsTheCommittedDefault(@TempDir Path project) throws IOException {
        write(project, "{ \"port\": 18882 }\n");
        currentPort(project, 19001);

        HostConfig.RequestedPort requested = HostConfig.requestedPort(project, null, 0);

        assertEquals(19001, requested.port(), "the local, current answer is more specific than the shared default");
        assertEquals("current", requested.source());
        assertTrue(requested.describe(project).contains("currently on"), requested.describe(project));
    }

    @Test
    void anExplicitChoiceBeatsBothFiles(@TempDir Path project) throws IOException {
        write(project, "{ \"port\": 18882 }\n");
        currentPort(project, 19001);

        HostConfig.RequestedPort requested = HostConfig.requestedPort(project, 18899, 0);

        assertEquals(18899, requested.port());
        assertEquals("flag", requested.source(), "a flag is a decision, not a preference to be outranked");
    }

    @Test
    void aCurrentPortIsUsedEvenWithoutACommittedDefault(@TempDir Path project) throws IOException {
        // The normal state of a project that never wrote `conf/`: optional means optional.
        currentPort(project, 19002);

        assertEquals(19002, HostConfig.requestedPort(project, null, 0).port());
    }

    @Test
    void anUnusableDefaultIsReportedAndTheNextSourceWins(@TempDir Path project) throws IOException {
        write(project, "{ \"port\": \"18882\" }\n");
        currentPort(project, 19003);

        HostConfig.RequestedPort withCurrent = HostConfig.requestedPort(project, null, 0);
        assertEquals(19003, withCurrent.port());
        assertFalse(withCurrent.problem().isEmpty(), "the typo is still reported");

        Files.delete(HostDescriptor.fileOf(project));
        HostConfig.RequestedPort withoutCurrent = HostConfig.requestedPort(project, null, 0);
        assertEquals(0, withoutCurrent.port(), "and with no current port the host falls back rather than guessing");
        assertFalse(withoutCurrent.problem().isEmpty());
    }

    @Test
    void aHostThatStaysOffWhenNothingIsConfiguredGetsItsOwnFallback(@TempDir Path project) {
        // The IDE hosts' contract: no port anywhere means no bridge, which is what -1 says.
        HostConfig.RequestedPort requested = HostConfig.requestedPort(project, null, -1);

        assertEquals(-1, requested.port());
        assertFalse(requested.configured());
        assertEquals("fallback", requested.source());
    }
}
