package hr.hrg.jcodebuddy.ipc;

import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A consumer of one live tool: connect to the socket its descriptor names and call methods.
 *
 * <p>One client is one connection and answers one request at a time — the socket protocol is request/response
 * per line, so a client that wants concurrency opens a second client. A host proxies a page's request to
 * {@link #call} and does not need the tool's classes: the request and the result are plain JSON values.</p>
 *
 * <p>There is no retry and no reconnect here. A dropped connection is reported, and rediscovering the
 * descriptor is the caller's decision; a client that reconnects behind the caller's back would hide the one
 * fact a host has to show ("this tool is not running").</p>
 */
public final class ToolClient implements AutoCloseable {

    private final SocketChannel channel;
    private final BufferedReader in;
    private final Writer out;
    private long nextId = 1;

    private ToolClient(SocketChannel channel) {
        this.channel = channel;
        this.in = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8));
    }

    /** Connects to the socket a descriptor names. */
    public static ToolClient connect(ToolDescriptor descriptor) throws IOException {
        if (descriptor == null) {
            throw new IllegalArgumentException("a descriptor is required");
        }
        return connectToSocket(descriptor.socketPath());
    }

    /** Connects by reading a descriptor file; this is the discovery step of a host or a proxy. */
    public static ToolClient connectToDescriptor(Path descriptorFile) throws IOException {
        return connect(ToolDescriptor.read(descriptorFile));
    }

    /** Connects to a socket path; a caller that has the descriptor should use {@link #connect(ToolDescriptor)}. */
    public static ToolClient connectToSocket(Path socket) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return new ToolClient(channel);
        } catch (IOException | RuntimeException failed) {
            closeQuietly(channel);
            throw failed;
        }
    }

    /** The tool's descriptor, as {@code tool.info} answers it. */
    public ToolDescriptor info() throws IOException, ToolException {
        Object result = call("tool.info", Map.of());
        if (result == null) {
            throw new IOException("tool.info returned no result");
        }
        try {
            return Json.MAPPER.convertValue(result, ToolDescriptor.class);
        } catch (RuntimeException malformed) {
            throw new IOException("tool.info returned something that is not a descriptor: "
                    + malformed.getMessage(), malformed);
        }
    }

    /**
     * Calls one method and returns its result as JSON values: maps, lists, strings, numbers, booleans or
     * {@code null}.
     *
     * @throws ToolException when the tool refuses the call; {@link ToolException#code()} is the JSON-RPC code
     */
    public synchronized Object call(String method, Map<String, Object> params) throws IOException, ToolException {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("a method name is required");
        }
        long id = nextId++;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null && !params.isEmpty()) {
            request.put("params", params);
        }
        write(request);
        return result(id);
    }

    /**
     * Opens an event stream on this connection.
     *
     * <p>The client is consumed: after this call the connection carries {@code tool.event} notifications and
     * nothing else. Use a separate client for calls, which is what a host does per SSE connection.</p>
     *
     * @param topic a topic from the tool's descriptor
     * @param since the last sequence the caller already has, or {@code null} to start at the tool's newest
     */
    public synchronized ToolSubscription subscribe(String topic, Long since) throws IOException, ToolException {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("a topic is required");
        }
        long id = nextId++;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("topic", topic);
        if (since != null) {
            params.put("since", since);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", ToolProtocol.TOOL_SUBSCRIBE);
        request.put("params", params);
        write(request);
        Object confirmation = result(id);
        if (!(confirmation instanceof Map<?, ?>)) {
            throw new IOException("the tool confirmed the stream without saying what it streams");
        }
        return new ToolSubscription(channel, in);
    }

    /** The method names the connected tool declares, from its {@code tool.info} descriptor. */
    public List<String> capabilities() throws IOException, ToolException {
        return info().capabilities();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private void write(Map<String, Object> request) throws IOException {
        out.write(Json.write(request));
        out.write('\n');
        out.flush();
    }

    /** Reads the response to request {@code expectedId}, refusing anything that is not that answer. */
    private Object result(long expectedId) throws IOException, ToolException {
        String line = Wire.readLine(in);
        if (line == null) {
            throw new EOFException("the tool endpoint closed the connection");
        }
        JsonNode response;
        try {
            response = Json.read(line);
        } catch (RuntimeException malformed) {
            throw new IOException("the tool endpoint sent malformed JSON", malformed);
        }
        JsonNode idNode = response.path("id");
        if (idNode.isNumber() && idNode.asLong() != expectedId) {
            throw new IOException("the tool answered request " + idNode.asLong()
                    + " while request " + expectedId + " was pending");
        }
        JsonNode error = response.path("error");
        if (error.isObject()) {
            int code = error.path("code").isNumber() ? error.path("code").asInt()
                    : ToolException.INTERNAL_ERROR;
            String message = error.path("message").isString() ? error.path("message").asString() : "";
            throw new ToolException(code, message);
        }
        return Json.MAPPER.convertValue(response.path("result"), Object.class);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The connect already failed; closing cannot add information.
        }
    }
}
