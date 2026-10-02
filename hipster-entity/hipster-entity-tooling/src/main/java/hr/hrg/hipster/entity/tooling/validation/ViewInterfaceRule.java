package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.hipster.entity.tooling.TreeQueries;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * View-interface conventions (plan.dsflash § 4.5/G8's predicate, expressed as a rule).
 *
 * <p><strong>Rewritten three times, and the third version is deliberately the narrowest. The history
 * is the reason for the shape, so it is kept:</strong></p>
 *
 * <ol>
 *   <li><strong>Version 1</strong> judged each file alone and required a view's <em>directly</em>
 *       extended type to be literally named {@code *Entity}. Nothing in the project has that shape;
 *       run over the committed example it produced <strong>19 issues about 19 correct
 *       interfaces</strong>. That measurement is also the evidence that task 1.13 ("wire the validator
 *       into the generator") had never been done — {@code EntityRulesValidator} was instantiated only
 *       from tests.</li>
 *   <li><strong>Version 2</strong> tried to infer the <em>marker</em> role from the inheritance graph
 *       ("a marker is a root nothing extends"). Every subtlety of a tree-wide index — nested
 *       declarations sharing a simple name, supertype resolution — produced a confidently wrong answer
 *       about a correct tree, and each fix moved the error rather than removing it.</li>
 *   <li><strong>Version 3</strong> (this one) keeps only checks that need no guess about another
 *       interface's role.</li>
 * </ol>
 *
 * <p>What it reports:</p>
 * <ul>
 *   <li>{@code view_does_not_derive_from_marker} — an interface named like a view ({@code *Summary},
 *       {@code *Details}, {@code *Update}, {@code *Form}, {@code *Dto}) that neither carries
 *       {@code @View} nor reaches {@code EntityBase}. The generator emits nothing for it, which is
 *       either a forgotten marker or a name collision; both are worth one line and the same fix.</li>
 * </ul>
 *
 * <p><strong>What it stopped reporting, and why that is not laziness.</strong> Version 3 also checked
 * that a view's name ends in one of the five suffixes, and run over the committed example that check
 * produced <strong>six findings about six correct views</strong>: the four
 * {@code *PaymentMethod} subclasses of the polymorphic family, {@code PersonAuditable} (an addon
 * field-source that is a view in its own right), and the doc-sample {@code person/iface/Person}
 * (which the generator deliberately excludes from the {@code packages} filter). The suffix convention
 * is real — the project's guides state it, and the generator derives every artifact name from the view
 * name — but it is a <em>convention for views the generator generates</em>, and a per-file rule cannot
 * tell which those are. A rule that reports six correct classes is a rule the team stops reading, and
 * F-43's lesson cuts both ways: a guard that cannot be right is worse than no guard.</p>
 *
 * <p><strong>The gap this leaves, stated so it is not mistaken for a bug:</strong> naming is not
 * enforced for a view the generator reaches by marker-derivation with no annotation. Enforcing it
 * correctly needs the generator's discovery result — the view list its pass computes — which is what
 * {@code ViewDiscoveryTest} pins against the real tree, and what a future iteration of this rule should
 * consume instead of re-deriving.</p>
 *
 * <p>It also does not dictate a package: the new-project guide shows a consuming project using its own
 * ({@code com.example.person.entity}), so a rule that rejected it would contradict the guide.</p>
 *
 * <h3>Phase 6</h3>
 * <p>The index is built from {@link TreeQueries#interfaces} and
 * {@link TreeQueries#supertypeNames}, which absorb two API splits that a mechanical port gets wrong
 * silently: one {@link J.ClassDeclaration} covers five kinds, and a supertype is a
 * {@link J.Identifier} when bare but a {@link J.ParameterizedType} when it has type arguments. The
 * rule's meaning is unchanged — only the names are indexed, and still with no symbol solver.</p>
 */
public class ViewInterfaceRule implements EntityRule {

    /** The suffixes the project uses for a view's name (naming conventions § 2). */
    private static final Set<String> VIEW_SUFFIXES =
            Set.of("Summary", "Details", "Update", "Form", "Dto");

    /** The framework surfaces that are not views at all (G8 rule 1). */
    private static final Set<String> SURFACE_TYPES =
            Set.of("ViewReader", "ViewWriter", "ViewChangeTracking");

    @Override
    public void validateAll(Map<Path, J.CompilationUnit> units,
                            List<EntityRulesValidator.ValidationIssue> issues) {
        // Only the `extends` names are indexed, merged per simple name so a chain that crosses packages
        // still resolves without a symbol solver. No role inference happens on top of it.
        Map<String, Set<String>> parents = new HashMap<>();
        for (Map.Entry<Path, J.CompilationUnit> unit : units.entrySet()) {
            for (J.ClassDeclaration decl : TreeQueries.typeDeclarations(unit.getValue())) {
                Set<String> extended = parents.computeIfAbsent(decl.getSimpleName(), __ -> new HashSet<>());
                // supertypeNames, not getExtends(): an interface's `extends` clause lives in
                // getImplements() (verified — see TreeQueries.supertypeNames). Reading getExtends()
                // here would index nothing for any interface, so every view's chain would look
                // empty and the rule would report correct views as unreachable.
                extended.addAll(TreeQueries.supertypeNames(decl));
            }
        }

        for (Map.Entry<Path, J.CompilationUnit> unit : units.entrySet()) {
            for (J.ClassDeclaration decl : TreeQueries.typeDeclarations(unit.getValue())) {
                check(decl, unit.getKey(), parents, issues);
            }
        }
    }

    private void check(J.ClassDeclaration decl, Path file, Map<String, Set<String>> parents,
                       List<EntityRulesValidator.ValidationIssue> issues) {
        // Kind test first: an interface only. The old guard was `!decl.isInterface()`, and the
        // enclosing findAll matched one JavaParser type; here the type covers every kind, so
        // dropping this test would make the rule judge records and enums as views.
        if (!TreeQueries.isKind(decl, J.ClassDeclaration.Kind.Type.Interface) || isSurface(decl)) {
            return;
        }
        String name = decl.getSimpleName();
        if (TreeQueries.hasAnnotation(decl, "View")) {
            return; // an explicit view declaration: the author knows, and naming is not enforced here
        }
        if (!reachesEntityBase(name, parents, new HashSet<>())
                && VIEW_SUFFIXES.stream().anyMatch(name::endsWith)) {
            // Named like a view, but nothing about it says "entity view": no annotation, and no
            // EntityBase anywhere in its chain. Either a marker is missing or the name is a
            // coincidence; the generator emits nothing either way, and silence is the worst answer.
            issues.add(new EntityRulesValidator.ValidationIssue(file,
                    "view_does_not_derive_from_marker: " + name + " is named like a view but neither "
                            + "reaches EntityBase nor carries @View, so the generator treats it as an "
                            + "ordinary interface and emits nothing for it. Derive it from the entity "
                            + "marker or annotate it with @View."));
        }
    }

    /** Whether {@code name}'s {@code extends} chain reaches {@code EntityBase}/{@code Identifiable}. */
    private static boolean reachesEntityBase(String name, Map<String, Set<String>> parents, Set<String> seen) {
        if (!seen.add(name)) {
            return false;
        }
        for (String parent : parents.getOrDefault(name, Set.of())) {
            if ("EntityBase".equals(parent) || "Identifiable".equals(parent)) {
                return true;
            }
            if (reachesEntityBase(parent, parents, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSurface(J.ClassDeclaration decl) {
        return TreeQueries.supertypeNames(decl).stream().anyMatch(SURFACE_TYPES::contains);
    }
}
