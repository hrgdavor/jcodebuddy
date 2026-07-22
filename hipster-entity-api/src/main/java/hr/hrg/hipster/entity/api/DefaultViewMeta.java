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
