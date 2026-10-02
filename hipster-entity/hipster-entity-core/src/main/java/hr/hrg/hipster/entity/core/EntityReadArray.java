package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.ViewReader;

public final class EntityReadArray<T, F extends Enum<F>> implements ViewReader {

    private final Class<F> enumClass;
    private final Object[] values;

    public EntityReadArray(Class<F> enumClass, Object ...values) {
        if(enumClass.getEnumConstants().length != values.length) {
            throw new IllegalArgumentException("View field count and data provided lengths do not match");
        }
        this.enumClass = enumClass;
        this.values = values;
    }

    @Override
    public Object get(int fieldOrdinal) {
        return values[fieldOrdinal];
    }
}
