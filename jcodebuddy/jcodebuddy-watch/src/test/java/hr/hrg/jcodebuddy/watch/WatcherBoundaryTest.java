package hr.hrg.jcodebuddy.watch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W7's boundary, asserted rather than described: the watcher and SSE event paths are fs-meta daemon clients, and
 * no production Java starts a {@code WatchService}. The one accepted exception is the entity build cache, which is
 * a one-shot derived state, not watcher state.
 *
 * <p>This test exists because W7 was a migration that ended with three files deleted and several consumers moved;
 * a grep-shaped guard is the difference between "we did it" and "the next change may undo it". It reads main
 * sources only, so a test that deliberately uses a Java watcher is not confused with production.</p>
 */
class WatcherBoundaryTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();

    private static final List<String> PRODUCTION_SOURCE_ROOTS = List.of(
            "jcodebuddy/jcodebuddy-watch/src/main/java",
            "jcodebuddy/jcodebuddy-agent/src/main/java",
            "project-automation/src/main/java",
            "webview/core/webviewd/src/main/java",
            "hipster-entity/hipster-entity-tooling/src/main/java");

    @Test
    void noProductionJavaStartsAWatchService() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : productionSources()) {
            String text = Files.readString(source);
            if (text.contains("newWatchService(") || text.contains("import java.nio.file.WatchService")) {
                offenders.add(relative(source));
            }
        }
        assertEquals(List.of(), offenders,
                "W7: the watcher and SSE paths are fs-meta daemon clients; no production code may start a WatchService");
    }

    @Test
    void theEntityBuildCacheIsTheOnlyProductionTrioWriter() throws IOException {
        List<String> writers = new ArrayList<>();
        for (Path source : productionSources()) {
            if (Files.readString(source).contains("FsMetaWriter.publish(")) {
                writers.add(relative(source));
            }
        }
        assertEquals(List.of("hipster-entity/hipster-entity-tooling/src/main/java/"
                        + "hr/hrg/hipster/entity/tooling/EntityBuildState.java"), writers,
                "W7: watcher state is daemon-owned; the only accepted Java trio writer is the one-shot "
                        + "entity build cache");
    }

    @Test
    void webviewdDependsOnTheDaemonClientAndFileWatchIsGone() throws IOException {
        assertTrue(Files.readString(REPO.resolve("webview/core/webviewd/pom.xml"))
                        .contains("<artifactId>jcodebuddy-watch</artifactId>"),
                "webviewd's SSE stream must depend on jcodebuddy-watch");
        assertFalse(Files.exists(REPO.resolve(
                        "jcodebuddy/jcodebuddy-watch/src/main/java/hr/hrg/jcodebuddy/watch/FileWatch.java")),
                "FileWatch had no production consumer after W7c and is deleted");
    }

    private static List<Path> productionSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String root : PRODUCTION_SOURCE_ROOTS) {
            Path dir = REPO.resolve(root);
            assertTrue(Files.isDirectory(dir), "the production source root exists: " + root);
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(path -> path.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .forEach(sources::add);
            }
        }
        return sources;
    }

    private static String relative(Path file) {
        return REPO.relativize(file).toString().replace('\\', '/');
    }
}
