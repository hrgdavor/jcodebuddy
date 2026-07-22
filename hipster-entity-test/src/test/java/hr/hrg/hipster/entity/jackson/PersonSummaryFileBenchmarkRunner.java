package hr.hrg.hipster.entity.jackson;

import org.junit.jupiter.api.Test;
import java.io.IOException;

/**
 * File benchmark test — runs all measurement methods and prints results.
 */
public class PersonSummaryFileBenchmarkRunner {
    @Test
    public void runFileScalarBenchmarks() throws IOException {
        PersonSummaryFileBenchmark bench = new PersonSummaryFileBenchmark();
        bench.setup();

        System.out.println("\n=====================================================");
        System.out.println("File Benchmark: 30,000 person-samples deserialization");
        System.out.println("=====================================================\n");

        double pojo = bench.measure_pojo();
        System.out.printf("Jackson POJO:                 %8.2f ms%n", pojo);

        double entityJackson = bench.measure_entityJackson();
        System.out.printf("EntityJackson (proxy):        %8.2f ms  (%.1f%%)%n", 
            entityJackson, 100.0 * entityJackson / pojo);

        double entityJacksonConcrete = bench.measure_entityJacksonConcrete();
        System.out.printf("EntityJackson (concrete):     %8.2f ms  (%.1f%%)%n", 
            entityJacksonConcrete, 100.0 * entityJacksonConcrete / pojo);

        double boilerplate = bench.measure_boilerplate();
        System.out.printf("Boilerplate (proxy):          %8.2f ms  (%.1f%%)%n", 
            boilerplate, 100.0 * boilerplate / pojo);

        double boilerplateDirect = bench.measure_boilerplateDirect();
        System.out.printf("Boilerplate Direct (proxy):   %8.2f ms  (%.1f%%)%n", 
            boilerplateDirect, 100.0 * boilerplateDirect / pojo);

        double boilerplateConcrete = bench.measure_boilerplateConcrete();
        System.out.printf("Boilerplate (concrete):       %8.2f ms  (%.1f%%)%n", 
            boilerplateConcrete, 100.0 * boilerplateConcrete / pojo);

        double orderedPositional = bench.measure_orderedPositional();
        System.out.printf("Ordered Positional (concrete):%8.2f ms  (%.1f%%)%n", 
            orderedPositional, 100.0 * orderedPositional / pojo);

        System.out.println();
        System.out.printf("Best view variant: %8.2f ms (%.1f%% of POJO)%n", 
            Math.min(entityJacksonConcrete, boilerplateConcrete), 
            100.0 * Math.min(entityJacksonConcrete, boilerplateConcrete) / pojo);
        System.out.println();
    }
}
