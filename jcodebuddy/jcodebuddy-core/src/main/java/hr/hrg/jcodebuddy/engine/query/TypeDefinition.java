package hr.hrg.jcodebuddy.engine.query;

import java.util.List;
import java.util.Map;

/**
 * What a generator is told about a type it asks for: its name, its kind, the fields it declares, and its
 * supertypes.
 *
 * <p>A snapshot rather than a handle. A generator receives this and cannot use it to reach the file,
 * the class loader or the rest of the project — so generation stays a function of what it was handed,
 * which is the property that makes generated output reproducible and a generator testable as a pure
 * function.</p>
 *
 * <p>Since 2026-10-03 (step 3.0i) it lives in the engine, and it is the <em>generator-facing</em> answer
 * shape rather than the engine's full one: it carries a type's fields and no relations, so "who extends
 * whom" is a question for {@link MetadataQuery}, not for this record. Growing this seam over the index —
 * relations included — is plan step 3.0d. <strong>Done 2026-10-03:</strong> the record carries
 * {@link #kind} and {@link #relations} now, and {@link IndexTypeResolver} projects them from the index.
 * Both were added for the same test: a generator that cannot tell a record from a class, or cannot see
 * what a type implements, has to read the file to find out — which is the parse this seam exists to make
 * unnecessary. Everything here is still a snapshot of one type, and nothing here can reach further.</p>
 *
 * @param qualifiedName the type's fully qualified name
 * @param simpleName    the type's simple name, so a generator need not take the FQN apart
 * @param kind          {@code class} / {@code interface} / {@code enum} / {@code record} /
 *                      {@code annotation}, as the index records it
 * @param fields        the field names, in declaration order
 * @param fieldTypes    those fields' type names as the source wrote them, keyed by field name — unresolved,
 *                      like every other name this model carries
 * @param relations     the type's supertypes, {@code extends} clause first and then {@code implements}, as
 *                      written; empty for a type that declares none
 */
public record TypeDefinition(
        String qualifiedName,
        String simpleName,
        String kind,
        List<String> fields,
        Map<String, String> fieldTypes,
        List<hr.hrg.jcodebuddy.engine.index.TypeRelation> relations
) {

    public TypeDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
        fieldTypes = fieldTypes == null ? Map.of() : Map.copyOf(fieldTypes);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }
}
