// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.scp;

import hr.hrg.watch2.core.ChecksumDatabase;
import hr.hrg.watch2.core.DirectoryScanner;
import hr.hrg.watch2.core.FileFilter;
import hr.hrg.watch2.core.ManagedFileWatcher;
import hr.hrg.watch2.core.WatchConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Worker that watches multiple source directories and copies changed files
 * to a single destination directory locally.
 */
public class CopyWorker implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(CopyWorker.class);
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET = "\u001B[0m";

    private final WatchScpConfig.CopyWorkerConfig config;
    private final boolean color;
    private final ChecksumDatabase checksumDb;
    private final List<ManagedFileWatcher> watchers = new ArrayList<>();
    private final Path destPath;

    public CopyWorker(WatchScpConfig.CopyWorkerConfig config, boolean color) {
        this.config = config;
        this.color = color;
        this.destPath = Paths.get(config.destDir).toAbsolutePath();
        this.checksumDb = new ChecksumDatabase();
    }

    public void init() throws IOException {
        if (!Files.exists(destPath)) {
            Files.createDirectories(destPath);
        }

        for (WatchScpConfig.Folder source : config.sources) {
            performInitialSync(source);
        }
    }

    @Override
    public void run() {
        try {
            for (WatchScpConfig.Folder source : config.sources) {
                Path sourcePath = Paths.get(source.localDir).toAbsolutePath();
                FileFilter filter = new FileFilter(sourcePath, source.includes, source.excludes);

                ManagedFileWatcher watcher = new ManagedFileWatcher(sourcePath, filter, 200, (path, relPath) -> {
                    processChange(sourcePath, path, relPath, filter);
                });
                watcher.start();
                watchers.add(watcher);
            }

            log.info("Started CopyWorker for destination: {}", destPath);
        } catch (Exception e) {
            log.error("Error in CopyWorker for " + config.destDir, e);
        }
    }

    private void performInitialSync(WatchScpConfig.Folder source) throws IOException {
        Path sourcePath = Paths.get(source.localDir).toAbsolutePath();
        FileFilter filter = new FileFilter(sourcePath, source.includes, source.excludes);

        log.info("CopyWorker: Initial sync for {} -> {}", sourcePath, destPath);

        DirectoryScanner scanner = new DirectoryScanner(sourcePath, filter, checksumDb);
        Map<String, ChecksumDatabase.DbEntry> currentLocalChecksums = scanner.scan();

        currentLocalChecksums.forEach((relPath, entry) -> {
            try {
                copyFile(sourcePath.resolve(relPath), relPath);
                checksumDb.setChecksum(relPath, entry.checksum, entry.mtime, sourcePath.resolve(relPath).toFile().length());
            } catch (IOException e) {
                log.error("Failed to copy " + relPath, e);
            }
        });
    }

    private void processChange(Path sourcePath, Path path, String relPath, FileFilter filter) {
        log.info("CopyWorker: Processing change for {}", relPath);
        File file = path.toFile();
        try {
            boolean isText = WatchConstants.isTextFile(file.getName());
            String checksum = checksumDb.calculateChecksum(file, isText);

            if (checksumDb.needsUpload(file, relPath, checksum, isText, ChecksumDatabase.CheckMode.hash)) {
                if (color) {
                    log.info("{}Copying changed file: {}{}", ANSI_YELLOW, relPath, ANSI_RESET);
                } else {
                    log.info("Copying changed file: {}", relPath);
                }
                if (copyFile(path, relPath)) {
                    checksumDb.setChecksum(relPath, checksum, file.lastModified(), file.length());
                }
            }
        } catch (Exception e) {
            log.error("Error processing change for " + relPath, e);
        }
    }

    private boolean copyFile(Path source, String relPath) throws IOException {
        Path target = destPath.resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    public void stop() {
        for (ManagedFileWatcher watcher : watchers) {
            watcher.stop();
        }
    }
}
