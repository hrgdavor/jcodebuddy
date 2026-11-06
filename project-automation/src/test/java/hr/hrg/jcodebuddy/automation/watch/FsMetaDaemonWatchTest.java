package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FsMetaDaemonWatch}: batch conversion and the two replay refusals, against a fake daemon on a real Unix
 * socket. No native binary is needed, and the assertions are about this adapter's behaviour rather than the daemon's.
 *
 * <p>The loop test is the point: a first subscription the daemon refuses ({@code batch-too-old}) must become one
 * overflow batch — the consumer's "the change set may be incomplete" signal — and then a head subscription that
 * delivers the live batch. A silently skipped generation would pass a simpler test and be the bug the format forbids.
 * </p>
 */
class FsMetaDaemonWatchTest {

    private static final PathFilter JAVA_ONLY = PathFilter.of(List.of("**/*.java"), List.of("**/target/**"));

    @Test
    void onlyWatchedNamesBecomeTheBatch() {
        FsMetaClient.Batch batch = new FsMetaClient.Batch(7, 6, List.of(
                new FsMetaClient.Change("src/A.java", FsMetaClient.Kind.ADDED),
                new FsMetaClient.Change("README.md", FsMetaClient.Kind.CHANGED),
                new FsMetaClient.Change("target/B.java", FsMetaClient.Kind.CHANGED),
                new FsMetaClient.Change("src/C.java", FsMetaClient.Kind.DELETED)));

        WatchBatch converted = FsMetaDaemonWatch.toWatchBatch(batch, JAVA_ONLY);

        assertEquals(Set.of("src/A.java"), converted.changed(), "an unrelated README must not wake a Java generator");
        assertEquals(Set.of("src/C.java"), converted.deleted());
        assertFalse(converted.overflow(), "a daemon batch is complete; overflow is the replay-refusal signal");
    }

    @Test
    void aRefusedReplayBecomesAFullPassAndThenTheLiveBatch() throws Exception {
        FakeDaemon daemon = FakeDaemon.start();
        List<WatchBatch> delivered = new CopyOnWriteArrayList<>();
        FsMetaDaemonWatch watch = new FsMetaDaemonWatch(JAVA_ONLY, daemon.socket(), 0, delivered::add);
        try {
            watch.start();
            await(() -> delivered.size() >= 2, 15_000);

            assertEquals(1, watch.recoveries(),
                    "one batch-too-old refusal is one recovery, not a retry loop");
            assertTrue(delivered.get(0).overflow(),
                    "the consumer must be told the change set may be incomplete, so it runs a full pass");
            assertEquals(Set.of("src/A.java"), delivered.get(1).changed(),
                    "after resubscribing at the head, the live batch is the ordinary one");
            assertTrue(delivered.get(1).deleted().isEmpty());
            assertFalse(delivered.get(1).overflow());
            assertEquals(5, watch.lastAppliedBatch(), "the applied generation is remembered for a reconnect");
            assertEquals(1, watch.deliveredBatches());
        } finally {
            watch.stop();
            daemon.close();
        }
    }

    @Test
    void theRefusalsThatNeedAFullReadAreExactlyTheReplayOnes() {
        assertTrue(FsMetaDaemonWatch.isReplayRefusal(FsMetaClient.ERROR_BATCH_TOO_OLD, 0),
                "batch-too-old is a full read even on a first subscription");
        assertTrue(FsMetaDaemonWatch.isReplayRefusal(FsMetaClient.ERROR_BATCH_GAP, 3),
                "a missing generation is never skipped");
        assertTrue(FsMetaDaemonWatch.isReplayRefusal(FsMetaClient.ERROR_PROTOCOL, 12),
                "a protocol refusal after a resume means the daemon cannot start where we asked");
        assertFalse(FsMetaDaemonWatch.isReplayRefusal(FsMetaClient.ERROR_PROTOCOL, 0),
                "a first subscribe refused for a protocol reason is not a replay gap to paper over");
        assertFalse(FsMetaDaemonWatch.isReplayRefusal(999, 5), "an unknown code is not guessed at");
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the condition did not become true within " + timeoutMs + " ms");
    }

