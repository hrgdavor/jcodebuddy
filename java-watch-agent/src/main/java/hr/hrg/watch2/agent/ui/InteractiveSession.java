// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

import hr.hrg.watch2.agent.config.AgentConfig;
import hr.hrg.watch2.agent.core.AuditManager;
import hr.hrg.watch2.agent.tools.ActionTool;
import hr.hrg.watch2.agent.tools.ActionTool.ToolContext;
import hr.hrg.watch2.agent.ui.PendingAction;
import java.util.stream.Collectors;
import hr.hrg.watch2.agent.ToolSetAgent;

/**
 * Terminal-based UI for reviewing and applying actions.
 */
public class InteractiveSession {
    private final PendingActionManager manager;
    private final Scanner scanner = new Scanner(System.in);
    private final List<ToolSetAgent> agents;
    private final AgentConfig config;
    private boolean running = true;

    public InteractiveSession(List<ToolSetAgent> agents, AgentConfig config, PendingActionManager manager) {
        this.agents = agents;
        this.config = config;
        this.manager = manager;
    }

    public void addAction(PendingAction action) {
        if (action != null) {
            if (config.isApplyFirst() && !action.isDiscovery()) {
                try {
                    action.doApply();
                } catch (IOException e) {
                    System.err.println("Error auto-applying action " + action.toolName() + ": " + e.getMessage());
                }
            }
            manager.addAction(action);
            if (action.isDiscovery()) {
                String tools = action.getSuggestions().stream().collect(Collectors.joining(", "));
                System.out.println("\n[!] New DISCOVERY pending: [" + tools + "]");
            } else {
                System.out.println("\n[!] New action pending: " + action.toolName());
            }
        }
    }

    public void startLoop() {
        System.out.println("Java Watch Agent Interactive Session Started.");
        System.out.println("Type 'help' for commands.");

        while (running) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty())
                continue;

