package hr.hrg.hipster.sql.tooling.model;

import java.util.List;

/**
 * One accessor of an entity, as far as the first emitters need it.
 *
 * <p>{@code typeText} is the type exactly as the source wrote it, with OpenRewrite's printer
 * normalisation applied by {@code TreeQueries.typeText} (no space after a generic comma). The
 * generator never guesses a type: an unresolved name stays the text the author wrote.</p>
 *
 * <p>{@code ordinal} is assigned by discovery/ledger, not by the emitter. It is the
 * {@code ColumnMeta} slot and the change-set bit; see the plan's ledger section.</p>
 *
 * @param ordinal    the persisted property slot
 * @param name       the property name (the getter minus its prefix, first letter lowercased)
 * @param getterName the accessor's method name
 * @param typeText   the accessor's return type as source text
 * @param transient_ whether the accessor is marked transient
 * @param keepRest   whether the accessor is the entity's catch-all field
 * @param primary    whether the accessor is the entity's {@code @Id} primary
 * @param annotations the accessor's leading annotations, in source order, copied to generated getters
 * @param initial    the source-written initial value, or {@code ""} when none
 */
public record PropertyModel(
        int ordinal,
        String name,
        String getterName,
        String typeText,
        boolean transient_,
        boolean keepRest,
        boolean primary,
        List<AnnotationModel> annotations,
        String initial) {

    public PropertyModel {
        annotations = List.copyOf(annotations);
        initial = initial == null ? "" : initial;
    }

    /** Compatibility constructor for the ordinary non-primary accessor. */
    public PropertyModel(int ordinal, String name, String getterName, String typeText,
                         boolean transient_, boolean keepRest) {
        this(ordinal, name, getterName, typeText, transient_, keepRest, false, List.of(), "");
    }

    /** Compatibility constructor for an accessor without annotations. */
    public PropertyModel(int ordinal, String name, String getterName, String typeText,
                         boolean transient_, boolean keepRest, boolean primary) {
        this(ordinal, name, getterName, typeText, transient_, keepRest, primary, List.of(), "");
    }

    /** Compatibility constructor for an accessor without an initial value. */
    public PropertyModel(int ordinal, String name, String getterName, String typeText,
                         boolean transient_, boolean keepRest, boolean primary,
                         List<AnnotationModel> annotations) {
        this(ordinal, name, getterName, typeText, transient_, keepRest, primary, annotations, "");
    }

    /** The legacy setter name: {@code set} plus the property name with its first letter uppercased. */
    public String setterName() {
        return "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
