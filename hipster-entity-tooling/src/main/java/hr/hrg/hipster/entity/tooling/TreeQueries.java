package hr.hrg.hipster.entity.tooling;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The read-only queries the tooling asks of a parsed tree.
 *
 * <h3>Why this exists</h3>
 * <p>Two things are true of every consumer of {@link SourceReader} at once, and neither is obvious
 * from the JavaParser code being replaced:</p>
 *
 * <ol>
 *   <li><strong>The replacement for {@code cu.findAll(X.class)} is a visitor, not a call.</strong>
 *       Every JavaParser {@code findAll} in this module becomes the same fifteen-line
 *       {@link JavaIsoVisitor} boilerplate. Scattering that across a dozen rules would bury the rules
 *       in traversal code.</li>
 *   <li><strong>One OpenRewrite class covers five Java kinds.</strong>
 *       {@link J.ClassDeclaration#getKind()} returns a {@code Kind.Type} whose values are exactly
 *       {@code Class, Enum, Interface, Annotation, Record} — there is no {@code J.InterfaceDeclaration}
 *       and no {@code J.EnumDeclaration}. So a port that mechanically maps "find interfaces" to
 *       "find {@code ClassDeclaration}" silently starts matching records and enums too, which is a
 *       wrong-answer bug that still compiles. Each kind gets a named method here so the choice is
 *       explicit at the call site rather than implied by a type.</li>
 * </ol>
 *
 * <h3>What this deliberately does not do</h3>
 * <p>Nothing here <em>builds</em> a tree. That is not an oversight and not a placeholder: the
 * generator port needs to be free to use {@code JavaTemplate} for some sites and immutable
 * {@code withXxx(...)} construction for others, and a helper that owned construction would force the
 * choice. A query returns plain {@link J} subtypes, which both approaches accept, so the emission
 * strategy stays open until the generators are ported.</p>
 *
 * <h3>What this does not abstract</h3>
 * <p>{@code findAll} was <em>type</em>-based; the LST is <em>kind</em>-based and its accessors differ
 * per node ({@code J.VariableDeclarations} for one field or several, {@code J.MethodDeclaration} for
 * constructors as well as methods, {@code J.EnumValueSet} for enum constants). Those differences are
 * real and are exactly what the migration guide calls out, so they stay visible at the call site
 * instead of being hidden behind a convenience method that would have to guess.</p>
 *
 * <p>Public rather than package-private because the validation rules live in a subpackage and are
 * the heaviest users of it.</p>
 */
public final class TreeQueries {

    /** A context for pure traversal. Nothing is attributed or written, so nothing is configured. */
    private static final ExecutionContext VISIT_CONTEXT = new InMemoryExecutionContext();

    private TreeQueries() {
    }

    /**
     * The declared package name, or {@code ""} for the default package.
     *
     * <p>Replaces JavaParser's
     * {@code cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("")}. The LST holds the
     * package declaration as a nullable node rather than an {@code Optional}, and a missing
     * declaration is the default package rather than an error.</p>
     */
    public static String packageName(J.CompilationUnit cu) {
        if (cu == null) {
            return "";
        }
        J.Package declaration = cu.getPackageDeclaration();
        if (declaration == null) {
            return "";
        }
        // The declaration's own name is the authoritative spelling: it is what the
        // file says, which is what every diagnostic in this module quotes.
        return declaration.getExpression() instanceof J.Identifier identifier
                ? identifier.getSimpleName()
                : declaration.getExpression().toString();
    }

    /**
     * Every type declaration in the file, at any depth, in source order.
     *
     * <p>Includes nested types. The old JavaParser code that read {@code cu.getTypes()} saw only
     * <em>top-level</em> types, so a caller that wants that narrower set must filter —
     * {@link #topLevelTypes} does it — and a caller that wants everything gets the wider answer. The
     * distinction is made explicit here rather than preserved by accident.</p>
     */
    public static List<J.ClassDeclaration> typeDeclarations(J.CompilationUnit cu) {
        List<J.ClassDeclaration> found = new ArrayList<>();
        if (cu == null) {
            return found;
        }
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration type, ExecutionContext ctx) {
                found.add(type);
                return super.visitClassDeclaration(type, ctx);
            }
        }.visit(cu, VISIT_CONTEXT);
        return found;
    }

    /**
     * The type declarations directly under the compilation unit — the set JavaParser's
     * {@code getTypes()} returned.
     *
     * <p>Read from {@link J.CompilationUnit#getClasses()} rather than by filtering
     * {@link #typeDeclarations}, because nesting is what is being excluded and the unit already knows
     * which declarations are its own children.</p>
     */
    public static List<J.ClassDeclaration> topLevelTypes(J.CompilationUnit cu) {
        if (cu == null) {
            return List.of();
        }
        return List.copyOf(cu.getClasses());
    }

    /**
     * Every type-parameter name declared by the types and methods of a source set.
     *
     * <p>Replaces the three {@code findAll} walks JavaParser needed — one for
     * {@code ClassOrInterfaceDeclaration}, one for {@code RecordDeclaration}, one for
     * {@code MethodDeclaration} — with two: the type declarations (whose kind test already covers
     * records, so the separate record walk disappears) and the methods inside them.</p>
     *
     * <p>Collected from types <em>and</em> methods because a bare name is ambiguous by nature:
     * {@code T} declared by a generic method has no class literal either, and a caller resolving
     * {@code T.class} must treat both the same way.</p>
     */
    public static Set<String> typeParameterNames(List<J.CompilationUnit> units) {
        Set<String> names = new LinkedHashSet<>();
        for (J.CompilationUnit unit : units) {
            if (unit == null) {
                continue;
            }
            for (J.ClassDeclaration declaration : typeDeclarations(unit)) {
                // `getTypeParameters()` is NULL on a declaration with none, not an empty list — the
                // same shape as `getImplements()` and, unlike JavaParser's `NodeList`, a bare
                // for-each throws. The migration guide calls this class of trap out; this is one of
                // them, and it failed 191 tests before the guard was added.
                List<J.TypeParameter> typeParameters = declaration.getTypeParameters();
                if (typeParameters != null) {
                    for (J.TypeParameter parameter : typeParameters) {
                        // The parameter's name is a `TypeTree`, not a String: JavaParser's
                        // `getNameAsString()` has no direct equivalent, and `toString()` would carry
                        // the bounds into the name (`T extends Foo`).
                        names.add(simpleTypeName(parameter.getName()));
                    }
                }
                for (J.MethodDeclaration method : findAll(declaration, J.MethodDeclaration.class)) {
                    List<J.TypeParameter> methodParameters = method.getTypeParameters();
                    if (methodParameters != null) {
                        for (J.TypeParameter parameter : methodParameters) {
                            names.add(simpleTypeName(parameter.getName()));
                        }
                    }
                }
            }
        }
        return names;
    }

    /** Type declarations of one kind, at any depth. */
    public static List<J.ClassDeclaration> typesOfKind(J.CompilationUnit cu, J.ClassDeclaration.Kind.Type kind) {
        return filter(typeDeclarations(cu), declaration -> declaration.getKind() == kind);
    }

    /**
     * Interfaces, at any depth.
     *
     * <p>Note the shape this replaces. JavaParser expressed "is an interface" as
     * {@code ClassOrInterfaceDeclaration.isInterface()}, so a search for interfaces was a search for
     * one class plus a predicate. Here the class is {@link J.ClassDeclaration} — which also covers
     * records, enums and annotations — and the predicate is the only thing that makes it an
     * interface. Dropping the predicate still compiles and starts returning records.</p>
     */
    public static List<J.ClassDeclaration> interfaces(J.CompilationUnit cu) {
        return typesOfKind(cu, J.ClassDeclaration.Kind.Type.Interface);
    }

    /** Classes (not interfaces, enums, annotations or records), at any depth. */
    public static List<J.ClassDeclaration> classes(J.CompilationUnit cu) {
        return typesOfKind(cu, J.ClassDeclaration.Kind.Type.Class);
    }

    /** Records, at any depth. */
    public static List<J.ClassDeclaration> records(J.CompilationUnit cu) {
        return typesOfKind(cu, J.ClassDeclaration.Kind.Type.Record);
    }

    /** Enums, at any depth. */
    public static List<J.ClassDeclaration> enums(J.CompilationUnit cu) {
        return typesOfKind(cu, J.ClassDeclaration.Kind.Type.Enum);
    }

    /** Annotation declarations, at any depth. */
    public static List<J.ClassDeclaration> annotations(J.CompilationUnit cu) {
        return typesOfKind(cu, J.ClassDeclaration.Kind.Type.Annotation);
    }

    /**
     * Every node of one type in the tree, at any depth, in source order.
     *
     * <p>The general replacement for JavaParser's {@code cu.findAll(X.class)}. It returns every
     * matching node, including a type declaration nested inside another. JavaParser's {@code findAll}
     * had the same reach, so a ported call site keeps its meaning; a site that needs to exclude the
     * top level must say so with {@link #topLevelTypes} or a predicate.</p>
     *
     * <p>Type attribution is <em>not</em> available from this traversal: a node's {@code getType()} is
     * null unless the parser was given a classpath that contains it. The previous JavaParser code had
     * the same limitation through its symbol solver, and no rule in this module depends on
     * attribution.</p>
     *
     * @param tree the root to search (inclusive of itself)
     * @param type the node type to collect
     */
    public static <T extends J> List<T> findAll(J tree, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (tree == null) {
            return found;
        }
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visit(@Nullable Tree visited, ExecutionContext ctx) {
                // `visit(Tree, ...)` is the hook every node's dispatch passes through,
                // so this sees each node exactly once and at every depth. Returning
                // `super.visit` is what makes the traversal continue into children.
                if (type.isInstance(visited)) {
                    found.add(type.cast(visited));
                }
                return super.visit(visited, ctx);
            }
        }.visit(tree, VISIT_CONTEXT);
        return found;
    }

    /** The subset of {@code nodes} matching {@code predicate}. */
    public static <T> List<T> filter(List<T> nodes, Predicate<T> predicate) {
        List<T> kept = new ArrayList<>(nodes.size());
        for (T node : nodes) {
            if (predicate.test(node)) {
                kept.add(node);
            }
        }
        return kept;
    }

    /**
     * Whether {@code declaration} is a type of the given kind.
     *
     * <p>Exists so a call site can express the kind test in one place. Prefer the named methods above;
     * this is for the cases where the kind comes from a variable.</p>
     */
    public static boolean isKind(J.ClassDeclaration declaration, J.ClassDeclaration.Kind.Type kind) {
        return declaration != null && declaration.getKind() == kind;
    }

    // ------------------------------------------------------------------ members ---

    /**
     * The methods a declaration <strong>directly</strong> declares, constructors excluded.
     *
     * <p>Two exclusions, and the first is the one that is easy to miss. {@code J.MethodDeclaration}
     * covers constructors as well as methods — there is no separate constructor type — so a walk over
     * method declarations returns both; JavaParser's {@code getMethods()} excluded constructors, and
     * every caller here is asking about accessors.</p>
     *
     * <p>The second is depth. JavaParser's {@code getMethods()} returned the declaration's own members
     * and nothing else, whereas {@link #findAll} reaches every depth — so the obvious translation
     * ({@code findAll(declaration, J.MethodDeclaration.class)}) also collects the accessors of a
     * <em>nested</em> type. Every view in the example declares a nested record, so that difference is
     * not theoretical: a view's property list would gain the nested record's components, and the
     * generated field enum, the builder and the metadata JSON would all describe fields the view never
     * declared. Reading the body's own statements is what keeps the meaning identical to
     * {@code getMethods()}.</p>
     */
    public static List<J.MethodDeclaration> methodsOf(J.ClassDeclaration declaration) {
        List<J.MethodDeclaration> methods = new ArrayList<>();
        if (declaration == null || declaration.getBody() == null) {
            return methods;
        }
        for (Statement statement : declaration.getBody().getStatements()) {
            if (statement instanceof J.MethodDeclaration method && !method.isConstructor()) {
                methods.add(method);
            }
        }
        return methods;
    }

    /**
     * The names of the no-argument methods on {@code declaration}, in declaration order.
     *
     * <p>The shape a view's accessor list takes: {@code decl.getMethods().stream().filter(m ->
     * m.getParameters().isEmpty() …)}. Note the LST quirk the migration guide records — an empty
     * parameter list is held as a single {@link J.Empty} placeholder, <em>not</em> an empty list — so
     * "no parameters" is not {@code isEmpty()}.</p>
     */
    public static List<String> noArgMethodNames(J.ClassDeclaration declaration) {
        List<String> names = new ArrayList<>();
        for (J.MethodDeclaration method : methodsOf(declaration)) {
            if (hasNoParameters(method) && !isDefaultMethod(method)) {
                names.add(method.getSimpleName());
            }
        }
        return names;
    }

    /**
     * Whether a method is declared {@code default}.
     *
     * <p>The LST models modifiers as an ordered {@code List<J.Modifier>}, so there is no
     * {@code isDefault()} accessor — the old JavaParser call has to become a keyword test. Kept as a
     * named method because "is this default" appears in several rules and a bare
     * {@code hasModifier(...)} at each site is easy to get subtly wrong.</p>
     */
    public static boolean isDefaultMethod(J.MethodDeclaration method) {
        return method != null && method.hasModifier(J.Modifier.Type.Default);
    }

    /** Whether a method is declared {@code static}. */
    public static boolean isStaticMethod(J.MethodDeclaration method) {
        return method != null && method.hasModifier(J.Modifier.Type.Static);
    }

    /**
     * Whether a method returns {@code void}.
     *
     * <p>Replaces JavaParser's {@code method.getType().isVoidType()}, and the replacement is not the
     * obvious one: a {@code void} method's return type is <strong>not</strong> absent. Measured, it is a
     * {@link J.Primitive} whose type is {@code JavaType.Primitive.Void} and which prints as
     * {@code void}. So a port that tested for a missing return-type expression would find every
     * {@code void} method to have one — and an accessor filter that excludes {@code void} methods would
     * stop excluding them, turning every mutation method into a field.</p>
     */
    public static boolean isVoidReturn(J.MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        org.openrewrite.java.tree.TypeTree returnType = method.getReturnTypeExpression();
        return returnType instanceof J.Primitive primitive
                && primitive.getType() == org.openrewrite.java.tree.JavaType.Primitive.Void;
    }

    /**
     * Whether a method declares no parameters.
     *
     * <p>Both spellings occur in the LST for the empty list: absent, an empty list, or a single
     * {@link J.Empty} placeholder. Treating any of them as "has a parameter" is how a port silently
     * stops matching no-arg accessors.</p>
     */
    public static boolean hasNoParameters(J.MethodDeclaration method) {
        List<Statement> parameters = method.getParameters();
        if (parameters == null || parameters.isEmpty()) {
            return true;
        }
        return parameters.size() == 1 && parameters.get(0) instanceof J.Empty;
    }

    /**
     * Whether a method declares exactly one parameter.
     *
     * <p>Exists because the obvious translation — {@code getParameters().size() == 1} — is wrong in a
     * way that reads as plausible. An empty parameter list is a single {@link J.Empty} placeholder, so
     * a no-argument method also reports {@code size() == 1}; a check meant to recognise a
     * one-argument setter would match the no-argument accessor beside it and quietly stop reporting
     * anything. Exactly one parameter therefore means one parameter that is <em>not</em> the
     * placeholder.</p>
     */
    public static boolean hasOneParameter(J.MethodDeclaration method) {
        List<Statement> parameters = method.getParameters();
        if (parameters == null || parameters.size() != 1) {
            return false;
        }
        return !(parameters.get(0) instanceof J.Empty);
    }

    /**
     * Every supertype <strong>as the LST node</strong>, {@code extends} first then {@code implements}.
     *
     * <p>This is the method to reach for whenever a port needs more than the supertype's name, and it
     * exists because reading {@link J.ClassDeclaration#getExtends()} directly is the single most
     * expensive trap in this migration. An <em>interface's</em> {@code extends} clause is held in
     * {@code getImplements()} and {@code getExtends()} is {@code null} for it — measured, not inferred:
     * for {@code interface PersonEntity extends EntityBase<Long>}, {@code getExtends()} is
     * {@code null} and {@code getImplements()} is {@code [EntityBase<Long>]}. A port that reads
     * {@code getExtends()} alone therefore finds <em>no</em> supertype for the commonest declaration in
     * this module and returns a plausible-looking "not an entity marker" for every marker interface —
     * which is how a single missed clause emptied an entire generation pass and failed 154 tests with
     * no exception anywhere.</p>
     *
     * <p>Reading the node rather than the name matters because a type <em>argument</em> is not a name:
     * {@code EntityBase<Long>}'s {@code Long} is reachable only from the
     * {@link J.ParameterizedType} itself. Returning the trees keeps that available without every
     * caller having to remember the two-clause rule.</p>
     */
    public static List<TypeTree> supertypeTypes(J.ClassDeclaration declaration) {
        List<TypeTree> types = new ArrayList<>();
        if (declaration == null) {
            return types;
        }
        TypeTree extending = declaration.getExtends();
        if (extending != null) {
            types.add(extending);
        }
        // Null when the clause is absent, not an empty list.
        List<TypeTree> implemented = declaration.getImplements();
        if (implemented != null) {
            types.addAll(implemented);
        }
        return types;
    }

    /** The supertypes named in an {@code extends} clause, as simple names. */
    public static List<String> supertypeNames(J.ClassDeclaration declaration) {
        List<String> names = new ArrayList<>();
        for (TypeTree type : supertypeTypes(declaration)) {
            names.add(simpleTypeName(type));
        }
        return names;
    }

    /**
     * Every supertype <strong>as source text, type arguments included</strong>.
     *
     * <p>What JavaParser's {@code ClassOrInterfaceType.asString()} returned, and the reason it cannot be
     * {@link #supertypeNames}: the type <em>argument</em> is the whole point for
     * {@code extends Identifiable<Long>} and {@code extends EntityBase<Long>}, and a simple name has
     * already thrown it away. A view that reaches its id type through {@code Identifiable<Long>} — the
     * markerless case, where the view's own clause is the only knowable answer — silently falls back to
     * {@code Object} when the argument is missing, which is a record that does not implement the
     * interface it claims to.</p>
     */
    public static List<String> supertypeTexts(J.ClassDeclaration declaration) {
        List<String> texts = new ArrayList<>();
        for (TypeTree type : supertypeTypes(declaration)) {
            texts.add(typeText(type));
        }
        return texts;
    }

    /**
     * A type as the source spelled it, rendered the way JavaParser's {@code asString()} did.
     *
     * <p>Two printer differences had to be neutralised, and the first is why this exists at all:
     * JavaParser's pretty printer separates type arguments with a bare comma
     * ({@code Map<String,List<Long>>}) while OpenRewrite's printer puts a space after it
     * ({@code Map<String, List<Long>>}). Emitted source is compared byte-for-byte against the committed
     * example, and — worse — the generator recognises <em>its own previous output</em> by that text, so a
     * printer difference reads as "the user edited this member" and reports a divergence for every
     * generic member in the tree. The comma is normalised here, once, rather than at each emitter.</p>
     */
    public static String typeText(J tree) {
        if (tree == null) {
            return "";
        }
        return withoutTypeArgumentSpaces(tree.toString().trim());
    }

    /**
     * The text of an expression as JavaParser's {@code toString()} produced it.
     *
     * <p>Needed because an LST literal does <strong>not</strong> round-trip: {@code J.Literal.toString()}
     * returns the literal's <em>value</em>, so a string literal loses its quotes. Carrying that through
     * an annotation's argument text emitted {@code @Pattern(regexp = [A-Z]{2})} — source that does not
     * compile, from an annotation the author wrote correctly. A value source is preferred when the
     * parser recorded one (it does whenever the canonical rendering differs from the source), and the
     * canonical rendering is reproduced otherwise.</p>
     */
    public static String expressionText(J tree) {
        if (tree == null) {
            return "";
        }
        if (tree instanceof J.Literal literal) {
            return literalText(literal);
        }
        return tree.toString().trim();
    }

    /** One literal rendered as source: quoted for {@code String} and {@code char}, bare otherwise. */
    private static String literalText(J.Literal literal) {
        String source = literal.getValueSource();
        if (source != null && !source.isEmpty()) {
            return source;
        }
        Object value = literal.getValue();
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            return sb.append('"').toString();
        }
        if (value instanceof Character c) {
            return "'" + (c == '\'' ? "\\'" : c == '\\' ? "\\\\" : String.valueOf(c)) + "'";
        }
        return String.valueOf(value);
    }

    /**
     * {@code Map<String, List<Long>>} to {@code Map<String,List<Long>>}: the comma inside a type
     * argument list loses its following space, everywhere but at depth zero.
     */
    private static String withoutTypeArgumentSpaces(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth > 0) {
                out.append(c);
                int next = i + 1;
                while (next < text.length() && text.charAt(next) == ' ') {
                    next++;
                }
                i = next - 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The simple name of a type reference, whatever shape it takes.
     *
     * <p>A JavaParser {@code ClassOrInterfaceType} could carry its own type arguments in one node.
     * The LST splits that: {@code EntityBase} is a {@link J.Identifier} and {@code EntityBase<T>} is a
     * {@link J.ParameterizedType} wrapping one. A port that read only {@link J.Identifier} would
     * silently miss every generic supertype, so both shapes are unwrapped here.</p>
     */
    public static String simpleTypeName(J tree) {
        if (tree == null) {
            return "";
        }
        if (tree instanceof J.ParameterizedType parameterized) {
            return simpleTypeName(parameterized.getClazz());
        }
        if (tree instanceof J.Identifier identifier) {
            return identifier.getSimpleName();
        }
        if (tree instanceof J.FieldAccess access) {
            // A qualified type: take the last segment, which is the declared name.
            return access.getName().getSimpleName();
        }
        // Last resort: the tree's own printed text, stripped of a package prefix and
        // any generic argument list. Only reached for shapes this module does not
        // currently produce, so it is a fallback rather than a primary path.
        String text = tree.toString().trim();
        int generic = text.indexOf('<');
        if (generic >= 0) {
            text = text.substring(0, generic);
        }
        int dot = text.lastIndexOf('.');
        return (dot >= 0 ? text.substring(dot + 1) : text).trim();
    }

    /** Whether {@code declaration} carries the named annotation, ignoring any package prefix. */
    public static boolean hasAnnotation(J.ClassDeclaration declaration, String simpleName) {
        return annotationNamed(declaration, simpleName) != null;
    }

    /** The named annotation on {@code declaration}, or {@code null}. */
    public static J.Annotation annotationNamed(J.ClassDeclaration declaration, String simpleName) {
        if (declaration == null) {
            return null;
        }
        for (J.Annotation annotation : declaration.getLeadingAnnotations()) {
            if (annotationName(annotation).equals(simpleName)) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * The named annotation on a method, or {@code null}.
     *
     * <p>A separate overload rather than a shared {@code J}-typed one: a method is not a type, and the
     * two carry their annotations on different accessors ({@code getLeadingAnnotations()} on both, but
     * there is no common supertype that offers it). Keeping them separate means a caller cannot
     * accidentally pass a type where a member is meant.</p>
     */
    public static J.Annotation annotationNamed(J.MethodDeclaration method, String simpleName) {
        if (method == null) {
            return null;
        }
        for (J.Annotation annotation : method.getLeadingAnnotations()) {
            if (annotationName(annotation).equals(simpleName)) {
                return annotation;
            }
        }
        return null;
    }

    /** Whether a method carries the named annotation, ignoring any package prefix. */
    public static boolean hasAnnotation(J.MethodDeclaration method, String simpleName) {
        return annotationNamed(method, simpleName) != null;
    }

    /**
     * An annotation's simple name.
     *
     * <p>JavaParser's {@code getAnnotationByName("View")} matched on the simple name, so a source
     * that writes {@code @hr.hrg.hipster.entity.api.View} must still match. The LST holds the
     * annotation type as a {@link J.Identifier} or a {@link J.FieldAccess}, and only the last segment
     * is the name being looked for.</p>
     */
    public static String annotationName(J.Annotation annotation) {
        if (annotation == null) {
            return "";
        }
        return simpleTypeName(annotation.getAnnotationType());
    }

    /**
     * One attribute of an annotation, or {@code null} when it is not present.
     *
     * <p>Two JavaParser-to-LST differences are absorbed here, because both are silent:</p>
     * <ul>
     *   <li>JavaParser exposed {@code MemberValuePair} with a {@code Name}; the LST uses
     *       {@link J.Assignment} whose {@code getVariable()} is the name. A marker annotation has no
     *       arguments at all — {@code getArguments()} is {@code null}, not empty.</li>
     *   <li><strong>A single unnamed argument is normalised to the name {@code value}.</strong>
     *       JavaParser left {@code @Foo(Bar.class)} nameless; the LST reports it as an assignment to
     *       {@code value}. So a lookup for {@code value} succeeds where the JavaParser code would
     *       have had to special-case the form.</li>
     * </ul>
     *
     * <p>An array initialiser is returned as the array node itself, so a caller reading a
     * class-valued list can branch on {@link J.NewArray} exactly as it branched on JavaParser's
     * {@code ArrayInitializerExpr}.</p>
     *
     * @return the attribute value expression, or {@code null} when the annotation does not set it
     */
    public static Expression annotationArg(J.Annotation annotation, String name) {
        if (annotation == null || annotation.getArguments() == null) {
            return null;
        }
        for (Expression argument : annotation.getArguments()) {
            // `@View()` parses to a single J.Empty argument, which names nothing.
            if (argument instanceof J.Empty) {
                continue;
            }
            if (argument instanceof J.Assignment assignment) {
                if (assignmentName(assignment).equals(name)) {
                    return assignment.getAssignment();
                }
                continue;
            }
            // A bare argument on an annotation that takes exactly one member: the
            // source wrote `@Foo(x)` rather than `@Foo(value = x)`.
            if ("value".equals(name)) {
                return argument;
            }
        }
        return null;
    }

    /** The member name an annotation argument assigns to. */
    public static String assignmentName(J.Assignment assignment) {
        Expression variable = assignment.getVariable();
        if (variable instanceof J.Identifier identifier) {
            return identifier.getSimpleName();
        }
        return variable.toString().trim();
    }

    /** Whether an annotation declares any attribute at all (`{@code @View}` and `{@code @View()}` do not). */
    public static boolean hasAnnotationArguments(J.Annotation annotation) {
        if (annotation == null || annotation.getArguments() == null) {
            return false;
        }
        for (Expression argument : annotation.getArguments()) {
            if (!(argument instanceof J.Empty)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------- ancestry and position ---

    /**
     * One type declaration together with the chain of types enclosing it.
     *
     * @param declaration the declaration itself
     * @param enclosing   the enclosing type declarations, <strong>outermost first</strong>
     */
    public record EnclosedType(J.ClassDeclaration declaration, List<J.ClassDeclaration> enclosing) {

        public EnclosedType {
            enclosing = List.copyOf(enclosing);
        }
    }

    /**
     * Every type declaration in the file with its enclosing chain, in source order.
     *
     * <p>Replaces JavaParser's {@code Node.getParentNode()} walk, which has no LST equivalent: an LST
     * node does not know its parent. The cursor does — {@link JavaIsoVisitor#getCursor()} exposes the
     * path from the root to the node currently being visited — so the ancestry is captured
     * <em>during</em> traversal rather than reconstructed afterwards.</p>
     *
     * <p>This is what an FQN needs: DEC-029 keys the class index by package-qualified name with member
     * types joined by {@code .}, and the package plus the enclosing chain is all that name requires.
     * No symbol solver is involved, and none is available at generation time.</p>
     */
    public static List<EnclosedType> typesWithEnclosing(J.CompilationUnit cu) {
        List<EnclosedType> found = new ArrayList<>();
        if (cu == null) {
            return found;
        }
        // The ancestry is tracked as an explicit stack on the way down rather than read out of
        // `getCursor().getPath()`. The cursor path's order is not documented and was measured to be
        // neither root-first nor leaf-first, so reading it produced a chain that matched the *wrong*
        // declaration — a nested type silently resolved to its parent's line. A stack the visitor
        // pushes and pops itself has one correct order by construction.
        new JavaIsoVisitor<ExecutionContext>() {

            private final java.util.Deque<J.ClassDeclaration> stack = new java.util.ArrayDeque<>();

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration type, ExecutionContext ctx) {
                found.add(new EnclosedType(type, new ArrayList<>(stack)));
                stack.push(type);
                try {
                    return super.visitClassDeclaration(type, ctx);
                } finally {
                    stack.pop();
                }
            }
        }.visit(cu, VISIT_CONTEXT);
        return found;
    }

    /**
     * The 1-based line a declaration's <strong>name</strong> sits on, or {@code -1}.
     *
     * <p>JavaParser answered this with {@code getName().getBegin().line}. The LST exposes no positions,
     * so the line comes from javac's line map over the same text
     * ({@link JavaSyntaxCheck#typeNameLines}), and the declaration is matched to its entry by
     * {@code (simpleName, enclosingChain)}.</p>
     *
     * <p>Both halves of that key are required, and the matching itself lives in {@link #positionOf};
     * see there for why. Note that this is the <em>name</em>'s line and not the declaration's — an
     * annotated declaration starts on its annotation, and {@link #declarationLineOf} is the query that
     * answers that.</p>
     *
     * <p>Returns {@code -1} when nothing matches, which DEC-029 already reads as "line unknown".</p>
     *
     * @param source the exact text the tree was parsed from; the positions are meaningless without it
     */
    public static int lineOf(J.ClassDeclaration declaration, String source) {
        return lineOfChained(declaration, List.of(), source);
    }

    /**
     * {@link #lineOf(J.ClassDeclaration, String)} for a nested declaration.
     *
     * @param enclosingNames the enclosing types' simple names, <strong>outermost first and excluding
     *                       the declaration itself</strong> — the same shape
     *                       {@link JavaSyntaxCheck.TypePosition#enclosingNames()} reports
     */
    public static int lineOfChained(J.ClassDeclaration declaration, List<String> enclosingNames, String source) {
        JavaSyntaxCheck.TypePosition position = positionOf(declaration, enclosingNames, source);
        return position == null ? -1 : position.nameLine();
    }

    /**
     * The 1-based line a declaration <strong>begins</strong> on — its annotations included — or
     * {@code -1}.
     *
     * <p>This is what JavaParser's {@code getBegin().line} answered, and it is <em>not</em>
     * {@link #lineOf}: for {@code @View(...)} on line 8 above {@code public interface PersonSummary}
     * on line 9 the two disagree, and both answers are recorded somewhere. A metadata record's view
     * line is the declaration's (8), which is the behaviour the ported pass has to reproduce; a
     * class-index or location row wants the line a reader's editor will scroll to for the member
     * itself, which is the name's. Keeping the two as separate queries is what stops one of them from
     * quietly taking the other's meaning.</p>
     */
    public static int declarationLineOf(J.ClassDeclaration declaration, String source) {
        return declarationLineOfChained(declaration, List.of(), source);
    }

    /** {@link #declarationLineOf(J.ClassDeclaration, String)} for a nested declaration. */
    public static int declarationLineOfChained(J.ClassDeclaration declaration, List<String> enclosingNames,
                                               String source) {
        JavaSyntaxCheck.TypePosition position = positionOf(declaration, enclosingNames, source);
        return position == null ? -1 : position.declarationLine();
    }

    /**
     * The javac position entry for a declaration, matched by {@code (simpleName, enclosingChain)}.
     *
     * <p>Both halves of that key are required, which is the whole reason this was wrong three times
     * before it was right. For {@code interface Shape { record Circle {} }} the javac entries are
     * {@code (Shape, [])} and {@code (Circle, [Shape])}. Matching on the chain alone would compare the
     * outer declaration's key — {@code (Shape, [Shape])} in the form that appends the declaration's own
     * name — against {@code (Circle, [Shape])} and <em>hit</em>, returning the inner record's line for
     * the outer interface. A confidently wrong line is worse than none, so the name is compared
     * explicitly.</p>
     *
     * @return the matching entry, or {@code null} when nothing matches
     */
    private static JavaSyntaxCheck.TypePosition positionOf(J.ClassDeclaration declaration,
                                                           List<String> enclosingNames, String source) {
        if (declaration == null || source == null || source.isBlank()) {
            return null;
        }
        String simpleName = declaration.getSimpleName();
        List<String> enclosing = enclosingNames == null ? List.of() : enclosingNames;
        for (JavaSyntaxCheck.TypePosition position : JavaSyntaxCheck.typeNameLines(source)) {
            if (position.simpleName().equals(simpleName)
                    && position.enclosingNames().equals(enclosing)) {
                return position;
            }
        }
        return null;
    }

    /**
     * The line a method's <strong>name</strong> sits on, or {@code -1}.
     *
     * <p>Replaces JavaParser's {@code method.getName().getBegin().line}. The distinction from the
     * declaration's own line is not academic: an accessor carrying {@code @FieldSource} begins on the
     * annotation's line, and DEC-028's location payload records both the accessor's line and the
     * annotation's — conflating them is the defect F-46 records.</p>
     *
     * @param ownerName       the simple name of the type declaring the method, which distinguishes two
     *                        same-named methods in one file
     * @param parameterCount  the method's arity, which distinguishes overloads of one name
     * @param source          the exact text the tree was parsed from
     */
    public static int methodLineOf(J.MethodDeclaration method, String ownerName, String source) {
        if (method == null || source == null || source.isBlank()) {
            return -1;
        }
        String declaredName = method.getSimpleName();
        int arity = method.getParameters() == null || hasNoParameters(method) ? 0 : method.getParameters().size();
        for (JavaSyntaxCheck.MethodPosition position : JavaSyntaxCheck.inspect(source).methods()) {
            if (position.simpleName().equals(declaredName)
                    && position.parameterCount() == arity
                    && (ownerName == null || position.declaringType().equals(ownerName))) {
                return position.nameLine();
            }
        }
        return -1;
    }

    /**
     * The line an annotation sits on, or {@code -1}.
     *
     * <p>Annotations have no LST node of their own position — they are attached trees with a prefix — so
     * this is the only way to answer "which line is {@code @FieldSource} on", which DEC-028 records as a
     * location distinct from the member it annotates.</p>
     *
     * <p>Matching only on the member name is not enough within one file: two nested interfaces of one
     * view may each declare an accessor of the same name, and their annotations are on different lines.
     * The declaring type is therefore part of the key, and passing {@code null} for it means "any".</p>
     *
     * @param declaringType the innermost declaring type's simple name, or {@code null} for any
     * @param ownerName     the member the annotation is written on (or the type's own name), or
     *                      {@code null} for any
     */
    public static int annotationLineOf(String declaringType, String ownerName, String annotationSimpleName,
                                       String source) {
        if (source == null || source.isBlank() || annotationSimpleName == null) {
            return -1;
        }
        for (JavaSyntaxCheck.AnnotationPosition position : JavaSyntaxCheck.inspect(source).annotations()) {
            if (!position.simpleName().equals(annotationSimpleName)) {
                continue;
            }
            if (ownerName != null && !position.owner().equals(ownerName)) {
                continue;
            }
            if (declaringType != null && !position.declaringType().equals(declaringType)) {
                continue;
            }
            return position.line();
        }
        return -1;
    }

    /**
     * {@link #annotationLineOf(String, String, String, String)} matching on the member name alone.
     *
     * <p>Kept for the callers that have only a member name in hand — and it is the weaker question, so
     * it answers with the first match in source order.</p>
     */
    public static int annotationLineOf(String ownerName, String annotationSimpleName, String source) {
        return annotationLineOf(null, ownerName, annotationSimpleName, source);
    }

    /**
     * The line an enum constant, record component or field name sits on, or {@code -1}.
     *
     * <p>The three roles are one query because they are one javac shape and the caller knows which role
     * it is looking for; {@link #memberLineOf} simply does not answer for a role the declaration does
     * not have. That is the honest answer — DEC-028's location payload verifies every line it records
     * against the text, so a line for a member that is not there would be a link that opens the wrong
     * place.</p>
     *
     * @param ownerDisplayName the declaring type's dotted display name, outermost first
     *                         (e.g. {@code PersonSummary} or {@code Outer.Inner})
     * @param role             {@code enum-constant}, {@code record-component} or {@code field}
     */
    public static int memberLineOf(String ownerDisplayName, String memberName, String role, String source) {
        if (source == null || source.isBlank() || memberName == null || role == null) {
            return -1;
        }
        for (JavaSyntaxCheck.MemberPosition position : JavaSyntaxCheck.inspect(source).members()) {
            if (position.simpleName().equals(memberName)
                    && position.role().equals(role)
                    && (ownerDisplayName == null || position.owner().equals(ownerDisplayName))) {
                return position.nameLine();
            }
        }
        return -1;
    }

    /**
     * A member declaration's <strong>exact original text</strong>, with a comment attached directly
     * above it, or {@code null} when the member cannot be located.
     *
     * <p>This is the LST's substitute for {@code node.getRange()}, and it exists because cooperative
     * codegen must carry a developer's member through a regeneration verbatim (DEC-020). Nothing that
     * re-prints a tree can do that: a printer normalises whitespace, and on this migration it even
     * changes the generic comma. So the text is <em>sliced out of the original source</em> using the
     * offsets javac recorded, and the slice is extended backwards over the comment that documents the
     * member — the part a user-written extension point is mostly made of.</p>
     *
     * <p>The comment rule, stated once here because it is the only place that needs it: from the
     * declaration's start, trailing whitespace is ignored; if what remains ends a block comment or is a
     * line comment, that comment belongs to the declaration. A comment separated from the declaration by
     * anything but whitespace belongs to something else, and a comment <em>inside</em> the member is
     * part of its slice already.</p>
     *
     * <p>The lookup returns the <em>first</em> match, which is what a map keyed on the same identity
     * would have done: two same-named members of equal arity are one identity in this codebase's
     * vocabulary and have always been treated as one.</p>
     *
     * @param owner          the declaring type's dotted display name, outermost first
     * @param kind           {@code type}, {@code method}, {@code constructor}, {@code field},
     *                       {@code enum-constant} or {@code record-component}
     * @param parameterCount the declared arity for a method or constructor
     * @param source         the exact text the tree was parsed from
     */
    public static String memberText(String owner, String kind, String name, int parameterCount,
                                    String source) {
        SourceSpan span = memberTextSpan(owner, kind, name, parameterCount, source);
        return span == null ? null : source.substring(span.start(), span.end());
    }

    /**
     * The <strong>character span</strong> {@link #memberText} slices, or {@code null} when the member
     * cannot be located.
     *
     * <p>Exists for the one caller that has to <em>delete</em> a member rather than reproduce it: field
     * enumeration compaction removes a retired constant, and an LST node cannot be removed from its
     * parent. Deleting a span is what replaces {@code entry.remove()}, and sharing the lookup with
     * {@code memberText} keeps the attached-comment rule in one place — a tombstone is emitted with a
     * {@code @deprecated} javadoc above it, so deleting the declaration alone would leave a comment
     * naming a constant that no longer exists.</p>
     */
    public static SourceSpan memberTextSpan(String owner, String kind, String name, int parameterCount,
                                            String source) {
        if (source == null || source.isBlank() || name == null) {
            return null;
        }
        for (JavaSyntaxCheck.MemberSpan span : JavaSyntaxCheck.inspect(source).spans()) {
            if (!span.kind().equals(kind) || !span.name().equals(name)
                    || span.parameterCount() != parameterCount) {
                continue;
            }
            if (owner != null && !span.owner().equals(owner)) {
                continue;
            }
            if (span.startOffset() < 0 || span.endOffset() < span.startOffset()
                    || span.endOffset() > source.length()) {
                return null;
            }
            return new SourceSpan(attachedCommentStart(source, span.startOffset()), span.endOffset());
        }
        return null;
    }

    /**
     * The character span of each switch arm in {@code cu}, keyed by the arm's own node.
     *
     * <p>Paired exactly as {@link #caseLines} is — two source-ordered lists, zipped, with an empty
     * answer when they disagree — because an arm's offset is the same fact as its line, read from the
     * same traversal.</p>
     */
    public static Map<J.Case, SourceSpan> caseSpans(J.CompilationUnit cu, String source) {
        if (cu == null || source == null || source.isBlank()) {
            return Map.of();
        }
        List<J.Case> cases = findAll(cu, J.Case.class);
        List<JavaSyntaxCheck.CaseSpan> spans = JavaSyntaxCheck.inspect(source).caseSpans();
        if (cases.size() != spans.size()) {
            return Map.of();
        }
        Map<J.Case, SourceSpan> byCase = new IdentityHashMap<>();
        for (int i = 0; i < cases.size(); i++) {
            JavaSyntaxCheck.CaseSpan span = spans.get(i);
            if (span.startOffset() >= 0 && span.endOffset() >= span.startOffset()) {
                byCase.put(cases.get(i), new SourceSpan(span.startOffset(), span.endOffset()));
            }
        }
        return byCase;
    }

    /**
     * A half-open character range in one source text.
     *
     * @param start the first character
     * @param end   one past the last character
     */
    public record SourceSpan(int start, int end) {

        public int length() {
            return end - start;
        }
    }

    /**
     * The offset a member's slice really starts at: the declaration's own start, or the start of the
     * comment immediately above it.
     *
     * <p>The comment's own indentation is included, because a javadoc's first character is {@code /} —
     * slicing from there would re-emit the member flush against the left margin and the next pass would
     * see a diff that is only whitespace, breaking the byte-identical fixed point DEC-020 promises.</p>
     */
    private static int attachedCommentStart(String source, int start) {
        if (start <= 0) {
            return Math.max(start, 0);
        }
        int end = start;
        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        int commentStart;
        if (end >= 2 && source.startsWith("*/", end - 2)) {
            commentStart = source.lastIndexOf("/*", end - 2);
            if (commentStart < 0) {
                return start;
            }
        } else {
            // A run of `//` lines: walk back while every line above is one.
            commentStart = lineCommentRunStart(source, end);
            if (commentStart < 0) {
                return start;
            }
        }
        int lineStart = source.lastIndexOf('\n', commentStart) + 1;
        return source.substring(lineStart, commentStart).isBlank() ? lineStart : commentStart;
    }

    /** The start of the consecutive {@code //} comment lines ending at {@code end}, or {@code -1}. */
    private static int lineCommentRunStart(String source, int end) {
        int runStart = -1;
        int lineEnd = end;
        while (lineEnd > 0) {
            int lineStart = source.lastIndexOf('\n', lineEnd - 1) + 1;
            String line = source.substring(lineStart, lineEnd).trim();
            if (!line.startsWith("//")) {
                break;
            }
            runStart = lineStart;
            lineEnd = lineStart > 0 ? lineStart - 1 : 0;
        }
        return runStart;
    }

    /**
     * The line each switch arm in {@code cu} starts on, keyed by the arm's own node.
     *
     * <p>Neither API can name an arm to the other, so the two source-ordered lists are paired
     * positionally: javac reports every {@code CaseTree} in the file in source order, and the LST's own
     * pre-order traversal reports every {@link J.Case} in source order. When the counts disagree the
     * answer is an empty map rather than a best guess — a wrong line is worse than a missing location,
     * and a mismatch means the two trees disagree about what an arm is.</p>
     *
     * <p>The pairing is deliberately file-wide rather than per method: matching by method name and arity
     * would have to survive overloads, nested types and lambdas, while "the k-th arm in the file" is
     * exactly what both traversals already agree on.</p>
     *
     * @param source the exact text {@code cu} was parsed from
     */
    public static Map<J.Case, Integer> caseLines(J.CompilationUnit cu, String source) {
        if (cu == null || source == null || source.isBlank()) {
            return Map.of();
        }
        List<J.Case> cases = findAll(cu, J.Case.class);
        List<Integer> lines = JavaSyntaxCheck.inspect(source).caseLines();
        if (cases.size() != lines.size()) {
            return Map.of();
        }
        Map<J.Case, Integer> byCase = new IdentityHashMap<>();
        for (int i = 0; i < cases.size(); i++) {
            byCase.put(cases.get(i), lines.get(i));
        }
        return byCase;
    }

}
