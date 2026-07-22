package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.ViewReader;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

import java.io.IOException;
import java.lang.reflect.Type;

public final class EntityJacksonViewSerializer<V extends EntityBase<?>, F extends Enum<F> & FieldDef> {

    @FunctionalInterface
    private interface FieldWriter {
        void write(JsonGenerator gen, Object value) throws IOException;
    }

    private final F[] fields;
    private final String[] fieldNames;
    private final byte[] fieldTypeCode;

    private static final byte TYPE_STRING = 1;
    private static final byte TYPE_INT = 2;
    private static final byte TYPE_LONG = 3;
    private static final byte TYPE_DOUBLE = 4;
    private static final byte TYPE_FLOAT = 5;
    private static final byte TYPE_BOOLEAN = 6;
    private static final byte TYPE_OBJECT = 7;

    public EntityJacksonViewSerializer(ViewMeta<V, F> meta) {
        this.fields = meta.fieldValues();
        this.fieldNames = new String[fields.length];
        this.fieldTypeCode = new byte[fields.length];

        for (int i = 0, n = fields.length; i < n; i++) {
            F f = fields[i];
            final String fieldName = f.name();
            fieldNames[i] = fieldName;
            Type type = f.javaType();

            if (type == String.class) {
                fieldTypeCode[i] = TYPE_STRING;
            } else if (type == Integer.class || type == int.class) {
                fieldTypeCode[i] = TYPE_INT;
            } else if (type == Long.class || type == long.class) {
                fieldTypeCode[i] = TYPE_LONG;
            } else if (type == Double.class || type == double.class) {
                fieldTypeCode[i] = TYPE_DOUBLE;
            } else if (type == Float.class || type == float.class) {
                fieldTypeCode[i] = TYPE_FLOAT;
            } else if (type == Boolean.class || type == boolean.class) {
                fieldTypeCode[i] = TYPE_BOOLEAN;
            } else {
                fieldTypeCode[i] = TYPE_OBJECT;
            }
        }
    }

    public void serialize(ViewReader entity, JsonGenerator gen) throws IOException {
        gen.writeStartObject();

        for (int i = 0, fieldCount = fields.length; i < fieldCount; i++) {
            String name = fieldNames[i];
            Object value = entity.get(i);

            gen.writeFieldName(name);
            if (value == null) {
                gen.writeNull();
                continue;
            }

            switch (fieldTypeCode[i]) {
                case TYPE_STRING -> gen.writeString((String) value);
                case TYPE_INT -> gen.writeNumber((Integer) value);
                case TYPE_LONG -> gen.writeNumber((Long) value);
                case TYPE_DOUBLE -> gen.writeNumber((Double) value);
                case TYPE_FLOAT -> gen.writeNumber((Float) value);
                case TYPE_BOOLEAN -> gen.writeBoolean((Boolean) value);
                default -> gen.writeObject(value);
            }
        }

        gen.writeEndObject();
    }

}
