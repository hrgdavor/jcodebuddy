package hr.hrg.hipster.entityexample.paymentMethod.entity;

import java.lang.reflect.Type;
import hr.hrg.hipster.entity.api.TypeUtils;

public enum CryptoPaymentMethod_ {

    id(java.lang.Long.class),
    type(java.lang.String.class),
    transactionId(java.lang.String.class),
    amount(java.math.BigDecimal.class),
    currency(java.lang.String.class),
    timestamp(java.time.Instant.class),
    status(java.lang.String.class),
    walletAddress(java.lang.String.class),
    transactionHash(java.lang.String.class),
    network(java.lang.String.class);

    private final Type propertyType;

    private CryptoPaymentMethod_(Type propertyType) {
        this.propertyType = propertyType;
    }

    public String getPropertyName() {
        return name();
    }

    public Type getPropertyType() {
        return propertyType;
    }

    public static CryptoPaymentMethod_ forName(String name) {
        if (name == null)
            return null;
        return switch (name) {
            case "id" -> id;
            case "type" -> type;
            case "transactionId" -> transactionId;
            case "amount" -> amount;
            case "currency" -> currency;
            case "timestamp" -> timestamp;
            case "status" -> status;
            case "walletAddress" -> walletAddress;
            case "transactionHash" -> transactionHash;
            case "network" -> network;
            default -> null;
        };
    }
}
