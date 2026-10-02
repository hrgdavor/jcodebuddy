package hr.hrg.hipster.entity.core;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("unchecked")
public final class EEnumSetLarge<E extends Enum<E>> implements EEnumSet<E> {

    private final long[] bits;
    private final int size;
    private final E[] universe;
    private final Class<E> enumClass;

    EEnumSetLarge(Class<E> enumClass, E... values) {
        this.enumClass = enumClass;

        universe = (E[]) enumClass.getEnumConstants();
        bits = new long[(universe.length + 63) / 64];

        int count = 0;
        for (E en : values) {
            int ordinal = en.ordinal();
            int segment = ordinal / 64;
            long mask = 1L << (ordinal & 63);
            if ((bits[segment] & mask) == 0) {
                count++;
                bits[segment] |= mask;
            }
        }
        this.size = count;
    }

    EEnumSetLarge(Class<E> enumClass, List<E> values) {
        this.enumClass = enumClass;

        universe = (E[]) enumClass.getEnumConstants();
        bits = new long[(universe.length + 63) / 64];

        int count = 0;
        for (E en : values) {
            int ordinal = en.ordinal();
            int segment = ordinal / 64;
            long mask = 1L << (ordinal & 63);
            if ((bits[segment] & mask) == 0) {
                count++;
                bits[segment] |= mask;
            }
        }
        this.size = count;
    }

    EEnumSetLarge(Class<E> enumClass, long[] bits, int size) {
        this.enumClass = enumClass;
        universe = (E[]) enumClass.getEnumConstants();
        this.bits = Arrays.copyOf(bits, (universe.length + 63) / 64);
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
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean has(int ordinal) {
        if (ordinal < 0) return false;
        int index = ordinal / 64;
        if (index >= bits.length) return false;
        return (bits[index] & (1L << (ordinal & 63))) != 0;
    }

    @Override
    public boolean has(E key) {
        return key != null && has(key.ordinal());
    }

    @Override
    public boolean hasAll(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
            return false;
        }
        if (other instanceof EEnumSetLarge<?> el) {
            long[] otherBits = el.bits;
            int len = Math.min(bits.length, otherBits.length);
            for (int i = 0; i < len; i++) {
                long ob = otherBits[i];
                if ((bits[i] & ob) != ob) return false;
            }
            return true;
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

    @Override
    public boolean hasAny(EEnumSetRead<E> other) {
        if (other == null || !Objects.equals(enumClass, other.getEnumClass())) {
            return false;
        }
        if (other instanceof EEnumSetLarge<?> el) {
            long[] otherBits = el.bits;
            int len = Math.min(bits.length, otherBits.length);
            for (int i = 0; i < len; i++) {
                if ((bits[i] & otherBits[i]) != 0) return true;
            }
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
        int otherSegments = other.getSegmentCount();
        if (otherSegments == bits.length) {
            for (int i = 0; i < bits.length; i++) {
                if ((bits[i] & other.getBits(i)) != 0) return true;
            }
            return false;
        }
        int maxSegments = Math.max(bits.length, otherSegments);
        for (int i = 0; i < maxSegments; i++) {
            long otherBits = other.getBits(i);
            long thisBits = i < bits.length ? bits[i] : 0L;
            if ((thisBits & otherBits) != 0) return true;
        }
        return false;
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
        for (int segment = 0; segment < bits.length; segment++) {
            long remaining = bits[segment];
            while (remaining != 0) {
                if (idx > 0) sb.append(',');
                int offset = Long.numberOfTrailingZeros(remaining);
                sb.append(universe[(segment * 64) + offset].toString());
                remaining &= (remaining - 1);
                idx++;
            }
        }
        sb.append(']');
        return sb.toString();
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
    public EEnumSetBuilder<E> toBuilder() {
        return new EEnumSetBuilderLarge<>(enumClass, bits, size);
    }

    @Override
    public int getSegmentCount() {
        return bits.length;
    }
}
