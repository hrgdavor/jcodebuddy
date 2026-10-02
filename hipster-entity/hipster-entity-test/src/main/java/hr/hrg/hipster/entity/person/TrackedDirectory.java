package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.ViewWriter;
import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import java.util.List;

/**
 * Test-only fixture for tasks 6.7 and 6.8 (plan.dsflash § 11): a view that holds a
 * <strong>collection of tracked views</strong> — a three-level nest of
 * {@code TrackedDirectory -> List<TrackedNode> -> TrackedNode}.
 *
 * <p>{@code ArrayBackedViewProxyFactory.createUpdatable} wraps exactly the view type handed to it
 * plus {@code ViewChangeTracking}, so the type must declare both surfaces itself, exactly as
 * {@link TrackedPersonSummary} does for the shallow case.</p>
 */
public interface TrackedDirectory
        extends ViewWriter, ViewChangeTracking<TrackedDirectory_, EEnumSet<TrackedDirectory_>> {

    Long id();

    String name();

    List<TrackedNode> heads();
}
