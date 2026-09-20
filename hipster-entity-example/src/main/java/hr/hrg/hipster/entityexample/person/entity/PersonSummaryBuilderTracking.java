// {@link hr.hrg.hipster.entityexample.person.entity.PersonSummary} Tracking builder for the PersonSummary view.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.EEnumSetBuilder;
import hr.hrg.hipster.entity.core.EEnumSetBuilder64;
import hr.hrg.hipster.entity.core.ViewChangeTracking;
import java.util.List;
import java.util.Map;

/**
 * A mutable copy of a {@link PersonSummary} that records which fields were
 * written with a value different from the one they held.
 *
 * <p>It keeps no old value: the value a field held before the write belongs to the
 * baseline instance this builder was constructed from, which stays the caller's object.
 * A consumer that wants an old -> new comparison is handed both and compares them.</p>
 *
 * <p>{@code changes()} is an immutable snapshot and {@code changesBuilder()} is the live
 * mutable set; both are views over the single {@code mf} field, so they can never
 * disagree.</p>
 */
public class PersonSummaryBuilderTracking implements ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>> {

    /** The one piece of tracking state. Both accessors derive from it. */
    final EEnumSetBuilder64<PersonSummary_> mf = new EEnumSetBuilder64<>(PersonSummary_.values());

    java.lang.Long id;
    String firstName;
    String lastName;
    Integer age;
    String departmentName;
    Map<String,List<Long>> metadata;

    /** Builds the tracking state from a baseline view (§ 8.6/3.16). */
    public PersonSummaryBuilderTracking(PersonSummary source) {
        this.id = source.id();
        this.firstName = source.firstName();
        this.lastName = source.lastName();
        this.age = source.age();
        this.departmentName = source.departmentName();
        this.metadata = source.metadata();
    }

    public java.lang.Long id() { return id; }
    public String firstName() { return firstName; }
    public String lastName() { return lastName; }
    public Integer age() { return age; }
    public String departmentName() { return departmentName; }
    public Map<String,List<Long>> metadata() { return metadata; }

    public Object get(int fieldOrdinal) {
        return switch (fieldOrdinal) {
            case 0 -> id;
            case 1 -> firstName;
            case 2 -> lastName;
            case 3 -> age;
            case 4 -> departmentName;
            case 5 -> metadata;
            default -> null;
        };
    }

    /** Fluent setter that marks the change when the value actually differs. */
    public PersonSummaryBuilderTracking id(java.lang.Long value) {
        if (!java.util.Objects.equals(this.id, value)) {
            this.id = value;
            mf.addOrdinal(0);
        }
        return this;
    }

    /** Fluent setter that marks the change when the value actually differs. */
    public PersonSummaryBuilderTracking firstName(String value) {
        if (!java.util.Objects.equals(this.firstName, value)) {
            this.firstName = value;
            mf.addOrdinal(1);
        }
        return this;
    }

    /** Fluent setter that marks the change when the value actually differs. */
    public PersonSummaryBuilderTracking lastName(String value) {
        if (!java.util.Objects.equals(this.lastName, value)) {
            this.lastName = value;
            mf.addOrdinal(2);
        }
        return this;
    }

    /** Fluent setter that marks the change when the value actually differs. */
    public PersonSummaryBuilderTracking metadata(Map<String,List<Long>> value) {
        if (!java.util.Objects.equals(this.metadata, value)) {
            this.metadata = value;
            mf.addOrdinal(5);
        }
        return this;
    }

    /** Positional mutator. Only writable ordinals are accepted (S1). */
    public void set(int fieldOrdinal, Object value) {
        switch (fieldOrdinal) {
            case 0 -> { if (!java.util.Objects.equals(this.id, value)) { this.id = (java.lang.Long) value; mf.addOrdinal(0); } }
            case 1 -> { if (!java.util.Objects.equals(this.firstName, value)) { this.firstName = (String) value; mf.addOrdinal(1); } }
            case 2 -> { if (!java.util.Objects.equals(this.lastName, value)) { this.lastName = (String) value; mf.addOrdinal(2); } }
            case 5 -> { if (!java.util.Objects.equals(this.metadata, value)) { this.metadata = (Map<String,List<Long>>) value; mf.addOrdinal(5); } }
            default -> throw new UnsupportedOperationException(
                    "Field " + fieldOrdinal + " is not writable on PersonSummary");
        }
    }

    /** Name mutator. Returns -1 for an unknown field, matching the array contract (D5). */
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

    /** Immutable snapshot — allocates when non-empty, so never use it on a hot path. */
    @Override
    public EEnumSet<PersonSummary_> changes() {
        return mf.toImmutable();
    }

    /** The live, mutable change set — the same state {@link #changes()} snapshots. */
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
        return field == null ? null : get(field.ordinal());
    }

    /**
     * A user-authored variant of the tracking builder. The generator never emits it and never
     * deletes it: it is recognised as a nested type the generator does not own and carried through
     * regeneration verbatim (plan.dsflash 4.5/G3 and 8.7/3.18, DEC-020).
     *
     * <p>Why it adds nothing: the plain {@link PersonSummaryBuilderTracking} already compares before
     * it marks — each setter is "compare the value the field holds with the one being assigned, then
     * mark only when they differ" — so a strict variant that re-checked equality would be
     * belt-and-braces rather than a correctness fix. Saying so here is the point of the class
     * existing at all: it stops a later consistency pass from deciding the un-strict builder is a bug
     * and wiring {@code EEnumSetBuilder64.Strict} in behind the developer's back.</p>
     */
    public static final class TrackingStrict extends PersonSummaryBuilderTracking {

        public TrackingStrict(PersonSummary source) {
            super(source);
        }
    }
}
