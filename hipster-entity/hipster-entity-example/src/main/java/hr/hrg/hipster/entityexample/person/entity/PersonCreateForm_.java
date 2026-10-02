// @generated file hr.hrg.hipster.entity.tooling.EntityMetadataGenerator — Field metadata for the PersonCreateForm view.
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

public enum PersonCreateForm_ implements FieldDef {

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
    ;

    private final Type javaType;

    private PersonCreateForm_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PersonCreateForm_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PersonCreateForm_.id;
            case "firstName":
                return PersonCreateForm_.firstName;
            case "lastName":
                return PersonCreateForm_.lastName;
            case "email":
                return PersonCreateForm_.email;
            case "phoneNumber":
                return PersonCreateForm_.phoneNumber;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PersonCreateForm_> NAME_MAPPER = PersonCreateForm_::forName;

    public static final ViewMeta<PersonCreateForm, PersonCreateForm_> META = new DefaultViewMeta<PersonCreateForm, PersonCreateForm_>(PersonCreateForm.class, PersonCreateForm_.class, NAME_MAPPER, (Object[] values) -> new PersonCreateFormRecord((java.lang.Object) values[0], (String) values[1], (String) values[2], (String) values[3], (String) values[4]), null, "", new Class<?>[0]);
}
