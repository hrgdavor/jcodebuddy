package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The opt-in integration E11's acceptance asks for: start {@code --watch} against the <strong>real</strong> Zig
 * fs-meta daemon, edit a source file, watch the generator emit the affected view, and prove the generated output
 * does not cause an endless second pass.
 *
 * <p>Point {@code FSMETA_BIN} at the fs-meta build — the sibling {@code ../fs-meta/zig-out/bin/fsmeta} checkout, or
 * the {@code fsmeta} on {@code PATH} — and the test is enabled.
 * Without it the test is skipped, exactly like {@code EntityBuildStateDaemonTest} and
 * {@code FsMetaClientDaemonTest}, so the ordinary suite does not require a native toolchain.</p>
 *
 * <p>What only the real daemon can prove here, and the in-process fakes cannot:</p>
 * <ul>
 *   <li>the subscription really is limited to the module source root, so another module's edit and the
 *       generator's own {@code .jcodebuddy} writes are not delivered;</li>
 *   <li>the daemon publishes the pass's generated files, and the loop's head-settle + {@code LATEST}
 *       resubscription really excludes them — the loop guard against the generator's own output;</li>
 *   <li>the pass's {@link EntityBuildState} baseline is recorded against the project-root daemon, so a second
 *       pass over the same writes would be a no-op even if the event were delivered twice;</li>
 *   <li>shutdown closes the subscription and terminates the loop thread within a bounded wait.</li>
 * </ul>
 */
@Tag("generator")
class EntityWatchDaemonTest {

