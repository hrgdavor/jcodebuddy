// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent;

import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectWatcher} on the fs-meta daemon path: the agent's cutover from the retired {@code FileWatch}.
 *
 * <p>No daemon is the case that must never throw — the agent has to start anyway, log the exact
 * {@link FsMetaDaemonDiscovery.Attach#problem()} and stay inactive. The dispatch case runs against a fake daemon on a
 * real Unix socket (the same fixture shape {@code jcodebuddy-watch}'s {@code FsMetaDaemonWatchTest} uses), so no
 * native binary is needed; the opt-in native integration stays where {@code FSMETA_BIN} already gates it
 * ({@code FsMetaClientDaemonTest} and project-automation's {@code WatchedRegenerationDaemonTest}).</p>
 */
class ProjectWatcherTest {

    @Test
    void withNoDaemonItStartsNothingLogsTheProblemAndStaysInactive(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Map<String, String> env = Map.of(
                "FSMETA_RUNTIME_DIR", dir.resolve("run").toString(),
                "FSMETA_CONFIG_HOME", dir.resolve("config").toString());

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(root, env, isWindows());
        assertFalse(attach.attached(), "no root list exists, so discovery must not attach");

        ProjectWatcher watcher = new ProjectWatcher(root, paths -> {}, attach);
        try {
            watcher.start(); // must not throw: no daemon is a normal state, not a startup failure
            assertFalse(watcher.live());
            assertTrue(watcher.attachProblem().contains("no fsmeta root list"),
                    "a watcher with no daemon must report why: " + watcher.attachProblem());
            assertTrue(watcher.daemonSocket().isEmpty(), "no socket was discovered, so none is claimed");
        } finally {
            watcher.stop();
            watcher.stop(); // safe twice
        }
        assertFalse(watcher.live());
    }

    @Test
    void aDaemonBatchIsDispatchedToTheListener() throws Exception {
        // A relative scratch directory would make the watcher's normalized paths differ from the fixture's; keep the
        // whole fixture absolute and short, because AF_UNIX limits the socket path to about a hundred characters.
        Path scratch = Files.createTempDirectory(Path.of("target"), "pw-").toAbsolutePath();
        Path root = Files.createDirectories(scratch.resolve("root"));
        Path runtime = Files.createDirectories(scratch.resolve("run"));
        Path config = Files.createDirectories(scratch.resolve("config"));
        Path socket = Files.createDirectories(runtime.resolve("roots")).resolve("a1b2c3d4.sock");

        FakeDaemon daemon = FakeDaemon.start(socket);
        ProjectWatcher watcher = null;
        try {
            writeRegistry(config, root, socket);
            Map<String, String> env = Map.of(
                    "FSMETA_RUNTIME_DIR", runtime.toString(),
                    "FSMETA_CONFIG_HOME", config.toString());
            FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(root, env, isWindows());
            assertTrue(attach.attached(), attach.problem());

            List<Path> seen = new CopyOnWriteArrayList<>();
            watcher = new ProjectWatcher(root, seen::addAll, attach);
            watcher.start();
            assertTrue(watcher.live(), watcher.attachProblem());
            assertEquals(socket, watcher.daemonSocket().orElseThrow());

            await(() -> !seen.isEmpty(), 15_000);

            Path expected = root.resolve("src/A.java");
            assertEquals(List.of(expected), seen, "the listener sees the daemon's change");
        } finally {
            if (watcher != null) {
                watcher.stop();
            }
            daemon.close();
            deleteRecursively(scratch);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────


    /** Writes the daemon's durable root list, the way {@code fsmeta root add} does. */
    private static void writeRegistry(Path configHome, Path root, Path socket) throws Exception {
        Path fsmeta = Files.createDirectories(configHome.resolve("fsmeta"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "a1b2c3d4");
        record.put("root", root.toRealPath().toString());
        record.put("socket", socket.toString());
        Map<String, Object> document = Map.of("version", 1, "roots", List.of(record));
        Files.writeString(fsmeta.resolve("roots.json"),
                new tools.jackson.databind.ObjectMapper().writeValueAsString(document));
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort; a leaked scratch directory must not mask the assertion.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    // ── a fake daemon: greet at generation 4, accept the subscription, send generation 5 ────────────────────────

    private static final class FakeDaemon implements AutoCloseable {

        private final ServerSocketChannel server;
        private final Thread thread = new Thread(this::serve, "fake-fsmeta-daemon");

        private FakeDaemon(ServerSocketChannel server) {
            this.server = server;
            this.thread.setDaemon(true);
        }

        static FakeDaemon start(Path socket) throws Exception {
            ServerSocketChannel server;
            try {
                server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                server.bind(UnixDomainSocketAddress.of(socket));
            } catch (UnsupportedOperationException | IOException unavailable) {
                Assumptions.assumeTrue(false, "Unix domain sockets are unavailable here: " + unavailable);
                throw unavailable;
            }
            FakeDaemon daemon = new FakeDaemon(server);
            daemon.thread.start();
            return daemon;
        }

        private void serve() {
            try (SocketChannel channel = server.accept()) {
                write(channel, hello(4));
                readFrames(channel, 2); // HELLO, then SUBSCRIBE at the greeter's head (LATEST)
                write(channel, startBatch(5, 4));
                write(channel, changeEntry("src/A.java", 0x40));
                write(channel, endBatch(5, 0, 1, 0));
                ByteBuffer sink = ByteBuffer.allocate(256);
                while (channel.read(sink) >= 0) {
                    sink.clear();
                }
            } catch (IOException | RuntimeException ignored) {
                // The test asserts on the watcher side; a closing fixture is not a failure.
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
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