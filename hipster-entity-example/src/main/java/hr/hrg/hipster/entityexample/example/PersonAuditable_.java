package hr.hrg.hipster.entityexample.example;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;

public enum PersonAuditable_ {

    id(java.lang.Object.class);

    private final Type propertyType;

    private PersonAuditable_(Type propertyType) {
        this.propertyType = propertyType;
    }

    public String getPropertyName() {
        return name();
    }

    public Type getPropertyType() {
        return propertyType;
    }

    public static PersonAuditable_ forName(String name) {
        if (name == null)
            return null;
        return switch (name) {
            case "id" -> id;
            default -> null;
        };
    }
}