    private static Harness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        String binary = System.getenv("FSMETA_BIN");
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)),
                "set FSMETA_BIN to the fs-meta Zig binary to run the watch integration");
        harness = Harness.start(binary);
    }

    @AfterAll
    static void stopDaemon() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void anEditRegeneratesTheViewAndTheGeneratedOutputDoesNotLoop() throws Exception {
        Path moduleRoot = Files.createDirectories(harness.project().resolve("module"));
        Path sourceRoot = Files.createDirectories(moduleRoot.resolve("src/main/java"));
        Path packageDir = Files.createDirectories(sourceRoot.resolve("build/entity"));
        Path metadata = moduleRoot.resolve(".jcodebuddy/metadata/entity");

        Path marker = packageDir.resolve("ThingEntity.java");
        Path view = packageDir.resolve("ThingSummary.java");
        String viewSource = """
                package build.entity;

                import hr.hrg.hipster.entity.api.View;

                @View
                public interface ThingSummary extends ThingEntity {
                  String name();
                }
                """;
        Files.writeString(marker, """
                package build.entity;

                import hr.hrg.hipster.entity.api.EntityBase;

                public interface ThingEntity extends EntityBase<Long> {
                }
                """);
        Files.writeString(view, viewSource);

        FsMetaDaemonState daemon = new FsMetaDaemonState(sourceRoot, harness.env(), isWindows());
        FsMetaDaemonDiscovery.Attach attach = daemon.discover(sourceRoot);
        Assertions.assertTrue(attach.attached(), attach.problem());
        Assertions.assertEquals(harness.project().toRealPath(), attach.location().root(),
                "the daemon root is the project root, not the module source root");

        EntityMetadataGenerator.PassInvocation invocation = new EntityMetadataGenerator.PassInvocation(
                sourceRoot, sourceRoot, metadata, sourceRoot, sourceRoot,
                List.of("build.entity"), List.of(), List.of(), false, false, false, false,
                EntityMetadataGenerator.Policy.OFF, false, null, daemon,
                new String[] { "watch-integration" });
        WatchCommand command = new WatchCommand(daemon, sourceRoot,
                full -> EntityMetadataGenerator.runPass(invocation, !full), true, 50);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread loop = new Thread(() -> {
            try {
                command.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "entity-watch-daemon-test");
        loop.setDaemon(true);

        try {
            loop.start();
            await(command::watching, 30_000, "the watch loop to attach to the daemon");

            Path enumFile = packageDir.resolve("ThingSummary_.java");
            await(() -> Files.isRegularFile(enumFile), 30_000, "the initial pass to emit ThingSummary_");
            assertNoFailure(failure);
            long initialGeneration = recordedGeneration(moduleRoot);
            Assertions.assertTrue(initialGeneration > 0,
                    "the initial pass records its daemon generation as the incremental baseline");

            // The edit the acceptance test asks for: an existing accessor is added to the view, so the affected
            // view's enum and record must be re-emitted.
            Files.writeString(view, viewSource.replace("String name();", "String name();\n  int age();"));

            await(() -> {
                try {
                    return Files.isRegularFile(enumFile)
                            && Files.readString(enumFile).toLowerCase(java.util.Locale.ROOT).contains("age");
                } catch (IOException unreadable) {
                    return false;
                }
            }, 30_000, "the watched pass to emit the edited view");
            assertNoFailure(failure);

            await(() -> command.passes() >= 1, 10_000, "the batch-triggered pass to finish");
            long passesAfterEdit = command.passes();
            long generationAfterEdit = recordedGeneration(moduleRoot);
            Assertions.assertTrue(generationAfterEdit > initialGeneration,
                    "the pass's own generated writes are inside the recorded baseline: "
                            + initialGeneration + " -> " + generationAfterEdit);

            // The loop guard: the generated files just published are behind the resubscription's head, so they
            // must not start another pass. Wait longer than debounce + settle + a pass would take.
            Thread.sleep(1_500);
            assertNoFailure(failure);
            Assertions.assertEquals(passesAfterEdit, command.passes(),
                    "the generator's own output must not trigger another pass (endless-loop guard)");
            Assertions.assertEquals(generationAfterEdit, recordedGeneration(moduleRoot),
                    "and it must not rewrite the baseline either");

            // One state directory, the existing one; no second tracker, no second cache.
            try (var cache = Files.list(moduleRoot.resolve(".jcodebuddy/cache"))) {
                Assertions.assertEquals(List.of("entity-build"),
                        cache.map(path -> path.getFileName().toString()).sorted().toList(),
                        "E11 adds no second state directory");
            }
            try (var stateFiles = Files.list(moduleRoot.resolve(".jcodebuddy/cache/entity-build"))) {
                Assertions.assertEquals(List.of(EntityBuildState.RUN_FILE),
                        stateFiles.map(path -> path.getFileName().toString()).sorted().toList(),
                        "the only state is the existing daemon-generation marker");
            }
        } finally {
            command.close();
            loop.join(20_000);
        }

        Assertions.assertFalse(loop.isAlive(), "shutdown closes the watcher and terminates the loop predictably");
        Assertions.assertFalse(command.watching(), "the daemon subscription is closed");
        assertNoFailure(failure);
    }

    private static long recordedGeneration(Path moduleRoot) {
        Path runFile = moduleRoot.resolve(".jcodebuddy/cache/entity-build")
                .resolve(EntityBuildState.RUN_FILE);
        if (!Files.isRegularFile(runFile)) {
            return 0;
        }
        try {
            return EntityMetadataGenerator.OBJECT_MAPPER.readTree(Files.readString(runFile))
                    .path("daemon").path("generation").asLong(0);
        } catch (IOException unreadable) {
            return 0;
        }
    }

    private static void assertNoFailure(AtomicReference<Throwable> failure) {
        Throwable thrown = failure.get();
        if (thrown != null) {
            Assertions.fail("the watch loop failed: " + thrown, thrown);
        }
    }

    private static void await(BooleanSupplier condition, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out after " + timeoutMs + " ms waiting for " + what);
    }

    // ── the harness: one daemon, one served project root, private runtime/config directories ─────────────

    private record Harness(String binary, Path project, Path base, Path configHome, Process daemon,
                           Map<String, String> env) implements AutoCloseable {

        static Harness start(String binary) throws Exception {
            // An AF_UNIX socket path is length-limited (about 104 bytes on Windows), so the runtime and config
            // directories go under the repository's short-lived, gitignored `.tmp/`; the served project can stay
            // under `target/`.
            Path scratch = Files.createDirectories(CompileHarness.findRepoRoot().resolve(".tmp"));
            Path base = Files.createTempDirectory(scratch, "watch-").toAbsolutePath();
            Path configHome = Files.createTempDirectory(scratch, "watch-cfg-").toAbsolutePath();
            Path project = Files.createTempDirectory(Path.of("target"), "watch-root-").toAbsolutePath();
            Files.writeString(project.resolve("README.md"), "served project\n");
            Map<String, String> env = new LinkedHashMap<>();
            env.put("FSMETA_RUNTIME_DIR", base.toString());
            env.put("FSMETA_CONFIG_HOME", configHome.toString());

            Process daemon = builder(binary, env,
                    "daemon", "start", "--foreground", "--base", base.toString()).start();
            Thread drain = new Thread(() -> {
                try (var input = daemon.getInputStream()) {
                    input.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    // The process is being stopped; there is nothing to report here.
                }
            }, "fsmeta-watch-output");
            drain.setDaemon(true);
            drain.start();
            Harness harness = new Harness(binary, project, base, configHome, daemon, env);
            try {
                awaitFile(base.resolve("daemon.sock"), 30_000, "the daemon control socket");
                harness.run("root", "add", project.toString(), "--mode", "always", "--idle", "0",
                        "--base", base.toString());
                return harness;
            } catch (Exception | AssertionError failure) {
                harness.close();
                throw failure;
            }
        }

        private static ProcessBuilder builder(String binary, Map<String, String> env, String... arguments) {
            List<String> command = new ArrayList<>();
            command.add(binary);
            command.addAll(List.of(arguments));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.environment().putAll(env);
            return builder;
        }

        ProcessBuilder process(String... arguments) {
            return builder(binary, env, arguments);
        }

        void run(String... arguments) throws Exception {
            Process process = process(arguments).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                throw new AssertionError("fsmeta " + String.join(" ", arguments) + " must succeed (exit "
                        + (finished ? process.exitValue() : "timeout") + "): " + output);
            }
        }

        @Override
        public void close() {
            try {
                Process stop = process("daemon", "stop", "--base", base.toString()).start();
                stop.getInputStream().transferTo(OutputStream.nullOutputStream());
                stop.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The daemon is destroyed below regardless; a failed stop must not mask an assertion.
            }
            daemon.destroy();
            try {
                if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
                    daemon.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            deleteRecursively(base);
            deleteRecursively(configHome);
            deleteRecursively(project);
        }
    }

    private static void awaitFile(Path file, long timeoutMs, String what) throws InterruptedException {
        await(() -> Files.exists(file), timeoutMs, what);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
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
