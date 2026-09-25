package hr.hrg.webview.core;

/**
 * A millisecond clock, injected so time-dependent logic can be tested without sleeping.
 *
 * <p>Moved here from the JetBrains plugin verbatim: the rate limiter needs it, and the rate limiter is
 * not a JetBrains concern.
 */
@FunctionalInterface
public interface Clock {

    /** The production clock. */
    Clock SYSTEM = System::currentTimeMillis;

    long millis();
}
