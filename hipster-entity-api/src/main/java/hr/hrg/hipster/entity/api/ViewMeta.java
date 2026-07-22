package hr.hrg.hipster.entity.api;

import java.lang.reflect.Type;

/**
 * Metadata and factory contract for a single array-backed entity view.
 *
 * <p>Each view interface (e.g. {@code PersonSummary}) is paired with one concrete
 * implementation of this interface (e.g. {@code PersonSummaryMeta}), typically a singleton.
 * It captures everything needed to register, serialize, deserialize, and construct the view:</p>
 * <ul>
 *   <li>{@link #viewType()} — the view interface class</li>
 *   <li>{@link #fieldType()} — the companion field enum class (implements {@link FieldDef})</li>
 *   <li>{@link #forName(String)} — reverse lookup from field name to enum constant</li>
 *   <li>{@link #create(Object[])} — constructs the view from a positional values array</li>
 * </ul>
 *
 * <h3>Naming contract (inherited from {@link FieldDef})</h3>
 * <p>Field names are {@code enum.name()} — identical to the accessor method name on the view
 * interface. No mapping exists. {@link #forName(String)} is the inverse of {@code enum.name()}.</p>
 *
 * @param <V> the view interface type
 * @param <F> the companion field enum type, must implement {@link FieldDef}
 */
public interface ViewMeta<V, F extends Enum<F> & FieldDef> extends ForNameOrdinal {

    /** The view interface class. */
    Class<V> viewType();

    /** The companion field enum class; {@code fieldType().getEnumConstants()[i].ordinal() == i}. */
    Class<F> fieldType();

    /** Number of fields in the view. */
    int fieldCount();

    /** Field value enum constants in ordinal order. */
    F[] fieldValues();

    /** Field name for given ordinal (same as enum.name()). */
    String fieldNameAt(int ordinal);

    /** Field Java type for given ordinal. */
    Type fieldTypeAt(int ordinal);

    /**
     * Functional interface is used intentionally so caller can use it directly, and allow jvm to inline it into the 
     * hot path of field-name lookup in deserializers, mappers, and adapters.
     * 
     * Reverse lookup: field name → enum constant, or {@code null} if not found.
     * Equivalent to {@code Enum.valueOf} but null-safe and without throwing on unknown names.
     *
     * <p>The implementation is a <strong>generated {@code switch} statement</strong> (see DEC-015
     * and DEC-016): O(1), zero allocation, no hashing. It is the only sanctioned path for
     * resolving a field name to its ordinal in parse loops, mappers, and adapters.</p>
     *
     * <p><strong>⚠ MANDATORY RULE (DEC-016):</strong> Do <em>not</em> build a
     * {@code HashMap<String,Integer>} (or any other dynamic name→ordinal structure) in place of
     * this method. Per-call map construction allocates, hashes every field name on setup, and
     * hashes every incoming token on lookup — all entirely unnecessary. The correct pattern is:
     * <pre>{@code
     * // called PER TOKEN in the parse loop:
     * F field = meta.forName(name);       // O(1) switch — zero allocation
     * if (field != null) {
     *     int ord = field.ordinal();      // capture once; use for both array indices below
     *     values[ord] = readers[ord].read(p);
     * } else {
     *     p.skipChildren();
     * }
     * }</pre>
     * where {@code readers[]} is pre-built <em>once per deserializer instance</em>, not per call.
     * See the full implementation guide at
     * {@code doc/user/field-lookup-guide.md} and decision record DEC-016.</p>
     *
     * @param name the field name (== {@code enum.name()})
     * @return the matching constant, or {@code null}
     */
    FieldNameMapper<F> forName();

    /**
     * Discriminator field used by polymorphic view hierarchies.
     *
     * <p>For sealed view roots, this is the field enum constant representing the
     * JSON/JDBC discriminator property. For non-polymorphic views, the default is
     * {@code null}.</p>
     */
    default F discriminatorField() {
        return null;
    }

    /**
     * Discriminator value for this concrete view type.
     *
     * <p>For concrete polymorphic subviews, this is the value written into the
     * discriminator field. For root or non-polymorphic views, the default is empty.</p>
     */
    default String discriminatorValue() {
        return "";
    }

    /**
     * Permitted subtypes for a sealed view root.
     *
     * <p>Only the root metadata needs to expose this information; concrete views may
     * return an empty array.</p>
     */
    default Class<?>[] permittedSubtypes() {
        return new Class<?>[0];
    }

    /**
     * Constructs the view from a positional values array where {@code values[f.ordinal()]}
     * holds the value for field {@code f}.
     *
     * @param values positional array indexed by field ordinal
     * @return the constructed view instance
     */
    V create(Object[] values);
}
