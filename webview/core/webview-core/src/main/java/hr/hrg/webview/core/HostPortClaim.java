package hr.hrg.webview.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Who owns a port, decided the same way in every host.
 *
 * <p>A bridge is per project, but a port is per machine, and the two do not agree. The cases that actually
 * happen, and the answer this class gives each:
 *
 * <table border="1">
 *   <caption>the four cases</caption>
 *   <tr><th>the port is…</th><th>and the process there is…</th><th>answer</th></tr>
 *   <tr><td>free</td><td>—</td><td>{@link Action#START} on it</td></tr>
 *   <tr><td>taken</td><td>another JCodeBuddy host serving <b>this</b> project</td>
 *       <td>{@link Action#SKIP}: that host already serves these edits</td></tr>
 *   <tr><td>taken</td><td>a JCodeBuddy host serving <b>another</b> project</td>
 *       <td>START on the next free port</td></tr>
 *   <tr><td>taken</td><td>anything else</td><td>START on the next free port</td></tr>
 * </table>
 *
 * <p>The first case is the reason this lives above the hosts rather than inside one of them: two IDEs open
 * on one project is a normal way to work — a debugger in one and a diff in the other — and the second one
 * must <b>not</b> open a second bridge, because a page that finds two bridges for one project has no rule
 * for choosing, and an edit that lands in the wrong IDE's buffer is worse than no bridge at all. The second
 * case is why the probe reads {@code /health} instead of guessing from a {@code BindException}: "the port is
 * busy" is not "a bridge is there", and only the occupant can say which project it serves.
 *
 * <p><b>A sticky port replaces the last two rows with {@link Action#FAIL}.</b> When the project's published
 * {@code host.json} says {@code sticky: true}, that port is pinned for this project, and the answer to "it is
 * taken by somebody else" is to serve <em>nothing</em> and say so loudly — not to move somewhere the user did
 * not ask for. Moving is the right default (a port is a deployment detail nobody wants to think about), but
 * it is the wrong answer when the port is the point: a bookmark, a firewall rule, a second screen. Only a
 * host already serving <b>this</b> project on the sticky port still means {@code SKIP}, because that is the
 * state the pin asks for.
 *
 * <p><b>The probe is unauthenticated on purpose.</b> {@code /health} answers without a token in every host
 * (it is how a page discovers a bridge at all), it carries no secret, and a token cannot be required here
 * anyway: the whole point is to identify a host we have never spoken to.
 *
 * <p>The decision is a pure function of "what happened when this port was tried", which is what makes it
 * testable without sockets; {@link #systemAttempt()} supplies the real attempt for a host that cannot make
 * one itself.
 */
public final class HostPortClaim {

    private HostPortClaim() {
    }

    /** How many consecutive ports are tried before a host gives up and serves nothing. */
    public static final int DEFAULT_ATTEMPTS = 20;

    /** How long the probe waits for a connect, and then for the document. Short: this runs at startup. */
    static final int PROBE_CONNECT_TIMEOUT_MS = 400;
    static final int PROBE_READ_TIMEOUT_MS = 800;

    /**
     * What answers on a port.
     *
     * @param answered true when a JCodeBuddy host answered {@code /health} there; false for a silent port, a
     *                 port held by an unrelated application, and one whose answer was not a health document
     * @param detail   why {@code answered} is false, for a log line; never null
     */
    public record Occupant(boolean answered, String plugin, String ide, String project, int port, String detail) {

        static Occupant silent(String detail) {
            return new Occupant(false, "", "", "", -1, detail);
        }

        /** True when a JCodeBuddy host is there, whatever project it serves. */
        public boolean isHost() {
            return answered;
        }

        /**
         * True when the occupant serves the given project — which is the one case that means "do not start".
         *
         * <p>Compared as paths, not as strings: the occupant reports what its own project path looks like on
         * its side ({@code D:/wrk/x} or {@code /home/x/wrk}), and a trailing separator, a {@code \} and a
         * symlinked parent must not turn one project into two.
         */
        public boolean servesSameProject(Path project) {
            return answered && sameProject(project, this.project);
        }

        /** One line for a log: who is there, or that nothing is. */
        public String describe() {
            if (!answered) {
                return "no JCodeBuddy host answered (" + detail + ")";
            }
            String ideName = ide == null || ide.isBlank() ? plugin : ide;
            return ideName + " on port " + port + " for " + (project == null || project.isBlank()
                    ? "an unnamed project" : project);
        }
    }

    /** What happened when one port was tried: it was taken, or it became ours. */
    public sealed interface Try {
        /** The port is now held by the caller (the bind succeeded). */
        record Free() implements Try {
        }

        /** The port was not available; {@code occupant} says who has it, if anyone answers. */
        record Taken(Occupant occupant) implements Try {
        }
    }

    /** What the caller should do about its endpoint. */
    public enum Action {
        /** Bind {@link Decision#port()} and serve. */
        START,
        /**
         * Do not open an endpoint at all, and do not treat it as an error: another host is already serving
         * this project, which is the state the caller wanted.
         */
        SKIP,
        /**
         * Do not open an endpoint, and tell the user: the project pinned this port ({@code sticky}) and it
         * could not be taken. Moving would be a silent lie about a deliberately chosen port.
         */
        FAIL
    }

    /**
     * The answer.
     *
     * @param action       whether to start, and on which port
     * @param requestedPort what the host asked for; {@code 0} means "let the operating system choose"
     * @param port         the port to bind when {@link #starts()}, otherwise the port that was found taken
     * @param sticky       true when this port is pinned for the project, so it was never a candidate for
     *                     being moved
     * @param tried        how many ports were attempted, 1 when the requested one was free
     * @param portsTried   the ports in the order they were tried, for a log line
     * @param occupant     the last occupant seen, or null when every port tried was free or silent
     * @param reason       one sentence a log can print verbatim
     */
    public record Decision(Action action, int requestedPort, int port, boolean sticky, int tried,
                           List<Integer> portsTried, Occupant occupant, String reason) {

        public boolean starts() {
            return action == Action.START;
        }

        public boolean skipped() {
            return action == Action.SKIP;
        }

        /** True when the caller must report an error instead of serving. */
        public boolean failed() {
            return action == Action.FAIL;
        }

        /** True when the decision cost more than one attempt, i.e. the requested port was not usable. */
        public boolean moved() {
            return tried > 1;
        }
    }

    /**
     * Tries to take one port. The host implements this rather than the claim, because only the host knows how
     * to bind its own server — and doing it this way closes the window a "check, then bind" design leaves
     * open: the port this method reports as free is the port the caller already holds.
     */
    @FunctionalInterface
    public interface Attempt extends Function<Integer, Try> {
    }

    /**
     * The decision, as a pure function of the attempts.
     *
     * @param project       the project the caller wants to serve
     * @param requestedPort the port the user configured, or {@code 0} for "any free port"
     * @param attempts      how many consecutive ports to try before giving up
     * @param attempt       what happens when one port is tried
     */
    public static Decision decide(Path project, int requestedPort, int attempts, Attempt attempt) {
        if (requestedPort <= 0) {
            // An ephemeral port cannot conflict: the operating system hands out one nobody holds. There is
            // nothing to claim, and probing would be busy work.
            return new Decision(Action.START, requestedPort, 0, false, 1, List.of(0), null,
                    "an ephemeral port was requested, so there is no conflict to resolve");
        }
        int limit = Math.max(1, attempts);
        List<Integer> tried = new ArrayList<>(limit);
        List<Occupant> seen = new ArrayList<>(limit);
        Occupant last = null;
        for (int index = 0; index < limit; index++) {
            int port = requestedPort + index;
            tried.add(port);
            Try outcome = attempt.apply(port);
            if (outcome instanceof Try.Free) {
                String reason = index == 0
                        ? "port " + port + " was free"
                        : "port " + port + " was free after " + index + " taken port(s): "
                                + describeTaken(tried.subList(0, index), seen);
                return new Decision(Action.START, requestedPort, port, false, index + 1, List.copyOf(tried),
                        last, reason);
            }
            Occupant occupant = ((Try.Taken) outcome).occupant();
            last = occupant;
            seen.add(occupant);
            if (occupant != null && occupant.servesSameProject(project)) {
                return new Decision(Action.SKIP, requestedPort, port, false, index + 1, List.copyOf(tried),
                        occupant,
                        "another webview host already serves this project: " + occupant.describe()
                                + "; this host will not open a second endpoint for the same project");
            }
        }
        return new Decision(Action.SKIP, requestedPort, requestedPort, false, limit, List.copyOf(tried), last,
                "none of the " + limit + " ports from " + requestedPort + " to " + (requestedPort + limit - 1)
                        + " was free, and none of them serves this project"
                        + (last == null || !last.answered() ? "" : " (" + last.describe() + ")"));
    }

    private static String describeTaken(List<Integer> ports, List<Occupant> seen) {
        List<String> parts = new ArrayList<>(ports.size());
        for (int index = 0; index < ports.size(); index++) {
            Occupant occupant = index < seen.size() ? seen.get(index) : null;
            parts.add(ports.get(index) + ": " + (occupant == null ? "taken" : occupant.describe()));
        }
        return String.join(", ", parts);
    }

    /** {@link #decide} with the real attempt and {@link #DEFAULT_ATTEMPTS}. */
    public static Decision claim(Path project, int requestedPort) {
        return claim(project, requestedPort, null, DEFAULT_ATTEMPTS, systemAttempt(),
                HostPortClaim::occupantOf);
    }

    /**
     * The claim for a project whose port is pinned.
     *
     * <p>Returns {@link Action#SKIP} when a host already serves this project on the pinned port (the state
     * the pin asks for) and {@link Action#FAIL} when the port is held by anybody else — the caller reports
     * that instead of serving somewhere the user did not ask for.
     */
    public static Decision claimSticky(Path project, int stickyPort) {
        return claim(project, stickyPort, stickyPort, DEFAULT_ATTEMPTS, systemAttempt(),
                HostPortClaim::occupantOf);
    }

    /**
     * The whole rule for a host that binds its own server: the project's own published descriptor first, then
     * the port loop — or the pinned port, when the project has one.
     *
     * <p>Both halves are needed, and the order is not arbitrary:
     *
     * <ul>
     *   <li><b>The descriptor first</b>, because a live host for this project may have moved to a port the
     *       caller never asked about. Probing only the requested port would find it free and start a second
     *       bridge for one project — the outcome this class exists to prevent.</li>
     *   <li><b>The port loop second</b>, because the descriptor is a file and the file can lie: a process
     *       killed without its shutdown hook leaves a live-looking pid until the number is reused. The port
     *       probe is the authority, so a descriptor whose port does not answer is not an occupant.</li>
     * </ul>
     *
     * @param requestedPort the port to ask for; ignored when {@code stickyPort} is set
     * @param stickyPort    the project's pinned port, or null when the project has not pinned one. A pinned
     *                      port is tried <b>alone</b>: no other port is a candidate
     * @param attempt       what happens when one port is tried; the caller binds, so the reported-free port is
     *                      already held
     * @param probe         how an occupant is asked what it is; injected so the whole rule can be tested
     *                      without a socket
     */
    public static Decision claim(Path project, int requestedPort, Integer stickyPort, int attempts,
                                 Attempt attempt, java.util.function.IntFunction<Occupant> probe) {
        if (stickyPort != null && stickyPort > 0) {
            return claimPinned(project, stickyPort, attempt, probe);
        }
        Decision published = publishedElsewhere(project, requestedPort, probe);
        if (published != null) {
            return published;
        }
        return decide(project, requestedPort, attempts, attempt);
    }

    /**
     * The pinned case: exactly one port is acceptable, and it is either already served by this project's own
     * host, free, or an error.
     */
    private static Decision claimPinned(Path project, int stickyPort, Attempt attempt,
                                        java.util.function.IntFunction<Occupant> probe) {
        // A live host for this project — on the pinned port or on any other — is still "already served", and
        // that is what the pin wants. Checked before binding, because the answer is not a bind attempt.
        Decision elsewhere = publishedElsewhere(project, stickyPort, probe);
        if (elsewhere != null && elsewhere.occupant() != null
                && elsewhere.occupant().servesSameProject(project)) {
            return elsewhere;
        }

        Occupant occupant = probe.apply(stickyPort);
        if (occupant != null && occupant.servesSameProject(project)) {
            return new Decision(Action.SKIP, stickyPort, stickyPort, true, 1, List.of(stickyPort), occupant,
                    "another webview host already serves this project on its pinned port: "
                            + occupant.describe());
        }
        Try outcome = attempt.apply(stickyPort);
        if (outcome instanceof Try.Free) {
            return new Decision(Action.START, stickyPort, stickyPort, true, 1, List.of(stickyPort), occupant,
                    "port " + stickyPort + " is pinned for this project (sticky), and it was free");
        }
        Occupant taken = ((Try.Taken) outcome).occupant();
        // The probe already knows who is there; the attempt only knows that binding failed. Report the richer
        // of the two, so the message names the host that holds the pinned port instead of "another application".
        Occupant reported = occupant != null && occupant.answered() ? occupant : taken;
        return new Decision(Action.FAIL, stickyPort, stickyPort, true, 1, List.of(stickyPort), reported,
                "port " + stickyPort + " is pinned for this project (sticky) and is held by "
                        + (reported == null || !reported.answered() ? "another application"
                                : reported.describe())
                        + "; not moving to another port. Free that port, or set \"sticky\": false in "
                        + HostDescriptor.fileOf(project));
    }

    /**
     * The descriptor fast path: a live host that did not write this file and answers for this project.
     *
     * <p>A descriptor written by <em>this</em> process is ignored — it records a socket this process has
     * already given up (it is how a restart re-claims its own port), and treating it as an occupant would
     * make a host refuse to restart itself.
     *
     * @return the skip decision, or null when the descriptor says nothing useful
     */
    public static Decision publishedElsewhere(Path project, int requestedPort,
                                              java.util.function.IntFunction<Occupant> probe) {
        HostDescriptor existing = HostDescriptor.read(project);
        if (existing == null || existing.isOurs() || !existing.isLive()) {
            return null;
        }
        Occupant occupant = probe.apply(existing.port());
        if (!occupant.servesSameProject(project)) {
            // A live process wrote the file but nothing that identifies itself as a host for this project
            // answers there: a stale or half-started record. The port loop decides, not the file.
            return null;
        }
        return new Decision(Action.SKIP, requestedPort, existing.port(), false, 0, List.of(existing.port()),
                occupant,
                "another webview host already serves this project: " + occupant.describe()
                        + ", published in " + HostDescriptor.fileOf(project)
                        + "; this host will not open a second endpoint for the same project");
    }

    /**
     * The attempt a host uses when it cannot bind its own server (or does not want to), so the default
     * behaviour is still one call: probe whether the port can be bound, and when it cannot, ask the occupant
     * what it is.
     *
     * <p>The probe releases the socket before the caller binds it, so a third process can in principle take
     * it in between. A host that can bind its own server (both Java hosts do) should pass its own
     * {@link Attempt} and close that window; this exists so that the sidecar and anything scripted has a
     * correct-in-practice default rather than a second implementation of the rule.
     */
    public static Attempt systemAttempt() {
        return port -> isBindable(port) ? new Try.Free() : new Try.Taken(occupantOf(port));
    }

    /** True when loopback {@code port} can be bound right now. */
    public static boolean isBindable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Asks {@code 127.0.0.1:port} for its {@code /health}, and reads the identity out of it.
     *
     * <p>Anything that is not a health document — no listener, a refused connection, a timeout, a non-200, a
     * body that is not JSON, a JSON body without {@code plugin} and {@code port} — is reported as "nobody we
     * know is there". The distinction matters: only a recognised occupant can make a host skip, so an
     * unrecognised one always means "take the next port".
     */
    public static Occupant occupantOf(int port) {
        if (port <= 0) {
            return Occupant.silent("port " + port + " is not probeable");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/health").toURL()
                    .openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status != 200) {
                return Occupant.silent("the port answered HTTP " + status + ", not a health document");
            }
            String body;
            try (InputStream stream = connection.getInputStream()) {
                body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            return parseHealth(port, body);
        } catch (IOException e) {
            return Occupant.silent("nothing answered on port " + port + " (" + e.getClass().getSimpleName() + ")");
        } catch (RuntimeException e) {
            return Occupant.silent("probing port " + port + " failed (" + e.getMessage() + ")");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Reads a {@code /health} body into an {@link Occupant}; true only for a recognizable document. */
    public static Occupant parseHealth(int port, String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
            if (!parsed.isJsonObject()) {
                return Occupant.silent("the answer on port " + port + " is not a JSON object");
            }
            JsonObject json = parsed.getAsJsonObject();
            if (!json.has("plugin") || !json.has("port")) {
                // Every JCodeBuddy host answers both keys (HostHealth.REQUIRED_KEYS). Something else that
                // happens to answer JSON on an HTTP port is an unrelated application, not an occupant.
                return Occupant.silent("the answer on port " + port + " is JSON but not a webview /health");
            }
            return new Occupant(true, text(json, "plugin"), text(json, "ide"), text(json, "project"),
                    json.get("port").getAsInt(), "answered /health");
        } catch (JsonSyntaxException | NumberFormatException | IllegalStateException e) {
            return Occupant.silent("the answer on port " + port + " is not a readable health document");
        }
    }

    /** True when two spellings of a project path name the same directory. */
    public static boolean sameProject(Path expected, String reported) {
        if (expected == null || reported == null || reported.isBlank()) {
            return false;
        }
        try {
            Path resolved = comparable(Path.of(reported.trim()));
            return resolved != null && resolved.equals(comparable(expected));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** A real path when the directory exists, otherwise the normalised absolute one. */
    public static Path comparable(Path path) {
        if (path == null) {
            return null;
        }
        try {
            Path real = path.toRealPath();
            return real;
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** One string field of a health document, or an empty string when it is absent or null. */
    private static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
