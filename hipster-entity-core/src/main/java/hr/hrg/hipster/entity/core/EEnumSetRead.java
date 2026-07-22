package hr.hrg.hipster.entity.core;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

public interface EEnumSetRead<E extends Enum<E>> {

    long getBits0();
    long getBits(int index);
    int getSegmentCount();

    boolean isEmpty();
    boolean has(int ordinal);

    boolean has(E key);
    boolean hasAny(EEnumSetRead<E> other);
    boolean hasAll(EEnumSetRead<E> other);

    int size();

    void forEach(java.util.function.ObjIntConsumer<E> callback);
    void forEach(java.util.function.Consumer<E> callback);

    default EnumSet<E> toEnumSet() {
        EnumSet<E> result = EnumSet.noneOf(getEnumClass());
        forEach(value -> result.add(value));
        return result;
    }

    default List<E> toList() {
        List<E> list = new ArrayList<>(size());
        forEach(value-> list.add(value));
        return list;
    }

    @SuppressWarnings("unchecked")
    default E[] toArray(E[] a) {
        int s = size();
        if (a.length < s) {
            a = (E[]) Array.newInstance(a.getClass().getComponentType(), s);
        }
        E[] target = a;
        forEach((value, idx) -> target[idx] = value);
        if (a.length > s) a[s] = null;
        return a;
    }

    Class<E> getEnumClass();
}
