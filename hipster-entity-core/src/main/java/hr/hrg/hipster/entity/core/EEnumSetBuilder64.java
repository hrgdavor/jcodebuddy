package hr.hrg.hipster.entity.core;

import java.util.Objects;

@SuppressWarnings("unchecked")
public class EEnumSetBuilder64<E extends Enum<E>> implements EEnumSetBuilder<E> {

    private final Class<E> enumClass;
    private final E[] universe;
    private long bits0;
    private int size;

    public EEnumSetBuilder64(Class<E> enumClass) {
        this.enumClass = enumClass;
        this.universe = (E[]) enumClass.getEnumConstants();
        if (universe.length > 64) {
            throw new IllegalArgumentException("EEnumSetBuilder64 requires enum with at most 64 values, got " + universe.length);
        }
    }

    public EEnumSetBuilder64(Class<E> enumClass, long bits0, int size) {
        this.enumClass = enumClass;
        this.universe = (E[]) enumClass.getEnumConstants();
        if (universe.length > 64) {
            throw new IllegalArgumentException("EEnumSetBuilder64 requires enum with at most 64 values, got " + universe.length);
        }
        this.bits0 = bits0;
        this.size = size;
    }

    @Override
    public long getBits0() {
        return bits0;
    }

    @Override
    public long getBits(int index) {
        return index == 0 ? bits0 : 0L;
    }

    @Override
    public int getSegmentCount() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return bits0 == 0;
    }

    @Override
    public boolean has(int ordinal) {
        if (ordinal < 0 || ordinal >= 64) return false;
        return (bits0 & (1L << ordinal)) != 0;
    }

    @Override
    public boolean has(E key) {
        return key != null && has(key.ordinal());
    }

    @Override
    public boolean hasAny(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
            return false;
        }
        if (other instanceof EEnumSetBuilder64<?> b64) return (bits0 & b64.rawBits0()) != 0;
        if (other instanceof EEnumSet64<?> e64) return (bits0 & e64.getBits0()) != 0;
        return (bits0 & other.getBits0()) != 0;
    }

    @Override
    public boolean hasAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
            return false;
        }
        if (other instanceof EEnumSetBuilder64<?> b64) {
            long ob = b64.rawBits0();
            return (bits0 & ob) == ob;
        }
        if (other instanceof EEnumSet64<?> e64) {
            long ob = e64.getBits0();
            return (bits0 & ob) == ob;
        }
        long ob = other.getBits0();
        return (bits0 & ob) == ob;
    }

    long rawBits0() {
        return bits0;
    }

    @Override
    public EEnumSetBuilder<E> addAll(E ... values) {
        for (E value : values) {
            if (value != null) {
                add(value);
            }
        }
        return this;
    }

    @Override
    public EEnumSetBuilder<E> removeAll(E ... values) {
        for (E value : values) {
            if (value != null) {
                remove(value);
            }
        }
        return this;
    }

    @Override
    public EEnumSetBuilder<E> addAll(Iterable<E> values) {
        if (values != null) {
            for (E value : values) {
                if (value != null) {
                    add(value);
                }
            }
        }
        return this;
    }

    @Override
    public EEnumSetBuilder<E> removeAll(Iterable<E> values) {
        if (values != null) {
            for (E value : values) {
                if (value != null) {
                    remove(value);
                }
            }
        }
        return this;
    }

    @Override
    public int size() {
        return size;
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
    public EEnumSetBuilder<E> addAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) return this;
        long initial = bits0;
        long added;
        if (other instanceof EEnumSetBuilder64<?> b64) {
            added = b64.rawBits0();
        } else if (other instanceof EEnumSet64<?> e64) {
            added = e64.getBits0();
        } else {
            added = other.getBits0();
        }
        long combined = initial | added;
        size += Long.bitCount(combined) - Long.bitCount(initial);
        bits0 = combined;
        return this;
    }

    @Override
    public EEnumSetBuilder<E> retainAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
            clear();
            return this;
        }

        long keep;
        if (other instanceof EEnumSetBuilder64<?> b64) {
            keep = b64.rawBits0();
        } else if (other instanceof EEnumSet64<?> e64) {
            keep = e64.getBits0();
        } else {
            keep = other.getBits0();
        }

        bits0 &= keep;
        size = Long.bitCount(bits0);
        return this;
    }

    @Override
    public EEnumSetBuilder<E> removeAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) return this;
        long initial = bits0;
        long removed;
        if (other instanceof EEnumSetBuilder64<?> b64) {
            removed = b64.rawBits0();
        } else if (other instanceof EEnumSet64<?> e64) {
            removed = e64.getBits0();
        } else {
            removed = other.getBits0();
        }
        long result = initial & ~removed;
        size -= Long.bitCount(initial) - Long.bitCount(result);
        bits0 = result;
        return this;
    }

    @Override
    public boolean addOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= 64 || ordinal >= universe.length) return false;
        long mask = 1L << ordinal;
        if ((bits0 & mask) != 0) return false;
        bits0 |= mask;
        size++;
        return true;
    }

    @Override
    public boolean add(E value) {
        return value != null && addOrdinal(value.ordinal());
    }

    @Override
    public boolean removeOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= 64 || ordinal >= universe.length) return false;
        long mask = 1L << ordinal;
        if ((bits0 & mask) == 0) return false;
        bits0 &= ~mask;
        size--;
        return true;
    }

    @Override
    public boolean remove(E value) {
        return value != null && removeOrdinal(value.ordinal());
    }

    @Override
    public void clear() {
        bits0 = 0;
        size = 0;
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
			return bits0 == e64.getBits0();
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
    public EEnumSet<E> toImmutable() {
        if (size == 0) return EEnumSetEmpty.of(enumClass);
        return new EEnumSet64<>(enumClass, bits0, size);
    }

    public static class Strict<E extends Enum<E>> extends EEnumSetBuilder64<E>   {
        public Strict(Class<E> enumClass) {
            super(enumClass);
        }

        @Override
        public boolean addOrdinalChange(int ordinal, Object OldValue, Object NewValue) {
            if (Objects.equals(OldValue, NewValue)) {
                return false;
            }
            return super.addOrdinalChange(ordinal, OldValue, NewValue);
        }
    }
}
