package hr.hrg.jcodebuddy.ipc;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What one live tool answers.
 *
 * <p>A tool implements one method per operation it publishes and lists them in {@link #capabilities()}, which
 * lands in the descriptor so a host can show what a tool offers without calling it. The API is deliberately
 * plain: {@code params} is a JSON object decoded into maps, lists and scalars, and a result is any value the
 * JSON codec can write. A tool never depends on this library's JSON types, and a proxy never depends on the
 * tool's classes.</p>
 *
 * <p>A tool that produces events declares {@link #topics()} and implements {@link #subscribe}: the transport
 * turns the {@code tool.subscribe} request into a stream of {@code tool.event} notifications on that
 * connection, and a host passes them on (over SSE, for the webview). A tool that has no events implements
 * neither and simply does not offer a stream.</p>
 */
@FunctionalInterface
public interface ToolMethods {

    /**
     * Answers one request.
     *
     * @param method the method name from the request, never blank
     * @param params the request's {@code params} object, decoded; empty when the request carried none
     * @return a JSON-serializable result, or {@code null} for a JSON {@code null}
     * @throws ToolException to answer with a JSON-RPC error; the code decides what the caller sees
     */
    Object call(String method, Map<String, Object> params) throws ToolException;

    /** The method names this tool implements, published in its descriptor and in {@code tool.info}. */
    default Collection<String> capabilities() {
        return List.of();
    }

    /** The event topics this tool streams, published in its descriptor. Empty means "no stream". */
    default Collection<String> topics() {
        return List.of();
    }

    /**
     * Opens one stream: replays the topic's retained backlog after {@code since}, then every new event.
     *
     * <p>{@code sink} may be called from any thread and must not block; the transport queues the events and
     * writes them on the connection's own thread. The returned handle stops the stream and is closed by the
     * transport when the connection ends, the browser disconnects or the tool stops.</p>
     *
     * @param topic a name from {@link #topics()}
     * @param since the last sequence the consumer already has, or {@code null} for "from now"
     * @param sink  receives each event's data as a JSON-serializable value
     */
    default AutoCloseable subscribe(String topic, Long since, Consumer<Map<String, Object>> sink)
            throws ToolException {
        throw ToolException.methodNotFound(ToolProtocol.TOOL_SUBSCRIBE);
    }
}
