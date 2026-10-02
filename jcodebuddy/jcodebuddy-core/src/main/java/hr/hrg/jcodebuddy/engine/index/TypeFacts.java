package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import org.openrewrite.java.tree.J;

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
 */
public record TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                        List<TypeRelation> relations, List<TypeAnnotation> annotations) {

    /**
     * The modifier vocabulary the index records.
     *
     * <p>Deliberately not "every keyword the parser reports": the index answers "what kind of type is
     * this and who can see it", and a keyword outside this set (a type-use modifier, a JVM-only flag)
     * would be a fact this table has no contract for. Adding one here is a format change and belongs in
     * DEC-029.</p>
     */
    public static final Set<String> KEYWORDS = Set.of(
            "public", "protected", "private", "abstract", "static", "final",
            "sealed", "non-sealed", "strictfp");

    public TypeFacts {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        relations = relations == null ? List.of() : List.copyOf(relations);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
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
        this(fqn, kind, modifiers, enclosing, line, depth, relations, List.of());
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
                annotations);
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
                annotationsOf(declaration));
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
        if (declaration == null) {
            return List.of();
        }
        List<J.Annotation> annotations = declaration.getLeadingAnnotations();
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
            relations.add(new TypeRelation(withoutTypeArguments(names.get(i)),
                    fromExtends ? TypeRelation.Kind.EXTENDS : TypeRelation.Kind.IMPLEMENTS));
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
        List<String> keywords = new ArrayList<>();
        for (J.Modifier modifier : declaration.getModifiers()) {
            String keyword = keywordOf(modifier);
            if (KEYWORDS.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        Collections.sort(keywords);
        return keywords;
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
