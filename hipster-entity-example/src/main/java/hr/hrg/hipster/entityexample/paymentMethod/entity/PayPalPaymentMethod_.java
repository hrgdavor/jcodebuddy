// {@link hr.hrg.hipster.entityexample.paymentMethod.entity.PayPalPaymentMethod} Field metadata for the PayPalPaymentMethod view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.paymentMethod.entity;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.DefaultViewMeta;
import java.math.BigDecimal;
import java.time.Instant;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

public enum PayPalPaymentMethod_ implements FieldDef {

    id(java.lang.Long.class) {

        @Override()
        public String column() {
            return "id";
        }
    }
    ,
    type(java.lang.String.class) {

        @Override()
        public String column() {
            return "type";
        }
    }
    ,
    transactionId(java.lang.String.class) {

        @Override()
        public String column() {
            return "transactionId";
        }
    }
    ,
    amount(java.math.BigDecimal.class) {

        @Override()
        public String column() {
            return "amount";
        }
    }
    ,
    currency(java.lang.String.class) {

        @Override()
        public String column() {
            return "currency";
        }
    }
    ,
    timestamp(java.time.Instant.class) {

        @Override()
        public String column() {
            return "timestamp";
        }
    }
    ,
    status(java.lang.String.class) {

        @Override()
        public String column() {
            return "status";
        }
    }
    ,
    paypalEmail(java.lang.String.class) {

        @Override()
        public String column() {
            return "paypalEmail";
        }
    }
    ,
    payerId(java.lang.String.class) {

        @Override()
        public String column() {
            return "payerId";
        }
    }
    ,
    payerStatus(java.lang.String.class) {

        @Override()
        public String column() {
            return "payerStatus";
        }
    }
    ;

    private final Type javaType;

    private PayPalPaymentMethod_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static PayPalPaymentMethod_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return PayPalPaymentMethod_.id;
            case "type":
                return PayPalPaymentMethod_.type;
            case "transactionId":
                return PayPalPaymentMethod_.transactionId;
            case "amount":
                return PayPalPaymentMethod_.amount;
            case "currency":
                return PayPalPaymentMethod_.currency;
            case "timestamp":
                return PayPalPaymentMethod_.timestamp;
            case "status":
                return PayPalPaymentMethod_.status;
            case "paypalEmail":
                return PayPalPaymentMethod_.paypalEmail;
            case "payerId":
                return PayPalPaymentMethod_.payerId;
            case "payerStatus":
                return PayPalPaymentMethod_.payerStatus;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<PayPalPaymentMethod_> NAME_MAPPER = PayPalPaymentMethod_::forName;

    public static final ViewMeta<PayPalPaymentMethod, PayPalPaymentMethod_> META = new DefaultViewMeta<PayPalPaymentMethod, PayPalPaymentMethod_>(PayPalPaymentMethod.class, PayPalPaymentMethod_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(PayPalPaymentMethod.class, new EntityReadArray<PayPalPaymentMethod, PayPalPaymentMethod_>(PayPalPaymentMethod_.class, values), NAME_MAPPER), null, "PAYPAL", new Class<?>[0]);
}
