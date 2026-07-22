package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.nio.file.Path;
import java.util.List;

public class AuditableRule implements EntityRule {

    @Override
    public void validate(Path file, String pkg, CompilationUnit cu, List<EntityRulesValidator.ValidationIssue> issues) {
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isInterface()) {
                continue;
            }
            if (decl.getNameAsString().endsWith("Auditable")) {
                if (!pkg.startsWith("hr.hrg.hipster.entity")) {
                    issues.add(new EntityRulesValidator.ValidationIssue(file, "Auditable interface should be in an entity module or package: " + decl.getNameAsString()));
                }
            }
        }
    }
}
