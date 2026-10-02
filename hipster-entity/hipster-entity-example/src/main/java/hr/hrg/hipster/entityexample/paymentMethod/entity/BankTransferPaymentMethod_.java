// @generated file hr.hrg.hipster.entity.tooling.EntityMetadataGenerator — Field metadata for the BankTransferPaymentMethod view.
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

public enum BankTransferPaymentMethod_ implements FieldDef {

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
    accountNumber(java.lang.String.class) {

        @Override()
        public String column() {
            return "accountNumber";
        }
    }
    ,
    routingNumber(java.lang.String.class) {

        @Override()
        public String column() {
            return "routingNumber";
        }
    }
    ,
    bankName(java.lang.String.class) {

        @Override()
        public String column() {
            return "bankName";
        }
    }
    ,
    swiftCode(java.lang.String.class) {

        @Override()
        public String column() {
            return "swiftCode";
        }
    }
    ;

    private final Type javaType;

    private BankTransferPaymentMethod_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static BankTransferPaymentMethod_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return BankTransferPaymentMethod_.id;
            case "type":
                return BankTransferPaymentMethod_.type;
            case "transactionId":
                return BankTransferPaymentMethod_.transactionId;
            case "amount":
                return BankTransferPaymentMethod_.amount;
            case "currency":
                return BankTransferPaymentMethod_.currency;
            case "timestamp":
                return BankTransferPaymentMethod_.timestamp;
            case "status":
                return BankTransferPaymentMethod_.status;
            case "accountNumber":
                return BankTransferPaymentMethod_.accountNumber;
            case "routingNumber":
                return BankTransferPaymentMethod_.routingNumber;
            case "bankName":
                return BankTransferPaymentMethod_.bankName;
            case "swiftCode":
                return BankTransferPaymentMethod_.swiftCode;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<BankTransferPaymentMethod_> NAME_MAPPER = BankTransferPaymentMethod_::forName;

    public static final ViewMeta<BankTransferPaymentMethod, BankTransferPaymentMethod_> META = new DefaultViewMeta<BankTransferPaymentMethod, BankTransferPaymentMethod_>(BankTransferPaymentMethod.class, BankTransferPaymentMethod_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(BankTransferPaymentMethod.class, new EntityReadArray<BankTransferPaymentMethod, BankTransferPaymentMethod_>(BankTransferPaymentMethod_.class, values), NAME_MAPPER), null, "BANK_TRANSFER", new Class<?>[0]);
}
