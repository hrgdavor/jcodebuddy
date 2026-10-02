package hr.hrg.jcodebuddy.codegen;

import java.util.List;
import java.util.Map;

/**
 * What a generator is told about a type it asks for: its name, and the fields it declares.
 *
 * <p>A snapshot rather than a handle. A generator receives this and cannot use it to reach the file,
 * the class loader or the rest of the project — so generation stays a function of what it was handed,
 * which is the property that makes generated output reproducible and a generator testable as a pure
 * function.
 *
 * @param qualifiedName the type's fully qualified name
 * @param simpleName    the type's simple name, so a generator need not take the FQN apart
 * @param fields        the field names, in declaration order
 * @param fieldTypes    those fields' type names, keyed by field name
 */
public record TypeDefinition(
        String qualifiedName,
        String simpleName,
        List<String> fields,
        Map<String, String> fieldTypes
) {
}
