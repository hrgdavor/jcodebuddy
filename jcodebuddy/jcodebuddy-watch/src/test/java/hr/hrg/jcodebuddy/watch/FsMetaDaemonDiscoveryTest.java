package hr.hrg.jcodebuddy.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FsMetaDaemonDiscovery} against the daemon's own host state — no daemon binary, no trio.
 *
 * <p>The rules asserted here are {@code FORMAT.daemon.md} § 1: the runtime and config directories per platform and
 * per {@code FSMETA_*} override, the record in {@code roots.json} being the authority for a root's socket, and a
 * missing daemon being a normal, explained answer rather than an exception.</p>
 *
 * <p>Lives in {@code jcodebuddy-watch} with the class it tests: discovery was promoted out of
 * {@code project-automation} when the agent became a second consumer.</p>
 */
class FsMetaDaemonDiscoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, String> env(Path runtime, Path config) {
        return Map.of("FSMETA_RUNTIME_DIR", runtime.toString(), "FSMETA_CONFIG_HOME", config.toString());
    }

    /** Writes the durable root list with one record for {@code root}, the way the daemon's registry does. */
    private static void writeRegistry(Path configHome, Path root, Path socket, String id) throws Exception {
        Path fsmeta = Files.createDirectories(configHome.resolve("fsmeta"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("root", root.toRealPath().toString());
        record.put("socket", socket.toString());
        Map<String, Object> document = Map.of("version", 1, "roots", List.of(record));
        Files.writeString(fsmeta.resolve("roots.json"), MAPPER.writeValueAsString(document));
    }

    @Test
    void aMissingRootListMeansNoDaemon(@TempDir Path dir) throws Exception {
        Path runtime = Files.createDirectories(dir.resolve("run"));
        Path config = Files.createDirectories(dir.resolve("config"));
        Path root = Files.createDirectories(dir.resolve("root"));

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(root, env(runtime, config), true);

        assertFalse(attach.attached());
        assertTrue(attach.problem().contains("no fsmeta root list"), attach.problem());
    }

    @Test
    void theRecordForThisRootNamesItsSocket(@TempDir Path dir) throws Exception {
        Path runtime = Files.createDirectories(dir.resolve("run"));
        Path config = Files.createDirectories(dir.resolve("config"));
        Path root = Files.createDirectories(dir.resolve("root"));
        Files.writeString(runtime.resolve("daemon.json"), "{\"v\":1,\"pid\":1}");
        Path socket = Files.createDirectories(runtime.resolve("roots")).resolve("a1b2c3d4.sock");
        Files.writeString(socket, "");
        writeRegistry(config, root, socket, "a1b2c3d4");

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(root, env(runtime, config), true);

        assertTrue(attach.attached(), attach.problem());
        assertNotNull(attach.location());
        assertEquals(socket, attach.location().socket());
        assertEquals("a1b2c3d4", attach.location().id());
        assertEquals(runtime, attach.location().runtimeBase());
        assertEquals(root.toRealPath(), attach.location().root());
    }

    @Test
    void aRecordWhoseSocketExistsIsACandidateEvenWithoutOurRuntimeState(@TempDir Path dir) throws Exception {
        Path runtime = Files.createDirectories(dir.resolve("run"));
        Path config = Files.createDirectories(dir.resolve("config"));
        Path root = Files.createDirectories(dir.resolve("root"));
        Path socket = Files.createDirectories(runtime.resolve("roots")).resolve("a1b2c3d4.sock");
        Files.writeString(socket, "");
        writeRegistry(config, root, socket, "a1b2c3d4");

        // No daemon.json anywhere under the runtime directory this process would compute. That is the normal shape
        // of a daemon started with an explicit --base: the record still names the absolute socket, and the connection
        // attempt — not a pid file — decides whether it is live. FORMAT.daemon.md says daemon.json is not the
        // authority on liveness, and a client that required it would refuse a perfectly good daemon.
        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(root, env(runtime, config), true);

        assertTrue(attach.attached(), attach.problem());
        assertEquals(socket, attach.location().socket());
    }

    @Test
    void aRecordWhoseSocketIsGoneIsNotAttached(@TempDir Path dir) throws Exception {
        Path runtime = Files.createDirectories(dir.resolve("run"));
        Path config = Files.createDirectories(dir.resolve("config"));
        Path root = Files.createDirectories(dir.resolve("root"));
        Files.writeString(runtime.resolve("daemon.json"), "{\"v\":1,\"pid\":1}");
        Path socket = Files.createDirectories(runtime.resolve("roots")).resolve("a1b2c3d4.sock");
        writeRegistry(config, root, socket, "a1b2c3d4");

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(root, env(runtime, config), true);

        assertFalse(attach.attached());
        assertTrue(attach.problem().contains("does not exist"), attach.problem());
    }

    @Test
    void aDifferentRootIsNotThisRootsRecord(@TempDir Path dir) throws Exception {
        Path runtime = Files.createDirectories(dir.resolve("run"));
        Path config = Files.createDirectories(dir.resolve("config"));
        Path root = Files.createDirectories(dir.resolve("root"));
        Path other = Files.createDirectories(dir.resolve("other"));
        Files.writeString(runtime.resolve("daemon.json"), "{\"v\":1,\"pid\":1}");
        Path socket = Files.createDirectories(runtime.resolve("roots")).resolve("a1b2c3d4.sock");
        Files.writeString(socket, "");
        writeRegistry(config, other, socket, "a1b2c3d4");

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(root, env(runtime, config), true);

        assertFalse(attach.attached());
        assertTrue(attach.problem().contains("has no record for"), attach.problem());
    }

    @Test
    void anUnreadableRootListIsReportedNotThrown(@TempDir Path dir) throws Exception {
        Path runtime = Files.createDirectories(dir.resolve("run"));
        Path config = Files.createDirectories(dir.resolve("config"));
        Path root = Files.createDirectories(dir.resolve("root"));
        Files.createDirectories(config.resolve("fsmeta"));
        Files.writeString(config.resolve("fsmeta").resolve("roots.json"), "{not json");

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(root, env(runtime, config), false);

        assertFalse(attach.attached());
        assertTrue(attach.problem().contains("cannot read the fsmeta root list"), attach.problem());
    }

    @Test
    void theRuntimeAndConfigDefaultsFollowTheDaemonRules() {
        Map<String, String> posix = Map.of("HOME", "/home/u", "XDG_RUNTIME_DIR", "/run/user/1000",
                "XDG_CONFIG_HOME", "/home/u/.config");
        assertEquals(Path.of("/run/user/1000/fsmeta"), FsMetaDaemonDiscovery.runtimeBase(posix, false));
        assertEquals(Path.of("/home/u/.config/fsmeta/roots.json"), FsMetaDaemonDiscovery.configFile(posix, false));

        Map<String, String> posixFallback = Map.of("HOME", "/home/u");
        assertEquals(Path.of("/home/u/.cache/fsmeta/run"), FsMetaDaemonDiscovery.runtimeBase(posixFallback, false));
        assertEquals(Path.of("/home/u/.config/fsmeta/roots.json"),
                FsMetaDaemonDiscovery.configFile(posixFallback, false));

        Map<String, String> windows = Map.of("LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local",
                "APPDATA", "C:\\Users\\u\\AppData\\Roaming");
        assertEquals(Path.of("C:\\Users\\u\\AppData\\Local").resolve("fsmeta").resolve("run"),
                FsMetaDaemonDiscovery.runtimeBase(windows, true));
        assertEquals(Path.of("C:\\Users\\u\\AppData\\Roaming").resolve("fsmeta").resolve("roots.json"),
                FsMetaDaemonDiscovery.configFile(windows, true));

        Map<String, String> overridden = Map.of("FSMETA_RUNTIME_DIR", "/scratch/run",
                "FSMETA_CONFIG_HOME", "/scratch/config");
        assertEquals(Path.of("/scratch/run"), FsMetaDaemonDiscovery.runtimeBase(overridden, false));
        assertEquals(Path.of("/scratch/config/fsmeta/roots.json"),
                FsMetaDaemonDiscovery.configFile(overridden, false));
    }

    @Test
    void aDotDotLocatorResolvesToTheRecordedRoot(@TempDir Path dir) throws Exception {
        Path runtime = Files.createDirectories(dir.resolve("run"));
        Path config = Files.createDirectories(dir.resolve("config"));
        Path root = Files.createDirectories(dir.resolve("root"));
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(runtime.resolve("daemon.json"), "{\"v\":1,\"pid\":1}");
        Path socket = Files.createDirectories(runtime.resolve("roots")).resolve("a1b2c3d4.sock");
        Files.writeString(socket, "");
        writeRegistry(config, root, socket, "a1b2c3d4");

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discover(
                root.resolve("nested").resolve(".."), env(runtime, config), false);

        assertTrue(attach.attached(), "the record stores a canonical root, so a locator resolves to it");
        assertEquals(socket, attach.location().socket());
    }
}
