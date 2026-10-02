package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.hipster.entity.tooling.TreeQueries;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.util.List;

/**
 * The {@code *Auditable} convention: an addon interface belongs to an entity module, not to a
 * consuming application.
 *
 * <p>The rule tests {@code pkg.startsWith("hr.hrg.hipster.entity")}, which is this repository's own
 * convention rather than a general one — the new-project guide deliberately shows an adopter using
 * its own package, and {@code MarkerEntityRule} records the same reasoning at length. It stays
 * because it is what this tree's own modules are held to.</p>
 *
 * <h3>Phase 6</h3>
 * <p>{@code findAll(ClassOrInterfaceDeclaration.class)} plus an {@code isInterface()} filter becomes
 * {@link TreeQueries#interfaces}. The two are not interchangeable: {@link J.ClassDeclaration} also
 * covers records, enums and annotations, so a port that kept only the {@code findAll} half would
 * silently start reporting {@code *Auditable} records.</p>
 */
public class AuditableRule implements EntityRule {

    @Override
    public void validate(Path file, String pkg, J.CompilationUnit cu,
                         List<EntityRulesValidator.ValidationIssue> issues) {
        for (J.ClassDeclaration decl : TreeQueries.interfaces(cu)) {
            String name = decl.getSimpleName();
            if (!name.endsWith("Auditable")) {
                continue;
            }
            if (!pkg.startsWith("hr.hrg.hipster.entity")) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "Auditable interface should be in an entity module or package: " + name));
            }
        }
    }
}
