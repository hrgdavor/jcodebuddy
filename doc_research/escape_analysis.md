To formally test whether an object escapes onto the heap or gets scalar-replaced by Java’s C2 JIT compiler, standard unit assertions are insufficient because Escape Analysis (EA) is a speculative runtime optimization. You need tooling that inspects memory allocations or C2 compiler logs directly.

---

### Method 1: Programmatic Heap Allocation Testing (JMH)

The standard and automated way to test if a design pattern escapes is using **JMH (Java Microbenchmark Harness)** with the `-prof gc` profiler.

If Escape Analysis succeeds, scalar replacement breaks the object down into local stack primitives, resulting in **0 bytes/op** allocated on the heap.

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class EscapeAnalysisTest {

    public interface Context { int value(); }
    public record RecordContext(int value) implements Context {}

    @Benchmark
    public int testRecordPattern() {
        // Test if passing a newly constructed record avoids heap allocation
        return process(new RecordContext(42));
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    private int process(Context ctx) {
        return ctx.value() * 2;
    }
}

```

**Execution Command:**

```bash
mvn clean package
java -jar target/benchmarks.jar EscapeAnalysisTest -prof gc

```

* **Passed EA Output:** `Alloc Rate: 0.000 MB/sec` or `0 B/op`.
* **Failed EA Output:** `Alloc Rate: >0 B/op` (indicates the object was allocated on the heap).

---

### Method 2: C2 Compiler Logs (`-XX:+PrintEscapeAnalysis`)

To directly inspect C2's decision-making regarding escape states and method inlining, run your code with JVM diagnostic flags:

```bash
java -XX:+UnlockDiagnosticVMOptions \
     -XX:+PrintEscapeAnalysis \
     -XX:+PrintEliminateAllocations \
     -XX:+PrintInlining \
     -jar target/benchmarks.jar

```

**Understanding C2 EA Classifications:**

* **`NoEscape`**: Object stays local to the thread/method and is scalar-replaced.
* **`ArgEscape`**: Object is passed into another method, but does not outlive the call chain. If the target method is **inlined**, this degrades to `NoEscape`.
* **`GlobalEscape`**: Object stored in a field, static reference, or returned. Allocation **cannot** be eliminated.

---

### Impact of Interfaces on Inlining and Escape Analysis

When testing Records vs. Builders behind a shared interface, Escape Analysis relies heavily on **Method Inlining**. If C2 cannot inline the method call, it must assume `ArgEscape`, which forces a physical heap allocation.

```
       [ Interface Call ]
               │
     ┌─────────┴─────────┐
     ▼                   ▼
Monomorphic          Polymorphic (3+ types)
 (1 Type)                │
     │                   ▼
     ▼               Inlining Fails
 Inlined                 │
     │                   ▼
     ▼              ArgEscape
 NoEscape                 │
(Scalar Replaced)         ▼
                   Heap Allocation

```

| Pattern Variant | Inlining Probability | Escape Analysis Success | Performance Trade-Off |
| --- | --- | --- | --- |
| **Direct Record** | Extremely High | High (`NoEscape`) | Best performance; zero allocation overhead. |
| **Builder Pattern** | High | High (`NoEscape`) | Eliminates builder instance overhead *if* builder methods inline cleanly. |
| **Interface Abstraction** | Monomorphic: High<br>

<br>Polymorphic: Low | Depends on target call-site profile | Virtual dispatch overhead can block inlining and force heap allocation. |

---

### Key Requirements for Successful Scalar Replacement

To ensure your ergonomic code patterns achieve 0-allocation status:

1. **Keep method bytecodes under 325 bytes**: C2 defaults to a maximum inline threshold of 325 bytes (`-XX:MaxInlineSize`).
2. **Avoid Type Pollution**: Passing multiple implementations of your interface through the same call site turns it megamorphic, disabling virtual call inlining.
3. **Avoid Escape Sinks**: Never store references to fields, assign to global variables, or pass objects into un-inlinable third-party methods.

