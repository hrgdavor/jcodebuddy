package hr.hrg.jcodebuddy.ipc;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What a consumer needs to find and use one live tool, published next to its socket.
 *
 * <p>The descriptor is the authority. It names the socket — which may be outside the project when the
 * conventional path is too long for the platform — the tool, the process, and the methods the tool
 * implements. A consumer therefore never recomputes a path from a convention it might spell differently, and
 * the conventional path ({@link ToolProtocol#conventionalSocket}) is only a fast path.</p>
 *
 * <p>It never carries a secret. There is no token, because the socket is a filesystem object whose access is
 * the operating system's answer: only the user who owns the project can connect. A host that proxies the
 * socket over HTTP keeps its own token and allow-list; the descriptor is not a credential.</p>
 *
 * <p>Paths are absolute and forward-slashed, the spelling every published descriptor in this ecosystem uses,
 * so a Windows path is not rewritten by the JSON layer.</p>
 *
 * @param protocol    the wire contract, {@link ToolProtocol#VERSION} when this library wrote it
 * @param tool        the tool id; {@code <tool>.sock} and {@code <tool>.json} derive from it
 * @param title       a human name for the tool, for a page that lists what is available
 * @param ide         the product the tool runs inside, or a standalone tool's own name
 * @param ideVersion  the product build, or an empty string when unknown
 * @param pid         the process that owns the socket
 * @param socket      the absolute socket path, forward-slashed
 * @param project     the project root the tool serves, forward-slashed
 * @param startedAt   when the endpoint started, ISO-8601 instant
 * @param capabilities the method names the tool implements
 * @param topics      the event topics the tool streams; empty when it pushes nothing
 */
public record ToolDescriptor(
        String protocol,
        String tool,
        String title,
        String ide,
        String ideVersion,
        long pid,
        String socket,
        String project,
        String startedAt,
        List<String> capabilities,
        List<String> topics) {

    public ToolDescriptor {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    /** Builds the descriptor of a running endpoint, with this process's pid and the current instant. */
    public static ToolDescriptor of(Path projectRoot, String tool, String title, String ide, String ideVersion,
                                    Path socket, Collection<String> capabilities) {
        return of(projectRoot, tool, title, ide, ideVersion, socket, capabilities, List.of());
    }

    /** Builds the descriptor of a running endpoint that also streams events. */
    public static ToolDescriptor of(Path projectRoot, String tool, String title, String ide, String ideVersion,
                                    Path socket, Collection<String> capabilities, Collection<String> topics) {
        return new ToolDescriptor(
                ToolProtocol.VERSION,
                ToolProtocol.toolId(tool),
                text(title),
                text(ide),
                text(ideVersion),
                ProcessHandle.current().pid(),
                forward(socket.toAbsolutePath()),
                forward(projectRoot.toAbsolutePath().normalize()),
                Instant.now().toString(),
                capabilities == null ? List.of() : List.copyOf(capabilities),
                topics == null ? List.of() : List.copyOf(topics));
    }

    /** The socket this descriptor names. */
    public Path socketPath() {
        return Path.of(socket);
    }

    /** The project root this descriptor names. */
    public Path projectPath() {
        return Path.of(project);
    }

    /** Whether this descriptor speaks a protocol this library implements. */
    public boolean supported() {
        return ToolProtocol.isSupported(protocol);
    }

    /** The descriptor as the map {@code tool.info} returns, with the field order a reader sees in the file. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("protocol", protocol);
        map.put("tool", tool);
        map.put("title", title);
        map.put("ide", ide);
        map.put("ideVersion", ideVersion);
        map.put("pid", pid);
        map.put("socket", socket);
        map.put("project", project);
        map.put("startedAt", startedAt);
        map.put("capabilities", capabilities);
        map.put("topics", topics);
        return map;
    }

    /** Writes the descriptor atomically: a consumer never reads a half-written document. */
    public void write(Path file) throws IOException {
        Path directory = file.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, Json.write(asMap()), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException noAtomicMove) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Reads one descriptor; a malformed or foreign document is reported, never silently turned into a default. */
    public static ToolDescriptor read(Path file) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw missing;
        }
        JsonNode node;
        try {
            node = Json.read(text);
        } catch (RuntimeException malformed) {
            throw new IOException("cannot parse tool descriptor " + file + ": " + malformed.getMessage(), malformed);
        }
        if (node == null || !node.isObject()) {
            throw new IOException("tool descriptor " + file + " is not a JSON object");
        }
        ToolDescriptor descriptor;
        try {
            descriptor = Json.MAPPER.convertValue(node, ToolDescriptor.class);
        } catch (RuntimeException malformed) {
            throw new IOException("tool descriptor " + file + " has the wrong shape: " + malformed.getMessage(),
                    malformed);
        }
        if (descriptor == null || descriptor.tool() == null || descriptor.tool().isBlank()
                || descriptor.socket() == null || descriptor.socket().isBlank()) {
            throw new IOException("tool descriptor " + file + " names no tool or no socket");
        }
        return descriptor;
    }

    /**
     * Every readable descriptor in a project's tool directory, sorted by tool id.
     *
     * <p>A malformed document is skipped: one broken tool must not hide the others from a host that lists
     * what is available. The caller that cares about that one file reads it directly and gets the reason.</p>
     */
    public static List<ToolDescriptor> list(Path projectRoot) {
        Path directory = ToolProtocol.directory(projectRoot);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<ToolDescriptor> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(file -> file.getFileName().toString().endsWith(ToolProtocol.DESCRIPTOR_SUFFIX))
                    .sorted()
                    .forEach(file -> {
                        try {
                            found.add(read(file));
                        } catch (IOException unreadable) {
                            // Reported by reading the file directly; listing is best-effort by design.
                        }
                    });
        } catch (IOException unreadableDirectory) {
            return List.of();
        }
        found.sort((left, right) -> left.tool().compareTo(right.tool()));
        return List.copyOf(found);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String forward(Path path) {
        return path.toString().replace('\\', '/');
    }
}
