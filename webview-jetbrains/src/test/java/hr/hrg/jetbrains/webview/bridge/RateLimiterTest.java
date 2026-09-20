package hr.hrg.jetbrains.webview.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RateLimiterTest {

    /** A clock the test moves by hand, so no test ever sleeps. */
    private static final class TestClock implements Clock {
        private long now = 1_000_000L;

        @Override
        public long millis() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    @Test
    public void refusesBeyondTheLimit() {
        RateLimiter limiter = new RateLimiter(3, 1000, new TestClock());

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse("the fourth call inside the window must be refused", limiter.tryAcquire());
    }

    @Test
    public void rollsOver() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(2, 1000, clock);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        clock.advance(1001);
        assertTrue("an event older than the window no longer counts", limiter.tryAcquire());
    }

    @Test
    public void boundaryIsInclusive() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(1, 1000, clock);

        assertTrue(limiter.tryAcquire());
        clock.advance(1000);
        assertFalse("the event at exactly window age is still inside the window", limiter.tryAcquire());

        clock.advance(1);
        assertTrue(limiter.tryAcquire());
    }

    @Test
    public void refusedCallsDoNotExtendTheWindow() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(1, 1000, clock);

        assertTrue(limiter.tryAcquire());
        for (int i = 0; i < 50; i++) {
            assertFalse(limiter.tryAcquire());
        }
        assertEquals("only the accepted call is in the window", 1, limiter.windowSize());

        clock.advance(1001);
        assertTrue("one window after the accepted call, capacity returns", limiter.tryAcquire());
    }

    @Test
    public void rejectsNonsensicalConfiguration() {
        TestClock clock = new TestClock();
        rejects(() -> new RateLimiter(0, 1000, clock));
        rejects(() -> new RateLimiter(-1, 1000, clock));
        rejects(() -> new RateLimiter(1, 0, clock));
    }

    private static void rejects(Runnable constructor) {
        try {
            constructor.run();
            fail("a nonsensical limit must be rejected loudly, not silently disable the policy");
        } catch (IllegalArgumentException expected) {
            // as intended
        }
    }
}
