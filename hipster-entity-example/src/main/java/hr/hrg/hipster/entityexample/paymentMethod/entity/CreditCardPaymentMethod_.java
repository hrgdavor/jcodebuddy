// {@link hr.hrg.hipster.entityexample.paymentMethod.entity.CreditCardPaymentMethod} Field metadata for the CreditCardPaymentMethod view.
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

public enum CreditCardPaymentMethod_ implements FieldDef {

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
    maskedCardNumber(java.lang.String.class) {

        @Override()
        public String column() {
            return "maskedCardNumber";
        }
    }
    ,
    expiryDate(java.lang.String.class) {

        @Override()
        public String column() {
            return "expiryDate";
        }
    }
    ,
    cardType(java.lang.String.class) {

        @Override()
        public String column() {
            return "cardType";
        }
    }
    ,
    gatewayTransactionId(java.lang.String.class) {

        @Override()
        public String column() {
            return "gatewayTransactionId";
        }
    }
    ;

    private final Type javaType;

    private CreditCardPaymentMethod_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static CreditCardPaymentMethod_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return CreditCardPaymentMethod_.id;
            case "type":
                return CreditCardPaymentMethod_.type;
            case "transactionId":
                return CreditCardPaymentMethod_.transactionId;
            case "amount":
                return CreditCardPaymentMethod_.amount;
            case "currency":
                return CreditCardPaymentMethod_.currency;
            case "timestamp":
                return CreditCardPaymentMethod_.timestamp;
            case "status":
                return CreditCardPaymentMethod_.status;
            case "maskedCardNumber":
                return CreditCardPaymentMethod_.maskedCardNumber;
            case "expiryDate":
                return CreditCardPaymentMethod_.expiryDate;
            case "cardType":
                return CreditCardPaymentMethod_.cardType;
            case "gatewayTransactionId":
                return CreditCardPaymentMethod_.gatewayTransactionId;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<CreditCardPaymentMethod_> NAME_MAPPER = CreditCardPaymentMethod_::forName;

    public static final ViewMeta<CreditCardPaymentMethod, CreditCardPaymentMethod_> META = new DefaultViewMeta<CreditCardPaymentMethod, CreditCardPaymentMethod_>(CreditCardPaymentMethod.class, CreditCardPaymentMethod_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(CreditCardPaymentMethod.class, new EntityReadArray<CreditCardPaymentMethod, CreditCardPaymentMethod_>(CreditCardPaymentMethod_.class, values), NAME_MAPPER), null, "CREDIT_CARD", new Class<?>[0]);
}
