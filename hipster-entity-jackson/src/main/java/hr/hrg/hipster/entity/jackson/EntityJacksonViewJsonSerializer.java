package hr.hrg.hipster.entity.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.ViewReader;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

import java.io.IOException;

public final class EntityJacksonViewJsonSerializer<V extends EntityBase<?>, F extends Enum<F> & FieldDef>
        extends ValueSerializer<V> {

    private final EntityJacksonViewSerializer<V, F> serializer;

    public EntityJacksonViewJsonSerializer(ViewMeta<V, F> meta) {
        this.serializer = new EntityJacksonViewSerializer<>(meta);
    }

    @Override
    public void serialize(V value, JsonGenerator gen, SerializationContext serializers) {
        if (!(value instanceof ViewReader reader)) {
            throw new IllegalStateException("Value is not EntityReader: " + value.getClass());
        }
        ViewReader typedReader = (ViewReader) reader;
        try {
            serializer.serialize(typedReader, gen);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
