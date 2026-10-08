// @generated file hr.hrg.hipster.entity.tooling.ViewJsonGenerator — Direct JSON writer for the PersonDto view (DEC-003/DEC-007).
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.io.Writer;

/**
 * Writes a {@link PersonDto} straight to JSON through the view's own
 * accessors — one compiled field write per field, no reflection and no positional array.
 *
 * <p>This is the read-projection path (DEC-003/DEC-007): a SQL row or a NoSQL document
 * reaches the response without being materialized into an entity first. The field order is
 * the view's ledger order, so the JSON keys and the field enum's ordinals cannot drift
 * apart.</p>
 */
public final class PersonDtoJson {

    /** The JSON member names, in the order {@link #write} emits them. */
    public static final String[] FIELDS = {"id", "firstName", "lastName", "age", "departmentName", "metadata"};

    private PersonDtoJson() {
    }

    /**
     * Writes {@code source} as one JSON object into a generator the <strong>caller</strong>
     * owns: this method never flushes and never closes it, so the result can be one element
     * of an enclosing array or one member of an enclosing object.
     */
    public static void write(JsonGenerator gen, PersonDto source) throws JacksonException {
        gen.writeStartObject();
        gen.writeNumberProperty("id", source.id());
        gen.writeStringProperty("firstName", source.firstName());
        gen.writeStringProperty("lastName", source.lastName());
        gen.writeNumberProperty("age", source.age());
        gen.writeStringProperty("departmentName", source.departmentName());
        gen.writePOJOProperty("metadata", source.metadata());
        gen.writeEndObject();
    }

    /**
     * Writes one complete JSON document for {@code source}, for a caller that has a
     * {@link Writer} rather than a generator. The mapper is supplied rather than held in a
     * static field, so this class carries no JSON configuration of its own: the codec a
     * non-scalar field needs is the <strong>caller's</strong>, taken from the mapper it passes.
     *
     * <p>The generator is closed and flushed here because this method created it; a caller
     * that already owns one calls {@link #write} instead.</p>
     */
    public static void toJson(ObjectMapper mapper, Writer out, PersonDto source) throws JacksonException {
        JsonGenerator gen = mapper.createGenerator(out);
        write(gen, source);
        gen.close();
    }
}
