package hr.hrg.hipster.entity.jackson.fixture;

import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityReadArray;

/**
 * The generated-shaped field enum of the concrete {@link Card} view (plan.dsflash § 9/4.9).
 *
 * <p>The discriminator <em>slot</em> is a literal {@code null} and the <em>value</em> is
 * {@code "CARD"}. That is not an omission: {@code DefaultViewMeta}'s discriminator-field parameter
 * is typed as this enum, so a concrete member cannot name its root's {@code Payment_.type} constant
 * at all — the {@link Payment_} enum is the family's single source for the field, and generation
 * contributes the value read from {@code Card}'s own {@code default type()} accessor.</p>
 *
 * <p>Field order is the member's own full field set in declaration order: the inherited
 * {@code id} and {@code type} first, then the member-specific accessor.</p>
 */
public enum Card_ implements FieldDef {
    id(Long.class),
    type(String.class),
    maskedCardNumber(String.class);

    private final Class<?> javaType;

    Card_(Class<?> javaType) {
        this.javaType = javaType;
    }

    @Override
    public Class<?> javaType() {
        return javaType;
    }

    public static Card_ forName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "id" -> id;
            case "type" -> type;
            case "maskedCardNumber" -> maskedCardNumber;
            default -> null;
        };
    }

    private static final FieldNameMapper<Card_> NAME_MAPPER = Card_::forName;

    public static final ViewMeta<Card, Card_> META = new DefaultViewMeta<>(
            Card.class,
            Card_.class,
            NAME_MAPPER,
            values -> {
                EntityReadArray<Card, Card_> readArray = new EntityReadArray<>(Card_.class, values);
                return ArrayBackedViewProxyFactory.createRead(Card.class, readArray, NAME_MAPPER);
            },
            null,
            "CARD",
            new Class<?>[0]);
}
