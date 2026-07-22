// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import hr.hrg.watch2.core.CodeEdit;
import hr.hrg.watch2.core.TransformationResult;
import hr.hrg.watch2.builder.api.GenerateBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * High-level engine for Record Builder transformations.
 * Returns surgical edits instead of full file content.
 */
public class BuilderTransformationEngine {
    private final JavaParser parser;
    private final String indent;

    public BuilderTransformationEngine(String indent) {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        config.setLexicalPreservationEnabled(true);
        this.parser = new JavaParser(config);
        this.indent = indent;
    }

    public TransformationResult generate(String uri, String source, int line) {
        var result = parser.parse(source);
        if (!result.isSuccessful()) {
            return TransformationResult.empty();
        }

        CompilationUnit cu = result.getResult().get();
        LexicalPreservingPrinter.setup(cu);

        RecordBuilderProcessor processor = new RecordBuilderProcessor(parser, indent);

        // Find the record at or near the line
        RecordDeclaration rd = cu.findAll(RecordDeclaration.class).stream()
                .filter(r -> r.getRange().isPresent() && Math.abs(r.getRange().get().begin.line - line) <= 5)
                .findFirst()
                .orElse(cu.findFirst(RecordDeclaration.class).orElse(null));

        if (rd == null)
            return TransformationResult.empty();

        processor.updateBuilder(rd);

        List<CodeEdit> edits = new ArrayList<>();

        // Target the record specifically for the edit
        String newRecordCode = LexicalPreservingPrinter.print(rd);

        // Second pass: fix indentation
        String intermediate = newRecordCode;
        String[] lines = intermediate.split("\\R");
        StringBuilder sbFinal = new StringBuilder();
        sbFinal = new StringBuilder();
        boolean insideBuilder = false;
        for (String l : lines) {
            String trimmed = l.trim();
            if (trimmed.startsWith("public static class Builder")) {
                insideBuilder = true;
                sbFinal.append(indent).append(trimmed).append(System.lineSeparator());
            } else if (trimmed.equals("}")) {
                if (insideBuilder) {
                    sbFinal.append(indent).append(trimmed).append(System.lineSeparator());
                    insideBuilder = false;
                } else {
                    sbFinal.append(trimmed).append(System.lineSeparator());
                }
            } else if (trimmed.isEmpty()) {
                sbFinal.append(System.lineSeparator());
            } else {
                if (insideBuilder) {
                    sbFinal.append(indent).append(indent).append(trimmed).append(System.lineSeparator());
                } else if ((trimmed.startsWith("public") || trimmed.startsWith("private") || trimmed.startsWith("//"))
                        && !trimmed.startsWith("public record")) {
                    sbFinal.append(indent).append(trimmed).append(System.lineSeparator());
                } else {
                    sbFinal.append(l).append(System.lineSeparator());
                }
            }
        }

        String finalizedCode = sbFinal.toString().trim();
        rd.getRange().ifPresent(range -> {
            edits.add(new CodeEdit(
                    uri,
                    range.begin.line, range.begin.column,
                    range.end.line, range.end.column,
                    finalizedCode));
        });

        return new TransformationResult(edits, Map.of());
    }
}
