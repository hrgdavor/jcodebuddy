package hr.hrg.hipster.entity.core;

/**
 * One changed field as reported by {@link ViewChangeTracking#changedValues()}: the field and the
 * value it holds <strong>now</strong>.
 *
 * <p>There is no {@code previous} component, and that is the point of the change-tracking contract:
 * the tracker never keeps the value a field held before the write. A consumer that needs an
 * old&nbsp;&rarr;&nbsp;new pair is handed both views by its caller — the baseline instance the
 * mutable was created from, and the mutable itself — and compares them; the library supplies only the
 * current half, because only the current half is the tracker's own state.</p>
 *
 * @param field   the field definition enum constant identifying the changed field
 * @param current the value the field holds now, or {@code null}
 */
public record FieldChange<E extends Enum<E>>(E field, Object current) {

    /** The field's name — the JSON/column-facing label of this change. */
    public String fieldName() {
        return field == null ? null : field.name();
    }
}
