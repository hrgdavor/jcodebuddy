package hr.hrg.hipster.entity.tooling.index;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;

import hr.hrg.hipster.entity.tooling.MetadataLocations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
 *                  from {@link MetadataLocations#kindOf(TypeDeclaration)} so there is exactly one kind
 *                  resolver in the tooling
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
     * <p>Deliberately not "every keyword JavaParser reports": the index answers "what kind of type is
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

    /** The facts of one declaration, with its FQN derived from the unit's package and its parents. */
    public static TypeFacts of(TypeDeclaration<?> declaration) {
        List<String> enclosingChain = enclosingNames(declaration);
        String packageName = packageOf(declaration);
        String simpleName = declaration.getNameAsString();

        List<String> chain = new ArrayList<>(enclosingChain);
        chain.add(simpleName);
        String fqn = packageName.isEmpty() ? String.join(".", chain) : packageName + "." + String.join(".", chain);
        String enclosing = enclosingChain.isEmpty()
                ? null
                : (packageName.isEmpty()
                        ? String.join(".", enclosingChain)
                        : packageName + "." + String.join(".", enclosingChain));

        return new TypeFacts(fqn, MetadataLocations.kindOf(declaration), modifiersOf(declaration), enclosing,
                declaration.getName().getBegin().map(position -> position.line).orElse(-1),
                enclosingChain.size());
    }

    /** The decl's package, or {@code ""} when the unit has no package declaration. */
    public static String packageOf(TypeDeclaration<?> declaration) {
        Optional<CompilationUnit> unit = declaration.findCompilationUnit();
        return unit.flatMap(CompilationUnit::getPackageDeclaration)
                .map(pd -> pd.getNameAsString())
                .orElse("");
    }

    /**
     * The names of this declaration's enclosing types, outermost first.
     *
     * <p>Walked through {@link Node#getParentNode()} rather than through JavaParser's symbol solver:
     * there is no classpath at generation time, and the parent chain is all an FQN needs.</p>
     */
    private static List<String> enclosingNames(TypeDeclaration<?> declaration) {
        List<String> names = new ArrayList<>();
        Node cursor = declaration.getParentNode().orElse(null);
        while (cursor != null) {
            if (cursor instanceof TypeDeclaration<?> parent) {
                names.add(0, parent.getNameAsString());
            }
            cursor = cursor.getParentNode().orElse(null);
        }
        return names;
    }

    /**
     * The declaration's keywords, filtered to {@link #KEYWORDS} and sorted.
     *
     * <p>Read from {@link Modifier#asString()} rather than from the enum constant, so a keyword
     * JavaParser models as two words ({@code non-sealed}) is spelled the way the source spells it and
     * the way this table's contract spells it.</p>
     */
    private static List<String> modifiersOf(TypeDeclaration<?> declaration) {
        List<String> keywords = new ArrayList<>();
        for (Modifier modifier : declaration.getModifiers()) {
            String keyword = modifier.getKeyword().asString();
            if (KEYWORDS.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        Collections.sort(keywords);
        return keywords;
    }
}
