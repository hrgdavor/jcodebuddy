package hr.hrg.jcodebuddy.ipc;

import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One live tool, listening on its Unix socket for newline-delimited JSON-RPC 2.0.
 *
 * <p>Starting an endpoint is three things: bind the socket named by the convention, publish the descriptor
 * next to it, and answer requests until {@link #stop()}. Nothing listens on TCP, so there is no port to
 * number and nothing to collide with another project's tool.</p>
 *
 * <h3>One endpoint per tool per project</h3>
 * <p>Two editor windows on the same project are normal, and a consumer has no rule for choosing between two
 * endpoints that answer for one project, so the second one does not bind: {@link #start()} answers
 * {@link BusyException}. The test is a real connection ({@code tool.info}), not the socket file's existence —
 * a socket file left behind by a crashed process is deleted and replaced.</p>
 *
 * <h3>The socket is scoped by the filesystem</h3>
 * <p>There is no token on the wire. The socket is a file, it is created with the owner's read/write mode on
 * POSIX systems, and the operating system refuses every other user. A host that proxies these calls over HTTP
 * applies its own token and allow-list; it does not pass this socket's credential on, because there is none.</p>
 *
 * <h3>Failure is reported, not swallowed</h3>
 * <p>A malformed frame gets a JSON-RPC error and the connection stays usable; a method that throws
 * {@link ToolException} gets that code; anything else becomes {@code internal error} with the exception's
 * simple name, and {@link #lastError()} keeps the detail for the tool's own log. A consumer that disconnects
 * is not an error at all.</p>
 */
public final class ToolEndpoint implements AutoCloseable {

    /** Thrown by {@link #start()} when another live endpoint already answers on this tool's socket. */
    public static final class BusyException extends IOException {

        private static final long serialVersionUID = 1L;

        private final Path socket;

        BusyException(Path socket, String detailedMessage) {
            super(detailedMessage);
            this.socket = socket;
        }

        /** The socket the live endpoint answered on, so a caller can name it in its log. */
        public Path socket() {
            return socket;
        }
    }

    private static final long PROBE_TIMEOUT_MS = 750;
    private static final int MAX_CONNECTIONS = 16;
    /** Events buffered for one subscriber; a consumer that falls this far behind ends its stream honestly. */
    private static final int SUBSCRIBER_QUEUE = 256;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final Path projectRoot;
    private final String tool;
    private final String title;
    private final String ide;
    private final String ideVersion;
    private final ToolMethods methods;
    private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService clientPool = Executors.newCachedThreadPool(
            task -> daemon(task, "jcodebuddy-tool-client-"));

    private volatile ServerSocketChannel server;
    private volatile Thread acceptor;
    private volatile ToolDescriptor descriptor;
    private volatile boolean running;
    private volatile String lastError = "";

    /**
     * @param projectRoot the project the tool serves; its {@code .jcodebuddy/ipc/} directory holds the socket
     *                    and the descriptor
     * @param tool        the tool id, a lowercase slug such as {@code jcodebuddy-incremental-build}
     * @param title       a human name for a host to show
     * @param ide         the product the tool runs inside, or the tool's own name when it is standalone
     * @param ideVersion  the product build, or an empty string
     * @param methods     what the tool answers
     */
    public ToolEndpoint(Path projectRoot, String tool, String title, String ide, String ideVersion,
                        ToolMethods methods) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("a project root is required");
        }
        if (methods == null) {
            throw new IllegalArgumentException("methods are required");
        }
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.tool = ToolProtocol.toolId(tool);
        this.title = title == null ? "" : title;
        this.ide = ide == null ? "" : ide;
        this.ideVersion = ideVersion == null ? "" : ideVersion;
        this.methods = methods;
    }

    /**
     * Binds the socket and publishes the descriptor.
     *
     * @return the descriptor that is now on disk
     * @throws BusyException when another live endpoint already answers on this socket
     * @throws UnsupportedOperationException when the platform has no Unix domain sockets
     */
    public synchronized ToolDescriptor start() throws IOException {
        if (running && descriptor != null) {
            return descriptor;
        }
        if (!ToolProtocol.isSupportedPlatform()) {
            throw new UnsupportedOperationException("Unix domain sockets are not available on this platform");
        }
        Path socket = ToolProtocol.socket(projectRoot, tool);
        Path socketDirectory = socket.getParent();
        if (socketDirectory != null) {
            Files.createDirectories(socketDirectory);
        }
        Files.createDirectories(ToolProtocol.directory(projectRoot));
        if (isLive(socket)) {
            throw busy(socket);
        }
        Files.deleteIfExists(socket);
        ServerSocketChannel channel;
        try {
            channel = bindOnce(socket);
        } catch (IOException firstFailure) {
            if (isLive(socket)) {
                throw busy(socket);
            }
            // The file was not a live endpoint, so it is a stale socket and removing it is the fix.
            Files.deleteIfExists(socket);
            channel = bindOnce(socket);
        }
        restrict(socket);
        List<String> capabilities = new ArrayList<>();
        capabilities.add(ToolProtocol.TOOL_INFO);
        if (!methods.topics().isEmpty()) {
            capabilities.add(ToolProtocol.TOOL_SUBSCRIBE);
        }
        capabilities.addAll(methods.capabilities());
        ToolDescriptor started = ToolDescriptor.of(projectRoot, tool, title, ide, ideVersion, socket, capabilities,
                methods.topics());
        Path descriptorFile = ToolProtocol.descriptorFile(projectRoot, tool);
        try {
            started.write(descriptorFile);
        } catch (IOException cannotPublish) {
            closeQuietly(channel);
            Files.deleteIfExists(socket);
            throw cannotPublish;
        }
        this.server = channel;
        this.descriptor = started;
        this.running = true;
        this.lastError = "";
        ServerSocketChannel listening = channel;
        this.acceptor = daemon(() -> acceptLoop(listening), "jcodebuddy-tool-" + tool + "-");
        this.acceptor.start();
        return started;
    }

    /** Stops listening, closes every connection and removes the socket and the descriptor this endpoint wrote. */
    public synchronized void stop() {
        ToolDescriptor current = descriptor;
        descriptor = null;
        running = false;
        closeQuietly(server);
        server = null;
        for (SocketChannel client : clients) {
            closeQuietly(client);
        }
        clients.clear();
        clientPool.shutdownNow();
        Thread currentAcceptor = acceptor;
        acceptor = null;
        if (currentAcceptor != null && currentAcceptor != Thread.currentThread()) {
            try {
                currentAcceptor.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (current != null) {
            deleteQuietly(current.socketPath());
            deleteQuietly(ToolProtocol.descriptorFile(projectRoot, tool));
        }
    }

    /** Whether the endpoint is listening. */
    public boolean isRunning() {
        return running;
    }

    /** The published descriptor, or {@code null} before {@link #start()} and after {@link #stop()}. */
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    /** The last internal failure, for the tool's own log; empty when nothing has failed. */
    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        stop();
    }

    private void acceptLoop(ServerSocketChannel channel) {
        while (running) {
            try {
                SocketChannel client = channel.accept();
                if (client == null) {
                    continue;
                }
                if (clients.size() >= MAX_CONNECTIONS) {
                    closeQuietly(client);
                    continue;
                }
                clients.add(client);
                try {
                    clientPool.execute(() -> serve(client));
                } catch (RejectedExecutionException stopped) {
                    clients.remove(client);
                    closeQuietly(client);
                }
            } catch (ClosedChannelException stopped) {
                // stop() closed the server channel; AsynchronousCloseException is one of these.
                return;
            } catch (IOException failed) {
                if (running) {
                    lastError = "accept failed: " + failed.getMessage();
                    running = false;
                    closeQuietly(channel);
                }
                return;
            }
        }
    }

    private void serve(SocketChannel client) {
        try (client) {
            client.configureBlocking(true);
            Reader reader = new BufferedReader(Channels.newReader(client, StandardCharsets.UTF_8));
            Writer writer = new BufferedWriter(Channels.newWriter(client, StandardCharsets.UTF_8));
            String line;
            while (running && (line = Wire.readLine(reader)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (!handle(line, reader, writer, client)) {
                    return;
                }
            }
        } catch (IOException ended) {
            // A consumer closing its connection is how a session ends, not a failure of the tool.
        } finally {
            clients.remove(client);
        }
    }

    /** Handles one request line; {@code false} means this connection is finished (a subscription took it). */
    private boolean handle(String line, Reader reader, Writer writer, SocketChannel client) throws IOException {
        JsonNode request;
        try {
            request = Json.read(line);
        } catch (RuntimeException malformed) {
            write(writer, error(null, ToolException.PARSE_ERROR, "parse error"));
            return true;
        }
        if (request == null || !request.isObject()) {
            write(writer, error(null, ToolException.INVALID_REQUEST, "expected a JSON object"));
            return true;
        }
        JsonNode idNode = request.path("id");
        boolean notification = idNode.isMissingNode() || idNode.isNull();
        Object id = notification ? null : Json.MAPPER.convertValue(idNode, Object.class);
        JsonNode methodNode = request.path("method");
        if (!methodNode.isString() || methodNode.asString().isBlank()) {
            if (!notification) {
                write(writer, error(id, ToolException.INVALID_REQUEST, "missing method"));
            }
            return true;
        }
        String method = methodNode.asString();
        Map<String, Object> params;
        try {
            params = params(request.path("params"));
        } catch (ToolException invalid) {
            if (!notification) {
                write(writer, error(id, invalid.code(), invalid.getMessage()));
            }
            return true;
        }
        if (ToolProtocol.TOOL_SUBSCRIBE.equals(method)) {
            if (notification) {
                // A stream needs an answer to confirm it; a notification has nowhere to carry one.
                return true;
            }
            return subscribe(id, params, reader, writer, client);
        }
        try {
            Object result = ToolProtocol.TOOL_INFO.equals(method)
                    ? (descriptor == null ? Map.of() : descriptor.asMap())
                    : methods.call(method, params);
            if (!notification) {
                write(writer, success(id, result));
            }
        } catch (ToolException refused) {
            if (!notification) {
                write(writer, error(id, refused.code(), refused.getMessage()));
            }
        } catch (RuntimeException failed) {
            lastError = method + " failed: " + failed;
            if (!notification) {
                write(writer, error(id, ToolException.INTERNAL_ERROR,
                        failed.getClass().getSimpleName() + ": " + failed.getMessage()));
            }
        }
        return true;
    }

    /**
     * Turns the connection into a {@code tool.event} stream: confirm the topic, then forward the tool's events
     * until the consumer goes away or the queue overflows.
     *
     * <p>The events are queued between the tool's own threads and this connection's thread, so a tool that
     * writes from its build worker never blocks on a socket write. A consumer that falls more than
     * {@value #SUBSCRIBER_QUEUE} events behind is ended rather than silently skipped: the notification says
     * why, and a consumer that wants the missing lines asks the tool's log method for them by sequence.</p>
     */
    private boolean subscribe(Object id, Map<String, Object> params, Reader reader, Writer writer,
                              SocketChannel client) throws IOException {
        Object topicValue = params.get("topic");
        String topic = topicValue instanceof String text ? text : "";
        if (topic.isBlank()) {
            write(writer, error(id, ToolException.INVALID_PARAMS, "tool.subscribe needs a topic"));
            return true;
        }
        if (!methods.topics().contains(topic)) {
            write(writer, error(id, ToolException.INVALID_PARAMS,
                    "no topic '" + topic + "'; this tool streams " + methods.topics()));
            return true;
        }
        Long since = asLong(params.get("since"));
        BlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE);
        AtomicBoolean overflowed = new AtomicBoolean();
        AtomicBoolean peerGone = new AtomicBoolean();
        AutoCloseable subscription;
        try {
            subscription = methods.subscribe(topic, since, event -> {
                if (event != null && !queue.offer(event)) {
                    overflowed.set(true);
                }
            });
        } catch (ToolException refused) {
            write(writer, error(id, refused.code(), refused.getMessage()));
            return true;
        } catch (RuntimeException failed) {
            lastError = "subscribe " + topic + " failed: " + failed;
            write(writer, error(id, ToolException.INTERNAL_ERROR, failed.getClass().getSimpleName()));
            return true;
        }
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("topic", topic);
        accepted.put("since", since == null ? 0L : since);
        write(writer, success(id, accepted));
        // The stream no longer reads requests, so a consumer that goes away is noticed here: a line on a
        // subscribed connection is not part of the protocol, and EOF is what a closed consumer looks like.
        Thread watcher = daemon(() -> {
            try {
                while (reader.read() != -1) {
                    // A consumer that sends on a stream is ignored; only the close matters.
                }
            } catch (IOException closed) {
                // The consumer closed the connection.
            } finally {
                peerGone.set(true);
            }
        }, "jcodebuddy-tool-stream-watch-");
        watcher.setDaemon(true);
        watcher.start();
        try {
            while (running && !peerGone.get()) {
                Map<String, Object> event = queue.poll(1, TimeUnit.SECONDS);
                if (overflowed.getAndSet(false)) {
                    write(writer, notification(ToolProtocol.TOOL_EVENT_ERROR,
                            Map.of("topic", topic, "reason", "overflow")));
                    return false;
                }
                if (event == null) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("topic", topic);
                payload.put("data", event);
                write(writer, notification(ToolProtocol.TOOL_EVENT, payload));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException closed) {
            // The consumer went away; the finally stops the tool's side of the stream.
        } finally {
            closeQuietly(subscription);
            // Wakes the watcher, if it is the one still blocked on the socket.
            closeQuietly(client);
        }
        return false;
    }

    private static void write(Writer writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private static String notification(String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        return Json.write(message);
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Map<String, Object> params(JsonNode node) throws ToolException {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw ToolException.invalidParams("params must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> converted = Json.MAPPER.convertValue(node, Map.class);
        return converted == null ? Map.of() : converted;
    }

    private static String success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return Json.write(response);
    }

    private static String error(Object id, int code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message == null ? "" : message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", detail);
        return Json.write(response);
    }

    private static ServerSocketChannel bindOnce(Path socket) throws IOException {
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.bind(UnixDomainSocketAddress.of(socket));
            return channel;
        } catch (IOException failed) {
            closeQuietly(channel);
            throw failed;
        }
    }

    private static BusyException busy(Path socket) {
        return new BusyException(socket,
                "another live tool endpoint already answers on " + socket
                        + "; two endpoints for one project would give a consumer no rule for choosing");
    }

    /**
     * Whether a live endpoint answers on {@code socket}.
     *
     * <p>A missing file is dead. A file with nothing listening fails to connect at once, which is dead. A peer
     * that connects but does not answer within the budget is treated as live: killing its socket file would
     * break every consumer that already discovered it, and refusing to start is the recoverable mistake.</p>
     */
    private static boolean isLive(Path socket) {
        if (!Files.exists(socket)) {
            return false;
        }
        AtomicBoolean live = new AtomicBoolean();
        Thread probe = daemon(() -> {
            try (ToolClient client = ToolClient.connectToSocket(socket)) {
                client.info();
                live.set(true);
            } catch (Exception notLive) {
                // A stale file or a foreign listener: neither is this tool's live endpoint.
            }
        }, "jcodebuddy-tool-probe-");
        probe.start();
        try {
            probe.join(PROBE_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
        return live.get() || probe.isAlive();
    }

    private static void restrict(Path socket) {
        try {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows has no POSIX mode bits; the socket is still a file only this user can reach.
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A file that cannot be removed is reported by the next start's busy/stale decision.
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing is best effort; the connection is already unusable.
        }
    }

    private static Thread daemon(Runnable task, String prefix) {
        Thread thread = new Thread(task, prefix + THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
