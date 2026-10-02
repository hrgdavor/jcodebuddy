package hr.hrg.hipster.entity.api;

public enum GenLevel {
    /**
     * Default level, which is determined by the code generator based on the presence of other options and the type of view.
     */
    DEFAULT,
    /**
     * Generate field metadata enum and ViewMeta only. The view is read-only via interface accessors
     * (backed by array proxy when no record is present). This is the entry point for utilities
     * such as database access, serialization, and configuration readers.
     */
    META,
    /**
     * Generate a record class that implements the view interface. More performant than proxy-backed
     * access and provides an immutable concrete materialization.
     */
    RECORD,
    /**
     * Generate a write interface following the builder pattern, enabling mutation through an
     * array-backed proxy. Suitable for DTOs and forms that do not need a concrete builder class.
     */
    WRITABLE,
    /**
     * Generate a concrete builder class with mutable fields and setter methods.
     * More performant than proxy-backed write, recommended for performance-sensitive views.
     */
    BUILDER,
    /**
     * Generate a concrete builder class with field-level change tracking.
     * Used for partial updates and patch operations where knowing which fields were set matters.
     */
    BUILDER_TRACKED,
    /**
     * Generate both a regular builder and a tracking builder. Rarely needed — only when a view
     * requires both untracked bulk construction and tracked partial updates at maximum performance.
     */
    BUILDER_ALL,
}
