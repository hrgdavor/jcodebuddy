// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import hr.hrg.watch2.builder.ClassMemberProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Generates getters and setters for a class's fields.
 *
 * <p>Phase 6: the parsing and the generation both moved to {@link ClassMemberProcessor} in
 * {@code jwa-builder}, which is the module that owns "read a Java type out of source and complete it"
 * and — unlike this one — has tests. What is left here is the tool contract: read the file, complete the
 * class nearest the caret, and report the new text.</p>
 *
 * <p>One behaviour changed with the move, deliberately: the JavaParser version returned
 * {@code cu.toString()}, i.e. the <strong>whole file re-printed</strong>, so a formatting difference
 * anywhere in it became part of the edit. This returns the file's own text with the generated members
 * spliced into the class body, so nothing outside that body moves. And when nothing was missing, it
 * returns no change at all rather than a rewrite that differs only in formatting.</p>
 */
public class AccessorGenerator implements ActionTool {
    private final String name;
    private final boolean getters;
    private final boolean setters;
    private final ClassMemberProcessor processor;

    public AccessorGenerator(String name, boolean getters, boolean setters) {
        this(name, getters, setters, "    ");
    }

    public AccessorGenerator(String name, boolean getters, boolean setters, String indent) {
        this.name = name;
        this.getters = getters;
        this.setters = setters;
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
            String generated = processor.withAccessors(source, target, getters, setters);
            if (generated.equals(source)) {
                // Every accessor was already there: an edit that changes nothing is not a change.
                return List.of();
            }
            return List.of(new FileChange(context.getFilePath(), generated, ChangeType.CHANGE));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
