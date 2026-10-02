// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main application class for the file watcher using DirectoryWatcher library
 */
public class FileWatcherApp {
    private static final Logger logger = LoggerFactory.getLogger(FileWatcherApp.class);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java -jar java_watch2.jar <source-directory> <destination-directory>");
            System.err.println("Example: java -jar java_watch2.jar C:\\source C:\\backup");
            System.exit(1);
        }

        Path sourceDir = Paths.get(args[0]);
        Path destDir = Paths.get(args[1]);

        System.out.println("=== File Watcher Service (DirectoryWatcher) ===");
        System.out.println("Source: " + sourceDir.toAbsolutePath());
        System.out.println("Destination: " + destDir.toAbsolutePath());
        System.out.println("Press Ctrl+C to stop...");
        System.out.println();

        FileWatcherService watcher = null;

        try {
            watcher = new FileWatcherService(sourceDir, destDir);

            // Create final reference for shutdown hook
            final FileWatcherService finalWatcher = watcher;

            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown signal received");
                try {
                    finalWatcher.stop();
                } catch (IOException e) {
                    logger.error("Error during shutdown", e);
                }
            }));

            // Start watching
            watcher.start();

            // Wait for termination
            watcher.awaitTermination();

        } catch (IOException e) {
            logger.error("Error with file watcher", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } finally {
            if (watcher != null) {
                try {
                    watcher.stop();
                } catch (IOException e) {
                    logger.error("Error stopping watcher", e);
                }
            }
        }
    }
}
