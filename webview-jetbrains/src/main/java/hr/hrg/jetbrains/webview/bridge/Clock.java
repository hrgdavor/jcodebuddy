package hr.hrg.jetbrains.webview.bridge;

/**
 * A millisecond clock, injected so time-dependent logic can be tested without sleeping.
 */
@FunctionalInterface
public interface Clock {

    /** The production clock. */
    Clock SYSTEM = System::currentTimeMillis;

    long millis();
}
