// @generated file hr.hrg.hipster.entity.tooling.EntityMetadataGenerator — Field metadata for the PersonSummary view.
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

public enum PersonSummary_ implements FieldDef {

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

    private PersonSummary_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PersonSummary_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PersonSummary_.id;
            case "firstName":
                return PersonSummary_.firstName;
            case "lastName":
                return PersonSummary_.lastName;
            case "age":
                return PersonSummary_.age;
            case "departmentName":
                return PersonSummary_.departmentName;
            case "metadata":
                return PersonSummary_.metadata;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PersonSummary_> NAME_MAPPER = PersonSummary_::forName;

    public static final ViewMeta<PersonSummary, PersonSummary_> META = new DefaultViewMeta<PersonSummary, PersonSummary_>(PersonSummary.class, PersonSummary_.class, NAME_MAPPER, (Object[] values) -> new PersonSummary.Record((java.lang.Long) values[0], (String) values[1], (String) values[2], (Integer) values[3], (String) values[4], (Map<String, List<Long>>) values[5]), null, "", new Class<?>[0]);
}