    // ── a fake daemon: refuse the first replay, then serve the head ──────────────────────────────────────────────

    /**
     * Connection 1 greets at generation 4, reads the client's {@code HELLO}/{@code SUBSCRIBE}, answers
     * {@code ERROR(batch-too-old)} and closes. Connection 2 greets, reads the subscribe and sends generation 5 with
     * one changed {@code src/A.java}.
     */
    private static final class FakeDaemon implements AutoCloseable {

        private final Path directory;
        private final ServerSocketChannel server;
        private final Thread thread = new Thread(this::serve, "fake-fsmeta-daemon");

        private FakeDaemon(Path directory, ServerSocketChannel server) {
            this.directory = directory;
            this.server = server;
            this.thread.setDaemon(true);
        }

        static FakeDaemon start() throws Exception {
            Path directory = Files.createTempDirectory(Path.of("target"), "fsmd-");
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
            FakeDaemon daemon = new FakeDaemon(directory, server);
            daemon.thread.start();
            return daemon;
        }

        Path socket() {
            return directory.resolve("s.sock");
        }

        private void serve() {
            try {
                try (SocketChannel first = server.accept()) {
                    write(first, hello(4));
                    readFrames(first, 2);
                    write(first, error(FsMetaClient.ERROR_BATCH_TOO_OLD, "batch-too-old: no batch 1"));
                }
                try (SocketChannel second = server.accept()) {
                    write(second, hello(4));
                    readFrames(second, 2);
                    write(second, startBatch(5, 4));
                    write(second, changeEntry("src/A.java", 0x40));
                    write(second, endBatch(5, 0, 1, 0));
                    ByteBuffer sink = ByteBuffer.allocate(256);
                    while (second.read(sink) >= 0) {
                        sink.clear();
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // The test asserts on the client side; a closing fixture is not a failure.
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
                        // Best effort.
                    }
                });
            } catch (IOException ignored) {
                // Same.
            }
        }
    }

    private static void write(SocketChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFrames(SocketChannel channel, int count) throws IOException {
        for (int index = 0; index < count; index++) {
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    return;
                }
            }
            int length = header.getInt(4);
            ByteBuffer payload = ByteBuffer.allocate(length);
            while (payload.hasRemaining()) {
                if (channel.read(payload) < 0) {
                    return;
                }
            }
        }
    }

    private static byte[] hello(long generation) {
        ByteBuffer payload = littleEndian(24);
        payload.putShort((short) FsMetaClient.PROTOCOL);
        payload.putShort((short) 64);
        payload.put((byte) 0);
        payload.put((byte) 0);
        payload.put((byte) 0);
        payload.put((byte) 0);
        payload.putLong(generation);
        payload.putLong(1 << 20);
        return frame(0x01, payload.array());
    }

    private static byte[] startBatch(long number, long parent) {
        ByteBuffer payload = littleEndian(24);
        payload.putLong(number);
        payload.putLong(parent);
        payload.putLong(0);
        return frame(0x02, payload.array());
    }

    private static byte[] endBatch(long number, long added, long changed, long deleted) {
        ByteBuffer payload = littleEndian(40);
        payload.putLong(number);
        payload.putLong(added);
        payload.putLong(changed);
        payload.putLong(deleted);
        payload.putLong(0);
        return frame(0x04, payload.array());
    }

    private static byte[] changeEntry(String name, int changeBits) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = littleEndian(64 + bytes.length);
        payload.putInt(40, bytes.length);
        payload.putInt(44, changeBits);
        payload.position(64);
        payload.put(bytes);
        return frame(0x03, payload.array());
    }

    private static byte[] error(int code, String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = littleEndian(2 + text.length);
        payload.putShort((short) code);
        payload.put(text);
        return frame(0x05, payload.array());
    }

    private static byte[] frame(int kind, byte[] payload) {
        ByteBuffer frame = littleEndian(8 + payload.length);
        frame.putShort((short) kind);
        frame.putShort((short) 0);
        frame.putInt(payload.length);
        frame.put(payload);
        return frame.array();
    }

    private static ByteBuffer littleEndian(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }
}
