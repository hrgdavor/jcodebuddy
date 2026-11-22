// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Project tool configuration: {@code .jcodebuddy/conf/agent.json}.
 *
 * <pre>{@code
 * { "tools": { "builder": { "activation": "explicit" } } }
 * }</pre>
 *
 * <p>Activation is {@code explicit} (only a marker/API request), {@code auto} (a source batch may
 * propose it) or {@code disabled}. Missing entries are explicit, so adding a tool never starts
 * generating without a project decision.</p>
 */
public final class AgentToolConfig {

    public static final String FILE = "agent.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> activation = new LinkedHashMap<>();
    private final Map<String, String> mode = new LinkedHashMap<>();

    public static AgentToolConfig read(Path projectRoot) {
        AgentToolConfig config = new AgentToolConfig();
        if (projectRoot == null) return config;
        Path file = projectRoot.resolve(".jcodebuddy").resolve("conf").resolve(FILE);
        if (!Files.isRegularFile(file)) return config;
        try {
            Map<?, ?> root = MAPPER.readValue(Files.readString(file), Map.class);
            Object tools = root.get("tools");
            if (tools instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> settings) {
                        Object mode = settings.get("activation");
                        if (mode != null) {
                            config.activation.put(String.valueOf(entry.getKey()),
                                    String.valueOf(mode).toLowerCase());
                        }
                        Object applyMode = settings.get("mode");
                        if (applyMode != null) {
                            config.mode.put(String.valueOf(entry.getKey()),
                                    String.valueOf(applyMode).toLowerCase());
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException malformed) {
            // A malformed config must not make the agent unusable; explicit is the safe default.
        }
        return config;
    }

    public String activation(String toolId) {
        String mode = activation.get(toolId);
        return mode == null || mode.isBlank() ? "explicit" : mode;
    }

    public boolean auto(String toolId) {
        return "auto".equals(activation(toolId));
    }

    public boolean disabled(String toolId) {
        return "disabled".equals(activation(toolId)) || "off".equals(activation(toolId));
    }

    /** {@code review} keeps proposals pending; {@code active} applies them and leaves them revertible. */
    public String mode(String toolId) {
        String value = mode.get(toolId);
        return value == null || value.isBlank() ? "review" : value;
    }

    public boolean active(String toolId) {
        return "active".equals(mode(toolId)) || "apply".equals(mode(toolId))
                || "active".equals(activation(toolId));
    }
}
