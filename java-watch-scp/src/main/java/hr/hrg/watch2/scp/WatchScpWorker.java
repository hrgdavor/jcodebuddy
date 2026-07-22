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

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main worker that watches a directory and automatically transfers files
 * to a remote destination via SCP.
 */
public class WatchScpWorker implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(WatchScpWorker.class);
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET = "\u001B[0m";

    private final WatchScpConfig config;
    private final WatchScpConfig.Folder folder;
    private final ScpTransfer scpTransfer;
    private final ChecksumDatabase checksumDb;
    private final Semaphore lockChannel;
    private final Path sourcePath;
    private final FileFilter filter;
    private ManagedFileWatcher managedWatcher;

    public WatchScpWorker(WatchScpConfig config, WatchScpConfig.Folder folder) {
        this.config = config;
        this.folder = folder;
        this.sourcePath = Paths.get(folder.localDir).toAbsolutePath();
        this.scpTransfer = new ScpTransfer(
                config.getHost(),
                config.getPort(),
                config.getUsername(),
                config.getPassword(),
                config.getKeyPath(),
                config.getPassphrase(),
                config.getCompress(),
                folder.remoteDir);
        this.checksumDb = new ChecksumDatabase();
        this.lockChannel = new Semaphore(config.getParallelThreads());
        this.filter = new FileFilter(sourcePath, folder.includes, folder.excludes);
    }

    private String getScpdbName() {
        String name = folder.scpdb != null && !folder.scpdb.isEmpty() ? folder.scpdb
                : ChecksumDatabase.getDatabaseFilename();
        return name;
    }

    private boolean isAbsoluteScpdb() {
        String name = getScpdbName();
        return name.startsWith("/") || (name.length() > 2 && name.charAt(1) == ':' && (name.charAt(2) == '/' || name.charAt(2) == '\\'));
    }

    public boolean performInitialSync() throws IOException {
        log.info("Performing initial synchronization for {}...", folder.localDir);
        long syncStart = System.currentTimeMillis();

        if (folder.noDb) {
            log.info("Skipping database (no_db mode enabled).");
        } else {
            // Try to download/load existing database
            boolean dbDownloaded = false;
            String scpdbName = getScpdbName();
            if (folder.localDb) {
                File localDbFile = isAbsoluteScpdb() ? new File(scpdbName) : sourcePath.resolve(scpdbName).toFile();
                if (localDbFile.exists()) {
                    checksumDb.load(localDbFile);
                    dbDownloaded = true;
                    log.info("Loaded local database: {}", localDbFile.getAbsolutePath());
                }
            } else {
                dbDownloaded = scpTransfer.downloadDatabase(checksumDb, scpdbName);
                if (dbDownloaded) {
                    log.info("Loaded remote database: {}", scpdbName);
                }
            }

            if (!dbDownloaded) {
                log.info("No database found, starting fresh.");
            }
        }

        DirectoryScanner scanner = new DirectoryScanner(sourcePath, filter, checksumDb);
        Map<String, ChecksumDatabase.DbEntry> currentLocalChecksums = scanner.scan();
        AtomicInteger filesSkipped = new AtomicInteger();
        AtomicInteger filesUploaded = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            currentLocalChecksums.forEach((relPathStr, entry) -> {
                executor.submit(() -> {
                    try {
                        File file = sourcePath.resolve(relPathStr).toFile();
                        boolean isText = WatchConstants.isTextFile(file.getName());
                        if (checksumDb.needsUpload(file, relPathStr, entry.checksum, isText, folder.check)) {
                            lockChannel.acquire();
                            try {
                                int counter = filesUploaded.incrementAndGet();
                                String msg = String.format("Initial sync: uploading [%d/%d] %s", counter,
                                        currentLocalChecksums.size() - filesSkipped.get(),
                                        relPathStr);
                                if (config.isColor()) {
                                    log.info("{}{}{}", ANSI_YELLOW, msg, ANSI_RESET);
                                } else {
                                    log.info(msg);
                                }
                                if (config.isDryRun()) {
                                    log.info("Dry-run: Would upload: {}", relPathStr);
                                } else {
                                    if (scpTransfer.transferFile(file, relPathStr)) {
                                        checksumDb.setChecksum(relPathStr, entry.checksum, entry.mtime, file.length());
                                    }
                                    if (counter > 0 && counter % 200 == 0) {
                                        saveAndUploadDatabaseNow();
                                    }
                                }
                            } finally {
                                lockChannel.release();
                            }
                        } else {
                            filesSkipped.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("Error during initial sync for " + relPathStr, e);
                    }
                });
            });
        }

        // Prune database: re-populate with only current local files
        if (!folder.noDb && !config.isDryRun()) {
            checksumDb.clear();
            currentLocalChecksums.forEach((path, entry) -> {
                File file = sourcePath.resolve(path).toFile();
                checksumDb.setChecksum(path, entry.checksum, entry.mtime, file.length());
            });

            saveAndUploadDatabaseNow();
        }

        if (config.getCleanup()) {
            performCleanup(currentLocalChecksums.keySet());
        }

        if (config.getExecCmd() != null) {
            if (config.isDryRun()) {
                log.info("Dry-run: Would execute remote command after initial sync: {}", config.getExecCmd());
            } else {
                try {
                    log.info("Executing remote command after initial sync: {}", config.getExecCmd());
                    scpTransfer.execCommandAndPrint(config.getExecCmd());
                } catch (Exception e) {
                    log.error("Failed to execute remote command: {}", config.getExecCmd(), e);
                }
            }
        }

        performSyncTrigger();
        performVersionFileUpload();

        long syncEnd = System.currentTimeMillis();
        double syncDuration = (syncEnd - syncStart) / 1000.0;
        log.info("Initial sync completed in {} seconds.", String.format("%.2f", syncDuration));
        return filesUploaded.get() > 0;
    }

    private void performCleanup(java.util.Set<String> localPaths) {
        java.util.List<String> remoteFiles = scpTransfer.listRemoteFilesRecursive("");
        int removedCount = 0;
        String scpdbName = getScpdbName();
        for (String relPath : remoteFiles) {
            if (relPath.equals(scpdbName))
                continue;

            if (filter.shouldInclude(sourcePath.resolve(relPath.replace('/', File.separatorChar)))) {
                if (!localPaths.contains(relPath)) {
                    if (config.isDryRun()) {
                        log.info("Cleanup: Dry-run: Would remove remote file {}", relPath);
                        removedCount++;
                    } else {
                        log.info("Cleanup: Removing remote file {}", relPath);
                        if (scpTransfer.removeRemoteFile(relPath)) {
                            removedCount++;
                        }
                    }
                }
            }
        }
        if (removedCount > 0) {
            log.info("Cleanup: Removed {} orphaned files.", removedCount);
        } else {
            log.info("Cleanup: No orphaned files found.");
        }
    }

    private synchronized void saveAndUploadDatabaseNow() {
        if (folder.noDb)
            return;
        String scpdbName = getScpdbName();
        if (folder.localDb) {
            File localDbFile = isAbsoluteScpdb() ? new File(scpdbName) : sourcePath.resolve(scpdbName).toFile();
            try {
                if (config.isDryRun()) {
                    log.info("Dry-run: Would save local database to {}", localDbFile.getAbsolutePath());
                } else {
                    log.info("Saving local database to {}", localDbFile.getAbsolutePath());
                    checksumDb.save(localDbFile);
                }
            } catch (IOException e) {
                log.error("Failed to save local database: " + localDbFile.getAbsolutePath(), e);
            }
        } else if (!config.isDryRun()) {
            scpTransfer.uploadDatabase(checksumDb, scpdbName);
        }
    }

    @Override
    public void run() {
        try {
            performInitialSync();

            managedWatcher = new ManagedFileWatcher(sourcePath, filter, config.getWatchDelayMs(), this::processChange);
            managedWatcher.start();
        } catch (Exception e) {
            log.error("Error in WatchScpWorker for " + folder.localDir, e);
        }
    }

    public void stop() {
        if (managedWatcher != null) {
            managedWatcher.stop();
        }
    }

    private void processChange(Path path, String relPath) {
        log.info("Processing change for {}", relPath);
        File file = path.toFile();
        try {
            boolean isText = WatchConstants.isTextFile(file.getName());
            String checksum = "";
            boolean needsUpload = true;

            if (folder.noDb) {
                ScpTransfer.RemoteFileInfo remoteInfo = scpTransfer.getRemoteFileInfo(relPath);
                if (remoteInfo != null) {
                    if (folder.check == ChecksumDatabase.CheckMode.mtime_size) {
                        // Upload if local is newer, OR binary file changed size
                        // remoteInfo.mtime is already in ms (getMTime()*1000 in ScpTransfer)
                        needsUpload = file.lastModified() > remoteInfo.mtime
                            || (!isText && file.length() != remoteInfo.size);
                    } else {
                        // For no_db + hash check, we'd need to download and hash, or just assume changed.
                        // Assuming changed for now.
                        needsUpload = true;
                    }
                }
            } else {
                if (folder.check == ChecksumDatabase.CheckMode.hash) {
                    checksum = checksumDb.calculateChecksum(file, isText);
                }
                needsUpload = checksumDb.needsUpload(file, relPath, checksum, isText, folder.check);
            }

            if (needsUpload) {
                lockChannel.acquire();
                try {
                    if (config.isColor()) {
                        log.info("{}Uploading changed file: {}{}", ANSI_YELLOW, relPath, ANSI_RESET);
                    } else {
                        log.info("Uploading changed file: {}", relPath);
                    }
                    if (config.isDryRun()) {
                        log.info("Dry-run: Would upload changed file: {}", relPath);
                    } else if (scpTransfer.transferFile(file, relPath)) {
                        if (checksum.isEmpty() && folder.check == ChecksumDatabase.CheckMode.hash) {
                            checksum = checksumDb.calculateChecksum(file, isText);
                        }
                        checksumDb.setChecksum(relPath, checksum, file.lastModified(), file.length());
                        saveAndUploadDatabaseNow();
                        if (config.getExecCmd() != null) {
                            try {
                                log.info("Executing remote command after file change: {}", config.getExecCmd());
                                scpTransfer.execCommandAndPrint(config.getExecCmd());
                            } catch (Exception ex) {
                                log.error("Failed to execute remote command: {}", config.getExecCmd(), ex);
                            }
                        }
                        performSyncTrigger();
                        performVersionFileUpload();
                    } else {
                        // transfer failed
                    }
                    if (config.isDryRun()) {
                        // Still "trigger" commands in log but don't run them
                        if (config.getExecCmd() != null) log.info("Dry-run: Would execute remote command after file change: {}", config.getExecCmd());
                        performSyncTrigger();
                        performVersionFileUpload();
                    }
                } finally {
                    lockChannel.release();
                }
            }
        } catch (Exception e) {
            log.error("Error processing change for " + relPath, e);
        }
    }

    private void performSyncTrigger() {
        if (folder.triggerTo == null || folder.triggerTo.isEmpty())
            return;

        if (folder.triggerFrom != null && !folder.triggerFrom.isEmpty()) {
            File triggerFile = new File(folder.triggerFrom);
            if (triggerFile.exists()) {
                if (config.isDryRun()) {
                    log.info("Dry-run: Would copy trigger file: {} -> {}", folder.triggerFrom, folder.triggerTo);
                } else {
                    log.info("Copying trigger file: {} -> {}", folder.triggerFrom, folder.triggerTo);
                    scpTransfer.transferFile(triggerFile, folder.triggerTo);
                }
            } else {
                log.warn("Trigger file not found: {}", folder.triggerFrom);
            }
        } else {
            if (config.isDryRun()) {
                log.info("Dry-run: Would write empty trigger file: {}", folder.triggerTo);
            } else {
                log.info("Writing empty trigger file: {}", folder.triggerTo);
                scpTransfer.transferFile(new java.io.ByteArrayInputStream(new byte[0]), 0, folder.triggerTo);
            }
        }
    }
    public void performVersionFileUpload() {
        String vFrom = folder.versionFrom != null ? folder.versionFrom : config.getVersionFrom();
        String vTo = folder.versionTo != null ? folder.versionTo : config.getVersionTo();
        String vName = folder.versionName != null ? folder.versionName : config.getVersionName();

        if (vFrom == null || vFrom.isEmpty() || vTo == null || vTo.isEmpty())
            return;

        File templateFile = new File(vFrom);
        if (!templateFile.exists()) {
            log.warn("Version template not found: {}", vFrom);
            return;
        }

        try {
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(templateFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }

            long nowSecs = System.currentTimeMillis() / 1000;
            String tsStr = String.valueOf(nowSecs);
            String updated = content.toString();

            // Replace ${timestamp}
            updated = updated.replace("${timestamp}", tsStr);

            // Replace name if versionName is set
            if (vName != null && !vName.isEmpty()) {
                // Generic placeholders: ${name} and ${version_name}
                updated = updated.replace("${name}", vName);
                updated = updated.replace("${version_name}", vName);
            }

            // Handle JSON format
            if (vFrom.endsWith(".json")) {
                // Replace timestamp field
                updated = updated.replaceAll("(\"timestamp\"\\s*:\\s*)\\d+", "$1" + tsStr);
                updated = updated.replaceAll("(\"timestamp\"\\s*:\\s*\")\\d*(\")", "$1" + tsStr + "$2");
                // Replace name field if versionName is set
                if (vName != null && !vName.isEmpty()) {
                    updated = updated.replaceAll("(\"name\"\\s*:\\s*\")[^\"]*\"", "\"name\": \"" + vName + "\"");
                }
            } else if (vFrom.endsWith(".ini")) {
                // Replace timestamp field
                updated = updated.replaceAll("(?m)^(timestamp\\s*=\\s*)\\d+", "$1" + tsStr);
                // Replace name field if versionName is set
                if (vName != null && !vName.isEmpty()) {
                    updated = updated.replaceAll("(?m)^(name\\s*=\\s*).*", "$1" + vName);
                }
            }

            // Fallback for missing fields in JSON
            if (vFrom.endsWith(".json")) {
                if (vName != null && !vName.isEmpty() && !updated.contains("\"name\"")) {
                    updated = updated.replaceFirst("\\}", (updated.trim().length() > 2 ? ", " : "") + "\"name\": \"" + vName + "\"}");
                }
                if (!updated.contains("\"timestamp\"")) {
                    updated = updated.replaceFirst("\\}", (updated.trim().length() > 2 ? ", " : "") + "\"timestamp\": " + tsStr + "}");
                }
            } else if (vFrom.endsWith(".ini")) {
                if (vName != null && !vName.isEmpty() && !updated.contains("name=")) {
                    updated += "\nname=" + vName;
                }
                if (!updated.contains("timestamp=")) {
                    updated += "\ntimestamp=" + tsStr;
                }
            }

            if (config.isDryRun()) {
                log.info("Dry-run: Would upload version file: {} -> {} (ts={})", vFrom, vTo, nowSecs);
            } else {
                log.info("Uploading version file: {} -> {} (ts={})", vFrom, vTo, nowSecs);
                byte[] bytes = updated.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                scpTransfer.transferFile(new java.io.ByteArrayInputStream(bytes), bytes.length, vTo);
            }

        } catch (IOException e) {
            log.error("Failed to process and upload version file: " + vFrom, e);
        }
    }
}
