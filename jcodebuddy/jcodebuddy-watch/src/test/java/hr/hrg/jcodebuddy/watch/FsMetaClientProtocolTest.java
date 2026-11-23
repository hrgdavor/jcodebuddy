package hr.hrg.jcodebuddy.watch;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol-level half of the daemon cutover, without a daemon binary: a fake server on a real Unix socket speaks
 * {@code FORMAT.socket.md}, and the Java client is asserted against it.
 *
 * <p>Two things are pinned here that the Zig integration test cannot reach cheaply: the change bits become the right
 * {@link FsMetaClient.Kind}, and the two replay refusals ({@code batch-too-old}, {@code batch-gap}) arrive as a
 * {@link FsMetaClient.ProtocolException} carrying the numeric code — the branch point a consumer must have, because
 * the protocol requires a full read rather than a silently skipped generation.</p>
 *
 * <p>Unix domain sockets are a JDK and OS feature; where the platform refuses them the test skips rather than fails,
 * exactly as the daemon integration test skips without {@code FSMETA_BIN}.</p>
 */
class FsMetaClientProtocolTest {

    @Test
    void protocolRefusalCarriesTheNewestBatchItNames() {
        FsMetaClient.ProtocolException refusal = new FsMetaClient.ProtocolException(
                FsMetaClient.ERROR_PROTOCOL,
                "protocol: from_batch 461 is newer than the newest batch 454");
        assertEquals(454, refusal.newestBatch(),
                "the watcher resumes at the archived batch instead of reconnecting at the invalid generation");
        assertEquals(-1, new FsMetaClient.ProtocolException(FsMetaClient.ERROR_PROTOCOL, "protocol: bad hello")
                .newestBatch());
    }

