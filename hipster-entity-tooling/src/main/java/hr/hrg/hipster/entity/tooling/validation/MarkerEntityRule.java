package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.hipster.entity.tooling.TreeQueries;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.util.List;

/**
 * The entity marker's shape.
 *
 * <p>Two things the first version got wrong, both of which only became visible once the validator was
 * actually run over the tree (task 1.13 was recorded as done while this rule had no production call
 * site):</p>
 *
 * <ol>
 *   <li><strong>A marker is not required to be named {@code *Entity}.</strong> The example's marker is
 *       {@code hr.hrg.hipster.entityexample.person.entity.Person} and the payment-method family's root
 *       is {@code PaymentMethod}. What defines a marker is that it reaches {@code EntityBase} and that
 *       the package's views derive from it (G8 rule 0).</li>
 *   <li><strong>Its package is not required to be under {@code hr.hrg.hipster.entity}.</strong> That is
 *       a convention of this repository's own modules; the new-project guide deliberately shows a
 *       consuming project using {@code com.example.person.entity}, so the check would have invalidated
 *       every adopter's tree. The convention still holds <em>here</em>, and it is asserted where it can
 *       be — {@code EntityRulesValidatorTest} and the example's own sources — rather than imposed on
 *       anyone who runs the tool.</li>
 * </ol>
 *
 * <p>What remains is checkable from one file: an interface named {@code *Entity} that extends
 * {@code EntityBase} is the package's marker, and a marker <strong>must not declare domain
 * methods</strong> — accessors belong on the views, and one declared on the marker appears in every
 * view's field list.</p>
 *
 * <h3>Phase 6</h3>
 * <p>Three API changes, each of which would otherwise be silent:</p>
 * <ul>
 *   <li>Interfaces are {@link TreeQueries#interfaces} rather than
 *       {@code findAll(ClassOrInterfaceDeclaration.class)} plus an {@code isInterface()} filter —
 *       without the kind test, {@link J.ClassDeclaration} also matches records, enums and
 *       annotations.</li>
 *   <li><strong>An interface's {@code extends} clause is held in {@code getImplements()}, not
 *       {@code getExtends()}.</strong> This was established by measurement, not assumption: for
 *       {@code interface PersonEntity extends EntityBase<String>}, {@code getExtends()} is
 *       {@code null} and {@code getImplements()} is {@code [EntityBase<String>]}. The old JavaParser
 *       code read {@code getExtendedTypes()}, and reading {@code getExtends()} here would silently
 *       stop recognising every marker in the tree — the rule would find nothing to check and report
 *       clean, which is the worst possible failure for a validator. {@link
 *       TreeQueries#supertypeNames} reads both clauses for exactly this reason.</li>
 *   <li>A supertype is a {@link J.Identifier} when bare but a {@link J.ParameterizedType} when it has
 *       type arguments, so the name comes from {@link TreeQueries#simpleTypeName} rather than from a
 *       single accessor.</li>
 * </ul>
 */
public class MarkerEntityRule implements EntityRule {

    @Override
    public void validate(Path file, String pkg, J.CompilationUnit cu,
                         List<EntityRulesValidator.ValidationIssue> issues) {
        for (J.ClassDeclaration decl : TreeQueries.interfaces(cu)) {
            String name = decl.getSimpleName();
            if (!name.endsWith("Entity")) {
                continue;
            }
            boolean extendsEntityBase = TreeQueries.supertypeNames(decl).stream()
                    .anyMatch("EntityBase"::equals);
            if (!extendsEntityBase) {
                // Either this interface derives its marker-ness through another interface, or it is
                // unrelated and merely ends in `Entity`. A single file cannot tell those apart, and a
                // false report on a correct tree is how a validator gets switched off — so it stays
                // silent here.
                continue;
            }
            for (J.MethodDeclaration method : TreeQueries.methodsOf(decl)) {
                if (TreeQueries.isDefaultMethod(method) || TreeQueries.isStaticMethod(method)) {
                    continue;
                }
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "Entity marker interface should not declare domain methods: " + name
                                + " declares " + method.getSimpleName() + "()"));
            }
        }
    }
}
