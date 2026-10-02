package hr.hrg.hipster.entity.core;

import java.util.EnumSet;
import java.util.Objects;

public final class EEnumSetAll<E extends Enum<E>> implements EEnumSet<E> {
	private final Class<E> enumClass;
	private final int segmentCount;
	private final E[] universe;

	public EEnumSetAll(Class<E> enumClass) {
		Objects.requireNonNull(enumClass);
		this.enumClass = enumClass;
		this.universe = enumClass.getEnumConstants();
		this.segmentCount = (universe.length + 63) / 64;
	}
	
	private long bitsForSegment(int index) {
		if (index < 0 || index >= segmentCount) return 0L;
		int lastSeg = (universe.length - 1) / 64;
		if (index < lastSeg) return -1L;
		int rem = universe.length & 63;
		return rem == 0 ? -1L : (1L << rem) - 1L;
	}

	@Override
	public long getBits0() { return bitsForSegment(0); }

	@Override
	public long getBits(int index) { return bitsForSegment(index); }

	@Override
	public boolean isEmpty() {	return false;	}

	@Override
	public boolean has(int ordinal) { return ordinal >= 0 && ordinal < universe.length; }

	@Override
	public boolean has(E key) { return key != null; }
	
	@Override
	public boolean hasAny(EEnumSetRead<E> other) {
		return other != null && !other.isEmpty() && Objects.equals(enumClass, other.getEnumClass());
	}

	@Override
	public boolean hasAll(EEnumSetRead<E> other) {
		return other != null && Objects.equals(enumClass, other.getEnumClass());
	}

	@Override
	public int size() {
		return universe.length;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(size() * 8);
		sb.append('[');
		for (int i = 0; i < universe.length; i++) {
			if (i > 0) sb.append(',');
			sb.append(universe[i].toString());
		}
		sb.append(']');
		return sb.toString();
	}

	@Override
	public void forEach(java.util.function.ObjIntConsumer<E> callback) {
		if (callback == null) return;
		for (int i = 0; i < universe.length; i++) {
			callback.accept(universe[i], i);
		}
	}

	@Override
	public void forEach(java.util.function.Consumer<E> callback) {
		if (callback == null) return;
		for (E value : universe) {
			callback.accept(value);
		}
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
	public EnumSet<E> toEnumSet() {
		return EnumSet.allOf(enumClass);
	}

	@Override
	public EEnumSetBuilder<E> toBuilder() {
		if (universe.length <= 64) {
			return new EEnumSetBuilder64<>(enumClass, bitsForSegment(0), universe.length);
		}
		long[] bits = new long[segmentCount];
		for (int i = 0; i < segmentCount; i++) bits[i] = bitsForSegment(i);
		return new EEnumSetBuilderLarge<>(enumClass, bits, universe.length);
	}
		
	@Override
	public int getSegmentCount() {
		return segmentCount;
	}
}
