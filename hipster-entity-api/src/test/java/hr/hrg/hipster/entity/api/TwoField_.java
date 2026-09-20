package hr.hrg.hipster.entity.api;

/** A well-formed two-constant field enum for {@link DefaultViewMetaContractTest}. */
enum TwoField_ implements FieldDef {

    id(java.lang.Long.class),
    name(java.lang.String.class);

    private final java.lang.reflect.Type javaType;

    TwoField_(java.lang.reflect.Type javaType) {
        this.javaType = javaType;
    }

    @Override
    public java.lang.reflect.Type javaType() {
        return javaType;
    }

    /**
     * The generated shape: a switch over the constant names (DEC-015/DEC-016).
     *
     * <p>The parameter is deliberately not called {@code name}: it would shadow the {@code name}
     * constant, and {@code case "name" -> name} would then resolve to the parameter. That exact
     * shadowing is notes F-42's generator defect, reproduced here by accident on the first compile —
     * which is a small argument for the generated code keeping {@code name} as its parameter only in
     * enums that have no {@code name} constant.</p>
     */
    public static TwoField_ forName(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        return switch (fieldName) {
            case "id" -> id;
            case "name" -> name;
            default -> null;
        };
    }
}
