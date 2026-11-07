package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.WatchBatch;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The watch loop's contract, without a native daemon.
 *
 * <p>{@link EntityWatchLoop} is the seam E11 asks for: it decides <em>when</em> the existing pass runs and what
 * order the subscription/pass/settle steps happen in. Every dependency it has is a small interface, so this test
 * can drive it with an in-process subscription and assert the two facts that matter most:</p>
 * <ul>
 *   <li>the watcher is <strong>stopped before</strong> the pass and the new subscription starts at
 *       {@code LATEST} <strong>after</strong> the head settled — the loop guard that keeps the pass's own
 *       generated files from triggering another pass;</li>
 *   <li>an overflow ({@code batch-too-old}/{@code batch-gap}) becomes a full pass, never a skipped batch.</li>
 * </ul>
 *
 * <p>What it cannot prove is that the real daemon publishes the pass's writes before the resubscription; that is
 * the opt-in real-daemon test {@code EntityWatchDaemonTest}.</p>
 */
@Tag("generator")
class EntityWatchLoopTest {

    private static final long AWAIT_MS = 10_000;

    /** One in-process subscription: records the generation it started from and can be driven by the test. */
    private static final class FakeSubscription implements EntityWatchLoop.Subscription {

        private final long fromBatch;
        private final Consumer<WatchBatch> listener;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean alive = new AtomicBoolean();

        FakeSubscription(long fromBatch, Consumer<WatchBatch> listener) {
            this.fromBatch = fromBatch;
            this.listener = listener;
        }

        @Override
        public void start() {
            started.set(true);
            alive.set(true);
        }

        @Override
        public void stop() {
            stopped.set(true);
            alive.set(false);
        }

        @Override
        public boolean isRunning() {
            return alive.get();
        }

        @Override
        public String lastError() {
            return "";
        }

        void deliver(WatchBatch batch) {
            listener.accept(batch);
        }
    }

    /** The factory: one fake subscription per subscribe call, kept so the test can inspect and drive them. */
    private static final class FakeSubscriptions implements EntityWatchLoop.Subscriptions {

        private final List<FakeSubscription> opened = new CopyOnWriteArrayList<>();

        @Override
        public EntityWatchLoop.Subscription subscribe(long fromBatch, Consumer<WatchBatch> listener) {
            FakeSubscription subscription = new FakeSubscription(fromBatch, listener);
            opened.add(subscription);
            return subscription;
        }

        FakeSubscription last() {
            Assertions.assertFalse(opened.isEmpty(), "the loop must have subscribed");
            return opened.get(opened.size() - 1);
        }
    }

    /** Records every pass, and lets a test make one fail (to assert the loop survives a broken edit). */
    private static final class RecordingPasses implements EntityWatchLoop.PassRunner {

        private final List<Boolean> modes = new CopyOnWriteArrayList<>();
        private volatile boolean failNext;

        @Override
        public void run(boolean full) {
            modes.add(full);
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("synthetic pass failure");
            }
        }

        int count() {
            return modes.size();
        }

        boolean initialFull() {
            return !modes.isEmpty() && modes.get(0);
        }

