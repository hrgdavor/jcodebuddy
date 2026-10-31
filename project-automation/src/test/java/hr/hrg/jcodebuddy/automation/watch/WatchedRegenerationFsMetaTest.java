package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.fsmeta.FsMetaLoad;
import hr.hrg.fsmeta.FsMetaPair;
import hr.hrg.watch2.core.ChangeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real-world state check for {@link WatchedRegeneration}: the loop breaker is now the fs-meta trio, not a private
 * SHA-1 map. This asserts the trio is written at construction, a same-content save is ignored (fs-meta calls it
 * {@code TOUCHED}), and a real content change runs the pass and republishes the trio.
 */
class WatchedRegenerationFsMetaTest {

    @Test
    void theLoopStateIsTheFsMetaTrioAndAContentChangeRunsThePass(@TempDir Path root) throws Exception {
        Path source = root.resolve("src/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}\n");

        List<WatchedRegeneration.Pass> seen = new java.util.ArrayList<>();
        WatchedRegeneration watcher = new WatchedRegeneration(root,
                List.of("**/*.java"),
                List.of("**/target/**"),
                50,
                () -> List.of(),
                seen::add);

        Path state = root.resolve(".jcodebuddy/cache/watch-fs-meta");
        FsMetaLoad seeded = FsMetaPair.loadIfPresent(state);
        assertNotNull(seeded, "the watcher must seed the fs-meta trio at construction");
        assertTrue(seeded.ok(), "the seeded trio must validate: " + seeded.describe());
        assertTrue(seeded.pair().contains("src/A.java"), "the watched .java file must be in the trio");

        // Same bytes, new mtime: fs-meta calls it TOUCHED, so the loop breaker must not pass.
        watcher.onBatch(new ChangeSet(Set.of(source), Set.of(), 0));
        assertFalse(seen.get(seen.size() - 1).regenerated(), "a same-content save must not regenerate");

        // A real content change: the pass runs and the trio reflects the post-pass tree.
        Files.writeString(source, "class A { int value; }\n");
        WatchedRegeneration.Pass pass = watcher.onBatch(new ChangeSet(Set.of(source), Set.of(), 0));
        assertTrue(pass.regenerated(), "a content change must regenerate");
        assertTrue(FsMetaPair.loadIfPresent(state).ok(), "the post-pass state must still validate");
    }
}
