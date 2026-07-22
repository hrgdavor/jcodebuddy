// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import java.util.List;

/**
 * A dummy tool for testing the Action Engine and Audit System.
 */
public class HelloTool implements ActionTool {
    @Override
    public String getName() {
        return "hello";
    }

    @Override
    public boolean isApplicable(ToolContext context) {
        return true;
    }

    @Override
    public List<FileChange> execute(ToolContext context) {
        // Just adds a comment at the top of the file
        try {
            String original = java.nio.file.Files.readString(context.getFilePath());
            String modified = "// Hello from Java Watch Agent!\n" + original;
            return List.of(new FileChange(context.getFilePath(), modified, ChangeType.CHANGE));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
