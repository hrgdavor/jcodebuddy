package hr.hrg.hipster.entity.jackson.fixture;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.FieldSource;
import hr.hrg.hipster.entity.api.Identifiable;

import java.util.List;
import java.util.Map;

/**
 * The {@code META}-level fixture for the Jackson module's own tests (plan.dsflash § 10/5.1).
 *
 * <p>It is hand-written rather than generated because {@code hipster-entity-jackson} has no
 * build-time dependency on the generator: the module's job is to serialize whatever a
 * {@code ViewMeta} describes, so its tests need a view whose {@code create()} route is the
 * array-backed proxy — one of the three materializations § 10/5.1 names.</p>
 *
 * <p>{@code balance} is {@code DERIVED}, which is what § 10/5.2's absent-is-not-null policy is
 * about: the field has an ordinal and a nullable slot even though no payload will ever carry it.</p>
 */
public interface Account extends EntityBase<Long>, Identifiable<Long> {

    String owner();

    @FieldSource(kind = FieldKind.DERIVED, expression = "SUM(amount)")
    Long balance();

    Map<String, List<Long>> tags();
}
