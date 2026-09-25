package hr.hrg.webview.core;

/**
 * A clock a test moves by hand, so no test in this module ever sleeps.
 *
 * <p>Its own file rather than a nested class of one test, because two of them need it and a nested
 * package-private class is not reachable from the other.
 */
final class TestClock implements Clock {

    private long now = 1_000_000L;

    @Override
    public long millis() {
        return now;
    }

    void advance(long millis) {
        now += millis;
    }
}
