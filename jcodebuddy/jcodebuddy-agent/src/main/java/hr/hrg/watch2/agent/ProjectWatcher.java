// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent;

import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Monitors the project root and dispatches events to relevant ToolSetAgents.
 *
 * <p>The event source is the <strong>fs-meta Zig daemon</strong>, not a local {@code WatchService}: the socket that
 * serves this root is found from the daemon's own host state ({@link FsMetaDaemonDiscovery}) and subscribed with
 * {@link FsMetaDaemonWatch} at {@link FsMetaClient#LATEST} — the agent starts from the tree as it is now, so no prior
 * state has to be claimed. The daemon batches, so the agent carries no debounce window of its own.</p>
 *
 * <p>No daemon is not an error. {@link #start()} then starts nothing, logs the exact
 * {@link FsMetaDaemonDiscovery.Attach#problem()} telling the user which {@code fsmeta} commands to run, and leaves the
 * watcher inactive ({@link #live()} false). An agent that refused to start because nobody had started a watcher would
 * be worse than one that runs without live events, and {@link #stop()} stays safe either way.</p>
 *
 * <p>The filter is a pass-all one for the root: each agent filters internally with its own toolset includes and
 * excludes. The state and policy stay in the agents; this class is only the event source.</p>
 */
public class ProjectWatcher {
    private static final Logger log = LoggerFactory.getLogger(ProjectWatcher.class);

    private final Path root;
    private final List<ToolSetAgent> agents;
    private final FsMetaDaemonDiscovery.Attach attach;
    /** The daemon subscription, or null when no daemon serves the root. */
    private final FsMetaDaemonWatch daemonWatch;

    private volatile boolean live;
    private volatile String attachProblem = "";

    /** Finds the daemon serving {@code root} from this process's environment and platform. */
    public ProjectWatcher(Path root, List<ToolSetAgent> agents) {
        this(root, agents, FsMetaDaemonDiscovery.discover(root));
    }

    /** The injectable-discovery form, for a test that points discovery at its own host state. */
    ProjectWatcher(Path root, List<ToolSetAgent> agents, FsMetaDaemonDiscovery.Attach attach) {
        if (root == null) {
            throw new IllegalArgumentException("a root is required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.agents = agents == null ? List.of() : List.copyOf(agents);
        this.attach = attach == null
                ? new FsMetaDaemonDiscovery.Attach(null, "discovery did not run")
                : attach;

        // A "pass-all" filter for the root. Each agent will filter internally. The state and policy stay in the
        // agents; this is only the event source (jcodebuddy-watch). The daemon's own state directory is excluded:
        // its trio is the daemon's business and must never look like a source edit.
        PathFilter rootFilter = PathFilter.of(List.of("**/*"),
                List.of(".git/**", "target/**", ".watch/**", ".jcodebuddy/**", ".fs-meta/**",
                        "**/.git/**", "**/target/**", "**/.watch/**", "**/.jcodebuddy/**", "**/.fs-meta/**"));

        this.daemonWatch = this.attach.attached()
                ? new FsMetaDaemonWatch(rootFilter, this.attach.location().socket(), FsMetaClient.LATEST,
                        this::dispatch)
                : null;
        this.attachProblem = this.attach.attached()
                ? "the fs-meta daemon has not been subscribed yet (call start())"
                : this.attach.problem();
    }

    /**
     * Attaches to the daemon serving the root and starts dispatching batches asynchronously.
     *
     * <p>An unusable daemon socket is reported through {@link #attachProblem()} and logged, never thrown: the agent
     * still runs its commands and tools, it simply receives no live events. The {@code IOException} in the signature
     * is retained for source compatibility; attaching is the only step that could have thrown it, and it does not.</p>
     */
    public void start() throws IOException {
        if (daemonWatch == null) {
            live = false;
            attachProblem = attach.problem();
            log.warn("Project watcher is not watching {}: {}", root, attachProblem);
            return;
        }
        try {
            daemonWatch.start();
            live = daemonWatch.isRunning();
            if (live) {
                log.info("Project watcher attached to the fs-meta daemon socket {}", daemonWatch.socket());
            } else {
                attachProblem = "the fsmeta daemon socket " + daemonWatch.socket()
                        + " could not be subscribed: " + daemonWatch.lastError();
                log.warn("Project watcher is not watching {}: {}", root, attachProblem);
            }
        } catch (IOException failed) {
            live = false;
            attachProblem = "cannot attach to the fsmeta daemon at " + daemonWatch.socket() + ": "
                    + failed.getMessage();
            log.warn("Project watcher is not watching {}: {}", root, attachProblem);
        }
    }

    /** Stops watching. Safe to call twice, safe without a daemon, and safe without {@link #start()}. */
    public void stop() {
        live = false;
        if (daemonWatch != null) {
            daemonWatch.stop();
        }
        log.info("Project watcher stopped.");
    }

    /** Whether a daemon subscription is delivering batches right now. */
    public boolean live() {
        return live && daemonWatch != null && daemonWatch.isRunning();
    }

    /** Why no live event source is running, or an empty string when {@link #live()} is true. */
    public String attachProblem() {
        return live() ? "" : attachProblem;
    }

    /** The daemon socket this watcher is attached to, empty when no daemon serves the root. */
    public Optional<Path> daemonSocket() {
        return daemonWatch == null ? Optional.empty() : Optional.of(daemonWatch.socket());
    }

    /** The root being watched. */
    public Path root() {
        return root;
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
