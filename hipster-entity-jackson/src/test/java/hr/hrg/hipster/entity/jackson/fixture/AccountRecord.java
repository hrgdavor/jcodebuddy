package hr.hrg.hipster.entity.jackson.fixture;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.FieldSource;
import hr.hrg.hipster.entity.api.Identifiable;

/**
 * The {@code RECORD}-level fixture for the Jackson module's own tests (plan.dsflash § 10/5.1,
 * § 8.4/3.10-3.11).
 *
 * <p>Its {@code create()} returns the nested record, not a proxy — the second of the three
 * materializations § 10/5.1 names. That matters to Jackson because a record is deliberately
 * <em>not</em> a {@code ViewReader}, which is exactly the case
 * {@link hr.hrg.hipster.entity.jackson.EntityJacksonMapper#toJson} needs a second entry point
 * for.</p>
 *
 * <p>{@code auditCount} is {@code DERIVED}, so it is the field § 10/5.2's null policy is asserted
 * on: a payload that omits it must leave the component {@code null} rather than fail.</p>
 */
public interface AccountRecord extends EntityBase<Long>, Identifiable<Long> {

    String owner();

    @FieldSource(kind = FieldKind.DERIVED, expression = "COUNT(*)")
    Long auditCount();

    /** Component order is the field-enum ordinal order, which is what makes {@code create()} positional. */
    record Record(Long id, String owner, Long auditCount) implements AccountRecord {
    }
}
