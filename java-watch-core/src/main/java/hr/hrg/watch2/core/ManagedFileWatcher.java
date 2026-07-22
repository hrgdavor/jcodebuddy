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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class ManagedFileWatcher {
    private static final Logger log = LoggerFactory.getLogger(ManagedFileWatcher.class);

    private final Path sourcePath;
    private final FileFilter filter;
    private final long delayMs;
    private final BiConsumer<Path, String> eventHandler;
    private DirectoryWatcher watcher;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<String, Integer> changeMap = new HashMap<>();

    public ManagedFileWatcher(Path sourcePath, FileFilter filter, long delayMs, BiConsumer<Path, String> eventHandler) {
        this.sourcePath = sourcePath;
        this.filter = filter;
        this.delayMs = delayMs;
        this.eventHandler = eventHandler;
    }

    public void start() throws IOException {
        watcher = DirectoryWatcher.builder()
                .path(sourcePath)
                .listener(this::handleFileChange)
                .build();

        log.info("Watching directory: {}", sourcePath);
        watcher.watchAsync();
    }

    public void stop() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                log.error("Error closing watcher", e);
            }
        }
    }

    private void handleFileChange(DirectoryChangeEvent event) {
        if (event.eventType() == DirectoryChangeEvent.EventType.DELETE) {
            return;
        }

        if (event.path() == null || !Files.isRegularFile(event.path())) {
            return;
        }

        if (!filter.shouldInclude(event.path())) {
            return;
        }

        String relPath = sourcePath.relativize(event.path()).toString().replace('\\', '/');

        synchronized (changeMap) {
            int currentSeq = seq.incrementAndGet();
            changeMap.put(relPath, currentSeq);

            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        synchronized (changeMap) {
                            if (changeMap.containsKey(relPath) && changeMap.get(relPath) == currentSeq) {
                                changeMap.remove(relPath);
                                eventHandler.accept(event.path(), relPath);
                            }
                        }
                    });
        }
    }
}
