package hr.hrg.webview.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The port table, as an executable assertion.
 *
 * <p>Four cases decide what every host does when its port is busy, and the third and fourth are the ones that
 * go wrong when the rule lives in each host separately: a host for <b>another</b> project, and anything that
 * is not a host at all, must both mean "take the next port" — only a host for <b>this</b> project may make a
 * host decline to serve.
 *
 * <p>The decision function is pure, so most of this needs no socket; the two tests that do need one are the
 * probe reading a real {@code /health}, because a probe that cannot read the document the hosts actually
 * write would satisfy every pure test and still never recognise a neighbour.
 */
class HostPortClaimTest {

    @TempDir
    Path project;

    @TempDir
    Path otherProject;

    /** A host answering {@code /health} on a free port, for the probe tests. */
    private static HttpServer healthServer(String body) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String healthOf(Path servedProject, String ide, String plugin) {
        return HostHealth.of(plugin, ide, servedProject.toString(), 1234, 0, false, Set.of("open")).toJson();
    }

    /** An attempt that reports the first port free and every other one taken by whoever is named. */
    private static HostPortClaim.Attempt afterFirst(int firstFreePort, HostPortClaim.Occupant occupant) {
        return port -> port < firstFreePort
                ? new HostPortClaim.Try.Taken(occupant)
                : new HostPortClaim.Try.Free();
    }

    @Test
    void aFreePortIsUsedAsAsked() {
        List<Integer> asked = new ArrayList<>();
        HostPortClaim.Decision decision = HostPortClaim.decide(project, 18881, 20, port -> {
            asked.add(port);
            return new HostPortClaim.Try.Free();
        });

        assertTrue(decision.starts());
        assertEquals(18881, decision.port());
        assertEquals(1, decision.tried());
        assertEquals(List.of(18881), asked, "a free port is not probed for a neighbour before it is used");
        assertFalse(decision.moved());
    }

    @Test
    void anotherHostForThisProjectMeansThisOneOpensNothing() {
        HostPortClaim.Occupant mine = HostPortClaim.parseHealth(18881,
                healthOf(project, "IntelliJ Platform", HostHealth.PLUGIN_JETBRAINS));

        HostPortClaim.Decision decision = HostPortClaim.decide(project, 18881, 20,
                afterFirst(Integer.MAX_VALUE, mine));

        assertTrue(decision.skipped(), "a second bridge for one project is the thing this prevents");
        assertEquals(18881, decision.port(), "the port it declined to take is named, so a log can say which");
        assertNotNull(decision.occupant());
        assertTrue(decision.reason().contains("already serves this project"), decision.reason());
        assertTrue(decision.reason().contains("will not open a second endpoint"), decision.reason());
    }

    @Test
    void anotherHostForADifferentProjectMovesThisOneToTheNextPort() {
        HostPortClaim.Occupant theirs = HostPortClaim.parseHealth(18881,
                healthOf(otherProject, "IntelliJ Platform", HostHealth.PLUGIN_JETBRAINS));

        HostPortClaim.Decision decision = HostPortClaim.decide(project, 18881, 20,
                afterFirst(18882, theirs));

        assertTrue(decision.starts());
        assertEquals(18882, decision.port());
        assertEquals(2, decision.tried());
        assertTrue(decision.moved());
        assertTrue(decision.reason().contains("port 18882 was free"), decision.reason());
    }

    @Test
    void anUnrelatedApplicationOnThePortIsSteppedOver() {
        HostPortClaim.Occupant stranger = HostPortClaim.parseHealth(18881, "{\"hello\":\"world\"}");

        HostPortClaim.Decision decision = HostPortClaim.decide(project, 18881, 20,
                afterFirst(18882, stranger));

        assertTrue(decision.starts());
        assertEquals(18882, decision.port(),
                "something that is not a webview host must never be mistaken for one serving this project");
    }

    @Test
    void anExhaustedRangeMeansTheHostServesNothing() {
        HostPortClaim.Occupant stranger = HostPortClaim.parseHealth(18881, "{}");

        HostPortClaim.Decision decision = HostPortClaim.decide(project, 18881, 3,
                afterFirst(Integer.MAX_VALUE, stranger));

        assertTrue(decision.skipped(), "binding a random port instead would be worse than serving nothing");
        assertEquals(3, decision.tried());
        assertEquals(List.of(18881, 18882, 18883), decision.portsTried());
        assertTrue(decision.reason().contains("none of the 3 ports"), decision.reason());
    }

    @Test
    void anEphemeralPortIsNeverProbed() {
        HostPortClaim.Decision decision = HostPortClaim.decide(project, 0, 20, port -> {
            throw new AssertionError("port 0 cannot conflict: no probe may happen");
        });

        assertTrue(decision.starts());
        assertEquals(0, decision.port(), "the caller binds 0 and reads the port the operating system chose");
    }

    // --- a pinned (sticky) port ---------------------------------------------------------------------

