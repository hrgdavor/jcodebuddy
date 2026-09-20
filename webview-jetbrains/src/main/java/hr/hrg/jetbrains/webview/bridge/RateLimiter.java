package hr.hrg.jetbrains.webview.bridge;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A sliding-window rate limiter: at most {@code limit} events per {@code windowMillis}.
 *
 * <p>Used to bound how fast a page can make the IDE open files, from either the injected
 * {@code window.openFile} path or the HTTP fallback. It exists as its own class with an injected
 * {@link Clock} so the policy can be tested by moving the clock instead of sleeping.
 *
 * <p>The methods are synchronised: the JCEF path calls from the EDT and the HTTP fallback calls from
 * its own executor thread, and both must see one shared window.
 */
public final class RateLimiter {

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RateLimiter(int limit, long windowMillis, @NotNull Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, was " + limit);
        }
        if (windowMillis < 1) {
            throw new IllegalArgumentException("windowMillis must be >= 1, was " + windowMillis);
        }
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /**
     * Records one event and reports whether it is allowed. A refused event is <b>not</b> recorded, so
     * a burst of refused calls cannot keep the window full.
     */
    public synchronized boolean tryAcquire() {
        long now = clock.millis();
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMillis) {
            timestamps.removeFirst();
        }
        if (timestamps.size() >= limit) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }

    /** How many events are currently inside the window. Exposed for diagnostics and tests. */
    public synchronized int windowSize() {
        return timestamps.size();
    }

    public int limit() {
        return limit;
    }

    public long windowMillis() {
        return windowMillis;
    }
}
