package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.Identifiable;
import hr.hrg.hipster.entity.api.ViewWriter;
import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

/**
 * The leaf view of the deep-tracking fixtures: one identifiable tracked view with a single mutable
 * label.
 *
 * <p>It exists so the deep tests can nest three levels
 * ({@code TrackedDirectory -> List<TrackedNode> -> TrackedNode}) without touching the
 * {@code PersonSummary} fixture, which is shared with the Jackson, benchmark and generator tests.
 * {@link Identifiable} is what makes the collection level's reorder detection possible (DEC-017).</p>
 */
public interface TrackedNode
        extends Identifiable<Long>, ViewWriter, ViewChangeTracking<TrackedNode_, EEnumSet<TrackedNode_>> {

    Long id();

    String label();
}
