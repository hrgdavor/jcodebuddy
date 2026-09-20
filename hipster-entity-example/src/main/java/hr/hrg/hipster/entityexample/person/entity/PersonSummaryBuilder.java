// {@link hr.hrg.hipster.entityexample.person.entity.PersonSummary} Mutable builder for the PersonSummary view.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

import java.util.List;
import java.util.Map;

/**
 * A mutable copy of a {@link PersonSummary}. Setters exist only for
 * writable fields: a {@code DERIVED}/{@code JOINED} field stays readable and keeps its
 * ordinal, but has no setter (S1).
 */
public final class PersonSummaryBuilder {

    private java.lang.Long id;
    private String firstName;
    private String lastName;
    private Integer age;
    private String departmentName;
    private Map<String,List<Long>> metadata;

    /** Copies every field from a source view. */
    public PersonSummaryBuilder(PersonSummary source) {
        this.id = source.id();
        this.firstName = source.firstName();
        this.lastName = source.lastName();
        this.age = source.age();
        this.departmentName = source.departmentName();
        this.metadata = source.metadata();
    }

    /** An empty builder. */
    public PersonSummaryBuilder() {
    }

    public java.lang.Long id() {
        return id;
    }

    public String firstName() {
        return firstName;
    }

    public String lastName() {
        return lastName;
    }

    public Integer age() {
        return age;
    }

    public String departmentName() {
        return departmentName;
    }

    public Map<String,List<Long>> metadata() {
        return metadata;
    }

    /** Positional read, matching the ordinal contract. */
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

    public PersonSummaryBuilder id(java.lang.Long value) {
        this.id = value;
        return this;
    }

    public PersonSummaryBuilder firstName(String value) {
        this.firstName = value;
        return this;
    }

    public PersonSummaryBuilder lastName(String value) {
        this.lastName = value;
        return this;
    }

    public PersonSummaryBuilder metadata(Map<String,List<Long>> value) {
        this.metadata = value;
        return this;
    }

    /** Positional write. A non-writable ordinal is rejected (S1). */
    public void set(int fieldOrdinal, Object value) {
        switch (fieldOrdinal) {
            case 0 -> this.id = (java.lang.Long) value;
            case 1 -> this.firstName = (String) value;
            case 2 -> this.lastName = (String) value;
            case 5 -> this.metadata = (Map<String,List<Long>>) value;
            default -> throw new UnsupportedOperationException(
                    "Field " + fieldOrdinal + " is not writable on PersonSummary");
        }
    }

    /**
     * Name write, returning the ordinal written or {@code -1} for an unknown field.
     * The low-level builders probe a name and report positionally; the user-facing
     * proxy is the layer that turns {@code -1} into an exception (D5).
     */
    public int set(String field, Object value) {
        switch (field) {
            case "id" -> {
                this.id = (java.lang.Long) value;
                return 0;
            }
            case "firstName" -> {
                this.firstName = (String) value;
                return 1;
            }
            case "lastName" -> {
                this.lastName = (String) value;
                return 2;
            }
            case "metadata" -> {
                this.metadata = (Map<String,List<Long>>) value;
                return 5;
            }
            default -> {
                return -1;
            }
        }
    }

    /** Builds the immutable view. */
    public PersonSummary build() {
        return new PersonSummary.Record(id, firstName, lastName, age, departmentName, metadata);
    }
}
