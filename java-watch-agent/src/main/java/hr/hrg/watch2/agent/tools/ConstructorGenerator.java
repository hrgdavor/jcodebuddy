// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import hr.hrg.watch2.builder.ClassMemberProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Generates an all-arguments and a no-argument constructor for a class.
 *
 * <p>Phase 6: parsing and generation moved to {@link ClassMemberProcessor} (see
 * {@link AccessorGenerator} for why, and for the one deliberate behaviour change — the file is spliced
 * rather than re-printed). Each constructor is still skipped when one of that arity already exists, so a
 * second run leaves the class alone.</p>
 */
public class ConstructorGenerator implements ActionTool {
    private final String name;
    private final ClassMemberProcessor processor;

    /** Registered by {@code WatchAgent} under the name its {@code getName()} has always returned. */
    public ConstructorGenerator() {
        this("constructor");
    }

    public ConstructorGenerator(String name) {
        this(name, "    ");
    }

    public ConstructorGenerator(String name, String indent) {
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
            String generated = processor.withConstructors(source, target);
            if (generated.equals(source)) {
                return List.of();
            }
            return List.of(new FileChange(context.getFilePath(), generated, ChangeType.CHANGE));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