    @Test
    void aPinnedPortIsTakenWhenItIsFree() {
        HostPortClaim.Decision decision = HostPortClaim.claimSticky(project, 19000);

        assertTrue(decision.starts());
        assertTrue(decision.sticky(), "the decision records that this port was pinned, not merely preferred");
        assertEquals(19000, decision.port());
        assertEquals(1, decision.tried(), "a pinned port is tried alone: no other port is a candidate");
    }

    @Test
    void aPinnedPortHeldByAnotherApplicationIsAnErrorNotAMove() {
        // 19000 is held by somebody who does not answer /health as a webview host. Every other port in the
        // range is free, and the point of the test is that the host does not reach for one of them.
        HostPortClaim.Attempt attempt = port -> port == 19000
                ? new HostPortClaim.Try.Taken(HostPortClaim.Occupant.silent("nothing answered"))
                : new HostPortClaim.Try.Free();

        HostPortClaim.Decision decision = HostPortClaim.claim(project, 18881, 19000,
                HostPortClaim.DEFAULT_ATTEMPTS, attempt, port -> HostPortClaim.Occupant.silent("nothing"));

        assertTrue(decision.failed(), "a pinned port is a choice: moving would be a silent lie about it");
        assertEquals(19000, decision.port());
        assertTrue(decision.sticky());
        assertEquals(1, decision.tried(), "no other port may be tried");
        assertTrue(decision.reason().contains("pinned"), decision.reason());
        assertTrue(decision.reason().contains("sticky"), "the message says how to unpin it: " + decision.reason());
        assertTrue(decision.reason().contains("host.json"), decision.reason());
    }

    @Test
    void aPinnedPortAlreadyServedByThisProjectIsASkipNotAnError() {
        // The state the pin asks for: this project is served on its pinned port. Nothing to do, nothing wrong.
        HostPortClaim.Attempt attempt = port -> {
            throw new AssertionError("a port that already serves this project must not be bound again");
        };

        HostPortClaim.Decision decision = HostPortClaim.claim(project, 18881, 19000,
                HostPortClaim.DEFAULT_ATTEMPTS, attempt, port -> HostPortClaim.parseHealth(port,
                        healthOf(project, "IntelliJ Platform", HostHealth.PLUGIN_JETBRAINS)));

        assertTrue(decision.skipped(), decision.reason());
        assertTrue(decision.sticky());
        assertEquals(19000, decision.port());
    }

    @Test
    void aPinnedPortServedByAnotherProjectIsStillAnError() {
        // The pinned port is held by a webview host — but for a *different* project. That is not "already
        // served", it is a collision, and the user pinned this port on purpose.
        HostPortClaim.Attempt attempt = port -> new HostPortClaim.Try.Taken(HostPortClaim.Occupant.silent("x"));

        HostPortClaim.Decision decision = HostPortClaim.claim(project, 18881, 19000,
                HostPortClaim.DEFAULT_ATTEMPTS, attempt, port -> HostPortClaim.parseHealth(port,
                        healthOf(otherProject, "Visual Studio Code", HostHealth.PLUGIN_VSCODE)));

        assertTrue(decision.failed(), decision.reason());
        assertTrue(decision.reason().contains("Visual Studio Code"), decision.reason());
    }

    @Test
    void aLiveHostForThisProjectElsewhereStillWinsOverAPin() throws IOException {
        // One bridge per project outranks the pin: if a live host already serves this project somewhere, the
        // pinned port is not an invitation to start a second one.
        long foreignPid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElseThrow(
                () -> new AssertionError("this test needs a live process that is not this one"));
        new HostDescriptor(HostHealth.PLUGIN_JETBRAINS, "IntelliJ Platform", foreignPid, 19001, false,
                project.toString(), "t", List.of("open"), "then", null).write(project);

        HostPortClaim.Decision decision = HostPortClaim.claim(project, 18881, 19000,
                HostPortClaim.DEFAULT_ATTEMPTS,
                port -> {
                    throw new AssertionError("nothing may be bound while the project is already served");
                },
                port -> HostPortClaim.parseHealth(port,
                        healthOf(project, "IntelliJ Platform", HostHealth.PLUGIN_JETBRAINS)));

        assertTrue(decision.skipped(), decision.reason());
        assertEquals(19001, decision.port(), "and it names the port that is actually serving");
    }

    @Test
    void aHealthDocumentIsRecognisedAndAnythingElseIsNot() {
        HostPortClaim.Occupant mine = HostPortClaim.parseHealth(18881,
                healthOf(project, "Visual Studio Code", HostHealth.PLUGIN_VSCODE));
        assertTrue(mine.answered());
        assertEquals("Visual Studio Code", mine.ide());
        assertEquals(HostHealth.normalizeProject(project.toString()), mine.project());
        assertTrue(mine.servesSameProject(project));

        assertFalse(HostPortClaim.parseHealth(18881, "not json").answered());
        assertFalse(HostPortClaim.parseHealth(18881, "").answered());
        assertFalse(HostPortClaim.parseHealth(18881, "[]").answered());
        assertFalse(HostPortClaim.parseHealth(18881, "{\"hello\":\"world\"}").answered(),
                "JSON without plugin and port is an unrelated application");
        assertFalse(HostPortClaim.parseHealth(18881, "{\"plugin\":\"x\"}").answered(),
                "both keys are required: a page reads the port from the same document");
    }

