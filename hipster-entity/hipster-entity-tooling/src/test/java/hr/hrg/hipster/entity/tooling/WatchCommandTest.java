package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.PathFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The parts of {@link WatchCommand} that decide what is watched and where it attaches, without a native daemon.
 *
 * <p>The loop's ordering is {@link EntityWatchLoopTest}'s subject; this class pins the two boundaries a wrong
 * implementation would silently move:</p>
 * <ul>
 *   <li>no daemon is an <strong>error with an actionable reason</strong>, not a fallback watcher;</li>
 *   <li>the subscription filter is the module source root and {@code .java} only, with {@code .jcodebuddy}
 *       excluded — wildmatch, the repository's only wildcard syntax (DEC-051).</li>
 * </ul>
 *
 * <p>The real-daemon version of both is {@code EntityWatchDaemonTest}, which is skipped without
 * {@code FSMETA_BIN}.</p>
 */
@Tag("generator")
class WatchCommandTest {

    @Test
    void noDaemonIsAnActionableError() throws Exception {
        EntityBuildState.Daemon detached = new EntityBuildState.Daemon() {
            @Override
            public FsMetaDaemonDiscovery.Attach discover(Path sourceRoot) {
                return new FsMetaDaemonDiscovery.Attach(null,
                        "the fsmeta root list " + sourceRoot + "/roots.json has no record for the project root");
            }

            @Override
            public long head(FsMetaDaemonDiscovery.Location location) throws IOException {
                throw new IOException("not attached");
            }

            @Override
            public Replay replay(FsMetaDaemonDiscovery.Location location, long fromBatch) throws IOException {
                throw new IOException("not attached");
            }

            @Override
            public long acknowledge(FsMetaDaemonDiscovery.Location location, long base) throws IOException {
                throw new IOException("not attached");
            }
        };
        WatchCommand command = new WatchCommand(detached, Path.of("module/src/main/java"),
                full -> { }, true, 10);

        WatchCommand.NoDaemonException refusal = Assertions.assertThrows(
                WatchCommand.NoDaemonException.class, command::run);

        Assertions.assertTrue(refusal.getMessage().contains("fsmeta root list"), refusal.getMessage());
        Assertions.assertFalse(command.watching(), "a refused command never watches");
    }

    @Test
    void theFilterIsTheSourceRootJavaFilesAndNeverJcodebuddy() {
        PathFilter filter = WatchCommand.sourceRootFilter(
                Path.of("/project"), Path.of("/project/module/src/main/java"));

        Assertions.assertTrue(filter.shouldInclude("module/src/main/java/build/entity/Thing.java"),
                "a source under the module root is the pass's input");
        Assertions.assertFalse(filter.shouldInclude("module/src/main/java/build/entity/Thing.class"),
                "only .java changes can change generated Java");
        Assertions.assertFalse(filter.shouldInclude("module/src/main/java/build/entity/README.md"));
        Assertions.assertFalse(filter.shouldInclude("module/src/test/java/build/entity/Thing.java"),
                "a sibling subfolder is not this module's source root");
        Assertions.assertFalse(filter.shouldInclude("other/src/main/java/Thing.java"),
                "another module under the same project root is not this watch's input");
        Assertions.assertFalse(filter.shouldInclude("module/src/main/java/.jcodebuddy/Thing.java"),
                "tool state inside the source root must never re-enter the loop");
        Assertions.assertFalse(filter.shouldInclude("module/src/main/java/.jcodebuddy/cache/x.java"));
    }

    @Test
    void aSourceRootThatIsTheDaemonRootStillFiltersJavaOnly() {
        PathFilter filter = WatchCommand.sourceRootFilter(
                Path.of("/project"), Path.of("/project"));

        Assertions.assertTrue(filter.shouldInclude("build/entity/Thing.java"),
                "with no prefix the include is **/*.java, which matches at any depth");
        Assertions.assertFalse(filter.shouldInclude("build/entity/Thing.json"));
        Assertions.assertFalse(filter.shouldInclude(".jcodebuddy/metadata/entity/Thing.java"));
    }
}
