package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves the imports a generated field or component declaration needs
 * (plan.dsflash § 8.2/3.6).
 *
 * <p>A declared type reaches the emitters as it was written in the view source — {@code
 * Map<String, List<Long>>} stays text, because keeping the author's own spelling is what makes
 * generated code readable. Emitting that text into a generated file without the corresponding import
 * produces source that does not compile, and there are two ways that happens:</p>
 *
 * <ul>
 *   <li><strong>a JDK type</strong> — {@code List} from {@code java.util}, found by the table below.
 *       This is the case that broke the first {@code BUILDER_TRACKED} output;</li>
 *   <li><strong>any type the view source imported</strong> — {@code import a.hr.Node;} plus
 *       {@code Node head()} reaches the emitter as the bare {@code Node}, and a generated file in
 *       another package that writes it without the import does not compile. That is
 *       {@link Property#typeImports()}, resolved where the accessor was parsed because only there is
 *       the declaring unit's import table in scope.</li>
 * </ul>
 *
 * <p>What is deliberately <em>not</em> here: resolving a simple name from outside the declaring
 * unit. That is the reader's job, and it has two sources — the declaring unit's own import table
 * (which is {@link Property#typeImports()}, resolved where the accessor was parsed because only there
 * is the table in scope) and the pass-wide index of the types the source set declares, which is what
 * resolves a name the declaring <em>package</em> owns without importing it. What is left is a
 * {@code java.lang} type, a type variable, or a type from outside the source set; the compile gate
 * reports those better than a guess would. The general solution is JavaParser's symbol solver, which
 * this offline build does not take on.</p>
 */
final class JdkImportSupport {

    private JdkImportSupport() {
    }

    /**
     * Whether the emitters supply this simple name's import themselves.
     *
     * <p>A caller that resolves a name against the source set must ask this first: a project type that
     * happens to share a simple name with a JDK type the emitters import would otherwise be added
     * beside it, and two single-type imports of the same simple name do not compile.</p>
     */
    static boolean isKnownJdkType(String simpleName) {
        return IMPORTS.containsKey(simpleName);
    }

    private static final Map<String, String> IMPORTS = Map.ofEntries(
            Map.entry("List", "java.util.List"),
            Map.entry("ArrayList", "java.util.ArrayList"),
            Map.entry("LinkedList", "java.util.LinkedList"),
            Map.entry("Set", "java.util.Set"),
            Map.entry("HashSet", "java.util.HashSet"),
            Map.entry("LinkedHashSet", "java.util.LinkedHashSet"),
            Map.entry("TreeSet", "java.util.TreeSet"),
            Map.entry("Map", "java.util.Map"),
            Map.entry("HashMap", "java.util.HashMap"),
            Map.entry("LinkedHashMap", "java.util.LinkedHashMap"),
            Map.entry("TreeMap", "java.util.TreeMap"),
            Map.entry("Collection", "java.util.Collection"),
            Map.entry("Iterable", "java.lang.Iterable"),
            Map.entry("Optional", "java.util.Optional"),
            Map.entry("UUID", "java.util.UUID"),
            Map.entry("BigDecimal", "java.math.BigDecimal"),
            Map.entry("BigInteger", "java.math.BigInteger"),
            Map.entry("Instant", "java.time.Instant"),
            Map.entry("LocalDate", "java.time.LocalDate"),
            Map.entry("LocalDateTime", "java.time.LocalDateTime"),
            Map.entry("OffsetDateTime", "java.time.OffsetDateTime"),
            Map.entry("ZonedDateTime", "java.time.ZonedDateTime"),
            Map.entry("LocalTime", "java.time.LocalTime"),
            Map.entry("Duration", "java.time.Duration"));

    /** The imports needed by a set of declarations, in a stable (alphabetical) order. */
    static List<String> importsFor(List<Property> properties) {
        Set<String> imports = new TreeSet<>();
        for (Property property : properties) {
            // The resolved imports travel with the property, because they depend on the source file the
            // accessor was declared in rather than on the view being emitted.
            imports.addAll(property.typeImports());
            for (String simpleName : simpleNamesIn(property.type())) {
                String importName = IMPORTS.get(simpleName);
                if (importName != null) {
                    imports.add(importName);
                }
            }
        }
        return List.copyOf(imports);
    }

    /** Every capitalized identifier in a declared type, so {@code Map<String, List<Long>>} yields three. */
    private static List<String> simpleNamesIn(String declaredType) {
        List<String> names = new ArrayList<>();
        if (declaredType == null) {
            return names;
        }
        for (String token : declaredType.split("[<>,\\[\\]\\s]+")) {
            String name = token.trim();
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                name = name.substring(dot + 1);
            }
            if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                names.add(name);
            }
        }
        return names;
    }
}
