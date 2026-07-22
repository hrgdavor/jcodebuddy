package hr.hrg.hipster.entity.api;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public final class TypeUtils {

    private TypeUtils() {
    }

    public static ParameterizedType parameterizedType(final Class<?> raw, final Type... args) {
        return new ParamType(raw, args);
    }

    public static ParameterizedType mapType(Type key, Type value) {
        return new ParamType(Map.class, key, value);
    }

    public static ParameterizedType listType(Type item) {
        return new ParamType(List.class, item);
    }

    public record ParamType(Class<?> raw, Type... args) implements ParameterizedType{
        @Override
        public Type[] getActualTypeArguments() {
            return args;
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    }
}
