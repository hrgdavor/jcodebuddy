package hr.hrg.jcodebuddy.ipc;

import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Map;

/**
 * One open event stream from a subscribed tool.
 *
 * <p>A subscription owns its connection: {@link ToolClient#subscribe} sends the request, reads the
 * confirmation, and hands the connection over. The consumer then blocks in {@link #nextEvent()} and forwards
 * what it gets — a host turns each event into an SSE frame, a CLI prints it — until the tool ends the stream,
 * the queue overflows, or the consumer closes it.</p>
 *
 * <p>The stream is not a second transport: the events are JSON-RPC notifications
 * ({@link ToolProtocol#TOOL_EVENT}) on the same socket, framed the same way as a response. A tool that ends a
 * stream it cannot keep up with sends {@link ToolProtocol#TOOL_EVENT_ERROR} first, so a consumer learns why
 * and can re-read the missing lines from the tool's own method (the compiler's log answers by sequence).</p>
 */
public final class ToolSubscription implements AutoCloseable {

    private final SocketChannel channel;
    private final BufferedReader in;

    ToolSubscription(SocketChannel channel, BufferedReader in) {
        this.channel = channel;
        this.in = in;
    }

    /**
     * Blocks for the next event.
     *
     * @return the notification's params: {@code topic} and {@code data}
     * @throws IOException when the tool closed the stream, ended it on overflow, or the socket failed
     */
    public Map<String, Object> nextEvent() throws IOException {
        String line = Wire.readLine(in);
        if (line == null) {
            throw new EOFException("the tool closed the event stream");
        }
        JsonNode message = Json.read(line);
        String method = message.path("method").isString() ? message.path("method").asString() : "";
        if (ToolProtocol.TOOL_EVENT_ERROR.equals(method)) {
            JsonNode reasonNode = message.path("params").path("reason");
            String reason = reasonNode.isString() ? reasonNode.asString() : "unknown";
            throw new IOException("the tool ended the event stream: " + reason);
        }
        if (!ToolProtocol.TOOL_EVENT.equals(method)) {
            throw new IOException("the tool sent '" + method + "' on an event stream");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = Json.MAPPER.convertValue(message.path("params"), Map.class);
        return params == null ? Map.of() : params;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
