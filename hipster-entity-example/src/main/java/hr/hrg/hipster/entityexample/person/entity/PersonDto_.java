// {@link hr.hrg.hipster.entityexample.person.entity.PersonDto} Field metadata for the PersonDto view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.DefaultViewMeta;
import java.util.List;
import java.util.Map;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

public enum PersonDto_ implements FieldDef {

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
    age(java.lang.Integer.class) {

        @Override()
        public FieldKind fieldKind() {
            return FieldKind.DERIVED;
        }

        @Override()
        public String expression() {
            return "YEAR(NOW()) - YEAR(birthDate)";
        }
    }
    ,
    departmentName(java.lang.String.class) {

        @Override()
        public FieldKind fieldKind() {
            return FieldKind.JOINED;
        }

        @Override()
        public String relation() {
            return "department.name";
        }
    }
    ,
    metadata(TypeUtils.parameterizedType(java.util.Map.class, java.lang.String.class, TypeUtils.parameterizedType(java.util.List.class, java.lang.Long.class))) {

        @Override()
        public String column() {
            return "metadata";
        }
    }
    ;

    private final Type javaType;

    private PersonDto_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PersonDto_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PersonDto_.id;
            case "firstName":
                return PersonDto_.firstName;
            case "lastName":
                return PersonDto_.lastName;
            case "age":
                return PersonDto_.age;
            case "departmentName":
                return PersonDto_.departmentName;
            case "metadata":
                return PersonDto_.metadata;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PersonDto_> NAME_MAPPER = PersonDto_::forName;

    public static final ViewMeta<PersonDto, PersonDto_> META = new DefaultViewMeta<PersonDto, PersonDto_>(PersonDto.class, PersonDto_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(PersonDto.class, new EntityReadArray<PersonDto, PersonDto_>(PersonDto_.class, values), NAME_MAPPER), null, "", new Class<?>[0]);
}
