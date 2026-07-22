// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.ui;

import hr.hrg.watch2.agent.core.AuditManager;
import hr.hrg.watch2.agent.tools.ActionTool;
import hr.hrg.watch2.agent.ToolSetAgent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents an action that has been calculated and snapshotted but not yet
 * applied (unless applyFirst is true).
 */
public class PendingAction {
    private final String toolName;
    private final ToolSetAgent agent;
    private final ActionTool.ToolContext context;
    private final AuditManager.AuditSession session;
    private final List<String> suggestions;
    private boolean applied = false;

    public PendingAction(String toolName, ToolSetAgent agent, ActionTool.ToolContext context,
            AuditManager.AuditSession session) {
        this(toolName, agent, context, session, null);
    }

    public PendingAction(String toolName, ToolSetAgent agent, ActionTool.ToolContext context,
            AuditManager.AuditSession session, List<String> suggestions) {
        this.toolName = toolName;
        this.agent = agent;
        this.context = context;
        this.session = session;
        this.suggestions = suggestions;
    }

    public String toolName() {
        return toolName;
    }

    public ToolSetAgent agent() {
        return agent;
    }

    public ActionTool.ToolContext context() {
        return context;
    }

    public AuditManager.AuditSession session() {
        return session;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public boolean isDiscovery() {
        return suggestions != null && !suggestions.isEmpty();
    }

    public void doApply() throws IOException {
        if (!applied && session != null) {
            session.apply();
            for (AuditManager.FileEntry entry : session.getEntries()) {
                Path path = agent.getRoot().resolve(entry.file);
                agent.getCache().update(entry.file, entry.newHash,
                        Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0);
            }
            agent.getCache().save(false);
            applied = true;
        }
    }

    public void doRevert() throws IOException {
        if (applied) {
            session.revert();
            for (AuditManager.FileEntry entry : session.getEntries()) {
                Path path = agent.getRoot().resolve(entry.file);
                agent.getCache().update(entry.file, entry.oldHash,
                        Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0);
            }
            agent.getCache().save(false);
            applied = false;
        }
    }

    public void accept() throws IOException {
        doApply();
        session.finalizeSession("Accepted tool: " + toolName);
    }

    public void reject() throws IOException {
        doRevert();
        session.finalizeSession("Rejected tool: " + toolName);
    }

    public boolean isApplied() {
        return applied;
    }

    public List<AuditManager.FileEntry> getChanges() {
        return session == null ? List.of() : session.getEntries();
    }
}
