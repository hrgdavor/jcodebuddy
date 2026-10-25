// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.scp;

import hr.hrg.watch2.core.ChecksumDatabase;
import hr.hrg.watch2.core.WatchConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for the Java Watch SCP application.
 */
public class WatchScpApp {
    private static final Logger log = LoggerFactory.getLogger(WatchScpApp.class);

    private static void printHelp() {
        System.out.println("Java Watch SCP - SSH/SCP file synchronization with checksum-based change detection");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar java-watch-scp.jar -c <config-file> [options] [host] [username] [password]");
        System.out.println("  java -jar java-watch-scp.jar get <remote-path> <local-path> -c <config-file>");
        System.out.println("  java -jar java-watch-scp.jar put <local-path> <remote-path> -c <config-file>");
        System.out.println(
                "  java -jar java-watch-scp.jar create <folder-path> [--includes <p1,p2>] [--excludes <p3,p4>]");
        System.out.println();
        System.out.println("Required (for sync mode):");
        System.out.println("  -c, --config <path>    Path to configuration file (use '-' to read from stdin)");
        System.out.println();
        System.out.println("Creation mode:");
        System.out.println("  create <path>        Create .scpdb for the specified folder");
        System.out.println("  --includes <patterns>  Comma-separated list of include patterns");
        System.out.println("  --excludes <patterns>  Comma-separated list of exclude patterns");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -x, --compress         Enable SSH compression");
        System.out.println("  --cleanup              Remove remote files not present locally");
        System.out.println("  -w, --watch            Enable watch mode (continuous sync)");
        System.out.println("  --no-color             Disable color output");
        System.out.println("  --watch-delay <ms>     Delay before syncing after change (default: 200)");
        System.out.println("  --exec <command>       Command to execute on remote after sync");
        System.out.println("  --check <hash|mtime_size> Change detection mode (default: hash)");
        System.out.println("  --no-db                Disable checksum database (.scpdb)");
        System.out.println("  --simple-log           Use simple logging (dummy parameter for CLI consistency)");
        System.out.println("  -h, --help             Show this help message");
        System.out.println();
        System.out.println("Positional arguments (override config file):");
        System.out.println("  host                   SSH host (can include port as host:port)");
        System.out.println("  username               SSH username");
        System.out.println("  password               SSH password");
        System.out.println();
    }

