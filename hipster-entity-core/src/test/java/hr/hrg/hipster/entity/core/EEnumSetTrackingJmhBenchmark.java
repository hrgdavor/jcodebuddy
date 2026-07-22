package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ForNameOrdinal;
import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;
import hr.hrg.hipster.entity.core.EnumTestUtil.Enum64;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class EEnumSetTrackingJmhBenchmark{

    enum E64 implements FieldDef{
        A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15,
        A16, A17, A18, A19, A20, A21, A22, A23, A24, A25, A26, A27, A28, A29, A30, A31,
        A32, A33, A34, A35, A36, A37, A38, A39, A40, A41, A42, A43, A44, A45, A46, A47,
        A48, A49, A50, A51, A52, A53, A54, A55, A56, A57, A58, A59, A60, A61, A62, A63,
        ;

        @Override
        public Class<?> javaType() {
            return String.class;
        }
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    static ForNameOrdinal forNameOrdinal64 = new ForNameOrdinalImpl(E64.class);

    enum E96 implements FieldDef{
        B0, B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12, B13, B14, B15,
        B16, B17, B18, B19, B20, B21, B22, B23, B24, B25, B26, B27, B28, B29, B30, B31,
        B32, B33, B34, B35, B36, B37, B38, B39, B40, B41, B42, B43, B44, B45, B46, B47,
        B48, B49, B50, B51, B52, B53, B54, B55, B56, B57, B58, B59, B60, B61, B62, B63,
        B64, B65, B66, B67, B68, B69, B70, B71, B72, B73, B74, B75, B76, B77, B78, B79,
        B80, B81, B82, B83, B84, B85, B86, B87, B88, B89, B90, B91, B92, B93, B94, B95,
        ;
        @Override
        public Class<?> javaType() {
            return String.class;
        }
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    static ForNameOrdinal forNameOrdinal96 = new ForNameOrdinalImpl(E96.class);

    static final class Dummy64 implements EntityBase<Integer> {}

    static final class Dummy96 implements EntityBase<Integer> {}

    @State(Scope.Thread)
    public static class Builder64State {
        EEnumSetBuilder64<E64> concrete;
        EEnumSetBuilder<E64> generic;
        int ordinal;

        @Setup(Level.Iteration)
        public void setup() {
            concrete = new EEnumSetBuilder64<>(E64.class);
            generic = concrete;
            ordinal = 31;
        }
    }

    @State(Scope.Thread)
    public static class Builder96State {
        EEnumSetBuilderLarge<E96> concrete;
        EEnumSetBuilder<E96> generic;
        int ordinal;

        @Setup(Level.Iteration)
        public void setup() {
            concrete = new EEnumSetBuilderLarge<>(E96.class);
            generic = concrete;
            ordinal = 72;
        }
    }

    @State(Scope.Thread)
    public static class Tracking64State {
        EntityUpdateTrackingArray64<Dummy64, E64> concrete;
        EntityUpdateTrackingArray<Dummy64, E64> generic;
        int ordinal;

        @Setup(Level.Iteration)
        public void setup() {
            Object[] values = new Object[E64.values().length];
            values[0] = 1;
            for (int i = 1; i < values.length; i++) values[i] = i;
            concrete = new EntityUpdateTrackingArray64<>(forNameOrdinal64, Enum64.values().length, values);
            generic = concrete;
            ordinal = 31;
        }
    }

    @State(Scope.Thread)
    public static class Tracking96State {
        EntityUpdateTrackingArrayLarge<Dummy96, E96> concrete;
        EntityUpdateTrackingArray<Dummy96, E96> generic;
        int ordinal;

        @Setup(Level.Iteration)
        public void setup() {
            Object[] values = new Object[E96.values().length];
            values[0] = 1;
            for (int i = 1; i < values.length; i++) values[i] = i;
            concrete = new EntityUpdateTrackingArrayLarge<>(forNameOrdinal96, E96.values().length, values);
            generic = concrete;
            ordinal = 72;
        }
    }

    @State(Scope.Thread)
    public static class PrefilledBuilder64State {
        EEnumSetBuilder64<E64> concrete;
        EEnumSetBuilder<E64> generic;

        @Setup(Level.Iteration)
        public void setup() {
            concrete = new EEnumSetBuilder64<>(E64.class);
            generic = concrete;
        }

        @Setup(Level.Invocation)
        public void fill() {
            concrete.clear();
            for (int i = 0; i < 64; i += 2) concrete.addOrdinal(i);
        }
    }

    @State(Scope.Thread)
    public static class PrefilledBuilder96State {
        EEnumSetBuilderLarge<E96> concrete;
        EEnumSetBuilder<E96> generic;

        @Setup(Level.Iteration)
        public void setup() {
            concrete = new EEnumSetBuilderLarge<>(E96.class);
            generic = concrete;
        }

        @Setup(Level.Invocation)
        public void fill() {
            concrete.clear();
            for (int i = 0; i < 96; i += 2) concrete.addOrdinal(i);
        }
    }

    @State(Scope.Thread)
    public static class PrefilledTracking64State {
        EntityUpdateTrackingArray64<Dummy64, E64> concrete;
        EntityUpdateTrackingArray<Dummy64, E64> generic;

        @Setup(Level.Iteration)
        public void setup() {
            Object[] values = new Object[E64.values().length];
            values[0] = 1;
            for (int i = 1; i < values.length; i++) values[i] = i;
            concrete = new EntityUpdateTrackingArray64<>(forNameOrdinal64, E64.values().length, values);
            generic = concrete;
        }

        @Setup(Level.Invocation)
        public void fill() {
            concrete.clear();
            for (int i = 0; i < 64; i += 2) concrete.mark(i);
        }
    }

    @State(Scope.Thread)
    public static class PrefilledTracking96State {
        EntityUpdateTrackingArrayLarge<Dummy96, E96> concrete;
        EntityUpdateTrackingArray<Dummy96, E96> generic;

        @Setup(Level.Iteration)
        public void setup() {
            Object[] values = new Object[E96.values().length];
            values[0] = 1;
            for (int i = 1; i < values.length; i++) values[i] = i;
            concrete = new EntityUpdateTrackingArrayLarge<>(forNameOrdinal96, E96.values().length, values);
            generic = concrete;
        }

        @Setup(Level.Invocation)
        public void fill() {
            concrete.clear();
            for (int i = 0; i < 96; i += 2) concrete.mark(i);
        }
    }

    @Benchmark
    public boolean markUnmark64ConcreteBuilder(Builder64State state) {
        return state.concrete.addOrdinal(state.ordinal) && state.concrete.removeOrdinal(state.ordinal);
    }

    @Benchmark
    public boolean markUnmark64InterfaceBuilder(Builder64State state) {
        return state.generic.addOrdinal(state.ordinal) && state.generic.removeOrdinal(state.ordinal);
    }

    @Benchmark
    public boolean markUnmark96ConcreteBuilder(Builder96State state) {
        return state.concrete.addOrdinal(state.ordinal) && state.concrete.removeOrdinal(state.ordinal);
    }

    @Benchmark
    public boolean markUnmark96InterfaceBuilder(Builder96State state) {
        return state.generic.addOrdinal(state.ordinal) && state.generic.removeOrdinal(state.ordinal);
    }

    @Benchmark
    public boolean markUnmark64ConcreteTracker(Tracking64State state) {
        return state.concrete.mark(state.ordinal) && state.concrete.unmark(state.ordinal);
    }

    @Benchmark
    public boolean markUnmark64AbstractTracker(Tracking64State state) {
        return state.generic.mark(state.ordinal) && state.generic.unmark(state.ordinal);
    }

    @Benchmark
    public boolean markUnmark96ConcreteTracker(Tracking96State state) {
        return state.concrete.mark(state.ordinal) && state.concrete.unmark(state.ordinal);
    }

    @Benchmark
    public boolean markUnmark96AbstractTracker(Tracking96State state) {
        return state.generic.mark(state.ordinal) && state.generic.unmark(state.ordinal);
    }

    @Benchmark
    public int clear64ConcreteBuilder(PrefilledBuilder64State state) {
        state.concrete.clear();
        return state.concrete.size();
    }

    @Benchmark
    public int clear64InterfaceBuilder(PrefilledBuilder64State state) {
        state.generic.clear();
        return state.generic.size();
    }

    @Benchmark
    public int clear96ConcreteBuilder(PrefilledBuilder96State state) {
        state.concrete.clear();
        return state.concrete.size();
    }

    @Benchmark
    public int clear96InterfaceBuilder(PrefilledBuilder96State state) {
        state.generic.clear();
        return state.generic.size();
    }

    @Benchmark
    public int clear64ConcreteTracker(PrefilledTracking64State state) {
        state.concrete.clear();
        return state.concrete.getChanges64().size();
    }

    @Benchmark
    public int clear64AbstractTracker(PrefilledTracking64State state) {
        state.generic.clear();
        return state.generic.getChanges().size();
    }

    @Benchmark
    public int clear96ConcreteTracker(PrefilledTracking96State state) {
        state.concrete.clear();
        return state.concrete.getChangesLarge().size();
    }

    @Benchmark
    public int clear96AbstractTracker(PrefilledTracking96State state) {
        state.generic.clear();
        return state.generic.getChanges().size();
    }

    @Benchmark
    public EEnumSet<E64> snapshot64ConcreteBuilder(PrefilledBuilder64State state) {
        return state.concrete.toImmutable();
    }

    @Benchmark
    public EEnumSet<E64> snapshot64InterfaceBuilder(PrefilledBuilder64State state) {
        return state.generic.toImmutable();
    }

    @Benchmark
    public EEnumSet<E96> snapshot96ConcreteBuilder(PrefilledBuilder96State state) {
        return state.concrete.toImmutable();
    }

    @Benchmark
    public EEnumSet<E96> snapshot96InterfaceBuilder(PrefilledBuilder96State state) {
        return state.generic.toImmutable();
    }

    @Benchmark
    public EEnumSet<E64> snapshot64ConcreteTracker(PrefilledTracking64State state) {
        return state.concrete.changesSnapshot();
    }

    @Benchmark
    public EEnumSet<E64> snapshot64AbstractTracker(PrefilledTracking64State state) {
        return state.generic.changesSnapshot();
    }

    @Benchmark
    public EEnumSet<E96> snapshot96ConcreteTracker(PrefilledTracking96State state) {
        return state.concrete.changesSnapshot();
    }

    @Benchmark
    public EEnumSet<E96> snapshot96AbstractTracker(PrefilledTracking96State state) {
        return state.generic.changesSnapshot();
    }
}
