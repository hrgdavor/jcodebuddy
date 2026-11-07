package hr.hrg.jcodebuddy.watch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The live event source for a consumer of the fs-meta daemon: one subscription to the daemon serving a root.
 *
 * <p>It is a transport and a policy seam, nothing more. {@link FsMetaClient} speaks {@code FORMAT.socket.md};
 * this class decides what a batch means for one consumer:</p>
 *
 * <ul>
 *   <li><strong>Batch conversion</strong> — every {@code CHANGE_ENTRY} becomes a {changed}/{deleted} relative path,
 *       filtered by the caller's {@link PathFilter}. A batch with no watched names is not delivered, so a busy
 *       monorepo does not wake a {@code .java} generator for its README.</li>
 *   <li><strong>{@code batch-too-old} / {@code batch-gap}</strong> — the protocol's two refusals are handled
 *       explicitly, never by skipping: the consumer is told the change set may be incomplete (one overflow
 *       {@link WatchBatch}, which means "run a full pass"), and the client resubscribes at the daemon's head. A
 *       silently skipped generation would be a quietly wrong index, which is the one outcome the format forbids.</li>
 *   <li><strong>Reconnect</strong> — a dropped connection resumes from the last applied generation; if the daemon
 *       cannot resume there, that is a replay refusal and takes the full-pass path above.</li>
 * </ul>
 *
 * <p>Only the daemon batches; this class never starts a watcher, never publishes a trio and never implements a
 * scheduler — {@code FORMAT.daemon.md} makes the Zig daemon the sole owner of all three. It is the watcher library's
 * own class (promoted from {@code project-automation} when the agent became a second consumer), so both the
 * regeneration loop and the agent subscribe with the same code.</p>
 */
public final class FsMetaDaemonWatch implements AutoCloseable {

    /** Stream callbacks for a consumer that wants to begin work before END_BATCH. */
    public interface BatchObserver {

        /** The no-op observer used by the four-argument constructor. */
        BatchObserver NONE = new BatchObserver() {
        };

        default void startBatch(long number, long parent) {
        }

        default void change(FsMetaClient.Change change) {
        }

        default void endBatch(WatchBatch batch) {
        }
    }

    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_DELAY_MS = 150;

    private final Path socket;
    private final PathFilter filter;
    private final Consumer<WatchBatch> listener;
    private final BatchObserver observer;

    private FsMetaClient client;
    private Thread thread;
    private volatile boolean running;

    /** The last generation this consumer applied; a reconnect resumes from here. */
    private long lastApplied;
    private long delivered;
    private long recoveries;
    private String lastError = "";
    /** Whether the last refusal already triggered a head-resubscribe, so a persistent one cannot loop. */
    private boolean recoveryUsed;

    /**
     * @param filter    what this consumer cares about; applied to every entry the daemon sends
     * @param socket    the root's consumer socket ({@code <base>/roots/<id>.sock})
     * @param fromBatch the last generation the caller's state already describes, or {@link FsMetaClient#LATEST}
     * @param listener  called on the reader thread when a watched batch arrives, and with an overflow batch when a
     *                  replay was refused and a full pass is the only safe answer
     */
    public FsMetaDaemonWatch(PathFilter filter, Path socket, long fromBatch, Consumer<WatchBatch> listener) {
        this(filter, socket, fromBatch, listener, BatchObserver.NONE);
    }

    /**
     * The streaming form: {@code observer} sees {@code START_BATCH} and each accepted {@code CHANGE_ENTRY} before
     * the complete {@link WatchBatch} is delivered to {@code listener}. The observer runs on the reader thread, so
     * it must enqueue work rather than perform a compile there.
     */
    public FsMetaDaemonWatch(PathFilter filter, Path socket, long fromBatch, Consumer<WatchBatch> listener,
                             BatchObserver observer) {
        if (socket == null) {
            throw new IllegalArgumentException("a daemon socket is required");
        }
        this.socket = socket.toAbsolutePath().normalize();
        this.filter = filter == null ? PathFilter.of(java.util.List.of(), java.util.List.of()) : filter;
        this.listener = listener == null ? batch -> { } : listener;
        this.observer = observer == null ? BatchObserver.NONE : observer;
        this.lastApplied = fromBatch;
    }

