// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

// The generator SPI is a JCodeBuddy *library* (jcodebuddy-codegen-api), not a project's private
// `project-automation`. This file used to import it from the latter, which meant a JCodeBuddy library
// depended on a driver project's dev-time assistant — the one thing a project-automation module must
// never be part of (AGENTS.md § 1). Promoted so that the rule holds and this class is what it claims to
// be: a generator a tool can hold, with no opinion about whose project it is running in.
import hr.hrg.jcodebuddy.codegen.CodeContext;
import hr.hrg.jcodebuddy.codegen.CodeGenerator;

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
