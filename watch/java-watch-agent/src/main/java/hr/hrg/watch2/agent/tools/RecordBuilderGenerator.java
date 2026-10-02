// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import java.nio.file.Files;
import java.util.List;
import hr.hrg.watch2.builder.BuilderTransformationEngine;
import hr.hrg.watch2.core.CodeEditApplier;
import hr.hrg.watch2.core.TransformationResult;

/**
 * Generates or updates a fluent builder for a Java Record.
 * Delegates to the shared BuilderTransformationEngine for surgical updates.
 */
public class RecordBuilderGenerator implements ActionTool {

    public RecordBuilderGenerator() {
    }

    @Override
    public String getName() {
        return "record_builder";
    }

    @Override
    public boolean isApplicable(ToolContext context) {
        return context.getFilePath().toString().endsWith(".java");
    }

    @Override
    public List<FileChange> execute(ToolContext context) {
        try {
            int line = context.getLine();
            String indentStr = context.getIndent();
            String uri = context.getFilePath().toUri().toString();

            BuilderTransformationEngine engine = new BuilderTransformationEngine(indentStr);
            String code = Files.readString(context.getFilePath());

            TransformationResult result = engine.generate(uri, code, line);

            if (result.edits().isEmpty()) {
                return List.of();
            }

            // Apply surgical edits to the code
            String output = CodeEditApplier.applyStyles(code, result.edits());

            return List.of(new FileChange(context.getFilePath(), output, ChangeType.CHANGE));
        } catch (Exception e) {
            throw new RuntimeException("Error generating record builder", e);
        }
    }
}
