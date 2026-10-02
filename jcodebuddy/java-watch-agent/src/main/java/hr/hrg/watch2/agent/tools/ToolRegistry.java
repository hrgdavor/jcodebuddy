// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for all available ActionTools.
 */
public class ToolRegistry {
    private final Map<String, ActionTool> tools = new HashMap<>();

    public void register(ActionTool tool) {
        tools.put(tool.getName().toLowerCase(), tool);
    }

    public Optional<ActionTool> getTool(String name) {
        return Optional.ofNullable(tools.get(name.toLowerCase()));
    }

    public Collection<ActionTool> getAllTools() {
        return tools.values();
    }
}
