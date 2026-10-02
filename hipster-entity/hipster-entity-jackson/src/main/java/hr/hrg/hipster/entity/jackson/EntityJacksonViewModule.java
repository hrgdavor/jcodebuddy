package hr.hrg.hipster.entity.jackson;

import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

public final class EntityJacksonViewModule<V extends EntityBase<?>, F extends Enum<F> & FieldDef>
        extends SimpleModule {

    public EntityJacksonViewModule(ViewMeta<V, F> meta) {
        super("EntityJacksonViewModule-" + meta.viewType().getSimpleName());
        addSerializer(meta.viewType(), (ValueSerializer<V>) new EntityJacksonViewJsonSerializer<>(meta));
        addDeserializer(meta.viewType(), (ValueDeserializer<? extends V>) new EntityJacksonViewJsonDeserializer<>(meta));
    }
}
