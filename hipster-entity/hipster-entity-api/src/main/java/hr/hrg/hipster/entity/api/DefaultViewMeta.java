package hr.hrg.hipster.entity.api;

import java.lang.reflect.Type;
import java.util.Objects;
import java.util.function.Function;

/**
 * Simple concrete {@link ViewMeta} implementation that is parametrized through
 * constructor arguments. This avoids per-view boilerplate classes in most cases.
 *
 * @param <V> view interface type
 * @param <F> field enum type (must implement {@link FieldDef})
 */
public final class DefaultViewMeta<V, F extends Enum<F> & FieldDef> implements ViewMeta<V, F> {

    private final Class<V> viewType;
    private final Class<F> fieldType;
    private final F[] fieldValues;
    private final int fieldCount;
    private final FieldNameMapper<F> forName;
    private final Function<Object[], V> creator;
    private final F discriminatorField;
    private final String discriminatorValue;
    private final Class<?>[] permittedSubtypes;

    public DefaultViewMeta(
            Class<V> viewType,
            Class<F> fieldDefType,
            FieldNameMapper<F> forName,
            Function<Object[], V> creator
    ) {
        this(viewType, fieldDefType, forName, creator, null, "", new Class<?>[0]);
    }

    /**
     * Full constructor.
     *
     * <p>{@code discriminatorField} is the view's <em>own</em> field enum type. A concrete member of
     * a polymorphic family deliberately passes {@code null} here: it shares its root's discriminator
     * field, and the root's hand-written enum is where that constant lives (§ 9/4.9). What a
     * generated subclass supplies is its {@code discriminatorValue}.</p>
     */
    public DefaultViewMeta(
            Class<V> viewType,
            Class<F> fieldType,
            FieldNameMapper<F> forName,
            Function<Object[], V> creator,
            F discriminatorField,
            String discriminatorValue,
            Class<?>[] permittedSubtypes
    ) {
        this.viewType = Objects.requireNonNull(viewType, "viewType");
        this.fieldType = Objects.requireNonNull(fieldType, "fieldType");
        this.fieldValues = fieldType.getEnumConstants();
        this.fieldCount = this.fieldValues.length;
        this.forName = Objects.requireNonNull(forName, "forName");
        this.creator = Objects.requireNonNull(creator, "creator");
        this.discriminatorField = discriminatorField;
        this.discriminatorValue = discriminatorValue == null ? "" : discriminatorValue;
        this.permittedSubtypes = permittedSubtypes == null ? new Class<?>[0] : permittedSubtypes.clone();
        checkFieldContract();
    }

    /**
     * The checkable half of the ordinal contract (plan.dsflash § 4.2/D7 and § 6.4 test 19).
     *
     * <p>What this can assert is deliberately narrow, and the narrowness is the point. The plan
     * originally specified {@code fieldValues[i].ordinal() == i}, which is a <strong>tautology</strong>:
     * {@code fieldValues} <em>is</em> {@code getEnumConstants()} and {@link Enum#ordinal()} is final and
     * declaration-ordered, so no enum can ever fail it and no test can exercise it. Ordinal
     * <em>order</em> is therefore not checkable here and is not claimed to be — the append-only rule
     * (R1, DEC-023) is enforced at build time by {@code EnumConstantOrderChecker}, and its runtime
     * witness is {@code allFields} in the metadata JSON.</p>
     *
     * <p>What <em>does</em> fail in practice, and is asserted here:</p>
     * <ol>
     *   <li>an empty field enum — a view with no fields cannot be positionally materialized at all;</li>
     *   <li>a name map that is not total and lossless ({@code forName.forName(f.name()) != f}) — the
     *       shape of a generated {@code forName} switch that lost an arm, a typo'd {@code case "..."},
     *       or a renamed constant with a stale {@code NAME_MAPPER}. Every one of those silently
     *       mis-resolves a field for the rest of the process, which is exactly the failure R1 exists
     *       to prevent.</li>
     * </ol>
     *
     * <p>The failure is a fail-fast {@link IllegalStateException} naming the enum and the offending
     * constant, because the alternative is a positional array whose slots mean something other than
     * what the enum says — a persisted-layout corruption rather than a crash.</p>
     */
    private void checkFieldContract() {
        if (fieldValues.length == 0) {
            throw new IllegalStateException("empty_field_enum: field enum " + fieldType.getName()
                    + " declares no constants, so a positional array cannot describe a view. "
                    + "A FieldDef enum must declare at least the identity field.");
        }
        for (F field : fieldValues) {
            F resolved = forName.forName(field.name());
            if (resolved != field) {
                throw new IllegalStateException("name_map_not_lossless: forName(NAME_MAPPER) of "
                        + fieldType.getName() + " resolved \"" + field.name() + "\" to "
                        + (resolved == null ? "null" : resolved.name())
                        + " instead of " + field.name() + ". A lost forName arm, a typo'd case label "
                        + "or a renamed constant with a stale mapper all look like this, and each one "
                        + "silently mis-resolves that field downstream.");
            }
        }
    }

    @Override
    public Class<V> viewType() {
        return viewType;
    }

    @Override
    public Class<F> fieldType() {
        return fieldType;
    }

    @Override
    public int fieldCount() {
        return fieldCount;
    }

    @Override
    public F[] fieldValues() {
        return fieldValues;
    }

    @Override
    public String fieldNameAt(int ordinal) {
        return fieldValues[ordinal].name();
    }

    @Override
    public Type fieldTypeAt(int ordinal) {
        return fieldValues[ordinal].javaType();
    }

    @Override
    public FieldNameMapper<F> forName() {
        return forName;
    }

    @Override
    public int forNameOrdinal(String fieldName) {
        F field = forName.forName(fieldName);
        if (field == null) {
            return -1;
        }
        return field.ordinal();
    }

    @Override
    public F discriminatorField() {
        return discriminatorField;
    }

    @Override
    public String discriminatorValue() {
        return discriminatorValue;
    }

    @Override
    public Class<?>[] permittedSubtypes() {
        return permittedSubtypes.clone();
    }

    @Override
    public V create(Object[] values) {
        return creator.apply(values);
    }
}
