// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Permanent "do not propose this again" records, stored in {@code .jcodebuddy/conf/tool-refusals.json}.
 *
 * <p>A normal reject only removes the current proposal; the next rebuild would produce it again. A tool
 * that can identify a stable case (for example "builder for this file") offers a permanent refusal,
 * which is recorded here and checked before its next preview. The file is in the tracked {@code conf/}
 * subtree because it is a project decision, not host state.</p>
 */
public final class ToolRefusals {

    public static final String FILE = "tool-refusals.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public ToolRefusals(Path projectRoot) {
        this.file = projectRoot.resolve(".jcodebuddy").resolve("conf").resolve(FILE);
    }

    public synchronized boolean contains(String toolId, String caseKey) {
        if (toolId == null || caseKey == null || caseKey.isBlank()) return false;
        for (Map<?, ?> entry : entries()) {
            if (toolId.equals(String.valueOf(entry.get("tool")))
                    && caseKey.equals(String.valueOf(entry.get("caseKey")))) {
                return true;
            }
        }
        return false;
    }

    public synchronized void add(String toolId, String caseKey, String path, String reason) {
        if (toolId == null || caseKey == null || caseKey.isBlank()) return;
        List<Map<String, Object>> entries = entries();
        for (Map<?, ?> entry : entries) {
            if (toolId.equals(String.valueOf(entry.get("tool")))
                    && caseKey.equals(String.valueOf(entry.get("caseKey")))) {
                return;
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tool", toolId);
        entry.put("caseKey", caseKey);
        entry.put("path", path == null ? "" : path);
        entry.put("reason", reason == null ? "" : reason);
        entry.put("createdAt", Instant.now().toString());
        entries.add(entry);
        write(entries);
    }

    public synchronized List<Map<String, Object>> entries() {
        if (!Files.isRegularFile(file)) return new ArrayList<>();
        try {
            Map<?, ?> root = MAPPER.readValue(Files.readString(file), Map.class);
            Object raw = root.get("refusals");
            if (!(raw instanceof List<?> list)) return new ArrayList<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    result.add(copy);
                }
            }
            return result;
        } catch (IOException | RuntimeException malformed) {
            return new ArrayList<>();
        }
    }

    private void write(List<Map<String, Object>> entries) {
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("refusals", entries);
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        } catch (IOException ignored) {
            // A refusal that cannot be persisted must not take the agent down; the caller reports it.
        }
    }
}
