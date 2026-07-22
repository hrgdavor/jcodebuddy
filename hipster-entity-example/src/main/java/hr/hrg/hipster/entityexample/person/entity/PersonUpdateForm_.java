package hr.hrg.hipster.entityexample.person.entity;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;

public enum PersonUpdateForm_ {

    id(java.lang.Long.class), firstName(java.lang.String.class), lastName(java.lang.String.class), email(java.lang.String.class), phoneNumber(java.lang.String.class);

    private final Type propertyType;

    private PersonUpdateForm_(Type propertyType) {
        this.propertyType = propertyType;
    }

    public String getPropertyName() {
        return name();
    }

    public Type getPropertyType() {
        return propertyType;
    }

    public static PersonUpdateForm_ forName(String name) {
        if (name == null)
            return null;
        return switch (name) {
            case "id" -> id;
            case "firstName" -> firstName;
            case "lastName" -> lastName;
            case "email" -> email;
            case "phoneNumber" -> phoneNumber;
            default -> null;
        };
    }
}
