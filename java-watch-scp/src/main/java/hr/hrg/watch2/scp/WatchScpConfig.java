// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.scp;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import hr.hrg.watch2.core.ChecksumDatabase;

/**
 * Configuration for the Watch SCP application, supporting multiple folders.
 */
public class WatchScpConfig {
    private String host = "";
    private int port = 22;
    private String username = "";
    private String password = "";
    private String keyPath = "";
    private String passphrase = "";
    private int parallelThreads = 4;
    private long watchDelayMs = 200;
    private boolean compress = false;
    private boolean cleanup = false;
    private boolean dryRun = false;
    private boolean watch = false;
    private boolean color = true;
    private List<String> textExtensions = new ArrayList<>(Arrays.asList(
            ".txt", ".md", ".c", ".h", ".cpp", ".hpp", ".java", ".py",
            ".js", ".ts", ".html", ".css", ".xml", ".json", ".yaml", ".yml",
            ".sh", ".bat", ".ps1", ".zig", ".go", ".rs", ".rb", ".php"));
    private List<Folder> folders = new ArrayList<>();
    private String createFolder = "";
    private List<String> createIncludes = new ArrayList<>();
    private List<String> createExcludes = new ArrayList<>();
    private String[] getFile = null;
    private String[] putFile = null;
    private String execCmd = null;
    private ChecksumDatabase.CheckMode cliCheck = null;
    private boolean cliNoDb = false;
    /** Variables supplied via --var VARNAME=value; highest expansion priority. */
    private final Map<String, String> cliVars = new java.util.HashMap<>();
    private String versionFrom;
    private String versionTo;
    private String versionName;

    public static class Folder {
        public String localDir;
        public String remoteDir;
        public boolean localDb = false;
        public String scpdb;
        public List<String> includes = new ArrayList<>();
        public List<String> excludes = new ArrayList<>();
        public String triggerFrom;
        public String triggerTo;
        public String versionFrom;
        public String versionTo;
        public String versionName;
        public ChecksumDatabase.CheckMode check = ChecksumDatabase.CheckMode.hash;
        public boolean noDb = false;
    }

    public static class CopyWorkerConfig {
        public String destDir;
        public List<Folder> sources = new ArrayList<>();
    }

    private List<CopyWorkerConfig> copyWorkers = new ArrayList<>();

