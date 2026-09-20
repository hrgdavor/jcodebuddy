package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

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
 */
public class MarkerEntityRule implements EntityRule {

    @Override
    public void validate(Path file, String pkg, CompilationUnit cu, List<EntityRulesValidator.ValidationIssue> issues) {
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isInterface()) {
                continue;
            }
            String name = decl.getNameAsString();
            if (!name.endsWith("Entity")) {
                continue;
            }
            boolean extendsEntityBase = decl.getExtendedTypes().stream()
                    .anyMatch(t -> t.getNameAsString().equals("EntityBase"));
            if (!extendsEntityBase) {
                // Either this interface derives its marker-ness through another interface, or it is
                // unrelated and merely ends in `Entity`. A single file cannot tell those apart, and a
                // false report on a correct tree is how a validator gets switched off — so it stays
                // silent here.
                continue;
            }
            for (MethodDeclaration method : decl.getMethods()) {
                if (method.isDefault() || method.isStatic()) {
                    continue;
                }
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "Entity marker interface should not declare domain methods: " + name
                                + " declares " + method.getNameAsString() + "()"));
            }
        }
    }
}
