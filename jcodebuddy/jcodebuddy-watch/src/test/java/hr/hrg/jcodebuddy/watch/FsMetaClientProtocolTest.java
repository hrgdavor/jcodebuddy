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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ── a fake daemon on a real Unix socket ──────────────────────────────────────────────────────────────────────

    /**
     * Accepts one connection, sends the greeting and then the prepared frames, and writes nothing else. The client
     * sends its own {@code HELLO} and {@code SUBSCRIBE} immediately; the server does not need to read them for these
     * assertions, and the writes fit in the socket buffer.
     */
    private static final class FakeServer implements AutoCloseable {

        private final Path directory;
        private final ServerSocketChannel server;
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

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

        private void serve() {
            try (SocketChannel channel = server.accept()) {
                for (byte[] frame : frames) {
                    ByteBuffer buffer = ByteBuffer.wrap(frame);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
                // Hold the connection open until the client closes it, so a refusal is read as a frame and not as an
                // EOF the test would misattribute. A Unix-domain SocketChannel has no socket() to half-close.
                ByteBuffer sink = ByteBuffer.allocate(256);
                while (channel.read(sink) >= 0) {
                    sink.clear();
                }
            } catch (IOException | RuntimeException ignored) {
                failure.compareAndSet(null, ignored);
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
