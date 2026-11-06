package hr.hrg.webview.webviewd;

import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real W7d contract: {@code ProjectEventStream} takes a live batch from the Zig fs-meta daemon and turns it
 * into the same SSE frame the test seam asserts.
 *
 * <p>Opt-in like the watch module's daemon test: set {@code FSMETA_BIN} to a built {@code fsmeta} binary. Without
 * it this test is skipped, so the ordinary build does not depend on a native toolchain.</p>
 */
class ProjectEventStreamDaemonTest {

    @Test
    void aLiveDaemonBatchBecomesAnSseFrame() throws Exception {
        String binary = System.getenv("FSMETA_BIN");
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)),
                "set FSMETA_BIN to the fs-meta Zig binary to run the daemon integration");

        Path project = Files.createTempDirectory(Path.of("target"), "wv-project-").toAbsolutePath();
        Path base = Files.createTempDirectory(Path.of("target"), "wv-base-").toAbsolutePath();
        Path configHome = Files.createTempDirectory(Path.of("target"), "wv-config-").toAbsolutePath();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("FSMETA_RUNTIME_DIR", base.toString());
        env.put("FSMETA_CONFIG_HOME", configHome.toString());

        Process daemon = null;
        ExecutorService readers = Executors.newCachedThreadPool();
        try {
            Path source = project.resolve("src/A.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class A {}\n");
            run(binary, project, Map.of(), "refresh", ".");

            ProcessBuilder daemonBuilder = new ProcessBuilder(binary, "daemon", "start", "--foreground",
                    "--base", base.toString());
            daemonBuilder.redirectErrorStream(true).environment().putAll(env);
            daemon = daemonBuilder.start();
            Process daemonProcess = daemon;
            readers.submit(() -> drain(daemonProcess));
            await(base.resolve("daemon.sock"), 20_000);

            run(binary, project, env, "root", "add", project.toString(),
                    "--mode", "always", "--idle", "0", "--base", base.toString());
            awaitRootSocket(base.resolve("roots"), 20_000);

            FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(
                    project, env, System.getProperty("os.name").toLowerCase().contains("win"));
            try (ProjectEventStream stream = ProjectEventStream.attach(project, attach)) {
                Thread.sleep(200);
                Files.writeString(source, "class A { int value; }\n");
                String frame = awaitChange(stream, 10_000);
                assertTrue(frame.contains("event: change"), frame);
                assertTrue(frame.contains("src/A.java"), frame);
            }
        } finally {
            if (daemon != null) {
                stopDaemon(binary, base);
                daemon.destroy();
                if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
                    daemon.destroyForcibly();
                }
            }
            readers.shutdownNow();
            deleteRecursively(project);
            deleteRecursively(base);
            deleteRecursively(configHome);
        }
    }

    private static String awaitChange(ProjectEventStream stream, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        String last = "";
        while (System.nanoTime() < deadline) {
            last = stream.awaitFrame(500);
            if (last.startsWith("event: change")) {
                return last;
            }
        }
        return last;
    }

    private static void run(String binary, Path cwd, Map<String, String> env, String... args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(binary);
        builder.command().addAll(List.of(args));
        builder.directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0,
                "fsmeta " + String.join(" ", args) + " failed (exit " + process.exitValue() + "): " + output);
    }

    private static void await(Path socket, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (Files.exists(socket)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the daemon did not create " + socket + " within " + timeoutMillis + " ms");
    }

    private static Path awaitRootSocket(Path roots, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
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
        throw new AssertionError("the daemon did not create a root socket under " + roots + " within "
                + timeoutMillis + " ms");
    }

    private static void stopDaemon(String binary, Path base) {
        try {
            Process stop = new ProcessBuilder(binary, "daemon", "stop", "--base", base.toString())
                    .redirectErrorStream(true)
                    .start();
            stop.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            stop.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The daemon is destroyed below; a failed stop must not mask the assertion.
        }
    }

    private static void drain(Process process) {
        try (var input = process.getInputStream()) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (Exception ignored) {
            // The process is being stopped.
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
                    // Best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
