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
    private static final long RETRY_BACKOFF_MS = 10_000;

    private final Path root;
    private final Consumer<List<Path>> batchListener;
    private final FsMetaDaemonDiscovery.Attach attach;
    private final FsMetaDaemonWatch daemonWatch;

    private volatile boolean live;
    private volatile String attachProblem = "";
    private volatile long retryNotBefore;

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

    public synchronized void start() throws IOException {
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
                attachProblem = "";
                log.info("Project watcher attached to the fs-meta daemon socket {}", daemonWatch.socket());
            } else {
                attachProblem = failure(daemonWatch.lastError());
                log.warn("Project watcher is not watching {}: {}", root, attachProblem);
            }
        } catch (IOException failed) {
            live = false;
            attachProblem = "cannot attach to the fsmeta daemon at " + daemonWatch.socket() + ": "
                    + failed.getMessage();
            log.warn("Project watcher is not watching {}: {}", root, attachProblem);
        }
    }

    /**
     * Reconnect a subscription that the daemon dropped. The status endpoint calls this on every poll, so a dead
     * reader is repaired without restarting the agent; a head replay is safe because the overflow batch asks the
     * consumer for a full pass.
     */
    public synchronized boolean ensureLive() {
        if (live()) {
            retryNotBefore = 0;
            return true;
        }
        if (daemonWatch == null) return false;
        long now = System.currentTimeMillis();
        if (now < retryNotBefore) return false;
        // Keep the backoff armed until a later status observation sees a stable live subscription. A start
        // that dies immediately must not make the next 1.5 s poll start another reader.
        retryNotBefore = now + RETRY_BACKOFF_MS;
        stop();
        try {
            start();
        } catch (IOException failed) {
            live = false;
            attachProblem = "cannot resubscribe to the fsmeta daemon at " + daemonWatch.socket() + ": "
                    + failed.getMessage();
            log.warn("Project watcher is not watching {}: {}", root, attachProblem);
        }
        return live();
    }

    private String failure(String error) {
        return error == null || error.isBlank()
                ? "the fsmeta daemon subscription stopped without an error"
                : "the fsmeta daemon subscription stopped: " + error;
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
        if (live()) return "";
        if (live && daemonWatch != null) {
            return failure(daemonWatch.lastError());
        }
        return attachProblem;
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
