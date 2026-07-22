// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.Set;
import java.util.stream.Collectors;

public class RecordBuilderProcessor {
    private final JavaParser parser;
    private final String indent;

    public RecordBuilderProcessor(JavaParser parser) {
        this(parser, "    "); // Default 4 spaces
    }

    public RecordBuilderProcessor(JavaParser parser, String indent) {
        this.parser = parser;
        this.indent = indent;
    }

    public String getIndent() {
        return indent;
    }

    public boolean process(CompilationUnit cu, int line) {
        RecordDeclaration rd = cu.findAll(RecordDeclaration.class).stream()
                .filter(r -> r.getRange().isPresent() && Math.abs(r.getRange().get().begin.line - line) <= 5)
                .findFirst()
                .orElse(cu.findFirst(RecordDeclaration.class).orElse(null));

        if (rd == null)
            return false;

        updateBuilder(rd);
        return true;
    }

    public void updateBuilder(RecordDeclaration record) {
        String recordName = record.getNameAsString();

        // 1. Ensure 'builder()' static factory exists
        if (record.getMethodsByName("builder").isEmpty()) {
            record.addMethod("builder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                    .setType("Builder")
                    .setBody(parseBlock("{ return new Builder(); }"));
        }

        // 2. Ensure 'toBuilder()' instance method exists
        String toBuilderAssignments = record.getParameters().stream()
                .map(p -> ".%s(this.%s())".formatted(p.getNameAsString(), p.getNameAsString()))
                .collect(Collectors.joining(""));

        BlockStmt toBuilderBody = parseBlock("{ return new Builder()" + toBuilderAssignments + "; }");
        var toBuilderOpt = record.getMethodsByName("toBuilder").stream().filter(m -> m.getParameters().isEmpty())
                .findFirst();
        if (toBuilderOpt.isEmpty()) {
            record.addMethod("toBuilder", Modifier.Keyword.PUBLIC)
                    .setType("Builder")
                    .setBody(toBuilderBody);
        } else {
            toBuilderOpt.get().setBody(toBuilderBody);
        }

        // 3. Find or Create the Builder Class
        ClassOrInterfaceDeclaration builder = record.getMembers().stream()
                .filter(m -> m instanceof ClassOrInterfaceDeclaration cl && cl.getNameAsString().equals("Builder"))
                .map(m -> (ClassOrInterfaceDeclaration) m)
                .findFirst()
                .orElseGet(() -> {
                    ClassOrInterfaceDeclaration newBuilder = new ClassOrInterfaceDeclaration().setName("Builder")
                            .setPublic(true).setStatic(true);
                    record.addMember(newBuilder);
                    return newBuilder;
                });

        Set<String> validParams = record.getParameters().stream().map(p -> p.getNameAsString())
                .collect(Collectors.toSet());

        // Cleanup obsolete
        builder.getFields().stream().filter(
                f -> f.getVariables().size() > 0 && !validParams.contains(f.getVariables().get(0).getNameAsString()))
                .forEach(f -> f.remove());
        builder.getMethods().stream().filter(m -> !m.getNameAsString().equals("build") && m.getParameters().size() == 1
                && !validParams.contains(m.getNameAsString())).forEach(m -> m.remove());

        // 1. Fields (grouped)
        for (var param : record.getParameters()) {
            String name = param.getNameAsString();
            var fieldOpt = builder.getFieldByName(name);
            FieldDeclaration field;
            if (fieldOpt.isEmpty()) {
                field = new FieldDeclaration();
                field.addModifier(Modifier.Keyword.PRIVATE);
                field.addVariable(new com.github.javaparser.ast.body.VariableDeclarator(param.getType(), name));
            } else {
                field = fieldOpt.get();
                field.getVariables().get(0).setType(param.getType());
                field.remove();
            }
            int lastIdx = -1;
            for (int i = 0; i < builder.getMembers().size(); i++) {
                if (builder.getMembers().get(i) instanceof FieldDeclaration)
                    lastIdx = i;
            }
            builder.getMembers().add(lastIdx + 1, field);
        }

        // 2. build() method (after fields)
        String args = record.getParameters().stream().map(p -> p.getNameAsString()).collect(Collectors.joining(", "));
        BlockStmt buildBody = parseBlock("{ return new %s(%s); }".formatted(recordName, args));
        MethodDeclaration buildMethod = builder.getMethodsByName("build").stream()
                .filter(m -> m.getParameters().isEmpty()).findFirst().orElse(null);
        if (buildMethod == null) {
            buildMethod = new MethodDeclaration().setName("build").addModifier(Modifier.Keyword.PUBLIC)
                    .setType(recordName).setBody(buildBody);
        } else {
            buildMethod.setBody(buildBody);
            buildMethod.setType(recordName);
            buildMethod.remove();
        }
        int lastIdx = -1;
        for (int i = 0; i < builder.getMembers().size(); i++) {
            if (builder.getMembers().get(i) instanceof FieldDeclaration)
                lastIdx = i;
        }
        builder.getMembers().add(lastIdx + 1, buildMethod);

        // 3. Setters (grouped at the end)
        for (var param : record.getParameters()) {
            String name = param.getNameAsString();
            var setters = builder.getMethodsByName(name).stream().filter(m -> m.getParameters().size() == 1)
                    .collect(Collectors.toList());
            MethodDeclaration setter;
            if (setters.isEmpty()) {
                setter = new MethodDeclaration().setName(name).addModifier(Modifier.Keyword.PUBLIC)
                        .setType("Builder").addParameter(param.getType(), name)
                        .setBody(parseBlock("{ this.%s = %s; return this; }".formatted(name, name)));
            } else {
                setter = setters.get(0);
                setter.getParameter(0).setType(param.getType());
                setter.setBody(parseBlock("{ this.%s = %s; return this; }".formatted(name, name)));
                setter.remove();
            }
            builder.addMember(setter);
        }
    }

    private BlockStmt parseBlock(String code) {
        ParseResult<BlockStmt> result = parser.parseBlock(code);
        if (result.isSuccessful()) {
            return result.getResult().get();
        }
        throw new RuntimeException("Failed to parse block: " + code + "\n" + result.getProblems());
    }
}
