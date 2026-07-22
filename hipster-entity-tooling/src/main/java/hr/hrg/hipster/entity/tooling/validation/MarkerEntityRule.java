package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.nio.file.Path;
import java.util.List;

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
            if (!decl.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().equals("EntityBase"))) {
                issues.add(new EntityRulesValidator.ValidationIssue(file, "Entity marker interface must extend EntityBase: " + name));
            }
            if (!decl.getMembers().stream().filter(m -> m.isMethodDeclaration()).findAny().isEmpty()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file, "Entity marker interface should not declare domain methods: " + name));
            }
            if (!pkg.startsWith("hr.hrg.hipster.entity.")) {
                issues.add(new EntityRulesValidator.ValidationIssue(file, "Entity package must be under hr.hrg.hipster.entity: " + pkg));
            }
        }
    }
}
