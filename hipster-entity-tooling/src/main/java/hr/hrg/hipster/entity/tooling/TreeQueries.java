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
import java.util.List;
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
     * The methods a declaration really declares, constructors excluded.
     *
     * <p>{@code J.MethodDeclaration} covers constructors as well as methods — there is no separate
     * constructor type — so a walk over method declarations returns both. JavaParser's
     * {@code getMethods()} excluded constructors, and every caller here is asking about accessors, so
     * the exclusion is done once here rather than trusted to each call site.</p>
     */
    public static List<J.MethodDeclaration> methodsOf(J.ClassDeclaration declaration) {
        return filter(findAll(declaration, J.MethodDeclaration.class), method -> !method.isConstructor());
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

    /** The supertypes named in an {@code extends} clause, as simple names. */
    public static List<String> supertypeNames(J.ClassDeclaration declaration) {
        List<String> names = new ArrayList<>();
        if (declaration == null) {
            return names;
        }
        TypeTree extending = declaration.getExtends();
        if (extending != null) {
            names.add(simpleTypeName(extending));
        }
        List<TypeTree> implemented = declaration.getImplements();
        if (implemented != null) {
            for (TypeTree type : implemented) {
                names.add(simpleTypeName(type));
            }
        }
        return names;
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
}
