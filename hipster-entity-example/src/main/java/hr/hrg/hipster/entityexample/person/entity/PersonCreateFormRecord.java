// @generated file hr.hrg.hipster.entity.tooling.ViewRecordGenerator — Immutable record materialization of the PersonCreateForm view.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

/**
 * An immutable {@link PersonCreateForm} whose component order is the field
 * enum order, so {@code values[field.ordinal()]} maps 1:1 onto the constructor.
 *
 * <p>Constraint annotations are carried over from the view's accessors (plan.dsflash
 * § 12.3/7.10), so a Bean Validation provider at the boundary sees exactly what the
 * author declared on the view. A constraint the generator could not apply is reported
 * as a divergence rather than dropped in silence.</p>
 */
public record PersonCreateFormRecord(
        java.lang.Object id,
        String firstName,
        String lastName,
        String email,
        String phoneNumber)
        implements PersonCreateForm {
}
