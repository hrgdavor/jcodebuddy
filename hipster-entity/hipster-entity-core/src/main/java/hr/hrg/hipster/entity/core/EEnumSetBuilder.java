package hr.hrg.hipster.entity.core;

/**
 * The mutable change set of a tracked view: which field ordinals are marked as changed.
 *
 * <p>It is a plain set of ordinals. It deliberately carries <strong>no value state at all</strong> —
 * not the value a field holds now (that is the view's, reachable through
 * {@link ViewChangeTracking#currentValue}) and not the value it held before (that is the caller's
 * baseline instance, which the library never copies). Writers compare the value they are about to
 * assign with the one the field currently holds and call {@link #addOrdinal(int)} only when the two
 * differ, which is the DEC-012 no-op rule expressed at the write site.</p>
 */
public interface EEnumSetBuilder<E extends Enum<E>> extends EEnumSetRead<E> {

    static <E extends Enum<E>> EEnumSetBuilder<E> create(Class<E> enumClass) {
        int size = enumClass.getEnumConstants().length;
        return size <= 64 ? new EEnumSetBuilder64<>(enumClass) : new EEnumSetBuilderLarge<>(enumClass);
    }

    @SafeVarargs
    static <E extends Enum<E>> EEnumSetBuilder<E> of(Class<E> enumClass, E... values) {
        EEnumSetBuilder<E> b = create(enumClass);
        b.addAll(values);
        return b;
    }

    // explicit ordinal-based operations
    boolean addOrdinal(int ordinal);
    boolean removeOrdinal(int ordinal);

    // explicit enum-value operations
    boolean add(E value);
    boolean remove(E value);

    default boolean setOrdinal(int ordinal, boolean value) {
        return value ? addOrdinal(ordinal) : removeOrdinal(ordinal);
    }

    default boolean set(E key, boolean value) {
        return key != null && setOrdinal(key.ordinal(), value);
    }

    EEnumSetBuilder<E> addAll(E ... values);
    EEnumSetBuilder<E> removeAll(E ... values);
    EEnumSetBuilder<E> addAll(EEnumSetRead<E> other);
    EEnumSetBuilder<E> removeAll(EEnumSetRead<E> other);
    EEnumSetBuilder<E> retainAll(EEnumSetRead<E> other);
    EEnumSetBuilder<E> addAll(Iterable<E> values);
    EEnumSetBuilder<E> removeAll(Iterable<E> values);

    void clear();

    EEnumSet<E> toImmutable();
}
