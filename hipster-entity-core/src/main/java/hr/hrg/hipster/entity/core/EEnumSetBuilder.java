package hr.hrg.hipster.entity.core;

import java.util.EnumSet;
import java.util.Objects;

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

    /**
     * Marks the ordinal as changed only when the old and new values differ.
     * Returns true if the supplied values are different, false when they are equal.
     * If the values differ, the ordinal is added to the set if needed.
     *
     * @param ordinal the ordinal field to mark
     * @param OldValue the previous value
     * @param NewValue the new value
     * @return true when the value changed, false when the values are equal
     */
    default public boolean addOrdinalChange(int ordinal, Object OldValue, Object NewValue) {
        if (Objects.equals(OldValue, NewValue)) {
            return false;
        }
        addOrdinal(ordinal);
        return true;
    }

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
