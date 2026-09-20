// {@link hr.hrg.hipster.entityexample.person.entity.PersonDetails} Field metadata for the PersonDetails view.
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

public enum PersonDetails_ implements FieldDef {

    id(java.lang.Long.class) {

        @Override()
        public String column() {
            return "id";
        }
    }
    ,
    firstName(java.lang.String.class) {

        @Override()
        public String column() {
            return "firstName";
        }
    }
    ,
    lastName(java.lang.String.class) {

        @Override()
        public String column() {
            return "lastName";
        }
    }
    ,
    email(java.lang.String.class) {

        @Override()
        public String column() {
            return "email";
        }
    }
    ,
    phoneNumber(java.lang.String.class) {

        @Override()
        public String column() {
            return "phoneNumber";
        }
    }
    ,
    createdAt(java.time.Instant.class) {

        @Override()
        public String column() {
            return "createdAt";
        }
    }
    ,
    updatedAt(java.time.Instant.class) {

        @Override()
        public String column() {
            return "updatedAt";
        }
    }
    ;

    private final Type javaType;

    private PersonDetails_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PersonDetails_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PersonDetails_.id;
            case "firstName":
                return PersonDetails_.firstName;
            case "lastName":
                return PersonDetails_.lastName;
            case "email":
                return PersonDetails_.email;
            case "phoneNumber":
                return PersonDetails_.phoneNumber;
            case "createdAt":
                return PersonDetails_.createdAt;
            case "updatedAt":
                return PersonDetails_.updatedAt;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PersonDetails_> NAME_MAPPER = PersonDetails_::forName;

    public static final ViewMeta<PersonDetails, PersonDetails_> META = new DefaultViewMeta<PersonDetails, PersonDetails_>(PersonDetails.class, PersonDetails_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(PersonDetails.class, new EntityReadArray<PersonDetails, PersonDetails_>(PersonDetails_.class, values), NAME_MAPPER), null, "", new Class<?>[0]);
}
