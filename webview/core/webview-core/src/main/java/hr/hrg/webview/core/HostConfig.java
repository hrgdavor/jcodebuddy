package hr.hrg.webview.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * The project's <b>committed</b> host preferences: {@code <project>/.jcodebuddy/conf/webview.json}.
 *
 * <p>This is configuration, not state, and the distinction decides both its location and its lifetime. It
 * lives in the reserved, tracked {@code conf/} subtree (DEC-026's final amendment, DEC-032), so it travels
 * with the checkout: a developer who clones the project gets the same starting point as everyone else, and a
 * reviewer sees the value in a diff. What it must <b>not</b> hold is anything about a running process — no
 * bound port, no pid, no token — because those are host state and belong in
 * {@code .jcodebuddy/webview/host.json}, which is ignored (DEC-032 § 2, DEC-033 § 1).
 *
 * <pre>{@code
 * // .jcodebuddy/conf/webview.json  — committed, reviewed, shared by the whole team
 * { "port": 18882 }
 * }</pre>
 *
 * <p><b>What the port means here.</b> It is the <b>default</b> to ask for — a bootstrap value, and
 * <b>optional</b>: a project with no such file gets the host's built-in default, which is the normal state.
 * The port a checkout is <em>currently</em> using is a different thing in a different file, the local
 * {@code .jcodebuddy/webview/host.json} (see {@link HostDescriptor}), and it outranks this default — a
 * checkout that took the next free port keeps it on the next start rather than drifting back. So the
 * resolution is: the run's explicit flag or IDE setting, then the checkout's current port, then this
 * default, then the built-in fallback ({@link #requestedPort}).
 *
 * <p>The default is deliberately a default rather than a pin: a project with several git worktrees is one
 * repository checked out several times, each a different directory with its own local state — so a mandatory
 * port would collide, and the second worktree must be free to take the next free one. A user who wants one
 * worktree pinned says so locally, by setting {@code sticky} in that worktree's
 * {@code .jcodebuddy/webview/host.json} (see {@link HostPortClaim}).
 *
 * <p><b>There is no user-home equivalent of this file, and there must not be.</b> A port names a socket for
 * one served directory, so a machine-wide port default would have every project on the machine ask for the
 * same port at once — a collision the user scope can neither express nor resolve. {@code ~/.jcodebuddy/} is
 * not consulted for a port at all (DEC-032 § 2).
 *
 * <p>A malformed file is never fatal. A host that cannot read its default says so in its log and starts
 * with the next source in the list, because refusing to serve a project over a config typo would be a worse
 * failure than serving it on the conventional port.
 */
public final class HostConfig {

    private HostConfig() {
    }

    /** The tracked configuration directory, a sibling of the ignored {@code webview/} state directory. */
    public static final String DIR = ".jcodebuddy/conf";

    /** This host's configuration file. One file per tool, so two tools never edit each other's. */
    public static final String FILE_NAME = "webview.json";

    /** The port a checkout asks for when nothing more specific was given, and any problem reading it. */
    public record PortPreference(OptionalInt port, String problem) {

        /** True when the file named a usable port. */
        public boolean present() {
            return port.isPresent();
        }

        /** One line for a host's log: what was read, or why it was not. Empty when there is nothing to say. */
        public String describe(Path project) {
            if (port.isPresent()) {
                return "the project asks for port " + port.getAsInt() + " (" + fileOf(project) + ")";
            }
            return problem;
        }

        static PortPreference none() {
            return new PortPreference(OptionalInt.empty(), "");
        }

        static PortPreference problem(String problem) {
            return new PortPreference(OptionalInt.empty(), problem);
        }
    }

    /**
     * The port a run should ask for, and where that answer came from.
     *
     * @param port    the port to ask for; {@code <= 0} means "nothing was configured", which is a host's own
     *                business (the standalone host binds an ephemeral port, the IDE hosts stay off)
     * @param source  {@code "flag"}, {@code "current"}, {@code "default"} or {@code "fallback"} — a label for
     *                a log line, so a user can tell why the host is on the port it is on
     * @param problem a configuration problem worth reporting, or the empty string
     */
    public record RequestedPort(int port, String source, String problem) {

        public boolean configured() {
            return port > 0;
        }

        /**
         * One line naming the decision, for a host's log. Empty for the fallback case, where there is nothing
         * interesting to say.
         */
        public String describe(Path project) {
            return switch (source) {
                case "flag" -> "port " + port + " was chosen for this run";
                case "current" -> "port " + port + " is the port this checkout is currently on ("
                        + HostDescriptor.fileOf(project) + ")";
                case "default" -> "port " + port + " is this project's default ("
                        + HostConfig.fileOf(project) + ")";
                default -> "";
            };
        }
    }

    /**
     * The port to ask for, in one place for every host: the run's explicit choice, then the port this
     * <b>checkout</b> is currently on, then the project's committed <b>default</b>, then the host's own
     * fallback.
     *
     * <p>This is the whole two-file model in one method. `conf/webview.json` is a <em>default</em> — optional,
     * tracked, and there to give a fresh clone somewhere sensible to start. `webview/host.json` is the
     * <em>current</em> port of this checkout — local, never in git, and more specific than the default, which
     * is why it wins: a checkout that took the next free port the first time keeps it, instead of drifting
     * back to a port the default names and colliding there again on every start.
     *
     * <p>A pin is a different question and is not resolved here: when the checkout's {@code host.json} says
     * {@code sticky}, the caller passes that port to {@link HostPortClaim#claimSticky} and this preference
     * list does not apply at all.
     *
     * @param explicitPort the flag or IDE setting for this run, or null when the user chose nothing
     * @param fallbackPort what the host does when nothing is configured; {@code 0} for the standalone host
     *                     (bind an ephemeral port), a conventional port for an IDE host, or {@code -1} for a
     *                     host that stays off
     */
    public static RequestedPort requestedPort(Path project, Integer explicitPort, int fallbackPort) {
        if (explicitPort != null && explicitPort > 0) {
            return new RequestedPort(explicitPort, "flag", "");
        }
        HostDescriptor current = HostDescriptor.read(project);
        PortPreference preference = portPreference(project);
        String problem = preference.problem();
        if (current != null && current.port() > 0) {
            return new RequestedPort(current.port(), "current", problem);
        }
        if (preference.present()) {
            return new RequestedPort(preference.port().getAsInt(), "default", problem);
        }
        return new RequestedPort(fallbackPort, "fallback", problem);
    }

    public static Path directoryOf(Path project) {
        return project.resolve(DIR);
    }

    public static Path fileOf(Path project) {
        return directoryOf(project).resolve(FILE_NAME);
    }

    /**
     * The port this project asks for, or an empty preference when there is no configuration — which is the
     * normal state and never an error.
     */
    public static PortPreference portPreference(Path project) {
        Path file = fileOf(project);
        if (!Files.isRegularFile(file)) {
            return PortPreference.none();
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return PortPreference.problem("could not read " + file + ": " + e.getMessage());
        }
        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return PortPreference.problem(file + " is not a JSON object; ignoring it");
            }
            object = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return PortPreference.problem(file + " is not valid JSON; ignoring it");
        }
        JsonElement port = object.get("port");
        if (port == null || port.isJsonNull()) {
            return PortPreference.none();
        }
        if (!port.isJsonPrimitive() || !port.getAsJsonPrimitive().isNumber()) {
            // A quoted number is a typo, not a port: accept it and the next typo is accepted too.
            return PortPreference.problem(file + ": 'port' must be a number (" + port + "); ignoring it");
        }
        double asDouble = port.getAsDouble();
        if (asDouble != Math.rint(asDouble)) {
            return PortPreference.problem(file + ": 'port' must be a whole number (" + port + "); ignoring it");
        }
        int value = (int) asDouble;
        if (value < 1 || value > 65535) {
            return PortPreference.problem(file + ": 'port' must be between 1 and 65535, was " + value
                    + "; ignoring it");
        }
        return new PortPreference(OptionalInt.of(value), "");
    }
}
