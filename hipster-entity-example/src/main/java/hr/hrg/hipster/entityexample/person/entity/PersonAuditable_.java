// {@link hr.hrg.hipster.entityexample.person.entity.PersonAuditable} Field metadata for the PersonAuditable view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.DefaultViewMeta;
import java.time.Instant;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

public enum PersonAuditable_ implements FieldDef {

    id(java.lang.Object.class) {

        @Override()
        public String column() {
            return "id";
        }
    }
    , firstName(java.lang.String.class) {

        @Override()
        public String column() {
            return "firstName";
        }
    }
    , lastName(java.lang.String.class) {

        @Override()
        public String column() {
            return "lastName";
        }
    }
    , createdAt(java.time.Instant.class) {

        @Override()
        public String column() {
            return "createdAt";
        }
    }
    , updatedAt(java.time.Instant.class) {

        @Override()
        public String column() {
            return "updatedAt";
        }
    }
    ;

    private final Type javaType;

    private PersonAuditable_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PersonAuditable_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PersonAuditable_.id;
            case "firstName":
                return PersonAuditable_.firstName;
            case "lastName":
                return PersonAuditable_.lastName;
            case "createdAt":
                return PersonAuditable_.createdAt;
            case "updatedAt":
                return PersonAuditable_.updatedAt;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PersonAuditable_> NAME_MAPPER = PersonAuditable_::forName;

    public static final ViewMeta<PersonAuditable, PersonAuditable_> META = new DefaultViewMeta<PersonAuditable, PersonAuditable_>(PersonAuditable.class, PersonAuditable_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(PersonAuditable.class, new EntityReadArray<PersonAuditable, PersonAuditable_>(PersonAuditable_.class, values), NAME_MAPPER), null, "", new Class<?>[0]);
}
