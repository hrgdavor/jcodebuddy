// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import hr.hrg.jcodebuddy.tool.ApplyResult;
import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolCatalog;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRefusal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.WriteCoordinator;
import hr.hrg.jcodebuddy.tool.builder.BuilderTool;
import hr.hrg.jcodebuddy.builder.RecordBuilderProcessor;
import hr.hrg.jcodebuddy.tool.java.RecordBuilderTool;
import hr.hrg.jcodebuddy.ipc.ToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The proposal and change-batch path for agent tools.
 *
 * <p>Previewing never writes. A review-mode proposal waits for accept; an active-mode proposal is
 * applied through the coordinator immediately but stays in its batch so a file or the whole batch can be
 * reverted. The webview heartbeat is what lets explicit tools propose automatically while a page is
 * alive without changing editor buffers.</p>
 */
public final class AgentTools {

    private static final long WEBVIEW_TTL_MS = 20_000;

    private final Path root;
    private final ToolCatalog catalog = new ToolCatalog();
    private final WriteCoordinator coordinator;
    private final EditorNotifier editorNotifier;
    private volatile AgentToolConfig config;
    private final ToolRefusals refusals;
    private final Path configFile;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, List<String>> batches = new ConcurrentHashMap<>();
    private final Map<String, String> ownWrites = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile long lastWebview;
    private volatile long configModified = -1;
    private volatile String lastAnnotationProblem = "";

    public AgentTools(Path root) {
        this.root = root == null ? Path.of(".").toAbsolutePath().normalize()
                : root.toAbsolutePath().normalize();
        this.coordinator = new WriteCoordinator(this.root);
        this.editorNotifier = new EditorNotifier(this.root);
        this.config = AgentToolConfig.read(this.root);
        this.configFile = this.root.resolve(".jcodebuddy").resolve("conf").resolve(AgentToolConfig.FILE);
        this.refusals = new ToolRefusals(this.root);
        catalog.register(new BuilderTool());
        catalog.register(new hr.hrg.jcodebuddy.tool.java.RecordBuilderTool());
        catalog.register(new hr.hrg.jcodebuddy.tool.java.RecordInterfaceSyncTool());
        catalog.register(new hr.hrg.jcodebuddy.tool.java.AccessorTool());
        catalog.register(new hr.hrg.jcodebuddy.tool.java.ConstructorTool());
        catalog.register(new hr.hrg.jcodebuddy.tool.java.RenameTool());
        catalog.register(new hr.hrg.jcodebuddy.tool.ioc.IocTool());
        catalog.register(new hr.hrg.jcodebuddy.tool.entity.EntityTool());
        for (Tool tool : catalog.tools()) {
            String problem = ToolMarkers.ensure(this.root, config, tool);
            if (!problem.isBlank()) {
                lastAnnotationProblem = problem;
                break;
            }
        }
    }

    public List<Tool> catalog() {
        return catalog.tools();
    }

    public String annotationProblem() {
        return configurationProblem();
    }

    /** The annotation setup must be usable before the agent starts; a missing location or failed write is fatal. */
    public String configurationProblem() {
        String configured = config.annotationProblem();
        return configured.isBlank() ? lastAnnotationProblem : configured;
    }

    private String annotationFor(String id) {
        return catalog.find(id).map(Tool::markerAnnotation).orElse("");
    }

    private boolean requiresMarker(String id) {
        return !annotationFor(id).isBlank();
    }

    private void requireRecordAnnotation(String id) throws ToolException {
        Tool tool = catalog.find(id)
                .orElseThrow(() -> ToolException.invalidParams("no such tool: " + id));
        String problem = ToolMarkers.ensure(root, config, tool);
        if (!problem.isBlank()) {
            throw new ToolException(ToolException.INVALID_PARAMS, problem);
        }
    }

    /** Called by the agent page, builder page and every status/list/batch poll. */
    public void touchWebview() {
        lastWebview = System.currentTimeMillis();
    }

    public boolean webviewAlive() {
        return System.currentTimeMillis() - lastWebview < WEBVIEW_TTL_MS;
    }

