package hr.hrg.hipster.entity.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the origin and mapping of an entity view field.
 * <p>
 * Applied to parameterless getter methods in view interfaces.
 * When absent, the field defaults to {@link FieldKind#COLUMN} with the
 * method name as the column name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FieldSource {
    /** Classification of this field's data origin. */
    FieldKind kind() default FieldKind.COLUMN;

    /** Database column name. Defaults to the method name when empty. */
    String column() default "";

    /** Relation path for JOINED fields (e.g. "department.name"). Ignored for COLUMN/DERIVED. */
    String relation() default "";

    /** Expression or description for DERIVED fields. Ignored for COLUMN/JOINED. */
    String expression() default "";
}
