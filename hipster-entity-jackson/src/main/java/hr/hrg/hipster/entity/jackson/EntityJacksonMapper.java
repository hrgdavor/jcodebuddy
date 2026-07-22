package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.ViewReader;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

import java.io.IOException;

/**
 * Reflection-free Jackson serializer/deserializer for array-backed view proxies.
 *
 * <p>Serialization uses metadata from {@link ViewMeta} and {@link ViewReader#get(int)}.
 * This module does not maintain duplicate schema objects or maps by field name.</p>
 */
public final class EntityJacksonMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private EntityJacksonMapper() {
    }

    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> void toJson(ViewMeta<V, F> meta,
                                                                 ViewReader entity,
                                                                 java.io.Writer writer) {
        try (JsonGenerator gen = OBJECT_MAPPER.createGenerator(writer)) {
            new EntityJacksonViewSerializer<>(meta).serialize(entity, gen);
            gen.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize entity", e);
        }
    }

    /**
     * One-off deserialization using a per-call {@link EntityJacksonViewDeserializer} instance.
     * Acceptable for single calls; for hot loops, pre-build the deserializer and call
     * {@link EntityJacksonViewDeserializer#deserialize(JsonParser)} directly.
     */
    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> V fromJson(ViewMeta<V, F> meta,
                                                                JsonParser p) {
        try {
            return new EntityJacksonViewDeserializer<>(meta).deserialize(p);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize entity", e);
        }
    }

    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> EntityJacksonViewModule<V, F> module(ViewMeta<V, F> meta) {
        return new EntityJacksonViewModule<>(meta);
    }

    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> void registerModule(com.fasterxml.jackson.databind.ObjectMapper mapper, ViewMeta<V, F> meta) {
        mapper.registerModule(module(meta));
    }
}
