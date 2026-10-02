package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EntityUpdateTrackingArray;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import java.util.ArrayList;

/**
 * The two materializations of the {@link TrackedNode} fixture, built from one description so the
 * deep parity test cannot drift apart.
 *
 * <ul>
 *   <li>{@link #proxy(long, String)} — an {@link ArrayBackedViewProxyFactory#createUpdatable} view
 *       over an {@link EntityUpdateTrackingArray}.</li>
 *   <li>{@link #builder(long, String)} — the hand-written stand-in for a generated
 *       {@code TrackedNodeBuilderTracking}.</li>
 * </ul>
 *
 * <p><strong>The identity must be distinct per element.</strong> Reorder detection is "same
 * identity, different index", so a collection whose entries share an id has no addressable position
 * and falls back to positional deltas with a diagnostic. That is why every factory here takes the id
 * explicitly.</p>
 */
public final class TrackedNodeFixture {

    private TrackedNodeFixture() {
    }

    /** The positional row, in {@link TrackedNode_} ordinal order. */
    public static Object[] row(long id, String label) {
        return new Object[] {id, label};
    }

    /** The proxy-backed materialization of an identifiable node. */
    public static TrackedNode proxy(long id, String label) {
        Object[] values = row(id, label);
        EntityUpdateTrackingArray<Object, TrackedNode_> array = EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(TrackedNode_.class), TrackedNode_.values(), values);
        return ArrayBackedViewProxyFactory.createUpdatable(
                TrackedNode.class, array, TrackedNode_::forName);
    }

    /** The builder-side materialization of an identifiable node. */
    public static TrackedNode builder(long id, String label) {
        return new TrackedNodeBuilder(id, label);
    }

    /**
     * A tracked node with <strong>no</strong> identity: the type the positional fallback exists for.
     * It is a real array-backed tracking view, reached through the tracking contract without the
     * {@link hr.hrg.hipster.entity.api.Identifiable} mixin.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ViewChangeTracking<TrackedNode_, ?> anonymous(String label) {
        Object[] values = row(0L, label);
        EntityUpdateTrackingArray<Object, TrackedNode_> array = EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(TrackedNode_.class), TrackedNode_.values(), values);
        return array;
    }

    /** A mutable list of identifiable nodes, for the collection fixtures. */
    public static ArrayList<TrackedNode> list(TrackedNode... nodes) {
        ArrayList<TrackedNode> list = new ArrayList<>(nodes.length);
        for (TrackedNode node : nodes) {
            list.add(node);
        }
        return list;
    }
}
