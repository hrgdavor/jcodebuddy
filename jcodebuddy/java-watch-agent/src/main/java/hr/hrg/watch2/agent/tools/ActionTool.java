// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for all automated transformation tools.
 */
public interface ActionTool {

    /**
     * Unique name of the tool (e.g., "builder", "getters").
     */
    String getName();

    /**
     * Returns true if this tool is applicable to the given context.
     * 
     * @param context Analysis context (AST info, cursor position, etc.)
     */
    boolean isApplicable(ToolContext context);

    /**
     * Executes the transformation.
     * 
     * @param context Analysis context.
     * @return List of proposed changes.
     */
    List<FileChange> execute(ToolContext context);

    /**
     * Metadata about a single file change.
     */
    record FileChange(Path path, String content, ChangeType type) {
    }

    enum ChangeType {
        ADD, CHANGE, DELETE
    }

    /**
     * Context provided to the tool for analysis and execution.
     */
    interface ToolContext {
        Path getRootPath();

        Path getFilePath();

        int getLine();

        String getIndent();
        // Additional methods for AST nodes, options, etc. will be added later
    }
}
