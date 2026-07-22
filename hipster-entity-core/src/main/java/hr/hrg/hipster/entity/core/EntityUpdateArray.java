package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.ViewWriter;

public final class EntityUpdateArray<T, F extends Enum<F> & FieldDef> implements ViewWriter {

    private final Object[] values;
    private final FieldNameMapper<F> forName;

    public EntityUpdateArray(ViewMeta<T, F> meta, Object ...values) {
        this.forName = meta.forName();
        this.values = values;
    }

    @Override
    public Object get(int fieldOrdinal) {
        return values[fieldOrdinal];
    }

    @Override
    public void set(int fieldOrdinal, Object value) {
        if (fieldOrdinal < 0 || fieldOrdinal >= values.length) {
            throw new IndexOutOfBoundsException("Field ordinal out of bounds: " + fieldOrdinal);
        }
        if (fieldOrdinal == 0) {
            throw new UnsupportedOperationException("ID field is immutable in EntityUpdateArray");
        }
        values[fieldOrdinal] = value;
    }

    @Override
    public int set(String fieldName, Object value) {
        F field  = forName.forName(fieldName);
        if (field == null) {
            return -1; // field not found
        }
        values[field.ordinal()] = value;
        return field.ordinal();
    }
}
