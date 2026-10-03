package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.JavacPositions;
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
 * @param span      the declaration's character range in its file, or {@code null} when the walk recorded none —
 *                  annotations and modifiers included, because that is what a reader clicks (DEC-040 D6)
 * @param permits   a sealed type's permitted subtypes as written, in order; empty otherwise (DEC-040 D1)
 */
public record TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                        List<TypeRelation> relations, List<TypeAnnotation> annotations,
                        List<MemberRecord> members, TreeQueries.SourceSpan span, List<String> permits) {

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
        permits = permits == null ? List.of() : List.copyOf(permits);
    }

    /**
     * The facts of a type whose annotations, members, position and permitted subtypes the caller did not read.
     *
     * <p>A delegating constructor rather than a second shape: an empty list is how "none read" and "carries
     * none" are both spelled, so a caller that knows nothing about annotations does not need to know this
     * record grew a field — and a caller that wants the distinction asks the parse path, not this
     * constructor.</p>
     */
    public TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                     List<TypeRelation> relations, List<TypeAnnotation> annotations,
                     List<MemberRecord> members) {
        this(fqn, kind, modifiers, enclosing, line, depth, relations, annotations, members, null, List.of());
    }

    /** The facts of a type with a declaration range but no permitted-subtype list read. */
    public TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                     List<TypeRelation> relations, List<TypeAnnotation> annotations,
                     List<MemberRecord> members, TreeQueries.SourceSpan span) {
        this(fqn, kind, modifiers, enclosing, line, depth, relations, annotations, members, span, List.of());
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
        return of(packageName, simpleName, enclosingNames, kind, modifiers, line, relations, annotations,
                members, null);
    }

    /** {@link #of(String, String, List, String, List, int)} with a declaration range but no permits read. */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line, List<TypeRelation> relations,
                               List<TypeAnnotation> annotations, List<MemberRecord> members,
                               TreeQueries.SourceSpan span) {
        return of(packageName, simpleName, enclosingNames, kind, modifiers, line, relations, annotations,
                members, span, List.of());
    }

    /** {@link #of(String, String, List, String, List, int)} with everything this record carries. */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line, List<TypeRelation> relations,
                               List<TypeAnnotation> annotations, List<MemberRecord> members,
                               TreeQueries.SourceSpan span, List<String> permits) {
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
                members,
                span,
                permits);
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
        return of(declaration, enclosingTypes, source, JavacPositions.of(source));
    }

    /**
     * {@link #of(J.ClassDeclaration, List, String)} with the file's positions already parsed.
     *
     * <p>A pass reads one file and then asks about every type in it, so building {@link JavacPositions} once
     * and passing it in is the difference between one javac parse per file and one per declaration — and the
     * members' lines and spans (DEC-040 D6) come from exactly that parse.</p>
     */
    public static TypeFacts of(J.ClassDeclaration declaration, List<J.ClassDeclaration> enclosingTypes,
                               String source, JavacPositions positions) {
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
                relationsOf(declaration, TypeKinds.kindOf(declaration), positions, declaration.getSimpleName()),
                annotationsOf(declaration.getLeadingAnnotations(), positions, declaration.getSimpleName(),
                        declaration.getSimpleName()),
                membersOf(declaration, positions),
                // The declaration's own range, so a consumer can point at the type itself (DEC-040 D6).
                positions.typeSpan(declaration.getSimpleName(), enclosingChain),
                // A sealed type's permitted subtypes, as written (DEC-040 D1). Read from the declaration rather
                // than from the class file, because `permits` is a compile-time fact whose reflective
                // equivalent depends on the version and the library doing the asking.
                permitsOf(declaration));
    }

    /**
     * A sealed type's permitted subtypes, as written and in order; empty for a type that is not sealed.
     *
     * <p>The type text is the LST's spelling ({@link TreeQueries#typeText}), so a nested permitted subtype
     * written {@code Outer.Inner} comes back as it was written rather than resolved — resolution is search's
     * work (3.0h), and DEC-040 D3 keeps the written form in the model either way.</p>
     */
    public static List<String> permitsOf(J.ClassDeclaration declaration) {
        if (declaration == null || declaration.getPermits() == null || declaration.getPermits().isEmpty()) {
            return List.of();
        }
        List<String> permitted = new ArrayList<>(declaration.getPermits().size());
        for (org.openrewrite.java.tree.TypeTree permittedType : declaration.getPermits()) {
            permitted.add(TreeQueries.typeText(permittedType));
        }
        return List.copyOf(permitted);
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
        return declaration == null ? List.of()
                : annotationsOf(declaration.getLeadingAnnotations(), null, null, null);
    }

    /**
     * The annotations on any declaration or member, as written (plan step 3.0r's member half).
     *
     * <p>The same reading as {@link #annotationsOf(J.ClassDeclaration)}, so a member's annotations cannot be
     * recorded differently from a type's: names unwrapped, arguments as text, a single {@code J.Empty} skipped
     * rather than recorded as one empty argument.</p>
     */
    public static List<TypeAnnotation> annotationsOf(List<J.Annotation> annotations) {
        return annotationsOf(annotations, null, null, null);
    }

    /**
     * {@link #annotationsOf(List)} with the file's positions, so each annotation carries the range it is written
     * at (DEC-040 D2/D6).
     *
     * <p>The lookup is keyed by the type the annotation is written inside, the member it is written on — or that
     * type's own name for a type-level annotation, which is the convention javac's walk records — and the
     * annotation's simple name. A parameter's annotations are <strong>not</strong> located by that walk, so they
     * carry no range: a stated gap rather than a guessed one.</p>
     */
    public static List<TypeAnnotation> annotationsOf(List<J.Annotation> annotations, JavacPositions positions,
                                                     String declaringType, String owner) {
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
            String name = TreeQueries.annotationName(annotation);
            TreeQueries.SourceSpan span = positions == null || declaringType == null || owner == null
                    ? null
                    : positions.annotationSpan(declaringType, owner, simpleNameOf(name));
            written.add(new TypeAnnotation(name, arguments, span));
        }
        return List.copyOf(written);
    }

    /** {@code jakarta.inject.Inject} to {@code Inject} — the spelling an annotation position is keyed by. */
    private static String simpleNameOf(String annotationName) {
        int dot = annotationName.lastIndexOf('.');
        return dot < 0 ? annotationName : annotationName.substring(dot + 1);
    }

    /**
     * What the declaration contains, in source order: its fields, methods, constructors, enum constants and
     * nested types (plan step 3.0r, DEC-029's member field; enum constants added by DEC-040 D1).
     *
     * <p>Two limits remain deliberate, and each is a fact this table has no contract for — so a consumer that
     * needs one asks the parse path rather than reading an absence here as a "no":</p>
     *
     * <ul>
     *   <li><strong>No bodies.</strong> Whether a callable <em>has</em> one is recorded ({@code hasBody}), because
     *       a generator that must override rather than call needs to know; what is <em>in</em> it would be the
     *       file's content in a second place, stale as soon as either copy moves. The same line separates a
     *       field's initialiser — recorded as written, one expression — from a method body, which is
     *       statements.</li>
     *   <li><strong>No parameter annotations.</strong> Parameter <em>types</em> are recorded; a parameter's own
     *       annotations are a third level of detail nothing asks for.</li>
     * </ul>
     *
     * <p>An annotation type's members read as the methods Java makes them: {@code String name();} is a
     * {@link MemberRecord.Kind#METHOD} whose type is {@code String} and which has no parameters.</p>
     */
    public static List<MemberRecord> membersOf(J.ClassDeclaration declaration) {
        return membersOf(declaration, JavacPositions.of(null));
    }

    /**
     * {@link #membersOf(J.ClassDeclaration)} with the file's positions already parsed (DEC-040 D6).
     *
     * <p>A member carries the line its name sits on and its character span, so a consumer can <em>point</em> at
     * it — and both come from the one javac parse the caller hands in. A member the walk cannot locate records
     * an unknown line and no span, which is a fact a reader can tell from a line rather than a guess (D4).</p>
     */
    public static List<MemberRecord> membersOf(J.ClassDeclaration declaration, JavacPositions positions) {
        if (declaration == null || declaration.getBody() == null) {
            return List.of();
        }
        String owner = declaration.getSimpleName();
        List<MemberRecord> members = new ArrayList<>();
        for (Statement statement : declaration.getBody().getStatements()) {
            if (statement instanceof J.EnumValueSet constants) {
                // An enum's constants arrive as ONE statement holding the comma-separated group, not as one
                // statement per constant (`J.EnumValueSet implements Statement`; `J.EnumValue` does not — which is
                // why the obvious `instanceof J.EnumValue` does not compile). Read through the group so the
                // declaration order is the order a consumer sees.
                for (J.EnumValue constant : constants.getEnums()) {
                    members.add(enumConstantOf(constant, owner, positions));
                }
            } else if (statement instanceof J.VariableDeclarations field) {
                members.addAll(fieldsOf(field, owner, positions));
            } else if (statement instanceof J.MethodDeclaration method) {
                boolean constructor = method.isConstructor();
                MemberRecord.Kind kind = constructor ? MemberRecord.Kind.CONSTRUCTOR : MemberRecord.Kind.METHOD;
                List<MemberParameter> parameters = parametersOf(method);
                members.add(new MemberRecord(method.getSimpleName(), kind,
                        // A constructor declares no return type and its name is the type's, so recording a
                        // type would be recording something the source does not say.
                        constructor ? "" : TreeQueries.typeText(method.getReturnTypeExpression()),
                        parameters,
                        modifiersOf(method.getModifiers()),
                        annotationsOf(method.getLeadingAnnotations(), positions, owner, method.getSimpleName()),
                        // An enum constant's arguments are the one kind-specific list that is not a callable's,
                        // so a method records none (DEC-040 D1).
                        false,
                        // A callable has a body or it does not (DEC-040 D1).
                        throwsOf(method),
                        method.getBody() != null,
                        positions.memberLine(owner, kind.positionKind(), method.getSimpleName(),
                                parameters.size()),
                        positions.memberSpan(owner, kind.positionKind(), method.getSimpleName(),
                                parameters.size())));
            } else if (statement instanceof J.ClassDeclaration nested) {
                members.add(new MemberRecord(nested.getSimpleName(), MemberRecord.Kind.NESTED,
                        nested.getSimpleName(), List.of(), modifiersOf(nested.getModifiers()),
                        annotationsOf(nested.getLeadingAnnotations(), positions, owner, nested.getSimpleName()),
                        // A nested type is none of the kind-specific facts: it takes no arguments, has no
                        // initialiser and throws nothing (DEC-040 D1 keeps each in the kind that owns it).
                        false, List.of(), false,
                        positions.memberLine(owner, MemberRecord.Kind.NESTED.positionKind(),
                                nested.getSimpleName(), 0),
                        positions.memberSpan(owner, MemberRecord.Kind.NESTED.positionKind(),
                                nested.getSimpleName(), 0)));
            }
        }
        return List.copyOf(members);
    }

    /**
     * One field declaration's members: {@code private int a, b;} declares <em>two</em>, and each is its own
     * member, because the question a consumer asks is "is there a field called {@code b}" rather than "what does
     * this statement declare".
     *
     * <p>Both carry the line javac records for their own name, and both carry the <strong>declaration's</strong>
     * span: javac records one span per declaration, keyed by the first declarator's name, and a span that
     * covered only one of two names would be a range that does not hold what it claims to. A range covering the
     * whole statement is the honest one, and it is what a consumer slicing the source would expect.</p>
     */
    private static List<MemberRecord> fieldsOf(J.VariableDeclarations field, String owner,
                                               JavacPositions positions) {
        String type = TreeQueries.typeText(field.getTypeExpression());
        List<String> modifiers = modifiersOf(field.getModifiers());
        String declarationName = field.getVariables().isEmpty() ? ""
                : field.getVariables().get(0).getSimpleName();
        List<TypeAnnotation> annotations = annotationsOf(field.getLeadingAnnotations(), positions, owner,
                declarationName);
        TreeQueries.SourceSpan span = positions.memberSpan(owner, MemberRecord.Kind.FIELD.positionKind(),
                declarationName, 0);
        List<MemberRecord> members = new ArrayList<>(field.getVariables().size());
        for (J.VariableDeclarations.NamedVariable variable : field.getVariables()) {
            members.add(new MemberRecord(variable.getSimpleName(), MemberRecord.Kind.FIELD, type, List.of(),
                    modifiers, annotations,
                    // Whether there is an initialiser, not what it says: the expression is inside the member's
                    // span and recovered by slicing it, because an arbitrary expression has no faithful text form
                    // this table can hold (DEC-040 D2). Per DECLARATOR, so `int a = 0, b;` is one field with and
                    // one without.
                    variable.getInitializer() != null,
                    // A field throws nothing and has no body; those are a callable's facts.
                    List.of(),
                    false,
                    positions.memberLine(owner, MemberRecord.Kind.FIELD.positionKind(),
                            variable.getSimpleName(), 0),
                    span));
        }
        return members;
    }

    /**
     * One enum constant: its name, its own annotations, and its position.
     *
     * <p>An enum constant is not a field of the enum: it is a value the enum declares, and its constructor
     * arguments are the call that makes it (DEC-040 D1). The arguments are <strong>not stored</strong>: they sit
     * inside the constant's own span, and a consumer that needs them slices the declaring file there — the same
     * rule a field's initialiser follows, and the correction that came out of storing one as text and getting a
     * debug dump back (DEC-040 D2).</p>
     *
     * <p>No modifiers: Java gives a constant {@code public static final} whether or not anybody writes it, and
     * recording keywords the source does not say is exactly what this model refuses to do elsewhere.</p>
     */
    private static MemberRecord enumConstantOf(J.EnumValue constant, String owner, JavacPositions positions) {
        String name = constant.getName() == null ? "" : constant.getName().getSimpleName();
        return new MemberRecord(name, MemberRecord.Kind.ENUM_CONSTANT, "", List.of(), List.of(),
                annotationsOf(constant.getAnnotations(), positions, owner, name),
                // A constant's arguments are recovered from its span rather than stored, and it has neither a
                // callable's facts nor a field's initialiser flag.
                false, List.of(), false,
                positions.memberLine(owner, MemberRecord.Kind.ENUM_CONSTANT.positionKind(), name, 0),
                positions.memberSpan(owner, MemberRecord.Kind.ENUM_CONSTANT.positionKind(), name, 0));
    }

    /**
     * A callable's {@code throws} clause as written, in order; empty when it declares none.
     *
     * <p>Recorded because a generator that emits a call must be able to declare what the call throws (DEC-040
     * D1), and because the clause is part of a method's API in a way the compiler keeps only in the class file's
     * attribute — a fact the source states plainly and a runtime reader has to be told.</p>
     */
    public static List<String> throwsOf(J.MethodDeclaration method) {
        if (method == null || method.getThrows() == null || method.getThrows().isEmpty()) {
            return List.of();
        }
        List<String> thrown = new ArrayList<>(method.getThrows().size());
        for (org.openrewrite.java.tree.NameTree named : method.getThrows()) {
            if (named instanceof J.Empty) {
                continue;
            }
            thrown.add(TreeQueries.typeText(named));
        }
        return List.copyOf(thrown);
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
        return relationsOf(declaration, kind, null, null);
    }

    /**
     * {@link #relationsOf(J.ClassDeclaration, String)} with the file's positions, so each relation carries the
     * range its written form sits at (DEC-040 D2/D6).
     *
     * <p>The range is looked up by <strong>clause and ordinal</strong>, never by the written text: javac's printer
     * and the LST's normalise generic commas differently — which is why {@code TreeQueries.typeText} exists — so
     * the text is not a safe key even though both walks go in source order. Ordinals count within a clause, and an
     * interface's or an annotation's whole supertype list counts as {@code extends} on both sides
     * (DEC-030's trap).</p>
     */
    public static List<TypeRelation> relationsOf(J.ClassDeclaration declaration, String kind,
                                                 JavacPositions positions, String owner) {
        if (declaration == null) {
            return List.of();
        }
        boolean interfaceLike = "interface".equals(kind) || "annotation".equals(kind);
        boolean extendsClause = declaration.getExtends() != null;
        List<String> names = TreeQueries.supertypeTexts(declaration);
        List<TypeRelation> relations = new ArrayList<>(names.size());
        int extendsOrdinal = 0;
        int implementsOrdinal = 0;
        for (int i = 0; i < names.size(); i++) {
            // Only the first name can come from an `extends` clause, and only when there is one; everything
            // after it came from `implements`, except on an interface, where the whole list is `extends`.
            boolean fromExtends = interfaceLike || (extendsClause && i == 0);
            // The name is DERIVED from the written form rather than typed in (DEC-040 D2), and the written form
            // itself is not stored: `span` points at it, so `ChildContext<AppContext>` is recovered by slicing
            // the source rather than duplicated here.
            TypeRelation.Kind relationKind = fromExtends
                    ? TypeRelation.Kind.EXTENDS : TypeRelation.Kind.IMPLEMENTS;
            int ordinal = fromExtends ? extendsOrdinal++ : implementsOrdinal++;
            TreeQueries.SourceSpan span = positions == null || owner == null
                    ? null
                    : positions.relationSpan(owner, relationKind.json(), ordinal);
            relations.add(new TypeRelation(withoutTypeArguments(names.get(i)), relationKind, span));
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
