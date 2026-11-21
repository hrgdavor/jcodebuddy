package hr.hrg.jcodebuddy.ipc;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The convention a live tool inside a project follows to be reachable by a host (DEC-053).
 *
 * <p>A tool publishes one Unix domain socket under the served project's derived
 * {@code .jcodebuddy/ipc/} subtree, and a descriptor next to it that names the socket, the tool, the process
 * and the methods it implements. A host — the webview server, a standalone server, or any other process that
 * is allowed to read the project's files — discovers the descriptor instead of guessing a port, and proxies
 * {@code HTTP} to it. Nothing binds a TCP port, so there is no port to number, reserve or collide.</p>
 *
 * <p>The socket is host state in exactly the sense DEC-032 gives that term: a fact about one checkout on one
 * machine. It is machine-written, ignored by git, and never configuration. A tool that is not running simply
 * has no socket; liveness is always answered by connecting, never by the descriptor's existence.</p>
 *
 * <p>The three names are fixed:</p>
 * <ul>
 *   <li>{@code <project>/.jcodebuddy/ipc/<tool>.sock} — the socket, or a shorter path in the system temp
 *       directory when the conventional one is too long for the platform's {@code sun_path} limit; the
 *       descriptor carries the actual path, so a consumer never recomputes it.</li>
 *   <li>{@code <project>/.jcodebuddy/ipc/<tool>.json} — the descriptor.</li>
 *   <li>{@link #VERSION} — the wire contract, carried by the descriptor and by {@code tool.info}.</li>
 * </ul>
 *
 * <p>This class is the whole of the naming rule; there is no second place that spells these paths.</p>
 */
public final class ToolProtocol {

    /** The wire contract of the socket and the descriptor. A consumer must refuse an unknown one. */
    public static final String VERSION = "jcodebuddy-tool/1";

    /**
     * The one method every endpoint answers, whatever tool it is.
     *
     * <p>It returns the descriptor, and it is how a consumer proves an endpoint is live before trusting the
     * socket file. The transport owns it so a tool cannot forget it and a probe never depends on the tool's own
     * method table.</p>
     */
    public static final String TOOL_INFO = "tool.info";

    /**
     * The request that turns a connection into an event stream.
     *
     * <p>Its params are {@code {topic, since?}}; its result confirms the topic, and every event after it is a
     * {@link #TOOL_EVENT} notification. A subscribed connection carries nothing else — the response is the
     * last request/response message on it — so a consumer that wants both calls and events opens two
     * connections, which is exactly what a host does for one SSE client.</p>
     */
    public static final String TOOL_SUBSCRIBE = "tool.subscribe";

    /** The notification a tool pushes on a subscribed connection: {@code params = {topic, data}}. */
    public static final String TOOL_EVENT = "tool.event";

    /** The notification that ends a stream the tool could not keep up with. */
    public static final String TOOL_EVENT_ERROR = "tool.event-error";

    /** Suffix of the socket file inside the tool directory. */
    public static final String SOCKET_SUFFIX = ".sock";

    /** Suffix of the descriptor next to it. */
    public static final String DESCRIPTOR_SUFFIX = ".json";

    /**
     * The largest socket path this library writes into the project.
     *
     * <p>The platform limit is {@code sockaddr_un.sun_path}: 108 bytes on Linux and Windows, 104 on macOS. A
     * project can be nested deeply enough to exceed it, so the conventional path is used only when it fits and
     * the descriptor names the real one either way.</p>
     */
    public static final int MAX_SOCKET_PATH = 100;

    /** The most bytes one request line may carry. A local client that exceeds it is refused, not buffered. */
    public static final int MAX_FRAME_BYTES = 1 << 20;

    private static final Pattern TOOL_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private ToolProtocol() {
    }

    /** The tool directory a project publishes its endpoints in: {@code <project>/.jcodebuddy/ipc}. */
    public static Path directory(Path projectRoot) {
        return projectRoot.resolve(".jcodebuddy").resolve("ipc");
    }

    /** The conventional socket path for a tool, whether or not it is short enough to bind. */
    public static Path conventionalSocket(Path projectRoot, String tool) {
        return directory(projectRoot).resolve(toolId(tool) + SOCKET_SUFFIX);
    }

    /** The descriptor path for a tool. Always inside the project, even when the socket had to move. */
    public static Path descriptorFile(Path projectRoot, String tool) {
        return directory(projectRoot).resolve(toolId(tool) + DESCRIPTOR_SUFFIX);
    }

    /**
     * The path a new endpoint should bind.
     *
     * <p>The conventional {@code .jcodebuddy/ipc/<tool>.sock} is used when its absolute form fits
     * {@link #MAX_SOCKET_PATH}; otherwise the socket moves to the system temp directory under a name derived
     * from the project path, and the descriptor — which stays in the project — becomes the only way a consumer
     * learns where it is.</p>
     */
    public static Path socket(Path projectRoot, String tool) {
        Path conventional = conventionalSocket(projectRoot, tool);
        if (conventional.toAbsolutePath().toString().length() <= MAX_SOCKET_PATH) {
            return conventional;
        }
        String hash = Integer.toHexString(projectRoot.toAbsolutePath().normalize().toString().hashCode());
        String name = "jcodebuddy-ipc-" + hash + "-" + toolId(tool) + SOCKET_SUFFIX;
        return Path.of(System.getProperty("java.io.tmpdir")).resolve(name);
    }

    /** Validates and returns a tool id: lowercase letters, digits and {@code -}, starting with a letter or digit. */
    public static String toolId(String tool) {
        if (tool == null || !TOOL_ID.matcher(tool).matches()) {
            throw new IllegalArgumentException(
                    "a tool id is lowercase letters, digits and '-', starting with a letter or digit: " + tool);
        }
        return tool;
    }

    /** Whether a descriptor or a caller names a protocol this library speaks. */
    public static boolean isSupported(String protocol) {
        return VERSION.equals(protocol);
    }

    /**
     * Whether this JDK can bind a Unix domain socket.
     *
     * <p>The type has existed since JDK 16 and works on Linux, macOS and Windows 10 1803+, where a socket is a
     * filesystem object by construction. There is no Windows fallback: a named pipe would be a second transport
     * and a second contract, and this one is deliberately one transport. A host on a platform without it simply
     * exposes no tool endpoints.</p>
     */
    public static boolean isSupportedPlatform() {
        try {
            Class.forName("java.net.UnixDomainSocketAddress");
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }
}
