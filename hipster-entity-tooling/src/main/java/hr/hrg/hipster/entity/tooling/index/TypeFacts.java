package hr.hrg.hipster.entity.tooling.index;

import hr.hrg.hipster.entity.tooling.MetadataLocations;
import hr.hrg.hipster.entity.tooling.TreeQueries;
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
 */
public record TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth) {

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
                enclosingNames == null ? 0 : enclosingNames.size());
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
                MetadataLocations.kindOf(declaration), modifiersOf(declaration),
                // The enclosing chain is the line lookup's key half: `Shape` and `Shape.Circle` differ
                // only by it, and a lookup missing it answers the outer declaration with the inner
                // declaration's line.
                TreeQueries.lineOfChained(declaration, enclosingChain, source));
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
