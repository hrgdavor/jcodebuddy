package hr.hrg.webview.webviewd;

import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/events}: server-sent events for files changing under the project, so a page can re-render
 * instead of polling ({@link hr.hrg.webview.core.EditorHost} has no opinion here — this is host work, not
 * editor work).
 *
 * <p>Frames are produced by {@link #awaitFrame(long)} rather than inside the HTTP handler, so the interesting
 * part — which changes become which frame — is testable without a socket or a sleeping test.</p>
 *
 * <p><strong>The event source is the fs-meta Zig daemon, not a Java watcher.</strong> {@code webviewd} discovers
 * the daemon that serves the project through {@link FsMetaDaemonDiscovery} and subscribes with
 * {@link FsMetaDaemonWatch}. There is no {@link java.nio.file.WatchService} fallback: fs-meta is a hard
 * requirement for the live stream. {@link #of(Path)} fails with an actionable message when no daemon is
 * attached, and the HTTP handler answers {@code 503} rather than pretending a stream that cannot update.</p>
 *
 * <p><strong>Exact root.</strong> Discovery asks for the daemon serving exactly the project directory; a project
 * opened as a subdirectory of a served root gets no stream. For a multi-root project, point the host at a root, or
 * use the ancestor-plus-project-filter mode discussed in {@code doc/architecture/fs-meta-roots.md}.</p>
 *
 * <p>Noise control is explicit: the daemon subscription filters the directories a project generates
 * ({@code .git}, {@code .jcodebuddy}, {@code node_modules}, {@code target}, …), because a page cares that its
 * sources changed, not that a build wrote a hundred class files. One frame per batch, not one per event: a save
 * that touches a dozen files is one thing that happened, and a page re-renders once.</p>
 */
final class ProjectEventStream implements AutoCloseable {

    /** Directories a page never wants noise from. Kept as one list for the daemon filter and the frame guard. */
    private static final List<String> IGNORED_PATTERNS = List.of(
            ".git/**", "**/.git/**",
            ".jcodebuddy/**", "**/.jcodebuddy/**",
            ".gradle/**", "**/.gradle/**",
            "node_modules/**", "**/node_modules/**",
            "target/**", "**/target/**",
            "build/**", "**/build/**",
            "out/**", "**/out/**",
            ".idea/**", "**/.idea/**");

    /** The daemon-side filter; the same paths are guarded again when a frame is built. */
    private static final PathFilter FILTER = PathFilter.of(List.of("**/*"), IGNORED_PATTERNS);

    private final Path project;
    private final FsMetaDaemonWatch watch;
    private final BlockingQueue<WatchBatch> batches;

    private ProjectEventStream(Path project, FsMetaDaemonWatch watch, BlockingQueue<WatchBatch> batches) {
        this.project = project;
        this.watch = watch;
        this.batches = batches;
    }

    /**
     * Attach to the fs-meta daemon serving {@code project} and start its live subscription.
     *
     * @throws IOException when no daemon is attached, or when the subscription cannot be opened; the message
     *                     names the exact {@code fsmeta daemon start} / {@code fsmeta root add} setup
     */
    static ProjectEventStream of(Path project) throws IOException {
        return attach(project, FsMetaDaemonDiscovery.discover(project));
    }

    /** The attach half, separately callable so a daemon integration test can inject its own environment. */
    static ProjectEventStream attach(Path project, FsMetaDaemonDiscovery.Attach attach) throws IOException {
        if (!attach.attached()) {
            throw new IOException("the fs-meta daemon is required for the live event stream: "
                    + attach.problem() + " (run `fsmeta daemon start` and `fsmeta root add "
                    + project.toAbsolutePath().normalize() + "`)");
        }
        BlockingQueue<WatchBatch> batches = new LinkedBlockingQueue<>();
        FsMetaDaemonWatch watch = new FsMetaDaemonWatch(FILTER, attach.location().socket(),
                FsMetaClient.LATEST, batches::offer);
        try {
            watch.start();
        } catch (IOException failed) {
            watch.close();
            throw new IOException("could not subscribe to the fs-meta daemon at "
                    + attach.location().socket() + ": " + failed.getMessage(), failed);
        }
        return new ProjectEventStream(project, watch, batches);
    }

    /**
     * A test seam: the same frame conversion, with batches supplied by the caller and no daemon subscription.
     * This is not a second production event source — {@link #of(Path)} is the only production constructor.
     */
    static ProjectEventStream forTesting(Path project) {
        return new ProjectEventStream(project, null, new LinkedBlockingQueue<>());
    }

    /** Offer a batch to a {@link #forTesting(Path)} stream. */
    void offer(WatchBatch batch) {
        batches.offer(batch);
    }

    /**
     * The next frame to send: a {@code change} event naming the paths that moved, or a comment line when the
     * timeout passes with nothing to report. Never null — a stream that produced nothing would look closed.
     */
    String awaitFrame(long timeoutMillis) throws InterruptedException {
        WatchBatch batch = batches.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (batch == null) {
            return ": keep-alive\n\n";
        }
        Set<String> changed = new LinkedHashSet<>();
        if (batch.overflow()) {
            // The daemon refused a replay; the page must re-render everything rather than miss a change.
            changed.add("*");
        } else {
            List<String> paths = new ArrayList<>(batch.changed());
            paths.addAll(batch.deleted());
            for (String path : paths) {
                String relative = path.replace('\\', '/').replaceFirst("^/+", "");
                if (FILTER.shouldInclude(relative)) {
                    changed.add(relative);
                }
            }
        }
        if (changed.isEmpty()) {
            return ": keep-alive\n\n";
        }
        return "event: change\ndata: " + frameData(changed) + "\n\n";
    }

    private static String frameData(Set<String> paths) {
        StringBuilder json = new StringBuilder("{\"paths\":[");
        boolean first = true;
        for (String path : paths) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(path.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append("]}").toString();
    }

    @Override
    public void close() {
        if (watch != null) {
            watch.stop();
        }
        batches.clear();
    }
}
