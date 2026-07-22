package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.nio.file.Path;
import java.util.List;

public class ViewInterfaceRule implements EntityRule {

    @Override
    public void validate(Path file, String pkg, CompilationUnit cu, List<EntityRulesValidator.ValidationIssue> issues) {
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isInterface()) {
                continue;
            }

            boolean extendsEntity = decl.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().endsWith("Entity"));

            boolean isCandidate = extendsEntity
                    || decl.getNameAsString().endsWith("Summary")
                    || decl.getNameAsString().endsWith("Details")
                    || decl.getNameAsString().endsWith("Update")
                    || decl.getNameAsString().endsWith("Form")
                    || decl.getNameAsString().endsWith("Dto")
                    || decl.isAnnotationPresent("View");

            if (!isCandidate) {
                continue;
            }

            if (!extendsEntity) {
                issues.add(new EntityRulesValidator.ValidationIssue(file, "View interface should extend an entity marker interface: " + decl.getNameAsString()));
            }

            if (!decl.getNameAsString().endsWith("Summary")
                    && !decl.getNameAsString().endsWith("Details")
                    && !decl.getNameAsString().endsWith("Update")
                    && !decl.getNameAsString().endsWith("Form")
                    && !decl.getNameAsString().endsWith("Dto")) {
                issues.add(new EntityRulesValidator.ValidationIssue(file, "View interface name should follow EntitySummary/EntityDetails/EntityUpdate/EntityForm/EntityDto: " + decl.getNameAsString()));
            }
        }
    }
}
