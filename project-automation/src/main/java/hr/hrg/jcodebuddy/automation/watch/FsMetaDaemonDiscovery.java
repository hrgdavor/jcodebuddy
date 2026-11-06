package hr.hrg.jcodebuddy.automation.watch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Finds the fs-meta daemon that serves one root, from the daemon's own host state.
 *
 * <p>The daemon is the watcher and the only trio writer ({@code FORMAT.daemon.md} § 1), so a client never guesses a
 * socket from a trio or starts a watcher of its own. Two pieces of <em>host state</em> are the whole discovery:</p>
 *
 * <ol>
 *   <li>the durable root list — {@code $FSMETA_CONFIG_HOME/fsmeta/roots.json}, or
 *       {@code %APPDATA%\fsmeta\roots.json} on Windows — whose record for this root carries the socket path the
 *       daemon rewrote on its last start ({@code FORMAT.daemon.md} § 3);</li>
 *   <li>that record's socket file, under the runtime directory ({@code FSMETA_RUNTIME_DIR},
 *       {@code XDG_RUNTIME_DIR/fsmeta}, or {@code %LOCALAPPDATA%\fsmeta\run}) or wherever a {@code --base} start put
 *       it — the record carries the absolute path, so the client does not have to recompute the id.</li>
 * </ol>
 *
 * <p>{@code daemon.json} is deliberately not required: the format says it is not the authority on whether a daemon
 * is live, and a daemon started with an explicit {@code --base} writes it somewhere this process could not guess.
 * Liveness is proven by the connection attempt, which is what {@link FsMetaDaemonWatch} does — a stale socket file
 * makes discovery succeed and attaching fail with a message naming the socket, and the caller falls back to the
 * read-only path either way.</p>
 *
 * <p>The trio is deliberately not consulted: it says what the tree looked like, not where a daemon lives, and
 * {@code FORMAT.md} keeps absolute paths out of it. A missing or dead daemon is a normal answer — the caller falls
 * back to reading the trio without live events — so discovery reports a {@link Attach} with a human-readable
 * {@link Attach#problem()} instead of throwing.</p>
 *
 * <p>The environment and the platform are parameters rather than ambient facts so the rules above can be asserted
 * on any host; {@link #discover(Path)} uses this process's environment.</p>
 */
public final class FsMetaDaemonDiscovery {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FsMetaDaemonDiscovery() {
    }

    /**
     * One daemon's host state for a served root.
     *
     * @param runtimeBase the runtime directory the socket lives under
     * @param configFile  the {@code roots.json} the record came from
     * @param id          the record's derived id ({@code hex8(low 32 bits of wyhash(root))})
     * @param root        the canonical root the record serves
     * @param socket      the consumer socket for that root
     */
    public record Location(Path runtimeBase, Path configFile, String id, Path root, Path socket) {
    }

    /**
     * What discovery found.
     *
     * @param location the daemon's host state, or {@code null} when no daemon serves the root
     * @param problem  why there is no location; empty when {@code location} is present
     */
    public record Attach(Location location, String problem) {

        public Attach {
            problem = problem == null ? "" : problem;
            if (location != null && !problem.isEmpty()) {
                throw new IllegalArgumentException("an attached result cannot also carry a problem");
            }
        }

        /** Whether a daemon is registered and its socket is present. */
        public boolean attached() {
            return location != null;
        }
    }

    /** Discovers the daemon serving {@code watchedRoot} from this process's environment and platform. */
    public static Attach discover(Path watchedRoot) {
        return discover(watchedRoot, System.getenv(), isWindows());
    }

    /** The testable form: {@code env} is read as the daemon would read it, and {@code windows} picks the rules. */
    public static Attach discover(Path watchedRoot, Map<String, String> env, boolean windows) {
        Path root;
        try {
            root = watchedRoot.toRealPath();
        } catch (IOException missing) {
            root = watchedRoot.toAbsolutePath().normalize();
        }
        Path runtimeBase = runtimeBase(env, windows);
        Path configFile = configFile(env, windows);
        if (!Files.isRegularFile(configFile)) {
            return new Attach(null, "no fsmeta root list at " + configFile
                    + " (no daemon has this root registered; start one with `fsmeta daemon start` and add it with"
                    + " `fsmeta root add " + root + "`)");
        }

        JsonNode document;
        try {
            document = MAPPER.readTree(Files.readString(configFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException malformed) {
            return new Attach(null, "cannot read the fsmeta root list " + configFile + ": "
                    + malformed.getClass().getSimpleName() + ": " + malformed.getMessage());
        }
        if (document == null || !document.isObject()) {
            return new Attach(null, "the fsmeta root list " + configFile + " is not a JSON object");
        }

        JsonNode record = recordFor(document.path("roots"), root, windows);
        if (record == null) {
            return new Attach(null, "the fsmeta root list " + configFile + " has no record for " + root
                    + " (add it with `fsmeta root add " + root + "`)");
        }
        String id = text(record, "id");
        String socket = text(record, "socket");
        if (socket == null || socket.isBlank()) {
            return new Attach(null, "the fsmeta record for " + root + " in " + configFile
                    + " has no socket path (the daemon rewrites it on every start)");
        }
        Path socketPath;
        try {
            socketPath = Path.of(socket);
        } catch (InvalidPathException malformed) {
            return new Attach(null, "the fsmeta record for " + root + " names an invalid socket path: " + socket);
        }
        Path control = runtimeBase.resolve("daemon.json");
        if (!Files.exists(socketPath)) {
            return new Attach(null, "the fsmeta daemon is not running: the record for " + root
                    + " names " + socketPath + ", which does not exist"
                    + (Files.isRegularFile(control) ? "" : " (and " + control + " is absent too)"));
        }
        return new Attach(new Location(runtimeBase, configFile, id == null ? "" : id, root, socketPath), "");
    }

    /**
     * The runtime directory, exactly as {@code FORMAT.daemon.md} § 1a resolves it: {@code FSMETA_RUNTIME_DIR} when
     * set, {@code XDG_RUNTIME_DIR/fsmeta} on POSIX, {@code ~/.cache/fsmeta/run} otherwise, and
     * {@code %LOCALAPPDATA%\fsmeta\run} on Windows.
     */
    public static Path runtimeBase(Map<String, String> env, boolean windows) {
        String override = value(env, "FSMETA_RUNTIME_DIR");
        if (override != null) {
            return Path.of(override);
        }
        if (windows) {
            String local = value(env, "LOCALAPPDATA");
            return Path.of(local != null ? local : home(env, windows)).resolve("fsmeta").resolve("run");
        }
        String xdg = value(env, "XDG_RUNTIME_DIR");
        if (xdg != null) {
            return Path.of(xdg).resolve("fsmeta");
        }
        return Path.of(home(env, windows)).resolve(".cache").resolve("fsmeta").resolve("run");
    }

    /**
     * The durable root list, exactly as {@code FORMAT.daemon.md} § 1b resolves it:
     * {@code $FSMETA_CONFIG_HOME/fsmeta/roots.json}, {@code $XDG_CONFIG_HOME/fsmeta/roots.json},
     * {@code ~/.config/fsmeta/roots.json}, and {@code %APPDATA%\fsmeta\roots.json} on Windows.
     */
    public static Path configFile(Map<String, String> env, boolean windows) {
        String override = value(env, "FSMETA_CONFIG_HOME");
        if (override != null) {
            return Path.of(override).resolve("fsmeta").resolve("roots.json");
        }
        if (windows) {
            String roaming = value(env, "APPDATA");
            return Path.of(roaming != null ? roaming : home(env, windows)).resolve("fsmeta").resolve("roots.json");
        }
        String xdg = value(env, "XDG_CONFIG_HOME");
        if (xdg != null) {
            return Path.of(xdg).resolve("fsmeta").resolve("roots.json");
        }
        return Path.of(home(env, windows)).resolve(".config").resolve("fsmeta").resolve("roots.json");
    }

    private static JsonNode recordFor(JsonNode roots, Path canonicalRoot, boolean windows) {
        if (roots == null || !roots.isArray()) {
            return null;
        }
        for (JsonNode record : roots) {
            String recorded = text(record, "root");
            if (recorded == null || recorded.isBlank()) {
                continue;
            }
            Path path;
            try {
                path = Path.of(recorded).toAbsolutePath().normalize();
            } catch (InvalidPathException malformed) {
                continue;
            }
            if (samePath(path, canonicalRoot, windows)) {
                return record;
            }
        }
        return null;
    }

    private static boolean samePath(Path left, Path right, boolean windows) {
        Path a = left.toAbsolutePath().normalize();
        Path b = right.toAbsolutePath().normalize();
        return windows ? a.toString().equalsIgnoreCase(b.toString()) : a.equals(b);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static String value(Map<String, String> env, String name) {
        String found = env == null ? null : env.get(name);
        return found == null || found.isBlank() ? null : found;
    }

    private static String home(Map<String, String> env, boolean windows) {
        String home = value(env, windows ? "USERPROFILE" : "HOME");
        if (home == null) {
            home = value(env, windows ? "HOME" : "USERPROFILE");
        }
        if (home == null) {
            home = System.getProperty("user.home");
        }
        return home;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
