// {@link hr.hrg.hipster.entityexample.person.entity.PersonUpdatableView} Field metadata for the PersonUpdatableView view.
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

public enum PersonUpdatableView_ implements FieldDef {

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

    private PersonUpdatableView_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PersonUpdatableView_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PersonUpdatableView_.id;
            case "email":
                return PersonUpdatableView_.email;
            case "phoneNumber":
                return PersonUpdatableView_.phoneNumber;
            case "firstName":
                return PersonUpdatableView_.firstName;
            case "lastName":
                return PersonUpdatableView_.lastName;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PersonUpdatableView_> NAME_MAPPER = PersonUpdatableView_::forName;

    public static final ViewMeta<PersonUpdatableView, PersonUpdatableView_> META = new DefaultViewMeta<PersonUpdatableView, PersonUpdatableView_>(PersonUpdatableView.class, PersonUpdatableView_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(PersonUpdatableView.class, new EntityReadArray<PersonUpdatableView, PersonUpdatableView_>(PersonUpdatableView_.class, values), NAME_MAPPER), null, "", new Class<?>[0]);
}
