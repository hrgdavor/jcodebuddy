package hr.hrg.watch2.sample;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Demonstrates Jackson usage inside a hot-reloaded class.
 * Modify the sample data or formatting here and watch the output change.
 */
public class DataProcessor {

    /**
     * Jackson 3 removed the mutating {@code ObjectMapper.enable(...)} configuration methods — a mapper is
     * configured by its builder and is then immutable — so the indent flag is set the way that API
     * requires. The mapper type is unchanged, so nothing else in this sample moves. (The same fix was
     * needed in {@code java-watch-agent}'s {@code AuditManager}, which is how this sample's identical
     * pattern was found: it had never compiled.)
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

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
