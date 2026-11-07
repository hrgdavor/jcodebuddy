package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.WatchBatch;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The watch loop behind {@code --watch} (plan {@code hipster-entity-mvp.md} E11), with its event source kept
 * behind a seam so the order of operations can be tested without a native daemon.
 *
 * <h3>What it does, and why each step is there</h3>
 * <ol>
 *   <li><strong>One initial pass</strong> — the caller's normal or {@code --incremental} pass, unchanged.</li>
 *   <li><strong>Subscribe at {@code LATEST}</strong> — through the fs-meta daemon, never a Java
 *       {@code WatchService}. The daemon is the only watcher, as {@code FORMAT.daemon.md} requires.</li>
 *   <li><strong>Coalesce/debounce a burst</strong> — a save during a build, or a formatter touching ten files,
 *       is one pass, not ten.</li>
 *   <li><strong>Stop the watcher before the pass</strong> — the generator writes into the tree it watches, so a
 *       subscription left open would deliver the pass's own output.</li>
 *   <li><strong>Run the same incremental pass</strong> — {@link PassRunner} is the generator's existing pass
 *       runner, not a second code path, and it uses {@link EntityBuildState} for its baseline.</li>
 *   <li><strong>Wait for the daemon head to settle</strong> — {@link Settler}; the pass's writes are then
 *       inside the head, not pending against it.</li>
 *   <li><strong>Resubscribe at {@code LATEST}</strong> — the pass's own generated files are before the new
 *       subscription's head, so they cannot trigger another pass. <em>This is the loop guard</em>, and it is a
 *       subscription boundary rather than a content-hash filter: no second tracker, no second dependency
 *       graph.</li>
 *   <li><strong>Overflow means a full pass</strong> — {@code batch-too-old}/{@code batch-gap} arrive from
 *       {@link hr.hrg.jcodebuddy.watch.FsMetaDaemonWatch} as an overflow batch; the change set may be
 *       incomplete, so the only safe answer is the reference pass. It is never silently skipped.</li>
 * </ol>
 *
 * <p>Shutdown is {@link #close()}: the daemon subscription is closed, the loop stops taking batches and the
 * thread running {@link #run(boolean)} is joined, so Ctrl+C leaves a bounded, predictable stop instead of a
 * daemon reader thread that outlives the process.</p>
 *
 * <p>This class deliberately has <strong>no</strong> state directory, no state file and no tracker of its own.
 * The pass it calls reuses {@link EntityBuildState}'s marker; a second state directory is exactly what E11
 * forbids. It also never writes a trio and never opens a {@code WatchService}.</p>
 */
final class EntityWatchLoop implements AutoCloseable {

    /** How long a burst may keep growing before it is coalesced into one pass. */
    static final long DEFAULT_DEBOUNCE_MS = 300;

    /** Runs one generator pass. {@code full} selects the reference pass instead of incremental semantics. */
    @FunctionalInterface
    interface PassRunner {

        void run(boolean full) throws IOException;
    }

    /** Opens one daemon subscription; the loop asks for a new one after every pass. */
    @FunctionalInterface
    interface Subscriptions {

        Subscription subscribe(long fromBatch, Consumer<WatchBatch> listener) throws IOException;
    }

    /** One live subscription, as small as the loop needs it: start, stop, liveness and the last error. */
    interface Subscription extends AutoCloseable {

        void start() throws IOException;

        void stop();

        boolean isRunning();

        String lastError();

        @Override
        default void close() {
            stop();
        }
    }

    /** Waits until the daemon head describes the tree the pass just wrote, including its burst having settled. */
    @FunctionalInterface
    interface Settler {

        void awaitSettled() throws IOException;
    }

    private final PassRunner passRunner;
    private final Subscriptions subscriptions;
    private final Settler settler;
    private final long debounceMs;
    private final BlockingQueue<WatchBatch> batches = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Subscription subscription;
    private volatile Thread runner;
    private volatile long passes;
    private volatile long batchesSeen;
    private volatile long overflows;
    private volatile long failures;
    private volatile String lastError = "";

    EntityWatchLoop(PassRunner passRunner, Subscriptions subscriptions, Settler settler, long debounceMs) {
        if (passRunner == null || subscriptions == null || settler == null) {
            throw new IllegalArgumentException("a watch loop needs a pass runner, subscriptions and a settler");
        }
        this.passRunner = passRunner;
        this.subscriptions = subscriptions;
        this.settler = settler;
        this.debounceMs = debounceMs <= 0 ? DEFAULT_DEBOUNCE_MS : debounceMs;
    }

    /**
     * Runs the initial pass, subscribes at the daemon's head, and blocks until {@link #close()}.
     *
     * @param initialFull {@code true} for the normal pass (the default), {@code false} for an
     *                    {@code --incremental} first pass
     */
    void run(boolean initialFull) throws IOException {
        if (closed.get()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("this watch loop is already running");
        }
        runner = Thread.currentThread();
        try {
            passRunner.run(initialFull);
            subscribeAtLatest();
            while (running.get()) {
                WatchBatch first = nextBatch();
                if (first == null) {
                    continue;
                }
                WatchBatch batch = debounce(first);
                if (batch == null || !running.get()) {
                    break;
                }
                // Stop before the pass: the pass writes the tree this subscription watches, so anything that
                // arrived from those writes must not be handed to the loop as if a developer had saved it.
                stopSubscription();
                boolean full = batch.overflow();
                if (full) {
                    overflows++;
                }
                System.out.println("[watch] " + describe(batch) + "; running the "
                        + (full ? "full" : "incremental") + " pass");
                try {
                    passRunner.run(full);
                    passes++;
                } catch (IOException | RuntimeException failure) {
                    // A broken edit must not kill the loop: report it, keep the old baseline, and let the next
                    // batch (the fix) try again. A daemon failure below is different and does stop the loop.
                    failures++;
                    lastError = "the pass failed: " + message(failure);
                    System.err.println("[watch] " + lastError);
                }
                // The pass's own writes must be inside the head before we subscribe again; otherwise the new
                // subscription reports them as changes and the loop never becomes quiet.
                settler.awaitSettled();
                if (!running.get()) {
                    break;
                }
                // Anything queued before the stop is already covered by the pass's own replay from the state,
                // and the new subscription starts at the head that includes it.
                batches.clear();
                subscribeAtLatest();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            stopSubscription();
            runner = null;
        }
    }

    /** The next batch, or {@code null} when the loop was closed; a dead subscription is reported as an error. */
    private WatchBatch nextBatch() throws IOException, InterruptedException {
        while (running.get()) {
            WatchBatch batch = batches.poll(100, TimeUnit.MILLISECONDS);
            if (batch != null) {
                return batch;
            }
            Subscription current = subscription;
            if (current != null && !current.isRunning()) {
                throw new IOException("the fs-meta daemon subscription stopped: " + current.lastError());
            }
        }
        return null;
    }

    /**
     * Waits for the burst to go quiet, coalescing every batch that arrives in the window into one pass.
     *
     * <p>A debounce window is measured from the last batch, so a formatter's ten saves become one pass while a
     * single fast save still runs promptly. {@code null} means the loop was closed while waiting.</p>
     */
    private WatchBatch debounce(WatchBatch first) throws InterruptedException {
        batchesSeen++;
        WatchBatch coalesced = first;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMs);
        while (running.get()) {
            long wait = deadline - System.nanoTime();
            if (wait <= 0) {
                break;
            }
            WatchBatch next = batches.poll(wait, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batchesSeen++;
            coalesced = coalesce(coalesced, next);
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMs);
        }
        return running.get() ? coalesced : null;
    }

    private static WatchBatch coalesce(WatchBatch left, WatchBatch right) {
        Set<String> changed = new LinkedHashSet<>(left.changed());
        changed.addAll(right.changed());
        Set<String> deleted = new LinkedHashSet<>(left.deleted());
        deleted.addAll(right.deleted());
        return new WatchBatch(changed, deleted, left.overflow() || right.overflow());
    }

    private static String describe(WatchBatch batch) {
        int count = batch.changed().size() + batch.deleted().size();
        if (batch.overflow() && count == 0) {
            return "the daemon reported an overflow (the change set may be incomplete)";
        }
        return count + " watched file change(s)";
    }

    private void subscribeAtLatest() throws IOException {
        Subscription next = subscriptions.subscribe(FsMetaClient.LATEST, this::offer);
        subscription = next;
        if (!running.get()) {
            next.stop();
            subscription = null;
            return;
        }
        try {
            next.start();
        } catch (IOException failure) {
            next.stop();
            subscription = null;
            throw failure;
        }
    }

    private void offer(WatchBatch batch) {
        if (running.get() && !batch.isEmpty()) {
            batches.offer(batch);
        }
    }

    private void stopSubscription() {
        Subscription current = subscription;
        subscription = null;
        if (current != null) {
            current.stop();
        }
    }

    /**
     * Stops the loop: no new batches, the subscription closed, and the thread that called {@link #run(boolean)}
     * joined (up to a bounded wait) so a shutdown hook does not return while a pass is still writing.
     */
    @Override
    public void close() {
        closed.set(true);
        running.set(false);
        stopSubscription();
        Thread current = runner;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Whether the loop is running and has a live subscription. */
    boolean isRunning() {
        Subscription current = subscription;
        return running.get() && current != null && current.isRunning();
    }

    /** How many passes were triggered by batches; the initial pass is not counted. */
    long passes() {
        return passes;
    }

    /** How many batches were seen (including the ones coalesced into a larger one). */
    long batchesSeen() {
        return batchesSeen;
    }

    /** How many of the triggered passes were forced to a full pass by an overflow. */
    long overflows() {
        return overflows;
    }

    /** How many triggered passes failed (and were reported rather than stopping the loop). */
    long failures() {
        return failures;
    }

    /** The last transport or pass error, or an empty string. */
    String lastError() {
        return lastError;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
