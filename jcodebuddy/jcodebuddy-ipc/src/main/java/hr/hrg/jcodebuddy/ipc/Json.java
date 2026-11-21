package hr.hrg.jcodebuddy.ipc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The single JSON codec of this module.
 *
 * <p>One mapper, because the envelope, the descriptor and a method result must share one configuration; a
 * second {@code new ObjectMapper()} somewhere would be a second dialect of the same wire format.</p>
 */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static JsonNode read(String text) {
        return MAPPER.readTree(text);
    }
}