    /** Connects first, so a caller learns about an unusable socket immediately, then reads batches on its own thread. */
    public synchronized void start() throws IOException {
        if (thread != null) {
            return;
        }
        connect(lastApplied);
        running = true;
        thread = new Thread(this::loop, "jcodebuddy-fsmeta-daemon");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops reading. Safe to call twice, and safe to call without {@link #start()}. */
    public synchronized void stop() {
        running = false;
        FsMetaClient current = client;
        client = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Closing is how the blocked read is woken; there is nothing to report if the OS already failed.
            }
        }
        Thread currentThread = thread;
        thread = null;
        if (currentThread != null && currentThread != Thread.currentThread()) {
            try {
                currentThread.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** The socket this subscription is for. */
    public Path socket() {
        return socket;
    }

    /** Whether the reader thread is running. */
    public boolean isRunning() {
        return running;
    }

    /** The last generation applied, or the generation subscribed from when nothing has arrived yet. */
    public long lastAppliedBatch() {
        return lastApplied;
    }

    /** How many watched batches were delivered to the listener. */
    public long deliveredBatches() {
        return delivered;
    }

    /** How many {@code batch-too-old}/{@code batch-gap} refusals turned into a full-read recovery. */
    public long recoveries() {
        return recoveries;
    }

    /** The last transport failure, or an empty string. */
    public String lastError() {
        return lastError;
    }

    /**
     * Turns one protocol batch into the batch this consumer's filter accepts.
     *
     * <p>The wire kinds are the entry's change bits ({@code FORMAT.socket.md} § Messages), so this is a mapping and
     * not a guess: {@code ADDED}/{@code CHANGED} are paths to read, {@code DELETED} is a path that is gone. Names
     * arrive workspace-relative and {@code /}-separated already, which is what {@link PathFilter} expects.</p>
     */
    public static WatchBatch toWatchBatch(FsMetaClient.Batch batch, PathFilter filter) {
        Set<String> changed = new LinkedHashSet<>();
        Set<String> deleted = new LinkedHashSet<>();
        for (FsMetaClient.Change change : batch.changes()) {
            if (filter != null && !filter.shouldInclude(change.name())) {
                continue;
            }
            switch (change.kind()) {
                case ADDED, CHANGED -> changed.add(change.name());
                case DELETED -> deleted.add(change.name());
            }
        }
        return new WatchBatch(changed, deleted, false);
    }

    private void loop() {
        try {
            while (running) {
                FsMetaClient.Batch batch;
                try {
                    FsMetaClient current = client;
                    if (current == null) {
                        return;
                    }
                    batch = current.nextBatch(new FsMetaClient.BatchObserver() {
                        @Override
                        public void startBatch(long number, long parent) {
                            observer.startBatch(number, parent);
                        }

                        @Override
                        public void change(FsMetaClient.Change change) {
                            if (filter.shouldInclude(change.name())) {
                                observer.change(change);
                            }
                        }
                    });
                } catch (FsMetaClient.ProtocolException refusal) {
                    if (!recover(refusal)) {
                        return;
                    }
                    continue;
                } catch (IOException dropped) {
                    if (!running) {
                        return;
                    }
                    lastError = "fs-meta daemon connection failed: " + dropped.getMessage();
                    if (!reconnect(lastApplied)) {
                        return;
                    }
                    continue;
                }
                recoveryUsed = false;
                lastApplied = Math.max(lastApplied, batch.number());
                WatchBatch converted = toWatchBatch(batch, filter);
                observer.endBatch(converted);
                if (converted.isEmpty()) {
                    continue;
                }
                delivered++;
                listener.accept(converted);
            }
        } catch (RuntimeException unexpected) {
            // The listener is the consumer's code; a failing pass must be visible, not a silent dead watcher.
            lastError = "the batch listener threw: " + unexpected;
            running = false;
        }
    }

    /**
     * The protocol's two replay refusals, handled explicitly: tell the consumer it may have missed changes — one
     * overflow batch, which a consumer must treat as a full recompile — and resubscribe at the daemon's head.
     *
     * <p>A {@code protocol} error is not a replay refusal, but one arriving after a resume is the daemon saying it
     * cannot start where we asked (its history was replaced); that is the same recovery, once. A second refusal in a
     * row stops the reader with {@link #lastError()} set rather than looping.</p>
     */
    private boolean recover(FsMetaClient.ProtocolException refusal) {
        lastError = refusal.getMessage();
        if (!isReplayRefusal(refusal.code(), lastApplied)) {
            running = false;
            return false;
        }
        if (recoveryUsed) {
            running = false;
            lastError = "the fs-meta daemon refused the replay twice: " + refusal.getMessage();
            return false;
        }
        recoveries++;
        recoveryUsed = true;
        WatchBatch overflow = new WatchBatch(Set.of(), Set.of(), true);
        observer.endBatch(overflow);
        listener.accept(overflow);
        return reconnect(FsMetaClient.LATEST);
    }

    /**
     * Whether an {@code ERROR} means "this replay cannot be trusted", which is the case that must produce a full
     * read: the two replay refusals by definition, and a {@code protocol} refusal after a resume (the daemon could
     * not start where we asked — its history was replaced, so our position is meaningless). A first
     * {@code SUBSCRIBE} from zero that the daemon refuses for another reason is not recoverable.
     */
    static boolean isReplayRefusal(int code, long lastApplied) {
        return code == FsMetaClient.ERROR_BATCH_TOO_OLD
                || code == FsMetaClient.ERROR_BATCH_GAP
                || (code == FsMetaClient.ERROR_PROTOCOL && lastApplied > 0);
    }

    private boolean reconnect(long fromBatch) {
        for (int attempt = 0; attempt < MAX_RECONNECT_ATTEMPTS; attempt++) {
            if (!running) {
                return false;
            }
            try {
                connect(fromBatch);
                return true;
            } catch (IOException failed) {
                lastError = "cannot reconnect to " + socket + ": " + failed.getMessage();
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    running = false;
                    return false;
                }
            }
        }
        running = false;
        return false;
    }

    private void connect(long fromBatch) throws IOException {
        FsMetaClient next = FsMetaClient.connect(socket, fromBatch);
        FsMetaClient previous = client;
        client = next;
        if (previous != null) {
            try {
                previous.close();
            } catch (IOException ignored) {
                // The old connection is already unusable if we are reconnecting.
            }
        }
        if (fromBatch == FsMetaClient.LATEST) {
            // Subscribed at the head: that head is the generation this consumer now describes.
            lastApplied = next.serverGeneration();
        }
    }
}
