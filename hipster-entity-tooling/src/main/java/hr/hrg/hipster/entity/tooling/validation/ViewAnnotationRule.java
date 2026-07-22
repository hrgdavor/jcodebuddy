package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.nio.file.Path;
import java.util.List;

public class ViewAnnotationRule implements EntityRule {

    @Override
    public void validate(Path file, String pkg, CompilationUnit cu, List<EntityRulesValidator.ValidationIssue> issues) {
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isInterface()) {
                continue;
            }
            decl.getAnnotationByName("View").ifPresent(viewAnn -> {
                if (!decl.isInterface()) {
                    issues.add(new EntityRulesValidator.ValidationIssue(file, "@View can only be applied on interfaces: " + decl.getNameAsString()));
                }
                String text = viewAnn.toString();
                if (!text.contains("read") && !text.contains("write")) {
                    issues.add(new EntityRulesValidator.ValidationIssue(file, "@View must declare read and/or write modes: " + decl.getNameAsString()));
                }
            });
        }
    }
}
