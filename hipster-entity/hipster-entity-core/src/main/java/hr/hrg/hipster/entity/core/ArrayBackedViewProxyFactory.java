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

    /**
     * Wraps an {@link EntityUpdateTrackingArray} as an updatable view proxy.
     *
     * <p>The proxy exposes the tracking contract ({@link ViewChangeTracking}) in addition to the
     * view type, so a caller can read {@code changes()}/{@code changesBuilder()}/{@code changedValues()}
     * straight off the proxy. The handler returns the array's own builder for
     * {@code changesBuilder()}, which is what makes the proxy satisfy the S5 state-sharing rule
     * without a wrapper (plan.dsflash § 6.2/1.9).</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <F extends Enum<F> & hr.hrg.hipster.entity.api.FieldDef, V>
    V createUpdatable(
            Class<V> viewType,
            EntityUpdateTrackingArray<?, F> updateArray,
            hr.hrg.hipster.entity.api.FieldNameMapper<F> fieldByMethodName
    ) {
        InvocationHandler handler = new UpdatableHandler<>(updateArray, fieldByMethodName::forName, viewType.getSimpleName() + "Proxy");
        return viewType.cast(Proxy.newProxyInstance(
                viewType.getClassLoader(),
                new Class[]{viewType, ViewChangeTracking.class},
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

    private static final class UpdatableHandler<F extends Enum<F> & hr.hrg.hipster.entity.api.FieldDef> implements InvocationHandler {
        private final EntityUpdateTrackingArray<?, F> updateArray;
        private final Function<String, F> fieldByMethodName;
        private final String label;

        private UpdatableHandler(EntityUpdateTrackingArray<?, F> updateArray, Function<String, F> fieldByMethodName, String label) {
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
                // Zero-arg tracking arms MUST come before the fieldByMethodName lookup below,
                // otherwise isChanged/changedValues reach the lookup, resolve to null and throw
                // "Unsupported accessor". Arm placement is part of the contract (plan.dsflash
                // § 6.2/1.9).
                switch (name) {
                    case "isChanged" -> {
                        return updateArray.isChanged();
                    }
                    case "changes" -> {
                        return updateArray.changes();
                    }
                    case "changesBuilder" -> {
                        // The array's own builder, not a copy: this is what makes the proxy
                        // satisfy the S5 state-sharing requirement without a wrapper.
                        return updateArray.changesBuilder();
                    }
                    case "changedValues" -> {
                        return updateArray.changedValues();
                    }
                    case "changesDeep" -> {
                        // The deep mirror of changes(): the same walk a generated tracking builder
                        // performs, driven from the values this array holds (plan.dsflash § 11/6.7).
                        return updateArray.changesDeep();
                    }
                    case "shallowPaths" -> {
                        return updateArray.shallowPaths();
                    }
                    case "nestedTrackers" -> {
                        return updateArray.nestedTrackers();
                    }
                    case "collectionDeltas" -> {
                        return updateArray.collectionDeltas();
                    }
                    case "collectionDiagnostics" -> {
                        return updateArray.collectionDiagnostics();
                    }
                    case "snapshotCollections" -> {
                        updateArray.snapshotCollections();
                        return null;
                    }
                    case "clearChanges" -> {
                        updateArray.clearChanges();
                        return null;
                    }
                    default -> {
                        // fall through to the field lookup below
                    }
                }

                F field = fieldByMethodName.apply(name);
                if (field == null) {
                    throw new IllegalArgumentException("Unsupported accessor: " + name);
                }
                return updateArray.get(field.ordinal());
            }

            // One-arg tracking arms MUST come before the generic parameterCount == 1 mutator
            // fallback below, which would otherwise treat a read as a write.
            if (parameterCount == 1 && name.equals("collectionDeltas")) {
                return updateArray.collectionDeltas(((Number) args[0]).intValue());
            }
            if (parameterCount == 1 && name.equals("hasCollection")) {
                return updateArray.hasCollection(((Number) args[0]).intValue());
            }
            if (parameterCount == 1 && name.equals("snapshotCollection")) {
                updateArray.snapshotCollection(((Number) args[0]).intValue());
                return null;
            }
            if (name.equals("get") && parameterCount == 1) {
                if (args[0] instanceof String fieldName) {
                    return updateArray.get(fieldName);
                }
                @SuppressWarnings("unchecked")
                F field = (F) args[0];
                return updateArray.get(field.ordinal());
            }

            if (name.equals("set") && parameterCount == 2) {
                // Two spellings reach here and they must be told apart by the *declared parameter
                // type*, never by the runtime argument type: ViewWriter declares both
                // set(String, Object) and set(int, Object), and for an interface whose methods are
                // generic/inherited the proxy delivers a raw Object parameter for the field form,
                // so the runtime argument may be the field constant itself. Dispatching on args[0]
                // would therefore send the field form down the name form's path, or vice versa.
                Class<?> firstParameter = method.getParameterTypes()[0];
                if (firstParameter == int.class || firstParameter == Integer.class) {
                    if (args[0] instanceof Number ordinal) {
                        updateArray.set(ordinal.intValue(), args[1]);
                        return null;
                    }
                    @SuppressWarnings("unchecked")
                    F field = (F) args[0];
                    updateArray.set(field.ordinal(), args[1]);
                    return null;
                }
                if (firstParameter == String.class) {
                    int ordinal = updateArray.set((String) args[0], args[1]);
                    if (ordinal == -1) {
                        throw new IllegalArgumentException("Unsupported mutator: " + args[0]);
                    }
                    return null;
                }
                if (args[0] instanceof Enum<?> field) {
                    updateArray.set(field.ordinal(), args[1]);
                    return null;
                }
                // void methods: You must return null. Returning any other value will cause a ClassCastException at runtime 
                // because the proxy expects no result.
                return null;
            }

            if (parameterCount == 1) {
                // Read arms that take a field constant MUST come before the generic mutator fallback
                // below, which would otherwise treat them as a write to a field named like the
                // method. currentValue is declared on ViewChangeTracking for exactly the field
                // type this proxy's view uses.
                if (name.equals("currentValue") && args[0] instanceof FieldDef field) {
                    @SuppressWarnings("unchecked")
                    F typed = (F) field;
                    return updateArray.currentValue(typed);
                }

                // Fluent setter of the form `<View> someField(Object value)`: resolve the field by
                // method name, exactly like the zero-arg read path.
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
