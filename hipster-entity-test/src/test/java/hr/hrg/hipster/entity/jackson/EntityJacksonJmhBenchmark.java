package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.ViewReader;
import hr.hrg.hipster.entity.person.PersonSummary;
import hr.hrg.hipster.entity.person.PersonSummary_;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class EntityJacksonJmhBenchmark {

    @State(Scope.Thread)
    public static class BenchmarkState {
        ObjectMapper defaultMapper;
        ObjectMapper moduleMapper;
        PersonSummary view;
        ViewReader viewReader;
        PersonDto baselineBean;
        String jsonFromView;
        byte[] jsonFromViewBytes;
        String jsonFromBean;
        byte[] jsonFromBeanBytes;
        EntityJacksonViewSerializer<PersonSummary, PersonSummary_> entityViewSerializer;
        PersonSummaryGeneratedSerializer personSummarySerializer;

        // Pre-built deserializers — shared across all benchmark calls (no per-call allocation)
        EntityJacksonViewDeserializer<PersonSummary, PersonSummary_> viewDeserializer;
        EntityJacksonViewDeserializer<PersonSummary, PersonSummary_> concreteViewDeserializer;
        PersonSummaryBoilerplateDeserializer boilerplateDeserializer;
        PersonSummaryBoilerplateDirectArrayDeserializer boilerplateDirectDeserializer;
        PersonSummaryConcreteBoilerplateDeserializer concreteBoilerplateDeserializer;
        PersonSummaryOrderedPositionalDeserializer orderedPositionalDeserializer;

        // Pre-filled values array for object-creation-only benchmarks
        Object[] prefilledValues;

        @Setup
        public void setup() throws IOException {
            defaultMapper = new ObjectMapper();
            moduleMapper = new ObjectMapper();
            EntityJacksonMapper.registerModule(moduleMapper, PersonSummary_.META);

            Object[] values = new Object[PersonSummary_.values().length];
            values[PersonSummary_.id.ordinal()] = 42L;
            values[PersonSummary_.firstName.ordinal()] = "Alice";
            values[PersonSummary_.lastName.ordinal()] = "Smith";
            values[PersonSummary_.age.ordinal()] = 34;
            values[PersonSummary_.departmentName.ordinal()] = "Engineering";
            values[PersonSummary_.metadata.ordinal()] = null;
            view = PersonSummary_.META.create(values);

            baselineBean = new PersonDto(42L, "Alice", "Smith", 34, "Engineering", null);

            StringWriter w = new StringWriter();
            viewReader = (ViewReader) view;
            EntityJacksonMapper.toJson(PersonSummary_.META, viewReader, w);
            jsonFromView = w.toString();
            jsonFromViewBytes = jsonFromView.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            jsonFromBean = defaultMapper.writeValueAsString(baselineBean);
            jsonFromBeanBytes = jsonFromBean.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            entityViewSerializer = new EntityJacksonViewSerializer<>(PersonSummary_.META);
            personSummarySerializer = new PersonSummaryGeneratedSerializer();

            // Build ViewMeta backed by concrete impl (no proxy) for concrete-path benchmarks
            ViewMeta<PersonSummary, PersonSummary_> concreteMeta = new DefaultViewMeta<>(
                    PersonSummary.class,
                    PersonSummary_.class,
                    PersonSummary_::forName,
                    PersonSummaryConcreteImpl::new
            );

            // Pre-build all deserializers once — hot path pays zero construction cost
            viewDeserializer         = new EntityJacksonViewDeserializer<>(PersonSummary_.META);
            concreteViewDeserializer = new EntityJacksonViewDeserializer<>(concreteMeta);
            boilerplateDeserializer      = new PersonSummaryBoilerplateDeserializer();
            boilerplateDirectDeserializer = new PersonSummaryBoilerplateDirectArrayDeserializer();
            concreteBoilerplateDeserializer = new PersonSummaryConcreteBoilerplateDeserializer();
            orderedPositionalDeserializer   = new PersonSummaryOrderedPositionalDeserializer();

            // Pre-filled array for object-creation-only benchmarks
            prefilledValues = new Object[PersonSummary_.values().length];
            prefilledValues[PersonSummary_.id.ordinal()] = 42L;
            prefilledValues[PersonSummary_.firstName.ordinal()] = "Alice";
            prefilledValues[PersonSummary_.lastName.ordinal()] = "Smith";
            prefilledValues[PersonSummary_.age.ordinal()] = 34;
            prefilledValues[PersonSummary_.departmentName.ordinal()] = "Engineering";
            prefilledValues[PersonSummary_.metadata.ordinal()] = null;
        }
    }

    // =========================================================================
    // Serialization benchmarks
    // =========================================================================

    @Benchmark
    public String serializeViewThroughEntityJackson(BenchmarkState state) throws IOException {
        StringWriter w = new StringWriter();
        EntityJacksonMapper.toJson(PersonSummary_.META, state.viewReader, w);
        return w.toString();
    }

    @Benchmark
    public String serializeViewThroughEntityJacksonViewSerializer(BenchmarkState state) throws IOException {
        StringWriter w = new StringWriter();
        JsonGenerator gen = state.defaultMapper.getFactory().createGenerator(w);
        state.entityViewSerializer.serialize(state.viewReader, gen);
        gen.close();
        return w.toString();
    }

    @Benchmark
    public String serializeViewThroughPersonSummaryGeneratedSerializer(BenchmarkState state) throws IOException {
        StringWriter w = new StringWriter();
        JsonGenerator gen = state.defaultMapper.getFactory().createGenerator(w);
        state.personSummarySerializer.serialize(state.viewReader, gen);
        gen.close();
        return w.toString();
    }

    @Benchmark
    public String serializePojoThroughDefaultJackson(BenchmarkState state) throws IOException {
        return state.defaultMapper.writeValueAsString(state.baselineBean);
    }

    // =========================================================================
    // Deserialization — full pipeline (parse + allocate result object)
    // =========================================================================

    /**
     * POJO baseline: Jackson BeanDeserializer → record constructor.
     * Uses the same JSON source (jsonFromView) and same createParser API path as view benchmarks,
     * so the only variable is the deserialization logic (BeanDeserializer vs our custom code).
     */
    @Benchmark
    public PersonDto deserializePojoThroughDefaultJackson(BenchmarkState state) throws IOException {
        JsonParser parser = state.defaultMapper.createParser(state.jsonFromView);
        return state.defaultMapper.readValue(parser, PersonDto.class);
    }

    /**
     * Generic view deserializer (pre-built, cached readers + interned names), proxy result.
     * Fix: was creating new EntityJacksonViewDeserializer() + ValueReader[] per call.
     */
    @Benchmark
    public PersonSummary deserializeViewThroughEntityJackson(BenchmarkState state) throws IOException {
        JsonParser parser = state.defaultMapper.createParser(state.jsonFromViewBytes);
        return state.viewDeserializer.deserialize(parser);
    }

    /**
     * Boilerplate switch deserializer (pre-built), proxy result.
     * Fix: was creating new PersonSummaryBoilerplateDeserializer() per call.
     */
    @Benchmark
    public PersonSummary deserializeViewThroughPersonSummaryBoilerplateDeserializer(BenchmarkState state) throws IOException {
        JsonParser parser = state.defaultMapper.createParser(state.jsonFromViewBytes);
        return state.boilerplateDeserializer.deserialize(parser);
    }

    /** Boilerplate direct-array switch (pre-built), proxy result. */
    @Benchmark
    public PersonSummary deserializeViewThroughPersonSummaryBoilerplateDirectArrayDeserializer(BenchmarkState state) throws IOException {
        JsonParser parser = state.defaultMapper.createParser(state.jsonFromViewBytes);
        return state.boilerplateDirectDeserializer.deserialize(parser);
    }

    /**
     * NEW: Boilerplate switch deserializer + concrete impl result (no JDK Proxy).
     * Theoretical ceiling for array-backed view deserialization.
     */
    @Benchmark
    public PersonSummary deserializeViewThroughPersonSummaryBoilerplateConcreteImpl(BenchmarkState state) throws IOException {
        JsonParser parser = state.defaultMapper.createParser(state.jsonFromViewBytes);
        return state.concreteBoilerplateDeserializer.deserialize(parser);
    }

    /**
     * NEW: Positional (no-dispatch) deserializer + concrete impl result.
     * Assumes JSON fields arrive in enum ordinal order (guaranteed by our serializer).
     * Eliminates all field-name matching: no switch, no hashCode, no lookup.
     * Performance ceiling approaches parse-only cost + minimal object-creation overhead.
     */
    @Benchmark
    public PersonSummary deserializeViewThroughPersonSummaryOrderedPositional(BenchmarkState state) throws IOException {
        JsonParser parser = state.defaultMapper.createParser(state.jsonFromViewBytes);
        return state.orderedPositionalDeserializer.deserialize(parser);
    }

    /**
     * NEW: Generic view deserializer + concrete impl result (no JDK Proxy).
     * Combines cached reader dispatch with direct allocation.
     */
    @Benchmark
    public PersonSummary deserializeViewThroughEntityJacksonConcrete(BenchmarkState state) throws IOException {
        JsonParser parser = state.defaultMapper.createParser(state.jsonFromViewBytes);
        return state.concreteViewDeserializer.deserialize(parser);
    }

    // =========================================================================
    // Isolation: object-creation cost only (parse already done — values pre-filled)
    // =========================================================================

    /**
     * How expensive is meta.create() — i.e. JDK proxy + EntityReadArray construction?
     * Divide the gap between this and createOnly_concrete to see proxy overhead per call.
     */
    @Benchmark
    public PersonSummary createOnly_proxy(BenchmarkState state) {
        return PersonSummary_.META.create(state.prefilledValues);
    }

    /**
     * How expensive is a concrete array-backed construction?
     * {@code new PersonSummaryConcreteImpl(values)} — one allocation, no proxy.
     */
    @Benchmark
    public PersonSummary createOnly_concrete(BenchmarkState state) {
        return new PersonSummaryConcreteImpl(state.prefilledValues);
    }

    /** Jackson baseline: how cheap is {@code new PersonDto(...)}, i.e. record constructor? */
    @Benchmark
    public Object createOnly_record(BenchmarkState state) {
        return new PersonDto(
                state.baselineBean.id(), state.baselineBean.firstName(),
                state.baselineBean.lastName(), state.baselineBean.age(),
                state.baselineBean.departmentName(), state.baselineBean.metadata());
    }

    // =========================================================================
    // Isolation: parse-only cost (no result-object alloc, consume tokens to prevent DCE)
    // =========================================================================

    /**
     * Parse the view JSON fully but return a checksum rather than constructing a view.
     * Shows irreducible tokenization cost — how much time is purely in the JsonParser.
     */
    @Benchmark
    public long parseOnly_viewJson(BenchmarkState state) throws IOException {
        return consumeTokens(state.defaultMapper.createParser(state.jsonFromViewBytes));
    }

    /** Same parse-only measurement over the POJO JSON (same structure, for symmetry). */
    @Benchmark
    public long parseOnly_pojoJson(BenchmarkState state) throws IOException {
        return consumeTokens(state.defaultMapper.createParser(state.jsonFromBeanBytes));
    }

    /** Consumes all tokens and returns a simple checksum to prevent dead-code elimination. */
    private static long consumeTokens(JsonParser p) throws IOException {
        long checksum = 0L;
        JsonToken t;
        while ((t = p.nextToken()) != null) {
            if (t == JsonToken.VALUE_NUMBER_INT) {
                checksum += p.getLongValue();
            } else if (t == JsonToken.VALUE_STRING) {
                checksum += p.getText().length();
            }
        }
        p.close();
        return checksum;
    }

    // =========================================================================
    // Boilerplate serializer (unchanged)
    // =========================================================================

    public static final class PersonSummaryGeneratedSerializer {
        public void serialize(ViewReader src, JsonGenerator gen) throws IOException {
            gen.writeStartObject();

            gen.writeFieldName("id");
            Object id = src.get(PersonSummary_.id.ordinal());
            if (id == null) gen.writeNull(); else gen.writeNumber((Long) id);

            gen.writeFieldName("firstName");
            Object firstName = src.get(PersonSummary_.firstName.ordinal());
            if (firstName == null) gen.writeNull(); else gen.writeString((String) firstName);

            gen.writeFieldName("lastName");
            Object lastName = src.get(PersonSummary_.lastName.ordinal());
            if (lastName == null) gen.writeNull(); else gen.writeString((String) lastName);

            gen.writeFieldName("age");
            Object age = src.get(PersonSummary_.age.ordinal());
            if (age == null) gen.writeNull(); else gen.writeNumber((Integer) age);

            gen.writeFieldName("departmentName");
            Object departmentName = src.get(PersonSummary_.departmentName.ordinal());
            if (departmentName == null) gen.writeNull(); else gen.writeString((String) departmentName);

            gen.writeFieldName("metadata");
            Object metadata = src.get(PersonSummary_.metadata.ordinal());
            if (metadata == null) gen.writeNull(); else gen.writeObject(metadata);

            gen.writeEndObject();
        }
    }

    public static record PersonDto(
            Long id,
            String firstName,
            String lastName,
            Integer age,
            String departmentName,
            Object metadata
    ) {
    }
}

