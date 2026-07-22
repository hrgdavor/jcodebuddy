package hr.hrg.hipster.entity.core;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class EEnumSetJmhBenchmark {

    enum E64 {
        A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15,
        A16, A17, A18, A19, A20, A21, A22, A23, A24, A25, A26, A27, A28, A29, A30, A31,
        A32, A33, A34, A35, A36, A37, A38, A39, A40, A41, A42, A43, A44, A45, A46, A47,
        A48, A49, A50, A51, A52, A53, A54, A55, A56, A57, A58, A59, A60, A61, A62, A63
    }

    enum E96 {
        B0, B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12, B13, B14, B15,
        B16, B17, B18, B19, B20, B21, B22, B23, B24, B25, B26, B27, B28, B29, B30, B31,
        B32, B33, B34, B35, B36, B37, B38, B39, B40, B41, B42, B43, B44, B45, B46, B47,
        B48, B49, B50, B51, B52, B53, B54, B55, B56, B57, B58, B59, B60, B61, B62, B63,
        B64, B65, B66, B67, B68, B69, B70, B71, B72, B73, B74, B75, B76, B77, B78, B79,
        B80, B81, B82, B83, B84, B85, B86, B87, B88, B89, B90, B91, B92, B93, B94, B95
    }

    private EEnumSetBuilder<E64> b64a;
    private EEnumSetBuilder<E64> b64b;
    private EEnumSetBuilder<E96> b96a;
    private EEnumSetBuilder<E96> b96b;

    @Setup
    public void setup() {
        b64a = EEnumSetBuilder.create(E64.class);
        b64b = EEnumSetBuilder.create(E64.class);
        for (int i = 0; i < 64; i += 3) b64a.addOrdinal(i);
        for (int i = 1; i < 64; i += 5) b64b.addOrdinal(i);

        b96a = EEnumSetBuilder.create(E96.class);
        b96b = EEnumSetBuilder.create(E96.class);
        for (int i = 0; i < 96; i += 3) b96a.addOrdinal(i);
        for (int i = 1; i < 96; i += 5) b96b.addOrdinal(i);
    }

    @Benchmark
    public boolean hasAny64() {
        return b64a.hasAny(b64b);
    }

    @Benchmark
    public boolean hasAll64() {
        return b64a.hasAll(b64b);
    }

    @Benchmark
    public boolean hasAny96() {
        return b96a.hasAny(b96b);
    }

    @Benchmark
    public boolean hasAll96() {
        return b96a.hasAll(b96b);
    }

    @Benchmark
    public EEnumSet<E64> builder64ToImmutable() {
        // copy builder each time to avoid modifying canonical state
        EEnumSetBuilder<E64> copy = EEnumSetBuilder.create(E64.class);
        copy.addAll(b64a);
        return copy.toImmutable();
    }

    @Benchmark
    public EEnumSet<E96> builder96ToImmutable() {
        EEnumSetBuilder<E96> copy = EEnumSetBuilder.create(E96.class);
        copy.addAll(b96a);
        return copy.toImmutable();
    }

    @Benchmark
    public EEnumSetBuilder<E64> allToBuilder64() {
        return new EEnumSetAll<>(E64.class).toBuilder();
    }

    @Benchmark
    public EEnumSetBuilder<E96> allToBuilder96() {
        return new EEnumSetAll<>(E96.class).toBuilder();
    }

    @Benchmark
    public EEnumSetBuilder<E64> builder64AddRemove() {
        EEnumSetBuilder<E64> temp = EEnumSetBuilder.create(E64.class);
        for (int i = 0; i < 64; i++) {
            temp.addOrdinal(i);
        }
        for (int i = 0; i < 64; i += 2) {
            temp.removeOrdinal(i);
        }
        return temp;
    }

    @Benchmark
    public EEnumSetBuilder<E96> builder96AddRemove() {
        EEnumSetBuilder<E96> temp = EEnumSetBuilder.create(E96.class);
        for (int i = 0; i < 96; i++) {
            temp.addOrdinal(i);
        }
        for (int i = 0; i < 96; i += 2) {
            temp.removeOrdinal(i);
        }
        return temp;
    }

    @Benchmark
    public void builder64Clear() {
        EEnumSetBuilder<E64> temp = EEnumSetBuilder.create(E64.class);
        for (int i = 0; i < 64; i++) {
            temp.addOrdinal(i);
        }
        temp.clear();
    }

    @Benchmark
    public void builder96Clear() {
        EEnumSetBuilder<E96> temp = EEnumSetBuilder.create(E96.class);
        for (int i = 0; i < 96; i++) {
            temp.addOrdinal(i);
        }
        temp.clear();
    }
}

