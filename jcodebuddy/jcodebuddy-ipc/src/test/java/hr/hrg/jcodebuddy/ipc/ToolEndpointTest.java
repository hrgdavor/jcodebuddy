package hr.hrg.jcodebuddy.ipc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The endpoint contract: one socket and one descriptor per tool, JSON-RPC over the socket, and a second
 * endpoint that refuses to steal a live one's socket.
 */
class ToolEndpointTest {

    @TempDir
    Path project;

    /** The stream's retained history and its live subscribers: a tiny stand-in for a tool's own log. */
    private final List<Map<String, Object>> history = new CopyOnWriteArrayList<>();
    private final List<Consumer<Map<String, Object>>> subscribers = new CopyOnWriteArrayList<>();

    private ToolMethods methods() {
        return new ToolMethods() {
            @Override
            public Object call(String method, Map<String, Object> params) throws ToolException {
                return switch (method) {
                    case "echo" -> params;
                    case "fail" -> throw new ToolException(ToolException.INVALID_PARAMS, "nothing to say");
                    default -> throw ToolException.methodNotFound(method);
                };
            }

            @Override
            public List<String> capabilities() {
                return List.of("echo", "fail");
            }

            @Override
            public List<String> topics() {
                return List.of("log");
            }

            @Override
            public AutoCloseable subscribe(String topic, Long since, Consumer<Map<String, Object>> sink)
                    throws ToolException {
                if (!"log".equals(topic)) {
                    throw ToolException.invalidParams("no topic '" + topic + "'");
                }
                if (since != null) {
                    for (Map<String, Object> event : history) {
                        if (((Number) event.get("sequence")).longValue() > since) {
                            sink.accept(event);
                        }
                    }
                }
                subscribers.add(sink);
                return () -> subscribers.remove(sink);
            }
        };
    }

    /** Pushes one log line to every subscriber and keeps it for a later replay. */
    private void push(String text) {
        Map<String, Object> event = Map.of("sequence", (long) history.size() + 1, "text", text);
        history.add(event);
        for (Consumer<Map<String, Object>> subscriber : subscribers) {
            subscriber.accept(event);
        }
    }

    private ToolEndpoint endpoint(String tool) {
        return new ToolEndpoint(project, tool, "Test Tool", "JUnit", "1.0", methods());
    }

    @Test
    void startPublishesTheSocketAndTheDescriptor() throws Exception {
        try (ToolEndpoint endpoint = endpoint("test-tool")) {
            ToolDescriptor started = endpoint.start();
            Path descriptorFile = ToolProtocol.descriptorFile(project, "test-tool");

            assertTrue(Files.isRegularFile(descriptorFile), "the descriptor must be on disk");
            assertTrue(Files.exists(started.socketPath()), "the socket must exist");
            assertEquals(ToolProtocol.VERSION, started.protocol());
            assertEquals("test-tool", started.tool());
            assertEquals(project.toAbsolutePath().normalize(), started.projectPath());
            assertEquals(ProcessHandle.current().pid(), started.pid());
            assertEquals(List.of(ToolProtocol.TOOL_INFO, ToolProtocol.TOOL_SUBSCRIBE, "echo", "fail"),
                    started.capabilities());
            assertEquals(List.of("log"), started.topics(), "the descriptor publishes what the tool streams");

            try (ToolClient client = ToolClient.connectToDescriptor(descriptorFile)) {
                assertEquals(started, client.info(), "tool.info must return the published descriptor");
            }
        }
    }

    @Test
    void callReturnsTheMethodResult() throws Exception {
        try (ToolEndpoint endpoint = endpoint("echo-tool")) {
            endpoint.start();
            try (ToolClient client = ToolClient.connectToDescriptor(
                    ToolProtocol.descriptorFile(project, "echo-tool"))) {
                Object result = client.call("echo", Map.of("value", 42, "text", "hello"));
                assertTrue(result instanceof Map<?, ?>, "the echo result is the params object");
                Map<?, ?> echoed = (Map<?, ?>) result;
                assertEquals(42, ((Number) echoed.get("value")).intValue());
                assertEquals("hello", echoed.get("text"));
            }
        }
    }

