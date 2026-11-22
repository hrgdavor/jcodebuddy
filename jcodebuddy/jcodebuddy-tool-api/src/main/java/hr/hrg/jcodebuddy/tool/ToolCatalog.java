package hr.hrg.jcodebuddy.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The explicit tool registry. A catalog is committed source, not a classpath scan: an agent or build names
 * the tools it offers, so a missing tool is a compile error rather than a runtime mystery.
 */
public final class ToolCatalog {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolCatalog register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("a tool is required");
        }
        if (tools.putIfAbsent(tool.id(), tool) != null) {
            throw new IllegalArgumentException("duplicate tool id: " + tool.id());
        }
        return this;
    }

    public Optional<Tool> find(String id) {
        return Optional.ofNullable(tools.get(id));
    }

    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    public Collection<String> ids() {
        return List.copyOf(tools.keySet());
    }
}
