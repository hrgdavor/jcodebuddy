// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Scans the project directory and notifies a processor of files.
 */
public class ProjectScanner {
    private final Path root;
    private final FileProcessor processor;

    public interface FileProcessor {
        void process(Path path, BasicFileAttributes attrs) throws IOException;
    }

    public ProjectScanner(Path root, FileProcessor processor) {
        this.root = root;
        this.processor = processor;
    }

    public void scan() throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName().toString();
                if (name.equals(".git") || name.equals("target") || name.equals(".watch")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                processor.process(file, attrs);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
