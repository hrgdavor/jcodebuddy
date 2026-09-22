// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import hr.hrg.watch2.builder.ClassMemberProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Generates a nested {@code <Class>Builder} and its {@code builder()} factory for a class.
 *
 * <p>Phase 6: parsing and generation moved to {@link ClassMemberProcessor} (see
 * {@link AccessorGenerator} for why, and for the one deliberate behaviour change — the file is spliced
 * rather than re-printed). The idempotence the JavaParser version got from looking for an existing
 * {@code builder()} or {@code Builder} type is preserved: a class that already has either is returned
 * unchanged, and an unchanged text produces no {@link FileChange}.</p>
 */
public class BuilderGenerator implements ActionTool {
    private final String name;
    private final ClassMemberProcessor processor;

    /** Registered by {@code WatchAgent} under the name its {@code getName()} has always returned. */
    public BuilderGenerator() {
        this("builder");
    }

    public BuilderGenerator(String name) {
        this(name, "    ");
    }

    public BuilderGenerator(String name, String indent) {
        this.name = name;
        this.processor = new ClassMemberProcessor(indent);
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
        try {
            String source = Files.readString(context.getFilePath());
            ClassMemberProcessor.Target target = processor.target(source, context.getLine());
            if (target == null) {
                return List.of();
            }
            String generated = processor.withBuilder(source, target);
            if (generated.equals(source)) {
                return List.of();
            }
            return List.of(new FileChange(context.getFilePath(), generated, ChangeType.CHANGE));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
