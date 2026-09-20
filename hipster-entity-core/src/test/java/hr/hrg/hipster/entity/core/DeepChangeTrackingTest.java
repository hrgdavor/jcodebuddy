package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * The Phase 6 acceptance test for deep change tracking (plan.dsflash § 11/6.8): a change three
 * levels down, reached through nested tracked views, is reportable, while shallow tracking stays
 * exactly as it was.
 *
 * <p>The model under test is <strong>pull</strong> (D4): {@code changesDeep()} walks into the
 * children and ORs their state into the reported result <em>without</em> mutating the parent's own
 * bitset. The tests below assert that property directly — the child is asked, the parent's
 * {@code changes()} is unchanged by the walk, and a child shared by two parents reports the same
 * answer to both.</p>
 */
class DeepChangeTrackingTest {

    /**
     * The nesting fixture. It is not a Java record, an entity, or a generated type: it is the
     * smallest thing that satisfies {@link ViewChangeTracking} <em>and</em> stores a child in one of
     * its own slots, which is what {@code EntityUpdateTrackingArray.changesDeep()} walks.
     */
    enum Fields implements FieldDef {
        id,
        name,
        child;

        @Override
        public Type javaType() {
            return Object.class;
        }

        public static Fields forName(String name) {
            for (Fields field : values()) {
                if (field.name().equals(name)) {
                    return field;
                }
            }
            return null;
        }
    }

    private static EntityUpdateTrackingArray<Object, Fields> node(String name) {
        Object[] values = {1L, name, null};
        return EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(Fields.class), Fields.values(), values);
    }

    private static final int NAME = Fields.name.ordinal();
    private static final int CHILD = Fields.child.ordinal();

    @Test
    void wiringTheNestMarksTheParentButChildChangesDoNot() {
        EntityUpdateTrackingArray<Object, Fields> leaf = node("leaf");
        EntityUpdateTrackingArray<Object, Fields> middle = node("middle");
        EntityUpdateTrackingArray<Object, Fields> root = node("root");

        // Wiring A child into A field *is* a write to that field, so the parent is marked — that is
        // the shallow, one-bit change ("the reference was reassigned").
        middle.set(CHILD, leaf);
        root.set(CHILD, middle);
        Assertions.assertTrue(root.isChanged(), "assigning the child marked the root's own field");
        Assertions.assertEquals(List.of("child"), names(root.shallowPaths()));

        // Now clear that shallow bit and change only the LEAF. The shallow views must stay silent:
        // nothing was written at the root or the middle any more.
        root.clearChanges();
        middle.clearChanges();
        leaf.set(NAME, "changed");

        Assertions.assertFalse(root.isChanged(),
                "a pull model never marks the parent when only a descendant changed");
        Assertions.assertTrue(root.changes().isEmpty());
        Assertions.assertTrue(root.shallowPaths().isEmpty());

        // Shallow paths at each level stay exactly one hop.
        Assertions.assertEquals(List.of("name"), names(leaf.shallowPaths()));
        Assertions.assertTrue(middle.shallowPaths().isEmpty());
    }

    @Test
    void deepTrackingReportsAThreeLevelPath() {
        EntityUpdateTrackingArray<Object, Fields> leaf = node("leaf");
        EntityUpdateTrackingArray<Object, Fields> middle = node("middle");
        EntityUpdateTrackingArray<Object, Fields> root = node("root");

        middle.set(CHILD, leaf);
        root.set(CHILD, middle);
        leaf.set(NAME, "changed");

        // Reassigning the child marks the root, and the root's deep walk extends through the middle
        // into the leaf: one path, three levels.
        root.set(CHILD, middle);

        List<ChangePath> deep = root.changesDeep();

        Assertions.assertEquals(1, deep.size(), "one deep path for the one changed leaf; got " + deep);
        ChangePath path = deep.get(0);
        Assertions.assertEquals("child.child.name", path.render());
        Assertions.assertEquals(3, path.depth(), "three levels: root -> middle -> leaf");
        Assertions.assertEquals(Fields.name, path.leaf(), "the leaf is the field that actually changed");
    }

    @Test
    void deepWalkDoesNotMutateTheParentBitset() {
        EntityUpdateTrackingArray<Object, Fields> leaf = node("leaf");
        EntityUpdateTrackingArray<Object, Fields> root = node("root");
        root.set(CHILD, leaf);
        leaf.set(NAME, "changed");

        EEnumSet<Fields> before = root.changes();

        root.changesDeep(); // the walk

        Assertions.assertEquals(before, root.changes(),
                "changesDeep() must not OR the child's state into the parent (D4)");
        Assertions.assertEquals(1, root.changes().size(), "the parent still reports only its own write");
    }

    @Test
    void aChildSharedByTwoParentsReportsTheSameAnswerToBoth() {
        EntityUpdateTrackingArray<Object, Fields> shared = node("shared");
        EntityUpdateTrackingArray<Object, Fields> first = node("first");
        EntityUpdateTrackingArray<Object, Fields> second = node("second");

        first.set(CHILD, shared);
        second.set(CHILD, shared);
        shared.set(NAME, "changed");

        Assertions.assertEquals(paths(first), paths(second),
                "pull keeps children shareable: no parent/child lifecycle coupling");
        Assertions.assertEquals(List.of("child.name"), paths(first));
    }

    @Test
    void aViewThatNestsNothingPaysNothingForDeepTracking() {
        EntityUpdateTrackingArray<Object, Fields> lonely = node("lonely");
        lonely.set(NAME, "changed");

        Assertions.assertTrue(lonely.nestedTrackers().isEmpty(),
                "no nested tracker is discovered, so the deep walk is a single loop");
        Assertions.assertEquals(List.of("name"), names(lonely.changesDeep()));
    }

    @Test
    void nestedTrackersReportsTheChildByOrdinal() {
        EntityUpdateTrackingArray<Object, Fields> child = node("child");
        EntityUpdateTrackingArray<Object, Fields> root = node("root");
        root.set(CHILD, child);

        Map<Integer, ViewChangeTracking<?, ?>> nested = root.nestedTrackers();

        Assertions.assertEquals(1, nested.size());
        Assertions.assertSame(child, nested.get(CHILD),
                "the tracker is found by the ordinal the value occupies");
    }

    @Test
    void aReassignmentWithNoChildChangeIsStillReported() {
        EntityUpdateTrackingArray<Object, Fields> child = node("child");
        EntityUpdateTrackingArray<Object, Fields> root = node("root");
        root.set(CHILD, child);

        List<ChangePath> deep = root.changesDeep();

        Assertions.assertEquals(1, deep.size(),
                "replacing the reference is itself a change at this level");
        Assertions.assertEquals("child", deep.get(0).render());
        Assertions.assertEquals(1, deep.get(0).depth());
    }

    private static List<String> paths(ViewChangeTracking<Fields, ?> tracking) {
        return tracking.changesDeep().stream().map(ChangePath::render).toList();
    }

    private static List<String> names(List<ChangePath> paths) {
        return paths.stream().map(ChangePath::render).toList();
    }
}
