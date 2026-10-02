package hr.hrg.hipster.entity.api;

/**
 * Pluggable field name lookup for view metadata.
 *
 * @param <F> field enum type
 */
@FunctionalInterface
public interface FieldNameMapper<F extends Enum<F> & FieldDef> {

    /**
     * Resolve a field by its JSON/property name.
     *
     * @param name property name (== enum.name())
     * @return enum field or {@code null} if unknown
     */
    F forName(String name);
}
