# Java lazy constants (was: stable values) 

More details in jvm internal @Stable annotation that is basis for stable values https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/jdk/internal/vm/annotation/Stable.java

https://www.infoq.com/news/2025/06/java25-stable-values-api-startup/

Java stable values are a new preview feature introduced in Java 25 via JEP 502 that enable the concept of deferred immutability for values in a thread-safe and optimized manner. A stable value is a special kind of value that can be initialized exactly once at any point during the runtime of an application and remains immutable thereafter. This means that unlike usual final fields that must be assigned at construction or class initialization time, stable values can be lazily initialized on demand while still giving the runtime guarantees and optimizations associated with constant values.

Key points about Java stable values are:

- They allow values to be assigned once at any time, not necessarily during object construction or class loading.
- Once initialized, these values behave like constants — immutable and eligible for JVM optimizations such as constant folding.
- The API centers around the class `StableValue<T>`, which can hold a single data value ("content").
- The main method to initialize or retrieve the value is `orElseSet(Supplier<T>)`, which initializes the stable value at most once even in concurrent scenarios, ensuring thread safety.
- Internally, stable values leverage JVM annotations like `@Stable` and memory barriers to provide thread-safe access without heavy synchronization overhead.
- This feature aims to improve application startup performance by allowing deferred and lazy initialization of constants, avoiding the eager initialization costs typical of final fields.
- Since stable values are new and part of a preview feature, they provide a safe and optimized wrapper around internal JVM mechanisms that previously were not directly accessible to developers.
- Usage example:

```java
private final StableValue<Settings> settings = StableValue.of();

public Settings getSettings() {
    return settings.orElseSet(this::loadSettingsFromDatabase);
}
```

Here, `loadSettingsFromDatabase` will only be called once, even if multiple threads call `getSettings()`, and subsequent calls return the initialized immutable value.

The key benefits of stable values include:

- Safe, thread-safe lazy initialization without explicit synchronization.
- Immutability guarantees improve robustness and readability.
- JVM optimizations treat them like final constants after initialization.
- They help address common concurrency and performance issues around lazily loaded constants.

In essence, stable values offer a flexible but safe way to declare constants that can be initialized on-demand while allowing the JVM to optimize them as if they were traditional final fields. This bridges the gap between flexibility of lazy initialization and the performance benefits of immutability and constant folding in modern Java applications.


## How do stable values differ from traditional final constants in Java
Stable values differ from traditional final constants in Java primarily in their timing and flexibility of initialization, while maintaining immutability and optimization benefits. Here's a detailed comparison:

| Aspect                                 | Traditional Final Constants                                  | Stable Values (Java 25 Preview)                              |
| :------------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
| **Initialization Timing**              | Must be initialized **once and only once** at object construction or class initialization time (constructor or static initializer). | Can be initialized **at any point during runtime**, lazily and on-demand, but only once. |
| **Mutability**                         | Immutable after initialization; cannot be changed.           | Immutable after the first initialization; no further updates allowed. |
| **Thread Safety**                      | Safe because it cannot be reassigned; but lazy initialization typically needs explicit synchronization or complex patterns (e.g., double-checked locking). | Thread-safe by design. The API guarantees the initialization lambda is executed only once, even if multiple threads initialize concurrently, without explicit synchronization on the user side. |
| **JVM Optimizations**                  | JVM treats final fields as constants, enabling optimizations like constant folding and inlining. | JVM can treat stable values as constants after initialization and apply optimizations similarly, due to internal JVM annotations like `@Stable`. |
| **Flexibility**                        | Rigid initialization fixed to construction or class load time, no re-initialization. | Flexible on-demand initialization anywhere in code; fills gap between mutable and fixed final fields. |
| **Use Case Example**                   | `static final int CONSTANT = 42;` initialized immediately at class load. | `StableValue<Integer> stableInt = StableValue.of();` initialized later with `orElseSet(() -> 42);` when needed for the first time. |
| **Side Effects During Initialization** | Initialization runs once in a controlled context with constructor/class loader. Side effects must be carefully managed. | Side effects during the lazy initialization lambda happen exactly once and are thread-safe with concurrent callers. |
| **API Support**                        | Language keyword (`final`) without explicit API.             | Introduced API `StableValue<T>` with methods like `orElseSet` for controlled lazy initialization. |

In summary, **stable values combine the immutability and optimization benefits of final constants with the flexibility of lazy, deferred initialization at any time during program execution, in a thread-safe manner without requiring manual synchronization.** This helps improve startup performance and compositional design of constants while ensuring JVM optimizations are still fully applicable. Traditional final constants remain rigid and must be initialized upfront, while stable values enable "deferred immutability" with robust concurrent guarantees

# Chaining stable values

It is generally not advisable to chain stable values by initializing one stable value through another stable value's initialization in the same runtime context. While stable values are designed for thread-safe, single-time deferred initialization, chaining them can introduce subtle risks and complexity:

Potential for Circular Dependencies: If stable value A depends on stable value B and vice versa, this can cause cyclic initialization issues or deadlock-like scenarios during first access.

Increased Complexity and Indirection: Chaining introduces more layers of lazy initialization, which may make code harder to reason about and debug if initialization order or timing matters.

Performance Concerns: Although stable values are optimized for single initialization, chaining multiple stable values serially could increase startup latency or runtime overhead, especially if nested initialization triggers repeated deferred calls.

Error Propagation Complexity: Failure or exception handling during chained stable value initialization can obscure root causes or cascade errors in unexpected ways.

Best practice recommended by Java stable values documentation and implementations is to keep stable value initialization independent and atomic, ideally initializing a stable value either by direct computation or a simple supplier without delegating to another stable value's lazy initializer.

If one stable value logically depends on data from another, a better approach is to:

Fully initialize the first stable value before using its content as input to initialize the second stable value.

Or explicitly control initialization order with eager or staged initialization, not on-demand chained calls.

This ensures clearer semantics, easier debugging, and avoids hidden dependencies or cycles.

In summary: while technically feasible to call one stable value's initializer from another, it is not advisable due to risks of complexity, potential cycles, and less maintainable code. It is better to keep stable value initializations separate and well-defined


# polyfill

A good polyfill for the Stable Values API that uses synchronization to prepare a codebase for future stable value usage can be implemented using a thread-safe lazy initialization pattern with double-checked locking. This mimics the deferred, at-most-once initialization behavior of StableValue, ensuring safe concurrent initialization and immutable-like access afterward.

```java
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
```

Usage:

```java
private final StableValuePolyfill<Settings> settings = new StableValuePolyfill<>();

Settings getSettings() {
    return settings.getOrInitialize(this::loadSettingsFromDatabase);
}
```

This polyfill:

- Guarantees at-most-once initialization with synchronization.
- Offers deferred, thread-safe lazy initialization similar to StableValue.
- Provides immutable-like access (no changes after first set).
- Uses standard Java APIs and idioms available in current versions.
When migrating to the real Stable Values API in Java 25+, the polyfill code can be easily replaced by calls to StableValue without major changes in calling code structure
(link)[].

# stable values and provider

From what I understand stable values will allow performant use of providers for lazy loaded singletons. The polyfill can be used to prepare the codebase,as it is valuable tool.


