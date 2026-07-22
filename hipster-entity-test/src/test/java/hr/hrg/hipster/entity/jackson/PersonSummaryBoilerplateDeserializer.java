package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import hr.hrg.hipster.entity.person.PersonSummary;
import hr.hrg.hipster.entity.person.PersonSummary_;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class PersonSummaryBoilerplateDeserializer {

    // Pre-computed type reference and ObjectReader — created once, never inside the parse loop
    private static final TypeReference<Map<String, List<Long>>> METADATA_TYPE_REF =
            new TypeReference<>() {};
    private static final ObjectReader METADATA_READER =
            new ObjectMapper().readerFor(METADATA_TYPE_REF);

    public PersonSummary deserialize(JsonParser p) throws IOException {
        Long id = null;
        String firstName = null;
        String lastName = null;
        Integer age = null;
        String departmentName = null;
        Object metadata = null;

        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }
        if (token != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("Expected JSON object");
        }

        String name;
        while ((name = p.nextFieldName()) != null) {
            switch (name) {
                case "id" -> { JsonToken t = p.nextToken(); id = t == JsonToken.VALUE_NULL ? null : p.getLongValue(); }
                case "firstName" -> firstName = p.nextTextValue();
                case "lastName" -> lastName = p.nextTextValue();
                case "age" -> { JsonToken t = p.nextToken(); age = t == JsonToken.VALUE_NULL ? null : Integer.valueOf(p.getIntValue()); }
                case "departmentName" -> departmentName = p.nextTextValue();
                case "metadata" -> { JsonToken t = p.nextToken(); metadata = t == JsonToken.VALUE_NULL ? null : METADATA_READER.readValue(p); }
                default -> { p.nextToken(); p.skipChildren(); }
            }
        }

        return PersonSummary_.META.create(new Object[]{id, firstName, lastName, age, departmentName, metadata});
    }
}

// direct-array variant: parse directly into the values array by known field index
final class PersonSummaryBoilerplateDirectArrayDeserializer {

    // Pre-computed type reference and ObjectReader — created once, never inside the parse loop
    private static final TypeReference<Map<String, List<Long>>> METADATA_TYPE_REF =
            new TypeReference<>() {};
    private static final ObjectReader METADATA_READER =
            new ObjectMapper().readerFor(METADATA_TYPE_REF);

    public PersonSummary deserialize(JsonParser p) throws IOException {
        Object[] values = new Object[PersonSummary_.values().length];

        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }
        if (token != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("Expected JSON object");
        }

        String name;
        while ((name = p.nextFieldName()) != null) {
            switch (name) {
                case "id"             -> { JsonToken t = p.nextToken(); values[0] = t == JsonToken.VALUE_NULL ? null : p.getLongValue(); }
                case "firstName"      -> values[1] = p.nextTextValue();
                case "lastName"       -> values[2] = p.nextTextValue();
                case "age"            -> { JsonToken t = p.nextToken(); values[3] = t == JsonToken.VALUE_NULL ? null : Integer.valueOf(p.getIntValue()); }
                case "departmentName" -> values[4] = p.nextTextValue();
                case "metadata"       -> { JsonToken t = p.nextToken(); values[5] = t == JsonToken.VALUE_NULL ? null : METADATA_READER.readValue(p); }
                default -> { p.nextToken(); p.skipChildren(); }
            }
        }

        return PersonSummary_.META.create(values);
    }
}

/**
 * Same as {@link PersonSummaryBoilerplateDirectArrayDeserializer} but eliminates
 * JDK Proxy from the result path by returning {@link PersonSummaryConcreteImpl} directly.
 *
 * <p>Benchmark baseline: establishes the maximum achievable for array-backed views.
 * Difference vs. {@link PersonSummaryBoilerplateDirectArrayDeserializer} measures
 * the pure proxy-creation overhead on every deserialization call.</p>
 */
final class PersonSummaryConcreteBoilerplateDeserializer {

    private static final TypeReference<Map<String, List<Long>>> METADATA_TYPE_REF =
            new TypeReference<>() {};
    private static final ObjectReader METADATA_READER =
            new ObjectMapper().readerFor(METADATA_TYPE_REF);

    public PersonSummary deserialize(JsonParser p) throws IOException {
        Object[] values = new Object[PersonSummary_.values().length];

        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }
        if (token != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("Expected JSON object");
        }

