package hr.hrg.hipster.entity.api;

/**
 * An empty field enum — the {@code empty_field_enum} case of {@link DefaultViewMetaContractTest}.
 *
 * <p>It cannot be a nested enum: {@link FieldDef} requires every constant to carry a
 * {@code javaType()}, and a nested enum inside the test class would also have to be declared as a
 * constant of the test, which is not what is being exercised.</p>
 */
enum EmptyField_ implements FieldDef {

    ;

    @Override
    public java.lang.reflect.Type javaType() {
        return java.lang.Object.class;
    }

    public static EmptyField_ forName(String name) {
        return null;
    }
}