            String[] parts = input.split("\\s+");
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "list":
                    listActions();
                    break;
                case "diff":
                    showDiff(parts);
                    break;
                case "accept":
                    acceptAction(parts);
                    break;
                case "reject":
                    rejectAction(parts);
                    break;
                case "jump":
                    jumpToSource(parts);
                    break;
                case "copy":
                    copyLocation(parts);
                    break;
                case "help":
                    printHelp();
                    break;
                case "rebuild-cache":
                    rebuildCache(parts);
                    break;
                case "exit":
                case "quit":
                    running = false;
                    break;
                default:
                    System.out.println("Unknown command: " + cmd);
            }
        }
    }

    private void listActions() {
        List<PendingAction> actions = manager.getActions();
        if (actions.isEmpty()) {
            System.out.println("No pending actions.");
            return;
        }
        System.out.println("Pending Actions:");
        for (int i = 0; i < actions.size(); i++) {
            PendingAction action = actions.get(i);
            if (action.isDiscovery()) {
                System.out.printf("[%d] DISCOVERY at %s:%d (Options: %s)\n",
                        i, action.context().getFilePath().getFileName(),
                        action.context().getLine(), action.getSuggestions());
            } else {
                System.out.printf("[%d] %s (%d files affected)\n", i, action.toolName(), action.getChanges().size());
            }
        }
    }

    private void showDiff(String[] parts) {
        int index = getIndex(parts);
        if (index == -1)
            return;

        PendingAction action = manager.getAction(index);
        if (action == null)
            return;
        System.out.println("--- Diff for " + action.toolName() + " ---");
        for (AuditManager.FileEntry entry : action.getChanges()) {
            System.out.println("File: " + entry.file);
            try {
                Path before = action.session().getSessionDir().resolve("before").resolve(entry.file);
                Path after = action.session().getSessionDir().resolve("after").resolve(entry.file);

                List<String> bLines = Files.exists(before) ? Files.readAllLines(before) : List.of();
                List<String> aLines = Files.exists(after) ? Files.readAllLines(after) : List.of();

                System.out.println("Original lines: " + bLines.size() + ", New lines: " + aLines.size());
                if (!bLines.equals(aLines)) {
                    System.out.println("[Change detected]");
                }
            } catch (IOException e) {
                System.out.println("Error reading diff: " + e.getMessage());
            }
        }
        System.out.println("----------------------------");
    }

    private void acceptAction(String[] parts) {
        int index = getIndex(parts);
        if (index == -1)
            return;

        PendingAction action = manager.getAction(index);
        if (action == null)
            return;

        try {
            if (action.isDiscovery()) {
                if (parts.length < 3) {
                    System.out.println("Error: Please specify which tool to run.");
                    System.out.println("Available options: " + action.getSuggestions());
                    System.out.println("Usage: accept <index> <toolName>");
                    return;
                }
                String toolName = parts[2];
                if (!action.getSuggestions().contains(toolName)) {
                    System.out.println("Error: " + toolName + " is not a suggested tool for this discovery.");
                    System.out.println("Available options: " + action.getSuggestions());
                    return;
                }
                // Run the tool
                PendingAction newAction = action.agent().getEngine().runTool(toolName, action.agent(),
                        (ActionTool.ToolContext) action.context());
                if (newAction != null) {
                    // Replace the discovery action with the real one?
                    // For now, just add it to the manager and remove discovery?
                    manager.removeAction(index);
                    manager.addAction(newAction);
                    System.out.println("Tool " + toolName + " executed. New pending action created.");
                }
            } else {
                manager.accept(index);
                System.out.println("Action accepted and applied.");
            }
        } catch (Exception e) {
            System.out.println("Failed to process action: " + e.getMessage());
        }
    }

    private void rejectAction(String[] parts) {
        int index = getIndex(parts);
        if (index == -1)
            return;

        try {
            manager.reject(index);
            System.out.println("Action rejected.");
        } catch (IOException e) {
            System.out.println("Failed to reject action: " + e.getMessage());
        }
    }

    private int getIndex(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Please specify an index.");
            return -1;
        }
        try {
            int index = Integer.parseInt(parts[1]);
            if (index < 0 || index >= manager.getActions().size()) {
                System.out.println("Invalid index.");
                return -1;
            }
            return index;
        } catch (NumberFormatException e) {
            System.out.println("Invalid number format.");
            return -1;
        }
    }

    private void printHelp() {
        System.out.println("Available commands:");
        System.out.println(" list                     - List pending actions");
        System.out.println(" diff <idx>               - Show changes for an action");
        System.out.println(" accept <idx>             - Apply an action");
        System.out.println(" reject <idx>             - Discard an action");
        System.out.println(" jump <idx>               - Open editor at action trigger site");
        System.out.println(" copy <idx>               - Copy 'file:line' to clipboard");
        System.out.println(" rebuild-cache [toolset]  - Full re-scan of the project and update metadata mappings");
        System.out.println(" exit/quit                - Close the agent");
    }

    private void jumpToSource(String[] parts) {
        int index = getIndex(parts);
        if (index == -1)
            return;

        PendingAction action = manager.getAction(index);
        if (action == null)
            return;
        ActionTool.ToolContext context = action.context();
        String file = context.getFilePath().toAbsolutePath().toString();
        int line = context.getLine();

        String command = config.getEditorCommand();
        if (command == null || command.isEmpty()) {
            // Default to 'code --goto' if not set, or print if nothing works
            command = "code --goto %f:%l";
        }

        String finalized = command.replace("%f", file).replace("%l", String.valueOf(line));
        System.out.println("Executing: " + finalized);

        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", finalized);
            pb.inheritIO();
            pb.start();
        } catch (IOException e) {
            System.err.println("Failed to execute jump command: " + e.getMessage());
        }
    }

    private void copyLocation(String[] parts) {
        int index = getIndex(parts);
        if (index == -1)
            return;

        PendingAction action = manager.getAction(index);
        if (action == null)
            return;
        ActionTool.ToolContext context = action.context();
        String location = context.getFilePath().toAbsolutePath() + ":" + context.getLine();

        try {
            StringSelection selection = new StringSelection(location);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
            System.out.println("Copied to clipboard: " + location);
        } catch (Exception e) {
            System.err.println("Failed to copy to clipboard: " + e.getMessage());
            System.out.println("Location: " + location);
        }
    }

    private void rebuildCache(String[] parts) {
        if (parts.length > 1) {
            String target = parts[1];
            for (ToolSetAgent agent : agents) {
                if (agent.getName().equalsIgnoreCase(target)) {
                    System.out.println("Rebuilding cache for toolset: " + agent.getName());
                    try {
                        agent.scan();
                        System.out.println("Cache rebuild complete for " + agent.getName());
                    } catch (IOException e) {
                        System.err.println("Error rebuilding cache: " + e.getMessage());
                    }
                    return;
                }
            }
            System.out.println("ToolSet not found: " + target);
        } else {
            System.out.println("Rebuilding all caches...");
            for (ToolSetAgent agent : agents) {
                try {
                    agent.scan();
                } catch (IOException e) {
                    System.err.println("Error rebuilding cache for " + agent.getName() + ": " + e.getMessage());
                }
            }
            System.out.println("All caches rebuilt.");
        }
    }
}
