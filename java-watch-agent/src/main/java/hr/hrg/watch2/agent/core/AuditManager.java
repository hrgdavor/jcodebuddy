// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.core;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import hr.hrg.wyhash.Wyhash64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles snapshotting and audit trail for file modifications.
 */
public class AuditManager {
    private static final Logger log = LoggerFactory.getLogger(AuditManager.class);
    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path projectRoot;
    private final Path auditRoot;
    @SuppressWarnings("unused")
    private final String toolSetName;

    public AuditManager(Path projectRoot, String toolSetName) {
        this.projectRoot = projectRoot;
        this.toolSetName = toolSetName;
        this.auditRoot = projectRoot.resolve(".watch").resolve("metadata").resolve(toolSetName).resolve("audit");
    }

    public AuditSession startSession(String actionName) throws IOException {
        Files.createDirectories(auditRoot);
        String timestamp = LocalDateTime.now().format(DIR_FORMAT);
        Path sessionDir = auditRoot.resolve(timestamp + "_" + actionName);
        Files.createDirectories(sessionDir);
        Files.createDirectories(sessionDir.resolve("before"));
        Files.createDirectories(sessionDir.resolve("after"));

        return new AuditSession(sessionDir);
    }

    public class AuditSession {
        private final Path sessionDir;
        private final List<FileEntry> entries = new ArrayList<>();

        public AuditSession(Path sessionDir) {
            this.sessionDir = sessionDir;
        }

        public void recordBefore(Path filePath) throws IOException {
            Path absFile = filePath.toAbsolutePath().normalize();
            Path relPath = projectRoot.relativize(absFile);
            Path target = sessionDir.resolve("before").resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.copy(absFile, target);

            byte[] content = Files.readAllBytes(absFile);
            String hash = String.format("%016x", Wyhash64.hash(0, content));

            entries.add(new FileEntry(relPath.toString().replace('\\', '/'), hash, null, "CHANGE"));
        }

        public void recordAfter(Path filePath, String newContent) throws IOException {
            Path absFile = filePath.toAbsolutePath().normalize();
            Path relPath = projectRoot.relativize(absFile);
            Path target = sessionDir.resolve("after").resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, newContent);

            String newHash = String.format("%016x", Wyhash64.hash(0, newContent.getBytes()));

            boolean found = false;
            for (FileEntry entry : entries) {
                if (entry.file.equals(relPath.toString().replace('\\', '/'))) {
                    entry.newHash = newHash;
                    found = true;
                    break;
                }
            }
            if (!found) {
                entries.add(new FileEntry(relPath.toString().replace('\\', '/'), null, newHash, "ADD"));
            }
        }

        public void apply() throws IOException {
            for (FileEntry entry : entries) {
                Path source = sessionDir.resolve("after").resolve(entry.file);
                Path target = projectRoot.resolve(entry.file);
                if (Files.exists(source)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else if ("DELETE".equals(entry.status)) {
                    Files.deleteIfExists(target);
                }
            }
        }

        public void revert() throws IOException {
            for (FileEntry entry : entries) {
                Path source = sessionDir.resolve("before").resolve(entry.file);
                Path target = projectRoot.resolve(entry.file);
                if (Files.exists(source)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else if ("ADD".equals(entry.status)) {
                    Files.deleteIfExists(target);
                }
            }
        }

        public void finalizeSession(String summaryText) throws IOException {
            // Write manifest.json
            MAPPER.writeValue(sessionDir.resolve("manifest.json").toFile(), Map.of("files", entries));

            // Write summary.md
            StringBuilder sb = new StringBuilder();
            sb.append("# Audit Summary: ").append(sessionDir.getFileName()).append("\n\n");
            sb.append(summaryText).append("\n\n");
            sb.append("## Files Changed\n\n");
            for (FileEntry entry : entries) {
                sb.append("- ").append(entry.file).append(" (").append(entry.status).append(")\n");
            }
            Files.writeString(sessionDir.resolve("summary.md"), sb.toString());

            log.info("Audit session finalized: {}", sessionDir);
        }

        public List<FileEntry> getEntries() {
            return entries;
        }

        public Path getSessionDir() {
            return sessionDir;
        }
    }

    public static class FileEntry {
        public String file;
        public String oldHash;
        public String newHash;
        public String status;

        public FileEntry() {
        } // For Jackson

        public FileEntry(String file, String oldHash, String newHash, String status) {
            this.file = file;
            this.oldHash = oldHash;
            this.newHash = newHash;
            this.status = status;
        }
    }
}
