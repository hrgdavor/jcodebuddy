package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EntityRulesValidator {

    public static class ValidationIssue {
        public final Path file;
        public final String message;

        public ValidationIssue(Path file, String message) {
            this.file = file;
            this.message = message;
        }

        @Override
        public String toString() {
            return file + ": " + message;
        }
    }

    private final List<EntityRule> rules;

    public EntityRulesValidator() {
        this.rules = Arrays.asList(
                new MarkerEntityRule(),
                new ViewInterfaceRule(),
                new ViewAnnotationRule(),
                new AuditableRule()
        );
    }

    public List<ValidationIssue> validate(Path moduleRoot) throws IOException {
        List<ValidationIssue> issues = new ArrayList<>();

        Files.walk(moduleRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(file -> {
                    try {
                        String source = Files.readString(file);
                        ParseResult<CompilationUnit> parse = new JavaParser().parse(source);
                        CompilationUnit cu = parse.getResult().orElse(null);
                        if (cu == null) {
                            issues.add(new ValidationIssue(file, "Could not parse Java source"));
                            return;
                        }

                        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("<default>");
                        for (EntityRule rule : rules) {
                            rule.validate(file, pkg, cu, issues);
                        }

                    } catch (IOException e) {
                        issues.add(new ValidationIssue(file, "IO error: " + e.getMessage()));
                    }
                });
        return issues;
    }
}

