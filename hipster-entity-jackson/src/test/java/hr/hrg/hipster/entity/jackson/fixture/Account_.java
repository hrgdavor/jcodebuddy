package hr.hrg.hipster.entity.jackson.fixture;

import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.TypeUtils;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Field definitions for {@link Account}, in the shape the generator emits for the {@code META}
 * level (plan.dsflash § 8.3/3.7): constants matching the accessor names, a {@code javaType()}
 * accessor typed {@link Type}, a {@code forName} switch, and a {@code META} whose creator is the
 * array-backed read proxy.
 *
 * <p>{@code javaType()} returns {@code Type}, not {@code Class}, and the generic field is built with
 * {@link TypeUtils#parameterizedType} — that is what § 8.3/3.7 requires of the emitter, and it is
 * load-bearing for Jackson: with a raw {@code Map.class} the deserializer binds the map's values as
 * {@code Integer} and a {@code List<Long>} payload silently changes type. This fixture keeps the
 * generics so the round-trip test can assert value types as well as values.</p>
 *
 * <p>{@code balance} carries the {@code @FieldSource} classification a generated enum would carry
 * too, so the fixture exercises the same metadata surface the generator produces (§ 8.3/3.9).</p>
 */
public enum Account_ implements FieldDef {
    id(Long.class),
    owner(String.class),
    balance(Long.class) {
        @Override
        public FieldKind fieldKind() {
            return FieldKind.DERIVED;
        }
    },
    tags(TypeUtils.parameterizedType(
            Map.class,
            String.class,
            TypeUtils.parameterizedType(List.class, Long.class)));

    private final Type javaType;

    Account_(Type javaType) {
        this.javaType = javaType;
    }

    @Override
    public Type javaType() {
        return javaType;
    }

    public static Account_ forName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "id" -> id;
            case "owner" -> owner;
            case "balance" -> balance;
            case "tags" -> tags;
            default -> null;
        };
    }

    private static final FieldNameMapper<Account_> NAME_MAPPER = Account_::forName;

    public static final ViewMeta<Account, Account_> META = new DefaultViewMeta<>(
            Account.class,
            Account_.class,
            NAME_MAPPER,
            values -> {
                EntityReadArray<Account, Account_> readArray =
                        new EntityReadArray<>(Account_.class, values);
                return ArrayBackedViewProxyFactory.createRead(Account.class, readArray, NAME_MAPPER);
            });
}
