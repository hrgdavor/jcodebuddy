package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixtures shared by {@link EntityUpdateTrackingArrayTest} and
 * {@link ViewChangeTrackingStateSharingTest}: two {@link FieldDef} enums, one small enough for the
 * {@code 64} builder variant and one with 65 constants to select the {@code Large} variant at the
 * boundary (plan.dsflash § 6.2/1.11).
 */
final class TrackingTestFixtures {

    private TrackingTestFixtures() {
    }

    /** Seven fields — comfortably inside the 64-bit variant. */
    enum Fields implements FieldDef {
        id(Long.class),
        firstName(String.class),
        lastName(String.class),
        age(Integer.class),
        departmentName(String.class),
        metadata(Map.class),
        tags(List.class);

        private final Class<?> javaType;

        Fields(Class<?> javaType) {
            this.javaType = javaType;
        }

        @Override
        public Type javaType() {
            return javaType;
        }

        public static Fields forName(String name) {
            if (name == null) {
                return null;
            }
            return switch (name) {
                case "id" -> id;
                case "firstName" -> firstName;
                case "lastName" -> lastName;
                case "age" -> age;
                case "departmentName" -> departmentName;
                case "metadata" -> metadata;
                case "tags" -> tags;
                default -> null;
            };
        }
    }

    /** A built-in field universe: exactly 65 constants, the first size that must select Large. */
    enum BuiltIn implements FieldDef {
        id, firstName, lastName, age, departmentName, metadata, tags,
        f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18, f19,
        f20, f21, f22, f23, f24, f25, f26, f27, f28, f29,
        f30, f31, f32, f33, f34, f35, f36, f37, f38, f39,
        f40, f41, f42, f43, f44, f45, f46, f47, f48, f49,
        f50, f51, f52, f53, f54, f55, f56, f57, f58, f59,
        f60, f61, f62, f63, f64;

        @Override
        public Type javaType() {
            return Integer.class;
        }

        public static BuiltIn forName(String name) {
            if (name == null) {
                return null;
            }
            for (BuiltIn value : values()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return null;
        }
    }

    static final ForNameOrdinalImpl<Fields> ORDINALS = new ForNameOrdinalImpl<>(Fields.class);
    static final ForNameOrdinalImpl<BuiltIn> ORDINALS_LARGE = new ForNameOrdinalImpl<>(BuiltIn.class);

    /**
     * A positional array whose slot {@code i} holds {@code "v<i>"}, with a non-null identity at
     * ordinal 0. Both universes therefore agree on {@code values[1]} and {@code values[63]}.
     */
    static Object[] values(FieldDef[] universe) {
        Object[] values = new Object[universe.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = "v" + i;
        }
        values[0] = 1L;
        return values;
    }

    /** The 7-field variant: one of the two flavours the {@code EntityUpdateTrackingArray} takes. */
    static EntityUpdateTrackingArray<Object, Fields> tracking() {
        Object[] values = values(Fields.values());
        return EntityUpdateTrackingArray.create(ORDINALS, Fields.values(), values);
    }

    /** The 65-field variant, which must select {@code EntityUpdateTrackingArrayLarge}. */
    static EntityUpdateTrackingArray<Object, BuiltIn> trackingLarge() {
        Object[] values = values(BuiltIn.values());
        return EntityUpdateTrackingArray.create(ORDINALS_LARGE, BuiltIn.values(), values);
    }

    /** Names of the marked fields in ascending ordinal order, for readable assertions. */
    static <E extends Enum<E>> List<String> names(EEnumSetRead<E> set) {
        List<String> names = new ArrayList<>();
        set.forEach((value, index) -> names.add(value.name()));
        return names;
    }

    static Map<String, Object> map(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}
