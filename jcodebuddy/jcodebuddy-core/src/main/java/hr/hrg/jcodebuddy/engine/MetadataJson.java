package hr.hrg.jcodebuddy.engine;

import tools.jackson.databind.ObjectMapper;

/**
 * The JSON vocabulary of the metadata files: one escaping rule and one mapper.
 *
 * <p>It lives in the engine because both sides need it and neither may own it alone. The class index
 * ({@code .jcodebuddy/index/classes.json}, DEC-029) writes its table by hand — the shape is chosen for
 * reading, so it is emitted as text rather than from a model — and the pass that reads those documents
 * needs the same escaping. Two spellings of "how a string reaches JSON" is how a table and the documents
 * that describe it drift apart on the first value that needs escaping, which is exactly what the pass's
 * own method javadoc said before the engine existed (plan step 3.0f-2).</p>
 */
public final class MetadataJson {

    /** The one mapper for metadata documents. Jackson 3 (`tools.jackson`), the line this reactor manages. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MetadataJson() {
    }

    /** The one mapper for metadata documents. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Escapes a value for the hand-rolled JSON writer. {@code null} stays {@code null}. */
    public static String escape(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}