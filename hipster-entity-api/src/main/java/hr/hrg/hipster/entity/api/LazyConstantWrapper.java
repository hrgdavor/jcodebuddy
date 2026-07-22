package hr.hrg.hipster.entity.api;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Mirror of java.lang.LazyConstant<T> from JEP 526.
 */
public final class LazyConstantWrapper<T> implements Supplier<T> {
    private final Supplier<? extends T> supplier;
    private volatile T value;

    private LazyConstantWrapper(Supplier<? extends T> supplier) {
        this.supplier = Objects.requireNonNull(supplier);
    }

    public static <T> LazyConstantWrapper<T> of(Supplier<? extends T> supplier) {
        return new LazyConstantWrapper<>(supplier);
    }

    @Override
    public T get() {
        T result = value;
        if (result == null) {
            synchronized (this) {
                result = value;
                if (result == null) {
                    // JEP 526 behavior: supplier must not return null
                    value = result = Objects.requireNonNull(supplier.get());
                }
            }
        }
        return result;
    }

    public boolean isInitialized() {
        return value != null;
    }

    public T orElse(T other) {
        T result = value;
        return (result != null) ? result : other;
    }
}