    public static void main(String[] args) {
        // Check if no arguments provided
        if (args.length == 0) {
            printHelp();
            return;
        }

        WatchScpConfig config = new WatchScpConfig();
        String configPath = null;
        int positionalCount = 0;
        String firstPositional = null;
        boolean showHelp = false;

        // Parse arguments matching Zig version logic
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                showHelp = true;
            } else if (arg.equals("-c") || arg.equals("--config")) {
                if (i + 1 < args.length) {
                    configPath = args[++i];
                } else {
                    log.error("Missing path for {} flag", arg);
                    return;
                }
            } else if (arg.equals("create")) {
                if (i + 1 < args.length) {
                    config.setCreateFolder(args[++i]);
                }
            } else if (arg.equals("--includes")) {
                if (i + 1 < args.length) {
                    for (String p : args[++i].split(","))
                        config.getCreateIncludes().add(p.trim());
                }
            } else if (arg.equals("--excludes")) {
                if (i + 1 < args.length) {
                    for (String p : args[++i].split(","))
                        config.getCreateExcludes().add(p.trim());
                }
            } else if (arg.equals("-x") || arg.equals("--compress")) {
                config.setCompress(true);
            } else if (arg.equals("-w") || arg.equals("--watch")) {
                config.setWatch(true);
            } else if (arg.equals("--no-color")) {
                config.setColor(false);
            } else if (arg.equals("--cleanup")) {
                config.setCleanup(true);
            } else if (arg.equals("--dry-run")) {
                config.setDryRun(true);
            } else if (arg.equals("--watch-delay")) {
                if (i + 1 < args.length) {
                    config.setWatchDelayMs(Long.parseLong(args[++i]));
                }
            } else if (arg.equals("--exec")) {
                if (i + 1 < args.length) {
                    config.setExecCmd(args[++i]);
                }
            } else if (arg.equals("--check")) {
                if (i + 1 < args.length) {
                    String val = args[++i];
                    if (val.equalsIgnoreCase("mtime_size")) {
                        config.setCliCheck(ChecksumDatabase.CheckMode.mtime_size);
                    } else {
                        config.setCliCheck(ChecksumDatabase.CheckMode.hash);
                    }
                }
            } else if (arg.equals("--no-db")) {
                config.setCliNoDb(true);
            } else if (arg.equals("--var") || arg.equals("-D")) {
                if (i + 1 < args.length) {
                    String pair = args[++i];
                    int eqIdx2 = pair.indexOf('=');
                    if (eqIdx2 < 1) {
                        log.error("--var expects VARNAME=value, got: {}", pair);
                        return;
                    }
                    config.addCliVar(pair.substring(0, eqIdx2), pair.substring(eqIdx2 + 1));
                } else {
                    log.error("Missing value for {} flag", arg);
                    return;
                }
            } else if (arg.equals("--simple-log")) {
                // Dummy parameter for CLI consistency with Zig version
            } else if (arg.equals("get")) {
                if (i + 2 < args.length) {
                    config.setGetFile(new String[] { args[++i], args[++i] });
                } else {
                    log.error("Missing remote-path or local-path for get command");
                    return;
                }
            } else if (arg.equals("put")) {
                if (i + 2 < args.length) {
                    config.setPutFile(new String[] { args[++i], args[++i] });
                } else {
                    log.error("Missing local-path or remote-path for put command");
                    return;
                }
            } else {
                // Positional arguments: host, username, password
                if (positionalCount == 0) {
                    firstPositional = arg;
                    int colonIdx = arg.indexOf(':');
                    if (colonIdx != -1) {
                        config.setHost(arg.substring(0, colonIdx));
                        config.setPort(Integer.parseInt(arg.substring(colonIdx + 1)));
                    } else {
                        config.setHost(arg);
                    }
                } else if (positionalCount == 1) {
                    config.setUsername(arg);
                } else if (positionalCount == 2) {
                    config.setPassword(arg);
                }
                positionalCount++;
            }
        }

        if (showHelp) {
            printHelp();
            return;
        }

        if (configPath == null && positionalCount == 1 
                && config.getCreateFolder().isEmpty() 
                && config.getGetFile() == null 
                && config.getPutFile() == null) {
            configPath = firstPositional;
            config.setHost("");
            config.setPort(22);
        }

        if (configPath == null && config.getCreateFolder().isEmpty()) {
            System.err.println(
                    "Error: Configuration file is required for sync mode, or use 'create' for database generation.");
            System.err.println();
            printHelp();
            return;
        }

        if (!config.getCreateFolder().isEmpty()) {
            handleCreateDb(config);
            return;
        }

        try {
            config = WatchScpConfig.load(configPath, config);

            if (config.getHost().isEmpty()) {
                log.error("Missing host. Provide via CLI positional argument or 'host' in {}", configPath);
                return;
            }
            if (config.getUsername().isEmpty()) {
                log.error("Missing username. Provide via CLI positional argument or 'username' in {}", configPath);
                return;
            }
            if (config.getPassword().isEmpty() && config.getKeyPath().isEmpty()) {
                log.error("Missing credentials. Provide password or key_path.");
                return;
            }

            if (config.getFolders().isEmpty() && config.getGetFile() == null && config.getPutFile() == null) {
                log.error("No [folder] sections found in configuration file: {}", configPath);
                return;
            }

            if (config.getGetFile() != null) {
                String remotePath = config.getGetFile()[0];
                String localPath = config.getGetFile()[1];
                System.out.println("Downloading " + remotePath + " to " + localPath + "...");
                ScpTransfer scp = new ScpTransfer(config.getHost(), config.getPort(), config.getUsername(),
                        config.getPassword(), config.getKeyPath(), config.getPassphrase(), config.getCompress(), "");
                File localFile = new File(localPath);
                if (scp.downloadFile(remotePath, localFile)) {
                    System.out.println("Downloaded successfully.");
                } else {
                    System.err.println("Failed to download file.");
                }
                return;
            }

            if (config.getPutFile() != null) {
                String localPath = config.getPutFile()[0];
                String remotePath = config.getPutFile()[1];
                System.out.println("Uploading " + localPath + " to " + remotePath + "...");
                ScpTransfer scp = new ScpTransfer(config.getHost(), config.getPort(), config.getUsername(),
                        config.getPassword(), config.getKeyPath(), config.getPassphrase(), config.getCompress(), "");
                File localFile = new File(localPath);
                if (scp.transferFile(localFile, remotePath)) {
                    System.out.println("Uploaded successfully.");
                } else {
                    System.err.println("Failed to upload file.");
                }
                return;
            }

            List<WatchScpWorker> workers = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();

            List<CopyWorker> copyWorkers = new ArrayList<>();
            for (WatchScpConfig.CopyWorkerConfig cwConfig : config.getCopyWorkers()) {
                CopyWorker worker = new CopyWorker(cwConfig, config.isColor());
                worker.init(); // Blocking initial sync
                copyWorkers.add(worker);
                if (config.isWatch()) {
                    Thread thread = new Thread(worker, "CopyWorker-" + cwConfig.destDir);
                    threads.add(thread);
                    thread.start();
                }
            }

            boolean anyChanges = false;
            for (WatchScpConfig.Folder folder : config.getFolders()) {
                WatchScpWorker worker = new WatchScpWorker(config, folder);
                workers.add(worker);
                if (config.isWatch()) {
                    Thread thread = new Thread(worker, "WatchWorker-" + folder.localDir);
                    threads.add(thread);
                    thread.start();
                } else {
                    if (worker.performInitialSync()) {
                        anyChanges = true;
                    }
                }
            }

            if (anyChanges && !workers.isEmpty()) {
                workers.get(0).performVersionFileUpload();
            }

            if (config.isWatch()) {
                log.info("Started {} watch workers and {} copy workers", workers.size(), copyWorkers.size());

                // Add shutdown hook to stop all workers
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    log.info("Stopping all workers...");
                    for (WatchScpWorker worker : workers) {
                        worker.stop();
                    }
                    for (CopyWorker worker : copyWorkers) {
                        worker.stop();
                    }
                }));

                // Stay alive
                for (Thread thread : threads) {
                    try {
                        thread.join();
                    } catch (InterruptedException e) {
                        log.error("Main thread interrupted", e);
                        break;
                    }
                }
            } else {
                log.info("Sync completed. Watch mode not enabled (-w to enable).");
            }

        } catch (Exception e) {
            log.error("Error during application startup: {}", e.getMessage(), e);
        }
    }

    private static void handleCreateDb(WatchScpConfig config) {
        String folderPath = config.getCreateFolder();
        System.out.println("Creating .scpdb for: " + folderPath);

        File sourceDir = new File(folderPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            System.err.println("Error: Directory not found: " + folderPath);
            return;
        }

        ChecksumDatabase db = new ChecksumDatabase();
        try (Stream<Path> paths = java.nio.file.Files.walk(sourceDir.toPath())) {
            paths.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> shouldIncludeFile(sourceDir.toPath(), path, config.getCreateIncludes(),
                            config.getCreateExcludes()))
                    .forEach(path -> {
                        String relPath = sourceDir.toPath().relativize(path).toString().replace('\\', '/');
                        try {
                            boolean isText = WatchConstants.isTextFile(path.getFileName().toString());
                            File file = path.toFile();
                            String checksum = db.calculateChecksum(file, isText);
                            db.setChecksum(relPath, checksum, file.lastModified(), file.length());
                        } catch (IOException e) {
                            System.err.println("Error calculating checksum for " + relPath + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error scanning directory: " + e.getMessage());
            return;
        }

        File dbFile = new File(sourceDir, ".scpdb");
        try {
            db.save(dbFile);
            System.out.println("Successfully created " + dbFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error saving database: " + e.getMessage());
        }
    }

    private static boolean shouldIncludeFile(Path sourcePath, Path file, List<String> includes, List<String> excludes) {
        String relPath = sourcePath.relativize(file).toString().replace('\\', '/');

        for (String pat : excludes) {
            if (matchPattern(relPath, pat))
                return false;
        }
        if (!includes.isEmpty()) {
            for (String pat : includes) {
                if (matchPattern(relPath, pat))
                    return true;
            }
            return false;
        }
        return true;
    }

    private static boolean matchPattern(String path, String pattern) {
        // Basic glob to regex conversion: * -> .*, ** -> .*
        String regex = pattern.replace(".", "\\.")
                .replace("**/", "(.*/)?")
                .replace("**", ".*")
                .replace("*", "[^/]*")
                .replace("?", ".");
        try {
            return path.matches(regex);
        } catch (Exception e) {
            return false;
        }
    }
}
