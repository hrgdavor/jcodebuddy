package hr.hrg.hipster.entity.jackson.fixture;

import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.ViewMeta;

/**
 * Field definitions for {@link AccountRecord} (plan.dsflash § 8.4/3.10).
 *
 * <p>The creator is a plain positional record construction, which is what the {@code RECORD} level
 * emits: {@code new AccountRecord.Record(values[0], values[1], values[2])}. No proxy and no
 * reflection are involved, and — the point for the null policy — a {@code null} slot is passed
 * straight through instead of being rejected.</p>
 */
public enum AccountRecord_ implements FieldDef {
    id(Long.class),
    owner(String.class),
    auditCount(Long.class) {
        @Override
        public FieldKind fieldKind() {
            return FieldKind.DERIVED;
        }
    };

    private final Class<?> javaType;

    AccountRecord_(Class<?> javaType) {
        this.javaType = javaType;
    }

    @Override
    public Class<?> javaType() {
        return javaType;
    }

    public static AccountRecord_ forName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "id" -> id;
            case "owner" -> owner;
            case "auditCount" -> auditCount;
            default -> null;
        };
    }

    private static final FieldNameMapper<AccountRecord_> NAME_MAPPER = AccountRecord_::forName;

    public static final ViewMeta<AccountRecord, AccountRecord_> META = new DefaultViewMeta<>(
            AccountRecord.class,
            AccountRecord_.class,
            NAME_MAPPER,
            values -> new AccountRecord.Record(
                    (Long) values[0],
                    (String) values[1],
                    (Long) values[2]));
}
