package hr.hrg.hipster.entity.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares read/write mode for entity view interfaces.
 *
 * <h3>{@code dto} — the projection marker (DEC-003 / DEC-007)</h3>
 *
 * <p>{@code dto = true} marks a view as a <strong>read projection</strong>: a thin read
 * contract for a SQL or NoSQL query result, whose purpose is to reach JSON without building
 * the entity. It is the marker the plan's step 6.5 names, and it is deliberately an
 * annotation attribute rather than a naming convention, so the generator decides from
 * committed source and an IDE rename refactor cannot silently change the answer.</p>
 *
 * <p>The attribute changes nothing about what the ordinary levels emit. It is read by the
 * opt-in projection pass ({@code --dto-projections}), which emits one extra committed class
 * beside the view: a direct-call writer for the view's own accessors. A view without the
 * marker gains nothing, and a pass without the flag emits nothing from the marker.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface View {
    GenLevel gen() default GenLevel.DEFAULT;
    String discriminatorField() default "";
    Class<?>[] addons() default {};
    /** Whether this view is a read projection (a DTO) rather than a materialization target. */
    boolean dto() default false;

    public record Record(GenLevel gen, String discriminatorField, Class<?>[] addons){}
}
