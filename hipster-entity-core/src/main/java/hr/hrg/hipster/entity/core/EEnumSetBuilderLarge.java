package hr.hrg.hipster.entity.core;

import java.util.Objects;

@SuppressWarnings("unchecked")
public class EEnumSetBuilderLarge<E extends Enum<E>> implements EEnumSetBuilder<E> {

    private final Class<E> enumClass;
    private final E[] universe;
    private final long[] bits;
    private int size;

    public EEnumSetBuilderLarge(Class<E> enumClass) {
        this.enumClass = enumClass;
        this.universe = (E[]) enumClass.getEnumConstants();
        this.bits = new long[(universe.length + 63) / 64];
    }

    public EEnumSetBuilderLarge(Class<E> enumClass, long[] sourceBits, int size) {
        this.enumClass = enumClass;
        this.universe = (E[]) enumClass.getEnumConstants();
        this.bits = new long[(universe.length + 63) / 64];
        int limit = Math.min(this.bits.length, sourceBits.length);
        for (int i = 0; i < limit; i++) this.bits[i] = sourceBits[i];
        this.size = size;
    }

    @Override
    public long getBits0() {
        return bits[0];
    }

    @Override
    public long getBits(int index) {
        return index < bits.length ? bits[index] : 0L;
    }

    @Override
    public int getSegmentCount() {
        return bits.length;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean has(int ordinal) {
        if (ordinal < 0 || ordinal >= universe.length) return false;
        int index = ordinal / 64;
        return (bits[index] & (1L << (ordinal & 63))) != 0;
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
        if (other instanceof EEnumSetBuilderLarge<?> bl) {
            long[] otherBits = bl.rawBits();
            int len = Math.min(bits.length, otherBits.length);
            for (int i = 0; i < len; i++) {
                if ((bits[i] & otherBits[i]) != 0) return true;
            }
            return false;
        }
        if (other instanceof EEnumSetLarge<?> el) {
            int len = Math.min(bits.length, el.getSegmentCount());
            for (int i = 0; i < len; i++) {
                if ((bits[i] & el.getBits(i)) != 0) return true;
            }
            return false;
        }
        int otherSegments = other.getSegmentCount();
        if (otherSegments == bits.length) {
            for (int i = 0; i < bits.length; i++) {
                if ((bits[i] & other.getBits(i)) != 0) return true;
            }
            return false;
        }
        int maxSegments = Math.max(bits.length, otherSegments);
        for (int i = 0; i < maxSegments; i++) {
            long thisBits = i < bits.length ? bits[i] : 0L;
            if ((thisBits & other.getBits(i)) != 0) return true;
        }
        return false;
    }

    @Override
    public boolean hasAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
            return false;
        }
        if (other instanceof EEnumSetBuilderLarge<?> bl) {
            long[] otherBits = bl.rawBits();
            int len = Math.min(bits.length, otherBits.length);
            for (int i = 0; i < len; i++) {
                long ob = otherBits[i];
                if ((bits[i] & ob) != ob) return false;
            }
            return true;
        }
        if (other instanceof EEnumSetLarge<?> el) {
            int len = Math.min(bits.length, el.getSegmentCount());
            for (int i = 0; i < len; i++) {
                long ob = el.getBits(i);
                if ((bits[i] & ob) != ob) return false;
            }
            return true;
        }
        int otherSegments = other.getSegmentCount();
        if (otherSegments == bits.length) {
            for (int i = 0; i < bits.length; i++) {
                long ob = other.getBits(i);
                if ((bits[i] & ob) != ob) return false;
            }
            return true;
        }
        int maxSegments = Math.max(bits.length, otherSegments);
        for (int i = 0; i < maxSegments; i++) {
            long thisBits = i < bits.length ? bits[i] : 0L;
            long otherBits = other.getBits(i);
            if ((thisBits & otherBits) != otherBits) return false;
        }
        return true;
    }

    long[] rawBits() {
        return bits;
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
        int idx = 0;
        for (int segment = 0; segment < bits.length; segment++) {
            long remaining = bits[segment];
            while (remaining != 0) {
                int offset = Long.numberOfTrailingZeros(remaining);
                callback.accept(universe[(segment * 64) + offset], idx++);
                remaining &= (remaining - 1);
            }
        }
    }

    @Override
    public void forEach(java.util.function.Consumer<E> callback) {
        if (callback == null) return;
        for (int segment = 0; segment < bits.length; segment++) {
            long remaining = bits[segment];
            while (remaining != 0) {
                int offset = Long.numberOfTrailingZeros(remaining);
                callback.accept(universe[(segment * 64) + offset]);
                remaining &= (remaining - 1);
            }
        }
    }

    @Override
    public EEnumSetBuilder<E> addAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) return this;

        for (int i = 0; i < bits.length; i++) {
            long oldBits = bits[i];
            long otherBits = other.getBits(i);
            long combined = oldBits | otherBits;
            bits[i] = combined;
            size += Long.bitCount(combined) - Long.bitCount(oldBits);
        }

        return this;
    }

    @Override
    public EEnumSetBuilder<E> removeAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) return this;

        int delta = 0;
        for (int i = 0; i < bits.length; i++) {
            long oldBits = bits[i];
            long otherBits = other.getBits(i);
            long newBits = oldBits & ~otherBits;
            bits[i] = newBits;
            delta += Long.bitCount(newBits) - Long.bitCount(oldBits);
        }
        size += delta;

        return this;
    }

    @Override
    public EEnumSetBuilder<E> retainAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
            clear();
            return this;
        }

        int newSize = 0;
        for (int i = 0; i < bits.length; i++) {
            bits[i] &= other.getBits(i);
            newSize += Long.bitCount(bits[i]);
        }
        size = newSize;
        return this;
    }

    @Override
    public boolean addOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= universe.length) return false;
        int segment = ordinal / 64;
        long mask = 1L << (ordinal & 63);
        if ((bits[segment] & mask) != 0) return false;
        bits[segment] |= mask;
        size++;
        return true;
    }

    @Override
    public boolean add(E value) {
        return value != null && addOrdinal(value.ordinal());
    }

    @Override
    public boolean removeOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= universe.length) return false;
        int segment = ordinal / 64;
        long mask = 1L << (ordinal & 63);
        if ((bits[segment] & mask) == 0) return false;
        bits[segment] &= ~mask;
        size--;
        return true;
    }

    @Override
    public boolean remove(E value) {
        return value != null && removeOrdinal(value.ordinal());
    }

    @Override
    public void clear() {
        for (int i = 0; i < bits.length; i++) bits[i] = 0;
        size = 0;
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
    public String toString() {
        StringBuilder sb = new StringBuilder(size * 8);
        sb.append('[');
        int idx = 0;
        for (int segment = 0; segment < bits.length; segment++) {
            long remaining = bits[segment];
            while (remaining != 0) {
                if (idx > 0) sb.append(',');
                int ordinal = Long.numberOfTrailingZeros(remaining);
                sb.append(universe[(segment * 64) + ordinal].toString());
                remaining &= (remaining - 1);
                idx++;
            }
        }
        sb.append(']');
        return sb.toString();
    }

    @Override
    public EEnumSet<E> toImmutable() {
        if (size == 0) return EEnumSetEmpty.of(enumClass);
        return new EEnumSetLarge<>(enumClass, bits, size);
    }

    public static class Strict<E extends Enum<E>> extends EEnumSetBuilderLarge<E>   {
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