    @Test
    void batchTooOldIsReportedByItsCode() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(hello(7), error(FsMetaClient.ERROR_BATCH_TOO_OLD, "batch-too-old: no batch 1"));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), 0)) {
            FsMetaClient.ProtocolException refusal = assertThrows(FsMetaClient.ProtocolException.class,
                    client::nextBatch);
            assertEquals(FsMetaClient.ERROR_BATCH_TOO_OLD, refusal.code());
            assertTrue(refusal.needsFullRead(), "a replay that is too old means a full read, not a skip");
            assertTrue(refusal.getMessage().contains("batch-too-old"),
                    "the message keeps the daemon's own detail: " + refusal.getMessage());
        } finally {
            server.close();
        }
    }

    @Test
    void batchGapIsReportedByItsCode() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(hello(9), error(FsMetaClient.ERROR_BATCH_GAP, "batch-gap: 4 has no directory"));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), 3)) {
            FsMetaClient.ProtocolException refusal = assertThrows(FsMetaClient.ProtocolException.class,
                    client::nextBatch);
            assertEquals(FsMetaClient.ERROR_BATCH_GAP, refusal.code());
            assertTrue(refusal.needsFullRead(), "a missing generation is never silently skipped");
        } finally {
            server.close();
        }
    }

    @Test
    void aBatchIsDecodedWithItsChangeBits() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(
                hello(12),
                startBatch(13, 12),
                changeEntry("src/A.java", FsMetaClient.Kind.ADDED),
                changeEntry("src/B.java", FsMetaClient.Kind.CHANGED),
                changeEntry("src/C.java", FsMetaClient.Kind.DELETED),
                endBatch(13, 1, 1, 1));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), FsMetaClient.LATEST)) {
            assertEquals(12, client.serverGeneration(), "LATEST subscribes at the daemon's head");
            FsMetaClient.Batch batch = client.nextBatch();
            assertEquals(13, batch.number());
            assertEquals(12, batch.parent());
            assertEquals(List.of(
                            new FsMetaClient.Change("src/A.java", FsMetaClient.Kind.ADDED),
                            new FsMetaClient.Change("src/B.java", FsMetaClient.Kind.CHANGED),
                            new FsMetaClient.Change("src/C.java", FsMetaClient.Kind.DELETED)),
                    batch.changes());
        } finally {
            server.close();
        }
    }

    @Test
    void theObserverSeesStartEntriesAndEnd() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(
                hello(13),
                startBatch(14, 13),
                changeEntry("src/A.java", FsMetaClient.Kind.ADDED),
                changeEntry("src/B.java", FsMetaClient.Kind.CHANGED),
                endBatch(14, 1, 1, 0));
        List<String> events = new ArrayList<>();
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), FsMetaClient.LATEST)) {
            FsMetaClient.Batch batch = client.nextBatch(new FsMetaClient.BatchObserver() {
                @Override
                public void startBatch(long number, long parent) {
                    events.add("start:" + number + "/" + parent);
                }

                @Override
                public void change(FsMetaClient.Change change) {
                    events.add("change:" + change.name() + ":" + change.kind());
                }

                @Override
                public void endBatch(FsMetaClient.Batch done) {
                    events.add("end:" + done.number() + "/" + done.changes().size());
                }
            });
            assertEquals(List.of("start:14/13", "change:src/A.java:ADDED", "change:src/B.java:CHANGED",
                    "end:14/2"), events);
            assertEquals(14, batch.number());
        } finally {
            server.close();
        }
    }

    @Test
    void aCountMismatchDiscardsTheBatch() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(
                hello(1),
                startBatch(2, 1),
                changeEntry("src/A.java", FsMetaClient.Kind.CHANGED),
                endBatch(2, 0, 2, 0));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), FsMetaClient.LATEST)) {
            assertThrows(IOException.class, client::nextBatch,
                    "END_BATCH is the commit, so a count that disagrees with the entries is refused");
        } finally {
            server.close();
        }
    }

    // ── the extended SUBSCRIBE: the server-side subfolder filter ────────────────────────────────────────────────

    /**
     * The one filter this module sends is the protocol's own ({@code FORMAT.socket.md} § SUBSCRIBE filter
     * patterns): a consumer that wants one module's source root inside a project root must not filter the whole
     * monorepo stream client-side, and must not invent a second pattern grammar. The payload is asserted byte by
     * byte against the fake server so a layout drift fails here rather than as a silently unfiltered stream.
     */
    @Test
    void theExtendedSubscribeCarriesTheIncludeAndExcludePatterns() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(hello(3));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), 2,
                List.of("module/src/main/java/**"), List.of("**/.jcodebuddy/**"))) {
            assertEquals(3, client.serverGeneration());
            ByteBuffer payload = littleEndian(server.subscribePayload());
            assertEquals(2L, payload.getLong(), "the from_batch is the first field of both forms");
            assertEquals(1, payload.getShort() & 0xffff, "one include pattern");
            assertEquals("module/src/main/java/**", pattern(payload));
            assertEquals(1, payload.getShort() & 0xffff, "one exclude pattern");
            assertEquals("**/.jcodebuddy/**", pattern(payload));
            assertFalse(payload.hasRemaining(), "the extended form ends after the exclude patterns");
        } finally {
            server.close();
        }
    }

    @Test
    void anUnfilteredSubscribeKeepsTheOriginalEightByteForm() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(hello(7));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), 5)) {
            byte[] payload = server.subscribePayload();
            assertEquals(8, payload.length, "both empty is the pass-all form, not an extended header of zeroes");
            assertEquals(5L, littleEndian(payload).getLong());
        } finally {
            server.close();
        }
    }

    private static String pattern(ByteBuffer payload) {
        int length = payload.getShort() & 0xffff;
        byte[] bytes = new byte[length];
        payload.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteBuffer littleEndian(byte[] payload) {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    // ── the one-shot read: a sleeping root's wake scan ──────────────────────────────────────────────────────────

    /**
     * The correctness case the greeting cannot answer: a root that was asleep says {@code waking} and publishes
     * its wake scan after the greeting. A one-shot consumer must read that batch, or an edit made while the
     * daemon slept would be answered "nothing changed".
     */
    @Test
    void aWakingRootsScanIsReadBeforeTheReplaySettles() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(
                hello(5),
                state(FsMetaClient.STATE_WAKING, 1, 0, 5),
                startBatch(6, 5),
                changeEntry("src/A.java", FsMetaClient.Kind.CHANGED),
                endBatch(6, 0, 1, 0),
                state(FsMetaClient.STATE_WATCHING, 4, 0, 6));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), 5)) {
            FsMetaClient.Replay replay = client.replayUntilSettled(5);

            assertEquals(6, replay.head(), "the wake scan's generation is the head the read reaches");
            assertEquals(List.of(new FsMetaClient.Change("src/A.java", FsMetaClient.Kind.CHANGED)),
                    replay.changes(), "and its change is read, not missed at the greeted generation");
            assertEquals(1, replay.batches().size());
            assertEquals(FsMetaClient.STATE_WATCHING, client.lastState().code());
        } finally {
            server.close();
        }
    }

    @Test
    void anAwakeRootWithNothingNewReturnsAtItsSettledState() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(hello(5), state(FsMetaClient.STATE_WATCHING, 0, 0, 5));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), 5)) {
            FsMetaClient.Replay replay = client.replayUntilSettled(5);

            assertEquals(5, replay.head());
            assertTrue(replay.changes().isEmpty());
            assertTrue(replay.batches().isEmpty());
            assertTrue(client.lastState().settled());
            assertEquals(FsMetaClient.STATE_WATCHING, client.lastState().code());
            assertEquals(0, client.lastState().reason());
        } finally {
            server.close();
        }
    }

    @Test
    void theStateFieldsAreDecoded() throws Exception {
        FakeServer server = FakeServer.start();
        server.reply(hello(2), state(FsMetaClient.STATE_ASLEEP, 2, 300_000, 2));
        try (FsMetaClient client = FsMetaClient.connect(server.socket(), 2)) {
            client.replayUntilSettled(2);

            FsMetaClient.State state = client.lastState();
            assertEquals(FsMetaClient.STATE_ASLEEP, state.code());
            assertEquals(2, state.reason(), "idle timeout");
            assertEquals(300_000, state.idleTimeoutMs());
            assertEquals(2, state.headBatch());
            assertTrue(state.settled(), "nothing is scanning");
        } finally {
            server.close();
        }
    }

    private static byte[] state(int code, int reason, long idleTimeoutMs, long headBatch) {
        ByteBuffer payload = frame(14);
        payload.put((byte) code);
        payload.put((byte) reason);
        payload.putInt((int) idleTimeoutMs);
        payload.putLong(headBatch);
        return frame(0x09, payload.array());
    }

    // ── a fake daemon on a real Unix socket ──────────────────────────────────────────────────────────────────────

    /**
     * Accepts one connection, sends the greeting and then the prepared frames, and records the client's
     * {@code SUBSCRIBE} payload so a test can assert the filter the client actually sent.
     *
     * <p>The client greets as soon as it is connected, so the server's own greeting and frames can be written
     * first (they fit in the socket buffer); after that it reads the client's two frames — {@code HELLO}, then
     * {@code SUBSCRIBE} — and finally holds the connection open until the client closes it.</p>
     */
    private static final class FakeServer implements AutoCloseable {

        private final Path directory;
        private final ServerSocketChannel server;
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<byte[]> subscribePayload = new AtomicReference<>();

        private FakeServer(Path directory, ServerSocketChannel server) {
            this.directory = directory;
            this.server = server;
            this.thread = new Thread(this::serve, "fake-fsmeta-server");
            this.thread.setDaemon(true);
        }

        static FakeServer start() throws Exception {
            Path directory = Files.createTempDirectory(Path.of("target"), "fsm-");
            Path socket = directory.resolve("s.sock");
            ServerSocketChannel server;
            try {
                server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                server.bind(UnixDomainSocketAddress.of(socket));
            } catch (UnsupportedOperationException | IOException unavailable) {
                deleteRecursively(directory);
                Assumptions.assumeTrue(false, "Unix domain sockets are unavailable here: " + unavailable);
                throw unavailable;
            }
            FakeServer fake = new FakeServer(directory, server);
            fake.thread.start();
            return fake;
        }

        private volatile byte[][] frames = new byte[0][];

        void reply(byte[]... frames) {
            this.frames = frames;
        }

        Path socket() {
            return directory.resolve("s.sock");
        }

        /** The client's {@code SUBSCRIBE} payload, or a failure when it never sent one. */
        byte[] subscribePayload() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                byte[] payload = subscribePayload.get();
                if (payload != null) {
                    return payload;
                }
                if (failure.get() != null) {
                    throw new AssertionError("the fake server failed before reading SUBSCRIBE",
                            failure.get());
                }
                Thread.sleep(10);
            }
            throw new AssertionError("the client sent no SUBSCRIBE within 5 s");
        }

        private void serve() {
            try (SocketChannel channel = server.accept()) {
                for (byte[] frame : frames) {
                    ByteBuffer buffer = ByteBuffer.wrap(frame);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
                readClientFrames(channel);
                // Hold the connection open until the client closes it, so a refusal is read as a frame and not
                // as an EOF the test would misattribute. A Unix-domain SocketChannel has no socket() to
                // half-close.
                ByteBuffer sink = ByteBuffer.allocate(256);
                while (channel.read(sink) >= 0) {
                    sink.clear();
                }
            } catch (IOException | RuntimeException ignored) {
                failure.compareAndSet(null, ignored);
            }
        }

        /** The client's {@code HELLO} then {@code SUBSCRIBE}; the payload of the latter is kept for assertions. */
        private void readClientFrames(SocketChannel channel) throws IOException {
            for (int index = 0; index < 2; index++) {
                ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, header);
                int kind = header.getShort(0) & 0xffff;
                int length = header.getInt(4);
                byte[] payload = new byte[length];
                readFully(channel, ByteBuffer.wrap(payload));
                if (kind == 0x06) {
                    subscribePayload.set(payload);
                    return;
                }
            }
        }

        private static void readFully(SocketChannel channel, ByteBuffer target) throws IOException {
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) {
                    throw new IOException("the client closed before its SUBSCRIBE");
                }
            }
        }

        @Override
        public void close() throws Exception {
            try {
                server.close();
            } finally {
                deleteRecursively(directory);
            }
        }

        private static void deleteRecursively(Path root) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort: a leaked scratch directory must not mask an assertion.
                    }
                });
            } catch (IOException ignored) {
                // Same.
            }
        }
    }

    private static byte[] hello(long generation) {
        ByteBuffer payload = frame(24);
        payload.putShort((short) FsMetaClient.PROTOCOL);
        payload.putShort((short) 64);
        payload.put((byte) 0);
        payload.put((byte) 0); // server
        payload.put((byte) 0); // capabilities
        payload.put((byte) 0);
        payload.putLong(generation);
        payload.putLong(1 << 20);
        return frame(0x01, payload.array());
    }

    private static byte[] startBatch(long number, long parent) {
        ByteBuffer payload = frame(24);
        payload.putLong(number);
        payload.putLong(parent);
        payload.putLong(0);
        return frame(0x02, payload.array());
    }

    private static byte[] endBatch(long number, long added, long changed, long deleted) {
        ByteBuffer payload = frame(40);
        payload.putLong(number);
        payload.putLong(added);
        payload.putLong(changed);
        payload.putLong(deleted);
        payload.putLong(0);
        return frame(0x04, payload.array());
    }

    private static byte[] changeEntry(String name, FsMetaClient.Kind kind) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        int flag = switch (kind) {
            case ADDED -> 0x20;
            case CHANGED -> 0x40;
            case DELETED -> 0x80;
        };
        ByteBuffer payload = frame(64 + bytes.length);
        payload.putInt(40, bytes.length);
        payload.putInt(44, flag);
        payload.position(64);
        payload.put(bytes);
        return frame(0x03, payload.array());
    }

    private static byte[] error(int code, String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = frame(2 + text.length);
        payload.putShort((short) code);
        payload.put(text);
        return frame(0x05, payload.array());
    }

    private static byte[] frame(int kind, byte[] payload) {
        ByteBuffer frame = frame(8 + payload.length);
        frame.putShort((short) kind);
        frame.putShort((short) 0);
        frame.putInt(payload.length);
        frame.put(payload);
        return frame.array();
    }

    /** A zeroed little-endian buffer with a written position at the start, for building a payload. */
    private static ByteBuffer frame(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }
}