    @Test
    void theSameProjectIsAPathComparisonNotAStringComparison() {
        assertTrue(HostPortClaim.sameProject(project, project + "/"));
        assertTrue(HostPortClaim.sameProject(project, project.resolve("sub").resolve("..").toString()));
        assertTrue(HostPortClaim.sameProject(project, project.toString().replace('/', '\\')));
        assertFalse(HostPortClaim.sameProject(project, otherProject.toString()));
        assertFalse(HostPortClaim.sameProject(project, ""));
        assertFalse(HostPortClaim.sameProject(project, null));
        assertFalse(HostPortClaim.sameProject(project, "not a path\0at all"),
                "an unparseable path is not this project");
    }

    @Test
    void theProbeReadsTheDocumentARealHostWrites() throws IOException {
        HttpServer server = healthServer(healthOf(project, "IntelliJ Platform", HostHealth.PLUGIN_JETBRAINS));
        int port = server.getAddress().getPort();
        try {
            HostPortClaim.Occupant occupant = HostPortClaim.occupantOf(port);
            assertTrue(occupant.answered());
            assertEquals("IntelliJ Platform", occupant.ide());
            assertTrue(occupant.servesSameProject(project));
            assertFalse(HostPortClaim.isBindable(port), "and the port is genuinely held");

            HostPortClaim.Decision decision = HostPortClaim.decide(project, port, 20,
                    HostPortClaim.systemAttempt());
            assertTrue(decision.skipped(), "the real attempt and the real probe reach the same answer");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPortNobodyAnswersOnIsNotAnOccupant() {
        HostPortClaim.Occupant nothing = HostPortClaim.occupantOf(1);
        assertFalse(nothing.answered());
        assertNotNull(nothing.detail(), "the reason is carried, so a log can say what was tried");
    }

    @Test
    void aLiveDescriptorForThisProjectStopsASecondHostEvenOnAnotherPort() throws IOException {
        long foreignPid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElseThrow(
                () -> new AssertionError("this test needs a live process that is not this one"));
        // The record belongs to the other process, which is the case the owner check exists for: a host must be
        // able to restart on its own published port.
        HostDescriptor foreign = new HostDescriptor(HostHealth.PLUGIN_JETBRAINS, "IntelliJ Platform", foreignPid,
                19000, false, project.toString(), "t", List.of("open"), "then", null);
        foreign.write(project);

        HostPortClaim.Decision decision = HostPortClaim.claim(project, 18881, null, 20,
                port -> new HostPortClaim.Try.Free(),
                port -> HostPortClaim.parseHealth(port,
                        healthOf(project, "IntelliJ Platform", HostHealth.PLUGIN_JETBRAINS)));

        assertTrue(decision.skipped(),
                "a live host that moved to a port nobody asked about must still stop a second bridge");
        assertEquals(19000, decision.port());
    }

    @Test
    void aDeadOrOwnDescriptorIsNotAnOccupant() throws IOException {
        // Dead: the process was killed without its shutdown hook, which leaves the file behind.
        new HostDescriptor(HostHealth.PLUGIN_JETBRAINS, "IntelliJ Platform", 999_999_999L, 19000, false,
                project.toString(), "t", List.of("open"), "then", null).write(project);
        assertNull(HostPortClaim.publishedElsewhere(project, 18881, HostPortClaim::occupantOf),
                "a dead descriptor must not stop a start, or a killed editor would lock the project out");

        // Ours: this process recorded a socket it has since given up, which is every restart.
        HostDescriptor mine = HostDescriptor.of(project, HostHealth.PLUGIN_JETBRAINS, "IntelliJ Platform", 19000,
                "t", List.of("open"), null);
        mine.write(project);
        assertTrue(mine.isOurs());
        assertNull(HostPortClaim.publishedElsewhere(project, 18881, HostPortClaim::occupantOf),
                "a host must be able to restart on its own published port");

        // Somebody else's and live, but serving a different project: not this project's host, so the port loop
        // decides. The occupant decides that, not the file, which is why the probe is the authority.
        long foreignPid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElseThrow(
                () -> new AssertionError("this test needs a live process that is not this one"));
        new HostDescriptor(HostHealth.PLUGIN_JETBRAINS, "IntelliJ Platform", foreignPid, 19000, false,
                otherProject.toString(), "t", List.of("open"), "then", null).write(project);
        assertNull(HostPortClaim.publishedElsewhere(project, 18881,
                        port -> HostPortClaim.parseHealth(port,
                                healthOf(otherProject, "IntelliJ Platform", HostHealth.PLUGIN_JETBRAINS))),
                "a live host for another project is not this project's host");
    }
}