    public List<CopyWorkerConfig> getCopyWorkers() {
        return copyWorkers;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getKeyPath() {
        return keyPath;
    }

    public void setKeyPath(String keyPath) {
        this.keyPath = keyPath;
    }

    public String getPassphrase() {
        return passphrase;
    }

    public void setPassphrase(String passphrase) {
        this.passphrase = passphrase;
    }

    public int getParallelThreads() {
        return parallelThreads;
    }

    public long getWatchDelayMs() {
        return watchDelayMs;
    }

    public void setWatchDelayMs(long watchDelayMs) {
        this.watchDelayMs = watchDelayMs;
    }

    public boolean getCompress() {
        return compress;
    }

    public void setCompress(boolean compress) {
        this.compress = compress;
    }

    public boolean getCleanup() {
        return cleanup;
    }

    public void setCleanup(boolean cleanup) {
        this.cleanup = cleanup;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isWatch() {
        return watch;
    }

    public void setWatch(boolean watch) {
        this.watch = watch;
    }

    public boolean isColor() {
        return color;
    }

    public void setColor(boolean color) {
        this.color = color;
    }

    public List<String> getTextExtensions() {
        return textExtensions;
    }

    public List<Folder> getFolders() {
        return folders;
    }

    public String getCreateFolder() {
        return createFolder;
    }

    public void setCreateFolder(String createFolder) {
        this.createFolder = createFolder;
    }

    public List<String> getCreateIncludes() {
        return createIncludes;
    }

    public List<String> getCreateExcludes() {
        return createExcludes;
    }

    public String[] getGetFile() {
        return getFile;
    }

    public void setGetFile(String[] getFile) {
        this.getFile = getFile;
    }

    public String[] getPutFile() {
        return putFile;
    }

    public void setPutFile(String[] putFile) {
        this.putFile = putFile;
    }

    public String getExecCmd() {
        return execCmd;
    }

    public void setExecCmd(String execCmd) {
        this.execCmd = execCmd;
    }

    public ChecksumDatabase.CheckMode getCliCheck() {
        return cliCheck;
    }

    public void setCliCheck(ChecksumDatabase.CheckMode cliCheck) {
        this.cliCheck = cliCheck;
    }

    public boolean isCliNoDb() {
        return cliNoDb;
    }

    public String getVersionFrom() {
        return versionFrom;
    }

    public String getVersionTo() {
        return versionTo;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setCliNoDb(boolean cliNoDb) {
        this.cliNoDb = cliNoDb;
    }

    /** Add a variable override from --var VARNAME=value. */
    public void addCliVar(String name, String value) {
        cliVars.put(name, value);
    }

    public boolean isTextFile(String fileName) {
        for (String ext : textExtensions) {
            if (fileName.endsWith(ext))
                return true;
        }
        return false;
    }

    public static WatchScpConfig load(String path) throws IOException {
        return load(path, new WatchScpConfig());
    }

    public static WatchScpConfig load(String path, WatchScpConfig config) throws IOException {
        if ("-".equals(path)) {
            // Read config from stdin
            return loadFromReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8), config);
        }
        try (java.io.FileReader fr = new java.io.FileReader(path)) {
            return loadFromReader(fr, config);
        }
    }

    /**
     * Expand ${VARNAME} placeholders in a config value string.
     * Priority: real environment variable (System.getenv) > config-defined default (ENV.VARNAME= line).
     * Throws IOException if a variable is not found in either source.
     */
    private static String expandVars(String value, Map<String, String> cliVars, Map<String, String> envDefaults) throws IOException {
        if (!value.contains("${")) return value;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start == -1) {
                sb.append(value, i, value.length());
                break;
            }
            sb.append(value, i, start);
            int end = value.indexOf('}', start + 2);
            if (end == -1) {
                throw new IOException("Config error: unclosed '${' in value: " + value);
            }
            String varName = value.substring(start + 2, end);
            // Priority: 1. --var CLI flag  2. real env var  3. ENV.X= config default
            String expanded = cliVars.get(varName);
            if (expanded == null) expanded = System.getenv(varName);
            if (expanded == null) expanded = envDefaults.get(varName);
            if (expanded == null) {
                throw new IOException(
                    "Config error: variable '${" + varName + "}' is not defined.\n" +
                    "  Set it via: --var " + varName + "=value  |  env var " + varName +
                    "  |  ENV." + varName + "= in config.");
            }
            sb.append(expanded);
            i = end + 1;
        }
        return sb.toString();
    }

    public static WatchScpConfig loadFromReader(java.io.Reader source, WatchScpConfig config) throws IOException {
        // Buffer all lines for two-pass processing
        List<String> allLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) allLines.add(line);
        }

        // --- Pass 1: collect ENV.VARNAME= defaults from global scope ---
        Map<String, String> envDefaults = new java.util.HashMap<>();
        boolean inGlobal = true;
        for (String rawLine : allLines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[")) { inGlobal = false; continue; }
            if (!inGlobal) continue;
            int eqIdx = line.indexOf('=');
            if (eqIdx == -1) continue;
            String key = line.substring(0, eqIdx).trim();
            String val = line.substring(eqIdx + 1).trim();
            if (key.startsWith("ENV.")) {
                String varName = key.substring(4);
                // --var CLI and real env var both win; only register default if neither is set
                if (!config.cliVars.containsKey(varName) && System.getenv(varName) == null) {
                    // Expand using only vars collected so far (defined-before-use rule)
                    envDefaults.put(varName, expandVars(val, config.cliVars, envDefaults));
                }
            }
        }

        // --- Pass 2: full parsing with variable expansion ---
        Folder currentFolder = null;
        for (String rawLine : allLines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";"))
                continue;

            if (line.equalsIgnoreCase("[folder]")) {
                currentFolder = new Folder();
                config.folders.add(currentFolder);
                continue;
            }

            if (line.equalsIgnoreCase("[file]")) {
                currentFolder = new Folder();
                currentFolder.check = ChecksumDatabase.CheckMode.mtime_size;
                currentFolder.noDb = true;
                config.folders.add(currentFolder);
                continue;
            }

            if (line.equalsIgnoreCase("[local-folder]")) {
                CopyWorkerConfig cw = new CopyWorkerConfig();
                config.copyWorkers.add(cw);
                currentFolder = null;
                continue;
            }

            if (line.equalsIgnoreCase("[source]")) {
                if (config.copyWorkers.isEmpty()) {
                    throw new IOException("[source] found before [local-folder]");
                }
                currentFolder = new Folder();
                config.copyWorkers.get(config.copyWorkers.size() - 1).sources.add(currentFolder);
                continue;
            }

            int eqIdx = line.indexOf('=');
            if (eqIdx == -1) continue;

            String key = line.substring(0, eqIdx).trim();
            // ENV.VARNAME= lines are consumed in pass 1; skip here
            if (key.startsWith("ENV.")) continue;

            // Expand ${VARNAME} in the value (fail fast if undefined)
            String value = expandVars(line.substring(eqIdx + 1).trim(), config.cliVars, envDefaults);

            if (currentFolder == null) {
                if (key.equals("version_from")) {
                    config.versionFrom = value;
                } else if (key.equals("version_to")) {
                    config.versionTo = value;
                } else if (key.equals("version_name")) {
                    config.versionName = value;
                }
            }

            if (currentFolder != null) {
                if (key.equals("local_dir"))
                    currentFolder.localDir = value;
                else if (key.equals("local_file")) {
                    java.io.File f = new java.io.File(value);
                    currentFolder.localDir = f.getParent() == null ? "." : f.getParent().replace('\\', '/');
                    currentFolder.includes.add(f.getName());
                } else if (key.equals("remote_dir"))
                    currentFolder.remoteDir = value;
                else if (key.equals("scpdb"))
                    currentFolder.scpdb = value;
                else if (key.equals("local_db"))
                    currentFolder.localDb = value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes");
                else if (key.equals("include") || key.equals("includes")) {
                    for (String p : value.split(","))
                        currentFolder.includes.add(p.trim());
                } else if (key.equals("exclude") || key.equals("excludes")) {
                    for (String p : value.split(","))
                        currentFolder.excludes.add(p.trim());
                } else if (key.equals("trigger_from")) {
                    currentFolder.triggerFrom = value;
                } else if (key.equals("trigger_to")) {
                    currentFolder.triggerTo = value;
                } else if (key.equals("version_from")) {
                    currentFolder.versionFrom = value;
                } else if (key.equals("version_to")) {
                    currentFolder.versionTo = value;
                } else if (key.equals("version_name")) {
                    currentFolder.versionName = value;
                } else if (key.equals("check")) {
                    if (value.equalsIgnoreCase("mtime_size")) {
                        currentFolder.check = ChecksumDatabase.CheckMode.mtime_size;
                    } else {
                        currentFolder.check = ChecksumDatabase.CheckMode.hash;
                    }
                } else if (key.equals("no_db")) {
                    currentFolder.noDb = value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes");
                }
            } else if (!config.copyWorkers.isEmpty()) {
                CopyWorkerConfig cw = config.copyWorkers.get(config.copyWorkers.size() - 1);
                if (key.equals("dest_dir"))
                    cw.destDir = value;
            } else {
                if (key.equals("host") && config.host.isEmpty()) {
                    int colonIdx = value.indexOf(':');
                    if (colonIdx != -1) {
                        config.host = value.substring(0, colonIdx);
                        config.port = Integer.parseInt(value.substring(colonIdx + 1));
                    } else {
                        config.host = value;
                    }
                } else if (key.equals("username") && config.username.isEmpty())
                    config.username = value;
                else if (key.equals("password") && config.password.isEmpty())
                    config.password = value;
                else if (key.equals("key_path") && config.keyPath.isEmpty())
                    config.keyPath = value;
                else if (key.equals("passphrase") && config.passphrase.isEmpty())
                    config.passphrase = value;
                else if (key.equals("parallel_threads"))
                    config.parallelThreads = Integer.parseInt(value);
                else if (key.equals("watch_delay_ms"))
                    config.watchDelayMs = Long.parseLong(value);
                else if (key.equals("compress"))
                    config.compress = value.equalsIgnoreCase("true") || value.equals("1")
                            || value.equalsIgnoreCase("yes");
                else if (key.equals("cleanup"))
                    config.cleanup = value.equalsIgnoreCase("true") || value.equals("1")
                            || value.equalsIgnoreCase("yes");
                else if (key.equals("dry_run") || key.equals("dryRun"))
                    config.dryRun = value.equalsIgnoreCase("true") || value.equals("1")
                            || value.equalsIgnoreCase("yes");
                else if (key.equals("color"))
                    config.color = value.equalsIgnoreCase("true") || value.equals("1")
                            || value.equalsIgnoreCase("yes");
                else if (key.equals("exec_cmd") || key.equals("exec"))
                    config.execCmd = value;
                else if (key.equals("text_extensions")) {
                    config.textExtensions.clear();
                    for (String ext : value.split(","))
                        config.textExtensions.add(ext.trim());
                }
            }
        }

        // Check environment variables if password/passphrase not set
        if (config.password.isEmpty()) {
            String envPwd = System.getenv("SYNC_SSH_PWD");
            if (envPwd != null) config.password = envPwd;
        }
        if (config.passphrase.isEmpty()) {
            String envPass = System.getenv("SYNC_SSH_PASSPHRASE");
            if (envPass != null) config.passphrase = envPass;
        }

        config.resolveSshConfig();

        if (config.cliCheck != null) {
            for (Folder f : config.folders) f.check = config.cliCheck;
        }
        if (config.cliNoDb) {
            for (Folder f : config.folders) f.noDb = true;
        }
        return config;
    }


    public void resolveSshConfig() {
        if (host == null || host.isEmpty())
            return;

        String home = System.getProperty("user.home");
        if (home == null)
            return;

        java.io.File sshConfig = new java.io.File(home, ".ssh/config");
        if (!sshConfig.exists())
            return;

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(sshConfig))) {
            String line;
            boolean match = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                String[] parts = line.split("\\s+", 2);
                if (parts.length < 2) {
                    if (parts[0].equalsIgnoreCase("Host")) {
                        match = false;
                    }
                    continue;
                }
                String key = parts[0];
                String value = parts[1].trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }

                if (key.equalsIgnoreCase("Host")) {
                    match = false;
                    for (String h : value.split("\\s+")) {
                        if (h.equals(host)) {
                            match = true;
                            break;
                        }
                    }
                } else if (match) {
                    if (key.equalsIgnoreCase("HostName")) {
                        host = value;
                    } else if (key.equalsIgnoreCase("User") && username.isEmpty()) {
                        username = value;
                    } else if (key.equalsIgnoreCase("Port") && port == 22) {
                        try {
                            port = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    } else if (key.equalsIgnoreCase("IdentityFile") && (keyPath == null || keyPath.isEmpty())) {
                        if (value.startsWith("~")) {
                            value = home + value.substring(1).replace('\\', '/').replace("//", "/");
                        }
                        keyPath = value;
                    }
                }
            }
        } catch (java.io.IOException e) {
            // ignore
        }
    }
}
