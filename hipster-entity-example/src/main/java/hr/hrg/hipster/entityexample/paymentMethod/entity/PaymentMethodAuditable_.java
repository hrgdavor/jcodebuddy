// @generated file hr.hrg.hipster.entity.tooling.EntityMetadataGenerator — Field metadata for the PaymentMethodAuditable view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.paymentMethod.entity;

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

public enum PaymentMethodAuditable_ implements FieldDef {

    id(java.lang.Object.class) {

        @Override()
        public String column() {
            return "id";
        }
    }
    , createdAt(java.time.Instant.class) {

        @Override()
        public String column() {
            return "createdAt";
        }
    }
    , updatedAt(java.time.Instant.class) {

        @Override()
        public String column() {
            return "updatedAt";
        }
    }
    ;

    private final Type javaType;

    private PaymentMethodAuditable_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PaymentMethodAuditable_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PaymentMethodAuditable_.id;
            case "createdAt":
                return PaymentMethodAuditable_.createdAt;
            case "updatedAt":
                return PaymentMethodAuditable_.updatedAt;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PaymentMethodAuditable_> NAME_MAPPER = PaymentMethodAuditable_::forName;

    public static final ViewMeta<PaymentMethodAuditable, PaymentMethodAuditable_> META = new DefaultViewMeta<PaymentMethodAuditable, PaymentMethodAuditable_>(PaymentMethodAuditable.class, PaymentMethodAuditable_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(PaymentMethodAuditable.class, new EntityReadArray<PaymentMethodAuditable, PaymentMethodAuditable_>(PaymentMethodAuditable_.class, values), NAME_MAPPER), null, "", new Class<?>[0]);
}
