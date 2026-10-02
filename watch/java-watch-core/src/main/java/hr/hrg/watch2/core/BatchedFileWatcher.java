// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Like {@link ManagedFileWatcher} but accumulates <em>all</em> file-system
 * changes that arrive within a single debounce window and delivers them together
 * as a single {@link ChangeSet} batch.
 *
 * <h2>Debounce strategy</h2>
 * A single global {@link AtomicInteger} sequence counter is incremented on every
 * incoming event.  Each event schedules a "flush" task that captures the current
 * counter value.  When the task fires, it only proceeds if the counter still
 * matches (i.e. no newer events arrived).  This yields a "trailing-edge debounce":
 * the batch is delivered {@value} ms after the <em>last</em> change.
 *
 * <h2>DELETE handling</h2>
 * Unlike {@link ManagedFileWatcher}, this class does <em>not</em> ignore
 * {@code DELETE} events.  Deleted paths are included in {@link ChangeSet#deleted()}
 * so the consumer can remove the corresponding {@code .class} files before
 * the next compile.
 *
 * <p>Note: {@link FileFilter#shouldInclude(Path)} relies only on the path string,
 * so it works correctly for paths that no longer exist on disk.
 *
 * <h2>Overflow</h2>
 * If the OS event queue overflows, a {@link ChangeSet} with
 * {@link ChangeSet#fullRecompile()} set to {@code true} is delivered immediately
 * (no debounce wait), signalling the consumer to recompile the entire source tree.
 */
public class BatchedFileWatcher {
    private static final Logger log = LoggerFactory.getLogger(BatchedFileWatcher.class);

    // ── Configuration ─────────────────────────────────────────────────────────

    private final Path sourcePath;
    private final FileFilter filter;
    private final long debounceMs;
    private final Consumer<ChangeSet> handler;

    // ── Mutable state ─────────────────────────────────────────────────────────

    private DirectoryWatcher watcher;

    /** Incremented on every incoming event; used for trailing-edge debounce. */
    private final AtomicInteger globalSeq = new AtomicInteger();

    /** Guards the pending-change accumulators below. */
    private final Object pendingLock = new Object();
    private final Set<Path> pendingChanged = new LinkedHashSet<>();
    private final Set<Path> pendingDeleted = new LinkedHashSet<>();
    private long firstEventMs = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param sourcePath root directory to watch
     * @param filter     which files to include (applied to both changes and deletes)
     * @param debounceMs milliseconds to wait after the last change before delivering the batch
     * @param handler    called with the accumulated {@link ChangeSet} after each quiet window
     */
    public BatchedFileWatcher(Path sourcePath, FileFilter filter, long debounceMs,
                              Consumer<ChangeSet> handler) {
        this.sourcePath = sourcePath;
        this.filter     = filter;
        this.debounceMs = debounceMs;
        this.handler    = handler;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts watching asynchronously.  Returns immediately.
     *
     * @throws IOException if the underlying {@link DirectoryWatcher} cannot be built
     */
    public void start() throws IOException {
        watcher = DirectoryWatcher.builder()
                .path(sourcePath)
                .listener(this::handleEvent)
                .build();

        log.info("Watching directory (batch mode): {}", sourcePath);
        watcher.watchAsync();
    }

    /** Stops the watcher and releases its resources. */
    public void stop() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                log.error("Error closing BatchedFileWatcher", e);
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void handleEvent(DirectoryChangeEvent event) {
        // ── Overflow: deliver a "full recompile" signal immediately ────────────
        if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
            log.warn("File-system event overflow – signalling full recompile.");
            ChangeSet overflow;
            synchronized (pendingLock) {
                overflow = new ChangeSet(Set.copyOf(pendingChanged),
                                         Set.copyOf(pendingDeleted),
                                         true,
                                         firstEventMs == 0 ? System.currentTimeMillis() : firstEventMs);
                pendingChanged.clear();
                pendingDeleted.clear();
                firstEventMs = 0;
            }
            globalSeq.incrementAndGet(); // invalidate any pending debounce tasks
            handler.accept(overflow);
            return;
        }

        Path path = event.path();
        if (path == null) return;

        boolean isDelete = event.eventType() == DirectoryChangeEvent.EventType.DELETE;

        // For non-delete events, skip directories and non-regular files.
        if (!isDelete && !Files.isRegularFile(path)) return;

        // FileFilter.shouldInclude() uses only path string matching → safe for deleted files.
        if (!filter.shouldInclude(path)) return;

        synchronized (pendingLock) {
            int seq = globalSeq.incrementAndGet();
            boolean isAtomicRename = false;

            if (firstEventMs == 0) {
                firstEventMs = System.currentTimeMillis();
            }

            if (isDelete) {
                pendingDeleted.add(path);
                pendingChanged.remove(path); // deleted file cannot be compiled
            } else {
                if (pendingDeleted.contains(path)) {
                    isAtomicRename = true;
                }
                pendingChanged.add(path);
                pendingDeleted.remove(path); // file was re-created
            }

            if (isAtomicRename) {
                log.debug("Atomic rename detected for {}, flushing synchronously", path);
                flushIfCurrent(seq);
            } else {
                // Schedule a flush; only the task whose seq still matches will run.
                CompletableFuture.delayedExecutor(debounceMs, TimeUnit.MILLISECONDS)
                        .execute(() -> flushIfCurrent(seq));
            }
        }
    }

    /**
     * Delivers the accumulated batch if no newer event has arrived since {@code capturedSeq}
     * was recorded (i.e. the debounce window has not been reset).
     */
    private void flushIfCurrent(int capturedSeq) {
        ChangeSet batch;
        synchronized (pendingLock) {
            if (globalSeq.get() != capturedSeq) return; // stale — a newer event arrived
            if (pendingChanged.isEmpty() && pendingDeleted.isEmpty()) return;

            batch = new ChangeSet(
                    new LinkedHashSet<>(pendingChanged),
                    new LinkedHashSet<>(pendingDeleted),
                    firstEventMs);
            pendingChanged.clear();
            pendingDeleted.clear();
            firstEventMs = 0;
        }

        log.debug("Batch flush: {} changed, {} deleted",
                batch.changed().size(), batch.deleted().size());
        handler.accept(batch);
    }
}
