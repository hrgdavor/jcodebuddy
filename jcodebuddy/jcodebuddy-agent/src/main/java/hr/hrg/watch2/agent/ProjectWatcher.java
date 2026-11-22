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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Watches the project through the shared fs-meta daemon and hands each batch of changed paths to the
 * agent's tool catalog.
 *
 * <p>The daemon, not a local {@code WatchService}, is the event source. Discovery is exact-root; a daemon
 * serving a parent directory is not attached. No daemon is not an error: the agent still answers its
 * methods, and reports the exact {@code fsmeta root add} command that would make live events work.</p>
 */
public class ProjectWatcher {
    private static final Logger log = LoggerFactory.getLogger(ProjectWatcher.class);

    private final Path root;
    private final Consumer<List<Path>> batchListener;
    private final FsMetaDaemonDiscovery.Attach attach;
    private final FsMetaDaemonWatch daemonWatch;

    private volatile boolean live;
    private volatile String attachProblem = "";

    public ProjectWatcher(Path root, Consumer<List<Path>> batchListener) {
        this(root, batchListener, FsMetaDaemonDiscovery.discover(root));
    }

    ProjectWatcher(Path root, Consumer<List<Path>> batchListener, FsMetaDaemonDiscovery.Attach attach) {
        if (root == null) {
            throw new IllegalArgumentException("a root is required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.batchListener = batchListener;
        this.attach = attach == null
                ? new FsMetaDaemonDiscovery.Attach(null, "discovery did not run")
                : attach;

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

    public void stop() {
        live = false;
        if (daemonWatch != null) daemonWatch.stop();
        log.info("Project watcher stopped.");
    }

    public boolean live() {
        return live && daemonWatch != null && daemonWatch.isRunning();
    }

    public String attachProblem() {
        return live() ? "" : attachProblem;
    }

    public Optional<Path> daemonSocket() {
        return daemonWatch == null ? Optional.empty() : Optional.of(daemonWatch.socket());
    }

    public Path root() {
        return root;
    }

    private void dispatch(WatchBatch batch) {
        List<Path> paths = new ArrayList<>();
        for (String relative : batch.changed()) paths.add(root.resolve(relative));
        if (batchListener != null && !paths.isEmpty()) {
            try {
                batchListener.accept(List.copyOf(paths));
            } catch (RuntimeException failed) {
                log.error("Error dispatching fs-meta batch to the tool catalog", failed);
            }
        }
    }
}
