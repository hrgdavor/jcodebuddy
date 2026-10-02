package hr.hrg.hipster.entity.jackson.fixture;

import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

/**
 * The hand-written root field enum of the {@link Payment} family (plan.dsflash § 9/4.9).
 *
 * <p>This is the artifact the plan calls "the documented hand-off": generation never emits it,
 * because it is the only place the family's discriminator <em>field</em> constant and its
 * permitted-subtype list can live. Its {@code discriminatorField} argument is its own {@code type}
 * constant, and its {@code discriminatorValue} is empty, since the root is not itself a member.</p>
 */
public enum Payment_ implements FieldDef {
    id(Long.class),
    type(String.class);

    private final Class<?> javaType;

    Payment_(Class<?> javaType) {
        this.javaType = javaType;
    }

    @Override
    public Class<?> javaType() {
        return javaType;
    }

    public static Payment_ forName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "id" -> id;
            case "type" -> type;
            default -> null;
        };
    }

    private static final FieldNameMapper<Payment_> NAME_MAPPER = Payment_::forName;

    public static final ViewMeta<Payment, Payment_> META = new DefaultViewMeta<>(
            Payment.class,
            Payment_.class,
            NAME_MAPPER,
            values -> {
                EntityReadArray<Payment, Payment_> readArray =
                        new EntityReadArray<>(Payment_.class, values);
                return ArrayBackedViewProxyFactory.createRead(Payment.class, readArray, NAME_MAPPER);
            },
            Payment_.type,
            "",
            new Class<?>[] { Card.class, Wallet.class });
}
