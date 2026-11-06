package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.fsmeta.FsMetaLoad;
import hr.hrg.fsmeta.FsMetaPair;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The W7b integration: <strong>two {@link WatchedRegeneration} clients on one root, both fed by the Zig fs-meta
 * daemon</strong>, seeing the same live batch, with the daemon as the only trio writer.
 *
 * <p>Opt-in, exactly like {@code FsMetaClientDaemonTest}: point {@code FSMETA_BIN} at a built {@code fsmeta} binary
 * and the test starts {@code fsmeta daemon start --foreground} under private runtime and config directories, adds
 * the root, subscribes two Java watchers, edits one file and asserts that both ran exactly one pass for the same
 * path. Without {@code FSMETA_BIN} the test is skipped, so the ordinary test run does not depend on a native
 * toolchain.</p>
 *
 * <p>The last assertion is the cutover's own claim: the trio is valid and was written by the daemon, not by this
 * repository's retired Java writer. Grep is the stronger check ({@code FsMetaWriter.publish} appears in no production
 * source); this test is the runtime witness.</p>
 */
class WatchedRegenerationDaemonTest {

    private static final String RETIRED_JAVA_WRITER = "jcodebuddy-watched-regeneration/1";

    @Test
    void twoClientsSeeTheSameDaemonBatchAndJavaDoesNotWriteTheTrio() throws Exception {
        String binary = System.getenv("FSMETA_BIN");
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)),
                "set FSMETA_BIN to the fs-meta Zig binary to run the daemon cutover integration");

        Path scratch = shortScratchDirectory();
        Path runtime = Files.createDirectories(scratch.resolve("run"));
        Path config = Files.createDirectories(scratch.resolve("cfg"));
        Path root = Files.createDirectories(scratch.resolve("root"));
        Path source = root.resolve("src/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}\n");
        Map<String, String> env = Map.of(
                "FSMETA_RUNTIME_DIR", runtime.toString(),
                "FSMETA_CONFIG_HOME", config.toString());

        Process daemon = null;
        try {
            // The daemon serves an existing trio; the engine's first publish is an explicit refresh.
            run(binary, root, env, "refresh", ".");
            daemon = startDaemon(binary, env);
            await(() -> Files.exists(runtime.resolve("daemon.sock")), 20_000, "the daemon control socket");
            run(binary, root, env, "root", "add", root.toString(), "--mode", "always", "--idle", "0");
            Path rootSocket = awaitRootSocket(runtime.resolve("roots"));

            List<WatchedRegeneration.Pass> firstSeen = new CopyOnWriteArrayList<>();
            List<WatchedRegeneration.Pass> secondSeen = new CopyOnWriteArrayList<>();
            WatchedRegeneration first = watcher(root, firstSeen, env);
            WatchedRegeneration second = watcher(root, secondSeen, env);
            try {
                first.start();
                second.start();
                assertTrue(first.live(), "the first client must attach: " + first.attachProblem());
                assertTrue(second.live(), "the second client must attach: " + second.attachProblem());
                assertEquals(rootSocket, first.daemonSocket().orElseThrow());
                assertEquals(first.daemonSocket(), second.daemonSocket());
                // The state was seeded from the daemon's trio, so the subscription starts at that generation rather
                // than replaying history into a pass.
                assertTrue(first.seededGeneration() >= 1,
                        "the daemon's trio must be the seed: generation " + first.seededGeneration());
                Thread.sleep(300);
                long firstBaseline = first.passCount();
                long secondBaseline = second.passCount();

                Files.writeString(source, "class A { int value; }\n");

                await(() -> first.passCount() > firstBaseline && second.passCount() > secondBaseline
                                && !firstSeen.isEmpty() && !secondSeen.isEmpty(), 30_000,
                        "both clients to run a pass for the edit");
                assertEquals(firstBaseline + 1, first.passCount(),
                        "one edit is one pass for the first client: " + firstSeen);
                assertEquals(secondBaseline + 1, second.passCount(),
                        "one edit is one pass for the second client: " + secondSeen);

                WatchedRegeneration.Pass firstPass = last(firstSeen);
                WatchedRegeneration.Pass secondPass = last(secondSeen);
                assertTrue(firstPass.regenerated() && secondPass.regenerated());
                assertEquals(Set.of("src/A.java"), firstPass.triggeringFiles(),
                        "both clients see the same daemon batch");
                assertEquals(firstPass.triggeringFiles(), secondPass.triggeringFiles(),
                        "two subscribers to one daemon receive the same change set");
                assertEquals(first.batchCount(), second.batchCount(),
                        "the two clients applied the same number of batches");
                assertEquals(0, first.recoveryCount(), "a live subscription needs no replay recovery");
                assertEquals(0, second.recoveryCount());

                FsMetaLoad trio = FsMetaPair.loadIfPresent(root.resolve(".fs-meta"));
                assertTrue(trio != null && trio.ok(),
                        "the daemon published a valid trio: " + (trio == null ? "absent" : trio.describe()));
                assertNotEquals(RETIRED_JAVA_WRITER, trio.pair().config().writer(),
                        "no JCodeBuddy process may publish the trio any more");
            } finally {
                first.close();
                second.close();
            }
        } finally {
            if (daemon != null) {
                stopDaemon(binary, env);
                daemon.destroy();
                if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
                    daemon.destroyForcibly();
                }
            }
            deleteRecursively(scratch);
        }
    }

    private static WatchedRegeneration watcher(Path root, List<WatchedRegeneration.Pass> seen,
                                               Map<String, String> env) {
        return new WatchedRegeneration(root,
                List.of("**/*.java"),
                List.of("**/target/**"),
                () -> List.of(),
                seen::add,
                env,
                isWindows());
    }

    private static WatchedRegeneration.Pass last(List<WatchedRegeneration.Pass> seen) {
        assertFalse(seen.isEmpty(), "a pass was expected");
        return seen.get(seen.size() - 1);
    }

    /** A short path under the module's target: AF_UNIX limits the socket path to about a hundred characters. */
    private static Path shortScratchDirectory() throws IOException {
        Path directory = Path.of("target").resolve("w7b-" + Long.toString(System.nanoTime(), 36));
        Files.createDirectories(directory);
        return directory.toAbsolutePath();
    }

    private static Process startDaemon(String binary, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(binary, "daemon", "start", "--foreground");
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        Process daemon = builder.start();
        Thread drain = new Thread(() -> {
            try (var input = daemon.getInputStream()) {
                input.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // The process is being stopped; there is nothing to report here.
            }
        }, "fsmeta-daemon-output");
        drain.setDaemon(true);
        drain.start();
        return daemon;
    }

    private static void run(String binary, Path directory, Map<String, String> env, String... arguments)
            throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(binary);
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "fsmeta " + String.join(" ", arguments) + " did not finish: " + output);
        assertEquals(0, process.exitValue(), "fsmeta " + String.join(" ", arguments) + " failed: " + output);
    }

    private static void stopDaemon(String binary, Map<String, String> env) {
        try {
            ProcessBuilder builder = new ProcessBuilder(binary, "daemon", "stop");
            builder.environment().putAll(env);
            builder.redirectErrorStream(true);
            Process stop = builder.start();
            stop.getInputStream().transferTo(OutputStream.nullOutputStream());
            stop.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The daemon is destroyed below regardless; a failed stop must not mask the assertion.
        }
    }

    private static Path awaitRootSocket(Path roots) throws Exception {
        Path[] found = new Path[1];
        await(() -> {
            if (!Files.isDirectory(roots)) {
                return false;
            }
            try (var entries = Files.list(roots)) {
                found[0] = entries.filter(path -> path.toString().endsWith(".sock")).findFirst().orElse(null);
            } catch (IOException ignored) {
                return false;
            }
            return found[0] != null;
        }, 20_000, "the daemon's per-root socket");
        return found[0];
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMs, String what)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out after " + timeoutMs + " ms waiting for " + what);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
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
                    // Best effort; a leaked scratch directory must not mask the assertion.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
