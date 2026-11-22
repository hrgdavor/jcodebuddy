package hr.hrg.jcodebuddy.tool;

import java.nio.file.Path;
import java.util.Map;

/** One request to a tool: where it should work and any tool-specific parameters. */
public record ToolRequest(Path root, Path file, int line, Map<String, Object> params) {

    public ToolRequest {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public String string(String key, String fallback) {
        Object value = params.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public int number(String key, int fallback) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
