// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class DirectoryScanner {
    private final Path sourcePath;
    private final FileFilter filter;
    private final ChecksumDatabase db;

    public static class ScanResult {
        public final String relPath;
        public final String checksum;
        public final long mtime;

        public ScanResult(String relPath, String checksum, long mtime) {
            this.relPath = relPath;
            this.checksum = checksum;
            this.mtime = mtime;
        }
    }

    public DirectoryScanner(Path sourcePath, FileFilter filter, ChecksumDatabase db) {
        this.sourcePath = sourcePath;
        this.filter = filter;
        this.db = db;
    }

    public java.util.Map<String, ChecksumDatabase.DbEntry> scan() throws IOException {
        java.util.Map<String, ChecksumDatabase.DbEntry> results = new ConcurrentHashMap<>();
        try (Stream<Path> paths = java.nio.file.Files.walk(sourcePath)) {
            paths.parallel()
                    .filter(java.nio.file.Files::isRegularFile)
                    .filter(filter::shouldInclude)
                    .forEach(path -> {
                        Path relativePath = sourcePath.relativize(path);
                        String relPathStr = relativePath.toString().replace('\\', '/');
                        File file = path.toFile();
                        try {
                            boolean isText = WatchConstants.isTextFile(file.getName());
                            String checksum = db.calculateChecksum(file, isText);
                            long mtime = file.lastModified();
                            results.put(relPathStr, new ChecksumDatabase.DbEntry(checksum, mtime, file.length()));
                        } catch (IOException e) {
                            // Log or handle error
                        }
                    });
        }
        return results;
    }
}
