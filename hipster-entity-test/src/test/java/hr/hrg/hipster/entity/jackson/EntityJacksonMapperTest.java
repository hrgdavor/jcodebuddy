package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.core.JsonParser;
import hr.hrg.hipster.entity.person.PersonSummary;
import hr.hrg.hipster.entity.person.PersonSummary_;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityJacksonMapperTest {

    private static PersonSummary personSummary(Long id, String firstName, String lastName,
                                               Integer age, String departmentName) {
        Object[] values = new Object[PersonSummary_.values().length];
        values[PersonSummary_.id.ordinal()]             = id;
        values[PersonSummary_.firstName.ordinal()]      = firstName;
        values[PersonSummary_.lastName.ordinal()]       = lastName;
        values[PersonSummary_.age.ordinal()]            = age;
        values[PersonSummary_.departmentName.ordinal()] = departmentName;
        values[PersonSummary_.metadata.ordinal()]       = null;
        return PersonSummary_.META.create(values);
    }

    @Test
    void serializeAndDeserializePersonSummaryView() throws Exception {
        PersonSummary source = personSummary(42L, "Alice", "Smith", 34, "Engineering");

        java.io.StringWriter writer = new java.io.StringWriter();
        EntityJacksonMapper.toJson(PersonSummary_.META,
                (hr.hrg.hipster.entity.api.ViewReader) source,
                writer);
        String json = writer.toString();

        try (JsonParser parser = new com.fasterxml.jackson.databind.ObjectMapper().createParser(json)) {
            PersonSummary deserialized = EntityJacksonMapper.fromJson(PersonSummary_.META, parser);
            assertEquals(source.firstName(),      deserialized.firstName());
            assertEquals(source.lastName(),       deserialized.lastName());
            assertEquals(source.age(),            deserialized.age());
            assertEquals(source.departmentName(), deserialized.departmentName());
            assertEquals(source.metadata(),       deserialized.metadata());
        }
    }

    @Test
    void serializeAndDeserializeViewHelpers() throws Exception {
        PersonSummary source = personSummary(42L, "Alice", "Smith", 34, "Engineering");

        java.io.StringWriter writer = new java.io.StringWriter();
        EntityJacksonMapper.toJson(PersonSummary_.META,
                (hr.hrg.hipster.entity.api.ViewReader) source,
                writer);
        String json = writer.toString();

        try (JsonParser parser = new com.fasterxml.jackson.databind.ObjectMapper().createParser(json)) {
            PersonSummary deserialized = EntityJacksonMapper.fromJson(PersonSummary_.META, parser);
            assertEquals(source.firstName(),      deserialized.firstName());
            assertEquals(source.lastName(),       deserialized.lastName());
            assertEquals(source.age(),            deserialized.age());
            assertEquals(source.departmentName(), deserialized.departmentName());
            assertEquals(source.metadata(),       deserialized.metadata());
        }
    }

    @Test
    void registerViewModuleIntoObjectMapper() throws Exception {
        PersonSummary source = personSummary(42L, "Alice", "Smith", 34, "Engineering");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        EntityJacksonMapper.registerModule(mapper, PersonSummary_.META);

        String json = mapper.writeValueAsString(source);
        PersonSummary deserialized = mapper.readValue(json, PersonSummary.class);

        assertEquals(source.firstName(),      deserialized.firstName());
        assertEquals(source.lastName(),       deserialized.lastName());
        assertEquals(source.age(),            deserialized.age());
        assertEquals(source.departmentName(), deserialized.departmentName());
        assertEquals(source.metadata(),       deserialized.metadata());
    }

    @Test
    void deserializeThroughBoilerplateDeserializer() throws Exception {
        PersonSummary source = personSummary(42L, "Alice", "Smith", 34, "Engineering");

        java.io.StringWriter writer = new java.io.StringWriter();
        EntityJacksonMapper.toJson(PersonSummary_.META,
                (hr.hrg.hipster.entity.api.ViewReader) source,
                writer);
        String json = writer.toString();

        try (JsonParser parser = new com.fasterxml.jackson.databind.ObjectMapper().createParser(json)) {
            PersonSummary deserialized = new PersonSummaryBoilerplateDeserializer().deserialize(parser);
            assertEquals(source.firstName(),      deserialized.firstName());
            assertEquals(source.lastName(),       deserialized.lastName());
            assertEquals(source.age(),            deserialized.age());
            assertEquals(source.departmentName(), deserialized.departmentName());
            assertEquals(source.metadata(),       deserialized.metadata());
        }
    }
}
