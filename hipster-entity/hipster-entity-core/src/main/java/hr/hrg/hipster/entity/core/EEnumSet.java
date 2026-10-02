package hr.hrg.hipster.entity.core;

import java.util.EnumSet;
import java.util.Objects;

public interface EEnumSet<E extends Enum<E>> extends EEnumSetRead<E> {

	static <E extends Enum<E>> EEnumSet<E> copyOf(Class<E> enumClass, EnumSet<E> source) {
		if (source == null) throw new NullPointerException("source");
		if (source.isEmpty()) return EEnumSetEmpty.of(enumClass);
		EEnumSetBuilder<E> b = EEnumSetBuilder.create(enumClass);
		for (E value : source) b.add(value);
		return b.toImmutable();
	}

	static <E extends Enum<E>> EEnumSet<E> copyOf(EEnumSetRead<E> source) {
		if (source == null) throw new NullPointerException("source");
		if (source instanceof EEnumSet<E> immutable) return immutable;
		if (source.isEmpty()) return EEnumSetEmpty.of(source.getEnumClass());
		return EEnumSetBuilder.create(source.getEnumClass()).addAll(source).toImmutable();
	}

	default EEnumSet<E> union(EEnumSetRead<E> other) {
		if (other == null || !Objects.equals(getEnumClass(), other.getEnumClass())) {
			throw new IllegalArgumentException("enum class mismatch or null");
		}
		return toBuilder().addAll(other).toImmutable();
	}

	default EEnumSet<E> intersect(EEnumSetRead<E> other) {
		if (other == null || !Objects.equals(getEnumClass(), other.getEnumClass())) {
			throw new IllegalArgumentException("enum class mismatch or null");
		}
		return toBuilder().retainAll(other).toImmutable();
	}

	default EEnumSet<E> difference(EEnumSetRead<E> other) {
		if (other == null || !Objects.equals(getEnumClass(), other.getEnumClass())) {
			throw new IllegalArgumentException("enum class mismatch or null");
		}
		return toBuilder().removeAll(other).toImmutable();
	}

	EEnumSetBuilder<E> toBuilder();
}
