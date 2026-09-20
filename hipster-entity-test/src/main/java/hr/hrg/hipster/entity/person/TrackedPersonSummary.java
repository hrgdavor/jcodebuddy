package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.ViewWriter;
import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

/**
 * Test-only fixture: the {@code person.entity.PersonSummary} view plus the write and tracking
 * contracts on the same type.
 *
 * <p>{@code ArrayBackedViewProxyFactory.createUpdatable} wraps exactly the view type passed to it
 * (plus {@code ViewChangeTracking}), so the type handed to it must declare the accessors the test
 * drives: the write surface via {@link ViewWriter} and the tracking surface via
 * {@link ViewChangeTracking}. Declaring them through {@code extends} is enough for the proxy —
 * each method is then visible with the erased signature the handler's name+parameter-count
 * dispatch matches (plan.dsflash § 6.4, "Fixture shape this requires").</p>
 */
public interface TrackedPersonSummary
        extends PersonSummary, ViewWriter, ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>> {
}
