// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.watchtools;

import java.nio.file.Files;
import java.util.List;

import hr.hrg.watch2.agent.tools.ActionTool;
import hr.hrg.jcodebuddy.builder.BuilderTransformationEngine;
import hr.hrg.watch2.core.CodeEditApplier;
import hr.hrg.watch2.core.TransformationResult;

/**
 * Generates or updates a fluent builder for a Java record, as a tool the watcher can run: it drives
 * {@code jwa-builder}'s {@link BuilderTransformationEngine} to read the type out of source and complete it,
 * then returns the edited text as one {@link ActionTool.FileChange}.
 *
 * <p><strong>Why this class lives here and not in {@code java-watch-agent}.</strong> It was in the agent, and
 * that made a `watch/*` module depend on `jwa-builder` — which the boundary forbids: `java-watch*` must not
 * know about Jackson, OpenRewrite or anything else from this workspace (DEC-038's amendment, plan step 3.0s).
 * The tool is JCodeBuddy work, so it moved to a JCodeBuddy library that <em>depends on</em> the watcher rather
 * than the other way round.</p>
 *
 * <p><strong>How it is wired, and why nothing discovers it.</strong> The agent's own launcher registers the
 * tools it knows about; this one is registered by whoever launches with JCodeBuddy code actions, by calling
 * something like:</p>
 *
 * <pre>{@code
 * registry.register(new RecordBuilderGenerator());
 * }</pre>
 *
 * <p>That is deliberate rather than a {@code ServiceLoader} registry or a {@code META-INF/services} file:
 * AGENTS.md § 1 rejects wiring whose contents are invisible in the committed source, and a registry that
 * discovers its own tools is exactly that. A consequence is recorded rather than hidden — the agent's default
 * tool set no longer contains {@code record_builder}, because the agent may not name this class; a launcher
 * that wants it registers it, visibly.</p>
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
