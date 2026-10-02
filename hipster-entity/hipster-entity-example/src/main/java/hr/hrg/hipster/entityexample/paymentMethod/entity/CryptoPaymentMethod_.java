// @generated file hr.hrg.hipster.entity.tooling.EntityMetadataGenerator — Field metadata for the CryptoPaymentMethod view.
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

public enum CryptoPaymentMethod_ implements FieldDef {

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
    walletAddress(java.lang.String.class) {

        @Override()
        public String column() {
            return "walletAddress";
        }
    }
    ,
    transactionHash(java.lang.String.class) {

        @Override()
        public String column() {
            return "transactionHash";
        }
    }
    ,
    network(java.lang.String.class) {

        @Override()
        public String column() {
            return "network";
        }
    }
    ;

    private final Type javaType;

    private CryptoPaymentMethod_(Type javaType) {
        this.javaType = javaType;
    }

    public Type javaType() {
        return javaType;
    }

    public static CryptoPaymentMethod_ forName(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "id":
                return CryptoPaymentMethod_.id;
            case "type":
                return CryptoPaymentMethod_.type;
            case "transactionId":
                return CryptoPaymentMethod_.transactionId;
            case "amount":
                return CryptoPaymentMethod_.amount;
            case "currency":
                return CryptoPaymentMethod_.currency;
            case "timestamp":
                return CryptoPaymentMethod_.timestamp;
            case "status":
                return CryptoPaymentMethod_.status;
            case "walletAddress":
                return CryptoPaymentMethod_.walletAddress;
            case "transactionHash":
                return CryptoPaymentMethod_.transactionHash;
            case "network":
                return CryptoPaymentMethod_.network;
            default:
                return null;
        }
    }

    private static final FieldNameMapper<CryptoPaymentMethod_> NAME_MAPPER = CryptoPaymentMethod_::forName;

    public static final ViewMeta<CryptoPaymentMethod, CryptoPaymentMethod_> META = new DefaultViewMeta<CryptoPaymentMethod, CryptoPaymentMethod_>(CryptoPaymentMethod.class, CryptoPaymentMethod_.class, NAME_MAPPER, (Object[] values) -> ArrayBackedViewProxyFactory.createRead(CryptoPaymentMethod.class, new EntityReadArray<CryptoPaymentMethod, CryptoPaymentMethod_>(CryptoPaymentMethod_.class, values), NAME_MAPPER), null, "CRYPTO", new Class<?>[0]);
}
