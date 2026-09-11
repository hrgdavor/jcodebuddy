# trying to keep method arguments on stack

TLDR; very hard to do until valhala value classes arrive (possibly in 2029, JSK-31, first available as preview in JDK 27).

When inlining is restricted or impossible, forcing parameters to stay off the heap shifts the burden from JIT inlining tricks to **JVM stack layout, object layout, and API design**.

Java does not have explicit C++-style stack-allocated objects (`ValueType` or `on-stack` parameters). However, you can achieve **stack-native performance** and avoid heap allocation using specific memory and design patterns.

---

### Strategy 1: Escape Analysis-Friendly Object Passing

If a method *cannot* be inlined, any newly allocated object passed as an argument defaults to **`ArgEscape`**. The JIT must assume the target method could leak the reference, so it allocates the object on the heap.

To keep parameters on the stack without inlining the target method, **demote the object's lifetime to thread-local or stack-local before the call site**.

#### 1. Pass Stack-Decomposed Primitives (Scalar Flattening)

Instead of passing a wrapper/record object into a method that won't inline, pass its raw primitive components directly or use a `long` packing trick for small data models.

```java
// ❌ Heap risk: If process() isn't inlined, Point escapes to heap
public record Point(int x, int y) {}
process(new Point(10, 20));

// ✅ Guaranteed stack primitives: Zero heap allocation regardless of inlining
process(10, 20);

// ✅ Packed primitive: Encode 2 x 32-bit ints into 1 x 64-bit long
long packedPoint = (((long) 10) << 32) | (20 & 0xFFFFFFFFL);
processPacked(packedPoint);

```

#### 2. Inverse Control via Value Consumers (Lambda Inlining)

Instead of creating a short-lived parameter object and passing it *into* a method, pass a lambda or functional callback that operates directly on stack variables.

```java
// Instead of creating a Context object:
public void handleRequest(int id, String name) {
    // Process using stack variables directly inside the callback block
    executeWithContext(id, name, (x, y) -> {
        // Business logic runs here with zero object allocation
    });
}

```

---

### Strategy 2: Project Valhalla & Value Classes (Identity-Free Types)

In modern Java (Java 20+ preview, stabilizing in Valhalla), **Value Classes** and **Inline Types** fundamentally solve the "stack vs. heap" problem for parameters.

```java
// Identity-free value object (Preview feature)
public value record Point(int x, int y) {}

```

#### How Value Classes Keep Object Params Off the Heap

* **No Object Header**: Standard Java objects have a 12-to-16-byte header (`mark word` + `klass word`). Value objects have **zero header overhead**.
* **Passed in CPU Registers / Stack**: When passed as parameters, the JVM flattens value classes directly into CPU registers or raw stack slots—**even if the target method is NOT inlined**.
* **Zero GC Overhead**: They are never tracked by the garbage collector.

---

### Strategy 3: ThreadLocal Reusable Buffers (Flyweight Pattern)

If an object parameter *must* exist as a complex instance and inlining is blocked, move allocation entirely out of the execution path by reusing thread-confined instances.

```java
public class ThreadLocalContext {
    private static final ThreadLocal<MutableContext> CONTEXT_HOLDER = 
        ThreadLocal.withInitial(MutableContext::new);

    public static void executeTask(int paramA, String paramB) {
        // Grab the pre-allocated thread-bound instance (0 heap allocations)
        MutableContext ctx = CONTEXT_HOLDER.get();
        ctx.reset(paramA, paramB);

        // Target method receives the parameter without allocating new heap memory
        process(ctx); 
    }
}

```

---

### Strategy 4: Off-Heap / Foreign Memory Parameters (JDK 22+ Foreign Function & Memory API)

For heavy or structured data buffers passed across deep call trees, avoid Java heap objects altogether using off-heap `MemorySegment` parameters.

```java
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public void MemorySegmentExample() {
    // Confine allocation to a bounded off-heap Arena scope
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment struct = arena.allocate(8); // 8 bytes off-heap
        struct.set(ValueLayout.JAVA_INT, 0, 100);
        struct.set(ValueLayout.JAVA_INT, 4, 200);

        // Parameter points to raw off-heap memory address, bypassing JVM Heap GC
        processNativeData(struct);
    } 
}

```

---

### Summary Matrix: Off-Heap Parameter Alternatives

| Technique | Inlining Needed? | Allocation Cost | GC Impact | Best For |
| --- | --- | --- | --- | --- |
| **Scalar Replacement (JIT EA)** | **Yes** (Mandatory) | Zero (Stack slots) | Zero | Simple records/tuples passed to short methods. |
| **Primitive Decomposition / Bit Packing** | No | Zero (CPU Registers) | Zero | 2–3 small fields (e.g., coordinates, IDs, flags). |
| **Project Valhalla (`value record`)** | No | Zero (Flattened Stack) | Zero | Domain entities, lightweight models, builders. |
| **ThreadLocal Flyweight** | No | Amortized Zero | Low | Heavy mutable context wrappers. |
| **FFM `MemorySegment**` | No | Off-Heap Native | Zero (GC-Free) | Large byte buffers, inter-op with C/Native libraries. |

