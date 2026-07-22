// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;

/**
 * File watcher service using DirectoryWatcher library.
 * Monitors a directory for changes and copies files to a destination.
 * Handles file deletions gracefully and avoids blocking file deletion on
 * Windows.
 */
public class FileWatcherService implements DirectoryChangeListener {
    private static final Logger logger = LoggerFactory.getLogger(FileWatcherService.class);

    private final Path sourceDir;
    private final Path destDir;
    private DirectoryWatcher watcher;
    private CompletableFuture<Void> watcherFuture;

    public FileWatcherService(Path sourceDir, Path destDir) throws IOException {
        this.sourceDir = sourceDir.toAbsolutePath().normalize();
        this.destDir = destDir.toAbsolutePath().normalize();

        // Validate source directory exists
        if (!Files.exists(sourceDir)) {
            throw new IOException("Source directory does not exist: " + sourceDir);
        }
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("Source path is not a directory: " + sourceDir);
        }

        // Create destination directory if it doesn't exist
        if (!Files.exists(destDir)) {
            Files.createDirectories(destDir);
            logger.info("Created destination directory: {}", destDir);
        }
    }

    /**
     * Start watching the source directory
     */
    public void start() throws IOException {
        logger.info("Starting file watcher...");
        logger.info("Source: {}", sourceDir);
        logger.info("Destination: {}", destDir);

        // Initial sync - copy all existing files
        syncInitialFiles();

        // Build and start the DirectoryWatcher
        watcher = DirectoryWatcher.builder()
                .path(sourceDir)
                .listener(this)
                .build();

        // Start watching asynchronously
        watcherFuture = watcher.watchAsync();

        logger.info("File watcher started successfully");
    }

    /**
     * DirectoryChangeListener callback - handles all file system events
     */
    @Override
    public void onEvent(DirectoryChangeEvent event) {
        try {
            Path eventPath = event.path();
            DirectoryChangeEvent.EventType eventType = event.eventType();

            logger.info("Event: {} - {}", eventType, eventPath);

            switch (eventType) {
                case CREATE:
                    handleCreate(eventPath);
                    break;
                case MODIFY:
                    handleModify(eventPath);
                    break;
                case DELETE:
                    handleDelete(eventPath);
                    break;
                case OVERFLOW:
                    logger.warn("Event overflow detected - some events may have been lost");
                    break;
            }
        } catch (Exception e) {
            logger.error("Error processing event: {}", event, e);
        }
    }

    /**
     * Sync all existing files from source to destination
     */
    private void syncInitialFiles() throws IOException {
        logger.info("Performing initial sync...");

        Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                copyFile(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = getDestinationPath(dir);
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                    logger.debug("Created directory: {}", targetDir);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        logger.info("Initial sync completed");
    }

    /**
     * Handle file/directory creation event
     */
    private void handleCreate(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            // Create corresponding directory in destination
            Path targetDir = getDestinationPath(path);
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
                logger.info("Created directory: {}", targetDir);
            }

            // Copy any files that might already be in the new directory
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    copyFile(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else if (Files.exists(path)) {
            // Copy the new file (only if it still exists)
            copyFile(path);
        }
    }

    /**
     * Handle file modification event
     */
    private void handleModify(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            // Re-copy the modified file
            copyFile(path);
        }
    }

    /**
     * Handle file/directory deletion event
     */
    private void handleDelete(Path path) throws IOException {
        Path targetPath = getDestinationPath(path);

        if (Files.exists(targetPath)) {
            try {
                if (Files.isDirectory(targetPath)) {
                    // Delete directory and all its contents
                    deleteDirectory(targetPath);
                    logger.info("Deleted directory: {}", targetPath);
                } else {
                    // Delete file
                    Files.delete(targetPath);
                    logger.info("Deleted file: {}", targetPath);
                }
            } catch (IOException e) {
                logger.error("Failed to delete: {}", targetPath, e);
            }
        }
    }

    /**
     * Copy a file from source to destination.
     * Uses REPLACE_EXISTING to avoid blocking on Windows.
     */
    private void copyFile(Path sourceFile) throws IOException {
        Path targetFile = getDestinationPath(sourceFile);

        // Ensure parent directory exists
        Path parentDir = targetFile.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        // Copy file with REPLACE_EXISTING to avoid Windows file locking issues
        // This allows the source file to be deleted even while we're copying
        Files.copy(sourceFile, targetFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);

        logger.info("Copied: {} -> {}", sourceFile, targetFile);
    }

    /**
     * Delete a directory and all its contents
     */
    private void deleteDirectory(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Get the corresponding destination path for a source path
     */
    private Path getDestinationPath(Path sourcePath) {
        Path relativePath = sourceDir.relativize(sourcePath);
        return destDir.resolve(relativePath);
    }

    /**
     * Stop the file watcher service
     */
    public void stop() throws IOException {
        if (watcher != null) {
            logger.info("Stopping file watcher...");
            watcher.close();

            if (watcherFuture != null) {
                watcherFuture.cancel(true);
            }

            logger.info("File watcher stopped");
        }
    }

    /**
     * Wait for the watcher to complete (blocks until stopped)
     */
    public void awaitTermination() {
        if (watcherFuture != null) {
            try {
                watcherFuture.join();
            } catch (Exception e) {
                logger.debug("Watcher terminated", e);
            }
        }
    }
}
