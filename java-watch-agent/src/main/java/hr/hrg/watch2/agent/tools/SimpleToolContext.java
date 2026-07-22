// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import java.nio.file.Path;

public record SimpleToolContext(Path root, Path file, int line, String indent) implements ActionTool.ToolContext {
    public SimpleToolContext(Path root, Path file, int line) {
        this(root, file, line, "    ");
    }

    public SimpleToolContext(Path root, Path file) {
        this(root, file, 1);
    }

    @Override
    public Path getRootPath() {
        return root;
    }

    @Override
    public Path getFilePath() {
        return file;
    }

    @Override
    public int getLine() {
        return line;
    }

    @Override
    public String getIndent() {
        return indent;
    }
}
