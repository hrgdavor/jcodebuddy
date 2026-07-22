package hr.hrg.hipster.entity.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class EEnumSetEmpty<E extends Enum<E>> implements EEnumSet<E> {

    private static final ConcurrentMap<Class<? extends Enum<?>>, EEnumSetEmpty<?>> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static <E extends Enum<E>> EEnumSetEmpty<E> of(Class<E> enumClass) {
        return (EEnumSetEmpty<E>) CACHE.computeIfAbsent(enumClass, cls -> new EEnumSetEmpty<>(enumClass));
    }

    private final Class<E> enumClass;

    EEnumSetEmpty(Class<E> enumClass) {
        this.enumClass = enumClass;
    }

	@Override
	public long getBits0() { return 0; }

	@Override
	public long getBits(int index) { return 0;	}

	@Override
	public boolean isEmpty() {	return true;	}

	@Override
	public boolean has(int ordinal) { return false;	}

	@Override
	public boolean has(E key) { return false; }

	@Override
	public boolean hasAny(EEnumSetRead<E> other) {	return false; }

	@Override
	public boolean hasAll(EEnumSetRead<E> other) {
		return other != null && other.isEmpty();
	}

	@Override
	public int size() {	return 0; }

	@Override
	public String toString() {
		return "[]";
	}

	@Override
	public Class<E> getEnumClass() {
		return enumClass;
	}

	@Override
	public boolean equals(Object o) {
		return EEnumSetUtils.equals(this, o);
	}

	@Override
	public int hashCode() {
		return EEnumSetUtils.hashCode(this);
	}

	@Override
	public void forEach(java.util.function.ObjIntConsumer<E> callback) {
		// do nothing, this set is empty
	}

	@Override
	public void forEach(java.util.function.Consumer<E> callback) {
		// no values, nothing to call
	}

	@Override
	public EEnumSetBuilder<E> toBuilder() {
		return EEnumSetBuilder.create(enumClass);
	}

	@Override
	public int getSegmentCount() {
		return 1;
	}
}
