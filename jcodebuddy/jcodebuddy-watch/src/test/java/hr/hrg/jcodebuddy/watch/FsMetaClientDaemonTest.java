package hr.hrg.jcodebuddy.watch;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real integration: a Java client watches through the **Zig fs-meta daemon** over its Unix socket.
 *
 * <p>This is opt-in because the daemon is a native binary outside this checkout. Point {@code FSMETA_BIN} at
 * {@code fs-meta/zig-out/bin/fsmeta.exe} (or the platform equivalent) and the test starts
 * {@code fsmeta daemon start --foreground}, adds the served root, connects the Java client, edits a file and asserts
 * the live batch. Without the variable the test is skipped, so the ordinary gate does not depend on a native
 * toolchain.</p>
 */
class FsMetaClientDaemonTest {

    @Test
    void javaReceivesALiveBatchFromTheZigDaemon() throws Exception {
        String binary = System.getenv("FSMETA_BIN");
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)),
                "set FSMETA_BIN to the fs-meta Zig binary to run the daemon integration");

        // Keep the served root under the module's target: a Unix socket and the refresh both need a real,
        // writable filesystem location, and this keeps the test independent of the machine's temp-directory ACLs.
        Path root = Files.createTempDirectory(Path.of("target"), "r-").toAbsolutePath();
        Path base = Files.createTempDirectory(Path.of("target"), "b-").toAbsolutePath();
        try {
            Path file = root.resolve("A.java");
            Files.writeString(file, "class A {}\n");

            // `daemon` serves an existing trio; the engine's first publish is an explicit refresh.
            Process refresh = new ProcessBuilder(binary, "refresh", ".")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String refreshOutput = new String(refresh.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean refreshed = refresh.waitFor(30, TimeUnit.SECONDS);
            assertTrue(refreshed && refresh.exitValue() == 0,
                    "fsmeta refresh must publish the initial trio (exit " + refresh.exitValue() + "): " + refreshOutput);

            Process daemon = new ProcessBuilder(binary, "daemon", "start", "--foreground", "--base", base.toString())
                    .redirectErrorStream(true)
                    .start();
            ExecutorService readers = Executors.newCachedThreadPool();
            readers.submit(() -> drain(daemon));
            try {
                await(base.resolve("daemon.sock"), 20_000);
                Process add = new ProcessBuilder(binary, "root", "add", root.toString(),
                        "--mode", "always", "--idle", "0", "--base", base.toString())
                        .redirectErrorStream(true)
                        .start();
                String addOutput = new String(add.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                boolean added = add.waitFor(30, TimeUnit.SECONDS);
                assertTrue(added && add.exitValue() == 0,
                        "fsmeta root add must succeed (exit " + add.exitValue() + "): " + addOutput);

                Path socket = awaitRootSocket(base.resolve("roots"), 20_000);
                try (FsMetaClient first = FsMetaClient.connect(socket, FsMetaClient.LATEST);
                     FsMetaClient second = FsMetaClient.connect(socket, FsMetaClient.LATEST)) {
                    long head = first.serverGeneration();
                    // Both clients subscribed after the daemon's current head. One real edit must reach both as a
                    // live batch, which is the multiple-tools shape the Zig daemon exists for.
                    Thread.sleep(200);
                    Files.writeString(file, "class A { int value; }\n");

                    Future<FsMetaClient.Batch> firstNext = readers.submit((Callable<FsMetaClient.Batch>) first::nextBatch);
                    Future<FsMetaClient.Batch> secondNext = readers.submit((Callable<FsMetaClient.Batch>) second::nextBatch);
                    FsMetaClient.Batch firstBatch = firstNext.get(30, TimeUnit.SECONDS);
                    FsMetaClient.Batch secondBatch = secondNext.get(30, TimeUnit.SECONDS);
                    assertLiveChange(firstBatch, head);
                    assertLiveChange(secondBatch, head);
                }
            } finally {
                stopDaemon(binary, base);
                daemon.destroy();
                if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
                    daemon.destroyForcibly();
                }
                readers.shutdownNow();
            }
        } finally {
            deleteRecursively(root);
            deleteRecursively(base);
        }
    }

    private static void await(Path socket, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (Files.exists(socket)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the daemon did not create " + socket + " within " + timeoutMs + " ms");
    }

    private static void assertLiveChange(FsMetaClient.Batch batch, long head) {
        assertTrue(batch.number() > head, "the batch must be newer than the daemon head " + head + ": " + batch);
        assertTrue(batch.changes().stream()
                        .anyMatch(change -> change.name().equals("A.java")
                                && change.kind() == FsMetaClient.Kind.CHANGED),
                "the live batch must name A.java as CHANGED: " + batch);
    }

    private static Path awaitRootSocket(Path roots, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(roots)) {
                try (var entries = Files.list(roots)) {
                    var socket = entries.filter(path -> path.toString().endsWith(".sock")).findFirst();
                    if (socket.isPresent()) {
                        return socket.get();
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the daemon did not create a root socket under " + roots + " within " + timeoutMs + " ms");
    }

    private static void stopDaemon(String binary, Path base) {
        try {
            Process stop = new ProcessBuilder(binary, "daemon", "stop", "--base", base.toString())
                    .redirectErrorStream(true)
                    .start();
            stop.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            stop.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The daemon is destroyed below regardless; a failed stop must not mask the assertion.
        }
    }

    private static void drain(Process process) {
        try (var input = process.getInputStream()) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (Exception ignored) {
            // The process is being stopped; there is nothing to report here.
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; a leaked test directory must not mask the assertion.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
