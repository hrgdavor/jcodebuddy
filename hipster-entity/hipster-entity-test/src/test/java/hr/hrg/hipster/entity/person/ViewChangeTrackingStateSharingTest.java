package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.EEnumSetBuilder;
import hr.hrg.hipster.entity.core.EEnumSetBuilder64;
import hr.hrg.hipster.entity.core.EntityUpdateTrackingArray;
import hr.hrg.hipster.entity.core.FieldChange;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The S5 state-sharing acceptance test of {@code plan.dsflash.md} § 6.4 steps 8–12, run against
 * <strong>both</strong> materializations through the <em>same</em> fixture view type
 * ({@link TrackedPersonSummary}) so the assertions are literally identical:
 *
 * <ul>
 *   <li>the proxy path — {@link ArrayBackedViewProxyFactory#createUpdatable};</li>
 *   <li>the builder path — a hand-written tracking holder with the same contract, standing in for
 *       the generated {@code <View>BuilderTracking}.</li>
 * </ul>
 *
 * <p>The generated builder is additionally driven end to end by the tooling module's
 * {@code GeneratedTrackingBuilderContractTest}, which compiles and loads the emitted source. That
 * test lives there because a runtime app module must not depend on the dev-time generator
 * ({@code AGENTS.md} § 2).</p>
 *
 * <p>Step 10 is the one that catches the mistake S5 exists to prevent: an implementation returning
 * a <em>copy</em> from {@code changesBuilder()} passes steps 8–9 and fails step 10.</p>
 */
class ViewChangeTrackingStateSharingTest {

    /** The ordinal exercised by the parity sequence; 0 is immutable on the array path (DoD #4). */
    private static final int FIRST_NAME = PersonSummary_.firstName.ordinal();
    private static final int AGE = PersonSummary_.age.ordinal();

    /**
     * The positional row. Deliberately an {@code Object[]}: the ordinal-array contract is
     * heterogeneous (a {@code Long} id, {@code String} names, an {@code Integer} age, a
     * {@code Map} metadata), so a {@code String[]} would throw {@code ArrayStoreException} on the
     * first non-String write.
     */
    private static final Object[] ROW = {
            1L, "Ada", "Lovelace", 36, "Engineering", java.util.Map.of()
    };

    // ------------------------------------------------------------------ fixtures

    private TrackedPersonSummary proxy() {
        Object[] values = ROW.clone();
        EntityUpdateTrackingArray<Object, PersonSummary_> array =
                EntityUpdateTrackingArray.create(PersonSummary_.META, PersonSummary_.values(), values);
        return ArrayBackedViewProxyFactory.createUpdatable(
                TrackedPersonSummary.class, array, PersonSummary_.META.forName());
    }

    /**
     * The builder-side materialization of the same contract. Hand-written here rather than
     * generated: a runtime app module cannot depend on the dev-time generator, so the generated
     * equivalent is exercised in the tooling module instead, and this stand-in keeps the
     * view-level parity assertions in this module.
     */
    private static final class BuilderSide implements TrackedPersonSummary {
        private final EEnumSetBuilder64<PersonSummary_> mf = new EEnumSetBuilder64<>(PersonSummary_.values());
        private Long id = 1L;
        private String firstName = "Ada";
        private String lastName = "Lovelace";
        private Integer age = 36;
        private String departmentName = "Engineering";
        private java.util.Map<String, List<Long>> metadata = java.util.Map.of();

        @Override
        public Long id() {
            return id;
        }

        @Override
        public String firstName() {
            return firstName;
        }

        @Override
        public String lastName() {
            return lastName;
        }

        @Override
        public Integer age() {
            return age;
        }

        @Override
        public String departmentName() {
            return departmentName;
        }

        @Override
        public java.util.Map<String, List<Long>> metadata() {
            return metadata;
        }

        @Override
        public Object get(int fieldOrdinal) {
            return switch (fieldOrdinal) {
                case 0 -> id;
                case 1 -> firstName;
                case 2 -> lastName;
                case 3 -> age;
                case 4 -> departmentName;
                default -> metadata;
            };
        }

        @Override
        public void set(int fieldOrdinal, Object value) {
            // Compare at the write site, as a generated setter does; only a differing value marks
            // the ordinal, and the replaced value is never retained.
            switch (fieldOrdinal) {
                case 0 -> { addIfChanged(0, id, value); id = (Long) value; }
                case 1 -> { addIfChanged(1, firstName, value); firstName = (String) value; }
                case 2 -> { addIfChanged(2, lastName, value); lastName = (String) value; }
                case 3 -> { addIfChanged(3, age, value); age = (Integer) value; }
                case 4 -> { addIfChanged(4, departmentName, value); departmentName = (String) value; }
                default -> { addIfChanged(5, metadata, value); metadata = cast(value); }
            }
        }

        private void addIfChanged(int ordinal, Object held, Object value) {
            if (!java.util.Objects.equals(held, value)) {
                mf.addOrdinal(ordinal);
            }
        }

        @SuppressWarnings("unchecked")
        private static java.util.Map<String, List<Long>> cast(Object value) {
            return (java.util.Map<String, List<Long>>) value;
        }

        @Override
        public int set(String field, Object value) {
            PersonSummary_ def = PersonSummary_.forName(field);
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
        public EEnumSet<PersonSummary_> changes() {
            return mf.toImmutable();
        }

        @Override
        public EEnumSetBuilder<PersonSummary_> changesBuilder() {
            return mf;
        }

        @Override
        public void clearChanges() {
            mf.clear();
        }

        @Override
        public Object currentValue(PersonSummary_ field) {
            return get(field.ordinal());
        }
    }

    // ------------------------------------------------------------------ the shared sequence

    /**
     * Steps 8–12 of § 6.4, expressed once and run against both materializations.
     *
     * @param mutate  performs one real change on the given ordinal (an ordinal-appropriate value)
     * @param restore writes the ordinal back to its baseline value
     */
    private void assertStateSharing(ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>> tracked,
                                    java.util.function.IntConsumer mutate,
                                    java.util.function.IntConsumer restore) {
        // 8: a fresh tracking view
        Assertions.assertTrue(tracked.changes().isEmpty(), "fresh view: changes() is empty");
        Assertions.assertFalse(tracked.isChanged(), "fresh view: isChanged() is false");

        // 9: one real change — both accessors agree
        mutate.accept(FIRST_NAME);
        Assertions.assertTrue(tracked.changesBuilder().has(FIRST_NAME), "changesBuilder() sees the change");
        Assertions.assertTrue(tracked.changes().has(FIRST_NAME), "changes() agrees with changesBuilder()");
        Assertions.assertTrue(tracked.isChanged());

        // 11 (captured before 10): a snapshot is a point-in-time value
        EEnumSet<PersonSummary_> before = tracked.changes();
        Assertions.assertTrue(before.has(FIRST_NAME));

        // 10: mutating through the builder must be visible through a later changes().
        //     An implementation returning a copy from changesBuilder() fails exactly here.
        tracked.changesBuilder().removeOrdinal(FIRST_NAME);
        Assertions.assertFalse(tracked.changes().has(FIRST_NAME),
                "mutation through changesBuilder() must be visible through changes()");
        Assertions.assertFalse(tracked.isChanged(), "isChanged() reflects the same shared state");

        // 11: the earlier snapshot still reports the old state
        Assertions.assertTrue(before.has(FIRST_NAME), "changes() is a snapshot, not a live alias");

        // 12: clearChanges() empties both accessors
        mutate.accept(AGE);
        tracked.changesBuilder().addOrdinal(FIRST_NAME);
        Assertions.assertTrue(tracked.isChanged());
        tracked.clearChanges();
        Assertions.assertTrue(tracked.changes().isEmpty(), "clearChanges() empties changes()");
        Assertions.assertTrue(tracked.changesBuilder().isEmpty(), "clearChanges() empties changesBuilder()");
        Assertions.assertFalse(tracked.isChanged());

        restore.accept(FIRST_NAME);
        restore.accept(AGE);
    }

    @Test
    void proxyMaterializationSharesState() {
        TrackedPersonSummary view = proxy();
        assertStateSharing(view, ordinal -> view.set(ordinal, valueFor(ordinal)),
                ordinal -> view.set(ordinal, baselineFor(ordinal)));
    }

    @Test
    void builderMaterializationSharesState() {
        BuilderSide view = new BuilderSide();
        assertStateSharing(view, ordinal -> view.set(ordinal, valueFor(ordinal)),
                ordinal -> view.set(ordinal, baselineFor(ordinal)));
    }

    /** A changed value of the right runtime type for the ordinal (the setters cast, as generated ones do). */
    private static Object valueFor(int ordinal) {
        return ordinal == AGE ? 37 : "Grace";
    }

    /** The baseline value of the right runtime type; keeps the shared sequence independent of ordinal. */
    private static Object baselineFor(int ordinal) {
        return ordinal == AGE ? 36 : "Ada";
    }

    // ------------------------------------------------------------------ parity of the two paths

    @Test
    void changedValuesMatchOnBothPathsAndTheCallerHoldsTheBaseline() {
        TrackedPersonSummary proxyView = proxy();
        BuilderSide builderView = new BuilderSide();

        for (TrackedPersonSummary view : List.of(proxyView, builderView)) {
            // The baseline is the caller's own instance, so the caller captures the old values
            // before the writes: the tracker keeps no previous value to ask for afterwards.
            String baselineFirstName = view.firstName();
            Integer baselineAge = view.age();

            view.set(FIRST_NAME, "Grace");
            view.set(AGE, 37);

            // (a) the ordinals are marked ...
            Assertions.assertTrue(view.changesBuilder().has(FIRST_NAME), view.getClass().getSimpleName());
            Assertions.assertTrue(view.changesBuilder().has(AGE), view.getClass().getSimpleName());
            Assertions.assertFalse(view.changesBuilder().has(PersonSummary_.lastName.ordinal()),
                    "a field that was not written is not marked: " + view.getClass().getSimpleName());

            // ... and (b) currentValue() reports the value the field holds now.
            Assertions.assertEquals("Grace", view.currentValue(PersonSummary_.firstName),
                    view.getClass().getSimpleName());
            Assertions.assertEquals(37, view.currentValue(PersonSummary_.age),
                    view.getClass().getSimpleName());

            List<String> names = view.changedValues().stream().map(FieldChange::fieldName).toList();
            Assertions.assertEquals(List.of("firstName", "age"), names, view.getClass().getSimpleName());

            List<Object> current = view.changedValues().stream().map(FieldChange::current).toList();
            Assertions.assertEquals(List.of("Grace", 37), current, view.getClass().getSimpleName());

            // The old half of an "old -> new" report is the baseline instance the caller holds:
            // the library reports only the current value, and the caller compares the two itself.
            Assertions.assertEquals("Ada", baselineFirstName, view.getClass().getSimpleName());
            Assertions.assertEquals(36, baselineAge, view.getClass().getSimpleName());
        }
    }

    /** The D5 split (test 18): the low-level path reports {@code -1}, the proxy throws. */
    @Test
    void unknownFieldNameSplitIsPreserved() {
        Object[] values = ROW.clone();
        EntityUpdateTrackingArray<Object, PersonSummary_> array =
                EntityUpdateTrackingArray.create(PersonSummary_.META, PersonSummary_.values(), values);
        Assertions.assertEquals(-1, array.set("noSuchField", "x"),
                "the array probes a name and reports positionally");

        BuilderSide builder = new BuilderSide();
        Assertions.assertEquals(-1, builder.set("noSuchField", "x"),
                "the generated-builder contract is the same -1");

        TrackedPersonSummary view = proxy();
        Assertions.assertThrows(IllegalArgumentException.class, () -> view.set("noSuchField", "x"),
                "the user-facing proxy validates and throws");
    }

    /** The DEC-012 no-op rule on the proxy path, which reaches it through the array. */
    @Test
    void equalValueWriteOnProxyIsANoOp() {
        TrackedPersonSummary view = proxy();
        view.set(FIRST_NAME, "Ada");

        Assertions.assertFalse(view.isChanged());
        Assertions.assertTrue(view.changes().isEmpty());
        Assertions.assertTrue(view.changedValues().isEmpty(),
                "an equal-value write marks nothing, so there is no change to report");
        Assertions.assertEquals("Ada", view.currentValue(PersonSummary_.firstName),
                "the value is read from the view itself; no previous value is kept to compare against");
    }

    /** The immutable-id rule is array-path only (§ 3 DoD #4), asserted so parity is not over-claimed. */
    @Test
    void ordinalZeroIsImmutableOnTheArrayPathOnly() {
        Object[] values = ROW.clone();
        EntityUpdateTrackingArray<Object, PersonSummary_> array =
                EntityUpdateTrackingArray.create(PersonSummary_.META, PersonSummary_.values(), values);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> array.set(0, 2L));

        BuilderSide builder = new BuilderSide();
        Long baselineId = builder.id(); // the value the caller's baseline instance still holds
        builder.set(0, 2L);
        Assertions.assertTrue(builder.isChanged(), "the generated tracking builder keeps an ordinal-0 setter");
        Assertions.assertTrue(builder.changesBuilder().has(0), "the ordinal-0 write is marked");
        Assertions.assertEquals(2L, builder.currentValue(PersonSummary_.id),
                "currentValue() reports the value the field holds now");
        Assertions.assertEquals(1L, baselineId,
                "the replaced value belongs to the caller's baseline instance, not to the tracker");
    }
}