    @Test
    void anUnknownMethodIsRefusedWithTheJsonRpcCode() throws Exception {
        try (ToolEndpoint endpoint = endpoint("unknown-method")) {
            endpoint.start();
            try (ToolClient client = ToolClient.connectToDescriptor(
                    ToolProtocol.descriptorFile(project, "unknown-method"))) {
                ToolException refused = assertThrows(ToolException.class,
                        () -> client.call("no.such.method", Map.of()));
                assertEquals(ToolException.METHOD_NOT_FOUND, refused.code());
            }
        }
    }

    @Test
    void aToolExceptionKeepsItsCode() throws Exception {
        try (ToolEndpoint endpoint = endpoint("failing-tool")) {
            endpoint.start();
            try (ToolClient client = ToolClient.connectToDescriptor(
                    ToolProtocol.descriptorFile(project, "failing-tool"))) {
                ToolException refused = assertThrows(ToolException.class, () -> client.call("fail", Map.of()));
                assertEquals(ToolException.INVALID_PARAMS, refused.code());
            }
        }
    }

    @Test
    void aSecondEndpointForTheSameProjectIsRefused() throws Exception {
        try (ToolEndpoint first = endpoint("one-per-project");
             ToolEndpoint second = endpoint("one-per-project")) {
            first.start();
            ToolEndpoint.BusyException busy = assertThrows(ToolEndpoint.BusyException.class, second::start);
            assertNotNull(busy.socket());
            try (ToolClient client = ToolClient.connectToDescriptor(
                    ToolProtocol.descriptorFile(project, "one-per-project"))) {
                assertEquals("one-per-project", client.info().tool());
            }
        }
    }

    @Test
    void stopRemovesTheSocketAndTheDescriptor() throws Exception {
        ToolEndpoint endpoint = endpoint("stoppable");
        ToolDescriptor started = endpoint.start();
        Path socket = started.socketPath();
        Path descriptor = ToolProtocol.descriptorFile(project, "stoppable");

        endpoint.stop();

        assertFalse(Files.exists(socket), "a stopped endpoint leaves no socket behind");
        assertFalse(Files.exists(descriptor), "a stopped endpoint leaves no descriptor behind");
    }

    @Test
    void aStaleSocketFileIsReplaced() throws Exception {
        Path socket = ToolProtocol.socket(project, "stale");
        Files.createDirectories(socket.getParent());
        Files.writeString(socket, "not a socket", StandardOpenOption.CREATE);

        try (ToolEndpoint endpoint = endpoint("stale")) {
            ToolDescriptor started = endpoint.start();
            assertTrue(Files.exists(started.socketPath()));
            try (ToolClient client = ToolClient.connect(started)) {
                assertEquals("stale", client.info().tool());
            }
        }
    }

    @Test
    void aSocketPathTooLongForThePlatformMovesToTheTempDirectory() {
        Path deep = project.resolve("a".repeat(24)).resolve("b".repeat(24)).resolve("c".repeat(24));
        Path socket = ToolProtocol.socket(deep, "deep-tool");

        assertFalse(socket.startsWith(deep), "the conventional path would not fit the sockaddr_un limit");
        assertTrue(socket.getFileName().toString().endsWith(".sock"));
        assertTrue(socket.toString().length() <= 200, "the fallback stays short: " + socket);
    }

    @Test
    void theDescriptorListFindsEveryReadableTool() throws Exception {
        try (ToolEndpoint endpoint = endpoint("listed")) {
            endpoint.start();
            ToolDescriptor other = ToolDescriptor.of(project, "second-tool", "Second", "JUnit", "1.0",
                    ToolProtocol.conventionalSocket(project, "second-tool"), List.of("run"));
            other.write(ToolProtocol.descriptorFile(project, "second-tool"));
            Files.writeString(ToolProtocol.directory(project).resolve("broken.json"), "not json");

            List<ToolDescriptor> found = ToolDescriptor.list(project);

            assertEquals(List.of("listed", "second-tool"), found.stream().map(ToolDescriptor::tool).toList());
        }
    }

