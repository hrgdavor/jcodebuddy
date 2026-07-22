package hr.hrg.hipster.entity.core;

import java.util.List;
import java.util.Objects;

@SuppressWarnings("unchecked")
public final class EEnumSet64<E extends Enum<E>> implements EEnumSet<E>{

	private final long bits0;
	private final int size;
	private final E[] universe;
    private final Class<E> enumClass;

	EEnumSet64(Class<E> enumClass, E ...values){
		universe = (E[])enumClass.getEnumConstants();
        this.enumClass = enumClass;
		ensureSupportedUniverseSize();
        long bits = 0L;
        int count = 0;
        for(E en:values) {
			int ordinal = en.ordinal();
			if((bits & (1L << ordinal)) == 0) {
				count++;
				bits |= (1L << ordinal);
			}
		}
        this.bits0 = bits;
        this.size = count;
	}

	/**
	 * Internal fast-path constructor used when all immutable state is already known.
	 *
	 * <p>Intended for package-local builder conversions to avoid rebuilding bits from
	 * enumerated values. Callers must provide a consistent pair where {@code size}
	 * matches the number of set bits in {@code bits0}.</p>
	 */
	EEnumSet64(Class<E> enumClass, long bits0, int size){
		this.enumClass = enumClass;
		universe = (E[]) enumClass.getEnumConstants();
		ensureSupportedUniverseSize();
		this.bits0 = bits0;
		this.size = size;
	}
	
	EEnumSet64(Class<E> enumClass,List<E> values){
		this.enumClass = enumClass;
		universe = (E[])enumClass.getEnumConstants();
		ensureSupportedUniverseSize();
		long bits = 0L;
		int count = 0;
		for(E en:values) {
			int ordinal = en.ordinal();
			if((bits & (1L << ordinal)) == 0) {
				count++;
				bits |= (1L << ordinal);
			}
		}
		this.bits0 = bits;
		this.size = count;
	}

	private void ensureSupportedUniverseSize() {
		if (universe.length > 64) {
			throw new IllegalArgumentException("EEnumSet64 requires enum with at most 64 values, got " + universe.length);
		}
	}
	@Override public long getBits0()  { return bits0; }
	@Override public long getBits(int index)  { return  index == 0 ? bits0 : 0L; }

	@Override
	public boolean isEmpty() {
		return bits0 == 0;
	}
	
	@Override
	public boolean has(int ordinal){
		if(ordinal < 0 || ordinal >= 64) return false;
		return (bits0 & (1L << ordinal)) != 0;		
	}
	
	@Override
	public boolean has(E key) {
		return key == null ? false: has(key.ordinal());
	}
		
	@Override
	public boolean hasAll(EEnumSetRead<E> other) {
		if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
			return false;
		}
		if(other instanceof EEnumSet64<?> e64) {
			long ob = e64.bits0;
			return (bits0 & ob) == ob;
		}
		if(other instanceof EEnumSetBuilder64<?> b64) {
			long ob = b64.rawBits0();
			return (bits0 & ob) == ob;
		}
		long otherBits0 = other.getBits0();
		return (bits0 & otherBits0) == otherBits0;
	}

	@Override
	public boolean hasAny(EEnumSetRead<E> other) {
		if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
			return false;
		}
		if (other instanceof EEnumSet64<?> e64) return (bits0 & e64.bits0) != 0;
		if (other instanceof EEnumSetBuilder64<?> b64) return (bits0 & b64.rawBits0()) != 0;
		return (bits0 & other.getBits0()) != 0;
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(size * 8);
		sb.append('[');
		int idx = 0;
		long remaining = bits0;
		while (remaining != 0) {
			if (idx > 0) sb.append(',');
			int ordinal = Long.numberOfTrailingZeros(remaining);
			sb.append(universe[ordinal].toString());
			remaining &= (remaining - 1);
			idx++;
		}
		sb.append(']');
		return sb.toString();
	}
	
	@Override
	public void forEach(java.util.function.ObjIntConsumer<E> callback) {
		if (callback == null) return;
		long remaining = bits0;
		int idx = 0;
		while (remaining != 0) {
			int ordinal = Long.numberOfTrailingZeros(remaining);
			callback.accept(universe[ordinal], idx++);
			remaining &= (remaining - 1);
		}
	}

	@Override
	public void forEach(java.util.function.Consumer<E> callback) {
		if (callback == null) return;
		long remaining = bits0;
		while (remaining != 0) {
			int ordinal = Long.numberOfTrailingZeros(remaining);
			callback.accept(universe[ordinal]);
			remaining &= (remaining - 1);
		}
	}

	@Override
	public Class<E> getEnumClass() {
		return enumClass;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof EEnumSetRead<?> other)) return false;
		if (!Objects.equals(enumClass, other.getEnumClass())) return false;
		if (other.getSegmentCount() > 1) return false;

		if (other instanceof EEnumSet64<?> e64) {
			return bits0 == e64.bits0;
		}
		if (other instanceof EEnumSetBuilder64<?> b64) {
			return bits0 == b64.rawBits0();
		}
		return bits0 == other.getBits0();
	}

	@Override
	public int hashCode() {
		return EEnumSetUtils.hashCode(this);
	}

	@Override
	public EEnumSetBuilder<E> toBuilder() {
		return new EEnumSetBuilder64<>(enumClass, bits0, size);
	}
	
	@Override
	public int getSegmentCount() {
		return 1;
	}
}
