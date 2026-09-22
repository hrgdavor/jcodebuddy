// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import hr.hrg.jcodebuddy.automation.CodeContext;
import hr.hrg.jcodebuddy.automation.CodeGenerator;

import java.nio.file.Path;
import java.util.List;

// `FileChange` and `ToolContext` are declared INSIDE `ActionTool`, and a nested type is not visible to
// another type in the same package: an unqualified use does not resolve, and this file did not compile
// until these two imports were added. Recorded here because the symptom was six "cannot find symbol"
// errors that read like a missing dependency rather than two missing imports.
import hr.hrg.watch2.agent.tools.ActionTool.FileChange;
import hr.hrg.watch2.agent.tools.ActionTool.ToolContext;

public class ActionToolAdapter implements CodeGenerator<List<FileChange>> {

    private final ActionTool delegate;

    public ActionToolAdapter(ActionTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.getName();
    }

    @Override
    public boolean isApplicable(CodeContext context) {
        ToolContext toolContext = toToolContext(context);
        return delegate.isApplicable(toolContext);
    }

    @Override
    public List<FileChange> generate(CodeContext context) {
        ToolContext toolContext = toToolContext(context);
        return delegate.execute(toolContext);
    }

    private ToolContext toToolContext(CodeContext context) {
        return new SimpleToolContext(
                context.getRootPath(),
                context.getFilePath(),
                context.getLine(),
                context.getIndent()
        );
    }
}
