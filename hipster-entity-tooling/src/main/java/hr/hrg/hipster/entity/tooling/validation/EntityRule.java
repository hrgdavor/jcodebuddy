package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;
import java.util.List;

public interface EntityRule {
    void validate(Path file, String pkg, CompilationUnit cu, List<EntityRulesValidator.ValidationIssue> issues);
}
