// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import hr.hrg.jcodebuddy.ipc.ToolException;
import hr.hrg.jcodebuddy.ipc.ToolMethods;
import hr.hrg.watch2.agent.ProjectWatcher;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's operations, shared by the standalone {@code /rpc} route and the tool socket the webview
 * host proxies. The proposal/batch verbs live in {@link AgentTools}; this class adds process status.
 */
public final class AgentMethods implements ToolMethods {

    public static final String TOOL = "jcodebuddy-agent";

    private final Path root;
    private final AgentTools tools;
    private volatile ProjectWatcher watcher;

    public AgentMethods(Path root) {
        this.root = root == null ? Path.of(".").toAbsolutePath().normalize()
                : root.toAbsolutePath().normalize();
        this.tools = new AgentTools(this.root);
    }

    public AgentTools tools() {
        return tools;
    }

    public void setWatcher(ProjectWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    public Collection<String> capabilities() {
        return List.of("agent.status",
                "tool.list", "tool.preview", "tool.apply", "tool.reject", "tool.batches",
                "tool.applyBatch", "tool.revertBatch", "tool.applyFile", "tool.revertFile");
    }

    @Override
    public Object call(String method, Map<String, Object> params) throws ToolException {
        return switch (method) {
            case "agent.status" -> status();
            case "tool.list" -> tools.list();
            case "tool.preview" -> tools.preview(params);
            case "tool.apply" -> tools.apply(requiredString(params, "proposalId"));
            case "tool.reject" -> tools.reject(requiredString(params, "proposalId"),
                    booleanValue(params, "permanent"));
            case "tool.batches" -> tools.batches();
            case "tool.applyBatch" -> tools.applyBatch(requiredString(params, "batchId"));
            case "tool.revertBatch" -> tools.revertBatch(requiredString(params, "batchId"));
            case "tool.applyFile" -> tools.applyFile(requiredString(params, "proposalId"),
                    requiredString(params, "path"));
            case "tool.revertFile" -> tools.revertFile(requiredString(params, "proposalId"),
                    requiredString(params, "path"));
            default -> throw ToolException.methodNotFound(method);
        };
    }

    private Map<String, Object> status() {
        tools.touchWebview();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "running");
        result.put("root", root.toString().replace('\\', '/'));
        ProjectWatcher current = watcher;
        boolean watching = current != null && current.ensureLive();
        result.put("watching", watching);
        result.put("watchProblem", current == null ? "watcher has not started" : current.attachProblem());
        result.put("configurationProblem", tools.annotationProblem());
        result.put("webviewAlive", tools.webviewAlive());
        result.put("batches", tools.batches().size());
        result.put("tools", tools.catalog().stream().map(hr.hrg.jcodebuddy.tool.Tool::id).toList());
        return result;
    }

    private static boolean booleanValue(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String requiredString(Map<String, Object> params, String key) throws ToolException {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw ToolException.invalidParams("missing '" + key + "'");
        }
        return String.valueOf(value);
    }
}