    /** Re-read the per-tool config when the file changes, so mode/activation changes need no restart. */
    private void refreshConfig() {
        try {
            long modified = Files.getLastModifiedTime(configFile).toMillis();
            if (modified != configModified) {
                config = AgentToolConfig.read(root);
                configModified = modified;
            }
        } catch (IOException missing) {
            if (configModified != -1) {
                config = AgentToolConfig.read(root);
                configModified = -1;
            }
        }
    }

    /**
     * fs-meta callback. Explicit file tools propose while a webview is alive; auto tools propose even
     * without one. Active tools apply the proposals immediately and leave the batch revertible.
     */
    public void onChanged(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return;
        refreshConfig();
        discardPendingBatches(paths);
        boolean webview = webviewAlive();
        String batchId = "b" + sequence.incrementAndGet();
        if (config.annotationProblem().isBlank()) lastAnnotationProblem = "";
        for (Tool tool : catalog.tools()) {
            if (config.disabled(tool.id()) || !tool.watchesFiles()) continue;
            if (!config.auto(tool.id()) && !webview && !config.active(tool.id())) continue;
            String annotation = annotationFor(tool.id());
            String annotationProblem = annotation.isBlank()
                    ? "" : ToolMarkers.ensure(root, config, tool);
            if (!annotation.isBlank() && !annotationProblem.isBlank()) {
                if (!annotationProblem.equals(lastAnnotationProblem)) {
                    System.err.println("record tooling is not configured: " + annotationProblem);
                    lastAnnotationProblem = annotationProblem;
                }
                continue;
            }
            for (Path path : paths) {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if (!name.endsWith(".java")) continue;
                String relative = relative(path);
                try {
                    String current = coordinator.currentHash(path);
                    if (current.equals(ownWrites.get(relative))) continue;
                    int line = 1;
                    if (tool instanceof RecordBuilderTool) {
                        int marked = annotationLine(Files.readString(path), annotation);
                        if (marked < 0) continue;
                        line = marked;
                    }
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("tool", tool.id());
                    params.put("file", relative);
                    params.put("line", line);
                    params.put("batchId", batchId);
                    if (!annotation.isBlank()) {
                        params.put("annotation", config.annotationPackage().isBlank()
                                ? annotation : config.annotationPackage() + "." + annotation);
                    }
                    Map<String, Object> proposal = preview(params);
                    Object proposalId = proposal.get("proposalId");
                    if (config.active(tool.id()) && proposalId != null) {
                        apply(String.valueOf(proposalId));
                    }
                } catch (IOException | ToolException | RuntimeException ignored) {
                    // A file that is not a case for this tool is normal in a batch.
                }
            }
        }
    }

    /**
     * A new fs-meta batch supersedes a batch that never touched the disk. Keeping the old proposals would let
     * Apply later write text computed from a file revision that no longer exists. Undoing an edit is the same
     * case: the old batch is discarded and, because the tool sees no change, no replacement batch is created.
     * Batches with any applied file are retained so their revert path stays available.
     */
    private void discardPendingBatches(List<Path> paths) {
        Set<String> changed = new LinkedHashSet<>();
        for (Path path : paths) changed.add(relative(path));
        for (Map.Entry<String, List<String>> batch : List.copyOf(batches.entrySet())) {
            boolean applied = false;
            boolean touched = false;
            for (String proposalId : List.copyOf(batch.getValue())) {
                Pending entry = pending.get(proposalId);
                if (entry == null) continue;
                if (!entry.applied.isEmpty()) {
                    applied = true;
                    break;
                }
                for (FileProposal file : entry.proposal.files()) {
                    if (changed.contains(relative(file.path()))) {
                        touched = true;
                        break;
                    }
                }
                if (touched) break;
            }
            if (!applied && touched) {
                for (String proposalId : List.copyOf(batch.getValue())) pending.remove(proposalId);
                batches.remove(batch.getKey());
            }
        }
    }

