package hr.hrg.jcodebuddy.ipc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host-facing half of DEC-053: what a webview server needs in order to expose a tool endpoint over HTTP,
 * without owning a second copy of the discovery, the status mapping or the SSE frame.
 *
 * <p>It is transport-neutral on purpose. {@link #list} and {@link #call} return a status and a JSON body for
 * the host to send; {@link #start} opens a stream whose {@link Stream#pump()} writes complete SSE frames into a
 * {@link Frames} sink. The two Java hosts — {@code webviewd} and the JetBrains bridge — each contribute only
 * their authentication rule and their {@code HttpExchange} plumbing, and a third host (the Bun standalone
 * server) follows the same wire, with its own port of this file because it is another language, not another
 * contract.</p>
 *
 * <p>Statuses, fixed here so the hosts cannot disagree: an unknown tool is {@code 404}, a malformed or refused
 * call is {@code 400} (the tool's JSON-RPC code included), an unreachable socket is {@code 502}, and a
 * successful call is {@code 200} with {@code {tool, result}}.</p>
 */
public final class ToolProxy {

    private static final ObjectMapper MAPPER = Json.MAPPER;

    private ToolProxy() {
    }

    /** One answer a host sends as JSON. */
    public record Answer(int status, String body) {
    }

    /** Where a host writes one complete SSE frame; the proxy owns the frame syntax. */
    @FunctionalInterface
    public interface Frames {
        void write(String frame) throws IOException;
    }

    /** An open event stream; {@link #pump()} moves one event, and {@link #close()} ends both ends. */
    public interface Stream extends AutoCloseable {
        void pump() throws IOException;

        @Override
        void close();
    }

    /** The {@code GET /api/tools} answer: the protocol and every readable descriptor. */
    public static Answer list(Path projectRoot) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDescriptor descriptor : ToolDescriptor.list(projectRoot)) {
            tools.add(descriptor.asMap());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocol", ToolProtocol.VERSION);
        body.put("tools", tools);
        return new Answer(200, Json.write(body));
    }

    /**
     * The {@code POST /api/tools/<tool>/call} answer: one JSON-RPC call forwarded to the tool's socket.
     *
     * @param requestBody the HTTP body, {@code {method, params}}
     */
    public static Answer call(Path projectRoot, String tool, String requestBody) {
        ToolDescriptor descriptor = descriptor(projectRoot, tool);
        if (descriptor == null) {
            return new Answer(404, error("unknown-tool",
                    "no tool descriptor for '" + tool + "' in .jcodebuddy/ipc/"));
        }
        JsonNode request;
        try {
            request = MAPPER.readTree(requestBody == null ? "" : requestBody);
        } catch (RuntimeException malformed) {
            return new Answer(400, error("bad-request",
                    "the body must be a JSON object {method, params}: " + malformed.getMessage()));
        }
        if (request == null || !request.isObject()) {
            return new Answer(400, error("bad-request", "the body must be a JSON object {method, params}"));
        }
        JsonNode methodNode = request.path("method");
        if (!methodNode.isString() || methodNode.asString().isBlank()) {
            return new Answer(400, error("bad-request", "the body must name a method"));
        }
        Map<String, Object> params;
        JsonNode paramsNode = request.path("params");
        if (paramsNode.isMissingNode() || paramsNode.isNull()) {
            params = Map.of();
        } else if (!paramsNode.isObject()) {
            return new Answer(400, error("bad-request", "params must be a JSON object"));
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = MAPPER.convertValue(paramsNode, Map.class);
            params = converted == null ? Map.of() : converted;
        }
        try (ToolClient client = ToolClient.connect(descriptor)) {
            Object result = client.call(methodNode.asString(), params);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tool", tool);
            body.put("result", result);
            return new Answer(200, Json.write(body));
        } catch (ToolException refused) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "tool-refused");
            body.put("code", refused.code());
            body.put("message", refused.getMessage() == null ? "" : refused.getMessage());
            return new Answer(400, Json.write(body));
        } catch (IOException unreachable) {
            return new Answer(502, error("tool-unreachable",
                    unreachable.getMessage() == null ? "" : unreachable.getMessage()));
        }
    }

    /**
     * Opens an event stream for {@code GET /api/tools/<tool>/events}.
     *
     * <p>A refusal is returned as an {@link Answer} (the host sends it), otherwise the stream is open and the
     * host pumps it. {@code topic} may be blank to take the tool's first topic; {@code since} may be null for
     * "from the tool's newest", and a host passes {@code Last-Event-ID} in place of it when reconnecting.</p>
     */
    public static Start start(Path projectRoot, String tool, String topic, Long since, Frames frames) {
        ToolDescriptor descriptor = descriptor(projectRoot, tool);
        if (descriptor == null) {
            return Start.refused(new Answer(404, error("unknown-tool",
                    "no tool descriptor for '" + tool + "' in .jcodebuddy/ipc/")));
        }
        String resolvedTopic = topic;
        if (resolvedTopic == null || resolvedTopic.isBlank()) {
            resolvedTopic = descriptor.topics().isEmpty() ? "" : descriptor.topics().get(0);
        }
        if (!descriptor.topics().contains(resolvedTopic)) {
            return Start.refused(new Answer(400, error("unknown-topic",
                    "no topic '" + resolvedTopic + "'; this tool streams " + descriptor.topics())));
        }
        ToolClient client;
        try {
            client = ToolClient.connect(descriptor);
        } catch (IOException unreachable) {
            return Start.refused(new Answer(502, error("tool-unreachable",
                    unreachable.getMessage() == null ? "" : unreachable.getMessage())));
        }
        ToolSubscription subscription;
        try {
            subscription = client.subscribe(resolvedTopic, since);
        } catch (ToolException refused) {
            closeQuietly(client);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "tool-refused");
            body.put("code", refused.code());
            body.put("message", refused.getMessage() == null ? "" : refused.getMessage());
            return Start.refused(new Answer(400, Json.write(body)));
        } catch (IOException ended) {
            closeQuietly(client);
            return Start.refused(new Answer(502, error("tool-unreachable",
                    ended.getMessage() == null ? "" : ended.getMessage())));
        }
        return Start.streaming(new OpenStream(tool, resolvedTopic, client, subscription, frames));
    }

    /** Either a refusal to send, or the open stream to pump. */
    public record Start(Answer refusal, Stream stream) {

        static Start refused(Answer answer) {
            return new Start(answer, null);
        }

        static Start streaming(Stream stream) {
            return new Start(null, stream);
        }

        /** Whether the host should send {@link #refusal()} instead of starting an SSE response. */
        public boolean refused() {
            return refusal != null;
        }
    }

    private static final class OpenStream implements Stream {

        private final String tool;
        private final String topic;
        private final ToolClient client;
        private final ToolSubscription subscription;
        private final Frames frames;

        private OpenStream(String tool, String topic, ToolClient client, ToolSubscription subscription,
                           Frames frames) {
            this.tool = tool;
            this.topic = topic;
            this.client = client;
            this.subscription = subscription;
            this.frames = frames;
        }

        @Override
        public void pump() throws IOException {
            Map<String, Object> event = subscription.nextEvent();
            Object data = event.get("data");
            long sequence = sequenceOf(data);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("topic", topic);
            payload.put("data", data);
            StringBuilder frame = new StringBuilder("event: tool-event\n");
            if (sequence >= 0) {
                frame.append("id: ").append(sequence).append('\n');
            }
            frame.append("data: ").append(Json.write(payload)).append("\n\n");
            frames.write(frame.toString());
        }

        @Override
        public void close() {
            closeQuietly(subscription);
            closeQuietly(client);
        }

        @Override
        public String toString() {
            return "tool stream " + tool + "/" + topic;
        }
    }

    /** The SSE {@code id}: the event data's sequence when it has one, so a reconnect resumes exactly. */
    private static long sequenceOf(Object data) {
        if (data instanceof Map<?, ?> map && map.get("sequence") instanceof Number number) {
            return number.longValue();
        }
        return -1;
    }

    private static ToolDescriptor descriptor(Path projectRoot, String tool) {
        if (tool == null || tool.isBlank()) {
            return null;
        }
        return ToolDescriptor.list(projectRoot).stream()
                .filter(candidate -> candidate.tool().equals(tool))
                .findFirst()
                .orElse(null);
    }

    private static String error(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message == null ? "" : message);
        return Json.write(body);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The connection is already unusable; closing cannot add information.
        }
    }
}
