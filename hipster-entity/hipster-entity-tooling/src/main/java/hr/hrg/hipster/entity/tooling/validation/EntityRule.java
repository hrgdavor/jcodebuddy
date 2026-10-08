package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import org.openrewrite.java.tree.J;

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
 *
 * <h3>Phase 6: the unit type moved</h3>
 * <p>{@code validate} and {@code validateAll} now pass an OpenRewrite {@link J.CompilationUnit}
 * instead of a JavaParser {@code CompilationUnit}, because the read that produces them is
 * {@link SourceReader} and it is the single place the tooling parses. Two consequences a rule author
 * must know:</p>
 * <ul>
 *   <li><strong>There is no {@code findAll}.</strong> A rule that needs nodes asks
 *       {@link hr.hrg.jcodebuddy.engine.source.TreeQueries}, which exists so a dozen rules do not each
 *       carry the same visitor boilerplate.</li>
 *   <li><strong>One class covers five kinds.</strong> {@link J.ClassDeclaration} is classes,
 *       interfaces, records, enums <em>and</em> annotations; the kind is a
 *       {@link J.ClassDeclaration#getKind()} test, never an {@code instanceof}. A rule that drops the
 *       test still compiles and silently starts matching records.</li>
 * </ul>
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
    default void validate(Path file, String pkg, J.CompilationUnit cu,
                          List<EntityRulesValidator.ValidationIssue> issues) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " is a tree-wide rule: it implements validateAll(Map, List) and has no per-file check");
    }

    /**
     * Checks a whole source set, with every file already parsed.
     *
     * @param units every parsed {@code .java} file under the validated root, keyed by path
     */
    default void validateAll(Map<Path, J.CompilationUnit> units,
                             List<EntityRulesValidator.ValidationIssue> issues) {
        units.forEach((file, cu) -> {
            String pkg = TreeQueries.packageName(cu);
            validate(file, pkg.isEmpty() ? "<default>" : pkg, cu, issues);
        });
    }

    /**
     * What a violation of this rule <strong>means</strong>, which is what decides whether a strict pass fails on it
     * (plan step 6.3; the maintainer's decision of 2026-10-08).
     *
     * <p>The distinction is not severity-as-taste, it is <em>what breaks</em>:</p>
     * <ul>
     *   <li>{@link #CONTRACT} — breaking the rule makes the generated model <strong>wrong</strong>: a marker that
     *       declares domain methods is not a marker, and a field enum out of ledger order breaks the ordinals stored
     *       data already carries. A {@code STRICT} pass fails on these <em>before</em> writing anything.</li>
     *   <li>{@link #CONVENTION} — breaking the rule is a choice a project may legitimately make differently: a name, a
     *       parent, a package. Those are <strong>always reported and never fatal</strong>, in every policy, because a
     *       build that fails on taste is a build people work around.</li>
     * </ul>
     *
     * <p>The maintainer named the two ends of this split — *"the core entity contract"* and *"the view hierarchy naming
     * rule"* — and asked for it to be decided per rule; the same question ("does breaking it make the model wrong?")
     * classifies the other three, and every rule states its own answer where a reader of that rule will see it.</p>
     */
    enum Nature {
        CONTRACT,
        CONVENTION
    }

    /** This rule's nature, declared by the rule itself so the decision lives where the rule does. */
    Nature nature();
}
