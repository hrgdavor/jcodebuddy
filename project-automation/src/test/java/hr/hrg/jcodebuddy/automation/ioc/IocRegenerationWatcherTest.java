package hr.hrg.jcodebuddy.automation.ioc;

import hr.hrg.jcodebuddy.automation.watch.WatchedRegeneration;
import hr.hrg.watch2.core.ChangeSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The watch half of step 3.9: the pass run on every save.
 *
 * <p>Two facts decide whether a watcher is usable, and both are asserted here rather than argued: a real content
 * change regenerates, and <strong>the watcher's own output does not start another pass</strong> — the loop a
 * generator writing into the tree it watches would otherwise create.</p>
 */
class IocRegenerationWatcherTest {

    /** The same minimal-but-real context the pass test uses: a bean on the context, its factory on the module. */
    private static Path tree(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("src/ioc/fixture"));
        Files.writeString(root.resolve("AppModule.java"), """
                package ioc.fixture;

                interface AppModule {
                    default Greeting buildGreeting() {
                        return new Greeting("hello");
                    }
                }
                """);
        Files.writeString(root.resolve("Greeting.java"), """
                package ioc.fixture;

                class Greeting {
                    private final String text;

                    Greeting(String text) {
                        this.text = text;
                    }

                    String text() {
                        return text;
                    }
                }
                """);
        Files.writeString(root.resolve("AppContext.java"), """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.HipsterContext;

                @HipsterContext
                public interface AppContext extends AppModule {
                    Greeting greeting();
                }
                """);
        return dir;
    }

    private static ChangeSet changed(Path... files) {
        return new ChangeSet(Arrays.stream(files)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)), Set.of(), 0);
    }

    /**
     * A real edit: the content has to change, because a save that leaves the bytes identical is deliberately a
     * no-op — an editor rewriting a file with the same content, or a touched timestamp, must not start a pass.
     */
    private static void edit(Path file, String appended) throws Exception {
        Files.writeString(file, Files.readString(file) + appended);
    }

    private static IocRegenerationWatcher watcherOf(Path sourceRoot, Path moduleRoot) {
        return new IocRegenerationWatcher(IocRegenerationWatcher.Config.of(sourceRoot, moduleRoot));
    }

    @Test
    void anEditToAContextRegenerates(@TempDir Path dir) throws Exception {
        Path sourceRoot = tree(dir);
        Path implementation = sourceRoot.resolve("src/ioc/fixture/AppContextImpl.java");
        IocRegenerationWatcher watcher = watcherOf(sourceRoot, dir);

        Assertions.assertFalse(Files.exists(implementation),
                "nothing is generated until something changes");

        edit(sourceRoot.resolve("src/ioc/fixture/AppContext.java"), "\n// a genuine edit\n");
        WatchedRegeneration.Pass pass = watcher.onBatch(changed(sourceRoot.resolve("src/ioc/fixture/AppContext.java")));

        Assertions.assertTrue(pass.regenerated(), "a real content change must regenerate: " + pass.divergences());
        Assertions.assertEquals(Set.of("src/ioc/fixture/AppContext.java"), pass.triggeringFiles(),
                "and it says which file caused it");
        Assertions.assertTrue(Files.exists(implementation), "the implementation exists afterwards");
        Assertions.assertEquals(1, watcher.passCount());
    }

    /**
     * The loop breaker, which is the only reason a generator may write into the tree it watches: the files it just
     * wrote hash to what the last pass left, so the batch that mentions them is recognised as that pass's echo.
     */
    @Test
    void theWatchersOwnOutputDoesNotStartAnotherPass(@TempDir Path dir) throws Exception {
        Path sourceRoot = tree(dir);
        IocRegenerationWatcher watcher = watcherOf(sourceRoot, dir);

        edit(sourceRoot.resolve("src/ioc/fixture/AppContext.java"), "\n// a genuine edit\n");
        Assertions.assertTrue(watcher.onBatch(changed(sourceRoot.resolve("src/ioc/fixture/AppContext.java")))
                .regenerated(), "the first pass runs");
        Assertions.assertEquals(1, watcher.passCount());

        List<Path> generated;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            generated = files.filter(path -> path.getFileName().toString().endsWith("Impl.java")).toList();
        }
        Assertions.assertFalse(generated.isEmpty(), "the pass must have written something to echo");

        WatchedRegeneration.Pass echo = watcher.onBatch(new ChangeSet(
                generated.stream().map(path -> path.toAbsolutePath().normalize())
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                Set.of(), 0));

        Assertions.assertFalse(echo.regenerated(),
                "the echo of its own output must be ignored, or the watcher never becomes quiescent: " + echo);
        Assertions.assertEquals(1, watcher.passCount(),
                "exactly one pass ran: the echo did not start a second");
    }

    /** A deletion always matters: the generator may need to remove or recreate a file. */
    @Test
    void aDeletionIsActedOn(@TempDir Path dir) throws Exception {
        Path sourceRoot = tree(dir);
        IocRegenerationWatcher watcher = watcherOf(sourceRoot, dir);

        // A path that no longer exists IS the deletion, and it is judged against the snapshot taken at construction:
        // the file was there, so its absence is a change the pass must see.
        Path removed = sourceRoot.resolve("src/ioc/fixture/Greeting.java");
        Files.delete(removed);
        WatchedRegeneration.Pass pass = watcher.onBatch(new ChangeSet(Set.of(), Set.of(removed.toAbsolutePath()), 0));

        Assertions.assertTrue(pass.regenerated(), "a deleted file is never a no-op");
    }
}
