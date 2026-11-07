package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.engine.JcodebuddyDirectory;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@code --watch} command (plan {@code hipster-entity-mvp.md} E11): one daemon attachment, one initial pass,
 * then the loop in {@link EntityWatchLoop}.
 *
 * <h3>The daemon is required, and it is discovered, not started</h3>
 * <p>{@code --watch} asks {@link FsMetaDaemonDiscovery#discoverAncestor(Path)} for the deepest served root above
 * the source root — the same lookup {@link EntityBuildState} uses for the incremental baseline, so the watcher
 * and the baseline cannot disagree about which daemon serves the project. Without a daemon it fails with a
 * message naming the commands that start one; it never falls back to an in-process {@code WatchService} or a
 * polling loop. That fallback would be a second watcher, which is exactly what E11 forbids.</p>
 *
 * <h3>The subscription is limited to the module source root</h3>
 * <p>The include pattern is the source root relative to the daemon root, narrowed to {@code **}{@code /*.java},
 * and {@code .jcodebuddy} is excluded. A project-root daemon therefore serves several modules without each watch
 * process waking for a neighbour's edit or for the generator's own metadata writes.</p>
 *
 * <h3>The loop guard is a subscription boundary, not a second tracker</h3>
 * <p>After each pass the loop stops its subscription, waits for the daemon head to settle (via the same
 * {@link EntityBuildState.Daemon#acknowledge} the incremental state uses), and subscribes again at
 * {@code LATEST}. The pass's generated files are therefore behind the new subscription's head and cannot
 * trigger another pass. No content-hash table, state directory or dependency graph is added: the only state is
 * {@link EntityBuildState}'s existing marker, written by the pass itself.</p>
 */
final class WatchCommand implements AutoCloseable {

    /** The default coalescing window; the same order of magnitude as the retired project-automation watcher. */
    static final long DEFAULT_DEBOUNCE_MS = EntityWatchLoop.DEFAULT_DEBOUNCE_MS;

    private final EntityBuildState.Daemon daemon;
    private final Path sourceRoot;
    private final EntityWatchLoop.PassRunner passRunner;
    private final boolean initialIncremental;
    private final long debounceMs;

    private volatile EntityWatchLoop loop;
    private volatile boolean closed;

    /**
     * @param daemon            the daemon seam: discovery, the head to settle against, and the same instance the
     *                          pass uses for its baseline, so both read one root
     * @param sourceRoot        the tree the pass reads and writes
     * @param passRunner        one generator pass; {@code true} requests the normal pass
     * @param initialIncremental whether the first pass uses {@code --incremental} semantics
     * @param debounceMs        quiet window before a burst becomes one pass
     */
    WatchCommand(EntityBuildState.Daemon daemon, Path sourceRoot,
                 EntityWatchLoop.PassRunner passRunner, boolean initialIncremental, long debounceMs) {
        if (daemon == null || sourceRoot == null || passRunner == null) {
            throw new IllegalArgumentException("a watch command needs a daemon, a source root and a pass runner");
        }
        this.daemon = daemon;
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.passRunner = passRunner;
        this.initialIncremental = initialIncremental;
        this.debounceMs = debounceMs <= 0 ? DEFAULT_DEBOUNCE_MS : debounceMs;
    }

    /**
     * Attaches, runs the initial pass, and blocks until {@link #close()} or a transport failure.
     *
     * @throws NoDaemonException when no fs-meta daemon serves the project root; the message is the reason
     *                           discovery gave, which names the root and the command that registers it
     */
    void run() throws IOException {
        FsMetaDaemonDiscovery.Attach attach = daemon.discover(sourceRoot);
        if (attach == null || !attach.attached()) {
            throw new NoDaemonException(attach == null ? "discovery produced no result" : attach.problem());
        }
        FsMetaDaemonDiscovery.Location location = attach.location();
        PathFilter filter = sourceRootFilter(location.root(), sourceRoot);

        EntityWatchLoop.PassRunner loggingPass = full -> {
            System.out.println("[watch] " + (full ? "full" : "incremental") + " pass over " + sourceRoot);
            passRunner.run(full);
        };
        // The same waiting rule the incremental state uses after a pass (FsMetaDaemonState.acknowledge polls
        // until the burst stops moving), applied to head-to-head so the resubscription is behind the writes.
        EntityWatchLoop.Settler settler = () -> {
            long head = daemon.head(location);
            daemon.acknowledge(location, head);
        };
        EntityWatchLoop.Subscriptions subscriptions = (fromBatch, listener) ->
                new DaemonSubscription(filter, location.socket(), fromBatch, listener);

        EntityWatchLoop created = new EntityWatchLoop(loggingPass, subscriptions, settler, debounceMs);
        loop = created;
        if (closed) {
            created.close();
            return;
        }
        System.out.println("[watch] daemon " + location.socket() + " serving " + location.root());
        System.out.println("[watch] watching " + sourceRoot
                + " (initial pass " + (initialIncremental ? "--incremental" : "full")
                + "; Ctrl+C stops)");
        try {
            created.run(!initialIncremental);
        } finally {
            loop = null;
        }
    }

    /**
     * The filter a module watch applies to the daemon's workspace-relative names: only {@code .java} files under
     * the source root, and never a {@code .jcodebuddy} subtree.
     *
     * <p>Wildmatch, not a glob dialect (DEC-051): the include is the same source-root prefix
     * {@link FsMetaDaemonState} uses for the incremental replay, plus {@code /**}{@code /*.java}; the exclude is
     * the protocol's tool-state pattern.</p>
     */
    static PathFilter sourceRootFilter(Path daemonRoot, Path sourceRoot) {
        String prefix = FsMetaDaemonState.sourcePrefix(daemonRoot, sourceRoot);
        List<String> includes = prefix.isEmpty()
                ? List.of("**/*.java")
                : List.of(prefix + "/**/*.java");
        List<String> excludes = List.of("**/" + JcodebuddyDirectory.DIR + "/**");
        return PathFilter.of(includes, excludes);
    }

    /** Whether the loop is attached and receiving batches. */
    boolean watching() {
        EntityWatchLoop current = loop;
        return current != null && current.isRunning();
    }

    /** How many batch-triggered passes have run (the initial pass is not counted). */
    long passes() {
        EntityWatchLoop current = loop;
        return current == null ? 0 : current.passes();
    }

    /** How many batches were seen, including those coalesced into one pass. */
    long batchesSeen() {
        EntityWatchLoop current = loop;
        return current == null ? 0 : current.batchesSeen();
    }

    /** How many batch-triggered passes were forced to a full pass by an overflow. */
    long overflows() {
        EntityWatchLoop current = loop;
        return current == null ? 0 : current.overflows();
    }

    /** The last pass or transport error, or an empty string. */
    String lastError() {
        EntityWatchLoop current = loop;
        return current == null ? "" : current.lastError();
    }

    @Override
    public void close() {
        closed = true;
        EntityWatchLoop current = loop;
        if (current != null) {
            current.close();
        }
    }

    /** The real subscription: {@link FsMetaDaemonWatch}, which never writes a trio and never starts a watcher. */
    private static final class DaemonSubscription implements EntityWatchLoop.Subscription {

        private final FsMetaDaemonWatch watch;

        DaemonSubscription(PathFilter filter, Path socket, long fromBatch, Consumer<WatchBatch> listener) {
            this.watch = new FsMetaDaemonWatch(filter, socket, fromBatch, listener);
        }

        @Override
        public void start() throws IOException {
            watch.start();
        }

        @Override
        public void stop() {
            watch.stop();
        }

        @Override
        public boolean isRunning() {
            return watch.isRunning();
        }

        @Override
        public String lastError() {
            return watch.lastError();
        }
    }

    /** No fs-meta daemon serves the project root; the message is the actionable discovery reason. */
    static final class NoDaemonException extends IOException {

        NoDaemonException(String message) {
            super(message);
        }
    }
}
