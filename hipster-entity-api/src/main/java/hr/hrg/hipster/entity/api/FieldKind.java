package hr.hrg.hipster.entity.api;

/**
 * Classifies the origin of an entity view field.
 */
public enum FieldKind {
    /** Directly mapped to a database column. */
    COLUMN,

    /** Derived/computed from other fields (not stored). */
    DERIVED,

    /** Sourced from a related table via join or sub-query. */
    JOINED
}