        String name;
        while ((name = p.nextFieldName()) != null) {
            switch (name) {
                case "id"             -> { JsonToken t = p.nextToken(); values[0] = t == JsonToken.VALUE_NULL ? null : p.getLongValue(); }
                case "firstName"      -> values[1] = p.nextTextValue();
                case "lastName"       -> values[2] = p.nextTextValue();
                case "age"            -> { JsonToken t = p.nextToken(); values[3] = t == JsonToken.VALUE_NULL ? null : Integer.valueOf(p.getIntValue()); }
                case "departmentName" -> values[4] = p.nextTextValue();
                case "metadata"       -> { JsonToken t = p.nextToken(); values[5] = t == JsonToken.VALUE_NULL ? null : METADATA_READER.readValue(p); }
                default -> { p.nextToken(); p.skipChildren(); }
            }
        }

        return new PersonSummaryConcreteImpl(values);  // no proxy, no InvocationHandler
    }
}

/**
 * Positional (ordered) deserializer — assumes the JSON fields always arrive in enum ordinal order:
 * {@code id, firstName, lastName, age, departmentName, metadata}.
 *
 * <p>This is the performance ceiling for array-backed view deserialization when field order is
 * guaranteed (e.g. JSON produced by {@link hr.hrg.hipster.entity.jackson.EntityJacksonViewSerializer},
 * which always iterates fields in {@link PersonSummary_} ordinal order).
 *
 * <p>Eliminates ALL field-name dispatch overhead: no switch, no hashCode, no equals, no
 * linear scan. The parser simply advances through tokens positionally — identical to
 * {@code parseOnly_viewJson} cost plus minimal object-creation overhead.
 *
 * <p>If field order changes, values will be silently misassigned. Use the boilerplate
 * switch deserializers for unknown-order JSON.</p>
 */
final class PersonSummaryOrderedPositionalDeserializer {

    private static final TypeReference<Map<String, List<Long>>> METADATA_TYPE_REF =
            new TypeReference<>() {};
    private static final ObjectReader METADATA_READER =
            new ObjectMapper().readerFor(METADATA_TYPE_REF);

    /**
     * Deserializes assuming fixed field order: id, firstName, lastName, age, departmentName, metadata.
     * Each call: START_OBJECT check + 6 × (skip-fieldname + read-value) + END_OBJECT. No dispatch.
     */
    public PersonSummary deserialize(JsonParser p) throws IOException {
        if (p.currentToken() == null) p.nextToken();
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("Expected JSON object");
        }

        // Field 0: id (Long)
        p.nextToken(); // FIELD_NAME "id" — advance past name without reading it
        JsonToken t = p.nextToken(); // VALUE
        Long id = t == JsonToken.VALUE_NULL ? null : p.getLongValue();

        // Field 1: firstName (String) — nextTextValue() advances past FIELD_NAME then reads VALUE
        p.nextToken(); // FIELD_NAME "firstName"
        String firstName = p.nextTextValue(); // → VALUE_STRING or null

        // Field 2: lastName (String)
        p.nextToken(); // FIELD_NAME "lastName"
        String lastName = p.nextTextValue();

        // Field 3: age (Integer)
        p.nextToken(); // FIELD_NAME "age"
        t = p.nextToken(); // VALUE
        Integer age = t == JsonToken.VALUE_NULL ? null : Integer.valueOf(p.getIntValue());

        // Field 4: departmentName (String)
        p.nextToken(); // FIELD_NAME "departmentName"
        String departmentName = p.nextTextValue();

        // Field 5: metadata (Map<String, List<Long>>)
        p.nextToken(); // FIELD_NAME "metadata"
        t = p.nextToken(); // VALUE (null or START_OBJECT)
        Object metadata = t == JsonToken.VALUE_NULL ? null : METADATA_READER.readValue(p);

        // Advance to END_OBJECT of the outer record so callers in a streaming array loop
        // see END_OBJECT as currentToken; the caller's p.nextToken() then moves to the
        // next START_OBJECT or END_ARRAY correctly.
        // (Switch-based deserializers do this implicitly via nextFieldName() returning null.)
        p.nextToken();

        return new PersonSummaryConcreteImpl(
                new Object[]{id, firstName, lastName, age, departmentName, metadata});
    }
}

