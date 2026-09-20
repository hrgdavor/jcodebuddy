package hr.hrg.hipster.entity.tooling;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * The one place a source type name becomes the literal a generated file needs
 * (plan.dsflash § 8.3/3.7 and the follow-up plan's § 2.1/§ 2.2).
 *
 * <h3>What it replaces</h3>
 * <p>Two private {@code classLiteral} methods had been copied across
 * {@link EntityMetadataGenerator} and {@link FieldBoilerplateGenerator}. Both passed a bare,
 * dot-free identifier straight through, so {@code T} became {@code T.class} — which does not compile.
 * That is notes F-38's finding, and it was invisible because nothing in the tree declared an accessor
 * returning a type variable other than {@code ID}, which one of the two copies special-cased by name.
 * A hand-maintained list of one is the shape that produced F-43, so this class replaces it with a
 * property derived from the source set.</p>
 *
 * <h3>The rule</h3>
 * <ul>
 *   <li>a primitive is itself; a boxed primitive and every {@code java.lang}/{@code java.util} name in
 *       {@link #KNOWN_SIMPLE_NAMES} is expanded to its qualified name;</li>
 *   <li>a name that is already qualified is used as written;</li>
 *   <li>a bare name that <strong>matches a type parameter declared in the source set</strong> is
 *       unresolved: it becomes {@code java.lang.Object} and is reported, because a type parameter has
 *       no class literal and the alternative is emitted source that does not compile;</li>
 *   <li>any other bare name is left as the author spelled it — F-51's resolved decision, because such
 *       a name is either a same-package type or one whose import travels with the property, and
 *       DEC-019 prefers the navigable spelling.</li>
 * </ul>
 *
 * <p>The known limitation, stated rather than hidden: a type genuinely <em>named</em> {@code T} and
 * declared in the view's own package is indistinguishable from a type parameter here, so it is
 * resolved to {@code Object} and reported. That is the safe direction — a reported false positive is
 * recoverable, emitted source that does not compile is not — and it is the trade notes F-38 asks for
 * ("fixing it properly means resolving the type parameter through the view's supertypes").</p>
 */
final class TypeLiterals {

    /** Simple names whose qualified form is known without reading anything. */
    private static final Set<String> KNOWN_SIMPLE_NAMES = Set.of(
            "String", "Long", "Integer", "Short", "Byte", "Boolean", "Double", "Float", "Character",
            "Object", "Number", "Void", "CharSequence", "Comparable", "Iterable", "Class",
            "List", "Set", "Map", "Collection", "Optional", "ArrayList", "HashMap", "HashSet",
            "LinkedHashMap", "LinkedHashSet", "TreeMap", "TreeSet", "Deque", "Queue", "Iterator",
            "BigInteger", "BigDecimal", "UUID", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
            "Duration", "Period", "ZonedDateTime", "OffsetDateTime", "Date");

    /** The qualified form of a known simple name; {@code null} when nothing is known about it. */
    private static final Set<String> PRIMITIVES = Set.of(
            "byte", "short", "int", "long", "float", "double", "boolean", "char", "void");

    private TypeLiterals() {
    }

    /** Whether a name is a primitive type (which has a class literal spelled the same way). */
    static boolean isPrimitive(String name) {
        return PRIMITIVES.contains(name);
    }

    /** The fully-qualified name of a known simple name, or {@code null}. */
    private static String qualify(String simpleName) {
        return switch (simpleName) {
            case "java.lang.String", "String" -> "java.lang.String";
            case "Long" -> "java.lang.Long";
            case "Integer" -> "java.lang.Integer";
            case "Short" -> "java.lang.Short";
            case "Byte" -> "java.lang.Byte";
            case "Boolean" -> "java.lang.Boolean";
            case "Double" -> "java.lang.Double";
            case "Float" -> "java.lang.Float";
            case "Character" -> "java.lang.Character";
            case "Object" -> "java.lang.Object";
            case "Number" -> "java.lang.Number";
            case "Void" -> "java.lang.Void";
            case "CharSequence" -> "java.lang.CharSequence";
            case "Comparable" -> "java.lang.Comparable";
            case "Iterable" -> "java.lang.Iterable";
            case "Class" -> "java.lang.Class";
            case "List" -> "java.util.List";
            case "Set" -> "java.util.Set";
            case "Map" -> "java.util.Map";
            case "Collection" -> "java.util.Collection";
            case "Optional" -> "java.util.Optional";
            case "ArrayList" -> "java.util.ArrayList";
            case "HashMap" -> "java.util.HashMap";
            case "HashSet" -> "java.util.HashSet";
            case "LinkedHashMap" -> "java.util.LinkedHashMap";
            case "LinkedHashSet" -> "java.util.LinkedHashSet";
            case "TreeMap" -> "java.util.TreeMap";
            case "TreeSet" -> "java.util.TreeSet";
            case "Deque" -> "java.util.Deque";
            case "Queue" -> "java.util.Queue";
            case "Iterator" -> "java.util.Iterator";
            case "BigInteger" -> "java.math.BigInteger";
            case "BigDecimal" -> "java.math.BigDecimal";
            case "UUID" -> "java.util.UUID";
            case "LocalDate" -> "java.time.LocalDate";
            case "LocalDateTime" -> "java.time.LocalDateTime";
            case "LocalTime" -> "java.time.LocalTime";
            case "Instant" -> "java.time.Instant";
            case "Duration" -> "java.time.Duration";
            case "Period" -> "java.time.Period";
            case "ZonedDateTime" -> "java.time.ZonedDateTime";
            case "OffsetDateTime" -> "java.time.OffsetDateTime";
            case "Date" -> "java.util.Date";
            default -> null;
        };
    }

    /**
     * How a source type name resolved, so callers can distinguish "known to be {@code Object}" from
     * "no class literal exists".
     *
     * <p>Without this distinction {@link #isUnresolved} would have to compare the emitted literal with
     * {@code java.lang.Object} — and the type actually <em>named</em> {@code Object} produces exactly
     * that literal, so every view whose id is an {@code Object} would be reported as an unresolved type
     * parameter. (Measured: three of the example's views were, the moment the check was added.)</p>
     */
    enum Resolution {
        /** A primitive or a name whose qualified form is known. */
        KNOWN,
        /** A name the author declared: used as written, with its import travelling beside it. */
        DECLARED,
        /** A type parameter declared in this source set: no class literal exists. */
        UNRESOLVED
    }

    /** How {@link #classLiteral} resolves a name, without computing the literal. */
    static Resolution resolutionOf(String typeName, Set<String> typeParameters) {
        String name = typeName.trim();
        if (isPrimitive(name) || name.contains(".") || name.contains("<")) {
            return Resolution.KNOWN;
        }
        if (qualify(name) != null) {
            return Resolution.KNOWN;
        }
        if (typeParameters != null && typeParameters.contains(name)) {
            return Resolution.UNRESOLVED;
        }
        return Resolution.DECLARED;
    }

    /**
     * The literal a {@code .class} expression must use for {@code typeName}.
     *
     * @param typeParameters the type parameter names declared anywhere in the source set; a bare name
     *                       in this set has no class literal
     * @return the qualified (or primitive) name, or {@code java.lang.Object} for an unresolved one
     */
    static String classLiteral(String typeName, Set<String> typeParameters) {
        String name = typeName.trim();
        if (isPrimitive(name)) {
            return name;
        }
        if (name.contains(".") || name.contains("<")) {
            // Qualified, or a generic spelling the caller expands recursively.
            return name;
        }
        String qualified = qualify(name);
        if (qualified != null) {
            return qualified;
        }
        if (typeParameters != null && typeParameters.contains(name)) {
            return "java.lang.Object";
        }
        return name;
    }

    /** Whether {@link #classLiteral} would resolve this name to {@code java.lang.Object}. */
    static boolean isUnresolved(String typeName, Set<String> typeParameters) {
        return resolutionOf(typeName, typeParameters) == Resolution.UNRESOLVED;
    }

    /**
     * Every type parameter name declared by the interfaces, classes and methods of a parsed source set.
     *
     * <p>Derived rather than listed: the generator already walks every compilation unit, so the index
     * costs one pass and cannot drift from the source the way a hand-written set of "names that look
     * like type variables" would.</p>
     */
    static Set<String> typeParameterNames(List<CompilationUnit> units) {
        Set<String> names = new LinkedHashSet<>();
        for (CompilationUnit unit : units) {
            if (unit == null) {
                continue;
            }
            // Type parameters live on interface/class declarations, on records, and on methods. They are
            // collected from all three because a bare name is ambiguous by nature: `T` from a generic
            // method has no class literal either.
            for (ClassOrInterfaceDeclaration decl : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                decl.getTypeParameters().forEach(tp -> names.add(tp.getNameAsString()));
            }
            for (com.github.javaparser.ast.body.RecordDeclaration decl
                    : unit.findAll(com.github.javaparser.ast.body.RecordDeclaration.class)) {
                decl.getTypeParameters().forEach(tp -> names.add(tp.getNameAsString()));
            }
            for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                method.getTypeParameters().forEach(tp -> names.add(tp.getNameAsString()));
            }
        }
        return names;
    }
}