        boolean lastFull() {
            return !modes.isEmpty() && modes.get(modes.size() - 1);
        }
    }

    /** The count of settle calls; the order against pass/subscription is what the assertions read. */
    private static final class RecordingSettler implements EntityWatchLoop.Settler {

        private final List<Long> settledAt = new CopyOnWriteArrayList<>();

        @Override
        public void awaitSettled() {
            settledAt.add(System.nanoTime());
        }

        int count() {
            return settledAt.size();
        }
    }

    @Test
    void initialPassThenLatestSubscriptionAndNoPassForTheGeneratedOutput() throws Exception {
        FakeSubscriptions subscriptions = new FakeSubscriptions();
        RecordingPasses passes = new RecordingPasses();
        RecordingSettler settler = new RecordingSettler();
        EntityWatchLoop loop = new EntityWatchLoop(passes, subscriptions, settler, 20);
        Thread thread = start(loop, true);

        try {
            await(() -> subscriptions.opened.size() == 1, "the first subscription");
            Assertions.assertEquals(List.of(true), passes.modes,
                    "the initial pass is the caller's normal pass, before the watcher starts");
            Assertions.assertEquals(FsMetaClient.LATEST, subscriptions.opened.get(0).fromBatch,
                    "the first subscription starts at the daemon's head, not at generation zero");

            FakeSubscription first = subscriptions.last();
            first.deliver(new WatchBatch(Set.of("build/entity/ThingSummary.java"), Set.of(), false));

            await(() -> subscriptions.opened.size() == 2, "a resubscription after the pass");
            await(() -> passes.count() == 2, "the pass triggered by the batch");
            Assertions.assertEquals(List.of(true, false), passes.modes,
                    "a batch runs the incremental pass, not a second full pass");
            Assertions.assertTrue(first.stopped.get(),
                    "the watcher is stopped before the pass so it cannot see the pass's own writes");

            FakeSubscription second = subscriptions.last();
            Assertions.assertEquals(FsMetaClient.LATEST, second.fromBatch,
                    "the resubscription starts at the head that includes the pass's generated files");
            Assertions.assertTrue(second.isRunning(), "and it is live after the pass");
            Assertions.assertEquals(1, settler.count(),
                    "the loop waits for the daemon head to settle between the pass and the resubscription");

            // No batch is delivered after the resubscription (the fake starts at LATEST and has nothing to
            // replay), so the generated output cannot start a second pass. Assert the quiet state explicitly.
            Thread.sleep(250);
            Assertions.assertEquals(2, passes.count(), "the pass's own output must not trigger another pass");
            Assertions.assertEquals(1, loop.passes(), "one batch-triggered pass");
        } finally {
            loop.close();
            thread.join(5_000);
        }
        Assertions.assertFalse(thread.isAlive(), "close() must stop the loop predictably");
        Assertions.assertFalse(loop.isRunning(), "and the loop is no longer live");
    }

    @Test
    void anOverflowIsAFullPass() throws Exception {
        FakeSubscriptions subscriptions = new FakeSubscriptions();
        RecordingPasses passes = new RecordingPasses();
        RecordingSettler settler = new RecordingSettler();
        EntityWatchLoop loop = new EntityWatchLoop(passes, subscriptions, settler, 20);
        Thread thread = start(loop, false);

        try {
            await(() -> subscriptions.opened.size() == 1, "the first subscription");
            Assertions.assertFalse(passes.initialFull(), "the caller asked for an --incremental initial pass");
            subscriptions.last().deliver(new WatchBatch(Set.of(), Set.of(), true));

            await(() -> subscriptions.opened.size() == 2, "a resubscription after the overflow pass");
            Assertions.assertEquals(List.of(false, true), passes.modes,
                    "batch-too-old/batch-gap must run the reference pass, never skip the batch");
            Assertions.assertEquals(1, loop.overflows());
        } finally {
            loop.close();
            thread.join(5_000);
        }
    }

    @Test
    void aBurstIsCoalescedIntoOnePass() throws Exception {
        FakeSubscriptions subscriptions = new FakeSubscriptions();
        RecordingPasses passes = new RecordingPasses();
        RecordingSettler settler = new RecordingSettler();
        EntityWatchLoop loop = new EntityWatchLoop(passes, subscriptions, settler, 150);
        Thread thread = start(loop, true);

        try {
            await(() -> subscriptions.opened.size() == 1, "the first subscription");
            FakeSubscription first = subscriptions.last();
            first.deliver(new WatchBatch(Set.of("a/One.java"), Set.of(), false));
            Thread.sleep(40);
            first.deliver(new WatchBatch(Set.of("a/Two.java"), Set.of("a/One.java"), false));

            await(() -> subscriptions.opened.size() == 2, "the pass after the quiet window");
            Assertions.assertEquals(2, passes.count(),
                    "two batches inside one debounce window are one initial + one triggered pass, not three");
            Assertions.assertEquals(2, loop.batchesSeen(), "both batches were seen and coalesced");
            Assertions.assertEquals(false, passes.lastFull(), "a coalesced burst is still an incremental pass");
        } finally {
            loop.close();
            thread.join(5_000);
        }
    }

    @Test
    void aFailedPassIsReportedAndTheLoopKeepsWatching() throws Exception {
        FakeSubscriptions subscriptions = new FakeSubscriptions();
        RecordingPasses passes = new RecordingPasses();
        RecordingSettler settler = new RecordingSettler();
        EntityWatchLoop loop = new EntityWatchLoop(passes, subscriptions, settler, 20);
        Thread thread = start(loop, true);

        try {
            await(() -> subscriptions.opened.size() == 1, "the first subscription");
            passes.failNext = true;
            subscriptions.last().deliver(new WatchBatch(Set.of("a/Broken.java"), Set.of(), false));

            await(() -> subscriptions.opened.size() == 2, "a resubscription after the failed pass");
            Assertions.assertEquals(2, passes.count(), "the initial and the failed pass both ran");
            Assertions.assertEquals(1, loop.failures(), "the failure is recorded rather than swallowed");
            Assertions.assertTrue(loop.lastError().contains("synthetic pass failure"), loop.lastError());

            // The loop is still live: the next edit gets a pass.
            passes.modes.clear();
            subscriptions.last().deliver(new WatchBatch(Set.of("a/Fixed.java"), Set.of(), false));
            await(() -> loop.passes() == 1, "the pass after the repaired edit");
            Assertions.assertEquals(List.of(false), passes.modes);
        } finally {
            loop.close();
            thread.join(5_000);
        }
        Assertions.assertFalse(thread.isAlive());
    }

    @Test
    void closeStopsTheSubscriptionAndJoinsTheLoop() throws Exception {
        FakeSubscriptions subscriptions = new FakeSubscriptions();
        RecordingPasses passes = new RecordingPasses();
        RecordingSettler settler = new RecordingSettler();
        EntityWatchLoop loop = new EntityWatchLoop(passes, subscriptions, settler, 20);
        Thread thread = start(loop, true);

        await(() -> subscriptions.opened.size() == 1 && loop.isRunning(), "a live subscription");
        FakeSubscription live = subscriptions.last();

        loop.close();

        thread.join(5_000);
        Assertions.assertFalse(thread.isAlive(), "the watch loop must terminate when closed");
        Assertions.assertFalse(live.isRunning(), "and it must close the daemon subscription");
        Assertions.assertTrue(live.stopped.get());
        Assertions.assertFalse(loop.isRunning());
    }

    private static Thread start(EntityWatchLoop loop, boolean initialFull) {
        Thread thread = new Thread(() -> {
            try {
                loop.run(initialFull);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }, "entity-watch-loop-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out after " + AWAIT_MS + " ms waiting for " + what);
    }
}
