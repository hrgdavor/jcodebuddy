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
 * no production Java starts a {@code WatchService}. Since Design B there is no accepted exception for the trio
 * either: the entity generator's incremental state is a daemon-generation marker
 * ({@code .jcodebuddy/cache/entity-build/run.json}), not a Java-written trio, so the daemon is the only writer of
 * watcher state anywhere in this reactor.
 *
 * <p>This test exists because W7 was a migration that ended with three files deleted and several consumers moved;
 * a grep-shaped guard is the difference between "we did it" and "the next change may undo it". It reads main
 * sources only, so a test that deliberately uses a Java writer as a fixture is not confused with production.</p>
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

    /**
     * Design B: no production Java publishes an {@code fs-meta} trio at all.
     *
     * <p>The entity build cache used to be the one accepted exception. Its incremental state is now a
     * daemon-generation marker, so the exception is gone and the guard is absolute: the daemon is the only
     * writer of the tree state every consumer reads. Both the call and the import are checked, because a module
     * that imports the writer but has not called it yet is the state this rule is about to become wrong in.</p>
     */
    @Test
    void noProductionJavaPublishesAnFsMetaTrio() throws IOException {
        List<String> writers = new ArrayList<>();
        for (Path source : productionSources()) {
            String text = Files.readString(source);
            if (text.contains("FsMetaWriter.publish(") || text.contains("import hr.hrg.fsmeta.FsMetaWriter")) {
                writers.add(relative(source));
            }
        }
        assertEquals(List.of(), writers,
                "Design B: the fs-meta daemon is the only trio writer; no production Java may publish one");
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
