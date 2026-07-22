// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import hr.hrg.watch2.agent.config.AgentConfig;
import hr.hrg.watch2.agent.core.*;
import hr.hrg.watch2.agent.tools.*;
import hr.hrg.watch2.agent.ui.*;

/**
 * Orchestrates all agent components for a specific ToolSet.
 */
public class ToolSetAgent {
    private final Path root;
    private final AgentConfig.ToolSet config;
    private final MetadataCache cache;
    private final AuditManager auditManager;
    private final ActionEngine engine;
    private final hr.hrg.watch2.core.FileFilter filter;
    private final ContextualAnalyzer analyzer;
    private final DependencyTracker dependencyTracker;
    private Consumer<PendingAction> actionCallback;

    public ToolSetAgent(Path root, AgentConfig.ToolSet config, ToolRegistry globalRegistry) {
        this.root = root;
        this.config = config;
        this.cache = new MetadataCache(root, config.getName());
        this.auditManager = new AuditManager(root, config.getName());
        this.engine = new ActionEngine(globalRegistry, auditManager);
        this.filter = new hr.hrg.watch2.core.FileFilter(root, config.getInclude(), config.getExclude());
        this.analyzer = new ContextualAnalyzer();
        this.dependencyTracker = new DependencyTracker();
    }

    public void setActionCallback(Consumer<PendingAction> callback) {
        this.actionCallback = callback;
    }

    public void load() throws IOException {
        cache.load();
    }

    public void scan() throws IOException {
        cache.resetVisited();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName().toString();
                if (name.equals(".git") || name.equals("target") || name.equals(".watch")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                processFile(file, attrs, true);
                return FileVisitResult.CONTINUE;
            }
        });
        cache.save(true); // Cleanup unvisited during full scan
    }

    public void onFileChange(Path path) throws IOException {
        processFile(path, Files.readAttributes(path, BasicFileAttributes.class));
    }

    public void processFile(Path path, BasicFileAttributes attrs) throws IOException {
        processFile(path, attrs, false);
    }

    public void processFile(Path path, BasicFileAttributes attrs, boolean force) throws IOException {
        String filename = path.getFileName().toString();
        if (path.toString().contains(".watch") || filename.equals(".git") || filename.equals("target"))
            return;

        if (!filter.shouldInclude(path))
            return;

        String relPath = root.relativize(path).toString().replace('\\', '/');
        cache.markVisited(relPath);

        boolean isChanged = cache.hasChanged(path);
        if (force || isChanged) {
            String checksum = cache.calculateChecksum(path);
            long mtime = attrs.lastModifiedTime().toMillis();
            cache.update(relPath, checksum, mtime);
            cache.save(false); // No cleanup during individual change

            if (isChanged) {
                System.out.println("[" + config.getName() + "] Change detected: " + relPath);
            }

            // Perform Discovery
            ContextualAnalyzer.Trigger trigger = analyzer.findTrigger(path).orElse(null);
            if (trigger != null) {
                if (trigger.isDiscovery()) {
                    List<String> suggestions = analyzer.suggestTools(path, trigger.line());
                    List<String> activeSuggestions = suggestions.stream()
                            .filter(s -> config.getTools().contains(s))
                            .collect(Collectors.toList());

                    if (!activeSuggestions.isEmpty() && actionCallback != null) {
                        SimpleToolContext context = new SimpleToolContext(root, path, trigger.line(),
                                config.getResolvedIndentString());
                        PendingAction pa = new PendingAction("discovery", this, (ActionTool.ToolContext) context,
                                (AuditManager.AuditSession) null, activeSuggestions);
                        actionCallback.accept(pa);
                    }
                } else {
                    String toolName = trigger.toolName();
                    System.out.println(
                            "[" + config.getName() + "] Trigger found in " + path.getFileName() + ": " + toolName
                                    + " at line " + trigger.line());

                    // Only trigger if the tool is in our active list
                    if (config.getTools().contains(toolName)) {
                        SimpleToolContext context = new SimpleToolContext(root, path, trigger.line(),
                                config.getResolvedIndentString());
                        try {
                            PendingAction pa = engine.runTool(toolName, this, context);
                            if (pa != null && actionCallback != null) {
                                actionCallback.accept(pa);
                            }
                        } catch (IOException e) {
                            System.err.println("Error running tool " + toolName + ": " + e.getMessage());
                        }
                    }
                }
            }
            // Update Dependencies & Notify Dependents
            List<ContextualAnalyzer.Watch> watches = analyzer.findWatches(path);
            List<Path> targetPaths = new ArrayList<>();
            for (ContextualAnalyzer.Watch w : watches) {
                try {
                    targetPaths.add(root.resolve(w.target()).toAbsolutePath().normalize());
                } catch (Exception e) {
                    System.err.println(
                            "Error resolving watch target '" + w.target() + "' in " + path + ": " + e.getMessage());
                }
            }
            dependencyTracker.setDependencies(path, targetPaths);

            triggerDependents(path);
        }
    }

    private void triggerDependents(Path changedFile) {
        java.util.Set<Path> dependents = dependencyTracker.getDependents(changedFile.toAbsolutePath().normalize());
        for (Path dependent : dependents) {
            System.out.println("[" + config.getName() + "] Dependent triggered: " + root.relativize(dependent)
                    + " because of " + root.relativize(changedFile));
            try {
                // Force a re-process of the dependent file
                processFile(dependent, Files.readAttributes(dependent, BasicFileAttributes.class), true);
            } catch (IOException e) {
                System.err.println("Error re-processing dependent " + dependent + ": " + e.getMessage());
            }
        }
    }

    public String getName() {
        return config.getName();
    }

    public ActionEngine getEngine() {
        return engine;
    }

    public Path getRoot() {
        return root;
    }

    public ContextualAnalyzer getAnalyzer() {
        return analyzer;
    }

    public MetadataCache getCache() {
        return cache;
    }
}
