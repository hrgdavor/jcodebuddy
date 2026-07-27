package hr.hrg.hipster.ioc;

import java.util.Objects;
import java.util.function.Supplier;

public class StableValuePolyfill<T> {
    private volatile T value;
    private final Object lock = new Object();

    public T getOrInitialize(Supplier<T> supplier) {
        T result = value;
        if (result == null) {
            synchronized (lock) {
                if (value == null) {
                    value = Objects.requireNonNull(supplier.get());
                }
                result = value;
            }
        }
        return result;
    }
}