// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.core;

import hr.hrg.wyhash.Wyhash64;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks file checksums and modification times to detect offline changes.
 */
public class MetadataCache {
    private final Map<String, FileInfo> cache = new ConcurrentHashMap<>();
    private final Path projectRoot;
    private final Path cacheFile;
    @SuppressWarnings("unused")
    private final String toolSetName;
    private final Set<String> visited = ConcurrentHashMap.newKeySet();

    public MetadataCache(Path projectRoot, String toolSetName) {
        this.projectRoot = projectRoot;
        this.toolSetName = toolSetName;
        this.cacheFile = projectRoot.resolve(".watch").resolve("metadata").resolve(toolSetName).resolve("metadata.db");
    }

    public static record FileInfo(String path, String checksum, long lastModified) {
    }

    public void markVisited(String relPath) {
        visited.add(relPath);
    }

    public void resetVisited() {
        visited.clear();
    }

    public void load() throws IOException {
        if (!Files.exists(cacheFile))
            return;

        try (BufferedReader reader = Files.newBufferedReader(cacheFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;

                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    String checksum = parts[0];
                    long mtime = Long.parseLong(parts[1]);
                    String path = parts[2];
                    cache.put(path, new FileInfo(path, checksum, mtime));
                }
            }
        }
    }

    public void save(boolean cleanupUnvisited) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        if (cleanupUnvisited) {
            cache.keySet().removeIf(path -> !visited.contains(path));
        }
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(cacheFile))) {
            List<String> paths = new ArrayList<>(cache.keySet());
            Collections.sort(paths);
            for (String path : paths) {
                FileInfo info = cache.get(path);
                writer.println(info.checksum() + "\t" + info.lastModified() + "\t" + info.path());
            }
        }
    }

    public void update(String relPath, String checksum, long lastModified) {
        cache.put(relPath, new FileInfo(relPath, checksum, lastModified));
        markVisited(relPath);
    }

    public FileInfo get(String relPath) {
        return cache.get(relPath);
    }

    public String calculateChecksum(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        long hash = Wyhash64.hash(0, content);
        return String.format("%016x", hash);
    }

    public boolean hasChanged(Path file) throws IOException {
        String relPath = projectRoot.relativize(file).toString().replace('\\', '/');
        FileInfo stored = cache.get(relPath);
        if (stored == null)
            return true;

        long currentMtime = Files.getLastModifiedTime(file).toMillis();
        // If mtime is the same, assume it hasn't changed (performance optimization)
        if (stored.lastModified() == currentMtime)
            return false;

        // If mtime changed, verify with checksum
        String currentChecksum = calculateChecksum(file);
        return !stored.checksum().equals(currentChecksum);
    }

    public Map<String, FileInfo> getCache() {
        return Collections.unmodifiableMap(cache);
    }
}
