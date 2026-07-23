package hr.hrg.watch2.sample;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.util.List;

/**
 * Demonstrates Jackson usage inside a hot-reloaded class.
 * Modify the sample data or formatting here and watch the output change.
 */
public class DataProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Creates a sample person, serializes it to JSON, and returns the result.
     * Edit the values below to verify hot-reload picked up your changes.
     */
    public static String process() throws Exception {
        PersonData person = new PersonData(
                "Alice Dev",
                30,
                List.of("Java", "Hot-Reload", "ECJ", "Fast Feedback")
        );

        // Serialize to pretty JSON
        String json = MAPPER.writeValueAsString(person);

        // Parse it back (round-trip) to confirm deserialization works too
        PersonData roundTrip = MAPPER.readValue(json, PersonData.class);

        return String.format(
                "Serialized  : %s%nRound-trip  : %s",
                json, roundTrip
        );
    }
}
