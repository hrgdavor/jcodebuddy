package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

import java.io.IOException;

public final class EntityJacksonViewJsonDeserializer<V extends EntityBase<?>, F extends Enum<F> & FieldDef>
        extends JsonDeserializer<V> {

    /** Pre-built, reused across all Jackson-driven deserializations of this view type. */
    private final EntityJacksonViewDeserializer<V, F> deserializer;

    public EntityJacksonViewJsonDeserializer(ViewMeta<V, F> meta) {
        this.deserializer = new EntityJacksonViewDeserializer<>(meta);
    }

    @Override
    public V deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return deserializer.deserialize(p);
    }
}
