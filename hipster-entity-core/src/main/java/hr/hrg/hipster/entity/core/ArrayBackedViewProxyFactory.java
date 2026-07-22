package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.ViewReader;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;

public final class ArrayBackedViewProxyFactory {

    private ArrayBackedViewProxyFactory() {
    }

    public static <ID, T extends EntityBase<ID>, V, F extends Enum<F> & FieldDef>
    V createRead(
            ViewMeta<V, F> meta,
            EntityReadArray<T, F> readArray
    ) {
        return createRead(meta.viewType(), readArray, meta.forName());
    }

    public static <ID, T extends EntityBase<ID>, F extends Enum<F> & hr.hrg.hipster.entity.api.FieldDef, V>
    V createRead(
            Class<V> viewType,
            EntityReadArray<T, F> readArray,
            hr.hrg.hipster.entity.api.FieldNameMapper<F> fieldByMethodName
    ) {
        InvocationHandler handler = new ReadHandler<>(readArray, fieldByMethodName::forName, viewType.getSimpleName() + "Proxy");
        return viewType.cast(Proxy.newProxyInstance(
                viewType.getClassLoader(),
                new Class[]{viewType, ViewReader.class},
                handler
        ));
    }

    public static <ID, T extends EntityBase<ID>, F extends Enum<F> & hr.hrg.hipster.entity.api.FieldDef, V>
    V createUpdatable(
            Class<V> viewType,
            EntityUpdateTrackingArray<T, F> updateArray,
            hr.hrg.hipster.entity.api.FieldNameMapper<F> fieldByMethodName
    ) {
        InvocationHandler handler = new UpdatableHandler<>(updateArray, fieldByMethodName::forName, viewType.getSimpleName() + "Proxy");
        return viewType.cast(Proxy.newProxyInstance(
                viewType.getClassLoader(),
                new Class[]{viewType},
                handler
        ));
    }

    private static Object handleObjectMethods(Object proxy, Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "toString" -> label;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new IllegalStateException("Unexpected Object method: " + method.getName());
        };
    }

    private static final class ReadHandler<T, F extends Enum<F> & hr.hrg.hipster.entity.api.FieldDef> implements InvocationHandler {
        private final EntityReadArray<T, F> readArray;
        private final Function<String, F> fieldByMethodName;
        private final String label;

        private ReadHandler(EntityReadArray<T, F> readArray, Function<String, F> fieldByMethodName, String label) {
            this.readArray = readArray;
            this.fieldByMethodName = fieldByMethodName;
            this.label = label;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethods(proxy, method, args, label);
            }
            if (method.getDeclaringClass() == ViewReader.class
                    && method.getName().equals("get") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class) {
                return readArray.get((int) args[0]);
            }
            if (method.getParameterCount() != 0) {
                throw new UnsupportedOperationException("Read proxy supports only zero-arg accessors");
            }

            F field = fieldByMethodName.apply(method.getName());
            if (field == null) {
                throw new IllegalArgumentException("Unsupported accessor: " + method.getName());
            }
            return readArray.get(field.ordinal());
        }
    }

    private static final class UpdatableHandler<T, F extends Enum<F> & hr.hrg.hipster.entity.api.FieldDef> implements InvocationHandler {
        private final EntityUpdateTrackingArray<T, F> updateArray;
        private final Function<String, F> fieldByMethodName;
        private final String label;

        private UpdatableHandler(EntityUpdateTrackingArray<T, F> updateArray, Function<String, F> fieldByMethodName, String label) {
            this.updateArray = updateArray;
            this.fieldByMethodName = fieldByMethodName;
            this.label = label;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethods(proxy, method, args, label);
            }

            String name = method.getName();
            int parameterCount = method.getParameterCount();

            if (parameterCount == 0) {
                if (name.equals("changes")) {
                    return updateArray.changesSnapshot();
                }
                if (name.equals("clearChanges")) {
                    updateArray.clear();
                    return null;
                }

                F field = fieldByMethodName.apply(name);
                if (field == null) {
                    throw new IllegalArgumentException("Unsupported accessor: " + name);
                }
                return updateArray.get(field.ordinal());
            }

            if (name.equals("get") && parameterCount == 1) {
                @SuppressWarnings("unchecked")
                F field = (F) args[0];
                return updateArray.get(field.ordinal());
            }

            if (name.equals("set") && parameterCount == 2) {
                @SuppressWarnings("unchecked")
                F field = (F) args[0];
                Object value = args[1];
                updateArray.set(field.ordinal(), value);
                // void methods: You must return null. Returning any other value will cause a ClassCastException at runtime 
                // because the proxy expects no result.
                return null;
            }

            if (parameterCount == 1) {
                int ordinal = updateArray.set(name, args[0]);
                if (ordinal == -1) {
                    throw new IllegalArgumentException("Unsupported mutator: " + name);
                }
                return proxy;
            }

            throw new UnsupportedOperationException("Unsupported method on updatable proxy: " + method);
        }
    }
}
