// @generated file hr.hrg.hipster.entity.tooling.ValidationGenerator — Imperative constraint checks for the PersonDto view.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit violation messages for a {@link PersonDto}, without a Bean Validation provider.
 *
 * <p>The same constraints are also emitted as annotations on the generated record and
 * builders, so a provider at the boundary sees them. This class is the other half: a
 * caller that wants a message rather than a {@code ConstraintViolation} can call it
 * directly, and the message text is committed source rather than a bundle lookup.</p>
 *
 */
public final class PersonDtoValidator {

    private PersonDtoValidator() {
    }

    /** Every violation, in field order. An empty list means the view is valid. */
    public static List<String> validate(PersonDto view) {
        List<String> violations = new ArrayList<>();
        if (view == null) {
            violations.add("view: must not be null");
            return violations;
        }
        if (view.metadata() == null) {
            violations.add("metadata: must not be null");
        }
        return violations;
    }
}
