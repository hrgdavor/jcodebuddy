// @generated file hr.hrg.hipster.entity.tooling.EntityMetadataGenerator — Field metadata for the PersonUpdateForm view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

public enum PersonUpdateForm_ implements FieldDef {

    id(java.lang.Long.class) {

        @Override()
        public String column() {
            return "id";
        }
    }
    , email(java.lang.String.class) {

        @Override()
        public String column() {
            return "email";
        }
    }
    , phoneNumber(java.lang.String.class) {

        @Override()
        public String column() {
            return "phoneNumber";
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
    ;

    private final Type javaType;

    private PersonUpdateForm_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PersonUpdateForm_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PersonUpdateForm_.id;
            case "email":
                return PersonUpdateForm_.email;
            case "phoneNumber":
                return PersonUpdateForm_.phoneNumber;
            case "firstName":
                return PersonUpdateForm_.firstName;
            case "lastName":
                return PersonUpdateForm_.lastName;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PersonUpdateForm_> NAME_MAPPER = PersonUpdateForm_::forName;

    public static final ViewMeta<PersonUpdateForm, PersonUpdateForm_> META = new DefaultViewMeta<PersonUpdateForm, PersonUpdateForm_>(PersonUpdateForm.class, PersonUpdateForm_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(PersonUpdateForm.class, new EntityReadArray<PersonUpdateForm, PersonUpdateForm_>(PersonUpdateForm_.class, values), NAME_MAPPER), null, "", new Class<?>[0]);
}
