package hr.hrg.hipster.entity.jackson.fixture;

import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

/**
 * The generated-shaped field enum of the concrete {@link Wallet} view — see {@link Card_} for why
 * the discriminator field slot is {@code null} and only the value is carried.
 */
public enum Wallet_ implements FieldDef {
    id(Long.class),
    type(String.class),
    walletAddress(String.class);

    private final Class<?> javaType;

    Wallet_(Class<?> javaType) {
        this.javaType = javaType;
    }

    @Override
    public Class<?> javaType() {
        return javaType;
    }

    public static Wallet_ forName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "id" -> id;
            case "type" -> type;
            case "walletAddress" -> walletAddress;
            default -> null;
        };
    }

    private static final FieldNameMapper<Wallet_> NAME_MAPPER = Wallet_::forName;

    public static final ViewMeta<Wallet, Wallet_> META = new DefaultViewMeta<>(
            Wallet.class,
            Wallet_.class,
            NAME_MAPPER,
            values -> {
                EntityReadArray<Wallet, Wallet_> readArray =
                        new EntityReadArray<>(Wallet_.class, values);
                return ArrayBackedViewProxyFactory.createRead(Wallet.class, readArray, NAME_MAPPER);
            },
            null,
            "WALLET",
            new Class<?>[0]);
}
