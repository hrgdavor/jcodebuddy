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

/**
 * Generates a simple fluent builder for a class.
 */
public class BuilderGenerator implements ActionTool {
    @Override
    public String getName() {
        return "builder";
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

            // Find class near line
            ClassOrInterfaceDeclaration cid = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getRange().isPresent() && Math.abs(c.getRange().get().begin.line - line) <= 5)
                    .findFirst()
                    .orElse(cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null));

            if (cid == null)
                return List.of();

            String className = cid.getNameAsString();
            String builderName = className + "Builder";

            // If builder() already exists, skip
            if (cid.getMethodsByName("builder").stream().anyMatch(m -> m.isStatic() && m.getParameters().isEmpty())) {
                return List.of();
            }
            // If Builder class already exists, skip
            if (cid.getMembers().stream().anyMatch(
                    m -> m instanceof ClassOrInterfaceDeclaration cl && cl.getNameAsString().equals(builderName))) {
                return List.of();
            }

            // Generate Builder methods (simplified)
            List<FieldDeclaration> fields = cid.getFields();

            StringBuilder factoryMethod = new StringBuilder();
            factoryMethod.append("    public static ").append(builderName).append(" builder() { return new ")
                    .append(builderName).append("(); }\n");

            ParseResult<BodyDeclaration<?>> fmResult = parser.parseBodyDeclaration(factoryMethod.toString());
            if (fmResult.isSuccessful()) {
                cid.addMember(fmResult.getResult().get());
            }

            StringBuilder builderClass = new StringBuilder();
            builderClass.append("    public static class ").append(builderName).append(" {\n");

            for (FieldDeclaration field : fields) {
                String name = field.getVariable(0).getNameAsString();
                String type = field.getVariable(0).getTypeAsString();
                builderClass.append("        private ").append(type).append(" ").append(name).append(";\n");
            }

            for (FieldDeclaration field : fields) {
                String name = field.getVariable(0).getNameAsString();
                String type = field.getVariable(0).getTypeAsString();
                builderClass.append("\n        public ").append(builderName).append(" ").append(name).append("(")
                        .append(type).append(" ").append(name).append(") {\n");
                builderClass.append("            this.").append(name).append(" = ").append(name).append(";\n");
                builderClass.append("            return this;\n");
                builderClass.append("        }\n");
            }

            builderClass.append("\n        public ").append(className).append(" build() {\n");
            builderClass.append("            return new ").append(className).append("();\n");
            builderClass.append("        }\n");
            builderClass.append("    }\n");

            // Insert into class
            ParseResult<BodyDeclaration<?>> bcResult = parser.parseBodyDeclaration(builderClass.toString());
            if (bcResult.isSuccessful()) {
                cid.addMember(bcResult.getResult().get());
            }

            return List.of(new FileChange(context.getFilePath(), cu.toString(), ChangeType.CHANGE));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
