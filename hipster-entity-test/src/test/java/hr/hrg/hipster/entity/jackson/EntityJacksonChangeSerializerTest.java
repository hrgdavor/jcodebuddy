package hr.hrg.hipster.entity.jackson;

import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.EntityUpdateTrackingArray;
import hr.hrg.hipster.entity.core.ViewChangeTracking;
import hr.hrg.hipster.entity.person.PersonSummary_;
import hr.hrg.hipster.entity.person.TrackedPersonSummary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/**
 * The Phase 2 exit gate of {@code plan.dsflash.md} § 7: a mutation produces a one-field JSON patch,
 * and the same patch is produced from the proxy path.
 */
class EntityJacksonChangeSerializerTest {

    private static final Object[] ROW = {
            1L, "Ada", "Lovelace", 36, "Engineering", Map.of()
    };

    private static final int FIRST_NAME = PersonSummary_.firstName.ordinal();
    private static final int AGE = PersonSummary_.age.ordinal();

    private TrackedPersonSummary proxy() {
        Object[] values = ROW.clone();
        EntityUpdateTrackingArray<Object, PersonSummary_> array =
                EntityUpdateTrackingArray.create(PersonSummary_.META, PersonSummary_.values(), values);
        return ArrayBackedViewProxyFactory.createUpdatable(
                TrackedPersonSummary.class, array, PersonSummary_.META.forName());
    }

    private static String toJson(ViewChangeTracking<PersonSummary_, ?> tracking) {
        StringWriter writer = new StringWriter();
        EntityJacksonMapper.toJsonChanges(PersonSummary_.META, tracking, writer);
        return writer.toString();
    }

    /** A hand-written second materialization of the same contract, mirroring the generated builder. */
    private static final class BuilderSide implements TrackedPersonSummary {
        private final hr.hrg.hipster.entity.core.EEnumSetBuilder64<PersonSummary_> mf =
                new hr.hrg.hipster.entity.core.EEnumSetBuilder64<>(PersonSummary_.values());
        private Long id = 1L;
        private String firstName = "Ada";
        private String lastName = "Lovelace";
        private Integer age = 36;
        private String departmentName = "Engineering";
        private Map<String, List<Long>> metadata = Map.of();

        @Override public Long id() { return id; }
        @Override public String firstName() { return firstName; }
        @Override public String lastName() { return lastName; }
        @Override public Integer age() { return age; }
        @Override public String departmentName() { return departmentName; }
        @Override public Map<String, List<Long>> metadata() { return metadata; }

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
            switch (fieldOrdinal) {
                case 1 -> { addIfChanged(1, firstName, value); firstName = (String) value; }
                case 2 -> { addIfChanged(2, lastName, value); lastName = (String) value; }
                case 3 -> { addIfChanged(3, age, value); age = (Integer) value; }
                case 4 -> { addIfChanged(4, departmentName, value); departmentName = (String) value; }
                default -> { }
            }
        }

        private void addIfChanged(int ordinal, Object held, Object value) {
            if (!java.util.Objects.equals(held, value)) {
                mf.addOrdinal(ordinal);
            }
        }

        @Override public int set(String field, Object value) {
            PersonSummary_ def = PersonSummary_.forName(field);
            if (def == null) { return -1; }
            set(def.ordinal(), value);
            return def.ordinal();
        }

        @Override public boolean isChanged() { return !mf.isEmpty(); }
        @Override public EEnumSet<PersonSummary_> changes() { return mf.toImmutable(); }
        @Override public hr.hrg.hipster.entity.core.EEnumSetBuilder<PersonSummary_> changesBuilder() { return mf; }
        @Override public void clearChanges() { mf.clear(); }
        @Override public Object currentValue(PersonSummary_ field) { return get(field.ordinal()); }
    }

    @Test
    void oneFieldPatchFromTheProxyPath() {
        TrackedPersonSummary view = proxy();
        view.set(FIRST_NAME, "Grace");

        Assertions.assertEquals("{\"firstName\":\"Grace\"}", toJson(view));
    }

    @Test
    void oneFieldPatchFromTheBuilderPath() {
        BuilderSide view = new BuilderSide();
        view.set(FIRST_NAME, "Grace");

        Assertions.assertEquals("{\"firstName\":\"Grace\"}", toJson(view));
    }

    @Test
    void bothPathsProduceTheSamePatch() {
        TrackedPersonSummary proxyView = proxy();
        BuilderSide builderView = new BuilderSide();

        for (TrackedPersonSummary view : List.of(proxyView, builderView)) {
            view.set(AGE, 37);
            view.set(FIRST_NAME, "Grace");
        }

        Assertions.assertEquals(toJson(proxyView), toJson(builderView));
        Assertions.assertEquals("{\"firstName\":\"Grace\",\"age\":37}", toJson(proxyView),
                "fields are written in ordinal order, and only the changed ones");
    }

    /**
     * The flat merge-patch shape, and why it is flat: the tracker keeps no previous value, so there
     * is no old&nbsp;&rarr;&nbsp;new pair to write. The old value belongs to the baseline instance
     * the caller still holds, and the caller reads it there.
     */
    @Test
    void eachChangedFieldIsWrittenAsItsCurrentValueWithNoPreviousPair() {
        TrackedPersonSummary view = proxy();
        String baselineFirstName = view.firstName(); // the caller's baseline, captured pre-write

        view.set(FIRST_NAME, "Grace");

        String json = toJson(view);
        Assertions.assertEquals("{\"firstName\":\"Grace\"}", json,
                "an entry is the field's current value, not a previous/current object");
        Assertions.assertFalse(json.contains("\"previous\""), json);
        Assertions.assertFalse(json.contains("\"current\""), json);

        Assertions.assertEquals("Ada", baselineFirstName,
                "the old value is read from the caller's baseline instance");
        Assertions.assertEquals("Grace", view.firstName(),
                "and the caller pairs the two instances itself");
    }

    @Test
    void unchangedFieldsAreAbsentNotExplicitNulls() {
        TrackedPersonSummary view = proxy();

        Assertions.assertEquals("{}", toJson(view), "no change means an empty patch (S4)");
        Assertions.assertFalse(toJson(view).contains("null"),
                "an unchanged field is absent, never an explicit null (S4)");
    }

    @Test
    void aNullChangedValueIsWrittenAsExplicitNull() {
        TrackedPersonSummary view = proxy();
        view.set(PersonSummary_.lastName.ordinal(), null);

        String json = toJson(view);
        Assertions.assertEquals("{\"lastName\":null}", json,
                "a changed field whose value is null is written as an explicit null (S4)");
        Assertions.assertFalse(json.contains("firstName"),
                "and the fields that did not change stay absent (S4)");
    }

    @Test
    void noOpWriteProducesNoPatch() {
        TrackedPersonSummary view = proxy();
        view.set(FIRST_NAME, "Ada"); // the same value

        Assertions.assertEquals("{}", toJson(view), "DEC-012: an equal-value write is a no-op");
    }

    @Test
    void clearChangesEmptiesThePatch() {
        TrackedPersonSummary view = proxy();
        view.set(FIRST_NAME, "Grace");
        Assertions.assertNotEquals("{}", toJson(view));

        view.clearChanges();

        Assertions.assertEquals("{}", toJson(view));
    }
}