    @Test
    void aSubscriptionReceivesPushedEvents() throws Exception {
        try (ToolEndpoint endpoint = endpoint("pushing-tool")) {
            endpoint.start();
            try (ToolClient client = ToolClient.connectToDescriptor(
                    ToolProtocol.descriptorFile(project, "pushing-tool"));
                 ToolSubscription subscription = client.subscribe("log", null)) {
                push("first line");

                Map<String, Object> event = subscription.nextEvent();
                assertEquals("log", event.get("topic"), "the notification names its topic");
                assertTrue(event.get("data") instanceof Map<?, ?>, "the event data is the JSON value");
                Map<?, ?> data = (Map<?, ?>) event.get("data");
                assertEquals("first line", data.get("text"));
                assertEquals(1, ((Number) data.get("sequence")).intValue());
            }
        }
    }

    @Test
    void aSubscriptionReplaysTheBacklogAfterSince() throws Exception {
        push("before one");
        push("before two");
        try (ToolEndpoint endpoint = endpoint("replaying-tool")) {
            endpoint.start();
            try (ToolClient client = ToolClient.connectToDescriptor(
                    ToolProtocol.descriptorFile(project, "replaying-tool"));
                 ToolSubscription subscription = client.subscribe("log", 1L)) {
                // Since sequence 1, the retained history holds only "before two".
                Map<String, Object> event = subscription.nextEvent();
                Map<?, ?> data = (Map<?, ?>) event.get("data");
                assertEquals("before two", data.get("text"));
                assertEquals(2, ((Number) data.get("sequence")).intValue());
            }
        }
    }

    @Test
    void anUnknownTopicIsRefused() throws Exception {
        try (ToolEndpoint endpoint = endpoint("topic-refusal")) {
            endpoint.start();
            try (ToolClient client = ToolClient.connectToDescriptor(
                    ToolProtocol.descriptorFile(project, "topic-refusal"))) {
                ToolException refused = assertThrows(ToolException.class, () -> client.subscribe("nope", null));
                assertEquals(ToolException.INVALID_PARAMS, refused.code());
            }
        }
    }

    @Test
    void rawFramesFollowTheJsonRpcEnvelope() throws Exception {
        try (ToolEndpoint endpoint = endpoint("raw-frames")) {
            endpoint.start();
            Path socket = ToolDescriptor.read(ToolProtocol.descriptorFile(project, "raw-frames")).socketPath();

            // A notification (no id) gets no response: the one line that comes back answers the request after it.
            String responses = exchange(socket, 1,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{}}",
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"echo\",\"params\":{\"a\":1}}");
            assertEquals(1L, responses.lines().count(), "a notification must not be answered");
            assertTrue(responses.contains("\"id\":7"), responses);

            // Params that are not an object are refused by the protocol, not by the tool.
            assertEquals(ToolException.INVALID_PARAMS, errorCode(exchange(socket, 1,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":[1,2]}")));
            // A line that is not JSON is a parse error.
            assertEquals(ToolException.PARSE_ERROR, errorCode(exchange(socket, 1, "not json")));
        }
    }

    private static int errorCode(String response) {
        return Json.read(response).path("error").path("code").asInt();
    }

    /** Sends every request on one connection and returns the first {@code responseCount} response lines. */
    private static String exchange(Path socket, int responseCount, String... requests) throws IOException {
        StringBuilder responses = new StringBuilder();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            BufferedReader in = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8));
            for (String request : requests) {
                out.write(request);
                out.write('\n');
            }
            out.flush();
            for (int i = 0; i < responseCount; i++) {
                String line = in.readLine();
                if (line == null) {
                    break;
                }
                responses.append(line).append('\n');
            }
        }
        return responses.toString();
    }
}
