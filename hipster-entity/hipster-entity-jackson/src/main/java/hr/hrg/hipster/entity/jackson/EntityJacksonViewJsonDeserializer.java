package hr.hrg.hipster.entity.jackson;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

import java.io.IOException;

public final class EntityJacksonViewJsonDeserializer<V extends EntityBase<?>, F extends Enum<F> & FieldDef>
        extends ValueDeserializer<V> {

    /** Pre-built, reused across all Jackson-driven deserializations of this view type. */
    private final EntityJacksonViewDeserializer<V, F> deserializer;

    public EntityJacksonViewJsonDeserializer(ViewMeta<V, F> meta) {
        this.deserializer = new EntityJacksonViewDeserializer<>(meta);
    }

    @Override
    public V deserialize(JsonParser p, DeserializationContext ctxt) {
        try {
            return deserializer.deserialize(p);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
