package hr.hrg.hipster.entityexample.person.entity;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;

public enum Write_ {

    id(java.lang.Long.class),
    firstName(java.lang.String.class),
    lastName(java.lang.String.class),
    age(java.lang.Integer.class),
    departmentName(java.lang.String.class),
    metadata(TypeUtils.parameterizedType(java.util.Map.class, java.lang.String.class, TypeUtils.parameterizedType(java.util.List.class, java.lang.Long.class))),
    toBuilder(PersonSummaryBuilder.class),
    toBuilderTracking(PersonSummaryBuilderTracking.class);

    private final Type propertyType;

    private Write_(Type propertyType) {
        this.propertyType = propertyType;
    }

    public String getPropertyName() {
        return name();
    }

    public Type getPropertyType() {
        return propertyType;
    }

    public static Write_ forName(String name) {
        if (name == null)
            return null;
        return switch (name) {
            case "id" -> id;
            case "firstName" -> firstName;
            case "lastName" -> lastName;
            case "age" -> age;
            case "departmentName" -> departmentName;
            case "metadata" -> metadata;
            case "toBuilder" -> toBuilder;
            case "toBuilderTracking" -> toBuilderTracking;
            default -> null;
        };
    }
}
