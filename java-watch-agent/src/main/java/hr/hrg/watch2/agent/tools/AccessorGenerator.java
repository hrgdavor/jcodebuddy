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
 * Generates getters and setters for fields in a class.
 */
public class AccessorGenerator implements ActionTool {
    private final String name;
    private final boolean getters;
    private final boolean setters;

    public AccessorGenerator(String name, boolean getters, boolean setters) {
        this.name = name;
        this.getters = getters;
        this.setters = setters;
    }

    @Override
    public String getName() {
        return name;
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

            List<FieldDeclaration> fields = cid.getFields();

            for (FieldDeclaration field : fields) {
                String fieldName = field.getVariable(0).getNameAsString();
                String fieldType = field.getVariable(0).getTypeAsString();
                String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);

                if (getters) {
                    String getterName = "get" + capitalized;
                    if (fieldType.equalsIgnoreCase("boolean")) {
                        getterName = "is" + capitalized;
                    }

                    final String finalGetterName = getterName;
                    boolean exists = cid.getMethodsByName(finalGetterName).stream()
                            .anyMatch(m -> m.getParameters().isEmpty());

                    if (!exists) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("    public ").append(fieldType).append(" ").append(getterName).append("() {\n");
                        sb.append("        return ").append(fieldName).append(";\n");
                        sb.append("    }\n");
                        ParseResult<BodyDeclaration<?>> memResult = parser.parseBodyDeclaration(sb.toString());
                        if (memResult.isSuccessful()) {
                            cid.addMember(memResult.getResult().get());
                        }
                    }
                }

                if (setters && !field.isFinal()) {
                    String setterName = "set" + capitalized;
                    final String finalSetterName = setterName;
                    boolean exists = cid.getMethodsByName(finalSetterName).stream()
                            .anyMatch(m -> m.getParameters().size() == 1);

                    if (!exists) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("    public void ").append(setterName).append("(").append(fieldType).append(" ")
                                .append(fieldName).append(") {\n");
                        sb.append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
                        sb.append("     }\n");
                        ParseResult<BodyDeclaration<?>> memResult = parser.parseBodyDeclaration(sb.toString());
                        if (memResult.isSuccessful()) {
                            cid.addMember(memResult.getResult().get());
                        }
                    }
                }
            }

            return List.of(new FileChange(context.getFilePath(), cu.toString(), ChangeType.CHANGE));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
