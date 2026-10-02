package hr.hrg.hipster.entity.api;

public interface ViewWriter extends ViewReader {
    int set(String field, Object value);
    void set(int fieldOrdinal, Object value);

    /**
     * Read a field by name. Returns {@code null} when the name is not a field of this view.
     * <p>
     * Non-abstract default so existing generated classes keep compiling; the name is
     * resolved through the writer's own field mapping, exactly like {@link #set(String, Object)}.
     */
    default Object get(String field) {
        return null;
    }

    /**
     * Probe whether {@code field} is a writable field of this view.
     * <p>
     * Feeds the write-mode contract of {@code FieldDef#fieldKind()}: a caller can tell a
     * non-writable field (for example a {@code DERIVED}/{@code JOINED} field) from a typo
     * instead of relying on a silent no-op.
     */
    default boolean supports(String field) {
        return false;
    }
}
