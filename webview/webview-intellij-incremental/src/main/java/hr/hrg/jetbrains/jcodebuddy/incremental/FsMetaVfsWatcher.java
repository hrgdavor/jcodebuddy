package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.local.FileWatcherNotificationSink;
import com.intellij.openapi.vfs.local.PluggableFileWatcher;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single-watcher bridge: fs-meta is the only file watcher, and IntelliJ's VFS receives the changes through this
 * {@link PluggableFileWatcher}. Set the global JVM option {@code -Didea.filewatcher.disabled=true} so the native
 * fsnotifier is not also watching. Without that option both watchers run; the bridge still works but does not
 * remove the duplicate effort.
 *
 * <p>When no fs-meta daemon serves a root, the root is reported to VFS as a manual-watch root, and a small retry
 * loop attaches as soon as the daemon appears. A daemon-root subscription is filtered to the requested VFS roots,
 * and its POSIX daemon paths are converted back to absolute filesystem paths before notifying VFS.</p>
 */
public final class FsMetaVfsWatcher extends PluggableFileWatcher {

    private static final Logger LOG = Logger.getInstance(FsMetaVfsWatcher.class);
    private static final long RETRY_SECONDS = 2;

    private final Object lock = new Object();
    private final List<RunningWatch> watches = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "jcodebuddy-vfs-watcher-retry");
        thread.setDaemon(true);
        return thread;
    });

    private volatile FileWatcherNotificationSink sink;
    private volatile List<Path> roots = List.of();
    private volatile boolean disposed;
    private volatile boolean settingRoots;

    @Override
    public void initialize(@NotNull FileWatcherNotificationSink notificationSink) {
        this.sink = notificationSink;
        String warning = duplicateWatcherWarning();
        if (warning != null) {
            LOG.warn(warning);
        }
        retry.scheduleWithFixedDelay(this::retryAttach, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS);
    }

    /** The user-facing opt-in warning, or {@code null} when fs-meta is already the only VFS watcher. */
    static String duplicateWatcherWarning() {
        if (Boolean.getBoolean("idea.filewatcher.disabled")) {
            return null;
        }
        return "JCodeBuddy fs-meta VFS watcher is active, but IntelliJ's native VFS watcher is also running. "
                + "To opt in to using only fs-meta, add -Didea.filewatcher.disabled=true to "
                + "Help | Edit Custom VM Options, then restart IntelliJ. This option is global for every "
                + "project in this IDE. See the plugin README section 'Single-watcher mode'.";
    }

    @Override
    public boolean isOperational() {
        return !disposed;
    }

    @Override
    public boolean isSettingRoots() {
        return settingRoots;
    }

    @Override
    public void setWatchRoots(@NotNull List<String> recursive, @NotNull List<String> flat, boolean shuttingDown) {
        if (shuttingDown) {
            stopWatches();
            return;
        }
        Set<Path> requested = new LinkedHashSet<>();
        for (String root : recursive) {
            addRoot(requested, root);
        }
        for (String root : flat) {
            addRoot(requested, root);
        }
        synchronized (lock) {
            if (disposed) {
                return;
            }
            stopWatchesLocked();
            roots = List.copyOf(requested);
            if (!roots.isEmpty()) {
                startWatchesLocked();
            }
        }
    }

    private static void addRoot(Set<Path> roots, String root) {
        if (root == null || root.isBlank()) {
            return;
        }
        try {
            roots.add(Path.of(root).toAbsolutePath().normalize());
        } catch (RuntimeException malformed) {
            LOG.warn("Ignoring invalid VFS watch root: " + root);
        }
    }

    private void retryAttach() {
        if (disposed) {
            return;
        }
        synchronized (lock) {
            watches.removeIf(running -> !running.watch().isRunning());
            if (watches.isEmpty() && !roots.isEmpty()) {
                startWatchesLocked();
            }
        }
    }

    private void startWatchesLocked() {
        FileWatcherNotificationSink currentSink = sink;
        if (currentSink == null || roots.isEmpty()) {
            return;
        }
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        List<String> manual = new ArrayList<>();
        for (Path root : roots) {
            try {
                FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discoverAncestor(root);
                if (attach == null || !attach.attached()) {
                    manual.add(root.toString());
                    continue;
                }
                Path daemonRoot = attach.location().root();
                String socket = attach.location().socket().toString();
                Endpoint endpoint = endpoints.computeIfAbsent(socket,
                        ignored -> new Endpoint(daemonRoot, attach.location().socket()));
                endpoint.roots.add(root);
            } catch (RuntimeException failed) {
                manual.add(root.toString());
                LOG.warn("Cannot attach fs-meta VFS watcher to " + root, failed);
            }
        }
        if (!manual.isEmpty()) {
            currentSink.notifyManualWatchRoots(this, manual);
            LOG.warn("fs-meta VFS watcher has no daemon for: " + manual
                    + "; these roots are manual-refresh until the daemon starts");
        } else {
            currentSink.notifyManualWatchRoots(this, List.of());
        }

        for (Endpoint endpoint : endpoints.values()) {
            if (disposed) {
                break;
            }
            List<String> includes = new ArrayList<>();
            for (Path root : endpoint.roots) {
                if (root.equals(endpoint.daemonRoot)) {
                    includes.clear();
                    break;
                }
                String relative = endpoint.daemonRoot.relativize(root).toString().replace('\\', '/');
                if (!relative.isEmpty()) {
                    includes.add(relative + "/**");
                }
            }
            PathFilter filter = includes.isEmpty()
                    ? PathFilter.of(List.of(), List.of())
                    : PathFilter.of(includes, List.of());
            FsMetaDaemonWatch watch = new FsMetaDaemonWatch(filter, endpoint.socket,
                    hr.hrg.jcodebuddy.watch.FsMetaClient.LATEST,
                    batch -> report(batch, endpoint.daemonRoot, endpoint.roots));
            try {
                watch.start();
                watches.add(new RunningWatch(endpoint, watch));
                LOG.info("fs-meta VFS watcher attached to " + endpoint.daemonRoot
                        + " for " + endpoint.roots.size() + " VFS root(s)");
                for (Path root : endpoint.roots) {
                    currentSink.notifyDirtyPathRecursive(root.toString());
                }
            } catch (IOException failed) {
                LOG.warn("Cannot start fs-meta VFS watcher for " + endpoint.daemonRoot, failed);
                currentSink.notifyManualWatchRoots(this, endpoint.roots.stream().map(Path::toString).toList());
                try {
                    watch.stop();
                } catch (RuntimeException ignored) {
                    // Best effort.
                }
            }
        }
    }

    private void report(WatchBatch batch, Path daemonRoot, Collection<Path> endpointRoots) {
        FileWatcherNotificationSink currentSink = sink;
        if (currentSink == null || disposed) {
            return;
        }
        if (batch.overflow()) {
            for (Path root : endpointRoots) {
                currentSink.notifyDirtyPathRecursive(root.toString());
            }
            return;
        }
        for (String name : batch.changed()) {
            Path path = daemonRoot.resolve(name).toAbsolutePath().normalize();
            if (!isUnderAny(path, endpointRoots)) {
                continue;
            }
            if (Files.isDirectory(path)) {
                currentSink.notifyDirtyDirectory(path.toString());
            } else {
                currentSink.notifyDirtyPath(path.toString());
            }
        }
        for (String name : batch.deleted()) {
            Path path = daemonRoot.resolve(name).toAbsolutePath().normalize();
            if (isUnderAny(path, endpointRoots)) {
                currentSink.notifyPathCreatedOrDeleted(path.toString());
            }
        }
    }

    private static boolean isUnderAny(Path path, Collection<Path> roots) {
        for (Path root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private void stopWatches() {
        synchronized (lock) {
            stopWatchesLocked();
        }
    }

    private void stopWatchesLocked() {
        for (RunningWatch running : watches) {
            try {
                running.watch().stop();
            } catch (RuntimeException failed) {
                LOG.warn("Cannot stop fs-meta VFS watcher", failed);
            }
        }
        watches.clear();
    }

    @Override
    public void dispose() {
        disposed = true;
        retry.shutdownNow();
        stopWatches();
        sink = null;
        roots = List.of();
    }

    @Override
    public void resetChangedPaths() {
        // fs-meta already coalesces per batch; no separate pending set here.
    }

    @Override
    public void startup() {
        // Test-only lifecycle method; normal startup goes through initialize/setWatchRoots.
    }

    @Override
    public void shutdown() {
        stopWatches();
    }

    private record Endpoint(Path daemonRoot, Path socket, List<Path> roots) {
        private Endpoint(Path daemonRoot, Path socket) {
            this(daemonRoot, socket, new ArrayList<>());
        }
    }

    private record RunningWatch(Endpoint endpoint, FsMetaDaemonWatch watch) {
    }
}
