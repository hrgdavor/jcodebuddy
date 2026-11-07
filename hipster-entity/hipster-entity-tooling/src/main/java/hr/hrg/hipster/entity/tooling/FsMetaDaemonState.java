package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.engine.JcodebuddyDirectory;
import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.PathFilter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link EntityBuildState.Daemon}: the fs-meta daemon, found by ancestor discovery, subscribed
 * through the protocol's own subfolder filter, replayed and acknowledged.
 *
 * <p>It is deliberately thin, because the rules it implements are fs-meta's, not this module's:</p>
 * <ul>
 *   <li><strong>Discovery</strong> is {@link FsMetaDaemonDiscovery#discoverAncestor(Path)}: the deepest served
 *       ancestor of the source root. That is the project root a person registered; the module's cache never
 *       adds a root of its own.</li>
 *   <li><strong>Filtering</strong> is the protocol's {@code SUBSCRIBE} include/exclude patterns
 *       ({@code FORMAT.socket.md}), not a second grammar: the include is the source root relative to the daemon
 *       root, {@code module/src/main/java/**}, and the daemon does the filtering server-side. A change outside
 *       that subfolder is never sent, which is what lets several modules share one daemon without each one
 *       waking for its neighbours' edits. The implementation's live consumer path still refuses the extended
 *       form (the filter exists only in {@code server.zig}), so the client sends it first and falls back to the
 *       pass-all form plus {@link PathFilter} — the same wildmatch — reporting the gap rather than inventing a
 *       second syntax.</li>
 *   <li><strong>A replay</strong> reads the generation the daemon reports at subscribe time and drains batches
 *       up to it. The names arrive workspace-relative, so they are relativized to the source root before they
 *       are returned — the same paths {@code EntityMetadataGenerator} uses.</li>
 *   <li><strong>Acknowledgment</strong> waits for the daemon to publish the pass's writes: the head is polled
 *       until the burst has settled, and that generation is recorded. No write is needed for an unchanged tree
 *       (a rewrite of identical bytes is {@code TOUCHED} and produces no batch), which is why a head that does
 *       not advance is still a truthfully recorded baseline of an unchanged tree.</li>
 * </ul>
 *
 * <p>Nothing here starts a watcher, schedules a pass or writes a trio. The daemon is the only watcher and the
 * only writer; this class is a client, exactly as {@code FORMAT.socket.md} requires.</p>
 */
final class FsMetaDaemonState implements EntityBuildState.Daemon {

    /** How long {@link #acknowledge} may take before it settles for what it has observed. */
    static final long ACK_TIMEOUT_MS = 5_000;

    /** How often the daemon head is polled while acknowledging. */
    static final long ACK_POLL_MS = 50;

    /** How long the head must stay unchanged before it is recorded as the settled generation. */
    static final long ACK_SETTLE_MS = 300;

    /**
     * Tool state that must never re-enter the loop, expressed as the protocol's exclude pattern. It is a
     * wildmatch at any depth, the same rule {@code PathFilter} uses; the include already narrows the stream to
     * the source root, so this only removes a `.jcodebuddy` that happens to live inside it.
     */
    private static final List<String> EXCLUDES = List.of(
            "**/" + JcodebuddyDirectory.DIR + "/**");

    private final Path sourceRoot;
    private final Map<String, String> environment;
    private final boolean windows;

    /** Whether this daemon applies the protocol's filter; cleared once it refuses the extended SUBSCRIBE. */
    private volatile boolean daemonFilters = true;

    /** The state for {@code sourceRoot}, reading this process's environment. */
    FsMetaDaemonState(Path sourceRoot) {
        this(sourceRoot, System.getenv(), isWindows());
    }

    /**
     * The injectable form: a test (or a container) supplies the {@code FSMETA_*} environment and the platform
     * rules explicitly, so discovery never reads the machine's real root list.
     */
    FsMetaDaemonState(Path sourceRoot, Map<String, String> environment, boolean windows) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("a source root is required");
        }
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.environment = environment;
        this.windows = windows;
    }

    @Override
    public FsMetaDaemonDiscovery.Attach discover(Path path) {
        return FsMetaDaemonDiscovery.discoverAncestor(sourceRoot, environment, windows);
    }

    /** The daemon's current head, read from the greeting of a subscription that starts at the head. */
    @Override
    public long head(FsMetaDaemonDiscovery.Location location) throws IOException {
        // The plain form on purpose: the head tells nothing about which files changed, and a daemon that
        // predates the protocol's filter refuses the extended subscribe only after it has already sent its
        // HELLO — so a filtered form here would pay a refusal on every poll of an acknowledgment.
        try (FsMetaClient client = FsMetaClient.connect(location.socket(), FsMetaClient.LATEST)) {
            return client.serverGeneration();
        }
    }

    /**
     * Replays every generation after {@code fromBatch}, up to the head the daemon reports at subscribe time,
     * with the source-root filter on the server side.
     *
     * <p>A protocol refusal is propagated: {@code batch-too-old} and {@code batch-gap} mean the change set is
     * incomplete, and the caller must fall back to a full pass rather than read an empty replay as "nothing
     * changed".</p>
     */
    @Override
    public Replay replay(FsMetaDaemonDiscovery.Location location, long fromBatch) throws IOException {
        String prefix = sourcePrefix(location.root());
        if (daemonFilters) {
            try {
                return replayThrough(location, fromBatch, prefix, null);
            } catch (FsMetaClient.ProtocolException refusal) {
                if (!filterUnsupported(refusal)) {
                    throw refusal;
                }
                // See the class javadoc: the daemon's live path predates the protocol's filter. Its refusal is
                // read only when the first batch is read (the HELLO already arrived), so the retry happens here.
                daemonFilters = false;
            }
        }
        return replayThrough(location, fromBatch, prefix, localFilter(location));
    }

    /**
     * One subscription's replay; {@code fallbackFilter} is {@code null} when the daemon applies the filter, and
     * the same wildmatch patterns applied locally for a daemon that does not.
     */
    private Replay replayThrough(FsMetaDaemonDiscovery.Location location, long fromBatch, String prefix,
                                 PathFilter fallbackFilter) throws IOException {
        try (FsMetaClient client = fallbackFilter == null
                ? FsMetaClient.connect(location.socket(), fromBatch, includes(location), EXCLUDES)
                : FsMetaClient.connect(location.socket(), fromBatch)) {
            // `replayUntilSettled`, not "up to the greeting": a root that was asleep publishes its wake scan
            // after the greeting, and stopping at the greeted generation would miss a change made while the
            // daemon slept — the one wrong answer a cache may never give.
            FsMetaClient.Replay replay = client.replayUntilSettled(fromBatch);
            List<FsMetaClient.Change> changes = new ArrayList<>();
            for (FsMetaClient.Change change : replay.changes()) {
                if (fallbackFilter != null && !fallbackFilter.shouldInclude(change.name())) {
                    continue;
                }
                FsMetaClient.Change sourceRelative = relativize(change, prefix);
                if (sourceRelative != null) {
                    changes.add(sourceRelative);
                }
            }
            return new Replay(replay.head(), changes);
        }
    }

    /**
     * Whether the refusal is the pre-filter daemon's "I only read a u64" answer.
     *
     * <p>{@code FORMAT.socket.md} defines the extended {@code SUBSCRIBE} (include/exclude patterns) and
     * {@code server.zig} implements it for {@code fsmeta serve}; the daemon's live consumer path
     * ({@code daemon/root.zig}) still accepts only the 8-byte form and answers the extended one with
     * {@code ERROR protocol: expected SUBSCRIBE with a u64 from_batch}. That is a missing server-side capability,
     * not a client mistake: the client sends the documented form first and degrades to the pass-all form plus
     * {@link PathFilter} — the same wildmatch, not a second grammar — so the decision stays correct until the
     * daemon ships its own filter. The text check can go when no deployed daemon refuses the extended form; the
     * protocol's numeric table has no "filter unsupported" code to branch on.</p>
     */
    private static boolean filterUnsupported(FsMetaClient.ProtocolException refusal) {
        return refusal.code() == FsMetaClient.ERROR_PROTOCOL
                && refusal.getMessage() != null
                && refusal.getMessage().contains("expected SUBSCRIBE with a u64 from_batch");
    }

    /** The same patterns the server would apply, for the pre-filter daemon fallback. */
    private PathFilter localFilter(FsMetaDaemonDiscovery.Location location) {
        return PathFilter.of(includes(location), EXCLUDES);
    }

    /**
     * Waits until the burst the pass started has settled, and returns the generation to record.
     *
     * <p>The first read is the {@code base} head taken after the pass; a pass that changed content must produce
     * a batch, so an advance past {@code base} is seen and its last generation recorded. A pass that rewrote
     * identical bytes produces no batch at all, so {@code base} itself is the truthful baseline and is returned
     * after the settle window. If the head cannot be read on every attempt the failure is propagated, and the
     * caller records no baseline — a guessed one could hide the next run.</p>
     */
    @Override
    public long acknowledge(FsMetaDaemonDiscovery.Location location, long base) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACK_TIMEOUT_MS);
        long observed = base;
        long stableSince = System.nanoTime();
        boolean observedAny = false;
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                long head = head(location);
                observedAny = true;
                long now = System.nanoTime();
                if (head != observed) {
                    observed = head;
                    stableSince = now;
                } else if (now - stableSince >= TimeUnit.MILLISECONDS.toNanos(ACK_SETTLE_MS)) {
                    return observed;
                }
            } catch (IOException failure) {
                lastFailure = failure;
            }
            try {
                Thread.sleep(ACK_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return observed;
            }
        }
        if (!observedAny && lastFailure != null) {
            throw lastFailure;
        }
        return observed;
    }

    /**
     * The include patterns that narrow the daemon stream to this module's source root: the source root
     * relative to the daemon root, {@code <relative>/**}. An empty list means the source root <em>is</em> the
     * daemon root, where no narrowing is possible or needed.
     *
     * @throws IllegalArgumentException when the source root is not under the daemon root, because a pattern
     *                                  that cannot be expressed would silently match nothing
     */
    static List<String> includePatterns(Path daemonRoot, Path sourceRoot) {
        String prefix = sourcePrefix(daemonRoot, sourceRoot);
        return prefix.isEmpty() ? List.of() : List.of(prefix + "/**");
    }

    /** The source root relative to the daemon root, POSIX-separated, or empty when they are the same directory. */
    static String sourcePrefix(Path daemonRoot, Path sourceRoot) {
        Path root = canonical(daemonRoot);
        Path source = canonical(sourceRoot);
        if (root.equals(source)) {
            return "";
        }
        if (!source.startsWith(root)) {
            throw new IllegalArgumentException("the source root " + source + " is not under the daemon root "
                    + root + ", so no subfolder filter can describe it");
        }
        Path relative = root.relativize(source);
        return relative.toString().replace('\\', '/');
    }

    /** The include patterns for this state's source root. */
    private List<String> includes(FsMetaDaemonDiscovery.Location location) {
        return includePatterns(location.root(), sourceRoot);
    }

    /** The source-root prefix for this state's own root. */
    private String sourcePrefix(Path daemonRoot) {
        return sourcePrefix(daemonRoot, sourceRoot);
    }

    /**
     * One wire change as a source-root-relative one, or {@code null} when it is outside the module's
     * subfolder. The server filter should already have removed those; the guard is what keeps a filter
     * mismatch from being read as a change in this module.
     */
    static FsMetaClient.Change relativize(FsMetaClient.Change change, String prefix) {
        String name = change.name();
        if (prefix.isEmpty()) {
            return change;
        }
        String withSeparator = prefix + "/";
        if (name == null || !name.startsWith(withSeparator)) {
            return null;
        }
        return new FsMetaClient.Change(name.substring(withSeparator.length()), change.kind());
    }

    /** The canonical form discovery compared: real-pathed when it exists, absolute-normalized otherwise. */
    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException missing) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
