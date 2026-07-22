package hr.hrg.hipster.entityexample.example;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;

public enum PaymentMethodAuditableProperty {

    id(java.lang.Object.class);

    private final Type propertyType;

    private PaymentMethodAuditableProperty(Type propertyType) {
        this.propertyType = propertyType;
    }

    public String getPropertyName() {
        return name();
    }

    public Type getPropertyType() {
        return propertyType;
    }

    public static PaymentMethodAuditableProperty forName(String name) {
        if (name == null)
            return null;
        if (name.equals("id"))
            return id;
        return null;
    }
}
