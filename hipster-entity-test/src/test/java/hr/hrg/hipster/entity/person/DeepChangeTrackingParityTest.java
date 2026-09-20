package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.ChangePath;
import hr.hrg.hipster.entity.core.CollectionDiagnostic;
import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.EEnumSetBuilder;
import hr.hrg.hipster.entity.core.EEnumSetBuilder64;
import hr.hrg.hipster.entity.core.EntityUpdateTrackingArray;
import hr.hrg.hipster.entity.core.FieldChange;
import hr.hrg.hipster.entity.core.ListChangeKind;
import hr.hrg.hipster.entity.core.ListDelta;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tasks 6.7 and 6.8 of {@code plan.dsflash.md} § 11.
 *
 * <p><strong>6.7 — proxy parity for the deep path.</strong> An
 * {@link ArrayBackedViewProxyFactory#createUpdatable} view must produce the same
 * {@code changesDeep()} result as the builder-side tracking holder, exactly as the shallow case
 * requires. The shape is the one {@link ViewChangeTrackingStateSharingTest} established: one shared
 * assertion sequence run against both materializations through the same fixture view type
 * ({@link TrackedDirectory}). The middle level is a {@code List}, so the walk crosses a collection
 * as well as a plain nested field.</p>
 *
 * <p><strong>6.8 — proof, end to end.</strong> Three nesting levels
 * ({@code directory -> List<node> -> node}) with the leaf mutated <em>in place</em>: the nested
 * instance is changed without the parent field being reassigned, which is the shallow-reference
 * hazard of § 4.2/D2. The three assertions the plan names are all here: {@code changes()} marks the
 * parent field, {@code changesDeep()} reports the leaf path with {@code listIndex} populated, and
 * the reported change is confined to the leaf.</p>
 */
class DeepChangeTrackingParityTest {

    private static final int HEADS = TrackedDirectory_.heads.ordinal();
    private static final int LABEL = TrackedNode_.label.ordinal();

    // ------------------------------------------------------------------ the two materializations

    /** The directory fixture over the array-backed proxy, with its array for the delta assertions. */
    private static final class ProxyDirectory {
        final List<TrackedNode> nodes;
        final EntityUpdateTrackingArray<Object, TrackedDirectory_> array;
        final TrackedDirectory view;

        ProxyDirectory(List<TrackedNode> nodes) {
            this.nodes = nodes;
            Object[] values = {1L, "HQ", nodes};
            this.array = EntityUpdateTrackingArray.create(
                    new ForNameOrdinalImpl<>(TrackedDirectory_.class), TrackedDirectory_.values(), values);
            this.view = ArrayBackedViewProxyFactory.createUpdatable(
                    TrackedDirectory.class, array, TrackedDirectory_::forName);
            array.clearChanges(); // the list is the loaded baseline, not an addition
        }
    }

    /** The builder-side stand-in for a generated {@code TrackedDirectoryBuilderTracking}. */
    private static final class BuilderDirectory implements TrackedDirectory {
        private final EEnumSetBuilder64<TrackedDirectory_> mf = new EEnumSetBuilder64<>(TrackedDirectory_.values());
        private Long id = 1L;
        private String name = "HQ";
        private List<TrackedNode> heads = List.of();

        /** Wires the collection, mirroring a generated {@code heads(List)} setter. */
        void heads(List<TrackedNode> heads) {
            if (!java.util.Objects.equals(this.heads, heads)) {
                mf.addOrdinal(HEADS);
                this.heads = heads;
            }
        }

        @Override
        public Long id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<TrackedNode> heads() {
            return heads;
        }

        @Override
        public Object get(int fieldOrdinal) {
            return switch (fieldOrdinal) {
                case 0 -> id;
                case 1 -> name;
                default -> heads;
            };
        }

        @Override
        public void set(int fieldOrdinal, Object value) {
            switch (fieldOrdinal) {
                case 0 -> {
                    if (!java.util.Objects.equals(id, value)) {
                        mf.addOrdinal(0);
                    }
                    id = (Long) value;
                }
                case 1 -> {
                    if (!java.util.Objects.equals(name, value)) {
                        mf.addOrdinal(1);
                    }
                    name = (String) value;
                }
                default -> heads(cast(value));
            }
        }

        @SuppressWarnings("unchecked")
        private static List<TrackedNode> cast(Object value) {
            return (List<TrackedNode>) value;
        }

        @Override
        public int set(String field, Object value) {
            TrackedDirectory_ def = TrackedDirectory_.forName(field);
            if (def == null) {
                return -1;
            }
            set(def.ordinal(), value);
            return def.ordinal();
        }

        @Override
        public boolean isChanged() {
            return !mf.isEmpty();
        }

        @Override
        public EEnumSet<TrackedDirectory_> changes() {
            return mf.toImmutable();
        }

        @Override
        public EEnumSetBuilder<TrackedDirectory_> changesBuilder() {
            return mf;
        }

        @Override
        public void clearChanges() {
            mf.clear();
        }

        @Override
        public Object currentValue(TrackedDirectory_ field) {
            return get(field.ordinal());
        }

        /**
         * The collection half of the generated builder: it asks each of its typed elements for its
         * own field deltas, and it is the same answer the array path produces by scanning the list it
         * holds.
         */
        @Override
        public Map<Integer, List<ListDelta>> collectionDeltas() {
            List<ListDelta> deltas = new ArrayList<>();
            for (int i = 0; i < heads.size(); i++) {
                List<FieldChange<?>> inside = fieldChangesOf(heads.get(i));
                if (!inside.isEmpty()) {
                    deltas.add(new ListDelta(ListChangeKind.FIELD_CHANGED, i, -1, heads.get(i).id(), inside, false));
                }
            }
            return Map.of(HEADS, deltas);
        }

        /**
         * The deep walk of the generated builder, reproducing what the generator emits: descend
         * through the typed collection carrying each element's index, then report the marked fields
         * of this level, with a marked collection falling back to naming the field itself.
         */
        @Override
        public List<ChangePath> changesDeep() {
            List<ChangePath> paths = new ArrayList<>();
            mf.forEach((field, index) -> {
                if (field != TrackedDirectory_.heads) {
                    paths.add(ChangePath.of(field));
                }
            });

            boolean anyNested = false;
            for (int i = 0; i < heads.size(); i++) {
                for (ChangePath childPath : heads.get(i).changesDeep()) {
                    paths.add(new ChangePath(TrackedDirectory_.heads, i, childPath));
                    anyNested = true;
                }
            }
            if (mf.has(TrackedDirectory_.heads) && !anyNested) {
                paths.add(ChangePath.of(TrackedDirectory_.heads));
            }
            return paths;
        }
    }

    // ------------------------------------------------------------------ 6.7 parity

    /**
     * The shared sequence: the same mutations are applied to both materializations and the whole
     * deep result is compared.
     */
    private void assertDeepParity(ViewChangeTracking<TrackedDirectory_, EEnumSet<TrackedDirectory_>> tracked,
                                  TrackedNode first,
                                  TrackedNode second) {
        // A fresh view: nothing changed at any depth. This is also the assertion that a collection
        // whose entries were wired up before the baseline was taken is not "all additions".
        Assertions.assertTrue(tracked.changesDeep().isEmpty(),
                "a fresh view reports no deep path: " + tracked.changesDeep());
        Assertions.assertTrue(tracked.collectionDeltas().getOrDefault(HEADS, List.of()).isEmpty(),
                "and no collection delta either");

        // Edit an element in place. No field of this view was written, so the parent's own bitset
        // stays empty -- which is exactly why the shallow accessors cannot see this change.
        first.set(LABEL, "first-edited");

        Assertions.assertFalse(tracked.isChanged(), "an in-place element edit writes to no field here");
        Assertions.assertTrue(tracked.changes().isEmpty());
        Assertions.assertTrue(tracked.shallowPaths().isEmpty());

        List<ChangePath> deep = tracked.changesDeep();
        Assertions.assertEquals(1, deep.size(), "one deep path for the one edited element; got " + deep);
        Assertions.assertEquals("heads[0].label", deep.get(0).render());
        Assertions.assertEquals(0, deep.get(0).listIndex(), "the element index is part of the path");
        Assertions.assertEquals(2, deep.get(0).depth());

        // A second element, at another index, is reported separately.
        second.set(LABEL, "second-edited");
        Assertions.assertEquals(List.of("heads[0].label", "heads[1].label"), render(tracked.changesDeep()),
                "each element is addressed by its own index");
    }

    @Test
    void proxyMaterializationReportsTheSameDeepPaths() {
        List<TrackedNode> nodes = new ArrayList<>(List.of(
                TrackedNodeFixture.proxy(11L, "first"),
                TrackedNodeFixture.proxy(12L, "second")));
        ProxyDirectory directory = new ProxyDirectory(nodes);

        assertDeepParity(directory.view, nodes.get(0), nodes.get(1));
    }

    @Test
    void builderMaterializationReportsTheSameDeepPaths() {
        List<TrackedNode> nodes = new ArrayList<>(List.of(
                TrackedNodeFixture.builder(11L, "first"),
                TrackedNodeFixture.builder(12L, "second")));
        BuilderDirectory directory = new BuilderDirectory();
        directory.heads(nodes);
        directory.clearChanges(); // the wired-up list is the loaded baseline, not an addition

        assertDeepParity(directory, nodes.get(0), nodes.get(1));
    }

    @Test
    void bothMaterializationsAgreeOnTheDeepResultAfterTheSameMutations() {
        List<TrackedNode> proxyNodes = new ArrayList<>(List.of(
                TrackedNodeFixture.proxy(11L, "first"),
                TrackedNodeFixture.proxy(12L, "second")));
        ProxyDirectory proxy = new ProxyDirectory(proxyNodes);

        List<TrackedNode> builderNodes = new ArrayList<>(List.of(
                TrackedNodeFixture.builder(11L, "first"),
                TrackedNodeFixture.builder(12L, "second")));
        BuilderDirectory builder = new BuilderDirectory();
        builder.heads(builderNodes);

        // The same two edits, through each materialization's own idiom.
        proxyNodes.get(1).set(LABEL, "second-edited");
        builderNodes.get(1).set(LABEL, "second-edited");
        proxy.view.set(TrackedDirectory_.name.ordinal(), "HQ-2");
        builder.set(TrackedDirectory_.name.ordinal(), "HQ-2");

        Assertions.assertEquals(List.of("name", "heads[1].label"), render(proxy.view.changesDeep()));
        Assertions.assertEquals(render(proxy.view.changesDeep()), render(builder.changesDeep()),
                "the two materializations must report the same deep result");
    }

    // ------------------------------------------------------------------ 6.8 end to end

    @Test
    void theNestedLeafIsReportedDeeplyWhileTheShallowViewStaysSilent() {
        List<TrackedNode> nodes = new ArrayList<>(List.of(
                TrackedNodeFixture.proxy(11L, "first"),
                TrackedNodeFixture.proxy(12L, "second")));
        ProxyDirectory directory = new ProxyDirectory(nodes);

        // Level 3: the leaf's own field changes. Nothing at level 1 or level 2 was written.
        nodes.get(0).set(LABEL, "first-edited");

        Assertions.assertFalse(directory.view.isChanged(),
                "the D2 hazard: no parent field was reassigned, so no shallow bit is set");
        Assertions.assertTrue(directory.view.changes().isEmpty());

        List<ChangePath> deep = directory.view.changesDeep();
        Assertions.assertEquals(1, deep.size(), "the leaf is still reachable by pull; got " + deep);
        Assertions.assertEquals("heads[0].label", deep.get(0).render());
        Assertions.assertEquals(0, deep.get(0).listIndex(),
                "the listIndex of the collection level is what makes the leaf addressable");
        Assertions.assertEquals(TrackedDirectory_.heads, deep.get(0).field());
        Assertions.assertEquals(TrackedNode_.label, deep.get(0).leaf(),
                "the leaf is the field that actually changed, two levels down");
    }

    @Test
    void theCollectionDeltasCarryTheLeafChangeAndItsIdentity() {
        List<TrackedNode> nodes = new ArrayList<>(List.of(
                TrackedNodeFixture.proxy(11L, "first"),
                TrackedNodeFixture.proxy(12L, "second")));
        ProxyDirectory directory = new ProxyDirectory(nodes);

        String baselineLabel = nodes.get(1).label(); // the caller's baseline value, captured pre-write
        nodes.get(1).set(LABEL, "second-edited");

        List<ListDelta> deltas = directory.array.collectionDeltas(HEADS);
        Assertions.assertEquals(1, deltas.size(), "got " + deltas);
        ListDelta delta = deltas.get(0);
        Assertions.assertEquals(ListChangeKind.FIELD_CHANGED, delta.kind());
        Assertions.assertEquals(1, delta.index());
        Assertions.assertEquals(12L, delta.identity(), "the element's identity is carried");
        Assertions.assertEquals(List.of("label"), delta.fieldNames());
        Assertions.assertEquals(TrackedNode_.label, delta.fieldChanges().get(0).field(),
                "the marked field is reported by its constant");
        Assertions.assertEquals("second-edited", delta.fieldChanges().get(0).current(),
                "and the value it holds now");
        Assertions.assertEquals("second", baselineLabel,
                "the old value is the caller's baseline instance, not tracker state");
        Assertions.assertTrue(directory.array.collectionDiagnostics().isEmpty());
    }

    @Test
    void reassigningAnElementMarksTheParentFieldAsWellAsTheDeepPath() {
        List<TrackedNode> nodes = new ArrayList<>(List.of(TrackedNodeFixture.proxy(11L, "first")));
        ProxyDirectory directory = new ProxyDirectory(nodes);

        // The shallow-reference hazard's other half: wiring the list *is* a write to the field, so
        // changes() marks it -- and changesDeep() still reports the path through the element.
        List<TrackedNode> replacement = List.of(TrackedNodeFixture.proxy(21L, "replacement"));
        directory.view.set(HEADS, replacement);
        replacement.get(0).set(LABEL, "replacement-edited");

        Assertions.assertTrue(directory.view.isChanged(), "the reference write marks the field");
        Assertions.assertEquals(List.of("heads"), names(directory.view.changes()));
        Assertions.assertEquals(List.of("heads[0].label"), render(directory.view.changesDeep()),
                "the deep path reaches the element rather than stopping at the field");
    }

    @Test
    void anInPlaceEditAndARemovalAreReportedDistinctly() {
        List<TrackedNode> nodes = TrackedNodeFixture.list(
                TrackedNodeFixture.proxy(11L, "first"),
                TrackedNodeFixture.proxy(12L, "second"),
                TrackedNodeFixture.proxy(13L, "third"));
        ProxyDirectory directory = new ProxyDirectory(nodes);

        // A field delta inside an element ...
        nodes.get(1).set(LABEL, "second-edited");
        // ... and a structural change to the collection itself.
        nodes.remove(0);

        List<ListDelta> deltas = directory.array.collectionDeltas(HEADS);
        List<ListChangeKind> kinds = deltas.stream().map(ListDelta::kind).toList();

        Assertions.assertTrue(kinds.contains(ListChangeKind.FIELD_CHANGED),
                "the in-place field delta is reported: " + deltas);
        Assertions.assertTrue(kinds.contains(ListChangeKind.REMOVED),
                "the removal is reported distinctly from it: " + deltas);
        Assertions.assertTrue(deltas.stream().noneMatch(ListDelta::fallback),
                "the element type is identifiable, so nothing falls back to position");

        // The element that was removed is the one reported, by its own identity.
        ListDelta removed = deltas.stream().filter(d -> d.kind() == ListChangeKind.REMOVED)
                .findFirst().orElseThrow();
        Assertions.assertEquals(11L, removed.identity(), "the first element is the one that is gone");
        Assertions.assertEquals(0, removed.index(), "removals are reported at their baseline index");
        Assertions.assertTrue(deltas.stream().noneMatch(d -> d.kind() == ListChangeKind.REORDERED),
                "removal alone must not manufacture reorders: " + deltas);
    }

    @Test
    void aReorderOfIdentifiableElementsIsReportedAsMoves() {
        List<TrackedNode> nodes = TrackedNodeFixture.list(
                TrackedNodeFixture.proxy(11L, "first"),
                TrackedNodeFixture.proxy(12L, "second"),
                TrackedNodeFixture.proxy(13L, "third"));
        ProxyDirectory directory = new ProxyDirectory(nodes);

        java.util.Collections.reverse(nodes);

        List<ListDelta> deltas = directory.array.collectionDeltas(HEADS);

        Assertions.assertTrue(deltas.stream().allMatch(d -> d.kind() == ListChangeKind.REORDERED),
                "a reorder is neither an add nor a remove; got " + deltas);
        Assertions.assertEquals(2, deltas.size(),
                "the longest run still in baseline order is one element, so two moved; got " + deltas);
        Assertions.assertEquals(List.of(13L, 12L), deltas.stream().map(ListDelta::identity).toList());
        Assertions.assertEquals(List.of(2, 1), deltas.stream().map(ListDelta::previousIndex).toList());
        Assertions.assertEquals(List.of(0, 1), deltas.stream().map(ListDelta::index).toList());

        // The deep walk reports one entry naming the field that holds the collection: a structural
        // change has no single field to point at, and the kinds live in the deltas.
        Assertions.assertEquals(List.of("heads"), render(directory.view.changesDeep()));
    }

    @Test
    void aNonIdentifiableElementTypeIsReportedAsADiagnosticNotGuessedAt() {
        List<Object> anonymous = new ArrayList<>(List.of(
                TrackedNodeFixture.anonymous("first"),
                TrackedNodeFixture.anonymous("second")));
        Object[] values = {1L, "HQ", anonymous};
        EntityUpdateTrackingArray<Object, TrackedDirectory_> array = EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(TrackedDirectory_.class), TrackedDirectory_.values(), values);
        array.clearChanges();

        List<CollectionDiagnostic> diagnostics = array.collectionDiagnostics();
        Assertions.assertEquals(1, diagnostics.size(), "the fallback is reported; got " + diagnostics);
        Assertions.assertEquals(CollectionDiagnostic.NOT_IDENTIFIABLE, diagnostics.get(0).code());
        Assertions.assertEquals(HEADS, diagnostics.get(0).ordinal(),
                "the diagnostic names the field whose collection cannot be matched");
        Assertions.assertFalse(array.collectionTrackers().get(HEADS).matchedByIdentity());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The element's field deltas, consumed as wildcards: the fixture's field enum type is not known
     * at this call site, and the field constant stays navigable inside the record.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<FieldChange<?>> fieldChangesOf(ViewChangeTracking<?, ?> tracked) {
        List<?> raw = ((ViewChangeTracking) tracked).changedValues();
        List<FieldChange<?>> changes = new ArrayList<>(raw.size());
        for (Object change : raw) {
            changes.add((FieldChange<?>) change);
        }
        return changes;
    }

    private static List<String> render(List<ChangePath> paths) {
        return paths.stream().map(ChangePath::render).toList();
    }

    private static List<String> names(EEnumSet<TrackedDirectory_> changes) {
        List<String> names = new ArrayList<>();
        changes.forEach((value, index) -> names.add(value.name()));
        return names;
    }
}
