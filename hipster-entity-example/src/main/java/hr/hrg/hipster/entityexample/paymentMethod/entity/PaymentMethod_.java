package hr.hrg.hipster.entityexample.paymentMethod.entity;

import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Field definitions for the {@code PaymentMethod} view.
 *
 * <p>Each constant name must match the accessor method name on {@link PaymentMethod}
 * exactly. {@code enum.name()} is the field name, and the ordinal is the positional
 * index into the backing array.</p>
 */
public enum PaymentMethod_ implements FieldDef {
    id(Long.class),
    type(String.class),
    transactionId(String.class),
    amount(BigDecimal.class),
    currency(String.class),
    timestamp(Instant.class),
    status(String.class);

    private final Class<?> javaType;

    PaymentMethod_(Class<?> javaType) {
        this.javaType = javaType;
    }

    @Override
    public Class<?> javaType() {
        return javaType;
    }

    public static PaymentMethod_ forName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "id" -> id;
            case "type" -> type;
            case "transactionId" -> transactionId;
            case "amount" -> amount;
            case "currency" -> currency;
            case "timestamp" -> timestamp;
            case "status" -> status;
            default -> null;
        };
    }

    private static final FieldNameMapper<PaymentMethod_> NAME_MAPPER = PaymentMethod_::forName;

    public static final ViewMeta<PaymentMethod, PaymentMethod_> META = new DefaultViewMeta<PaymentMethod, PaymentMethod_>(
            PaymentMethod.class,
            PaymentMethod_.class,
            NAME_MAPPER,
            values -> {
                EntityReadArray<PaymentMethod, PaymentMethod_> readArray =
                        new EntityReadArray<>(PaymentMethod_.class, values);
                return ArrayBackedViewProxyFactory.createRead(
                        PaymentMethod.class,
                        readArray,
                        NAME_MAPPER);
            },
            PaymentMethod_.type,
            "",
            new Class<?>[] {
                    BankTransferPaymentMethod.class,
                    PayPalPaymentMethod.class,
                    CryptoPaymentMethod.class,
                    CreditCardPaymentMethod.class
            }
    );
}
