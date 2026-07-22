// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import hr.hrg.watch2.agent.core.JavaParserFactory;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates constructors (No-args and all-args) for a class.
 */
public class ConstructorGenerator implements ActionTool {
    @Override
    public String getName() {
        return "constructors";
    }

    @Override
    public boolean isApplicable(ToolContext context) {
        return context.getFilePath().toString().endsWith(".java");
    }

    @Override
    public List<FileChange> execute(ToolContext context) {
        JavaParser parser = JavaParserFactory.getParser();
        try {
            ParseResult<CompilationUnit> result = parser.parse(context.getFilePath());
            if (!result.isSuccessful()) {
                throw new RuntimeException("Parse failed: " + result.getProblems());
            }
            CompilationUnit cu = result.getResult().get();
            int line = context.getLine();

            ClassOrInterfaceDeclaration cid = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getRange().isPresent() && Math.abs(c.getRange().get().begin.line - line) <= 5)
                    .findFirst()
                    .orElse(cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null));

            if (cid == null)
                return List.of();

            String className = cid.getNameAsString();
            List<FieldDeclaration> fields = cid.getFields();

            // All-args constructor
            boolean hasAllArgs = cid.getConstructors().stream()
                    .anyMatch(c -> c.getParameters().size() == fields.size());
            if (!hasAllArgs && !fields.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("    public ").append(className).append("(");

                String params = fields.stream()
                        .map(f -> f.getVariable(0).getTypeAsString() + " " + f.getVariable(0).getNameAsString())
                        .collect(Collectors.joining(", "));

                sb.append(params).append(") {\n");
                for (FieldDeclaration field : fields) {
                    String name = field.getVariable(0).getNameAsString();
                    sb.append("        this.").append(name).append(" = ").append(name).append(";\n");
                }
                sb.append("    }\n");

                ParseResult<BodyDeclaration<?>> memResult1 = parser.parseBodyDeclaration(sb.toString());
                if (memResult1.isSuccessful()) {
                    cid.addMember(memResult1.getResult().get());
                }
            }

            // No-args constructor (if not already present)
            boolean hasNoArgs = cid.getConstructors().stream().anyMatch(c -> c.getParameters().isEmpty());
            if (!hasNoArgs) {
                ParseResult<BodyDeclaration<?>> memResult2 = parser
                        .parseBodyDeclaration("    public " + className + "() {}\n");
                if (memResult2.isSuccessful()) {
                    cid.addMember(memResult2.getResult().get());
                }
            }

            return List.of(new FileChange(context.getFilePath(), cu.toString(), ChangeType.CHANGE));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
