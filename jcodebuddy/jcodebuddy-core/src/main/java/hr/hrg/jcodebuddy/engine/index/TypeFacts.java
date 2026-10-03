package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The basic facts about one type declaration — what a class index row records beyond the file's own
 * content identity (DEC-029).
 *
 * <p>{@code fqn} is the key the whole index is addressed by: package-qualified, member types joined
 * with {@code .} ({@code …person.entity.PersonSummary.Record}), which is the form a Java developer, an
 * IDE's rename refactor, {@code grep} and every other tool already understands. That is the reason
 * this index has no surrogate id: an id would be the one reference an IDE could not update.</p>
 *
 * @param fqn       the fully qualified name of the type — the row's key
 * @param kind      {@code class} / {@code interface} / {@code enum} / {@code record} / {@code annotation},
 *                  from {@link MetadataLocations#kindOf} so there is exactly one kind resolver in the
 *                  tooling
 * @param modifiers the Java modifier keywords of the declaration, <strong>sorted</strong> and
 *                  restricted to {@link #KEYWORDS} — a reordered modifier list must not be a diff
 * @param enclosing the FQN of the enclosing type, or {@code null} for a top-level type
 * @param line      the declaration's start line (its <em>name</em>), 1-based, or {@code -1}
 * @param depth     0 for a top-level type, the number of enclosing types otherwise
 * @param relations the type's supertypes, {@code extends} clause first and then {@code implements}, as
 *                  {@link TypeRelation}s — the names as written, since a row is a fact about one declaration
 *                  and resolving a name needs the whole index (DEC-029's relation half, plan step 3.0b)
 * @param annotations the annotations on the declaration, as written, with their arguments as written — the
 *                  declaration's own text, never an interpretation of it ({@link TypeAnnotation})
 * @param members   what the declaration contains — its fields, methods, constructors and nested types, as
 *                  {@link MemberRecord}s, in source order (plan step 3.0r). Read from the declaration that was
 *                  already parsed for everything else here, never by a second parse.
 */
public record TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                        List<TypeRelation> relations, List<TypeAnnotation> annotations,
                        List<MemberRecord> members) {

    /**
     * The modifier vocabulary the index records, for a type declaration and for a member alike.
     *
     * <p>Deliberately not "every keyword the parser reports": the index answers "what kind of declaration is
     * this and who can see it", and a keyword outside this set (a type-use modifier, a JVM-only flag) would be
     * a fact this table has no contract for. Adding one here is a format change and belongs in DEC-029.</p>
     *
     * <p><strong>{@code default} is in the set for a measured reason (plan step 3.0e).</strong> Before it, an
     * interface's {@code default String buildXxx()} and its {@code String name();} both recorded an empty
     * modifier list, so the two were <em>indistinguishable in the index</em> — and that distinction is exactly
     * what hipster-ioc's model is made of: an abstract accessor is a bean, a {@code default} method is the
     * factory that builds one. It is a member-only keyword (no type declaration can carry it), which is why the
     * set is documented as shared rather than as a type vocabulary.</p>
     */
    public static final Set<String> KEYWORDS = Set.of(
            "public", "protected", "private", "abstract", "static", "final",
            "sealed", "non-sealed", "strictfp", "default");

    public TypeFacts {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        relations = relations == null ? List.of() : List.copyOf(relations);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        members = members == null ? List.of() : List.copyOf(members);
    }

    /**
     * The facts of a type whose annotations and members the caller did not read.
     *
     * <p>A delegating constructor rather than a second shape: an empty list is how "none read" and "carries
     * none" are both spelled, so a caller that knows nothing about annotations does not need to know this
     * record grew a field — and a caller that wants the distinction asks the parse path, not this
     * constructor.</p>
     */
    public TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                     List<TypeRelation> relations, List<TypeAnnotation> annotations) {
        this(fqn, kind, modifiers, enclosing, line, depth, relations, annotations, List.of());
    }

    /**
     * The facts of a type whose annotations the caller did not read.
     *
     * <p>A delegating constructor rather than a second shape: an empty list is how "none read" and "carries
     * none" are both spelled, so a caller that knows nothing about annotations does not need to know this
     * record grew a field — and a caller that wants the distinction asks the parse path, not this
     * constructor.</p>
     */
    public TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                     List<TypeRelation> relations) {
        this(fqn, kind, modifiers, enclosing, line, depth, relations, List.of(), List.of());
    }

    /**
     * {@link #of(J.ClassDeclaration, List, String)} for callers that already hold the facts.
     *
     * <p>The one place an FQN is composed, so the index cannot grow two spellings of a nested type's
     * name. Every caller reaches it through the LST walk in
     * {@link TreeQueries#typesWithEnclosing}.</p>
     *
     * @param packageName    the declaring file's package, or {@code ""} for the default package
     * @param simpleName     the type's own name
     * @param enclosingNames the enclosing types' simple names, outermost first
     */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line) {
        return of(packageName, simpleName, enclosingNames, kind, modifiers, line, List.of(), List.of());
    }

    /** {@link #of(String, String, List, String, List, int)} with the type's relations (plan step 3.0b). */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line, List<TypeRelation> relations) {
        return of(packageName, simpleName, enclosingNames, kind, modifiers, line, relations, List.of());
    }

    /** {@link #of(String, String, List, String, List, int)} with relations and annotations. */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line, List<TypeRelation> relations,
                               List<TypeAnnotation> annotations) {
        return of(packageName, simpleName, enclosingNames, kind, modifiers, line, relations, annotations, List.of());
    }

    /** {@link #of(String, String, List, String, List, int)} with relations, annotations and members. */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line, List<TypeRelation> relations,
                               List<TypeAnnotation> annotations, List<MemberRecord> members) {
        List<String> chain = new ArrayList<>(enclosingNames);
        chain.add(simpleName);
        String prefix = packageName == null || packageName.isEmpty() ? "" : packageName + ".";
        return new TypeFacts(
                prefix + String.join(".", chain),
                kind,
                modifiers,
                enclosingNames == null || enclosingNames.isEmpty()
                        ? null
                        : prefix + String.join(".", enclosingNames),
                line,
                enclosingNames == null ? 0 : enclosingNames.size(),
                relations,
                annotations,
                members);
    }

    /**
     * The facts of one declaration, with its FQN derived from the unit's package and its parents.
     *
     * <p>Phase 6: the enclosing chain is supplied rather than discovered. JavaParser's
     * {@code Node.getParentNode()} has no LST equivalent — a node does not know its parent — so the
     * ancestry is captured during traversal by {@link TreeQueries#typesWithEnclosing} and handed in
     * here. Every fact this method needs is then either local to the declaration or already computed.
     * </p>
     *
     * @param source the text the declaration was parsed from; carried for the line number, which the
     *               tree alone cannot supply (see {@link TreeQueries#lineOf})
     */
    public static TypeFacts of(J.ClassDeclaration declaration, List<J.ClassDeclaration> enclosingTypes,
                               String source) {
        List<String> enclosingChain = new ArrayList<>();
        for (J.ClassDeclaration enclosing : enclosingTypes) {
            enclosingChain.add(enclosing.getSimpleName());
        }
        return of(packageOf(declaration, source), declaration.getSimpleName(), enclosingChain,
                TypeKinds.kindOf(declaration), modifiersOf(declaration),
                // The enclosing chain is the line lookup's key half: `Shape` and `Shape.Circle` differ
                // only by it, and a lookup missing it answers the outer declaration with the inner
                // declaration's line.
                TreeQueries.lineOfChained(declaration, enclosingChain, source),
                relationsOf(declaration, TypeKinds.kindOf(declaration)),
                annotationsOf(declaration),
                membersOf(declaration));
    }

    /**
     * The annotations on a declaration, as written, in declaration order (asked for 2026-10-02).
     *
     * <p>Names come from {@link TreeQueries#annotationName}, which is the same reading the entity tooling's own
     * annotation queries use, so there is one answer to "what is this annotation called" rather than two.
     * Arguments come from {@link TreeQueries#expressionText} and are deliberately <em>not</em> evaluated: the
     * engine records what the source says, and a consumer that needs a value has the type to read it with.
     * A single {@code J.Empty} is what an annotation with an empty parameter list parses to (DEC-030's trap for
     * methods, and the same shape here), so it is skipped rather than recorded as one empty argument.</p>
     */
    public static List<TypeAnnotation> annotationsOf(J.ClassDeclaration declaration) {
        return declaration == null ? List.of() : annotationsOf(declaration.getLeadingAnnotations());
    }

    /**
     * The annotations on any declaration or member, as written (plan step 3.0r's member half).
     *
     * <p>The same reading as {@link #annotationsOf(J.ClassDeclaration)}, so a member's annotations cannot be
     * recorded differently from a type's: names unwrapped, arguments as text, a single {@code J.Empty} skipped
     * rather than recorded as one empty argument.</p>
     */
    public static List<TypeAnnotation> annotationsOf(List<J.Annotation> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return List.of();
        }
        List<TypeAnnotation> written = new ArrayList<>(annotations.size());
        for (J.Annotation annotation : annotations) {
            List<String> arguments = new ArrayList<>();
            List<org.openrewrite.java.tree.Expression> writtenArguments = annotation.getArguments();
            if (writtenArguments != null) {
                for (org.openrewrite.java.tree.Expression argument : writtenArguments) {
                    if (argument instanceof J.Empty) {
                        continue;
                    }
                    arguments.add(TreeQueries.expressionText(argument));
                }
            }
            written.add(new TypeAnnotation(TreeQueries.annotationName(annotation), arguments));
        }
        return List.copyOf(written);
    }

    /**
     * What the declaration contains, in source order: its fields, methods, constructors and nested types (plan
     * step 3.0r, DEC-029's member field).
     *
     * <p>Three limits are deliberate, and each is a fact this table has no contract for — so a consumer that
     * needs one asks the parse path rather than reading an absence here as a "no":</p>
     *
     * <ul>
     *   <li><strong>No bodies and no initialisers.</strong> The index answers "what shape does this declaration
     *       have"; a body would be the file's content in a second place, stale as soon as either copy moves.</li>
     *   <li><strong>No enum constants.</strong> An enum's constants are {@code J.EnumValue} statements rather
     *       than variables, and nothing in this table's contract describes them yet.</li>
     *   <li><strong>No parameter annotations.</strong> Parameter <em>types</em> are recorded; a parameter's own
     *       annotations are a third level of detail nothing asks for.</li>
     * </ul>
     *
     * <p>An annotation type's members read as the methods Java makes them: {@code String name();} is a
     * {@link MemberRecord.Kind#METHOD} whose type is {@code String} and which has no parameters.</p>
     */
    public static List<MemberRecord> membersOf(J.ClassDeclaration declaration) {
        if (declaration == null || declaration.getBody() == null) {
            return List.of();
        }
        List<MemberRecord> members = new ArrayList<>();
        for (Statement statement : declaration.getBody().getStatements()) {
            if (statement instanceof J.VariableDeclarations field) {
                members.addAll(fieldsOf(field));
            } else if (statement instanceof J.MethodDeclaration method) {
                boolean constructor = method.isConstructor();
                members.add(new MemberRecord(method.getSimpleName(),
                        constructor ? MemberRecord.Kind.CONSTRUCTOR : MemberRecord.Kind.METHOD,
                        // A constructor declares no return type and its name is the type's, so recording a
                        // type would be recording something the source does not say.
                        constructor ? "" : TreeQueries.typeText(method.getReturnTypeExpression()),
                        parametersOf(method),
                        modifiersOf(method.getModifiers()),
                        annotationsOf(method.getLeadingAnnotations())));
            } else if (statement instanceof J.ClassDeclaration nested) {
                members.add(new MemberRecord(nested.getSimpleName(), MemberRecord.Kind.NESTED,
                        nested.getSimpleName(), List.of(), modifiersOf(nested.getModifiers()),
                        annotationsOf(nested.getLeadingAnnotations())));
            }
        }
        return List.copyOf(members);
    }

    /**
     * One field declaration's members: {@code private int a, b;} declares <em>two</em>, and each is its own
     * member, because the question a consumer asks is "is there a field called {@code b}" rather than "what does
     * this statement declare".
     */
    private static List<MemberRecord> fieldsOf(J.VariableDeclarations field) {
        String type = TreeQueries.typeText(field.getTypeExpression());
        List<String> modifiers = modifiersOf(field.getModifiers());
        List<TypeAnnotation> annotations = annotationsOf(field.getLeadingAnnotations());
        List<MemberRecord> members = new ArrayList<>(field.getVariables().size());
        for (J.VariableDeclarations.NamedVariable variable : field.getVariables()) {
            members.add(new MemberRecord(variable.getSimpleName(), MemberRecord.Kind.FIELD, type, List.of(),
                    modifiers, annotations));
        }
        return members;
    }

    /**
     * A callable's parameters as written, in order: each one's type, its name and its own annotations.
     *
     * <p>The name and the annotations are here because a consumer needs them and the index is where a consumer
     * reads (plan step 3.0e): hipster-ioc's factory model is "which bean does {@code buildMapper} build, and
     * which of its parameters are {@code @Circular}" — a question about a parameter, not about its type. With
     * types alone that consumer had to parse the file again, which is the second parse this model exists to
     * remove.</p>
     *
     * <p>The LST spells "no parameters" as a single {@link J.Empty} rather than an empty list (DEC-030's trap
     * for methods), so it is skipped here instead of being recorded as one parameter with an empty type.</p>
     */
    private static List<MemberParameter> parametersOf(J.MethodDeclaration method) {
        List<Statement> parameters = method.getParameters();
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        List<MemberParameter> written = new ArrayList<>(parameters.size());
        for (Statement parameter : parameters) {
            if (parameter instanceof J.Empty) {
                continue;
            }
            if (parameter instanceof J.VariableDeclarations declarations) {
                List<TypeAnnotation> annotations = annotationsOf(declarations.getLeadingAnnotations());
                String type = TreeQueries.typeText(declarations.getTypeExpression());
                if (declarations.getVariables() != null) {
                    for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
                        written.add(new MemberParameter(type, variable.getSimpleName(), annotations));
                    }
                    continue;
                }
                written.add(new MemberParameter(type, "", annotations));
                continue;
            }
            // A shape this contract does not model — recorded as written rather than dropped, because dropping
            // it would understate the arity and make a signature look unique when it is not.
            written.add(MemberParameter.of(TreeQueries.typeText(parameter), ""));
        }
        return List.copyOf(written);
    }

    /**
     * The declaration's supertypes as relations, in source order: the {@code extends} clause first, then the
     * {@code implements} clause (plan step 3.0b).
     *
     * <p>The clause is <strong>not</strong> read from {@link J.ClassDeclaration#getExtends()} alone, and that
     * is why this method exists: an <em>interface's</em> {@code extends} clause is held in
     * {@code getImplements()} while its {@code getExtends()} is {@code null} — DEC-030's measured trap, where
     * reading {@code getExtends()} finds no supertype at all for the commonest declaration in this project.
     * So the declaration's kind decides what an entry of {@code getImplements()} means: for an interface or an
     * annotation it is an {@code extends}, for a class, enum or record an {@code implements}. A table that had
     * this backwards would draw an {@code implements} edge from a declaration that cannot have one.</p>
     *
     * <p>Names come from {@link TreeQueries#supertypeTexts} — the form written in the source, with balanced type
     * arguments removed — rather than from {@link TreeQueries#supertypeNames}, and the first test of this step
     * is why: {@code supertypeNames} answers with the <em>last segment</em> only ({@code Serializable} for
     * {@code java.io.Serializable}), so recording it would throw away the qualification and make two different
     * types one relation. Dropping the arguments keeps a name from becoming source text: the relation is
     * {@code EntityBase}, not {@code EntityBase<Long>} (see {@link TypeRelation}).</p>
     */
    public static List<TypeRelation> relationsOf(J.ClassDeclaration declaration, String kind) {
        if (declaration == null) {
            return List.of();
        }
        boolean interfaceLike = "interface".equals(kind) || "annotation".equals(kind);
        boolean extendsClause = declaration.getExtends() != null;
        List<String> names = TreeQueries.supertypeTexts(declaration);
        List<TypeRelation> relations = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            // Only the first name can come from an `extends` clause, and only when there is one; everything
            // after it came from `implements`, except on an interface, where the whole list is `extends`.
            boolean fromExtends = interfaceLike || (extendsClause && i == 0);
            // The text as written is recorded and the bare name is derived from it (DEC-040 D2): the arguments
            // are what the compiler erases, and `ChildContext<AppContext>` is the fact a consumer needs.
            String written = names.get(i);
            relations.add(new TypeRelation(withoutTypeArguments(written),
                    fromExtends ? TypeRelation.Kind.EXTENDS : TypeRelation.Kind.IMPLEMENTS, written));
        }
        return List.copyOf(relations);
    }

    /**
     * Removes balanced {@code <…>} groups from a type's source text, keeping everything else.
     *
     * <p>Balanced groups rather than a cut at the first {@code <}: the first is wrong for
     * {@code Outer<T>.Inner}, which is two names and one argument, and it would answer {@code Outer} for a
     * relation to {@code Outer.Inner}. An unbalanced {@code <} (which cannot come from a parsed declaration)
     * leaves the text as it is rather than truncating a name.</p>
     */
    public static String withoutTypeArguments(String typeText) {
        if (typeText == null || typeText.indexOf('<') < 0) {
            return typeText;
        }
        StringBuilder out = new StringBuilder(typeText.length());
        int depth = 0;
        for (int i = 0; i < typeText.length(); i++) {
            char c = typeText.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                if (depth == 0) {
                    return typeText; // unbalanced: not a shape a declaration produced, so do not guess
                }
                depth--;
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return depth == 0 ? out.toString() : typeText;
    }

    /**
     * The declaration's package, or {@code ""} when the unit has no package declaration.
     *
     * <p>Read from the source text rather than by walking to the compilation unit, because the LST
     * gives a node no way back to its root. The package declaration is at the top of the file by
     * definition, so the text is both the cheapest and the most reliable place to ask — and it is
     * already required for the line number.</p>
     */
    public static String packageOf(J.ClassDeclaration declaration, String source) {
        if (source == null) {
            return "";
        }
        for (String rawLine : source.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.startsWith("package ")) {
                String name = line.substring("package ".length()).trim();
                if (name.endsWith(";")) {
                    name = name.substring(0, name.length() - 1).trim();
                }
                return name;
            }
            // The package declaration must precede every type; anything else ends the search.
            if (!line.isEmpty() && !line.startsWith("//") && !line.startsWith("*")
                    && !line.startsWith("/*") && !line.startsWith("import ")) {
                break;
            }
        }
        return "";
    }

    /**
     * The declaration's keywords, filtered to {@link #KEYWORDS} and sorted.
     *
     * <p>Read from {@link J.Modifier#getKeyword()} rather than from the enum constant, so a keyword the
     * parser models as two words ({@code non-sealed}) is spelled the way the source spells it and the
     * way this table's contract spells it.</p>
     */
    private static List<String> modifiersOf(J.ClassDeclaration declaration) {
        return modifiersOf(declaration.getModifiers());
    }

    /**
     * Any declaration's or member's keywords, filtered to {@link #KEYWORDS} and sorted (plan step 3.0r's member
     * half goes through here, so a member's modifiers cannot be spelled differently from a type's).
     *
     * <p>The filter is the contract, not a convenience: a keyword outside {@link #KEYWORDS} is a fact this
     * table has no case for, and recording it would grow the format by accident.</p>
     */
    public static List<String> modifiersOf(List<J.Modifier> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>(modifiers.size());
        for (J.Modifier modifier : modifiers) {
            String keyword = keywordOf(modifier);
            if (KEYWORDS.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        Collections.sort(keywords);
        return List.copyOf(keywords);
    }

    /**
     * The source spelling of a modifier keyword.
     *
     * <p>{@code J.Modifier.Type.NonSealed} must render as {@code non-sealed}, not {@code NonSealed}:
     * DEC-029's table is a contract over {@link #KEYWORDS}, and every entry there is spelled the way
     * Java spells it. The default {@code toString()} of the enum constant would silently break that
     * contract for the two hyphenated keywords.</p>
     */
    private static String keywordOf(J.Modifier modifier) {
        return switch (modifier.getType()) {
            case Public -> "public";
            case Protected -> "protected";
            case Private -> "private";
            case Abstract -> "abstract";
            case Static -> "static";
            case Final -> "final";
            case Sealed -> "sealed";
            case NonSealed -> "non-sealed";
            case Strictfp -> "strictfp";
            default -> modifier.getType().name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
