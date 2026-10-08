package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.generated.GeneratedCodeMarkers;

import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the projection writer for a view — the read half of DEC-003/DEC-007, "projection plus
 * DTO marker for SQL/NoSQL direct JSON output" (plan step 6.5).
 *
 * <h3>What problem this solves</h3>
 * <p>A read projection (a DTO) exists so a query result reaches JSON without building the entity.
 * The generator's other emitters do not serve that: the field enum's {@code META} and the Jackson
 * serializers both walk a <strong>positional array</strong>, so a source that is not a
 * {@code ViewReader} has to be materialized into one first — exactly the materialization a
 * projection is meant to avoid. A SQL row or a Mongo document arrives as values, not as an entity.</p>
 *
 * <h3>Shape</h3>
 * <pre>{@code
 * // @generated file hr.hrg.hipster.entity.tooling.ViewJsonGenerator — Direct JSON writer for the PersonDto view.
 * public final class PersonDtoJson {
 *     public static final String[] FIELDS = {"id", "firstName", …};
 *
 *     public static void write(JsonGenerator gen, PersonDto source) throws JacksonException {
 *         gen.writeStartObject();
 *         gen.writeNumberField("id", source.id());
 *         …
 *         gen.writeEndObject();
 *     }
 * }
 * }</pre>
 *
 * <p>Every field is a <strong>direct method call on the view's own accessor</strong>
 * ({@code source.firstName()}), so the emitted class is ordinary committed Java a stock IDE can
 * navigate from the writer into the view (AGENTS.md § 1, DEC-019). There is no reflection, no
 * {@code Map<String, Method>}, and no schema object built at runtime: the field list is a
 * {@code String[]} literal and the field access is compiled.</p>
 *
 * <h3>Why the writer takes {@code JsonGenerator} rather than a {@code Writer}</h3>
 * <p>A projection is written <em>into</em> a document the caller is already streaming — one element
 * of an array, or a member of an enclosing object — so the caller owns the generator's lifecycle and
 * this class must not close or flush it. {@link #toJson} exists for the standalone case where the
 * caller has a {@code Writer} and wants one complete document, and it is the only method here that
 * creates a generator.</p>
 *
 * <h3>The type mapping, and what it deliberately does not do</h3>
 * <p>A field whose type names a JSON scalar is written with the matching Jackson primitive method;
 * everything else is written with {@code writeObjectField}, which hands the value to the generator's
 * own codec. For the example's {@code Map<String, List<Long>>} that is the only correct answer — the
 * collection's JSON form is a decision the caller's {@code ObjectMapper} owns, and a generator that
 * guessed a shape would be wrong the first time a caller configured one. Emitting
 * {@code writeObjectField} keeps that decision where it belongs and needs no codec at generation
 * time; a generator with no codec configured fails loudly at the first such field rather than
 * silently writing a wrong shape.</p>
 *
 * <h3>Status: opt-in</h3>
 * <p>Like the SQL adapter pair, this emitter runs only when a project asks for it
 * ({@code --dto-projections}), and only for a view that carries the marker
 * ({@code @View(dto = true)}, DEC-003/DEC-007). Neither half alone emits anything: a project that
 * passes the flag but marks no view gets no files, and a project that marks a view but never passes
 * the flag gets no files either.</p>
 */
public final class ViewJsonGenerator {

    private ViewJsonGenerator() {
    }

    /** What the emitter produced, for tests and diagnostics. */
    public record Result(Path file, String className, List<String> fields) {
    }

    /**
     * The class name emitted for a view.
     *
     * <p>Refactor-<strong>sensitive</strong>: it is derived from the view's type name, so a rename
     * refactor must reach it. The {@code {@link}} in the emitted javadoc is what wires that
     * relationship for an IDE (DEC-022).</p>
     */
    public static String jsonClassName(String viewName) {
        return viewName + "Json";
    }

    /**
     * Emits {@code <View>Json} into the view's own package.
     *
     * @param outputRoot   the java source root
     * @param packageName  the package the view lives in
     * @param view         the view
     * @param properties   the view's resolved properties, in the order the document should carry them
     *                     (the ledger order every other emitter uses, so the JSON field order and the
     *                     field-enum order cannot disagree)
     * @param divergences  where a frozen file is reported; {@code null} for a caller that only wants
     *                     the file and does not report
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view,
                                  List<Property> properties, DivergenceReporter divergences)
            throws IOException {
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);

        // A retired field keeps its ordinal in the positional ledger but has no accessor left on the
        // view, so writing it would emit a call that cannot compile. The predicate is the adapter
        // generator's, deliberately: one notion of "not a real field of this view".
        List<Property> writable = properties.stream()
                .filter(property -> !ViewAdapterGenerator.isRetired(property))
                .toList();

        String className = jsonClassName(view.name());
        Path file = packageDir.resolve(className + ".java");
        ViewAdapterGenerator.writeUnlessFrozen(file, source(packageName, view, writable, className),
                divergences);
        return new Result(file, className, writable.stream().map(Property::name).toList());
    }

    private static String source(String packageName, ViewMeta view, List<Property> properties,
                                 String className) {
        StringBuilder sb = new StringBuilder();
        sb.append(GeneratedCodeMarkers.fileHeader(ViewJsonGenerator.class.getName(),
                "Direct JSON writer for the " + view.name() + " view (DEC-003/DEC-007)."));
        sb.append("package ").append(packageName).append(";\n\n");

        for (String importName : JdkImportSupport.importsFor(properties)) {
            sb.append("import ").append(importName).append(";\n");
        }
        sb.append("import tools.jackson.core.JsonGenerator;\n");
        sb.append("import tools.jackson.core.JacksonException;\n");
        sb.append("import tools.jackson.databind.ObjectMapper;\n");
        sb.append("import java.io.Writer;\n\n");

        sb.append("/**\n");
        sb.append(" * Writes a {@link ").append(view.name()).append("} straight to JSON through the view's own\n");
        sb.append(" * accessors — one compiled field write per field, no reflection and no positional array.\n");
        sb.append(" *\n");
        sb.append(" * <p>This is the read-projection path (DEC-003/DEC-007): a SQL row or a NoSQL document\n");
        sb.append(" * reaches the response without being materialized into an entity first. The field order is\n");
        sb.append(" * the view's ledger order, so the JSON keys and the field enum's ordinals cannot drift\n");
        sb.append(" * apart.</p>\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        sb.append("    /** The JSON member names, in the order {@link #write} emits them. */\n");
        sb.append("    public static final String[] FIELDS = {");
        for (int i = 0; i < properties.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(quote(properties.get(i).name()));
        }
        sb.append("};\n\n");

        sb.append("    private ").append(className).append("() {\n    }\n\n");

        sb.append("    /**\n");
        sb.append("     * Writes {@code source} as one JSON object into a generator the <strong>caller</strong>\n");
        sb.append("     * owns: this method never flushes and never closes it, so the result can be one element\n");
        sb.append("     * of an enclosing array or one member of an enclosing object.\n");
        sb.append("     */\n");
        sb.append("    public static void write(JsonGenerator gen, ").append(view.name())
                .append(" source) throws JacksonException {\n");
        sb.append("        gen.writeStartObject();\n");
        for (Property property : properties) {
            sb.append("        ").append(fieldWrite(property)).append('\n');
        }
        sb.append("        gen.writeEndObject();\n");
        sb.append("    }\n\n");

        sb.append("    /**\n");
        sb.append("     * Writes one complete JSON document for {@code source}, for a caller that has a\n");
        sb.append("     * {@link Writer} rather than a generator. The mapper is supplied rather than held in a\n");
        sb.append("     * static field, so this class carries no JSON configuration of its own: the codec a\n");
        sb.append("     * non-scalar field needs is the <strong>caller's</strong>, taken from the mapper it passes.\n");
        sb.append("     *\n");
        sb.append("     * <p>The generator is closed and flushed here because this method created it; a caller\n");
        sb.append("     * that already owns one calls {@link #write} instead.</p>\n");
        sb.append("     */\n");
        sb.append("    public static void toJson(ObjectMapper mapper, Writer out, ").append(view.name())
                .append(" source) throws JacksonException {\n");
        sb.append("        JsonGenerator gen = mapper.createGenerator(out);\n");
        sb.append("        write(gen, source);\n");
        sb.append("        gen.close();\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * One field write, chosen from the declared type.
     *
     * <p>The names are Jackson 3's: what Jackson 2 spelled {@code writeStringField} is
     * {@code writeStringProperty} here, and the composite writers are split into a "start" call the
     * caller closes ({@code writeObjectPropertyStart} / {@code writeArrayPropertyStart}). Neither
     * rename is a local choice — the committed generated source has to compile against the Jackson on
     * this reactor's classpath, so the spelling is read from the jar rather than remembered.</p>
     *
     * <p>The mapping is deliberately small and total: a type it does not recognise goes to
     * {@code writePOJOProperty}, which is correct for any type the caller's codec understands and
     * fails loudly for one it does not. Guessing a JSON shape for a collection would be the one
     * answer that is silently wrong.</p>
     */
    static String fieldWrite(Property property) {
        String member = quote(property.name());
        String read = "source." + property.name() + "()";
        String call = switch (simpleType(property.type())) {
            case "String" -> "writeStringProperty";
            case "boolean", "Boolean" -> "writeBooleanProperty";
            case "byte", "Byte", "short", "Short", "int", "Integer", "long", "Long",
                    "float", "Float", "double", "Double",
                    "BigDecimal", "BigInteger" -> "writeNumberProperty";
            default -> "writePOJOProperty";
        };
        return "gen." + call + "(" + member + ", " + read + ");";
    }

    /**
     * The type's <strong>simple</strong> name, with generics, array brackets and any package prefix
     * removed.
     *
     * <p>Simple rather than fully qualified because the declared type reaches the generator as the
     * author wrote it, and both spellings mean the same JSON scalar: the example's inherited identity
     * field arrives as {@code Long} while a view that spelled it {@code java.lang.Long} would otherwise
     * fall through to the codec. Everything after the last dot is the name the type has, so that is what
     * is matched — never the package, which would make the same field write differently in two
     * packages.</p>
     */
    private static String simpleType(String declaredType) {
        if (declaredType == null) {
            return "Object";
        }
        String type = declaredType.trim();
        int generic = type.indexOf('<');
        String raw = (generic < 0 ? type : type.substring(0, generic)).trim();
        if (raw.endsWith("[]")) {
            // An array is never a JSON scalar: it goes through the codec like any other composite.
            return "Object";
        }
        int dot = raw.lastIndexOf('.');
        return dot < 0 ? raw : raw.substring(dot + 1);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
