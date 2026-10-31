package hr.hrg.jcodebuddy.watch;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The event source the JCodeBuddy watchers use: a JDK {@link WatchService} with recursive registration, a debounce
 * window and a {@link PathFilter}. It owns events only; the state and its diff are {@code fs-meta}'s.
 *
 * <p>This is the replacement for {@code java-watch-core}'s {@code ManagedFileWatcher}/{@code BatchedFileWatcher}: one
 * class, no dependency on the frozen watcher family, and the batch boundary the consumers need. It deliberately does
 * not hash, diff or decide whether a pass is useful — that is the consumer's policy, and in this repository it is
 * {@code fs-meta}'s trio.</p>
 *
 * <p>A single daemon thread polls the service. The first event starts the debounce window; further events in that
 * window join the batch. A queue overflow is carried as {@link WatchBatch#overflow()}, not hidden.</p>
 */
public final class FileWatch implements AutoCloseable {

    private final Path root;
    private final PathFilter filter;
    private final long debounceMs;
    private final Consumer<WatchBatch> listener;

    private WatchService service;
    private Thread thread;
    private volatile boolean running;

    private final Set<String> changed = new LinkedHashSet<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    private boolean overflow;

    /**
     * @param root      the tree to watch
     * @param filter    what counts as a file worth reporting and which directories to enter
     * @param debounceMs quiet window after the first event; a non-positive value means the default 300 ms
     * @param listener  called on the watcher thread with one batch
     */
    public FileWatch(Path root, PathFilter filter, long debounceMs, Consumer<WatchBatch> listener) {
        if (root == null) {
            throw new IllegalArgumentException("a root is required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.filter = filter == null ? PathFilter.of(List.of(), List.of()) : filter;
        this.debounceMs = debounceMs <= 0 ? 300 : debounceMs;
        this.listener = listener;
    }

    /** Starts watching asynchronously. Returns immediately; batches arrive on the watcher thread. */
    public synchronized void start() throws IOException {
        if (thread != null) {
            return;
        }
        service = root.getFileSystem().newWatchService();
        try {
            registerTree(root);
        } catch (IOException failure) {
            service.close();
            service = null;
            throw failure;
        }
        running = true;
        thread = new Thread(this::loop, "jcodebuddy-watch");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops watching. Safe to call twice, and safe to call without {@link #start()}. */
    public synchronized void stop() {
        running = false;
        WatchService current = service;
        service = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Closing is how the polling thread is woken; there is nothing to report if the OS already failed.
            }
        }
        Thread currentThread = thread;
        thread = null;
        if (currentThread != null && currentThread != Thread.currentThread()) {
            try {
                currentThread.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void loop() {
        try {
            while (running) {
                WatchService current = service;
                if (current == null) {
                    return;
                }
                WatchKey key = current.take();
                handle(current, key);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMs);
                while (running) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    WatchKey next = current.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    handle(current, next);
                }
                flush();
            }
        } catch (ClosedWatchServiceException closed) {
            // stop() closes the service; this is the normal exit, not an error.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            // A listener that throws is the consumer's bug; the watcher stops rather than spinning on it.
            running = false;
        }
    }

    private void handle(WatchService current, WatchKey key) {
        Path directory = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                overflow = true;
                continue;
            }
            Path child = directory.resolve((Path) event.context());
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                try {
                    registerTree(child);
                } catch (IOException ignored) {
                    // A directory that disappeared before registration contributes no files; the next event will
                    // report the deletion if it is one we can see.
                }
                continue;
            }
            String relative = root.relativize(child).toString().replace('\\', '/');
            if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                if (filter.shouldInclude(relative)) {
                    deleted.add(relative);
                }
            } else if (filter.shouldInclude(relative)) {
                changed.add(relative);
            }
        }
        if (!key.reset()) {
            // The watched directory is gone; its key is no longer valid.
            key.cancel();
        }
    }

    private void flush() {
        if (changed.isEmpty() && deleted.isEmpty() && !overflow) {
            return;
        }
        WatchBatch batch = new WatchBatch(changed, deleted, overflow);
        changed.clear();
        deleted.clear();
        overflow = false;
        if (listener != null) {
            listener.accept(batch);
        }
    }

    private void registerTree(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                String relative = root.equals(directory)
                        ? ""
                        : root.relativize(directory).toString().replace('\\', '/');
                if (!relative.isEmpty() && !filter.shouldEnter(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                directory.register(service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                // An unreadable directory is skipped rather than failing the whole watcher.
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
