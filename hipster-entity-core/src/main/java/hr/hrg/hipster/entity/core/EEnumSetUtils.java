package hr.hrg.hipster.entity.core;

import java.util.Objects;

final class EEnumSetUtils {

    private EEnumSetUtils() {
    }

    static <E extends Enum<E>> boolean equals(EEnumSetRead<E> self, Object o) {
        if (self == o) return true;
        if (!(o instanceof EEnumSetRead<?> other)) return false;
        if (!Objects.equals(self.getEnumClass(), other.getEnumClass())) return false;

        int selfSegments = self.getSegmentCount();
        int otherSegments = other.getSegmentCount();
        int max = Math.max(selfSegments, otherSegments);

        for (int i = 0; i < max; i++) {
            if (self.getBits(i) != other.getBits(i)) return false;
        }

        return true;
    }

    static <E extends Enum<E>> int hashCode(EEnumSetRead<E> self) {
        int result = Objects.hashCode(self.getEnumClass());
        int segments = self.getSegmentCount();
        for (int i = 0; i < segments; i++) {
            result = 31 * result + Long.hashCode(self.getBits(i));
        }
        return result;
    }
}
