// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.core;

import hr.hrg.watch2.agent.tools.ActionTool;
import hr.hrg.watch2.agent.tools.ToolRegistry;
import hr.hrg.watch2.agent.ui.PendingAction;
import hr.hrg.watch2.agent.ToolSetAgent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Coordinates tools and audit sessions.
 */
public class ActionEngine {
    private static final Logger log = LoggerFactory.getLogger(ActionEngine.class);

    private final ToolRegistry registry;
    private final AuditManager auditManager;

    public ActionEngine(ToolRegistry registry, AuditManager auditManager) {
        this.registry = registry;
        this.auditManager = auditManager;
    }

    public PendingAction runTool(String toolName, ToolSetAgent agent, ActionTool.ToolContext context)
            throws IOException {
        ActionTool tool = registry.getTool(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));

        if (!tool.isApplicable(context)) {
            log.warn("Tool {} is not applicable in this context", toolName);
            return null;
        }

        AuditManager.AuditSession session = auditManager.startSession(toolName);
        try {
            List<ActionTool.FileChange> changes = tool.execute(context);

            for (ActionTool.FileChange change : changes) {
                if (change.type() == ActionTool.ChangeType.CHANGE) {
                    session.recordBefore(change.path());
                    session.recordAfter(change.path(), change.content());
                }
                // TODO: Handle ADD and DELETE
            }

            if (changes.isEmpty()) {
                session.revert();
                return null;
            }

            return new PendingAction(toolName, agent, context, session);

        } catch (Exception e) {
            log.error("Error executing tool {}", toolName, e);
            throw new IOException("Tool execution failed", e);
        }
    }
}