    public List<Map<String, Object>> list() {
        touchWebview();
        refreshConfig();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : catalog.tools()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tool.id());
            item.put("title", tool.title());
            item.put("version", tool.version());
            item.put("description", "Preview and apply " + tool.title().toLowerCase() + " changes");
            item.put("activation", config.activation(tool.id()));
            item.put("mode", config.mode(tool.id()));
            item.put("watchesFiles", tool.watchesFiles());
            item.put("refusalSupported", tool.supportsPermanentRefusal());
            item.put("page", tool.standalonePage());
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> preview(Map<String, Object> params) throws ToolException {
        refreshConfig();
        String id = string(params.get("tool"));
        String file = string(params.get("file"));
        if (id == null || id.isBlank()) {
            throw ToolException.invalidParams("missing 'tool'");
        }
        if (file == null || file.isBlank()) {
            throw ToolException.invalidParams("missing 'file'");
        }
        Tool tool = catalog.find(id)
                .orElseThrow(() -> ToolException.invalidParams("no such tool: " + id));
        if (config.disabled(id)) {
            throw new ToolException(ToolException.INVALID_PARAMS, "tool is disabled by project config: " + id);
        }
        if (requiresMarker(id)) {
            requireRecordAnnotation(id);
            params = new LinkedHashMap<>(params);
            String annotation = annotationFor(id);
            params.put("annotation", config.annotationPackage().isBlank()
                    ? annotation : config.annotationPackage() + "." + annotation);
        }
        Path path = Path.of(file);
        if (!path.isAbsolute()) path = root.resolve(path);
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw ToolException.invalidParams("file escapes the project: " + file);
        }
        if (!Files.isRegularFile(path)) {
            throw ToolException.invalidParams("no such file: " + file);
        }
        int line = number(params.get("line"), 1);
        ToolRequest request = new ToolRequest(root, path, line, params);
        String caseKey = tool.caseKey(request);
        boolean refusalSupported = tool.supportsPermanentRefusal();
        if (refusalSupported && refusals.contains(id, caseKey)) {
            return Map.of("tool", id, "files", List.of(), "status", "refused",
                    "caseKey", caseKey, "message", "case is permanently refused");
        }
        try {
            ToolProposal proposal = tool.preview(request);
            if (proposal == null || proposal.empty()) {
                return Map.of("tool", id, "files", List.of(), "message", "no change");
            }
            String batchId = string(params.get("batchId"));
            if (batchId == null || batchId.isBlank()) batchId = "b" + sequence.incrementAndGet();
            String proposalId = "p" + sequence.incrementAndGet();
            Pending entry = new Pending(proposalId, batchId, proposal, caseKey, refusalSupported,
                    Instant.now().toString());
            pending.put(proposalId, entry);
            batches.computeIfAbsent(batchId, ignored -> new ArrayList<>()).add(proposalId);
            Map<String, Object> result = proposalMap(entry);
            result.put("applied", config.active(id) && entry.applied.size() == proposal.files().size());
            return result;
        } catch (ToolRefusal refused) {
            throw new ToolException(ToolException.INVALID_PARAMS, refused.getMessage());
        } catch (RuntimeException failed) {
            throw new ToolException(ToolException.INTERNAL_ERROR, failed.getClass().getSimpleName()
                    + ": " + failed.getMessage());
        }
    }

    /** Every pending proposal, grouped by the fs-meta/manual batch that produced it. */
    public List<Map<String, Object>> batches() {
        touchWebview();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> batch : batches.entrySet()) {
            List<Map<String, Object>> proposals = new ArrayList<>();
            String createdAt = null;
            boolean anyApplied = false;
            boolean allApplied = true;
            for (String proposalId : List.copyOf(batch.getValue())) {
                Pending entry = pending.get(proposalId);
                if (entry == null) continue;
                Map<String, Object> map = proposalMap(entry);
                proposals.add(map);
                createdAt = createdAt == null ? entry.createdAt : createdAt;
                anyApplied |= !entry.applied.isEmpty();
                allApplied &= entry.applied.size() == entry.proposal.files().size();
            }
            if (proposals.isEmpty()) {
                batches.remove(batch.getKey());
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("batchId", batch.getKey());
            item.put("createdAt", createdAt);
            item.put("proposals", proposals);
            item.put("anyApplied", anyApplied);
            item.put("allApplied", allApplied);
            result.add(item);
        }
        result.sort((left, right) -> String.valueOf(right.get("createdAt"))
                .compareTo(String.valueOf(left.get("createdAt"))));
        return result;
    }

    public Map<String, Object> apply(String proposalId) throws ToolException {
        Pending entry = required(proposalId);
        return applyInternal(entry);
    }

    public Map<String, Object> applyFile(String proposalId, String relativePath) throws ToolException {
        Pending entry = required(proposalId);
        FileProposal file = fileOf(entry, relativePath);
        Map<String, Object> result = applyFileInternal(entry, file);
        return Map.of("proposalId", proposalId, "path", relativePath,
                "files", List.of(result), "status", entry.applied.size()
                        == entry.proposal.files().size() ? "applied" : "partial");
    }

    public Map<String, Object> applyBatch(String batchId) throws ToolException {
        List<String> ids = batches.get(batchId);
        if (ids == null) throw ToolException.invalidParams("no such batch: " + batchId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : List.copyOf(ids)) {
            Pending entry = pending.get(id);
            if (entry != null) results.add(applyInternal(entry));
        }
        boolean anyApplied = false;
        boolean anyFailed = false;
        for (Map<String, Object> proposal : results) {
            Object files = proposal.get("files");
            if (!(files instanceof List<?> list)) continue;
            for (Object file : list) {
                if (!(file instanceof Map<?, ?> map)) continue;
                Object status = map.get("status");
                if ("APPLIED".equals(status) || "already-applied".equals(status)) anyApplied = true;
                else if ("REFUSED".equals(status) || "ERROR".equals(status)) anyFailed = true;
            }
        }
        String status = anyApplied ? (anyFailed ? "partial" : "applied") : "refused";
        return Map.of("batchId", batchId, "status", status, "proposals", results);
    }

    public Map<String, Object> revertFile(String proposalId, String relativePath) throws ToolException {
        Pending entry = required(proposalId);
        FileProposal file = fileOf(entry, relativePath);
        if (!entry.applied.remove(relativePath)) {
            return Map.of("proposalId", proposalId, "path", relativePath, "status", "not-applied");
        }
        try {
            ApplyResult result = coordinator.revert(file);
            if (!result.ok()) entry.applied.add(relativePath);
            else ownWrites.put(relativePath, WriteCoordinator.hash(file.beforeContent() == null
                    ? "" : file.beforeContent()));
            return Map.of("proposalId", proposalId, "path", relativePath,
                    "status", result.status().name(), "message", result.message());
        } catch (IOException failed) {
            entry.applied.add(relativePath);
            throw new ToolException(ToolException.INTERNAL_ERROR, failed.getMessage());
        }
    }

    public Map<String, Object> revertBatch(String batchId) throws ToolException {
        List<String> ids = batches.get(batchId);
        if (ids == null) throw ToolException.invalidParams("no such batch: " + batchId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : List.copyOf(ids)) {
            Pending entry = pending.get(id);
            if (entry == null) continue;
            results.add(revertInternal(entry));
        }
        return Map.of("batchId", batchId, "status", "reverted", "proposals", results);
    }

    /** Reject one proposal; {@code permanent} records the stable case in {@code tool-refusals.json}. */
    public Map<String, Object> reject(String proposalId, boolean permanent) throws ToolException {
        Pending entry = pending.get(proposalId);
        if (entry == null) throw ToolException.invalidParams("no such proposal: " + proposalId);
        Map<String, Object> result = revertInternal(entry);
        pending.remove(proposalId);
        List<String> ids = batches.get(entry.batchId);
        if (ids != null) ids.remove(proposalId);
        if (ids != null && ids.isEmpty()) batches.remove(entry.batchId);
        boolean recorded = permanent && entry.refusalSupported && !entry.caseKey.isBlank();
        if (recorded) refusals.add(entry.proposal.toolId(), entry.caseKey,
                entry.proposal.files().isEmpty() ? "" : relative(entry.proposal.files().get(0).path()),
                "rejected from the review UI");
        result.put("permanent", recorded);
        result.put("caseKey", entry.caseKey);
        return result;
    }

    private Map<String, Object> applyInternal(Pending entry) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (FileProposal file : entry.proposal.files()) {
            results.add(applyFileInternal(entry, file));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposalId", entry.proposalId);
        result.put("batchId", entry.batchId);
        result.put("status", entry.applied.size() == entry.proposal.files().size() ? "applied" : "partial");
        result.put("files", results);
        return result;
    }

    private Map<String, Object> applyFileInternal(Pending entry, FileProposal file) {
        String relative = relative(file.path());
        if (entry.applied.contains(relative)) {
            return Map.of("path", relative, "status", "already-applied");
        }
        try {
            ApplyResult result = coordinator.apply(file);
            if (result.ok()) {
                entry.applied.add(relative);
                ownWrites.put(relative, file.afterHash());
                editorNotifier.changed(file.path());
            }
            return Map.of("path", relative, "status", result.status().name(),
                    "message", result.message());
        } catch (IOException failed) {
            return Map.of("path", relative, "status", "ERROR", "message", String.valueOf(failed.getMessage()));
        }
    }

    private Map<String, Object> revertInternal(Pending entry) throws ToolException {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String relative : List.copyOf(entry.applied)) {
            FileProposal file = fileOf(entry, relative);
            try {
                ApplyResult result = coordinator.revert(file);
                if (result.ok()) {
                    entry.applied.remove(relative);
                    ownWrites.put(relative, WriteCoordinator.hash(file.beforeContent() == null
                            ? "" : file.beforeContent()));
                    editorNotifier.changed(file.path());
                }
                results.add(Map.of("path", relative, "status", result.status().name(),
                        "message", result.message()));
            } catch (IOException failed) {
                results.add(Map.of("path", relative, "status", "ERROR",
                        "message", String.valueOf(failed.getMessage())));
            }
        }
        return new LinkedHashMap<>(Map.of("proposalId", entry.proposalId, "status",
                results.isEmpty() ? "discarded" : "reverted", "files", results));
    }

    private Pending required(String proposalId) throws ToolException {
        Pending entry = pending.get(proposalId);
        if (entry == null) throw ToolException.invalidParams("no such proposal: " + proposalId);
        return entry;
    }

    private FileProposal fileOf(Pending entry, String relativePath) throws ToolException {
        for (FileProposal file : entry.proposal.files()) {
            if (relative(file.path()).equals(relativePath)) return file;
        }
        throw ToolException.invalidParams("no such file in proposal: " + relativePath);
    }

    private Map<String, Object> proposalMap(Pending entry) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (FileProposal file : entry.proposal.files()) {
            String relative = relative(file.path());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", relative);
            item.put("changeType", file.changeType().name());
            item.put("before", file.beforeContent() == null ? "" : file.beforeContent());
            item.put("after", file.afterContent() == null ? "" : file.afterContent());
            item.put("idempotencyKey", file.idempotencyKey());
            item.put("applied", entry.applied.contains(relative));
            files.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposalId", entry.proposalId);
        result.put("batchId", entry.batchId);
        result.put("createdAt", entry.createdAt);
        result.put("tool", entry.proposal.toolId());
        result.put("title", entry.proposal.title());
        result.put("version", entry.proposal.version());
        result.put("caseKey", entry.caseKey);
        result.put("refusalSupported", entry.refusalSupported);
        result.put("mode", config.mode(entry.proposal.toolId()));
        result.put("files", files);
        result.put("metadata", entry.proposal.metadata());
        return result;
    }

    private String relative(Path path) {
        Path absolute = path.isAbsolute() ? path : root.resolve(path);
        try {
            return root.relativize(absolute.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException outside) {
            return path.toString().replace('\\', '/');
        }
    }

    private static int annotationLine(String source, String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return -1;
        String needle = "@" + simpleName;
        int index = source.indexOf(needle);
        while (index >= 0) {
            int after = index + needle.length();
            if (after >= source.length() || !Character.isJavaIdentifierPart(source.charAt(after))) {
                int line = 1;
                for (int i = 0; i < index; i++) if (source.charAt(i) == '\n') line++;
                return line;
            }
            index = source.indexOf(needle, index + 1);
        }
        return -1;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static final class Pending {
        final String proposalId;
        final String batchId;
        final ToolProposal proposal;
        final String caseKey;
        final boolean refusalSupported;
        final String createdAt;
        final Set<String> applied = new LinkedHashSet<>();

        Pending(String proposalId, String batchId, ToolProposal proposal, String caseKey,
                boolean refusalSupported, String createdAt) {
            this.proposalId = proposalId;
            this.batchId = batchId;
            this.proposal = proposal;
            this.caseKey = caseKey;
            this.refusalSupported = refusalSupported;
            this.createdAt = createdAt;
        }
    }
}
