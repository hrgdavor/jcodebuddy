package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import hr.hrg.hipster.entity.api.DefaultViewMeta;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.person.PersonSummary;
import hr.hrg.hipster.entity.person.PersonSummary_;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * File-scan deserialization measurement tool: deserialize every record in
 * {@code person-samples.json} (30 000 PersonSummary objects) from a byte[]
 * and return the average age.
 *
 * <p>Uses {@code byte[]} input so Jackson creates a {@code UTF8StreamJsonParser}
 * (vectorised byte scanning) — the same path as reading from a file or network
 * stream. This is NOT part of the JMH benchmark suite; it is a standalone
 * utility for measuring file-scan deserialization performance on realistic
 * data volume (30 k records, 3 MB JSON) with GC pressure from sustained allocations.</p>
 *
 * <p>Manual invocation example:</p>
 * <pre>
 *   PersonSummaryFileBenchmark tool = new PersonSummaryFileBenchmark();
 *   tool.setup();
 *   System.out.println("POJO:                     " + tool.measure_pojo() + " ms");
 *   System.out.println("EntityJackson:            " + tool.measure_entityJackson() + " ms");
 *   System.out.println("Boilerplate (concrete):   " + tool.measure_boilerplateConcrete() + " ms");
 * </pre>
 */
public class PersonSummaryFileBenchmark {

    /** POJO baseline — mirrors PersonSummary fields, no framework machinery. */
    public record PersonDto(Long id, String firstName, String lastName,
                            Integer age, String departmentName,
                            Map<String, List<Long>> metadata) {}

    // =========================================================================
    // Instance state
    // =========================================================================

    private byte[] jsonBytes;
    private ObjectMapper mapper;
    private EntityJacksonViewDeserializer<PersonSummary, PersonSummary_> viewDeserializer;
    private EntityJacksonViewDeserializer<PersonSummary, PersonSummary_> concreteViewDeserializer;
    private PersonSummaryBoilerplateDeserializer boilerplateDeserializer;
    private PersonSummaryBoilerplateDirectArrayDeserializer boilerplateDirectDeserializer;
    private PersonSummaryConcreteBoilerplateDeserializer concreteBoilerplateDeserializer;
    private PersonSummaryOrderedPositionalDeserializer orderedPositionalDeserializer;

    /**
     * Initialize: load the sample file and construct all deserializer instances.
     * Call this once before running multiple measurements.
     */
    public void setup() throws IOException {
        mapper = new ObjectMapper();

        try (InputStream is = getClass().getResourceAsStream("/data/person-samples.json")) {
            if (is == null) throw new IllegalStateException(
                    "Resource /data/person-samples.json not found on classpath. " +
                    "Run scripts/gen-sample-data.js first.");
            jsonBytes = is.readAllBytes();
        }

        ViewMeta<PersonSummary, PersonSummary_> concreteMeta = new DefaultViewMeta<>(
                PersonSummary.class,
                PersonSummary_.class,
                PersonSummary_::forName,
                PersonSummaryConcreteImpl::new
        );

        viewDeserializer             = new EntityJacksonViewDeserializer<>(PersonSummary_.META);
        concreteViewDeserializer     = new EntityJacksonViewDeserializer<>(concreteMeta);
        boilerplateDeserializer      = new PersonSummaryBoilerplateDeserializer();
        boilerplateDirectDeserializer = new PersonSummaryBoilerplateDirectArrayDeserializer();
        concreteBoilerplateDeserializer = new PersonSummaryConcreteBoilerplateDeserializer();
        orderedPositionalDeserializer   = new PersonSummaryOrderedPositionalDeserializer();
    }

    // =========================================================================
    // Measurement methods (not @Benchmark — run() semantics only)
    // =========================================================================

    /**
     * Jackson BeanDeserializer scanning all 30 000 records.
     * @return milliseconds to deserialize entire file
     */
    public double measure_pojo() throws IOException {
        long start = System.nanoTime();
        long ageSum = 0; int count = 0;
        try (JsonParser p = mapper.createParser(jsonBytes)) {
            p.nextToken(); // START_ARRAY
            while (p.nextToken() != JsonToken.END_ARRAY) {
                PersonDto dto = mapper.readValue(p, PersonDto.class);
                if (dto.age() != null) ageSum += dto.age();
                count++;
            }
        }
        long elapsedNs = System.nanoTime() - start;
        return elapsedNs / 1_000_000.0;
    }

    /** Generic cached-reader deserializer, result backed by JDK Proxy. */
    public double measure_entityJackson() throws IOException {
        return scanWith(viewDeserializer);
    }

    /** Generic cached-reader deserializer, result backed by concrete impl. */
    public double measure_entityJacksonConcrete() throws IOException {
        return scanWith(concreteViewDeserializer);
    }

    /** Boilerplate switch, result via META.create() (Proxy). */
    public double measure_boilerplate() throws IOException {
        return scanWith(boilerplateDeserializer);
    }

    /** Boilerplate switch, direct array, result via META.create() (Proxy). */
    public double measure_boilerplateDirect() throws IOException {
        return scanWith(boilerplateDirectDeserializer);
    }

    /** Boilerplate switch, concrete impl result (no JDK Proxy). */
    public double measure_boilerplateConcrete() throws IOException {
        return scanWith(concreteBoilerplateDeserializer);
    }

    /** Positional (no dispatch) deserializer. */
    public double measure_orderedPositional() throws IOException {
        return scanWith(orderedPositionalDeserializer);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private interface ViewDeserializer {
        PersonSummary deserialize(JsonParser p) throws IOException;
    }

    private double scanWith(ViewDeserializer d) throws IOException {
        long start = System.nanoTime();
        long ageSum = 0; int count = 0;
        try (JsonParser p = mapper.createParser(jsonBytes)) {
            p.nextToken(); // START_ARRAY
            while (p.nextToken() != JsonToken.END_ARRAY) {
                PersonSummary ps = d.deserialize(p);
                if (ps.age() != null) ageSum += ps.age();
                count++;
            }
        }
        long elapsedNs = System.nanoTime() - start;
        return elapsedNs / 1_000_000.0;
    }

    private double scanWith(EntityJacksonViewDeserializer<PersonSummary, PersonSummary_> d) throws IOException {
        return scanWith((ViewDeserializer) d::deserialize);
    }

    private double scanWith(PersonSummaryBoilerplateDeserializer d) throws IOException {
        return scanWith((ViewDeserializer) d::deserialize);
    }

    private double scanWith(PersonSummaryBoilerplateDirectArrayDeserializer d) throws IOException {
        return scanWith((ViewDeserializer) d::deserialize);
    }

    private double scanWith(PersonSummaryConcreteBoilerplateDeserializer d) throws IOException {
        return scanWith((ViewDeserializer) d::deserialize);
    }

    private double scanWith(PersonSummaryOrderedPositionalDeserializer d) throws IOException {
        return scanWith((ViewDeserializer) d::deserialize);
    }
}
