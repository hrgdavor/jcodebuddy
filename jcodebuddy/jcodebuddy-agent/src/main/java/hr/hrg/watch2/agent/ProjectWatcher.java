// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent;

import hr.hrg.jcodebuddy.watch.FileWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Monitors the project root and dispatches events to relevant ToolSetAgents.
 */
public class ProjectWatcher {
    private static final Logger log = LoggerFactory.getLogger(ProjectWatcher.class);

    private final Path root;
    private final List<ToolSetAgent> agents;
    private final FileWatch internalWatcher;

    public ProjectWatcher(Path root, List<ToolSetAgent> agents) {
        this.root = root;
        this.agents = agents;

        // A "pass-all" filter for the root. Each agent will filter internally. The state and policy stay in the
        // agents; this is only the event source (jcodebuddy-watch).
        PathFilter rootFilter = PathFilter.of(List.of("**/*"),
                List.of(".git/**", "target/**", ".watch/**", ".jcodebuddy/**",
                        "**/.git/**", "**/target/**", "**/.watch/**", "**/.jcodebuddy/**"));

        this.internalWatcher = new FileWatch(root, rootFilter, 500, this::dispatch);
    }

    public void start() throws IOException {
        internalWatcher.start();
        log.info("Project watcher started on {}", root);
    }

    public void stop() {
        internalWatcher.stop();
        log.info("Project watcher stopped.");
    }

    private void dispatch(WatchBatch batch) {
        for (String relative : batch.changed()) {
            Path path = root.resolve(relative);
            for (ToolSetAgent agent : agents) {
                try {
                    agent.onFileChange(path);
                } catch (Exception e) {
                    log.error("Error dispatching change to agent {}", agent.getName(), e);
                }
            }
        }
    }
}
