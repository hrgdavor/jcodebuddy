package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.databind.module.SimpleModule;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

public final class EntityJacksonViewModule<V extends EntityBase<?>, F extends Enum<F> & FieldDef>
        extends SimpleModule {

    public EntityJacksonViewModule(ViewMeta<V, F> meta) {
        super("EntityJacksonViewModule-" + meta.viewType().getSimpleName());
        addSerializer(meta.viewType(), new EntityJacksonViewJsonSerializer<>(meta));
        addDeserializer(meta.viewType(), new EntityJacksonViewJsonDeserializer<>(meta));
    }
}
