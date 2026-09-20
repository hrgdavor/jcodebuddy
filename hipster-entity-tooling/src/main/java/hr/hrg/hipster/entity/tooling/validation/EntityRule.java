package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One convention the tooling checks.
 *
 * <p>There are two entry points because two kinds of rule exist, and conflating them is what let the
 * view conventions rot:</p>
 * <ul>
 *   <li>{@link #validate} — a rule that can decide from <strong>one file</strong>: the annotation
 *       shapes, the header of a field enum, whether a marker declares domain methods.</li>
 *   <li>{@link #validateAll} — a rule that needs the <strong>whole tree</strong>, because the question
 *       it asks is about inheritance ("is this interface a view?") or about a relationship between two
 *       files ("does this addon exist?"). {@link ViewInterfaceRule} is the first such rule: checking
 *       per file forced it to judge a view by the literal name of its direct parent, which no
 *       interface in this repository satisfies, so it reported 19 issues about 19 correct
 *       interfaces.</li>
 * </ul>
 *
 * <p>The default {@code validateAll} simply fans {@link #validate} out over the parsed units, so
 * existing rules need no change and cannot silently stop running.</p>
 */
public interface EntityRule {

    /**
     * Checks one file. Rules that need the whole tree implement {@link #validateAll} instead and leave
     * this unimplemented — which the default below makes explicit rather than silent.
     *
     * <p>A default that did <em>nothing</em> would be the worst of both worlds: the registry would
     * call it, no check would run, and nothing would say so. A default that <em>throws</em> makes a
     * tree-wide rule being invoked file-by-file a loud error at the first attempt.</p>
     */
    default void validate(Path file, String pkg, CompilationUnit cu,
                          List<EntityRulesValidator.ValidationIssue> issues) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " is a tree-wide rule: it implements validateAll(Map, List) and has no per-file check");
    }

    /**
     * Checks a whole source set, with every file already parsed.
     *
     * @param units every parsed {@code .java} file under the validated root, keyed by path
     */
    default void validateAll(Map<Path, CompilationUnit> units,
                             List<EntityRulesValidator.ValidationIssue> issues) {
        units.forEach((file, cu) -> validate(file,
                cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("<default>"),
                cu, issues));
    }
}
