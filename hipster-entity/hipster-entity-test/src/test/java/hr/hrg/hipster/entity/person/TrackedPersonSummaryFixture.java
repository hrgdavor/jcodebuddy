package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.EEnumSetBuilder;
import hr.hrg.hipster.entity.core.EEnumSetBuilder64;
import hr.hrg.hipster.entity.core.EntityUpdateTrackingArray;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import java.util.List;
import java.util.Map;

/**
 * The two materializations of the {@link TrackedPersonSummary} fixture, built from one description so
 * the shallow state-sharing test and the deep parity test cannot drift apart.
 *
 * <ul>
 *   <li>{@link #proxy()} — an {@link ArrayBackedViewProxyFactory#createUpdatable} view over an
 *       {@link EntityUpdateTrackingArray}.</li>
 *   <li>{@link #builder()} — the builder-side stand-in for a generated
 *       {@code TrackedPersonSummaryBuilderTracking}: typed fields plus one
 *       {@link EEnumSetBuilder64}.</li>
 * </ul>
 *
 * <p>The builder side is hand-written because a runtime app module must not depend on the dev-time
 * generator ({@code AGENTS.md} § 2); the generated equivalent is driven end to end in
 * {@code hipster-entity-tooling}.</p>
 */
public final class TrackedPersonSummaryFixture {

    /** The field names shared by both sides, in ordinal order after {@code id}. */
    public static final String FIRST_NAME = "Ada";
    public static final String LAST_NAME = "Lovelace";
    public static final int AGE = 36;
    public static final String DEPARTMENT = "Engineering";

    private TrackedPersonSummaryFixture() {
    }

    /** The positional row, in {@code PersonSummary_} ordinal order. */
    public static Object[] row(String firstName) {
        return new Object[] {
                1L, firstName, LAST_NAME, AGE, DEPARTMENT, Map.of()
        };
    }

    private static EntityUpdateTrackingArray<Object, PersonSummary_> array(String firstName) {
        return EntityUpdateTrackingArray.create(
                PersonSummary_.META, PersonSummary_.values(), row(firstName));
    }

    /** The proxy-backed materialization of the fixture view. */
    public static TrackedPersonSummary proxy() {
        return proxy(FIRST_NAME);
    }

    /** The proxy-backed materialization with a chosen first name. */
    public static TrackedPersonSummary proxy(String firstName) {
        return ArrayBackedViewProxyFactory.createUpdatable(
                TrackedPersonSummary.class, array(firstName), PersonSummary_.META.forName());
    }

    /** The array behind {@link #proxy()}, for a test that needs the array path itself. */
    public static EntityUpdateTrackingArray<Object, PersonSummary_> proxyArray(String firstName) {
        return array(firstName);
    }

    /** The builder-side materialization of the fixture view. */
    public static TrackedPersonSummary builder() {
        return new BuilderSide();
    }

    /**
     * The builder-side materialization, spelled the way a generated
     * {@code <View>BuilderTracking} is: one typed field per column, one change builder, and a
     * positional {@code get}/{@code set} pair driven by the field enum.
     */
    public static final class BuilderSide implements TrackedPersonSummary {
        private final EEnumSetBuilder64<PersonSummary_> mf = new EEnumSetBuilder64<>(PersonSummary_.values());
        private Long id = 1L;
        private String firstName = FIRST_NAME;
        private String lastName = LAST_NAME;
        private Integer age = AGE;
        private String departmentName = DEPARTMENT;
        private Map<String, List<Long>> metadata = Map.of();

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
        public Map<String, List<Long>> metadata() {
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
            // Compare at the write site, exactly as a generated setter does: the ordinal is marked
            // only when the value differs from the one the field holds now, and the value being
            // replaced is not retained anywhere (ViewChangeTracking keeps no baseline).
            switch (fieldOrdinal) {
                case 0 -> { addIfChanged(0, id, value); id = (Long) value; }
                case 1 -> { addIfChanged(1, firstName, value); firstName = (String) value; }
                case 2 -> { addIfChanged(2, lastName, value); lastName = (String) value; }
                case 3 -> { addIfChanged(3, age, value); age = (Integer) value; }
                case 4 -> { addIfChanged(4, departmentName, value); departmentName = (String) value; }
                default -> { addIfChanged(5, metadata, value); metadata = castMetadata(value); }
            }
        }

        private void addIfChanged(int ordinal, Object held, Object value) {
            if (!java.util.Objects.equals(held, value)) {
                mf.addOrdinal(ordinal);
            }
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

        @SuppressWarnings("unchecked")
        private static Map<String, List<Long>> castMetadata(Object value) {
            return (Map<String, List<Long>>) value;
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
}
