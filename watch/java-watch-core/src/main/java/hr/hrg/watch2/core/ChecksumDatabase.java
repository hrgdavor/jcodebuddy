// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import java.io.*;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import hr.hrg.wyhash.Wyhash64;

/**
 * Manages a checksum database (.scpdb) to track uploaded files and avoid
 * unnecessary transfers.
 */
public class ChecksumDatabase {
    public static enum CheckMode { hash, mtime_size };
    private static final String DATABASE_FILENAME = ".scpdb";

    public static class DbEntry {
        public final String checksum;
        public final long mtime;
        public final long size;

        public DbEntry(String checksum, long mtime, long size) {
            this.checksum = checksum;
            this.mtime = mtime;
            this.size = size;
        }
    }

    private final Map<String, DbEntry> checksums = new ConcurrentHashMap<>();

    public static String getDatabaseFilename() {
        return DATABASE_FILENAME;
    }

    public void load(File file) throws IOException {
        if (!file.exists())
            return;
        try (InputStream in = new FileInputStream(file)) {
            load(in);
        }
    }

    public void load(InputStream in) throws IOException {
        int corruptedCount = 0;
        String firstCorruptedLine = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;

                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    String checksum = parts[0];
                    String mtimeStr = parts[1];
                    String sizeStr = parts.length >= 4 ? parts[2] : "0";
                    String path = parts.length >= 4 ? parts[3] : parts[2];

                    try {
                        long mtime = Long.parseLong(mtimeStr);
                        long size = Long.parseLong(sizeStr);
                        checksums.put(path, new DbEntry(checksum, mtime, size));
                    } catch (NumberFormatException e) {
                        corruptedCount++;
                        if (firstCorruptedLine == null) {
                            firstCorruptedLine = line;
                        }
                    }
                } else {
                    corruptedCount++;
                }
            }
        }

        if (corruptedCount > 0) {
            System.err.println("Warning: Skipped " + corruptedCount + " corrupted line(s) in checksum database.");
            System.err.println("Expected format: checksum\\ttimestamp\\tpath");
            if (firstCorruptedLine != null) {
                System.err.println("First corrupted line: " + firstCorruptedLine);
            }
            System.err.println("Continuing with partial database...\n");
        }
    }

    public void save(File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            save(out);
        }
    }

    public void save(OutputStream out) throws IOException {
        List<String> paths = new ArrayList<>(checksums.keySet());
        Collections.sort(paths);

        PrintWriter writer = new PrintWriter(out);
        for (String path : paths) {
            DbEntry entry = checksums.get(path);
            writer.println(entry.checksum + "\t" + entry.mtime + "\t" + entry.size + "\t" + path);
        }
        writer.flush();
    }

    public void setChecksum(String relPath, String checksum, long mtime, long size) {
        checksums.put(relPath, new DbEntry(checksum, mtime, size));
    }

    public String calculateChecksum(File file, boolean isText) throws IOException {
        if (isText) {
            try (InputStream input = new FileInputStream(file)) {
                byte[] content = input.readAllBytes();
                content = normalizeLineEndings(content);
                long hash = Wyhash64.hash(0, content);
                return String.format("%016x", hash);
            }
        } else {
            Wyhash64.Streaming hasher = new Wyhash64.Streaming(0);
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[12 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    hasher.update(buffer, 0, read);
                }
                long hash = hasher.finalHash();
                return String.format("%016x", hash);
            }
        }
    }

    private byte[] normalizeLineEndings(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < content.length) {
            if (i + 1 < content.length && content[i] == '\r' && content[i + 1] == '\n') {
                out.write('\n');
                i += 2;
            } else if (content[i] == '\r') {
                out.write('\n');
                i += 1;
            } else {
                out.write(content[i]);
                i += 1;
            }
        }
        return out.toByteArray();
    }

    public boolean needsUpload(File file, String relPath, String currentChecksum, boolean isText, CheckMode checkMode) {
        DbEntry stored = checksums.get(relPath);
        if (stored == null) return true;
        if (checkMode == CheckMode.mtime_size) {
            // Upload if local is newer, OR binary file changed size
            return file.lastModified() > stored.mtime
                || (!isText && file.length() != stored.size);
        }
        return !currentChecksum.equals(stored.checksum);
    }

    public void clear() {
        checksums.clear();
    }
}
