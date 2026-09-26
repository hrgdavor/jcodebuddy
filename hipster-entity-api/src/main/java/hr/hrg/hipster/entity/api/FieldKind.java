package hr.hrg.hipster.entity.api;

/**
 * Classifies the origin of an entity view field.
 *
 * <p>This classification describes where a field's <b>value</b> comes from. It is not a description of a
 * <b>relation</b> between two entities, and the two must not be conflated: a relation is expressed by keys
 * the entities hold, never by one entity referencing another. See
 * <a href="../../../../../../../../../doc-hipster-entity/architecture/decisions/DEC-034.md">DEC-034</a>.
 */
public enum FieldKind {
    /** Directly mapped to a database column. */
    COLUMN,

    /** Derived/computed from other fields (not stored). */
    DERIVED,

    /**
     * Sourced from a related row via join or sub-query — <b>an origin, not a relation</b>.
     *
     * <p>The {@code relation} path on {@link FieldSource} names where the value is read from. It is a
     * human-facing label for a join, and it is <b>not</b> how a relation between two entities is declared:
     * a relation is carried by a marked key on the referencing view, so that the generator can verify it
     * and an IDE rename reaches it. See
     * <a href="../../../../../../../../../doc-hipster-entity/architecture/decisions/DEC-034.md">DEC-034</a>.
     */
    JOINED
}
