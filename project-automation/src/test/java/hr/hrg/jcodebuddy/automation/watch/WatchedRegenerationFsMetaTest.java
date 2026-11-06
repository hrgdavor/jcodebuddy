package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.fsmeta.FsMetaFile;
import hr.hrg.fsmeta.FsMetaHashMode;
import hr.hrg.fsmeta.FsMetaLoad;
import hr.hrg.fsmeta.FsMetaPair;
import hr.hrg.fsmeta.FsMetaScanOptions;
import hr.hrg.fsmeta.FsMetaScanner;
import hr.hrg.fsmeta.FsMetaWriter;
import hr.hrg.jcodebuddy.watch.WatchBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The W7b state half: the loop breaker is fs-meta's fingerprints, <strong>read from the daemon's trio and never
 * written by this class</strong>.
 *
 * <p>Each test runs with discovery pointed at empty runtime/config directories, which is the standalone case: no
 * daemon serves the root, so {@link WatchedRegeneration#live()} is false, the events cannot arrive, and the read-only
 * path is what has to be correct. A trio in the daemon's {@code <root>/.fs-meta} — or, as a legacy baseline, the W5a
 * {@code .jcodebuddy/cache/watch-fs-meta} — seeds the state; {@code FsMetaWriter} appears here and only here, because
 * a test may prepare state and production may not write it.</p>
 */
class WatchedRegenerationFsMetaTest {

    private static WatchedRegeneration watcher(Path root, List<WatchedRegeneration.Pass> seen) {
        Map<String, String> env = Map.of(
                "FSMETA_RUNTIME_DIR", root.getParent().resolve("no-daemon-runtime").toString(),
                "FSMETA_CONFIG_HOME", root.getParent().resolve("no-daemon-config").toString());
        return new WatchedRegeneration(root,
                List.of("**/*.java"),
                List.of("**/target/**"),
                () -> List.of(),
                seen::add,
                env,
                true);
    }

    /** A trio the way the daemon (or the retired W5a writer) would have left it. Test-only preparation. */
    private static void publish(Path root, Path directory, String... names) throws Exception {
        List<FsMetaFile> files = new ArrayList<>();
        for (String name : names) {
            FsMetaScanner.scanFile(root, root.resolve(name), FsMetaScanOptions.defaults()).ifPresent(files::add);
        }
        FsMetaLoad load = FsMetaWriter.publish(directory, files, FsMetaHashMode.TEXT, "test/1", root.toString());
        assertTrue(load.ok(), load.describe());
    }

    private static Path source(Path root) throws Exception {
        Path source = root.resolve("src/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}\n");
        return source;
    }

    @Test
    void noDaemonMeansNoLiveSourceAndNoStateIsWritten(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        source(root);
        List<WatchedRegeneration.Pass> seen = new ArrayList<>();

        WatchedRegeneration watcher = watcher(root, seen);
        watcher.start();

        assertFalse(watcher.live(), "there is no daemon in the empty runtime directory");
        assertTrue(watcher.attachProblem().contains("no fsmeta root list"), watcher.attachProblem());
        assertEquals(root.resolve(".fs-meta"), watcher.stateDirectory(),
                "the state is the daemon's trio directory, not a second state directory of this class's own");
        assertFalse(Files.exists(root.resolve(".fs-meta")), "a client must not create a trio");
        assertFalse(Files.exists(root.resolve(".jcodebuddy/cache/watch-fs-meta")),
                "the W5a location is not re-created either");
    }

    @Test
    void theDaemonsTrioSeedsTheStateAndIsNeverRewritten(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path source = source(root);
        Path state = root.resolve(".fs-meta");
        publish(root, state, "src/A.java");
        byte[] before = Files.readAllBytes(state.resolve("files.json"));

        List<WatchedRegeneration.Pass> seen = new ArrayList<>();
        WatchedRegeneration watcher = watcher(root, seen);
        assertEquals(1, watcher.seededGeneration(), "the trio's generation is what the state was seeded from");
        assertArrayEquals(before, Files.readAllBytes(state.resolve("files.json")),
                "construction must not publish a new generation");

        // Same bytes, new mtime: fs-meta calls it TOUCHED, so the loop breaker must not pass.
        Files.writeString(source, "class A {}\n");
        watcher.onBatch(new WatchBatch(Set.of("src/A.java"), Set.of(), false));
        assertFalse(seen.get(seen.size() - 1).regenerated(), "a same-content save must not regenerate");
        assertArrayEquals(before, Files.readAllBytes(state.resolve("files.json")),
                "an ignored batch must not rewrite the daemon's trio");

        // A real content change: the pass runs, and the daemon's trio is still not this class's to write.
        Files.writeString(source, "class A { int value; }\n");
        WatchedRegeneration.Pass pass = watcher.onBatch(new WatchBatch(Set.of("src/A.java"), Set.of(), false));
        assertTrue(pass.regenerated(), "a content change must regenerate");
        assertArrayEquals(before, Files.readAllBytes(state.resolve("files.json")),
                "a pass must not publish the trio: the daemon is the only writer");
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, message);
    }

    @Test
    void theLegacyTrioIsAReadOnlyBaseline(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path source = source(root);
        Path legacy = root.resolve(".jcodebuddy/cache/watch-fs-meta");
        publish(root, legacy, "src/A.java");
        byte[] before = Files.readAllBytes(legacy.resolve("files.json"));

        List<WatchedRegeneration.Pass> seen = new ArrayList<>();
        WatchedRegeneration watcher = watcher(root, seen);
        watcher.start();

        assertEquals(1, watcher.seededGeneration(), "the legacy trio is still a truthful baseline");
        assertFalse(Files.exists(root.resolve(".fs-meta")), "reading the legacy baseline must not create the new one");
        assertFalse(watcher.live());

        Files.writeString(source, "class A { int value; }\n");
        assertTrue(watcher.onBatch(new WatchBatch(Set.of("src/A.java"), Set.of(), false)).regenerated());
        assertArrayEquals(before, Files.readAllBytes(legacy.resolve("files.json")),
                "the legacy location is read-only: W7b removed the last Java publish path");
    }

    @Test
    void noTrioSeedsFromTheTreeWithoutWritingAnything(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path source = source(root);
        List<WatchedRegeneration.Pass> seen = new ArrayList<>();

        WatchedRegeneration watcher = watcher(root, seen);
        assertEquals(0, watcher.seededGeneration());
        assertFalse(Files.exists(root.resolve(".fs-meta")));
        assertFalse(Files.exists(root.resolve(".jcodebuddy")), "no tool-state directory is created by the watcher");

        // The scan at construction recorded A.java, so a same-content save is a no-op...
        watcher.onBatch(new WatchBatch(Set.of("src/A.java"), Set.of(), false));
        assertFalse(seen.get(seen.size() - 1).regenerated());

        // ...while a real edit runs a pass.
        Files.writeString(source, "class A { int value; }\n");
        assertTrue(watcher.onBatch(new WatchBatch(Set.of("src/A.java"), Set.of(), false)).regenerated());
        assertFalse(Files.exists(root.resolve(".fs-meta")), "still no trio written");
    }

    @Test
    void pollAnswersOneShotWithoutADaemon(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path source = source(root);
        List<WatchedRegeneration.Pass> seen = new ArrayList<>();
        WatchedRegeneration watcher = watcher(root, seen);

        assertFalse(watcher.poll().regenerated(), "nothing changed since construction");

        Files.writeString(source, "class A { int value; }\n");
        WatchedRegeneration.Pass changed = watcher.poll();
        assertTrue(changed.regenerated(), "the read-only poll sees the content change");
        assertEquals(Set.of("src/A.java"), changed.triggeringFiles());

        assertFalse(watcher.poll().regenerated(), "and it is quiescent afterwards");
        assertFalse(Files.exists(root.resolve(".fs-meta")));
    }

    @Test
    void aDeletionIsAlwaysAPass(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        source(root);
        List<WatchedRegeneration.Pass> seen = new ArrayList<>();
        WatchedRegeneration watcher = watcher(root, seen);

        Files.delete(root.resolve("src/A.java"));
        WatchedRegeneration.Pass pass = watcher.onBatch(
                new WatchBatch(Set.of(), Set.of("src/A.java"), false));
        assertTrue(pass.regenerated(), "a deleted source is never a no-op");
        assertEquals(Set.of("src/A.java"), pass.triggeringFiles());
    }

    @Test
    void theDaemonSocketIsDiscoveredFromHostStateNotFromTheTrio(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        source(root);
        Path runtime = Files.createDirectories(dir.resolve("run"));
        Path config = Files.createDirectories(dir.resolve("config"));
        Files.writeString(runtime.resolve("daemon.json"), "{\"v\":1,\"pid\":1}");
        Path socket = Files.createDirectories(runtime.resolve("roots")).resolve("a1b2c3d4.sock");
        Files.writeString(socket, "");
        Path fsmeta = Files.createDirectories(config.resolve("fsmeta"));
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("id", "a1b2c3d4");
        record.put("root", root.toRealPath().toString());
        record.put("socket", socket.toString());
        Files.writeString(fsmeta.resolve("roots.json"),
                new tools.jackson.databind.ObjectMapper().writeValueAsString(
                        Map.of("version", 1, "roots", List.of(record))));

        List<WatchedRegeneration.Pass> seen = new ArrayList<>();
        WatchedRegeneration watcher = new WatchedRegeneration(root,
                List.of("**/*.java"), List.of("**/target/**"), () -> List.of(), seen::add,
                Map.of("FSMETA_RUNTIME_DIR", runtime.toString(), "FSMETA_CONFIG_HOME", config.toString()),
                true);

        assertNotNull(watcher.daemonSocket().orElse(null));
        assertEquals(socket, watcher.daemonSocket().orElseThrow());
        assertFalse(Files.exists(root.resolve(".fs-meta")),
                "discovery came from the daemon's host state, and the trio was not consulted for a socket");

        // The socket is a plain file, so the attach must fail and leave the read-only path working rather than throw.
        watcher.start();
        assertFalse(watcher.live(), "a socket that is not a listening daemon cannot deliver events");
        assertFalse(watcher.attachProblem().isEmpty(), "the failure must say what could not be attached");
        assertFalse(Files.exists(root.resolve(".fs-meta")));
    }

    @Test
    void theSeededTrioStillValidatesAfterAPass(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path source = source(root);
        Path state = root.resolve(".fs-meta");
        publish(root, state, "src/A.java");
        List<WatchedRegeneration.Pass> seen = new ArrayList<>();
        WatchedRegeneration watcher = watcher(root, seen);

        Files.writeString(source, "class A { int value; }\n");
        assertTrue(watcher.onBatch(new WatchBatch(Set.of("src/A.java"), Set.of(), false)).regenerated());
        FsMetaLoad load = FsMetaPair.loadIfPresent(state);
        assertNotNull(load);
        assertTrue(load.ok(), "the untouched daemon trio still validates: " + load.describe());
    }
}
