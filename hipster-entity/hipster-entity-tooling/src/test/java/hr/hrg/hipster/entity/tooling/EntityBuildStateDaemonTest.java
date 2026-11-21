package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The opt-in integration: {@code --incremental}'s daemon-generation state against the <strong>real</strong> Zig
 * fs-meta daemon.
 *
 * <p>Point {@code FSMETA_BIN} at the fs-meta build — the sibling {@code ../fs-meta/zig-out/bin/fsmeta} checkout, or
 * the {@code fsmeta} on {@code PATH}. Without it the
 * class is skipped, so the ordinary suite does not depend on a native toolchain — the same opt-in rule as
 * {@code FsMetaClientDaemonTest}.</p>
 *
 * <p>What only a real daemon can prove, and what the in-process fake cannot:</p>
 * <ul>
 *   <li>the daemon is registered for the <strong>project root</strong> and discovered from a source root several
 *       levels below it ({@link FsMetaDaemonDiscovery#discoverAncestor(Path)});</li>
 *   <li>the extended {@code SUBSCRIBE} really filters server-side: a {@code .java} written outside the source
 *       root never reaches this consumer's stream;</li>
 *   <li>a real pass's writes are published, and the generation that includes them is the baseline the next run
 *       no-ops against;</li>
 *   <li>a source edit arrives as {@code CHANGED} and an added source as {@code ADDED} (a full pass, the E6 row).</li>
 * </ul>
 */
@Tag("generator")
class EntityBuildStateDaemonTest {

    private static Harness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        String binary = System.getenv("FSMETA_BIN");
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)),
                "set FSMETA_BIN to the fs-meta Zig binary to run the daemon integration");
        harness = Harness.start(binary);
    }

    @AfterAll
    static void stopDaemon() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void noBaselineThenNoopThenIncrementalThenAnOutsideChangeIsIrrelevant() throws Exception {
        Module module = harness.module("matrix");
        Module.EntitySources sources = module.writeSources();
        EntityBuildState state = module.state();
        EntityBuildState.Config config = module.config();

        Assertions.assertEquals(EntityBuildState.Mode.FULL, state.decide(config).mode(),
                "no run.json yet, so the reference pass is the only safe answer");

        EntityMetadataGenerator.generate(module.sourceRoot(), module.metadata(), module.sourceRoot());
        EntityBuildState.Publish published = state.publish(config, EntityBuildState.ValidationOutcome.NOT_RUN,
                EntityBuildState.Selection.full());
        Assertions.assertTrue(published.recorded(), published.problem());
        long baseline = published.generation();
        Assertions.assertTrue(baseline > 0, "the daemon has a generation once the pass's writes are published");

        Assertions.assertEquals(EntityBuildState.Mode.NOOP, state.decide(config).mode(),
                "the pass's own writes are inside the recorded generation");

        // A real source edit: the daemon publishes it as CHANGED, and the decision is incremental with the
        // source-root-relative name the pass uses.
        Files.writeString(sources.view(), sources.viewSource() + "\n// changed\n");
        EntityBuildState.Decision changed = awaitDecision(state, config, EntityBuildState.Mode.INCREMENTAL, 20_000);
        Assertions.assertEquals(List.of("build/entity/ThingSummary.java"), changed.changed(), changed.reason());

        EntityMetadataGenerator.generate(module.sourceRoot(), module.metadata(), module.sourceRoot());
        EntityBuildState.Publish afterPass = state.publish(config, EntityBuildState.ValidationOutcome.NOT_RUN,
                new EntityBuildState.Selection("incremental", changed.changed(), List.of("build.entity.ThingSummary")));
        Assertions.assertTrue(afterPass.recorded(), afterPass.problem());
        byte[] stateBefore = Files.readAllBytes(module.runFile());

        // A change outside the module's source root: the daemon tracks it (its head advances), and the filtered
        // replay has nothing this pass reads.
        long beforeOutside = module.daemon().head(module.location());
        Files.writeString(module.projectRoot().resolve("README.md"), "outside the module\n");
        awaitHeadPast(module, beforeOutside, 20_000);
        EntityBuildState.Decision irrelevant = state.decide(config);
        Assertions.assertEquals(EntityBuildState.Mode.NOOP, irrelevant.mode(), irrelevant.reason());
        Assertions.assertTrue(irrelevant.reason().contains("no .java file"), irrelevant.reason());
        Assertions.assertArrayEquals(stateBefore, Files.readAllBytes(module.runFile()),
                "a no-op does not advance the baseline");

        // A new source file is the E6 full-pass row: a new type can change more than its own file.
        long beforeExtra = module.daemon().head(module.location());
        Files.writeString(module.sourceRoot().resolve("build/entity/Extra.java"),
                "package build.entity;\npublic interface Extra {}\n");
        awaitHeadPast(module, beforeExtra, 20_000);
        EntityBuildState.Decision added = state.decide(config);
        Assertions.assertEquals(EntityBuildState.Mode.FULL, added.mode(), added.reason());
        Assertions.assertTrue(added.reason().contains("source set changed"), added.reason());
    }

    /**
     * The subfolder filter against the real daemon: a {@code .java} file written outside the source root is a
     * real change (the head moves and the pass-all replay names it), but the filtered subscription sends nothing
     * for it — or, on a daemon that predates the protocol's filter, the client-side fallback ignores it.
     *
     * <p>The capability is recorded rather than assumed: the daemon's live consumer path in {@code root.zig}
     * currently refuses the extended {@code SUBSCRIBE} that {@code FORMAT.socket.md} defines, so this test asserts
     * which of the two paths ran and proves the same decision either way.</p>
     */
    @Test
    void theSubfolderFilterHidesAChangeOutsideTheSourceRoot() throws Exception {
        Module module = harness.module("filtered");
        module.writeSources();
        FsMetaDaemonDiscovery.Attach attach = module.daemon().discover(module.sourceRoot());
        Assertions.assertTrue(attach.attached(), attach.problem());
        FsMetaDaemonDiscovery.Location location = attach.location();
        Assertions.assertEquals(module.projectRoot().toRealPath(), location.root(),
                "the daemon root is the project root, not the module's source root");

        long from = module.daemon().head(location);
        Path outside = module.projectRoot().resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("Other.java"), "public final class Other {}\n");
        long head = awaitHeadPast(module, from, 20_000);

        List<String> passAll = replayNames(location, from, List.of(), List.of());
        Assertions.assertTrue(passAll.contains("outside/Other.java"),
                "the change is real and the unfiltered stream names it: " + passAll);

        boolean serverFiltered;
        List<String> filtered = new ArrayList<>();
        try {
            filtered = replayNames(location, from,
                    FsMetaDaemonState.includePatterns(module.projectRoot(), module.sourceRoot()),
                    List.of("**/.jcodebuddy/**"));
            serverFiltered = true;
        } catch (FsMetaClient.ProtocolException refusal) {
            serverFiltered = false;
            Assertions.assertTrue(refusal.getMessage().contains("expected SUBSCRIBE with a u64 from_batch"),
                    "the only acceptable refusal here is the pre-filter daemon's: " + refusal.getMessage());
        }
        if (serverFiltered) {
            Assertions.assertFalse(filtered.contains("outside/Other.java"),
                    "the include pattern is module/src/main/java/**, so the outside change is not sent: " + filtered);
            Assertions.assertTrue(filtered.stream().allMatch(name -> name.startsWith("filtered/src/main/java/")),
                    "every delivered name is inside the module subfolder: " + filtered);
        }

        // Whether the filter ran on the server or as the fallback, the decision ignores the change.
        EntityBuildState state = module.state();
        EntityBuildState.Config config = module.config();
        EntityBuildState.Publish baseline = state.publish(config, EntityBuildState.ValidationOutcome.NOT_RUN,
                EntityBuildState.Selection.full());
        Assertions.assertTrue(baseline.recorded(), baseline.problem());
        Files.writeString(module.projectRoot().resolve("README.md"), "another outside change\n");
        awaitHeadPast(module, baseline.generation(), 20_000);
        EntityBuildState.Decision decision = state.decide(config);
        Assertions.assertEquals(EntityBuildState.Mode.NOOP, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("no .java file"), decision.reason());
        Assertions.assertTrue(head > 0, "the daemon head observed the outside write");
    }

    /** Every change name the daemon replays from {@code from}, optionally with the protocol filter applied. */
    private static List<String> replayNames(FsMetaDaemonDiscovery.Location location, long from,
                                            List<String> includes, List<String> excludes) throws IOException {
        List<String> names = new ArrayList<>();
        try (FsMetaClient client = FsMetaClient.connect(location.socket(), from, includes, excludes)) {
            long to = client.serverGeneration();
            while (to > from) {
                FsMetaClient.Batch batch = client.nextBatch();
                for (FsMetaClient.Change change : batch.changes()) {
                    names.add(change.name());
                }
                if (batch.number() >= to) {
                    break;
                }
            }
        }
        return names;
    }

    /**
     * A root that was asleep publishes nothing while it sleeps. The edit made in that window is published by the
     * <strong>wake scan</strong>, after the greeting — the case the protocol's {@code STATE(waking)} frame exists
     * for, and the one a naive "stop at the greeted generation" read answers with a wrong no-op.
     */
    @Test
    void aChangeMadeWhileTheRootSleptIsSeenByTheWakeScan() throws Exception {
        Module module = harness.module("sleepy");
        Module.EntitySources sources = module.writeSources();
        EntityBuildState state = module.state();
        EntityBuildState.Config config = module.config();

        EntityMetadataGenerator.generate(module.sourceRoot(), module.metadata(), module.sourceRoot());
        EntityBuildState.Publish baseline = state.publish(config, EntityBuildState.ValidationOutcome.NOT_RUN,
                EntityBuildState.Selection.full());
        Assertions.assertTrue(baseline.recorded(), baseline.problem());
        Assertions.assertEquals(EntityBuildState.Mode.NOOP, state.decide(config).mode());

        // Drop the watcher, then change the tree while nothing is watching it.
        harness.run("root", "sleep", module.projectRoot().toString(), "--base", harness.base().toString());
        Files.writeString(sources.view(), sources.viewSource() + "\n// edited while the root slept\n");

        EntityBuildState.Decision decision = awaitDecision(state, config,
                EntityBuildState.Mode.INCREMENTAL, 20_000);
        Assertions.assertEquals(List.of("build/entity/ThingSummary.java"), decision.changed(),
                "the wake scan's batch is a source change, not an empty replay: " + decision.reason());
    }

    // ── the harness ──────────────────────────────────────────────────────────

    /** A daemon under the module's {@code target/}, plus one served project root and its runtime/config dirs. */
    private record Harness(String binary, Path project, Path base, Path configHome, Process daemon,
                           ExecutorService readers, Map<String, String> env) implements AutoCloseable {

        static Harness start(String binary) throws Exception {
            // The runtime base is what the socket path is built from, and an AF_UNIX path is length-limited
            // (about 104 bytes on Windows). A `target/`-relative base is too deep for this module, so the
            // runtime and config directories go under the repository's short-lived `.tmp/` (gitignored, and not
            // deleted by `clean`), while the served project can stay under `target/`.
            Path scratch = Files.createDirectories(CompileHarness.findRepoRoot().resolve(".tmp"));
            Path base = Files.createTempDirectory(scratch, "edr-").toAbsolutePath();
            Path configHome = Files.createTempDirectory(scratch, "edc-").toAbsolutePath();
            Path project = Files.createTempDirectory(Path.of("target"), "edp-").toAbsolutePath();
            Files.writeString(project.resolve("README.md"), "served project\n");
            Map<String, String> env = new LinkedHashMap<>();
            env.put("FSMETA_RUNTIME_DIR", base.toString());
            env.put("FSMETA_CONFIG_HOME", configHome.toString());

            Process daemon = builder(binary, env,
                    "daemon", "start", "--foreground", "--base", base.toString()).start();
            ExecutorService readers = Executors.newCachedThreadPool();
            readers.submit(() -> drain(daemon));
            Harness harness = new Harness(binary, project, base, configHome, daemon, readers, env);
            try {
                await(base.resolve("daemon.sock"), 20_000);
                Process add = builder(binary, env, "root", "add", project.toString(),
                        "--mode", "always", "--idle", "0", "--base", base.toString()).start();
                String output = new String(add.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean finished = add.waitFor(30, TimeUnit.SECONDS);
                if (!finished || add.exitValue() != 0) {
                    throw new AssertionError("fsmeta root add must succeed (exit "
                            + (finished ? add.exitValue() : "timeout") + "): " + output);
                }
                return harness;
            } catch (Exception | AssertionError failure) {
                // A half-started harness must not leak a daemon or a socket; @AfterAll gets a null instead.
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

        Module module(String name) throws Exception {
            Path moduleRoot = Files.createDirectories(project.resolve(name));
            Path sourceRoot = Files.createDirectories(moduleRoot.resolve("src/main/java"));
            Files.createDirectories(sourceRoot.resolve("build/entity"));
            return new Module(project, moduleRoot, sourceRoot);
        }

        ProcessBuilder process(String... arguments) {
            return builder(binary, env, arguments);
        }

        /** Runs one fsmeta command and fails with its output when it does not exit 0. */
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
                stop.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
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
            readers.shutdownNow();
            deleteRecursively(base);
            deleteRecursively(configHome);
            deleteRecursively(project);
        }
    }

    /** One module under the served project root: the source root the cache answers for. */
    private record Module(Path projectRoot, Path moduleRoot, Path sourceRoot) {

        record EntitySources(Path marker, Path view, String viewSource) {
        }

        EntitySources writeSources() throws IOException {
            Path pkg = sourceRoot.resolve("build/entity");
            Files.createDirectories(pkg);
            Path marker = pkg.resolve("ThingEntity.java");
            Path view = pkg.resolve("ThingSummary.java");
            Files.writeString(marker, """
                    package build.entity;

                    import hr.hrg.hipster.entity.api.EntityBase;

                    public interface ThingEntity extends EntityBase<Long> {
                    }
                    """);
            String viewSource = """
                    package build.entity;

                    import hr.hrg.hipster.entity.api.View;

                    @View
                    public interface ThingSummary extends ThingEntity {
                      String name();
                    }
                    """;
            Files.writeString(view, viewSource);
            return new EntitySources(marker, view, viewSource);
        }

        Path metadata() {
            return moduleRoot.resolve(".jcodebuddy/metadata/entity");
        }

        Path runFile() {
            return moduleRoot.resolve(".jcodebuddy/cache/entity-build").resolve(EntityBuildState.RUN_FILE);
        }

        FsMetaDaemonState daemon() {
            return new FsMetaDaemonState(sourceRoot, harness.env(), isWindows());
        }

        FsMetaDaemonDiscovery.Location location() {
            FsMetaDaemonDiscovery.Attach attach = daemon().discover(sourceRoot);
            Assertions.assertTrue(attach.attached(), attach.problem());
            return attach.location();
        }

        EntityBuildState state() {
            return EntityBuildState.forPass(sourceRoot, metadata(), daemon());
        }

        EntityBuildState.Config config() {
            return new EntityBuildState.Config(metadata(), sourceRoot, List.of("build.entity"),
                    false, false, List.of(), List.of(), false, false, EntityMetadataGenerator.Policy.OFF);
        }
    }

    // ── polling helpers (the daemon owns the batch boundary, so a test waits for it) ─────────────────────────

    private static EntityBuildState.Decision awaitDecision(EntityBuildState state, EntityBuildState.Config config,
                                                           EntityBuildState.Mode expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        EntityBuildState.Decision last = null;
        while (System.nanoTime() < deadline) {
            last = state.decide(config);
            if (last.mode() == expected) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the daemon never produced " + expected + "; last decision: " + last);
    }

    private static long awaitHeadPast(Module module, long baseline, long timeoutMs) throws Exception {
        return awaitHeadPast(module.daemon(), module.sourceRoot(), baseline, timeoutMs);
    }

    private static long awaitHeadPast(FsMetaDaemonState daemon, Path sourceRoot, long baseline, long timeoutMs)
            throws Exception {
        FsMetaDaemonDiscovery.Attach attach = daemon.discover(sourceRoot);
        Assertions.assertTrue(attach.attached(), attach.problem());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long head = baseline;
        while (System.nanoTime() < deadline) {
            head = daemon.head(attach.location());
            if (head > baseline) {
                return head;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the daemon head stayed at " + baseline + " for " + timeoutMs + " ms");
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

    private static void drain(Process process) {
        try (var input = process.getInputStream()) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (Exception ignored) {
            // The process is being stopped; there is nothing to report here.
        }
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
